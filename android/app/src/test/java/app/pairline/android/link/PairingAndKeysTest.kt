package app.pairline.android.link

import app.pairline.android.protocol.B64u
import app.pairline.android.protocol.PairFailReason
import app.pairline.android.protocol.PairingClaim
import app.pairline.android.protocol.PairingUri
import app.pairline.android.protocol.Proto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class PairingAndKeysTest {
    private class MemoryPeers : PeerKeyStore {
        var key: ByteArray? = null
        override fun pinned() = key
        override fun pin(publicKey: ByteArray) { key = publicKey }
        override fun clear() { key = null }
    }

    private var now = 1_000_000L
    private val peers = MemoryPeers()
    private val manager = PairingManager(
        deviceId = { ByteArray(8) { it.toByte() } },
        identityPublicKey = { ByteArray(65).also { it[0] = 4 } },
        peers = peers,
        clock = { now },
    )

    private fun secretOf(offer: PairingOffer): ByteArray = PairingUri.parse(offer.uri)!!.secret

    @Test
    fun noOfferMeansExpired() {
        assertEquals(PairFailReason.EXPIRED, (manager.claim() as PairingClaim.Refused).reason)
    }

    @Test
    fun offerIsValidForFiveMinutes() {
        val offer = manager.newOffer()
        assertEquals(now + Proto.PAIRING_SECRET_TTL_MS, offer.expiresAt)
        val claim = manager.claim() as PairingClaim.Granted
        assertArrayEquals(secretOf(offer), claim.secret)
        now += Proto.PAIRING_SECRET_TTL_MS
        assertEquals(PairFailReason.EXPIRED, (manager.claim() as PairingClaim.Refused).reason)
        assertNull(manager.offer.value)
    }

    @Test
    fun secretWorksOnce() {
        manager.newOffer()
        assertTrue(manager.claim() is PairingClaim.Granted)
        manager.onPaired(ByteArray(65) { 9 })
        assertNotNull(peers.key)
        assertEquals(PairingOutcome.PAIRED, manager.outcome.value)
        assertEquals(PairFailReason.USED, (manager.claim() as PairingClaim.Refused).reason)
        assertNull(manager.offer.value)
    }

    @Test
    fun refreshMakesANewSecret() {
        val a = secretOf(manager.newOffer())
        val b = secretOf(manager.newOffer())
        assertNotEquals(B64u.encode(a), B64u.encode(b))
        assertEquals(32, b.size)
    }

    @Test
    fun tooManyWrongCodesInvalidateTheOffer() {
        manager.newOffer()
        repeat(4) { manager.onBadMac() }
        assertTrue(manager.claim() is PairingClaim.Granted)
        manager.onBadMac()
        assertEquals(PairingOutcome.TOO_MANY_ATTEMPTS, manager.outcome.value)
        assertTrue(manager.claim() is PairingClaim.Refused)
    }

    @Test
    fun cancelForgetsTheSecret() {
        manager.newOffer()
        manager.cancel()
        assertEquals(PairFailReason.EXPIRED, (manager.claim() as PairingClaim.Refused).reason)
    }

    @Test
    fun currentOrNewReusesAValidOffer() {
        val a = manager.currentOrNewOffer()
        assertEquals(a.uri, manager.currentOrNewOffer().uri)
        now += Proto.PAIRING_SECRET_TTL_MS + 1
        assertNotEquals(a.uri, manager.currentOrNewOffer().uri)
    }

    @Test
    fun offerUriContainsDeviceIdAndKey() {
        val parsed = PairingUri.parse(manager.newOffer().uri)!!
        assertArrayEquals(ByteArray(8) { it.toByte() }, parsed.deviceId)
        assertEquals(4, parsed.publicKey[0].toInt())
    }

    // ---- Wrapped identity file (Android 11 path) ----

    private class SoftwareWrapper : KeyWrapper {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun wrap(plaintext: ByteArray, aad: ByteArray): ByteArray {
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            c.updateAAD(aad)
            return iv + c.doFinal(plaintext)
        }
        override fun unwrap(blob: ByteArray, aad: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
            c.updateAAD(aad)
            return c.doFinal(blob, 12, blob.size - 12)
        }
    }

    @Test
    fun wrappedKeyFileRoundTrip() {
        val w = SoftwareWrapper()
        val public = ByteArray(65) { (it * 3).toByte() }
        val private = ByteArray(138) { (it * 7).toByte() }
        val file = WrappedKeyFile.encode(public, private, w)
        assertEquals(WrappedKeyFile.VERSION, file[0])
        val (p, k) = WrappedKeyFile.decode(file, w)
        assertArrayEquals(public, p)
        assertArrayEquals(private, k)
    }

    @Test
    fun wrappedKeyFileDetectsTampering() {
        val w = SoftwareWrapper()
        val file = WrappedKeyFile.encode(ByteArray(65), ByteArray(40), w)
        // The public key is authenticated as associated data.
        val swapped = file.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { WrappedKeyFile.decode(swapped, w) }
        val bad = file.copyOf().also { it[0] = 9 }
        assertThrows(IllegalArgumentException::class.java) { WrappedKeyFile.decode(bad, w) }
    }
}
