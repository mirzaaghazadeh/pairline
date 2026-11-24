package app.pairline.android.privileged.adb

import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One message of adb's wire protocol (AOSP packages/modules/adb/protocol.txt). Plain JVM code. */
internal class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = EMPTY) {

    fun encode(): ByteArray = ByteBuffer.allocate(HEADER + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(command)
        putInt(arg0)
        putInt(arg1)
        putInt(data.size)
        putInt(checksum(data))
        putInt(command.inv())
        put(data)
    }.array()

    companion object {
        const val CNXN = 0x4e584e43
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val STLS = 0x534c5453
        const val AUTH = 0x48545541

        const val VERSION = 0x01000001
        const val STLS_VERSION = 0x01000000
        const val MAX_DATA = 1024 * 1024
        const val HEADER = 24
        private val EMPTY = ByteArray(0)

        /** A NUL-terminated string payload ("host::", "shell:…"). */
        fun text(command: Int, arg0: Int, arg1: Int, text: String) =
            AdbMessage(command, arg0, arg1, (text + "\u0000").toByteArray(Charsets.UTF_8))

        /** adb's "checksum": the sum of the payload bytes (newer adbd ignores it, older ones check it). */
        fun checksum(data: ByteArray): Int = data.fold(0) { sum, b -> sum + (b.toInt() and 0xFF) }

        fun read(input: DataInputStream): AdbMessage {
            val header = ByteArray(HEADER)
            input.readFully(header)
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = b.int
            val arg0 = b.int
            val arg1 = b.int
            val length = b.int
            b.int // checksum
            val magic = b.int
            if (magic != command.inv()) throw IOException("not an adb message")
            if (length < 0 || length > MAX_DATA) throw IOException("adb message too large ($length bytes)")
            val data = ByteArray(length)
            input.readFully(data)
            return AdbMessage(command, arg0, arg1, data)
        }
    }
}
