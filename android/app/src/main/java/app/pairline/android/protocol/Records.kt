package app.pairline.android.protocol

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM records (PROTOCOL.md, "Records").
 * Wire: counter (8 bytes, big-endian) ‖ ciphertext ‖ 16-byte tag.
 * Nonce: 4-byte big-endian direction label ‖ 8-byte big-endian counter.
 * Associated data: protocol version ‖ channel.
 */
object Records {
    private const val COUNTER_LENGTH = 8

    fun nonce(direction: Int, counter: Long): ByteArray =
        BigEndian.u32(direction.toLong()) + BigEndian.u64(counter)

    fun associatedData(channel: Int): ByteArray = byteArrayOf(Proto.VERSION.toByte(), channel.toByte())

    fun seal(key: ByteArray, direction: Int, counter: Long, channel: Int, plaintext: ByteArray): ByteArray {
        require(counter >= 0)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(Proto.TAG_LENGTH * 8, nonce(direction, counter)),
        )
        cipher.updateAAD(associatedData(channel))
        return BigEndian.u64(counter) + cipher.doFinal(plaintext)
    }

    /** The counter in a record, before authentication. Negative means ≥ 2⁶³, which is never valid. */
    fun counterOf(wire: ByteArray): Long {
        if (wire.size < COUNTER_LENGTH + Proto.TAG_LENGTH) throw ProtocolException("record too short")
        return BigEndian.readU64(wire, 0)
    }

    /** Decrypts a record. Throws [ProtocolException] if it doesn't authenticate. */
    fun open(key: ByteArray, direction: Int, channel: Int, wire: ByteArray): Pair<Long, ByteArray> {
        val counter = counterOf(wire)
        if (counter < 0) throw ProtocolException("record counter out of range")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(Proto.TAG_LENGTH * 8, nonce(direction, counter)),
        )
        cipher.updateAAD(associatedData(channel))
        val plaintext = try {
            cipher.doFinal(wire, COUNTER_LENGTH, wire.size - COUNTER_LENGTH)
        } catch (_: AEADBadTagException) {
            throw ProtocolException("record failed authentication")
        } catch (e: java.security.GeneralSecurityException) {
            throw ProtocolException("record failed authentication")
        }
        return counter to plaintext
    }
}

/** Seals outgoing records in order. Counter 0 is the confirm record; messages start at 1. */
class RecordSealer(private val key: ByteArray, private val direction: Int, private val channel: Int) {
    var nextCounter: Long = 0
        private set

    /** True once the counter is about to reach 2³²: close the link and reconnect (new keys). */
    val exhausted: Boolean get() = nextCounter >= Proto.COUNTER_LIMIT - 1

    fun seal(plaintext: ByteArray): ByteArray {
        if (exhausted) throw ProtocolException("record counter exhausted; reconnect to rekey")
        return Records.seal(key, direction, nextCounter, channel, plaintext).also { nextCounter++ }
    }

    fun wipe() = key.wipe()
}

/**
 * Opens incoming records on an ordered channel. Any counter at or below the last accepted one is a
 * replay, and the caller must drop the connection.
 */
class RecordOpener(private val key: ByteArray, private val direction: Int, private val channel: Int) {
    var lastAccepted: Long = -1
        private set

    fun open(wire: ByteArray): Pair<Long, ByteArray> {
        val counter = Records.counterOf(wire)
        if (counter < 0 || counter >= Proto.COUNTER_LIMIT) throw ProtocolException("record counter out of range")
        if (counter <= lastAccepted) throw ProtocolException("replayed or reordered record")
        val result = Records.open(key, direction, channel, wire)
        lastAccepted = counter
        return result
    }

    fun wipe() = key.wipe()
}

/** Session keys from the handshake (PROTOCOL.md, "Session handshake" step 4). */
class SessionKeys(val okm: ByteArray) {
    init {
        require(okm.size == 64)
    }

    /** B → R. */
    val bridgeToRemote: ByteArray get() = okm.copyOfRange(0, 32)

    /** R → B. */
    val remoteToBridge: ByteArray get() = okm.copyOfRange(32, 64)

    /** Wi-Fi media channel keys (M5): first 32 bytes B→R, last 32 R→B. */
    fun media(): Pair<ByteArray, ByteArray> = split(Hkdf.derive(okm, ByteArray(0), Proto.MEDIA_INFO, 64))

    /** Wi-Fi bulk channel keys (M5). */
    fun bulk(): Pair<ByteArray, ByteArray> = split(Hkdf.derive(okm, ByteArray(0), Proto.BULK_INFO, 64))

    fun wipe() = okm.wipe()

    private fun split(k: ByteArray) = k.copyOfRange(0, 32) to k.copyOfRange(32, 64)

    companion object {
        /** salt = SHA-256(helloR ‖ helloB); okm = HKDF(ikm, salt, "pairline v0 session", 64). */
        fun derive(ikm: ByteArray, helloRemote: ByteArray, helloBridge: ByteArray): SessionKeys {
            val salt = Sha256.digest(helloRemote, helloBridge)
            return SessionKeys(Hkdf.derive(ikm, salt, Proto.SESSION_INFO, 64))
        }

        /**
         * The bridge's side: ikm = ECDH(eB, eR) ‖ ECDH(sB, eR) ‖ ECDH(eB, sR).
         */
        fun forBridge(
            bridgeStatic: StaticKey,
            bridgeEphemeral: StaticKey,
            remoteStatic: ByteArray,
            helloRemote: Hello,
            helloBridge: Hello,
        ): SessionKeys {
            val eR = P256.decodePublicKey(helloRemote.ephemeralPublicKey)
            val sR = P256.decodePublicKey(remoteStatic)
            val ikm = bridgeEphemeral.agree(eR) + bridgeStatic.agree(eR) + bridgeEphemeral.agree(sR)
            try {
                return derive(ikm, helloRemote.encode(), helloBridge.encode())
            } finally {
                ikm.wipe()
            }
        }

        /** The remote's side, used by tests and the debug tools: ECDH(eR, eB) ‖ ECDH(eR, sB) ‖ ECDH(sR, eB). */
        fun forRemote(
            remoteStatic: StaticKey,
            remoteEphemeral: StaticKey,
            bridgeStatic: ByteArray,
            helloRemote: Hello,
            helloBridge: Hello,
        ): SessionKeys {
            val eB = P256.decodePublicKey(helloBridge.ephemeralPublicKey)
            val sB = P256.decodePublicKey(bridgeStatic)
            val ikm = remoteEphemeral.agree(eB) + remoteEphemeral.agree(sB) + remoteStatic.agree(eB)
            try {
                return derive(ikm, helloRemote.encode(), helloBridge.encode())
            } finally {
                ikm.wipe()
            }
        }
    }
}
