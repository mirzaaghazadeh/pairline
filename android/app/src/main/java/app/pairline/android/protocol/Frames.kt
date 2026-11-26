package app.pairline.android.protocol

/** Constants from docs/PROTOCOL.md (wire version 0). */
object Proto {
    const val VERSION: Int = 0

    /**
     * What `device.info` says as `proto`: v1 adds remote mode (D-092). The local links keep [VERSION] on the wire
     * (hello and associated data).
     */
    const val SUPPORTED_VERSION: Int = 1
    const val ROLE_REMOTE: Byte = 0x52 // 'R'
    const val ROLE_BRIDGE: Byte = 0x42 // 'B'

    /** Direction labels, the first 4 bytes of every nonce. */
    const val DIR_BRIDGE_TO_REMOTE: Int = 1
    const val DIR_REMOTE_TO_BRIDGE: Int = 2

    /** Channels, the second byte of the associated data. */
    const val CHANNEL_CONTROL: Int = 0
    const val CHANNEL_MEDIA: Int = 1
    const val CHANNEL_BULK: Int = 2

    /** Call audio inside a Bluetooth or debug TCP session (`MEDIA` frames, D-072). */
    const val CHANNEL_LINK_MEDIA: Int = 3

    const val MAX_FRAME: Int = 65_535
    const val HELLO_LENGTH: Int = 99
    const val PUBLIC_KEY_LENGTH: Int = 65
    const val RANDOM_LENGTH: Int = 32
    const val DEVICE_ID_LENGTH: Int = 8
    const val PAIRING_SECRET_LENGTH: Int = 32
    const val PAIRING_NONCE_LENGTH: Int = 32
    const val MAC_LENGTH: Int = 32
    const val KEY_LENGTH: Int = 32
    const val TAG_LENGTH: Int = 16

    /** Pairing secrets expire 5 minutes after the QR code appears and work once. */
    const val PAIRING_SECRET_TTL_MS: Long = 5 * 60 * 1000L

    /** Close and reconnect before any record counter reaches 2³² (PROTOCOL.md, "Rekeying"). */
    const val COUNTER_LIMIT: Long = 1L shl 32

    /** Messages are paged so each reply's JSON stays under 30 KB (PROTOCOL.md, "Messages"). */
    const val PAGE_BUDGET_BYTES: Int = 30_000

    val PAIR_LABEL: ByteArray = "pairline-pair-v0".toByteArray(Charsets.US_ASCII)
    val SESSION_INFO: ByteArray = "pairline v0 session".toByteArray(Charsets.US_ASCII)
    val MEDIA_INFO: ByteArray = "pairline v0 media".toByteArray(Charsets.US_ASCII)
    val BULK_INFO: ByteArray = "pairline v0 bulk".toByteArray(Charsets.US_ASCII)
    val LINK_MEDIA_INFO: ByteArray = "pairline v0 link media".toByteArray(Charsets.US_ASCII)

    /** Media datagrams carry at most this much plaintext, so one fits any Wi-Fi path unfragmented. */
    const val MEDIA_MAX_PLAINTEXT: Int = 1200

    /** Record overhead: 8-byte counter and 16-byte tag. */
    const val RECORD_OVERHEAD: Int = 8 + TAG_LENGTH

    /** The media channel's sliding replay window, in records. */
    const val MEDIA_REPLAY_WINDOW: Int = 64

    /** File data per `BULK` record (PROTOCOL.md, "File transfer"). */
    const val FILE_CHUNK: Int = 49_152

    /** Largest file either side sends or accepts. */
    const val FILE_MAX_SIZE: Long = 1L shl 30

    /** First byte of a `BULK` plaintext that carries file data. */
    const val BULK_FILE_DATA: Int = 0x01
}

enum class FrameType(val code: Int) {
    PAIR_HELLO(0x01),
    PAIR_OK(0x02),
    PAIR_FAIL(0x03),
    HELLO(0x10),
    RECORD(0x20),

    /** A record on the bulk channel (M5): Wi-Fi TCP only, after the handshake. */
    BULK(0x21),

    /** A record on the link media channel (call audio, D-072): Bluetooth and debug TCP only, after the handshake. */
    MEDIA(0x22);

    companion object {
        fun of(code: Int): FrameType? = entries.firstOrNull { it.code == code }
    }
}

/** One complete unit after transport reassembly: a type byte and a body. */
class Frame(val type: FrameType, val body: ByteArray) {

    fun encode(): ByteArray {
        val out = ByteArray(1 + body.size)
        out[0] = type.code.toByte()
        body.copyInto(out, 1)
        if (out.size > Proto.MAX_FRAME) throw ProtocolException("frame too long")
        return out
    }

    companion object {
        /** Unknown frame types close the connection, so this throws instead of skipping. */
        fun decode(bytes: ByteArray): Frame {
            if (bytes.isEmpty()) throw ProtocolException("empty frame")
            if (bytes.size > Proto.MAX_FRAME) throw ProtocolException("frame too long")
            val type = FrameType.of(bytes[0].toInt() and 0xff)
                ?: throw ProtocolException("unknown frame type")
            return Frame(type, bytes.copyOfRange(1, bytes.size))
        }
    }
}

/** The 99-byte hello body: version ‖ role ‖ ephemeral public key (65) ‖ random (32). */
class Hello(val role: Byte, val ephemeralPublicKey: ByteArray, val random: ByteArray) {
    init {
        require(ephemeralPublicKey.size == Proto.PUBLIC_KEY_LENGTH)
        require(random.size == Proto.RANDOM_LENGTH)
    }

    fun encode(): ByteArray {
        val out = ByteArray(Proto.HELLO_LENGTH)
        out[0] = Proto.VERSION.toByte()
        out[1] = role
        ephemeralPublicKey.copyInto(out, 2)
        random.copyInto(out, 2 + Proto.PUBLIC_KEY_LENGTH)
        return out
    }

    companion object {
        fun decode(body: ByteArray, expectedRole: Byte): Hello {
            if (body.size != Proto.HELLO_LENGTH) throw ProtocolException("hello has the wrong length")
            if (body[0].toInt() != Proto.VERSION) throw ProtocolException("unsupported protocol version")
            if (body[1] != expectedRole) throw ProtocolException("hello from the wrong role")
            return Hello(
                role = body[1],
                ephemeralPublicKey = body.copyOfRange(2, 2 + Proto.PUBLIC_KEY_LENGTH),
                random = body.copyOfRange(2 + Proto.PUBLIC_KEY_LENGTH, Proto.HELLO_LENGTH),
            )
        }
    }
}
