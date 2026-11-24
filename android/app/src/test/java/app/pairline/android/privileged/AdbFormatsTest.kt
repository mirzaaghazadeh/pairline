package app.pairline.android.privileged

import app.pairline.android.privileged.adb.AdbKeyFormat
import app.pairline.android.privileged.adb.AdbMessage
import app.pairline.android.privileged.adb.PairingCrypto
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The byte formats Pairline shares with Android's adbd for the built-in helper (D-054). */
class AdbFormatsTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun hkdfMatchesRfc5869WithoutSalt() {
        // RFC 5869 test case 3: SHA-256, no salt, no info (the first 32 of its 42 output bytes).
        val okm = PairingCrypto.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(0), 32)
        assertArrayEquals(hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d"), okm)
    }

    @Test
    fun nonceIsTheCounterLittleEndianInTwelveBytes() {
        assertArrayEquals(ByteArray(12), PairingCrypto.nonce(0))
        assertArrayEquals(hex("010200000000000000000000"), PairingCrypto.nonce(0x0201))
    }

    @Test
    fun aesGcmRoundTripsAndRejectsAWrongKey() {
        val key = ByteArray(16) { it.toByte() }
        val sealed = PairingCrypto.seal(key, 0, "peer info".toByteArray())
        assertEquals(9 + 16, sealed.size) // 16-byte tag, as adb's EVP_aead_aes_128_gcm
        assertArrayEquals("peer info".toByteArray(), PairingCrypto.open(key, 0, sealed))
        assertNull(PairingCrypto.open(ByteArray(16), 0, sealed))
        assertNull(PairingCrypto.open(key, 1, sealed))
    }

    @Test
    fun adbPublicKeyHasAdbsStructAndName() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key = pair.public as RSAPublicKey
        val encoded = AdbKeyFormat.adbPublicKey(key, "Pairline")
        val text = String(encoded, Charsets.UTF_8)
        assertTrue(text.endsWith(" Pairline\u0000"))
        val struct = Base64.getDecoder().decode(text.substringBefore(' '))
        assertEquals(524, struct.size)
        val b = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.int) // modulus size in 32-bit words
        val n0inv = b.int
        val modulus = ByteArray(256).also { b.get(it) }
        val rr = ByteArray(256).also { b.get(it) }
        assertEquals(65537, b.int)

        val n = BigInteger(1, modulus.reversedArray())
        assertEquals(key.modulus, n)
        // n0inv * n[0] ≡ -1 (mod 2^32)
        val r32 = BigInteger.ONE.shiftLeft(32)
        assertEquals(r32.subtract(BigInteger.ONE), n.mod(r32).multiply(BigInteger.valueOf(n0inv.toLong() and 0xFFFFFFFFL)).mod(r32))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), BigInteger(1, rr.reversedArray()))
    }

    @Test
    fun selfSignedCertificateParsesAndVerifies() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val der = AdbKeyFormat.selfSignedCertificate(pair.public as RSAPublicKey, pair.private, "Pairline")
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        cert.verify(pair.public)
        assertEquals(3, cert.version)
        assertEquals(pair.public, cert.publicKey)
        assertEquals("CN=Pairline", cert.subjectX500Principal.name)
        assertEquals("SHA256withRSA", cert.sigAlgName)
    }

    @Test
    fun oidEncodingMatchesKnownBytes() {
        // sha256WithRSAEncryption
        assertArrayEquals(hex("06092a864886f70d01010b"), AdbKeyFormat.Der.oid("1.2.840.113549.1.1.11"))
    }

    @Test
    fun adbMessagesRoundTrip() {
        val m = AdbMessage.text(AdbMessage.OPEN, 1, 0, "shell:echo hi")
        val bytes = m.encode()
        assertEquals(24 + "shell:echo hi".length + 1, bytes.size)
        val back = AdbMessage.read(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(AdbMessage.OPEN, back.command)
        assertEquals(1, back.arg0)
        assertArrayEquals(m.data, back.data)
        // "CNXN" as little-endian ASCII, and its magic is the bitwise complement.
        assertArrayEquals("CNXN".toByteArray(), AdbMessage(AdbMessage.CNXN, 0, 0).encode().copyOf(4))
    }
}
