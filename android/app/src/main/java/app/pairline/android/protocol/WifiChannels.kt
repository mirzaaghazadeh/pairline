package app.pairline.android.protocol

/**
 * The Wi-Fi session's extra channels (PROTOCOL.md, "Records" and "Wi-Fi"): keys, the UDP media records with their
 * 64-record replay window, and the bulk channel's file-data payload. Pure Kotlin, tested against
 * protocol/test-vectors/v0.json.
 */

/** Media and bulk keys of one Wi-Fi session, from its `okm`. B seals with the B→R keys and opens with R→B. */
class WifiKeys(
    val mediaBridgeToRemote: ByteArray,
    val mediaRemoteToBridge: ByteArray,
    val bulkBridgeToRemote: ByteArray,
    val bulkRemoteToBridge: ByteArray,
) {
    fun wipe() {
        mediaBridgeToRemote.wipe()
        mediaRemoteToBridge.wipe()
        bulkBridgeToRemote.wipe()
        bulkRemoteToBridge.wipe()
    }

    companion object {
        fun from(keys: SessionKeys): WifiKeys {
            val (mBR, mRB) = keys.media()
            val (bBR, bRB) = keys.bulk()
            return WifiKeys(mBR, mRB, bBR, bRB)
        }
    }
}

/**
 * The receiver's 64-record sliding window: `top` is the highest counter accepted and bit i of [mask] says whether
 * `top − i` was. Check with [mayAccept] before decrypting and [mark] only after the record authenticated.
 */
class ReplayWindow(private val size: Int = Proto.MEDIA_REPLAY_WINDOW) {
    init {
        require(size in 1..64)
    }

    private var top: Long = -1
    private var mask: Long = 0

    fun mayAccept(counter: Long): Boolean {
        if (counter < 0) return false
        if (top < 0 || counter > top) return true
        val offset = top - counter
        return offset < size && (mask ushr offset.toInt()) and 1L == 0L
    }

    fun mark(counter: Long) {
        when {
            top < 0 -> {
                top = counter
                mask = 1
            }
            counter > top -> {
                val shift = counter - top
                mask = if (shift < size) ((mask shl shift.toInt()) or 1L) and windowMask() else 1L
                top = counter
            }
            else -> mask = mask or (1L shl (top - counter).toInt())
        }
    }

    private fun windowMask(): Long = if (size == 64) -1L else (1L shl size) - 1
}

/** Seals media datagrams. Thread-safe; every datagram, probes included, takes the next counter. */
class MediaSealer(private val key: ByteArray, private val direction: Int) {
    private var next: Long = 0

    /** Null once the counter would reach 2³²: the Wi-Fi session must reconnect to rekey. */
    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray? {
        require(plaintext.size <= Proto.MEDIA_MAX_PLAINTEXT) { "media payload too large" }
        if (next >= Proto.COUNTER_LIMIT - 1) return null
        return Records.seal(key, direction, next++, Proto.CHANNEL_MEDIA, plaintext)
    }

    @Synchronized
    fun wipe() = key.wipe()
}

/** Opens media datagrams with the replay window. Returns null for anything it drops; never throws. */
class MediaOpener(private val key: ByteArray, private val direction: Int) {
    private val window = ReplayWindow()

    @Synchronized
    fun open(datagram: ByteArray, length: Int = datagram.size): ByteArray? {
        if (length < Proto.RECORD_OVERHEAD || length > Proto.RECORD_OVERHEAD + Proto.MEDIA_MAX_PLAINTEXT) return null
        val wire = if (length == datagram.size) datagram else datagram.copyOf(length)
        val counter = try {
            Records.counterOf(wire)
        } catch (_: ProtocolException) {
            return null
        }
        if (counter >= Proto.COUNTER_LIMIT || !window.mayAccept(counter)) return null
        val plaintext = try {
            Records.open(key, direction, Proto.CHANNEL_MEDIA, wire).second
        } catch (_: ProtocolException) {
            return null
        }
        window.mark(counter)
        return plaintext
    }

    @Synchronized
    fun wipe() = key.wipe()
}

/** `BULK` plaintext with file data: `0x01 ‖ fid (u32) ‖ offset (u64) ‖ data`. */
class FileData(val fid: Long, val offset: Long, val data: ByteArray) {
    fun encode(): ByteArray {
        require(data.isNotEmpty() && data.size <= Proto.FILE_CHUNK)
        return byteArrayOf(Proto.BULK_FILE_DATA.toByte()) + BigEndian.u32(fid) + BigEndian.u64(offset) + data
    }

    companion object {
        private const val HEADER = 1 + 4 + 8

        /** Null for a record that isn't file data (reserved types are ignored) or is malformed. */
        fun decode(plaintext: ByteArray): FileData? {
            if (plaintext.size <= HEADER || plaintext[0].toInt() != Proto.BULK_FILE_DATA) return null
            if (plaintext.size - HEADER > Proto.FILE_CHUNK) return null
            val offset = BigEndian.readU64(plaintext, 5)
            if (offset < 0) return null
            return FileData(BigEndian.readU32(plaintext, 1), offset, plaintext.copyOfRange(HEADER, plaintext.size))
        }

        fun encode(fid: Long, offset: Long, data: ByteArray, length: Int): ByteArray {
            require(length in 1..Proto.FILE_CHUNK)
            val out = ByteArray(HEADER + length)
            out[0] = Proto.BULK_FILE_DATA.toByte()
            BigEndian.u32(fid).copyInto(out, 1)
            BigEndian.u64(offset).copyInto(out, 5)
            data.copyInto(out, HEADER, 0, length)
            return out
        }
    }
}
