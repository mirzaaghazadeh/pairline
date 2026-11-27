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
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * One connection from the internet that becomes a [TransportKind.DIRECT] session (D-092, PROTOCOL.md "Direct
 * connection"): accepted on the public listener ([DirectListener]), or joined to the iPhone by the user's own server
 * ([app.pairline.android.link.remote.relay.RelayClient], D-106). Framing as on Wi-Fi (a 4-byte length before each
 * frame). Until the session handshake has authenticated the pinned iPhone it reads exactly two frames and looks at
 * nothing but their lengths and type bytes: a 100-byte `HELLO`, then the 25-byte confirm `RECORD`. Anything else
 * (pairing frames included) closes it, and so does a handshake that isn't done by [deadline].
 */
internal class GatedConnection(
    private val socket: Socket,
    override val label: String,
    private val deadline: Long,
    private val listener: ConnectionListener,
    private val owner: Owner,
    /** Never logged; null when the other end is the user's relay rather than the iPhone itself. */
    override val peerAddress: InetAddress?,
    private val clock: () -> Long = System::currentTimeMillis,
) : LinkConnection {

    /** Keeps count of its connections: the pending limit, and drops before the handshake for the log. */
    interface Owner {
        /** The handshake authenticated the iPhone, or the connection is going: it no longer counts as pending. */
        fun settled(connection: GatedConnection)

        /** The connection is gone (closed here or by the peer). */
        fun ended(connection: GatedConnection)

        /** It ended before the handshake, for [reason]. */
        fun dropped(reason: String)
    }

    override val kind = TransportKind.DIRECT
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
            owner.settled(this)
            owner.ended(this)
            if (!authenticated) owner.dropped(reason)
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
        owner.settled(this)
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
        owner.settled(this)
        owner.ended(this)
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

    companion object {
        private const val MAX_BACKLOG = 32

        /** A length-prefixed `HELLO`: the type byte and the 99-byte hello body. */
        const val HELLO_FRAME = 1 + Proto.HELLO_LENGTH

        /** The confirm: the type byte and a record with an empty plaintext (counter and tag). */
        const val CONFIRM_FRAME = 1 + Proto.RECORD_OVERHEAD
    }
}

/** Connections dropped before the handshake, logged as one line a minute at most, never with an address. */
internal class DropLog(private val tag: String, private val what: String) {
    private var count = 0
    private var lastLog = Long.MIN_VALUE / 2
    private val reasons = HashMap<String, Int>()

    @Synchronized
    fun note(now: Long, reason: String) {
        count++
        reasons[reason] = (reasons[reason] ?: 0) + 1
        if (now - lastLog < LOG_EVERY_MS) return
        Log.i(tag, "dropped $count $what before the handshake (${reasons.entries.joinToString { "${it.key}: ${it.value}" }})")
        count = 0
        lastLog = now
        reasons.clear()
    }

    private companion object {
        const val LOG_EVERY_MS = 60_000L
    }
}
