package app.pairline.android.link

import app.pairline.android.AppPrefs
import app.pairline.android.log.Log
import app.pairline.android.protocol.BridgeSession
import app.pairline.android.protocol.Messages
import app.pairline.android.protocol.SessionCallbacks
import app.pairline.android.protocol.StaticKey
import app.pairline.android.protocol.WifiHint
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

/** File transfers follow the Wi-Fi session (PROTOCOL.md, "File transfer"). */
interface WifiSessionListener {
    /** The established Wi-Fi session changed; null when there's none. */
    fun onWifiSession(token: Long?)

    /** An authentic `BULK` record from the Wi-Fi session [token]. Called on the link thread. */
    fun onBulk(token: Long, plaintext: ByteArray)
}

/**
 * Owns every connection's [BridgeSession] on one thread ("link thread"). At most one established session per lane:
 * Bluetooth (or the debug TCP link) and Wi-Fi (PROTOCOL.md, "Two sessions at once"). Events go to the Bluetooth
 * session when there is one, otherwise to Wi-Fi; replies go back on the session the request came from. Runs
 * keep-alive and handshake timeouts, and opens the media channel for each Wi-Fi session.
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

        /** Call audio inside this Bluetooth or debug TCP session (D-072); null on Wi-Fi. */
        var linkMedia: LinkMediaChannel? = null
        val wifi: Boolean get() = connection.kind.isWifi
    }

    private val entries = HashMap<LinkConnection, Entry>()

    /** The established session on each lane. Written on the link thread only. */
    private var radio: Entry? = null

    @Volatile
    private var wifi: Entry? = null

    /** The session events go to: [radio] when up, else [wifi]. */
    private var primary: Entry? = null
    private var tokens = 0L
    private val pingIds = AtomicInteger(1)
    private var watchdog: Job? = null

    var handler: MessageHandler? = null
    var wifiListener: WifiSessionListener? = null

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

    /** The Bluetooth (or debug TCP) session's token and its link media channel (D-072). Read on any thread. */
    @Volatile
    private var radioMedia: Pair<Long, LinkMediaChannel>? = null

    /**
     * The link media channel of the established Bluetooth or debug TCP session [token], or null (the session ended,
     * or [token] is a Wi-Fi session). Call audio with `via: "link"` uses it (D-072).
     */
    fun linkMedia(token: Long): LinkMediaChannel? = radioMedia?.takeIf { it.first == token }?.second

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
            radioMedia = null
            entries.clear()
            radio = null
            wifi = null
            refreshPrimary()
            setMedia(null)
            wifiListener?.onWifiSession(null)
        }
        updateState { LinkSnapshot() }
    }

    override fun onOpened(connection: LinkConnection) = post {
        Log.i(TAG, "${connection.label}: connected")
        if (connection.kind.isWifi) {
            // At most a few Wi-Fi connections may sit in the handshake at once; drop the oldest.
            val unfinished = entries.values
                .filter { it.wifi && it.session.state != BridgeSession.State.ESTABLISHED }
                .sortedBy { it.openedAt }
            if (unfinished.size >= MAX_UNFINISHED_WIFI) unfinished.first().session.close("too many Wi-Fi connections")
        }
        val callbacks = Callbacks(connection)
        val entry = Entry(
            connection,
            BridgeSession(identity(), pairing, callbacks, wifiChannels = connection.kind.isWifi),
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
        Log.i(TAG, "${connection.label}: disconnected ($reason)")
        laneLost(entry)
    }

    /** Sends an event to the iPhone on the session that carries events. Returns immediately; safe from any thread. */
    fun send(message: JSONObject) = post {
        primary?.session?.send(message)
    }

    /** Sends a reply on the session [token] if it's still established; otherwise drops it. */
    fun reply(token: Long, message: JSONObject) = post {
        val entry = established(token)
        if (entry == null) {
            Log.d(TAG, "dropped a reply for a session that has ended")
            return@post
        }
        entry.session.send(message)
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

    /** The user unpaired on this phone: tell the iPhone on every session, then forget it. */
    fun unpairLocally() = post {
        listOfNotNull(radio, wifi).forEach { e ->
            e.session.send(Messages.unpair())
            e.session.close("unpaired on this phone")
        }
        forgetPeer()
        Log.i(TAG, "unpaired on this phone")
    }

    /** The iPhone sent `unpair`. */
    fun onRemoteUnpair() = post {
        listOfNotNull(radio, wifi).forEach { it.session.close("unpaired by the iPhone") }
        forgetPeer()
        Log.i(TAG, "the iPhone unpaired")
    }

    private fun forgetPeer() {
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

    private fun established(token: Long): Entry? = listOfNotNull(radio, wifi).firstOrNull { it.token == token }

    /** A connection's session ended: take it off its lane and move events elsewhere if needed. */
    private fun laneLost(entry: Entry) {
        if (radio === entry) radio = null
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
            if (radioMedia?.second === channel) radioMedia = null
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
        val now = radio ?: wifi
        val changed = now !== primary
        primary = now
        val kinds = listOfNotNull(radio, wifi).map { it.connection.kind }.toSet()
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
                if (now - e.openedAt > HANDSHAKE_TIMEOUT_MS) e.session.close("handshake timed out")
                continue
            }
            val idle = now - e.lastReceived
            val pingAfter = if (e.wifi) WIFI_PING_AFTER_MS else PING_AFTER_MS
            val idleLimit = if (e.wifi) WIFI_IDLE_TIMEOUT_MS else IDLE_TIMEOUT_MS
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
            Log.i(TAG, "${connection.label}: closing ($reason)")
            connection.close()
            laneLost(entry)
        }

        override fun pinnedRemoteKey(): ByteArray? = peers.pinned()

        override fun onPaired() {
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
            // Newest session wins on its lane: an iPhone that reconnects may leave a stale connection behind.
            // Put the new one in place first, so closing the old one doesn't look like a disconnect.
            val old = if (e.wifi) wifi else radio
            if (e.wifi) {
                wifi = e
                wifiToken = e.token
            } else {
                radio = e
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
                    radioMedia = e.token to channel
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

    private companion object {
        const val TAG = "link"
        const val WATCHDOG_INTERVAL_MS = 5_000L
        const val HANDSHAKE_TIMEOUT_MS = 30_000L
        const val PING_AFTER_MS = 60_000L
        const val IDLE_TIMEOUT_MS = 180_000L

        /** Wi-Fi drops must be noticed fast so traffic falls back to Bluetooth (PROTOCOL.md, "Wi-Fi"). */
        const val WIFI_PING_AFTER_MS = 15_000L
        const val WIFI_IDLE_TIMEOUT_MS = 45_000L
        const val MAX_UNFINISHED_WIFI = 4
    }
}
