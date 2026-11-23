package app.pairline.android.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * P-256 helpers on top of the platform's JCA providers (AGENTS.md rule 8). Nothing here implements
 * an algorithm itself; it only converts between the wire encodings and JCA key objects.
 */
object P256 {
    private const val CURVE = "secp256r1"

    val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec(CURVE))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    fun generateKeyPair(random: SecureRandom): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec(CURVE), random)
            generateKeyPair()
        }

    /** 65-byte uncompressed X9.62 point: 04 ‖ X ‖ Y. */
    fun encodePublicKey(key: ECPublicKey): ByteArray {
        val out = ByteArray(Proto.PUBLIC_KEY_LENGTH)
        out[0] = 0x04
        fixed32(key.w.affineX).copyInto(out, 1)
        fixed32(key.w.affineY).copyInto(out, 33)
        return out
    }

    /** Parses and validates a peer's public key; anything off the curve is rejected. */
    fun decodePublicKey(bytes: ByteArray): ECPublicKey {
        if (bytes.size != Proto.PUBLIC_KEY_LENGTH || bytes[0].toInt() != 0x04) {
            throw ProtocolException("public key must be a 65-byte uncompressed point")
        }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        if (!isOnCurve(x, y)) throw ProtocolException("public key is not on P-256")
        val spec = ECPublicKeySpec(ECPoint(x, y), params)
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    /** Builds a private key from a 32-byte scalar (test vectors, and the Android 11 key store). */
    fun privateKeyFromScalar(scalar: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), params))

    /** ECDH: the 32-byte big-endian X coordinate of the shared point. */
    fun ecdh(privateKey: PrivateKey, peer: ECPublicKey, provider: String? = null): ByteArray {
        val agreement = if (provider == null) {
            KeyAgreement.getInstance("ECDH")
        } else {
            KeyAgreement.getInstance("ECDH", provider)
        }
        agreement.init(privateKey)
        agreement.doPhase(peer, true)
        val secret = agreement.generateSecret()
        if (secret.size != 32) throw ProtocolException("unexpected ECDH output length")
        return secret
    }

    private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
        val p = (params.curve.field as ECFieldFp).p
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        val left = y.multiply(y).mod(p)
        val right = x.multiply(x).multiply(x).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        return left == right
    }

    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0].toInt() == 0 -> raw.copyOfRange(1, 33)
            raw.size < 32 -> ByteArray(32 - raw.size) + raw
            else -> throw IllegalArgumentException("coordinate too large")
        }
    }
}

/** A long-term identity key. The private half may live in Android Keystore and never leave it. */
interface StaticKey {
    /** 65-byte uncompressed public key. */
    val publicKey: ByteArray

    /** ECDH with a peer's public key. */
    fun agree(peer: ECPublicKey): ByteArray
}

/** A key held in process memory: ephemeral keys, the Android 11 identity key and tests. */
class SoftwareKey(private val privateKey: PrivateKey, publicKey: ECPublicKey) : StaticKey {
    override val publicKey: ByteArray = P256.encodePublicKey(publicKey)
    override fun agree(peer: ECPublicKey): ByteArray = P256.ecdh(privateKey, peer)

    companion object {
        fun generate(random: SecureRandom): SoftwareKey {
            val pair = P256.generateKeyPair(random)
            return SoftwareKey(pair.private, pair.public as ECPublicKey)
        }
    }
}

object Sha256 {
    fun digest(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            parts.forEach { update(it) }
            digest()
        }
}

object HmacSha256 {
    fun mac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            // HMAC pads short keys with zeros, so an empty key equals 32 zero bytes; JCA refuses empty keys.
            init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
            parts.forEach { update(it) }
            doFinal()
        }
}

/** HKDF-SHA256 (RFC 5869) built on the platform's HMAC. */
object Hkdf {
    private const val HASH_LENGTH = 32

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = HmacSha256.mac(salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LENGTH)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            previous = HmacSha256.mac(prk, previous, info, byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - written)
            previous.copyInto(out, written, 0, n)
            written += n
            counter++
        }
        return out
    }
}
