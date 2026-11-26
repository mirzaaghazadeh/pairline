package app.pairline.android.link.remote

import app.pairline.android.link.ConnectionListener
import app.pairline.android.link.LinkConnection
import app.pairline.android.link.TransportKind
import app.pairline.android.log.Log
import app.pairline.android.protocol.BigEndian
import app.pairline.android.protocol.FrameType
import app.pairline.android.protocol.Proto
import app.pairline.android.protocol.ProtocolException
import app.pairline.android.protocol.TcpFraming
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Where the direct connection listens; [DirectListener] on the phone, a fake in tests. */
interface DirectServer {
    /** Listens on exactly [addresses] (closing the others); the port, the same on all of them, or null if none bound. */
    fun listen(addresses: List<InetAddress>): Int?

    /** Stops listening and drops every connection that hasn't completed the handshake. Established sessions stay. */
    fun close()
}

/**
 * Remote mode's public listener (D-080, PROTOCOL.md "Direct connection"): the one socket in the app that accepts
 * connections from any address, because the home router forwards the internet to it. Framing as on Wi-Fi (a 4-byte
 * length before each frame), and each connection becomes a [TransportKind.DIRECT] session through the
 * [ConnectionListener] (LinkManager). Until the session handshake has authenticated the pinned iPhone it reads
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
    private val pending = LinkedHashSet<DirectConnection>()
    private val connections = ConcurrentHashMap.newKeySet<DirectConnection>()
    private val labels = AtomicInteger(1)
    private val drops = DropLog()

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
                DirectConnection(socket, "direct#${labels.getAndIncrement()}", now + handshakeTimeoutMs)
                    .also { pending += it; connections += it }
            }
        }
        if (connection == null) {
            runCatching { socket.close() }
            drops.note(now, "too many connections")
            return
        }
        connection.start()
    }

    private inner class DirectConnection(
        private val socket: Socket,
        override val label: String,
        private val deadline: Long,
    ) : LinkConnection {
        override val kind = TransportKind.DIRECT
        override val peerAddress: InetAddress? = socket.inetAddress
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "pairline-$label-write") }
        private val backlog = Semaphore(MAX_BACKLOG)
        private val output by lazy { BufferedOutputStream(socket.getOutputStream(), 16 * 1024) }
        private val verdict = CountDownLatch(1)

        @Volatile
        private var authenticated = false

        @Volatile
        private var closed = false

        fun start() {
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
            } catch (_: IOException) {
                // Already gone; the read loop reports it.
            }
            listener.onOpened(this)
            Thread({ readLoop() }, "pairline-$label-read").apply { isDaemon = true }.start()
        }

        private fun readLoop() {
            var reason = "closed by the peer"
            try {
                val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
                deliver(readGated(input, HELLO_FRAME, FrameType.HELLO))
                deliver(readGated(input, CONFIRM_FRAME, FrameType.RECORD))
                // Nothing more is read until LinkManager says the handshake authenticated the pinned iPhone.
                val left = deadline - clock()
                val decided = left > 0 && verdict.await(left, TimeUnit.MILLISECONDS)
                if (!authenticated) {
                    reason = if (decided) "closed" else "handshake timed out"
                    return
                }
                socket.soTimeout = 0
                while (!closed) {
                    deliver(TcpFraming.read(input) ?: break)
                }
            } catch (_: SocketTimeoutException) {
                reason = "handshake timed out"
            } catch (_: ProtocolException) {
                reason = if (authenticated) "bad frame" else "refused before the handshake"
            } catch (_: EOFException) {
                reason = "closed by the peer"
            } catch (e: Exception) {
                reason = if (closed) "closed" else "read error (${e.javaClass.simpleName})"
            } finally {
                closed = true
                verdict.countDown()
                runCatching { socket.close() }
                writer.shutdown()
                synchronized(lock) { pending.remove(this) }
                connections.remove(this)
                if (!authenticated) drops.note(clock(), reason)
                listener.onClosed(this, reason)
            }
        }

        /** One frame before the handshake: exactly [length] bytes long, starting with [type]; otherwise it closes. */
        private fun readGated(input: InputStream, length: Int, type: FrameType): ByteArray {
            val header = readBefore(input, 4)
            if (BigEndian.readU32(header, 0) != length.toLong()) throw ProtocolException("unexpected frame before the handshake")
            val frame = readBefore(input, length)
            if ((frame[0].toInt() and 0xff) != type.code) throw ProtocolException("unexpected frame before the handshake")
            return frame
        }

        /** Reads [n] bytes before [deadline]; a peer that trickles them in slower gets [SocketTimeoutException]. */
        private fun readBefore(input: InputStream, n: Int): ByteArray {
            val out = ByteArray(n)
            var read = 0
            while (read < n) {
                val left = deadline - clock()
                if (left <= 0) throw SocketTimeoutException()
                socket.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val r = input.read(out, read, n - read)
                if (r < 0) throw EOFException()
                read += r
            }
            return out
        }

        /** Waits while the link thread is behind, so frames don't pile up (as on Wi-Fi). */
        private fun deliver(frame: ByteArray) {
            backlog.acquire()
            listener.onFrame(this, frame)
        }

        override fun frameProcessed() = backlog.release()

        override fun onAuthenticated() {
            authenticated = true
            synchronized(lock) { pending.remove(this) }
            verdict.countDown()
        }

        override fun send(frame: ByteArray) = send(frame) {}

        override fun send(frame: ByteArray, onSent: () -> Unit) {
            if (closed) return onSent()
            try {
                writer.execute {
                    try {
                        if (!closed) {
                            output.write(TcpFraming.encode(frame))
                            output.flush()
                        }
                    } catch (_: IOException) {
                        closed = true
                        runCatching { socket.close() }
                    } finally {
                        onSent()
                    }
                }
            } catch (_: RejectedExecutionException) {
                onSent()
            }
        }

        /**
         * Before the handshake: at once. After it: once frames already queued (such as a final `unpair`) are written,
         * or after 2 s at most, as on Wi-Fi.
         */
        override fun close() {
            if (closed) return
            closed = true
            verdict.countDown()
            synchronized(lock) { pending.remove(this) }
            connections.remove(this)
            if (!authenticated) {
                runCatching { socket.close() }
                return
            }
            runCatching { writer.execute { runCatching { output.flush() }; runCatching { socket.close() } } }
            Thread({
                Thread.sleep(2_000)
                runCatching { socket.close() }
            }, "pairline-$label-close").apply { isDaemon = true }.start()
        }
    }

    /** Dropped connections from the internet, logged as one line a minute at most, never with an address. */
    private class DropLog {
        private var count = 0
        private var lastLog = Long.MIN_VALUE / 2
        private val reasons = HashMap<String, Int>()

        @Synchronized
        fun note(now: Long, reason: String) {
            count++
            reasons[reason] = (reasons[reason] ?: 0) + 1
            if (now - lastLog < LOG_EVERY_MS) return
            Log.i(TAG, "dropped $count connection(s) before the handshake (${reasons.entries.joinToString { "${it.key}: ${it.value}" }})")
            count = 0
            lastLog = now
            reasons.clear()
        }
    }

    companion object {
        private const val TAG = "direct"

        /** Next to the Wi-Fi link's 47820 (PROTOCOL.md leaves the number to the bridge; any free port works). */
        const val PREFERRED_PORT = 47830
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val MAX_PENDING = 4
        private const val MAX_CONNECTIONS = 8
        private const val BACKLOG = 8
        private const val MAX_BACKLOG = 32
        private const val LOG_EVERY_MS = 60_000L

        /** A length-prefixed `HELLO`: the type byte and the 99-byte hello body. */
        const val HELLO_FRAME = 1 + Proto.HELLO_LENGTH

        /** The confirm: the type byte and a record with an empty plaintext (counter and tag). */
        const val CONFIRM_FRAME = 1 + Proto.RECORD_OVERHEAD
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
