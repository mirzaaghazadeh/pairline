package app.pairline.android.protocol

import java.util.Base64

/** Thrown for anything a peer sends that breaks docs/PROTOCOL.md. The connection is closed. */
class ProtocolException(message: String) : Exception(message)

/** Lowercase hex, used by the test vectors and the diagnostics screen. */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0f])
        }
        return out.toString()
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "not hex" }
            ((hi shl 4) or lo).toByte()
        }
    }
}

/** Base64url without padding: every binary value in JSON and URIs (PROTOCOL.md, "Encodings"). */
object B64u {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Returns null for anything that isn't valid base64url. Padding is tolerated. */
    fun decodeOrNull(text: String): ByteArray? = try {
        decoder.decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal object BigEndian {
    fun u16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

    fun u32(value: Long): ByteArray = ByteArray(4) { i -> (value ushr (24 - 8 * i)).toByte() }

    fun u64(value: Long): ByteArray = ByteArray(8) { i -> (value ushr (56 - 8 * i)).toByte() }

    fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    fun readU32(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (bytes[offset + i].toLong() and 0xff)
        return v
    }

    /** Reads 8 bytes as a signed Long. Values ≥ 2⁶³ come back negative; callers reject them. */
    fun readU64(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[offset + i].toLong() and 0xff)
        return v
    }
}

/** Constant-time comparison for MACs. Length is not secret. */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

/** Overwrites key material we no longer need. Best effort: the JVM may have copies. */
fun ByteArray.wipe() = fill(0)
