package app.pairline.android.debugtools

import app.pairline.android.link.ConnectionListener
import app.pairline.android.link.LinkConnection
import app.pairline.android.link.LinkTransport
import app.pairline.android.link.TransportKind
import app.pairline.android.log.Log
import app.pairline.android.protocol.TcpFraming
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * DEBUG BUILDS ONLY (D-018). Listens on 127.0.0.1:47800 inside the emulator; `adb forward tcp:47800 tcp:47800`
 * makes it reachable from the Mac. Frames carry a 4-byte big-endian length. One connection at a time: a new
 * one replaces the old one (PROTOCOL.md clarifications). Pairing, handshake and records are the same as on BLE.
 */
class DebugTcpTransport(
    private val listener: ConnectionListener,
    private val onListening: (Boolean) -> Unit,
) : LinkTransport {
    override val kind = TransportKind.DEBUG_TCP

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var current: TcpConnection? = null
    private val labels = AtomicInteger(1)

    override fun start() {
        if (server != null) return
        Thread({ acceptLoop() }, "pairline-tcp-accept").apply { isDaemon = true }.start()
    }

    override fun stop() {
        runCatching { server?.close() }
        server = null
        current?.close()
        current = null
        onListening(false)
    }

    private fun acceptLoop() {
        val s = try {
            ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
        } catch (e: IOException) {
            Log.w(TAG, "can't listen on port $PORT", e)
            return
        }
        server = s
        onListening(true)
        Log.i(TAG, "debug TCP transport listening on 127.0.0.1:$PORT")
        while (!s.isClosed) {
            val socket = try {
                s.accept()
            } catch (_: IOException) {
                break
            }
            current?.close()
            val c = TcpConnection(socket, "tcp#${labels.getAndIncrement()}")
            current = c
            c.start()
        }
        onListening(false)
    }

    private inner class TcpConnection(private val socket: Socket, override val label: String) : LinkConnection {
        override val kind = TransportKind.DEBUG_TCP
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "pairline-$label-write") }

        @Volatile
        private var closed = false

        fun start() {
            socket.tcpNoDelay = true
            listener.onOpened(this)
            Thread({ readLoop() }, "pairline-$label-read").apply { isDaemon = true }.start()
        }

        private fun readLoop() {
            var reason = "closed by the peer"
            try {
                val input = BufferedInputStream(socket.getInputStream())
                while (!closed) {
                    val frame = TcpFraming.read(input) ?: break
                    listener.onFrame(this, frame)
                }
            } catch (e: Exception) {
                reason = if (closed) "closed" else "read error (${e.javaClass.simpleName})"
            } finally {
                close()
                writer.shutdown()
                listener.onClosed(this, reason)
                if (current == this) current = null
            }
        }

        override fun send(frame: ByteArray) {
            if (closed) return
            writer.execute {
                try {
                    val out = socket.getOutputStream()
                    out.write(TcpFraming.encode(frame))
                    out.flush()
                } catch (_: IOException) {
                    close()
                }
            }
        }

        override fun close() {
            closed = true
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "tcp"
        const val PORT = 47800
    }
}
