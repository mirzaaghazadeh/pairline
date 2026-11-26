package app.pairline.android.link

import app.pairline.android.AppPrefs
import app.pairline.android.log.Log
import app.pairline.android.protocol.BridgeSession
import app.pairline.android.protocol.Messages
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.SessionCallbacks
import app.pairline.android.protocol.StaticKey
import app.pairline.android.protocol.WifiHint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class BluetoothStatus { UNKNOWN, ON, OFF, NO_PERMISSION, UNSUPPORTED, ADVERTISE_FAILED }

/** The Wi-Fi link's listener (M5). */
enum class WifiLinkStatus {
    /** Switched off in Settings. */
    OFF,

    /** On, but the phone has no Wi-Fi network or hotspot with a local address right now. */
    NO_NETWORK,

    /** Listening on at least one local address. */
    LISTENING,
}

/** What the UI and the notification show about the link. */
data class LinkSnapshot(
    val running: Boolean = false,
    val bluetooth: BluetoothStatus = BluetoothStatus.UNKNOWN,
    val advertising: Boolean = false,
    /**
     * Android keeps refusing to advertise over Bluetooth (several failures in a row, none since the last success):
     * some older phones' chips can't, so the iPhone can't find this one over Bluetooth at all.
     */
    val cantAdvertise: Boolean = false,
    val connected: Boolean = false,
    /** The transport that carries events: Bluetooth (or debug TCP) when it's up, otherwise Wi-Fi. */
    val transport: TransportKind? = null,
    /** Every transport with an established session right now. */
    val transports: Set<TransportKind> = emptySet(),
    val connectedSince: Long = 0,
    val debugTcpListening: Boolean = false,
    val wifi: WifiLinkStatus = WifiLinkStatus.OFF,
    /** Where the iPhone can reach this bridge over Wi-Fi (pair link and `wifi.offer`); null without Wi-Fi. */
    val wifiHint: WifiHint? = null,
    /** The hotspot's band ("2.4", "5", "6") while the bridge hotspot runs, if Android says. */
    val hotspotBand: String? = null,
    val mediaOpen: Boolean = false,
)

/** Handles decrypted messages from the iPhone. Implemented by [MessageRouter]. */
interface MessageHandler {
    /**
     * A session finished its handshake. [carriesEvents]: it's now the session events go to (the first one up, or a
     * Bluetooth session that took over from Wi-Fi).
     */
    fun onEstablished(token: Long, kind: TransportKind, carriesEvents: Boolean)

    /** Events moved to another session that was already up (for example Bluetooth dropped and Wi-Fi stayed). */
    fun onEventSessionChanged(token: Long)

    /** A message from the session [token]; replies go back through [LinkManager.reply] with the same token. */
    fun onMessage(message: JSONObject, token: Long)

    /** No session is left. */
    fun onDisconnected()
}

/** Handles `file.*` messages (PROTOCOL.md, "File transfer"). Called on the link thread. */
interface FileMessageHandler {
    fun onMessage(message: JSONObject, token: Long)

    /** The iPhone's `device.info` arrived; it may accept files now. */
    fun onPeerCaps() {}
}

/**
 * Remote mode's mailbox in iCloud (D-092), the path next to the sessions: it carries events and replies while no
 * session is up. Implemented by `link.remote.RemoteLink`.
 */
interface MailboxRoute {
    /** Remote mode is on and running, so events can go into the mailbox. */
    val isActive: Boolean

    /** Queues an event or a reply for the mailbox. Called on the link thread; never blocks. */
    fun send(message: JSONObject)

    /** A session is now up ([up]) or the last one ended. Called on the link thread. */
    fun onSessionChanged(up: Boolean)

    /**
     * A `status` just went to the iPhone on a session, so the mailbox needn't carry it again: only a status that
     * differs from it is worth an iCloud record (and a push that wakes the iPhone). Called on the link thread.
     */
    fun statusDelivered(status: JSONObject) {}

