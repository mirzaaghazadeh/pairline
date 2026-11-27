package app.pairline.android.link.remote.relay

import app.pairline.android.link.ConnectionListener
import app.pairline.android.link.remote.DropLog
import app.pairline.android.link.remote.GatedConnection
import app.pairline.android.log.Log
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Why the bridge isn't waiting at the user's own server: `remote.state`'s `relayReason` (PROTOCOL.md, D-106). */
enum class RelayReason(val wire: String) {
    /** No TCP or TLS connection (or the server closed it). */
    UNREACHABLE("unreachable"),

    /** The server's certificate doesn't match the fingerprint in its link. */
    PIN("pin"),

    /** The server refused the access key (`0x03`). */
    KEY("key"),

    /** The server doesn't speak this version, or didn't understand the preamble (`0x04`). */
    VERSION("version"),

    /** The server is full (`0x05`). */
    FULL("full"),
}

/** What the screen and `remote.state` show about the user's own server. */
data class RelayStatus(
    /** A server is set (and remote mode is on). */
    val set: Boolean = false,
    /** Waiting there for the iPhone, or a session runs through it. */
    val up: Boolean = false,
    /** Why it isn't up; null while the first attempt runs. */
    val reason: RelayReason? = null,
) {
    /** `remote.state`'s `relay`: null, `up` or `down`. */
    val wire: String? get() = when {
        !set -> null
        up -> "up"
        else -> "down"
    }

    /** `remote.state`'s `relayReason`: only when down. */
    val reasonWire: String? get() = reason?.wire?.takeIf { set && !up }
}

/**
 * The bridge's side of the user's own server (D-106; PROTOCOL.md "Your own server (relay, D-106)"). While remote mode is
 * on and a relay is set ([config]), it keeps one **waiting connection** there: TCP and TLS out to the server (pinned to
 * the certificate in its link, [RelaySockets]), the 54-byte preamble with role `B`, then only reading single status
 * bytes. `0x00` is a keep-alive (nothing for [keepAliveTimeoutMs] and the connection is replaced); `0x01` means the
 * iPhone joined, and the socket becomes a [GatedConnection] (a `TransportKind.DIRECT` session, gated exactly like one
 * on the public listener) while a new waiting connection opens at once. A certificate that doesn't match, or a refused
 * key, stops it until the relay is set again or the network changes; other failures retry after 2 s, doubling to a
 * minute. Any network will do: it connects out, so mobile data works too.
 *
 * One thread runs it all, blocking. It never logs the server's name or address, its key, fingerprint or the rendezvous
 * id, only state changes.
 */
