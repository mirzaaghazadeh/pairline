package app.pairline.android.link.remote

import app.pairline.android.link.ConnectionListener
import app.pairline.android.log.Log
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Where the direct connection listens; [DirectListener] on the phone, a fake in tests. */
interface DirectServer {
    /** Listens on exactly [addresses] (closing the others); the port, the same on all of them, or null if none bound. */
    fun listen(addresses: List<InetAddress>): Int?

    /** Stops listening and drops every connection that hasn't completed the handshake. Established sessions stay. */
    fun close()
}

/**
 * Remote mode's public listener (D-092, PROTOCOL.md "Direct connection"): the one socket in the app that accepts
 * connections from any address, because the home router forwards the internet to it. Each connection is a
 * [GatedConnection] (shared with the user's own server, D-106): a `TransportKind.DIRECT` session through the
 * [ConnectionListener] (LinkManager) that, until the session handshake has authenticated the pinned iPhone, reads
 * exactly two frames and looks at nothing but their lengths and type bytes: a 100-byte `HELLO`, then the 25-byte
 * confirm `RECORD`. Anything else (pairing frames included) closes the connection. Also:
 * - a connection that hasn't completed the handshake within [handshakeTimeoutMs] (10 s) is closed;
 * - at most [MAX_PENDING] such connections; a new one pushes out the oldest;
 * - at most [AdmissionGate.PER_MINUTE] new connections per minute from one address (an IPv6 /64 counts as one);
 *   the rest are closed at once.
 * Connections from the internet are counted, not logged one by one, and their addresses never are.
 */
class DirectListener(
    private val listener: ConnectionListener,
    private val preferredPort: Int = PREFERRED_PORT,
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : DirectServer {

    private val lock = Any()
    private val servers = HashMap<InetAddress, ServerSocket>()
    private var port = 0
    private val gate = AdmissionGate()
    private val pending = LinkedHashSet<GatedConnection>()
    private val connections = ConcurrentHashMap.newKeySet<GatedConnection>()
    private val labels = AtomicInteger(1)
    private val drops = DropLog(TAG, "connection(s)")

    override fun listen(addresses: List<InetAddress>): Int? = synchronized(lock) {
        val wanted = addresses.toSet()
        for (address in servers.keys.filter { it !in wanted }) servers.remove(address)?.let { runCatching { it.close() } }
        if (servers.isEmpty()) port = 0
        for (address in wanted) {
            if (address in servers) continue
            val server = bind(address) ?: continue
            servers[address] = server
            Thread({ acceptLoop(server) }, "pairline-direct-accept").apply { isDaemon = true }.start()
        }
        if (servers.isEmpty()) null else port
    }

    override fun close() {
        val dropped = synchronized(lock) {
            servers.values.forEach { runCatching { it.close() } }
            servers.clear()
            port = 0
            pending.toList()
        }
        dropped.forEach { it.close() }
    }

    /** Every address shares one port: [preferredPort] when free, otherwise whatever the first bind got. */
    private fun bind(address: InetAddress): ServerSocket? {
        fun tryPort(p: Int): ServerSocket? = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, p), BACKLOG)
            }
        } catch (_: IOException) {
            null
        }
        if (port != 0) return tryPort(port)
        val server = tryPort(preferredPort) ?: tryPort(0) ?: return null
        port = server.localPort
        if (preferredPort != 0 && port != preferredPort) Log.i(TAG, "port $preferredPort is taken; using $port")
        return server
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            admit(socket)
        }
    }

    private fun admit(socket: Socket) {
        val now = clock()
        val peer = socket.inetAddress
        if (peer == null || !gate.admit(peer, now)) {
            runCatching { socket.close() }
            drops.note(now, "rate limit")
            return
        }
        val connection = synchronized(lock) {
            if (connections.size >= MAX_CONNECTIONS || servers.isEmpty()) null else {
                if (pending.size >= MAX_PENDING) pending.first().close()
                GatedConnection(
                    socket, "direct#${labels.getAndIncrement()}", now + handshakeTimeoutMs, listener, owner, peer, clock,
                ).also { pending += it; connections += it }
            }
        }
        if (connection == null) {
            runCatching { socket.close() }
            drops.note(now, "too many connections")
            return
        }
        connection.start()
    }

    private val owner = object : GatedConnection.Owner {
        override fun settled(connection: GatedConnection) {
            synchronized(lock) { pending.remove(connection) }
        }

        override fun ended(connection: GatedConnection) {
            connections.remove(connection)
        }

        override fun dropped(reason: String) = drops.note(clock(), reason)
    }

    companion object {
        private const val TAG = "direct"

        /** Next to the Wi-Fi link's 47820 (PROTOCOL.md leaves the number to the bridge; any free port works). */
        const val PREFERRED_PORT = 47830
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val MAX_PENDING = 4
        private const val MAX_CONNECTIONS = 8
        private const val BACKLOG = 8

        /** A length-prefixed `HELLO`: the type byte and the 99-byte hello body. */
        const val HELLO_FRAME = GatedConnection.HELLO_FRAME

        /** The confirm: the type byte and a record with an empty plaintext (counter and tag). */
        const val CONFIRM_FRAME = GatedConnection.CONFIRM_FRAME
    }
}

/**
 * At most [PER_MINUTE] new connections per minute from one address (PROTOCOL.md, "Direct connection"). An IPv6
 * /64 counts as one address, since one home or phone gets a whole /64. Remembers at most [MAX_TRACKED] addresses,
 * forgetting the least recently seen.
 */
class AdmissionGate(private val perMinute: Int = PER_MINUTE) {
    private val seen = object : LinkedHashMap<String, ArrayDeque<Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<Long>>?) = size > MAX_TRACKED
    }

    @Synchronized
    fun admit(address: InetAddress, now: Long): Boolean {
        val times = seen.getOrPut(key(address)) { ArrayDeque() }
        while (times.isNotEmpty() && times.first() <= now - WINDOW_MS) times.removeFirst()
        if (times.size >= perMinute) return false
        times.addLast(now)
        return true
    }

    companion object {
        const val PER_MINUTE = 10
        private const val WINDOW_MS = 60_000L
        private const val MAX_TRACKED = 4_096

        fun key(address: InetAddress): String {
            val b = address.address
            return when {
                address is Inet4Address -> "4:" + b.joinToString(".") { (it.toInt() and 0xff).toString() }
                address is Inet6Address && isV4Mapped(b) -> "4:" + b.copyOfRange(12, 16).joinToString(".") { (it.toInt() and 0xff).toString() }
                else -> "6:" + b.copyOfRange(0, 8).joinToString("") { "%02x".format(it) }
            }
        }

        private fun isV4Mapped(b: ByteArray): Boolean =
            (0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1
    }
}
