package app.pairline.android.privileged.adb

import android.os.Build
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * A connection to this phone's own Wireless debugging (D-054), only to run the one command that starts Pairline's
 * helper ([app.pairline.android.privileged.HelperCommand]). It connects to 127.0.0.1 only.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class AdbConnection(private val port: Int, private val key: AdbKey) : Closeable {
    private val socket = Socket()
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream

    /** Throws [AdbNotPairedException] when adbd doesn't accept Pairline's key (pairing removed or never done). */
    fun connect() {
        socket.connect(InetSocketAddress(LOCALHOST, port), TIMEOUT_MS)
        socket.soTimeout = TIMEOUT_MS
        socket.tcpNoDelay = true
        input = DataInputStream(socket.getInputStream())
        output = socket.getOutputStream()
        send(AdbMessage.text(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_DATA, "host::"))
        var message = AdbMessage.read(input)
        if (message.command != AdbMessage.STLS) throw IOException("Wireless debugging didn't offer TLS")
        send(AdbMessage(AdbMessage.STLS, AdbMessage.STLS_VERSION, 0))
        val tls = key.sslContext.socketFactory.createSocket(socket, LOCALHOST, port, true) as SSLSocket
        try {
            tls.startHandshake()
            input = DataInputStream(tls.inputStream)
            output = tls.outputStream
            message = AdbMessage.read(input)
        } catch (e: IOException) {
            // adbd ends the handshake (or the connection right after it) for a key the user hasn't paired.
            throw AdbNotPairedException(e)
        }
        if (message.command != AdbMessage.CNXN) throw IOException("unexpected reply ${message.command}")
    }

    /** Runs [command] with `sh` as the shell user and returns what it printed (at most 64 KB). */
    fun shell(command: String): String {
        val local = 1
        send(AdbMessage.text(AdbMessage.OPEN, local, 0, "shell:$command"))
        val out = ByteArrayOutputStream()
        while (true) {
            val m = AdbMessage.read(input)
            when (m.command) {
                AdbMessage.OKAY -> Unit
                AdbMessage.WRTE -> {
                    if (out.size() < 64 * 1024) out.write(m.data)
                    send(AdbMessage(AdbMessage.OKAY, local, m.arg0))
                }
                AdbMessage.CLSE -> {
                    send(AdbMessage(AdbMessage.CLSE, local, m.arg0))
                    return out.toString(Charsets.UTF_8.name())
                }
                else -> throw IOException("unexpected adb message ${m.command}")
            }
        }
    }

    private fun send(message: AdbMessage) {
        output.write(message.encode())
        output.flush()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        const val LOCALHOST = "127.0.0.1"
        const val TIMEOUT_MS = 10_000
    }
}

/** adbd didn't accept Pairline's key: the user removed the pairing, or it was never made. */
internal class AdbNotPairedException(cause: Throwable) : IOException("not paired with Wireless debugging", cause)
