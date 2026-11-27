package app.pairline.android.link.remote.relay

import android.annotation.SuppressLint
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Opens a connection to the user's own server, ready for the preamble; the phone's, or a fake in tests. */
fun interface RelaySockets {
    /**
     * TCP and TLS to [config]'s server, pinned to its certificate. Throws [PinMismatchException] when the server's
     * certificate isn't the one in its link, any other exception when it can't be reached.
     */
    fun open(config: RelayConfig): Socket
}

/** The server's certificate isn't the one whose SHA-256 the link carries. */
class PinMismatchException : IOException("the server's certificate doesn't match its link")

/**
 * TCP out to the relay (a name is looked up through the phone's normal DNS; an address isn't), then TLS 1.2 or 1.3
 * accepted only if the SHA-256 of the server's leaf certificate equals the link's fingerprint (PROTOCOL.md, "Your own
 * server"). Nothing else about the certificate counts: no certificate authority, name or dates. The relay makes its
 * own certificate, so the pin is the whole trust, and no domain is needed.
 */
object PinnedTlsSockets : RelaySockets {
    const val CONNECT_TIMEOUT_MS = 10_000
    private const val MAX_ADDRESSES = 4

    override fun open(config: RelayConfig): Socket {
        val addresses = InetAddress.getAllByName(config.host).take(MAX_ADDRESSES)
        var last: Exception? = null
        for (address in addresses) {
            val raw = Socket()
            try {
                raw.connect(InetSocketAddress(address, config.port), CONNECT_TIMEOUT_MS)
                raw.soTimeout = CONNECT_TIMEOUT_MS
                raw.tcpNoDelay = true
                raw.keepAlive = true
                return handshake(raw, config)
            } catch (e: PinMismatchException) {
                runCatching { raw.close() }
                throw e
            } catch (e: IOException) {
                runCatching { raw.close() }
                last = e
            }
        }
        throw last ?: IOException("no address")
    }

    /** TLS over [raw], pinned to [config]'s fingerprint. */
    fun handshake(raw: Socket, config: RelayConfig): SSLSocket {
        val trust = PinningTrustManager(config.fingerprint)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val socket = context.socketFactory.createSocket(raw, config.host, config.port, true) as SSLSocket
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        try {
            socket.startHandshake()
        } catch (e: IOException) {
            runCatching { socket.close() }
            if (trust.mismatch) throw PinMismatchException()
            throw e
        }
        return socket
    }
}

/**
 * Accepts exactly one server certificate: the one whose DER encoding hashes (SHA-256) to [fingerprint]. Pinning is the
 * point here, so this replaces the system's certificate authorities on purpose (the relay's certificate is
 * self-signed, and its link is how the user vouches for it).
 */
@SuppressLint("CustomX509TrustManager")
internal class PinningTrustManager(private val fingerprint: ByteArray) : X509TrustManager {
    @Volatile
    var mismatch = false
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
        val digest = leaf?.let { MessageDigest.getInstance("SHA-256").digest(it.encoded) }
        if (digest == null || !MessageDigest.isEqual(digest, fingerprint)) {
            mismatch = true
            throw CertificateException("the server's certificate doesn't match the pin")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("no client certificates")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
