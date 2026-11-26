package app.pairline.android.link

import app.pairline.android.protocol.LinkMediaPackets
import app.pairline.android.protocol.LinkMediaSealer

/**
 * Call audio inside a Bluetooth (or debug TCP) session (PROTOCOL.md, "Call audio", D-072): the same [MediaChannel] as
 * the Wi-Fi UDP channel, carried as `MEDIA` frames on [connection].
 *
 * [send] only queues: the connection takes the next frame when it can ([MediaFrameSource]), and that frame holds every
 * packet waiting, so a Bluetooth hold-up turns into one bigger frame instead of a backlog. Packets that have waited
 * longer than [MAX_WAITING] frames (200 ms) are dropped, oldest first: late audio is worse than a short gap. Received
 * records arrive through [receive] on the link thread. Thread-safe.
 */
class LinkMediaChannel(
    private val connection: LinkConnection,
    private val sealer: LinkMediaSealer,
) : MediaChannel, MediaFrameSource {
    private val lock = Any()
    private val sendLock = Any()
    private val waiting = ArrayDeque<ByteArray>()
    private var closed = false

    /** Frames sent, packets sent, packets dropped for waiting too long: for the call's log line. */
    var framesSent = 0
        private set
    var packetsSent = 0
        private set
    var packetsDropped = 0
        private set

    @Volatile
    override var onPacket: ((ByteArray) -> Unit)? = null

    override val isOpen: Boolean get() = synchronized(lock) { !closed }

    override fun send(packet: ByteArray) {
        require(packet.size >= LinkMediaPackets.MIN_PACKET && LinkMediaPackets.cost(packet) <= LinkMediaPackets.MAX_PLAINTEXT) {
            "a media packet is ${LinkMediaPackets.MIN_PACKET} to ${LinkMediaPackets.MAX_PLAINTEXT - 2} bytes"
        }
        synchronized(lock) {
            if (closed) return
            waiting.addLast(packet)
            while (waiting.size > MAX_WAITING) {
                waiting.removeFirst()
                packetsDropped++
            }
        }
        if (connection.pullsMedia) {
            connection.mediaReady(this)
        } else {
            // Seal and hand over under one lock, so the frames reach the connection in counter order.
            synchronized(sendLock) { nextFrame()?.let(connection::send) }
        }
    }

    /** Batches and seals under one lock, so frames are numbered in the order their packets were sent. */
    override fun nextFrame(): ByteArray? = synchronized(lock) {
        if (closed || waiting.isEmpty()) return null
        val batch = ArrayList<ByteArray>(waiting.size)
        var size = 0
        while (waiting.isNotEmpty() && size + LinkMediaPackets.cost(waiting.first()) <= LinkMediaPackets.MAX_PLAINTEXT) {
            val p = waiting.removeFirst()
            size += LinkMediaPackets.cost(p)
            batch += p
        }
        val frame = sealer.seal(LinkMediaPackets.pack(batch)) ?: return null
        framesSent++
        packetsSent += batch.size
        frame
    }

    /** An authentic `MEDIA` record from the iPhone. A record that doesn't split into packets is ignored. */
    fun receive(plaintext: ByteArray) {
        val packets = LinkMediaPackets.unpack(plaintext) ?: return
        val receiver = onPacket ?: return
        for (p in packets) receiver(p)
    }

    /** The session ended (or replaced this channel): nothing more goes out. The session wipes the key. */
    fun close() {
        synchronized(lock) {
            closed = true
            waiting.clear()
        }
        onPacket = null
    }

    companion object {
        /** 10 × 20 ms of audio may wait for the link; older packets are dropped. */
        const val MAX_WAITING = 10
    }
}
