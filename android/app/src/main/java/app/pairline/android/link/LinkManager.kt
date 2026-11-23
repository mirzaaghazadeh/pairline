package app.pairline.android.link

import app.pairline.android.AppPrefs
import app.pairline.android.log.Log
import app.pairline.android.protocol.BridgeSession
import app.pairline.android.protocol.Messages
import app.pairline.android.protocol.SessionCallbacks
import app.pairline.android.protocol.StaticKey
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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

/** What the UI and the notification show about the link. */
data class LinkSnapshot(
    val running: Boolean = false,
    val bluetooth: BluetoothStatus = BluetoothStatus.UNKNOWN,
    val advertising: Boolean = false,
    val connected: Boolean = false,
    val transport: TransportKind? = null,
    val connectedSince: Long = 0,
    val debugTcpListening: Boolean = false,
)

/** Handles decrypted messages from the iPhone. Implemented by [MessageRouter]. */
interface MessageHandler {
    fun onEstablished()
    fun onMessage(message: JSONObject)
    fun onDisconnected()
}

/**
 * Owns every connection's [BridgeSession] on one thread ("link thread"), keeps one authenticated session
 * active, and runs keep-alive and handshake timeouts.
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
    val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private class Entry(val session: BridgeSession, val openedAt: Long) {
        var lastReceived: Long = openedAt
        var lastPingSent: Long = 0
    }

    private val entries = HashMap<LinkConnection, Entry>()
    private var active: LinkConnection? = null
    private val pingIds = AtomicInteger(1)
    private var watchdog: Job? = null

    var handler: MessageHandler? = null

    private val _state = MutableStateFlow(LinkSnapshot())
    val state: StateFlow<LinkSnapshot> = _state

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
            entries.clear()
            setActive(null)
        }
        updateState { LinkSnapshot() }
    }

    override fun onOpened(connection: LinkConnection) = post {
        Log.i(TAG, "${connection.label}: connected")
        entries[connection] = Entry(
            BridgeSession(identity(), pairing, Callbacks(connection)),
            clock(),
        )
    }

    override fun onFrame(connection: LinkConnection, frame: ByteArray) = post {
        val entry = entries[connection] ?: return@post
        entry.lastReceived = clock()
        entry.session.onFrame(frame)
    }

    override fun onClosed(connection: LinkConnection, reason: String) = post {
        val entry = entries.remove(connection) ?: return@post
        entry.session.close(reason)
        Log.i(TAG, "${connection.label}: disconnected ($reason)")
        if (active == connection) setActive(null)
    }

    /** Sends a message to the iPhone if a session is up. Returns immediately; safe from any thread. */
    fun send(message: JSONObject) = post {
        val c = active ?: return@post
        entries[c]?.session?.send(message)
    }

    val isConnected: Boolean get() = _state.value.connected

    /** The user unpaired on this phone: tell the iPhone if it's connected, then forget it. */
    fun unpairLocally() = post {
        active?.let { c ->
            entries[c]?.session?.send(Messages.unpair())
            entries[c]?.session?.close("unpaired on this phone")
        }
        forgetPeer()
        Log.i(TAG, "unpaired on this phone")
    }

    /** The iPhone sent `unpair`. */
    fun onRemoteUnpair() = post {
        active?.let { c -> entries[c]?.session?.close("unpaired by the iPhone") }
        forgetPeer()
        Log.i(TAG, "the iPhone unpaired")
    }

    private fun forgetPeer() {
        peers.clear()
        prefs.clearPeer()
        pairing.resetOutcome()
    }

    private fun post(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "link error", e)
            }
        }
    }

    private fun setActive(connection: LinkConnection?) {
        val previous = active
        active = connection
        if (connection != null) {
            updateState { it.copy(connected = true, transport = connection.kind, connectedSince = clock()) }
            prefs.lastSeen = clock()
        } else {
            updateState { it.copy(connected = false, transport = null, connectedSince = 0) }
            if (previous != null) {
                prefs.lastSeen = clock()
                handler?.onDisconnected()
            }
        }
    }

    private fun checkTimers() {
        val now = clock()
        for ((c, e) in entries.toList()) {
            val established = e.session.state == BridgeSession.State.ESTABLISHED
            if (!established) {
                if (now - e.openedAt > HANDSHAKE_TIMEOUT_MS) e.session.close("handshake timed out")
                continue
            }
            val idle = now - e.lastReceived
            when {
                idle > IDLE_TIMEOUT_MS -> e.session.close("nothing received for ${idle / 1000} s")
                idle > PING_AFTER_MS && now - e.lastPingSent > PING_AFTER_MS -> {
                    e.lastPingSent = now
                    e.session.send(Messages.ping(pingIds.getAndIncrement().toLong()))
                }
            }
            if (c == active) prefs.lastSeen = e.lastReceived
        }
    }

    private inner class Callbacks(private val connection: LinkConnection) : SessionCallbacks {
        override fun sendFrame(frame: ByteArray) = connection.send(frame)

        override fun close(reason: String) {
            Log.i(TAG, "${connection.label}: closing ($reason)")
            connection.close()
            if (active == connection) setActive(null)
        }

        override fun pinnedRemoteKey(): ByteArray? = peers.pinned()

        override fun onPaired() {
            prefs.clearPeer()
            prefs.pairedAt = clock()
            // A new iPhone replaces the old one: drop every other connection, including an established one.
            entries.filterKeys { it != connection }.values.toList().forEach { it.session.close("another iPhone paired") }
        }

        override fun onEstablished() {
            // Newest session wins: an iPhone that reconnects may leave a stale connection behind.
            active?.takeIf { it != connection }?.let { old ->
                entries[old]?.session?.close("replaced by a new connection")
            }
            Log.i(TAG, "${connection.label}: session established")
            setActive(connection)
            handler?.onEstablished()
        }

        override fun onMessage(message: JSONObject) {
            handler?.onMessage(message)
        }
    }

    private companion object {
        const val TAG = "link"
        const val WATCHDOG_INTERVAL_MS = 10_000L
        const val HANDSHAKE_TIMEOUT_MS = 30_000L
        const val PING_AFTER_MS = 60_000L
        const val IDLE_TIMEOUT_MS = 180_000L
    }
}
