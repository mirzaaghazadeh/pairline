package app.pairline.android.protocol

/**
 * Call audio inside a Bluetooth or debug TCP session (PROTOCOL.md, "Call audio", D-072): `MEDIA` frames on channel 3
 * with the session's link media keys. A record's plaintext holds one or more media packets, each preceded by its
 * 2-byte big-endian length, so a sender that fell behind sends everything waiting in one record. Pure Kotlin, tested
 * against protocol/test-vectors/v0.json.
 */
object LinkMediaPackets {
    /** A media packet is at least kind ‖ sequence (3 bytes). */
    const val MIN_PACKET: Int = 3

    /** Everything in one record, length prefixes included (the same limit as a Wi-Fi media datagram). */
    const val MAX_PLAINTEXT: Int = Proto.MEDIA_MAX_PLAINTEXT

    /** Bytes a packet takes in a record: its length prefix and itself. */
    fun cost(packet: ByteArray): Int = 2 + packet.size

    fun pack(packets: List<ByteArray>): ByteArray {
        val total = packets.sumOf(::cost)
        require(packets.isNotEmpty() && total <= MAX_PLAINTEXT) { "a link media record holds 1 to $MAX_PLAINTEXT bytes" }
        val out = ByteArray(total)
        var at = 0
        for (p in packets) {
            require(p.size in MIN_PACKET..0xFFFF)
            out[at] = (p.size ushr 8).toByte()
            out[at + 1] = p.size.toByte()
            p.copyInto(out, at + 2)
            at += 2 + p.size
        }
        return out
    }

    /** The packets in a record, or null if it doesn't split exactly into length-prefixed packets (then it's ignored). */
    fun unpack(plaintext: ByteArray): List<ByteArray>? {
        if (plaintext.isEmpty() || plaintext.size > MAX_PLAINTEXT) return null
        val out = ArrayList<ByteArray>(2)
        var at = 0
        while (at < plaintext.size) {
            if (plaintext.size - at < 2) return null
            val length = (plaintext[at].toInt() and 0xFF shl 8) or (plaintext[at + 1].toInt() and 0xFF)
            at += 2
            if (length < MIN_PACKET || length > plaintext.size - at) return null
            out += plaintext.copyOfRange(at, at + length)
            at += length
        }
        return out
    }
}

/**
 * Seals this side's `MEDIA` frames in order. Thread-safe: the Bluetooth thread seals while the link thread may close
 * the session. Returns null once closed or before the counter would reach 2³² (the session must reconnect).
 */
class LinkMediaSealer(private val key: ByteArray, private val direction: Int) {
    private var next: Long = 0
    private var closed = false

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray? {
        if (closed || next >= Proto.COUNTER_LIMIT - 1) return null
        val record = Records.seal(key, direction, next++, Proto.CHANNEL_LINK_MEDIA, plaintext)
        return Frame(FrameType.MEDIA, record).encode()
    }

    @Synchronized
    fun wipe() {
        closed = true
        key.wipe()
    }
}