    /** The pairing is forgotten: remote mode ends, its state and records are deleted. [farewell] still goes out first. */
    fun forget(farewell: JSONObject?)
}

/** File transfers follow the Wi-Fi session (PROTOCOL.md, "File transfer"). */
interface WifiSessionListener {
    /** The established Wi-Fi session changed; null when there's none. */
    fun onWifiSession(token: Long?)

    /** An authentic `BULK` record from the Wi-Fi session [token]. Called on the link thread. */
    fun onBulk(token: Long, plaintext: ByteArray)
}

/**
 * Owns every connection's [BridgeSession] on one thread ("link thread"). At most one established session per lane:
 * Bluetooth (or the debug TCP link), Wi-Fi (PROTOCOL.md, "Two sessions at once") and remote mode's direct connection
 * (D-092). Events go to the Bluetooth session when there is one, otherwise to Wi-Fi, otherwise to the direct session,
 * otherwise (remote mode) to the mailbox in iCloud; replies go back on the session the request came from. Requests
 * read from the mailbox carry [MAILBOX_TOKEN]. Runs keep-alive and handshake timeouts, and opens the media channel for
 * each Wi-Fi session. The direct session is a Wi-Fi session without media, bulk data or pairing: none of the Wi-Fi
 * lane's extras (files, call audio, internet sharing's peer address) ever reach a connection from the internet.
 */
