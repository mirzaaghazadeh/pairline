package app.pairline.android.privileged.adb

import android.os.Build
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairs Pairline with this phone's Wireless debugging using the 6-digit code from "Pair device with pairing code"
 * (D-054). The same steps as AOSP's adb client (packages/modules/adb/pairing_connection):
 *
 * 1. TLS 1.3 to the pairing port ([PairingTls], BoringSSL), and 64 bytes of exported keying material from it.
 * 2. SPAKE2 with password = code + keying material; a wrong code gives each side a different key.
 * 3. Each side sends its "peer info" (ours: Pairline's public key) encrypted with AES-128-GCM under a key from HKDF.
 *    adbd stores our key only if it could decrypt it, so only the person looking at the code can pair.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class AdbPairingClient(private val port: Int, private val code: String, private val key: AdbKey) {

    fun pair() {
        PairingTls.connect(port, key).use { tls ->
            val keyMaterial = tls.exportKeyingMaterial(EXPORTED_KEY_LABEL, EXPORTED_KEY_SIZE)
            Spake2.create().use { spake ->
                val ours = spake.generateMessage(code.toByteArray(Charsets.US_ASCII) + keyMaterial)
                writePacket(tls, TYPE_SPAKE2, ours)
                val theirs = readPacket(tls, TYPE_SPAKE2)
                val shared = spake.processMessage(theirs) ?: throw AdbWrongCodeException()
                val aesKey = PairingCrypto.hkdfSha256(shared, AES_KEY_INFO, 16)

                val peerInfo = ByteArray(PEER_INFO_SIZE)
                peerInfo[0] = PEER_INFO_RSA_PUBLIC_KEY
                key.adbPublicKey.copyInto(peerInfo, 1, 0, minOf(key.adbPublicKey.size, PEER_INFO_SIZE - 1))
                writePacket(tls, TYPE_PEER_INFO, PairingCrypto.seal(aesKey, 0, peerInfo))
                val reply = try {
                    readPacket(tls, TYPE_PEER_INFO)
                } catch (_: EOFException) {
                    // adbd hangs up when it can't decrypt our peer info: the code was wrong.
                    throw AdbWrongCodeException()
                }
                val opened = PairingCrypto.open(aesKey, 0, reply) ?: throw AdbWrongCodeException()
                if (opened.size != PEER_INFO_SIZE) throw AdbPairingException("unexpected peer info")
            }
        }
    }

    private fun writePacket(tls: PairingTls, type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(HEADER_VERSION).put(type).putInt(payload.size).array()
        tls.write(header + payload)
    }

    private fun readPacket(tls: PairingTls, expected: Byte): ByteArray {
        val b = ByteBuffer.wrap(tls.read(HEADER_SIZE)).order(ByteOrder.BIG_ENDIAN)
        val version = b.get()
        val type = b.get()
        val size = b.int
        if (version != HEADER_VERSION) throw AdbPairingException("pairing protocol version $version")
        if (type != expected) throw AdbPairingException("unexpected pairing packet $type")
        if (size <= 0 || size > MAX_PAYLOAD) throw IOException("pairing packet of $size bytes")
        return tls.read(size)
    }

    companion object {
        private val EXPORTED_KEY_LABEL = "adb-label\u0000".toByteArray(Charsets.US_ASCII)
        private const val EXPORTED_KEY_SIZE = 64
        private const val HEADER_VERSION: Byte = 1
        private const val HEADER_SIZE = 6
        private const val TYPE_SPAKE2: Byte = 0
        private const val TYPE_PEER_INFO: Byte = 1
        private const val PEER_INFO_RSA_PUBLIC_KEY: Byte = 0
        private const val PEER_INFO_SIZE = 8192
        private const val MAX_PAYLOAD = PEER_INFO_SIZE * 2
        private val AES_KEY_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)
    }
}

/** BoringSSL TLS 1.3 to this phone's pairing port, 127.0.0.1 only (src/main/cpp/adb_tls_jni.cpp, D-054). */
internal class PairingTls private constructor(private var handle: Long) : Closeable {

    fun exportKeyingMaterial(label: ByteArray, length: Int): ByteArray =
        nativeExportKeyingMaterial(handle, label, length) ?: throw AdbPairingException("no TLS keying material")

    fun write(data: ByteArray) {
        if (!nativeWrite(handle, data)) throw IOException("pairing connection closed")
    }

    /** Exactly [length] bytes; [EOFException] when adbd hangs up first. */
    fun read(length: Int): ByteArray = nativeRead(handle, length) ?: throw EOFException("pairing connection closed")

    override fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    companion object {
        private const val TIMEOUT_MS = 10_000

        fun connect(port: Int, key: AdbKey): PairingTls {
            if (!Spake2.available) throw AdbPairingException("pairing isn't available on this phone's processor")
            val handle = nativeConnect(port, key.certificateDer, key.privateKeyPkcs8, TIMEOUT_MS)
            if (handle == 0L) throw IOException("couldn't open the pairing connection")
            return PairingTls(handle)
        }

        @JvmStatic private external fun nativeConnect(port: Int, certDer: ByteArray, keyPkcs8: ByteArray, timeoutMs: Int): Long

        @JvmStatic private external fun nativeExportKeyingMaterial(handle: Long, label: ByteArray, length: Int): ByteArray?

        @JvmStatic private external fun nativeWrite(handle: Long, data: ByteArray): Boolean

        @JvmStatic private external fun nativeRead(handle: Long, length: Int): ByteArray?

        @JvmStatic private external fun nativeClose(handle: Long)
    }
}

/** The HKDF and AES-GCM steps of adb pairing, with the platform's JCA. Plain JVM code for unit tests. */
internal object PairingCrypto {
    /** RFC 5869 HKDF with SHA-256 and no salt (adb passes none), for up to 32 bytes. */
    fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..32)
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32), "HmacSHA256")) }
        val prk = extract.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        return expand.doFinal(info + byteArrayOf(1)).copyOf(length)
    }

    /** adb's nonce: the message counter as a little-endian 64-bit number, in a 12-byte zero nonce. */
    fun nonce(sequence: Long): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(sequence).array()

    fun seal(key: ByteArray, sequence: Long, plain: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE, key, sequence).doFinal(plain)

    /** Null when the tag doesn't match (a wrong pairing code). */
    fun open(key: ByteArray, sequence: Long, sealed: ByteArray): ByteArray? = try {
        cipher(Cipher.DECRYPT_MODE, key, sequence).doFinal(sealed)
    } catch (_: AEADBadTagException) {
        null
    }

    private fun cipher(mode: Int, key: ByteArray, sequence: Long) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(sequence)))
    }
}
