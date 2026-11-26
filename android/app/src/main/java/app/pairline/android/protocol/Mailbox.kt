package app.pairline.android.protocol

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Remote mode (PROTOCOL.md, "Remote mode (v1, D-072)"): mailbox records, each sealed on its own, and the crypto and
 * invitation link of remote pairing. Pure Kotlin on JCA, tested against protocol/test-vectors/v1-remote.json.
 */

/** Header byte 1, and how long the header of that kind is. */
enum class MailboxKind(val wire: Int, val headerLength: Int) {
    /** Sealed with the pinned identity keys: one-way Noise K (`e, es, ss`). The header ends with the ephemeral key. */
    SEALED(0x01, Mailbox.BASE_HEADER_LENGTH + Proto.PUBLIC_KEY_LENGTH),

    /** Remote pairing, sealed with keys from the invitation secret. */
    INVITE(0x02, Mailbox.BASE_HEADER_LENGTH);

    companion object {
        fun of(wire: Int): MailboxKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** Header byte 2: the sender's role, 'B' for B→R and 'R' for R→B. */
enum class MailboxDirection(val wire: Byte) {
    BRIDGE_TO_REMOTE(Proto.ROLE_BRIDGE),
    REMOTE_TO_BRIDGE(Proto.ROLE_REMOTE);

    companion object {
        fun of(wire: Byte): MailboxDirection? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * version (1) ‖ kind (1) ‖ direction (1) ‖ record id (16) ‖ time in ms since the epoch (8, big-endian) ‖ the sender's
 * ephemeral public key (65, kind 1 only). The header is also the record's associated data.
 */
class MailboxHeader(
    val kind: MailboxKind,
    val direction: MailboxDirection,
    val recordId: ByteArray,
    val timeMs: Long,
    val ephemeralPublicKey: ByteArray? = null,
) {
    init {
        require(recordId.size == Mailbox.RECORD_ID_LENGTH)
        require(timeMs >= 0)
        require((kind == MailboxKind.SEALED) == (ephemeralPublicKey != null))
        require(ephemeralPublicKey == null || ephemeralPublicKey.size == Proto.PUBLIC_KEY_LENGTH)
    }

    /** The CloudKit record name: the record id as 32 lowercase hex digits. */
    val recordName: String get() = Hex.encode(recordId)

    fun encode(): ByteArray =
        byteArrayOf(Mailbox.VERSION.toByte(), kind.wire.toByte(), direction.wire) + recordId +
            BigEndian.u64(timeMs) + (ephemeralPublicKey ?: ByteArray(0))

    /** The receiver's clock rule: at most 14 days old and at most 10 minutes ahead of [nowMs]. */
    fun isTimely(nowMs: Long): Boolean = timeMs >= nowMs - Mailbox.MAX_AGE_MS && timeMs <= nowMs + Mailbox.MAX_AHEAD_MS

    companion object {
        private const val ID_OFFSET = 3
        private const val TIME_OFFSET = ID_OFFSET + Mailbox.RECORD_ID_LENGTH

        /**
         * Reads the header at the start of [sealed]. Throws [ProtocolException] for an unknown version, kind or
         * direction, a time of 2⁶³ or more, an ephemeral key that isn't a P-256 point, no room for the tag, or more
         * ciphertext than [Mailbox.MAX_PLAINTEXT].
         */
        fun parse(sealed: ByteArray): MailboxHeader {
            if (sealed.size < Mailbox.BASE_HEADER_LENGTH) throw ProtocolException("mailbox record too short")
            if (sealed[0].toInt() != Mailbox.VERSION) throw ProtocolException("unsupported mailbox record version")
            val kind = MailboxKind.of(sealed[1].toInt() and 0xff)
                ?: throw ProtocolException("unknown mailbox record kind")
            val direction = MailboxDirection.of(sealed[2])
                ?: throw ProtocolException("unknown mailbox record direction")
            val ciphertext = sealed.size - kind.headerLength - Proto.TAG_LENGTH
            if (ciphertext < 0) throw ProtocolException("mailbox record too short")
            if (ciphertext > Mailbox.MAX_PLAINTEXT) throw ProtocolException("mailbox record too long")
            val time = BigEndian.readU64(sealed, TIME_OFFSET)
            if (time < 0) throw ProtocolException("mailbox record time out of range")
            val ephemeral = if (kind == MailboxKind.SEALED) {
                sealed.copyOfRange(Mailbox.BASE_HEADER_LENGTH, kind.headerLength).also { P256.decodePublicKey(it) }
            } else {
                null
            }
            return MailboxHeader(kind, direction, sealed.copyOfRange(ID_OFFSET, TIME_OFFSET), time, ephemeral)
        }
    }
}

/** An opened record: its header and the plaintext, a UTF-8 JSON message from the catalog. */
class MailboxRecord(val header: MailboxHeader, val plaintext: ByteArray)

/** Sealing and opening mailbox records (PROTOCOL.md, "Mailbox records" and "Remote pairing" step 2). */
object Mailbox {
    const val VERSION: Int = 1
    const val RECORD_ID_LENGTH: Int = 16

    /** version, kind, direction, record id and time: all of a kind 2 header, the start of a kind 1 header. */
    const val BASE_HEADER_LENGTH: Int = 3 + RECORD_ID_LENGTH + 8

    const val MAX_PLAINTEXT: Int = 48 * 1024
    const val MAX_AGE_MS: Long = 14 * 24 * 60 * 60 * 1000L
    const val MAX_AHEAD_MS: Long = 10 * 60 * 1000L

    private const val NONCE_LENGTH = 12
    private val MAILBOX_INFO = "pairline v1 mailbox".toByteArray(Charsets.US_ASCII)
    private val INVITE_INFO = "pairline v1 invite".toByteArray(Charsets.US_ASCII)

    fun newRecordId(random: SecureRandom): ByteArray = ByteArray(RECORD_ID_LENGTH).also(random::nextBytes)

    /**
     * Kind 1: seals [plaintext] from [sender] (this phone's identity key) to the pinned peer [recipient] (its 65-byte
     * public key), with a fresh ephemeral key from [random]. Returns header ‖ ciphertext ‖ tag.
     */
    fun seal(
        sender: StaticKey,
        recipient: ByteArray,
        direction: MailboxDirection,
        recordId: ByteArray,
        timeMs: Long,
        plaintext: ByteArray,
        random: SecureRandom,
    ): ByteArray = seal(sender, recipient, direction, recordId, timeMs, plaintext, SoftwareKey.generate(random))

    /** Kind 1 with a given ephemeral key, for the test vectors only. A real record always gets a fresh key. */
    internal fun seal(
        sender: StaticKey,
        recipient: ByteArray,
        direction: MailboxDirection,
        recordId: ByteArray,
        timeMs: Long,
        plaintext: ByteArray,
        ephemeral: StaticKey,
    ): ByteArray {
        require(plaintext.size <= MAX_PLAINTEXT)
        val peer = P256.decodePublicKey(recipient)
        val header = MailboxHeader(MailboxKind.SEALED, direction, recordId, timeMs, ephemeral.publicKey).encode()
        // ikm = ECDH(e, sP) ‖ ECDH(sS, sP)
        val ikm = ephemeral.agree(peer) + sender.agree(peer)
        val key = recordKey(ikm, header).also { ikm.wipe() }
        try {
            return header + aead(Cipher.ENCRYPT_MODE, key, ByteArray(NONCE_LENGTH), header).doFinal(plaintext)
        } finally {
            key.wipe()
        }
    }

    /**
     * Kind 1: opens a record that the pinned peer [sender] (its 65-byte public key) sealed to [recipient] (this phone's
     * identity key). Throws [ProtocolException] unless the header is well formed, of kind 1 and from [direction], and
     * the record authenticates. The caller still checks [MailboxHeader.isTimely] and that the record id is new.
     */
    fun open(recipient: StaticKey, sender: ByteArray, direction: MailboxDirection, sealed: ByteArray): MailboxRecord {
        val header = MailboxHeader.parse(sealed)
        val ephemeral = header.ephemeralPublicKey
        if (header.kind != MailboxKind.SEALED || ephemeral == null) {
            throw ProtocolException("not a sealed mailbox record")
        }
        if (header.direction != direction) throw ProtocolException("mailbox record from the wrong direction")
        // ikm = ECDH(sP, e) ‖ ECDH(sP, sS)
        val ikm = recipient.agree(P256.decodePublicKey(ephemeral)) + recipient.agree(P256.decodePublicKey(sender))
        val ad = sealed.copyOfRange(0, MailboxKind.SEALED.headerLength)
        val key = recordKey(ikm, ad).also { ikm.wipe() }
        try {
            return MailboxRecord(header, decrypt(key, ByteArray(NONCE_LENGTH), ad, sealed))
        } finally {
            key.wipe()
        }
    }

    /** key = HKDF-SHA256(ikm, salt = SHA-256(header), info = "pairline v1 mailbox", L = 32). */
    internal fun recordKey(ikm: ByteArray, header: ByteArray): ByteArray =
        Hkdf.derive(ikm, Sha256.digest(header), MAILBOX_INFO, Proto.KEY_LENGTH)

    /** HKDF-SHA256(invitation secret, salt = empty, info = "pairline v1 invite", L = 64): (B→R, R→B). */
    fun inviteKeys(inviteSecret: ByteArray): Pair<ByteArray, ByteArray> {
        require(inviteSecret.size == RemotePairing.SECRET_LENGTH)
        val k = Hkdf.derive(inviteSecret, ByteArray(0), INVITE_INFO, 2 * Proto.KEY_LENGTH)
        try {
            return k.copyOfRange(0, Proto.KEY_LENGTH) to k.copyOfRange(Proto.KEY_LENGTH, k.size)
        } finally {
            k.wipe()
        }
    }

    /**
     * Kind 2: seals a remote pairing message with the invitation's key for [direction]. The nonce is the record id's
     * first 12 bytes, so every record needs a fresh random id.
     */
    fun sealInvite(
        inviteSecret: ByteArray,
        direction: MailboxDirection,
        recordId: ByteArray,
        timeMs: Long,
        plaintext: ByteArray,
    ): ByteArray {
        require(plaintext.size <= MAX_PLAINTEXT)
        val header = MailboxHeader(MailboxKind.INVITE, direction, recordId, timeMs).encode()
        val key = inviteKey(inviteSecret, direction)
        try {
            return header + aead(Cipher.ENCRYPT_MODE, key, recordId.copyOf(NONCE_LENGTH), header).doFinal(plaintext)
        } finally {
            key.wipe()
        }
    }

    /** Kind 2: opens a remote pairing record from [direction]. Throws [ProtocolException] like [open]. */
    fun openInvite(inviteSecret: ByteArray, direction: MailboxDirection, sealed: ByteArray): MailboxRecord {
        val header = MailboxHeader.parse(sealed)
        if (header.kind != MailboxKind.INVITE) throw ProtocolException("not an invitation record")
        if (header.direction != direction) throw ProtocolException("mailbox record from the wrong direction")
        val ad = sealed.copyOfRange(0, MailboxKind.INVITE.headerLength)
        val key = inviteKey(inviteSecret, direction)
        try {
            return MailboxRecord(header, decrypt(key, header.recordId.copyOf(NONCE_LENGTH), ad, sealed))
        } finally {
            key.wipe()
        }
    }

    private fun inviteKey(inviteSecret: ByteArray, direction: MailboxDirection): ByteArray {
        val (bridgeToRemote, remoteToBridge) = inviteKeys(inviteSecret)
        return if (direction == MailboxDirection.BRIDGE_TO_REMOTE) {
            remoteToBridge.wipe()
            bridgeToRemote
        } else {
            bridgeToRemote.wipe()
            remoteToBridge
        }
    }

    private fun aead(mode: Int, key: ByteArray, nonce: ByteArray, header: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(Proto.TAG_LENGTH * 8, nonce))
            updateAAD(header)
        }

    /** Decrypts what follows [header] in [sealed]. */
    private fun decrypt(key: ByteArray, nonce: ByteArray, header: ByteArray, sealed: ByteArray): ByteArray = try {
        aead(Cipher.DECRYPT_MODE, key, nonce, header).doFinal(sealed, header.size, sealed.size - header.size)
    } catch (_: GeneralSecurityException) {
        throw ProtocolException("mailbox record failed authentication")
    }
}

/** Remote pairing's commitment and comparison code (PROTOCOL.md, "Remote pairing" steps 3 and 6). */
object RemotePairing {
    const val SECRET_LENGTH: Int = 32
    const val NONCE_LENGTH: Int = 32
    const val CODE_DIGITS: Int = 6

    private val COMMIT_LABEL = "pairline v1 commit".toByteArray(Charsets.US_ASCII)
    private val CODE_LABEL = "pairline v1 code".toByteArray(Charsets.US_ASCII)

    /** commit = SHA-256("pairline v1 commit" ‖ pubR ‖ pubB ‖ nonceB). B sends it before it sees nonceR. */
    fun commit(remotePublic: ByteArray, bridgePublic: ByteArray, bridgeNonce: ByteArray): ByteArray {
        require(remotePublic.size == Proto.PUBLIC_KEY_LENGTH && bridgePublic.size == Proto.PUBLIC_KEY_LENGTH)
        require(bridgeNonce.size == NONCE_LENGTH)
        return Sha256.digest(COMMIT_LABEL, remotePublic, bridgePublic, bridgeNonce)
    }

    /**
     * The six digits both people compare: the first 4 bytes of
     * HMAC-SHA256(is, "pairline v1 code" ‖ pubR ‖ pubB ‖ nonceR ‖ nonceB), big-endian, modulo 1,000,000.
     * Always ASCII digits; the screen may localize them.
     */
    fun code(
        inviteSecret: ByteArray,
        remotePublic: ByteArray,
        bridgePublic: ByteArray,
        remoteNonce: ByteArray,
        bridgeNonce: ByteArray,
    ): String {
        require(inviteSecret.size == SECRET_LENGTH)
        require(remotePublic.size == Proto.PUBLIC_KEY_LENGTH && bridgePublic.size == Proto.PUBLIC_KEY_LENGTH)
        require(remoteNonce.size == NONCE_LENGTH && bridgeNonce.size == NONCE_LENGTH)
        val mac = HmacSha256.mac(inviteSecret, CODE_LABEL, remotePublic, bridgePublic, remoteNonce, bridgeNonce)
        return (BigEndian.readU32(mac, 0) % 1_000_000).toString().padStart(CODE_DIGITS, '0')
    }
}

/** Where the mailboxes live (`b` in the invitation, `backend` in `remote.config`). */
enum class RemoteBackend(val wire: String) {
    PRIVATE("private"),
    PUBLIC("public");

    companion object {
        fun of(wire: String): RemoteBackend? = entries.firstOrNull { it.wire == wire }
    }
}

/** The CloudKit environment (`e` in the invitation, `env` in `remote.config`). */
enum class RemoteEnvironment(val wire: String) {
    PRODUCTION("production"),
    DEVELOPMENT("development");

    companion object {
        fun of(wire: String): RemoteEnvironment? = entries.firstOrNull { it.wire == wire }
    }
}

/** The three mailbox ids, chosen by R: R→B, B→R alert and B→R quiet (`m`, `a`, `q`; `mb {b, a, q}`). */
data class RemoteMailboxes(val toBridge: String, val alert: String, val quiet: String) {
    /** Each is 32 lowercase hex digits, and no two are the same. */
    fun isValid(): Boolean =
        listOf(toBridge, alert, quiet).all(::isMailboxId) && setOf(toBridge, alert, quiet).size == 3

    companion object {
        fun isMailboxId(text: String): Boolean =
            text.length == 2 * Mailbox.RECORD_ID_LENGTH && text.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

/**
 * `pairline://invite?v=1&k=<pubR>&s=<is>&b=<backend>&e=<env>&t=<token>&m=<R→B id>&a=<alert id>&q=<quiet id>&x=<expiry>`
 * (PROTOCOL.md, "Remote pairing" step 1). It holds the invitation secret and R's web auth token: never log it.
 */
data class RemoteInvitation(
    val remotePublicKey: ByteArray,
    val inviteSecret: ByteArray,
    val backend: RemoteBackend,
    val environment: RemoteEnvironment,
    val token: String,
    val mailboxes: RemoteMailboxes,
    /** Unix seconds. */
    val expiresAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is RemoteInvitation && remotePublicKey.contentEquals(other.remotePublicKey) &&
            inviteSecret.contentEquals(other.inviteSecret) && backend == other.backend &&
            environment == other.environment && token == other.token && mailboxes == other.mailboxes &&
            expiresAt == other.expiresAt

    override fun hashCode(): Int = 31 * remotePublicKey.contentHashCode() + expiresAt.hashCode()

    /** Leaves out the secret and the token. */
    override fun toString(): String =
        "RemoteInvitation(backend=${backend.wire}, env=${environment.wire}, expiresAt=$expiresAt)"

    companion object {
        const val PREFIX = "pairline://invite?"
        private const val VERSION = "1"
        private const val MAX_EXPIRY_DIGITS = 12

        /**
         * Returns null for anything malformed: another version, a missing or repeated parameter, a key or secret of the
         * wrong length or not in unpadded base64url, a key off P-256, an unknown backend or environment, a bad mailbox
         * id, or an expiry that isn't decimal seconds. Values are percent-decoded ('+' stays '+') and must decode to
         * printable ASCII. Unknown parameters are ignored.
         */
        fun parse(uri: String): RemoteInvitation? {
            if (!uri.startsWith(PREFIX)) return null
            val params = HashMap<String, String>()
            for (part in uri.substring(PREFIX.length).split('&')) {
                val eq = part.indexOf('=')
                if (eq <= 0) return null
                val value = percentDecode(part.substring(eq + 1)) ?: return null
                if (params.put(part.substring(0, eq), value) != null) return null
            }
            if (params["v"] != VERSION) return null
            val key = params["k"]?.let(::unpaddedBase64Url)?.takeIf { it.size == Proto.PUBLIC_KEY_LENGTH }
                ?: return null
            try {
                P256.decodePublicKey(key)
            } catch (_: ProtocolException) {
                return null
            }
            val secret = params["s"]?.let(::unpaddedBase64Url)?.takeIf { it.size == RemotePairing.SECRET_LENGTH }
                ?: return null
            val backend = params["b"]?.let(RemoteBackend::of) ?: return null
            val environment = params["e"]?.let(RemoteEnvironment::of) ?: return null
            val token = params["t"]?.takeIf { it.isNotEmpty() } ?: return null
            val mailboxes = RemoteMailboxes(
                toBridge = params["m"] ?: return null,
                alert = params["a"] ?: return null,
                quiet = params["q"] ?: return null,
            )
            if (!mailboxes.isValid()) return null
            val expiry = params["x"]?.takeIf { it.length in 1..MAX_EXPIRY_DIGITS && it.all { c -> c in '0'..'9' } }
                ?: return null
            return RemoteInvitation(key, secret, backend, environment, token, mailboxes, expiry.toLong())
        }

        private fun unpaddedBase64Url(text: String): ByteArray? = if ('=' in text) null else B64u.decodeOrNull(text)

        /** %XX escapes to bytes; every byte, raw or escaped, must be printable ASCII (no space or control). */
        private fun percentDecode(text: String): String? {
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val c = if (text[i] == '%') {
                    if (i + 2 >= text.length) return null
                    val hi = hexDigit(text[i + 1])
                    val lo = hexDigit(text[i + 2])
                    if (hi < 0 || lo < 0) return null
                    i += 3
                    ((hi shl 4) or lo).toChar()
                } else {
                    text[i++]
                }
                if (c !in '!'..'~') return null
                out.append(c)
            }
            return out.toString()
        }

        private fun hexDigit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}