class LinkManager(
    private val identity: () -> StaticKey,
    private val pairing: PairingManager,
    private val peers: PrefsPeerKeyStore,
    private val prefs: AppPrefs,
    private val clock: () -> Long = System::currentTimeMillis,
) : ConnectionListener {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pairline-link") }
    private val dispatcher = executor.asCoroutineDispatcher()
    val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> Log.e(TAG, "link task failed", e) },
    )

    private class Entry(val connection: LinkConnection, val session: BridgeSession, val openedAt: Long) {
        var lastReceived: Long = openedAt
        var lastPingSent: Long = 0
        var token: Long = 0
        var media: MediaEndpoint? = null

        /** Call audio inside this Bluetooth, debug TCP or direct session (D-072, D-092); null on Wi-Fi. */
        var linkMedia: LinkMediaChannel? = null
        val wifi: Boolean get() = connection.kind.isWifi

        /** A connection from the internet that hasn't authenticated: counted by the listener, not logged here. */
        val quiet: Boolean get() = connection.kind == TransportKind.DIRECT && token == 0L
    }

    private val entries = HashMap<LinkConnection, Entry>()

    /** The established session on each lane. Written on the link thread only. */
    private var radio: Entry? = null

    @Volatile
    private var wifi: Entry? = null

    private var direct: Entry? = null

    /** The session events go to: [radio] when up, else [wifi], else [direct]. */
    private var primary: Entry? = null
    private var tokens = 0L
    private val pingIds = AtomicInteger(1)
    private var watchdog: Job? = null

    var handler: MessageHandler? = null
    var wifiListener: WifiSessionListener? = null

    /** Remote mode's mailbox (D-092); set once by AppGraph. */
    var remote: MailboxRoute? = null

    /** The iPhone listed `files` in its `device.info` caps (M5). */
    @Volatile
    var peerAcceptsFiles: Boolean = false

    /** The iPhone listed `ring` in its `device.info` caps, so `remote.ring` reaches it (D-084). */
    @Volatile
    var peerCanRing: Boolean = false

    private val _state = MutableStateFlow(LinkSnapshot())
    val state: StateFlow<LinkSnapshot> = _state

    private val _media = MutableStateFlow<MediaChannel?>(null)

    /**
     * The open media channel of the current Wi-Fi session, or null (no Wi-Fi session, or no authentic datagram from
     * the iPhone yet). Call audio (M6) collects this to learn when the channel opens and closes.
     */
    val media: StateFlow<MediaChannel?> = _media

    /**
     * The link media channel of each established Bluetooth, debug TCP or direct session, by token (D-072; remote mode's
     * direct connection, D-092). Written on the link thread, read on any thread.
     */
    private val linkMedias = ConcurrentHashMap<Long, LinkMediaChannel>()

    /**
     * The link media channel of the established Bluetooth, debug TCP or direct session [token], or null (the session
     * ended, or [token] is a Wi-Fi session). Call audio with `via: "link"` uses it (D-072, D-092).
     */
    fun linkMedia(token: Long): LinkMediaChannel? = linkMedias[token]

    /** A session's link media channel closed with it. Called on the link thread with the session's token. */
    var onLinkMediaClosed: ((Long) -> Unit)? = null

    fun updateState(transform: (LinkSnapshot) -> LinkSnapshot) {
        synchronized(_state) { _state.value = transform(_state.value) }
    }

    fun start() {
        updateState { it.copy(running = true) }
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                checkTimers()
            }
        }
    }

    fun stop() {
        watchdog?.cancel()
        watchdog = null
        post {
            entries.keys.toList().forEach { it.close() }
            entries.values.forEach { it.media?.close() }
            entries.values.forEach { e ->
                e.linkMedia?.let { channel ->
                    channel.close()
                    onLinkMediaClosed?.invoke(e.token)
                }
            }
            linkMedias.clear()
            entries.clear()
            radio = null
            wifi = null
            direct = null
            refreshPrimary()
            setMedia(null)
            wifiListener?.onWifiSession(null)
        }
        updateState { LinkSnapshot() }
    }

    override fun onOpened(connection: LinkConnection) = post {
        val kind = connection.kind
        if (kind != TransportKind.DIRECT) Log.i(TAG, "${connection.label}: connected")
        if (kind.isWifi || kind == TransportKind.DIRECT) {
            // At most a few Wi-Fi (or direct) connections may sit in the handshake at once; drop the oldest.
            val unfinished = entries.values
                .filter { it.connection.kind == kind && it.session.state != BridgeSession.State.ESTABLISHED }
                .sortedBy { it.openedAt }
            if (unfinished.size >= MAX_UNFINISHED_WIFI) unfinished.first().session.close("too many connections")
        }
        val callbacks = Callbacks(connection)
        val entry = Entry(
            connection,
            BridgeSession(
                identity(),
                pairing,
                callbacks,
                wifiChannels = kind.isWifi,
                // Pairing needs the QR code or an invitation, never a connection from the internet (D-092).
                allowPairing = kind != TransportKind.DIRECT,
            ),
            clock(),
        )
        callbacks.entry = entry
        entries[connection] = entry
    }

    override fun onFrame(connection: LinkConnection, frame: ByteArray) = post {
        try {
            val entry = entries[connection] ?: return@post
            entry.lastReceived = clock()
            entry.session.onFrame(frame)
        } finally {
            connection.frameProcessed()
        }
    }

    override fun onClosed(connection: LinkConnection, reason: String) = post {
        val entry = entries.remove(connection) ?: return@post
        entry.session.close(reason)
        if (!entry.quiet) Log.i(TAG, "${connection.label}: disconnected ($reason)")
        laneLost(entry)
    }

    /**
     * Sends an event to the iPhone on the session that carries events, or into the mailbox while none is up and
     * remote mode is on. Returns immediately; safe from any thread.
     */
    fun send(message: JSONObject) = post {
        sendOnEventPath(message)
    }

    /**
     * Sends a reply on the session [token] if it's still established; otherwise drops it. A reply to a request from
     * the mailbox ([MAILBOX_TOKEN]) goes on the event session if one is up now, otherwise back into the mailbox.
     */
    fun reply(token: Long, message: JSONObject) = post {
        if (token == MAILBOX_TOKEN) {
            sendOnEventPath(message)
            return@post
        }
        val entry = established(token)
        if (entry == null) {
            Log.d(TAG, "dropped a reply for a session that has ended")
            return@post
        }
        entry.session.send(message)
        noteStatus(message)
    }

    /** Sends one `BULK` record on the Wi-Fi session [token]. [onSent] gets false if it couldn't be queued. */
    fun sendBulk(token: Long, plaintext: ByteArray, onSent: (Boolean) -> Unit) = post {
        val entry = wifi?.takeIf { it.token == token }
        val frame = entry?.session?.sealBulk(plaintext)
        if (entry == null || frame == null) {
            onSent(false)
            return@post
        }
        entry.connection.send(frame) { onSent(true) }
    }

    /** The token of the established Wi-Fi session, or null. Read on any thread. */
    @Volatile
    var wifiToken: Long? = null
        private set

    /** The paired iPhone's address on the established Wi-Fi session (authenticated by the handshake), or null. */
    val wifiPeerAddress: java.net.InetAddress? get() = wifi?.connection?.peerAddress

    val isConnected: Boolean get() = _state.value.connected

    /** Events reach the iPhone: a session is up, or remote mode's mailbox takes them. */
    val canSendEvents: Boolean get() = isConnected || remote?.isActive == true

    /** The user unpaired on this phone: tell the iPhone on every session (or through the mailbox), then forget it. */
    fun unpairLocally() = post {
        val sessions = listOfNotNull(radio, wifi, direct)
        sessions.forEach { e ->
            e.session.send(Messages.unpair())
            e.session.close("unpaired on this phone")
        }
        forgetPeer(farewell = if (sessions.isEmpty()) Messages.unpair() else null)
        Log.i(TAG, "unpaired on this phone")
    }

    /** The iPhone sent `unpair`. */
    fun onRemoteUnpair() = post {
        listOfNotNull(radio, wifi, direct).forEach { it.session.close("unpaired by the iPhone") }
        forgetPeer(farewell = null)
        Log.i(TAG, "the iPhone unpaired")
    }

    /**
     * Remote pairing (D-092): the user confirmed the codes, so [remotePublicKey] is pinned now and replaces any paired
     * iPhone, like a pairing from the QR code. Safe from any thread; the key is on disk when this returns.
     */
    fun pairedRemotely(remotePublicKey: ByteArray) {
        remote?.forget(farewell = null)
        pairing.onPaired(remotePublicKey)
        post {
            entries.values.toList().forEach { it.session.close("another iPhone paired") }
            prefs.clearPeer()
            prefs.pairedAt = clock()
            peerAcceptsFiles = false
        }
    }

    /** A message read from the mailbox: handled like one from a session, with [MAILBOX_TOKEN]. */
    fun deliverFromMailbox(message: JSONObject) = post {
        try {
            handler?.onMessage(message, MAILBOX_TOKEN)
        } catch (e: Exception) {
            Log.e(TAG, "handling a message from the mailbox failed", e)
        }
    }

    private fun sendOnEventPath(message: JSONObject) {
        val session = primary?.session
        if (session != null) {
            session.send(message)
            noteStatus(message)
        } else {
            remote?.takeIf { it.isActive }?.send(message)
        }
    }

    /** A status that went out on a session: the mailbox remembers it, so it doesn't send the same one again. */
    private fun noteStatus(message: JSONObject) {
        if (message.optString("t") == Msg.STATUS) remote?.statusDelivered(message)
    }

    private fun forgetPeer(farewell: JSONObject?) {
        // Remote mode first: its farewell is sealed to the key that's about to be forgotten.
        remote?.forget(farewell)
        peers.clear()
        prefs.clearPeer()
        pairing.resetOutcome()
        peerAcceptsFiles = false
        peerCanRing = false
    }

    /** Runs [block] on the link thread, where messages and bulk data are handled. */
    fun runOnLinkThread(block: () -> Unit) = post(block)

    private fun post(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "link error", e)
            }
        }
    }

    private fun established(token: Long): Entry? = listOfNotNull(radio, wifi, direct).firstOrNull { it.token == token }

    /** A connection's session ended: take it off its lane and move events elsewhere if needed. */
    private fun laneLost(entry: Entry) {
        if (radio === entry) radio = null
        if (direct === entry) direct = null
        if (wifi === entry) {
            wifi = null
            wifiToken = null
            wifiListener?.onWifiSession(null)
        }
        entry.media?.let { endpoint ->
            endpoint.close()
            if (_media.value === endpoint) setMedia(null)
        }
        entry.media = null
        entry.linkMedia?.let { channel ->
            channel.close()
            linkMedias.remove(entry.token, channel)
            onLinkMediaClosed?.invoke(entry.token)
        }
        entry.linkMedia = null
        val previous = primary
        if (refreshPrimary()) {
            primary?.let { now ->
                Log.i(TAG, "${now.connection.label}: now carries events")
                handler?.onEventSessionChanged(now.token)
            }
        }
        if (previous === entry && primary == null) {
            prefs.lastSeen = clock()
            handler?.onDisconnected()
        }
    }

    /** Recomputes [primary] and the snapshot. Returns true if the event session changed. */
    private fun refreshPrimary(): Boolean {
        val now = radio ?: wifi ?: direct
        val changed = now !== primary
        val wasUp = primary != null
        primary = now
        if (wasUp != (now != null)) remote?.onSessionChanged(now != null)
        val kinds = listOfNotNull(radio, wifi, direct).map { it.connection.kind }.toSet()
        updateState {
            it.copy(
                connected = now != null,
                transport = now?.connection?.kind,
                transports = kinds,
                connectedSince = when {
                    now == null -> 0
                    it.connectedSince == 0L -> clock()
                    else -> it.connectedSince
                },
                mediaOpen = _media.value?.isOpen == true,
            )
        }
        if (now != null) prefs.lastSeen = clock()
        return changed
    }

    private fun setMedia(channel: MediaChannel?) {
        _media.value = channel
        updateState { it.copy(mediaOpen = channel?.isOpen == true) }
    }

    private fun checkTimers() {
        val now = clock()
        for (e in entries.values.toList()) {
            val established = e.session.state == BridgeSession.State.ESTABLISHED
            if (!established) {
                val limit = if (e.connection.kind == TransportKind.DIRECT) DIRECT_HANDSHAKE_TIMEOUT_MS else HANDSHAKE_TIMEOUT_MS
                if (now - e.openedAt > limit) e.session.close("handshake timed out")
                continue
            }
            val idle = now - e.lastReceived
            val fast = e.connection.kind.fastKeepAlive
            val pingAfter = if (fast) WIFI_PING_AFTER_MS else PING_AFTER_MS
            val idleLimit = if (fast) WIFI_IDLE_TIMEOUT_MS else IDLE_TIMEOUT_MS
            when {
                idle > idleLimit -> e.session.close("nothing received for ${idle / 1000} s")
                idle > pingAfter && now - e.lastPingSent > pingAfter -> {
                    e.lastPingSent = now
                    e.session.send(Messages.ping(pingIds.getAndIncrement().toLong()))
                }
            }
            if (e === primary) prefs.lastSeen = e.lastReceived
        }
    }

    /** Opens the media channel for a new Wi-Fi session and tells the iPhone its port (`wifi.media`). */
    private fun openMedia(entry: Entry) {
        val connection = entry.connection as? MediaCapable
        val keys = entry.session.takeMediaKeys()
        val endpoint = if (connection != null && keys != null) connection.openMedia(keys.first, keys.second) else null
        if (endpoint == null) keys?.let { (a, b) -> a.fill(0); b.fill(0) }
        entry.media = endpoint
        endpoint?.onOpened = {
            post {
                if (wifi === entry && entry.media === endpoint) {
                    Log.i(TAG, "${entry.connection.label}: media channel open")
                    setMedia(endpoint)
                }
            }
        }
        entry.session.send(Messages.wifiMedia(endpoint?.port))
    }

    private inner class Callbacks(private val connection: LinkConnection) : SessionCallbacks {
        lateinit var entry: Entry

        override fun sendFrame(frame: ByteArray) = connection.send(frame)

        override fun close(reason: String) {
            if (!entry.quiet) Log.i(TAG, "${connection.label}: closing ($reason)")
            connection.close()
            laneLost(entry)
        }

        override fun pinnedRemoteKey(): ByteArray? = peers.pinned()

        override fun onPaired() {
            // Remote mode belonged to the previous pairing; the iPhone turns it on again with remote.config.
            remote?.forget(farewell = null)
            prefs.clearPeer()
            prefs.pairedAt = clock()
            peerAcceptsFiles = false
            peerCanRing = false
            // A new iPhone replaces the old one: drop every other connection, including an established one.
            entries.filterKeys { it != connection }.values.toList().forEach { it.session.close("another iPhone paired") }
        }

        override fun onEstablished() {
            val e = entry
            e.token = ++tokens
            connection.onAuthenticated()
            // Newest session wins on its lane: an iPhone that reconnects may leave a stale connection behind.
            // Put the new one in place first, so closing the old one doesn't look like a disconnect.
            val old: Entry?
            when {
                e.wifi -> {
                    old = wifi
                    wifi = e
                    wifiToken = e.token
                }
                connection.kind == TransportKind.DIRECT -> {
                    old = direct
                    direct = e
                }
                else -> {
                    old = radio
                    radio = e
                }
            }
            val carriesEvents = refreshPrimary() && primary === e
            old?.takeIf { it !== e }?.session?.close("replaced by a new connection")
            Log.i(TAG, "${connection.label}: session established")
            if (e.wifi) {
                openMedia(e)
                wifiListener?.onWifiSession(e.token)
            } else {
                e.session.takeLinkMediaSealer()?.let { sealer ->
                    val channel = LinkMediaChannel(connection, sealer)
                    e.linkMedia = channel
                    linkMedias[e.token] = channel
                }
            }
            handler?.onEstablished(e.token, connection.kind, carriesEvents)
        }

        override fun onMessage(message: JSONObject) {
            // A bug in a handler is ours, not the iPhone's: log it and keep the session.
            try {
                handler?.onMessage(message, entry.token)
            } catch (e: Exception) {
                Log.e(TAG, "handling a message failed", e)
            }
        }

        override fun onBulk(plaintext: ByteArray) {
            try {
                wifiListener?.onBulk(entry.token, plaintext)
            } catch (e: Exception) {
                Log.e(TAG, "handling bulk data failed", e)
            }
        }

        override fun onLinkMedia(plaintext: ByteArray) {
            entry.lastReceived = clock()
            try {
                entry.linkMedia?.receive(plaintext)
            } catch (e: Exception) {
                Log.e(TAG, "handling call audio failed", e)
            }
        }
    }

    companion object {
        /** The "session" of messages read from the mailbox in iCloud (remote mode, D-092). Session tokens are positive. */
        const val MAILBOX_TOKEN = -1L

        private const val TAG = "link"
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val HANDSHAKE_TIMEOUT_MS = 30_000L

        /** The direct connection faces the internet: 10 s (PROTOCOL.md, "Direct connection"); its listener enforces it too. */
        private const val DIRECT_HANDSHAKE_TIMEOUT_MS = 10_000L
        private const val PING_AFTER_MS = 60_000L
        private const val IDLE_TIMEOUT_MS = 180_000L

        /** Wi-Fi drops must be noticed fast so traffic falls back to Bluetooth (PROTOCOL.md, "Wi-Fi"). */
        private const val WIFI_PING_AFTER_MS = 15_000L
        private const val WIFI_IDLE_TIMEOUT_MS = 45_000L
        private const val MAX_UNFINISHED_WIFI = 4
    }
}