class RelayClient(
    private val listener: ConnectionListener,
    /** The relay to wait at: null while remote mode is off or no relay is set. */
    private val config: () -> RelayConfig?,
    private val sockets: RelaySockets = PinnedTlsSockets,
    private val clock: () -> Long = System::currentTimeMillis,
    private val keepAliveTimeoutMs: Int = KEEPALIVE_TIMEOUT_MS,
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
    private val firstRetryMs: Long = FIRST_RETRY_MS,
) {
    private val lock = Any()

    /** Wakes the loop from [pause]; one slot, so wake-ups while it runs fold into one. */
    private val wake = ArrayBlockingQueue<Unit>(1)
    private var thread: Thread? = null

    // Inside synchronized(lock) only.
    private var running = false
    private var generation = 0L
    private var stoppedAt = -1L
    private var retryAt = 0L
    private var failures = 0
    private var current: Socket? = null
    private var waiting = false
    private var reason: RelayReason? = null
    private var lastConfig: RelayConfig? = null
    private val pending = LinkedHashSet<GatedConnection>()
    private val sessions = HashSet<GatedConnection>()

    private val labels = AtomicInteger(1)
    private val drops = DropLog(TAG, "relay connection(s)")

    private val _status = MutableStateFlow(RelayStatus())
    val status: StateFlow<RelayStatus> = _status

    /** Told each time [status] changes (DirectLink sends `remote.state`). Any thread. */
    var onChange: ((RelayStatus) -> Unit)? = null

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            thread = Thread(::loop, "pairline-relay").apply { isDaemon = true; start() }
        }
    }

    fun stop() {
        val socket = synchronized(lock) {
            running = false
            generation++
            wake.offer(Unit)
            current.also { current = null }
        }
        socket?.let { runCatching { it.close() } }
    }

    /**
     * The relay was set, changed or removed, or remote mode went on or off: look again at once. A waiting connection
     * to the same relay is kept.
     */
    fun reconfigure() {
        val wanted = config()
        val socket = synchronized(lock) {
            generation++
            if (wanted != lastConfig) {
                failures = 0
                retryAt = 0
                reason = null
                stoppedAt = -1
            }
            wake.offer(Unit)
            if (wanted != lastConfig) current.also { current = null } else null
        }
        socket?.let { runCatching { it.close() } }
    }

    /**
     * The phone's default network changed (Wi-Fi to mobile data, another Wi-Fi): the waiting connection is replaced,
     * and a stop (a wrong certificate or key) is tried once more, since a captive network can show another one.
     */
    fun networkChanged() {
        val socket = synchronized(lock) {
            if (!running) return
            generation++
            failures = 0
            retryAt = 0
            stoppedAt = -1
            wake.offer(Unit)
            current.also { current = null }
        }
        if (socket != null) Log.i(TAG, "the network changed; reconnecting to your own server")
        socket?.let { runCatching { it.close() } }
    }

    private fun loop() {
        while (true) {
            val gen = synchronized(lock) {
                if (!running) return
                generation
            }
            // Read outside the lock: it decrypts from this phone's storage.
            val cfg = config()
            val changed = synchronized(lock) {
                (cfg != lastConfig).also { lastConfig = cfg }
            }
            if (changed) Log.i(TAG, if (cfg == null) "no own server" else "own server set; connecting")
            if (cfg == null) {
                publish()
                pause(gen, 0)
                continue
            }
            val wait = synchronized(lock) {
                when {
                    generation != gen -> -2L
                    stoppedAt == gen -> 0L
                    retryAt > clock() -> retryAt - clock()
                    else -> -1L
                }
            }
            if (wait == -2L) continue
            if (wait >= 0) {
                publish()
                pause(gen, wait)
                continue
            }
            publish()
            val outcome = attempt(cfg, gen)
            val stillCurrent = synchronized(lock) {
                if (generation != gen) return@synchronized false
                when (outcome) {
                    is Outcome.Joined -> {
                        failures = 0
                        reason = null
                    }
                    is Outcome.Failed -> {
                        if (outcome.heard) failures = 0
                        reason = outcome.reason
                        if (outcome.stop) {
                            stoppedAt = gen
                        } else {
                            failures++
                            retryAt = clock() + backoff(failures, firstRetryMs)
                        }
                    }
                }
                true
            }
            // A connection closed because the relay or the network changed isn't a failure worth a line.
            if (stillCurrent && outcome is Outcome.Failed) note(outcome)
        }
    }

    private sealed interface Outcome {
        data object Joined : Outcome

        /** [heard]: the server accepted the preamble first (a keep-alive came), so it was up until now. */
        data class Failed(val reason: RelayReason, val stop: Boolean = false, val heard: Boolean = false) : Outcome
    }

    private var lastLogged: RelayReason? = null

    private fun note(outcome: Outcome.Failed) {
        if (outcome.reason == lastLogged && !outcome.heard) return
        lastLogged = outcome.reason
        Log.i(TAG, when (outcome.reason) {
            RelayReason.UNREACHABLE -> if (outcome.heard) "lost the connection to your own server; reconnecting" else "can't reach your own server; retrying"
            RelayReason.PIN -> "your own server's certificate doesn't match its link; stopped until it's set again"
            RelayReason.KEY -> "your own server refused the access key; stopped until it's set again"
            RelayReason.VERSION -> "your own server doesn't speak this version; retrying"
            RelayReason.FULL -> "your own server is full; retrying"
        })
    }

    /** One waiting connection, from connecting until it's joined to the iPhone or fails. */
    private fun attempt(cfg: RelayConfig, gen: Long): Outcome {
        val socket = try {
            sockets.open(cfg)
        } catch (_: PinMismatchException) {
            return Outcome.Failed(RelayReason.PIN, stop = true)
        } catch (_: Exception) {
            return Outcome.Failed(RelayReason.UNREACHABLE)
        }
        val keep = synchronized(lock) {
            (generation == gen && running).also { if (it) current = socket }
        }
        if (!keep) {
            runCatching { socket.close() }
            return Outcome.Failed(RelayReason.UNREACHABLE)
        }
        var handedOver = false
        var heard = false
        try {
            socket.soTimeout = keepAliveTimeoutMs
            socket.getOutputStream().apply {
                write(cfg.preamble(RelayConfig.ROLE_BRIDGE))
                flush()
            }
            setWaiting(true)
            // Byte by byte from the TLS stream itself (no buffer of our own): what follows 0x01 is the iPhone's.
            val input = socket.getInputStream()
            while (true) {
                when (input.read()) {
                    -1 -> return Outcome.Failed(RelayReason.UNREACHABLE, heard = heard)
                    STATUS_KEEPALIVE -> {
                        if (!heard) {
                            heard = true
                            resetAfterSuccess()
                        }
                    }
                    STATUS_JOINED -> {
                        handedOver = handOver(socket)
                        return Outcome.Joined
                    }
                    STATUS_WRONG_KEY -> return Outcome.Failed(RelayReason.KEY, stop = true)
                    STATUS_BAD_VERSION -> return Outcome.Failed(RelayReason.VERSION, heard = heard)
                    STATUS_FULL -> return Outcome.Failed(RelayReason.FULL, heard = heard)
                    // 0x02 is for the iPhone; anything else isn't in the protocol.
                    else -> return Outcome.Failed(RelayReason.VERSION, heard = heard)
                }
            }
        } catch (_: SocketTimeoutException) {
            return Outcome.Failed(RelayReason.UNREACHABLE, heard = heard)
        } catch (_: IOException) {
            return Outcome.Failed(RelayReason.UNREACHABLE, heard = heard)
        } finally {
            synchronized(lock) {
                if (current === socket) current = null
                // Published by the loop once it knows why (or by the hand-over, which keeps it up).
                waiting = false
            }
            if (!handedOver) runCatching { socket.close() }
        }
    }

    private fun resetAfterSuccess() = synchronized(lock) {
        failures = 0
        reason = null
        lastLogged = null
    }

    /** The preamble went out on a pinned connection: waiting there (until a status byte says otherwise). */
    private fun setWaiting(on: Boolean) {
        synchronized(lock) {
            waiting = on
            if (on) reason = null
        }
        publish()
    }

    /**
     * The iPhone joined: the socket becomes a direct session through LinkManager, gated until the handshake. At most
     * [MAX_PENDING] such connections wait for their handshake (a new one pushes out the oldest), at most
     * [MAX_SESSIONS] in all.
     */
    private fun handOver(socket: Socket): Boolean {
        val now = clock()
        val connection = synchronized(lock) {
            if (sessions.size >= MAX_SESSIONS) {
                null
            } else {
                if (pending.size >= MAX_PENDING) pending.first().close()
                GatedConnection(
                    socket, "relay#${labels.getAndIncrement()}", now + handshakeTimeoutMs, listener, owner,
                    peerAddress = null, clock = clock,
                ).also {
                    pending += it
                    sessions += it
                }
            }
        }
        if (connection == null) {
            drops.note(now, "too many connections")
            return false
        }
        connection.start()
        publish()
        return true
    }

    private val owner = object : GatedConnection.Owner {
        override fun settled(connection: GatedConnection) {
            synchronized(lock) { pending.remove(connection) }
        }

        override fun ended(connection: GatedConnection) {
            val removed = synchronized(lock) { sessions.remove(connection) }
            if (removed) publish()
        }

        override fun dropped(reason: String) = drops.note(clock(), reason)
    }

    /** Waits [ms] (0: until woken) unless something changed since [gen]. A wake-up that came early just ends it. */
    private fun pause(gen: Long, ms: Long) {
        if (synchronized(lock) { generation != gen || !running }) return
        wake.poll(if (ms > 0) ms else Long.MAX_VALUE, TimeUnit.MILLISECONDS)
    }

    private fun publish() {
        val next = synchronized(lock) {
            val set = lastConfig != null && running
            RelayStatus(set = set, up = set && (waiting || sessions.isNotEmpty()), reason = if (set) reason else null)
        }
        if (next == _status.value) return
        _status.value = next
        onChange?.invoke(next)
    }

    companion object {
        private const val TAG = "relay"
        const val STATUS_KEEPALIVE = 0x00
        const val STATUS_JOINED = 0x01
        const val STATUS_NO_BRIDGE = 0x02
        const val STATUS_WRONG_KEY = 0x03
        const val STATUS_BAD_VERSION = 0x04
        const val STATUS_FULL = 0x05

        /** The relay sends a keep-alive every 30 s; three missed and the connection is replaced. */
        const val KEEPALIVE_TIMEOUT_MS = 90_000
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val FIRST_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 60_000L
        const val MAX_PENDING = 4
        const val MAX_SESSIONS = 8

        /** 2, 4, 8 … s, at most a minute. */
        fun backoff(failures: Int, first: Long = FIRST_RETRY_MS): Long =
            (first shl (failures - 1).coerceIn(0, 10)).coerceAtMost(MAX_RETRY_MS)
    }
}
