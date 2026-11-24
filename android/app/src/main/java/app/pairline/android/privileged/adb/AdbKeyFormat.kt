package app.pairline.android.privileged.adb

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * The formats adbd expects for Pairline's key (D-054). Plain JVM code so unit tests can check it.
 *
 * - [adbPublicKey]: adb's own RSA public-key format (AOSP libcrypto_utils/android_pubkey.cpp), base64, followed by
 *   a name. adbd shows that name under Wireless debugging › Paired devices.
 * - [selfSignedCertificate]: a minimal X.509 certificate for the TLS connections. adbd only checks that its public
 *   key is one the user paired; the certificate itself is never shown or trusted by anyone.
 */
internal object AdbKeyFormat {
    const val MODULUS_BYTES = 2048 / 8
    private const val MODULUS_WORDS = MODULUS_BYTES / 4
    private const val STRUCT_SIZE = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4

    /** "<base64 of adb's RSAPublicKey struct> <name>" followed by a NUL, as adb's PeerInfo carries it. */
    fun adbPublicKey(key: RSAPublicKey, name: String): ByteArray {
        require(key.modulus.bitLength() <= MODULUS_BYTES * 8) { "adb keys are 2048-bit RSA" }
        val r32 = BigInteger.ONE.shiftLeft(32)
        // n0inv = -1 / n[0] mod 2^32; rr = (2^2048)^2 mod n (Montgomery constants adbd uses to verify quickly).
        val n0inv = key.modulus.mod(r32).modInverse(r32).negate()
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8).modPow(BigInteger.valueOf(2), key.modulus)
        val buffer = ByteBuffer.allocate(STRUCT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MODULUS_WORDS)
        buffer.putInt(n0inv.toInt())
        buffer.put(littleEndian(key.modulus))
        buffer.put(littleEndian(rr))
        buffer.putInt(key.publicExponent.toInt())
        val base64 = Base64.getEncoder().encode(buffer.array())
        return base64 + " $name\u0000".toByteArray(Charsets.UTF_8)
    }

    private fun littleEndian(value: BigInteger): ByteArray {
        val big = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(big.size <= MODULUS_BYTES)
        return ByteArray(MODULUS_BYTES) { i -> if (i < big.size) big[big.size - 1 - i] else 0 }
    }

    /** A self-signed X.509 v3 certificate (SHA-256 with RSA) for [publicKey], valid from 2020 to 2099. */
    fun selfSignedCertificate(publicKey: RSAPublicKey, privateKey: PrivateKey, commonName: String): ByteArray {
        val sha256WithRsa = Der.sequence(Der.oid("1.2.840.113549.1.1.11"), Der.NULL)
        val name = Der.sequence(Der.set(Der.sequence(Der.oid("2.5.4.3"), Der.utf8(commonName))))
        val validity = Der.sequence(Der.utcTime("200101000000Z"), Der.generalizedTime("20991231235959Z"))
        val tbs = Der.sequence(
            Der.explicit(0, Der.integer(BigInteger.valueOf(2))), // v3
            Der.integer(BigInteger.ONE),
            sha256WithRsa,
            name,
            validity,
            name,
            publicKey.encoded, // SubjectPublicKeyInfo, already DER
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(tbs)
            sign()
        }
        return Der.sequence(tbs, sha256WithRsa, Der.bitString(signature))
    }

    /** Just enough DER for [selfSignedCertificate]. */
    internal object Der {
        val NULL = byteArrayOf(0x05, 0x00)

        fun sequence(vararg parts: ByteArray) = tlv(0x30, concat(parts))
        fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))
        fun integer(value: BigInteger) = tlv(0x02, value.toByteArray())
        fun utf8(text: String) = tlv(0x0C, text.toByteArray(Charsets.UTF_8))
        fun utcTime(text: String) = tlv(0x17, text.toByteArray(Charsets.US_ASCII))
        fun generalizedTime(text: String) = tlv(0x18, text.toByteArray(Charsets.US_ASCII))
        fun bitString(bytes: ByteArray) = tlv(0x03, byteArrayOf(0) + bytes)
        fun explicit(tag: Int, content: ByteArray) = tlv(0xA0 or tag, content)

        fun oid(dotted: String): ByteArray {
            val arcs = dotted.split('.').map { it.toLong() }
            val out = ByteArrayOutputStream()
            out.write((arcs[0] * 40 + arcs[1]).toInt())
            for (arc in arcs.drop(2)) {
                val groups = ArrayList<Int>()
                var v = arc
                do {
                    groups.add((v and 0x7F).toInt())
                    v = v shr 7
                } while (v > 0)
                for (i in groups.indices.reversed()) out.write(groups[i] or if (i > 0) 0x80 else 0)
            }
            return tlv(0x06, out.toByteArray())
        }

        fun tlv(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val length = content.size
            when {
                length < 0x80 -> out.write(length)
                length < 0x100 -> { out.write(0x81); out.write(length) }
                length < 0x10000 -> { out.write(0x82); out.write(length shr 8); out.write(length and 0xFF) }
                else -> { out.write(0x83); out.write(length shr 16); out.write((length shr 8) and 0xFF); out.write(length and 0xFF) }
            }
            out.write(content)
            return out.toByteArray()
        }

        private fun concat(parts: Array<out ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            parts.forEach { out.write(it) }
            return out.toByteArray()
        }
    }
}
