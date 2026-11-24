package app.pairline.android.link.wifi

import app.pairline.android.link.MediaEndpoint
import app.pairline.android.log.Log
import app.pairline.android.protocol.MediaOpener
import app.pairline.android.protocol.MediaSealer
import app.pairline.android.protocol.Proto
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The UDP media channel of one Wi-Fi session (PROTOCOL.md, "Media channel (UDP)"). Bound to the local address the
 * session's TCP connection arrived on, so it never listens on mobile data. Accepts datagrams only from the session's
 * peer IP; learns the iPhone's port from the latest authentic datagram and answers its probes. Sending seals on the
 * caller's thread and hands the datagram to a sender thread through a small queue: when the queue is full the
 * packet is dropped, so [send] never blocks.
 */
internal class MediaSocket private constructor(
    private val socket: DatagramSocket,
    private val peer: InetAddress,
    sealKey: ByteArray,
    openKey: ByteArray,
    private val label: String,
) : MediaEndpoint {
    private val sealer = MediaSealer(sealKey, Proto.DIR_BRIDGE_TO_REMOTE)
    private val opener = MediaOpener(openKey, Proto.DIR_REMOTE_TO_BRIDGE)
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_SIZE)

    @Volatile
    private var remote: InetSocketAddress? = null

    @Volatile
    private var closed = false

    @Volatile
    private var opened = false

    private var probeWindowStart = 0L
    private var probesInWindow = 0
    private var dropsLogged = false

    override val isOpen: Boolean get() = opened && !closed
    override val port: Int = socket.localPort

    @Volatile
    override var onPacket: ((ByteArray) -> Unit)? = null

    @Volatile
    override var onOpened: (() -> Unit)? = null

    private fun start() {
        Thread({ receiveLoop() }, "pairline-$label-media-rx").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }.start()
        Thread({ sendLoop() }, "pairline-$label-media-tx").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }.start()
    }

    override fun send(packet: ByteArray) {
        require(packet.isNotEmpty() && packet.size <= Proto.MEDIA_MAX_PLAINTEXT) { "media packets are 1 to 1200 bytes" }
        if (!isOpen) return
        val wire = sealer.seal(packet) ?: return
        if (!queue.offer(wire) && !dropsLogged) {
            dropsLogged = true
            Log.w(TAG, "$label: media send queue full; dropping packets")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        onPacket = null
        onOpened = null
        runCatching { socket.close() }
        queue.clear()
        sealer.wipe()
        opener.wipe()
    }

    private fun receiveLoop() {
        val buffer = ByteArray(MAX_DATAGRAM + 1)
        while (!closed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                break
            } catch (e: IOException) {
                if (closed) break
                Log.w(TAG, "$label: media receive failed", e)
                continue
            }
            if (packet.address != peer) continue // only the iPhone of this session
            val plaintext = opener.open(buffer, packet.length) ?: continue
            remote = InetSocketAddress(packet.address, packet.port)
            if (!opened) {
                opened = true
                runCatching { onOpened?.invoke() }
            }
            if (plaintext.isEmpty()) {
                answerProbe()
            } else {
                try {
                    onPacket?.invoke(plaintext)
                } catch (e: Exception) {
                    Log.e(TAG, "$label: media packet handler failed", e)
                }
            }
        }
    }

    /** Each authentic probe gets one back, at most [MAX_PROBE_REPLIES] a second. */
    private fun answerProbe() {
        val now = System.nanoTime()
        if (now - probeWindowStart > 1_000_000_000L) {
            probeWindowStart = now
            probesInWindow = 0
        }
        if (++probesInWindow > MAX_PROBE_REPLIES) return
        sealer.seal(ByteArray(0))?.let { queue.offer(it) }
    }

    private fun sendLoop() {
        while (!closed) {
            val wire = try {
                queue.poll(500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            val to = remote ?: continue
            try {
                socket.send(DatagramPacket(wire, wire.size, to))
            } catch (e: IOException) {
                if (closed) break
                // A full buffer or a short network hiccup: this packet is lost, like any UDP packet.
            }
        }
    }

    companion object {
        private const val TAG = "media"
        private const val QUEUE_SIZE = 64
        private const val MAX_PROBE_REPLIES = 10
        private const val MAX_DATAGRAM = Proto.RECORD_OVERHEAD + Proto.MEDIA_MAX_PLAINTEXT

        /**
         * Binds on [local] (the session's TCP local address), at the Wi-Fi port number when it's free. Returns null if
         * no socket could be bound; the session then goes on without media (`wifi.media` with a null port).
         */
        fun open(
            local: InetAddress,
            peer: InetAddress,
            preferredPort: Int,
            sealKey: ByteArray,
            openKey: ByteArray,
            label: String,
        ): MediaSocket? {
            val socket = bind(local, preferredPort) ?: bind(local, 0) ?: return null
            return MediaSocket(socket, peer, sealKey, openKey, label).also { it.start() }
        }

        private fun bind(local: InetAddress, port: Int): DatagramSocket? = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(local, port))
            }
        } catch (_: IOException) {
            null
        }
    }
}
