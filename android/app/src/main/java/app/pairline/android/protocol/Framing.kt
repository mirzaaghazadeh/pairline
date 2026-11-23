package app.pairline.android.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * BLE fragmenting (PROTOCOL.md, "BLE"). Each fragment starts with a 1-byte header: bit 0 = first,
 * bit 1 = last. The first fragment adds the frame's 2-byte big-endian total length.
 */
object BleFragmenter {
    const val FIRST: Int = 0x01
    const val LAST: Int = 0x02

    /** The smallest ATT payload (default MTU 23 − 3). */
    const val MIN_ATT_PAYLOAD: Int = 20

    fun fragment(frame: ByteArray, attPayload: Int): List<ByteArray> {
        require(attPayload >= 4) { "ATT payload too small" }
        require(frame.isNotEmpty() && frame.size <= Proto.MAX_FRAME) { "bad frame size" }
        val out = ArrayList<ByteArray>()
        var pos = 0
        var first = true
        while (true) {
            val room = attPayload - 1 - (if (first) 2 else 0)
            val n = minOf(room, frame.size - pos)
            val last = pos + n >= frame.size
            val header = (if (first) FIRST else 0) or (if (last) LAST else 0)
            val fragment = if (first) {
                byteArrayOf(header.toByte()) + BigEndian.u16(frame.size) + frame.copyOfRange(pos, pos + n)
            } else {
                byteArrayOf(header.toByte()) + frame.copyOfRange(pos, pos + n)
            }
            out += fragment
            pos += n
            first = false
            if (last) return out
        }
    }
}

/** Rebuilds frames from fragments, one connection at a time. Throws on anything malformed. */
class BleReassembler {
    private var buffer: ByteArrayOutputStream? = null
    private var expected = 0

    /** Returns a complete frame, or null while more fragments are needed. */
    fun accept(fragment: ByteArray): ByteArray? {
        if (fragment.isEmpty()) throw ProtocolException("empty fragment")
        val header = fragment[0].toInt() and 0xff
        val first = header and BleFragmenter.FIRST != 0
        val last = header and BleFragmenter.LAST != 0
        var offset = 1
        if (first) {
            if (buffer != null) throw ProtocolException("new frame started before the last one ended")
            if (fragment.size < 3) throw ProtocolException("first fragment too short")
            expected = BigEndian.readU16(fragment, 1)
            if (expected == 0) throw ProtocolException("empty frame")
            buffer = ByteArrayOutputStream(expected)
            offset = 3
        } else if (buffer == null) {
            throw ProtocolException("continuation without a first fragment")
        }
        val buf = buffer!!
        val n = fragment.size - offset
        if (buf.size() + n > expected) throw ProtocolException("fragment overflows the frame")
        buf.write(fragment, offset, n)
        if (!last) return null
        buffer = null
        if (buf.size() != expected) throw ProtocolException("frame shorter than announced")
        return buf.toByteArray()
    }

    fun reset() {
        buffer = null
        expected = 0
    }
}

/** Debug TCP framing: each frame is preceded by its 4-byte big-endian length (PROTOCOL.md, "TCP"). */
object TcpFraming {
    fun encode(frame: ByteArray): ByteArray {
        require(frame.isNotEmpty() && frame.size <= Proto.MAX_FRAME)
        return BigEndian.u32(frame.size.toLong()) + frame
    }

    /** Reads one frame; returns null on a clean end of stream before a header. */
    fun read(input: InputStream): ByteArray? {
        val header = ByteArray(4)
        if (!readFully(input, header, allowCleanEof = true)) return null
        val length = BigEndian.readU32(header, 0)
        if (length <= 0 || length > Proto.MAX_FRAME) throw ProtocolException("bad frame length")
        val frame = ByteArray(length.toInt())
        readFully(input, frame, allowCleanEof = false)
        return frame
    }

    private fun readFully(input: InputStream, into: ByteArray, allowCleanEof: Boolean): Boolean {
        var read = 0
        while (read < into.size) {
            val n = input.read(into, read, into.size - read)
            if (n < 0) {
                if (read == 0 && allowCleanEof) return false
                throw EOFException("stream ended inside a frame")
            }
            read += n
        }
        return true
    }
}
