package app.pairline.android.protocol

import org.json.JSONException
import org.json.JSONObject
import java.security.SecureRandom

/** Validates pairing attempts against the QR code currently on screen. */
interface PairingGate {
    /** Called for each PAIR_HELLO: the secret to check the MAC with, or why pairing isn't possible. */
    fun claim(): PairingClaim

    /** The MAC didn't match. Too many failures invalidate the code. */
    fun onBadMac()

    /** The MAC matched: mark the secret used and pin [remotePublicKey], replacing any previous peer. */
    fun onPaired(remotePublicKey: ByteArray)
}

sealed interface PairingClaim {
    class Granted(val secret: ByteArray) : PairingClaim
    class Refused(val reason: PairFailReason) : PairingClaim
}

/** What the session needs from the outside world. Every call happens on the link thread. */
interface SessionCallbacks {
    fun sendFrame(frame: ByteArray)
    fun close(reason: String)
    fun pinnedRemoteKey(): ByteArray?
    fun onPaired() {}
    fun onEstablished() {}
    fun onMessage(message: JSONObject)

    /** An authentic `BULK` record's plaintext (Wi-Fi sessions only). */
    fun onBulk(plaintext: ByteArray) {}

    /** An authentic `MEDIA` record's plaintext (Bluetooth and debug TCP sessions only, D-072). */
    fun onLinkMedia(plaintext: ByteArray) {}
}

/**
 * The bridge (B) side of one connection: pairing, the session handshake and records
 * (docs/PROTOCOL.md). Transport-independent and single-threaded; BLE and TCP feed it whole frames.
 *
 * A Wi-Fi session ([wifiChannels]) also derives the media and bulk keys and accepts `BULK` frames (M5). Any other
 * session (Bluetooth, debug TCP, remote mode's direct connection) derives the link media keys and accepts `MEDIA`
 * frames: call audio (D-072). Remote mode's direct connection ([allowPairing] false) closes on `PAIR_HELLO` without
 * answering (D-080).
 */
class BridgeSession(
    private val identity: StaticKey,
    private val pairing: PairingGate,
    private val callbacks: SessionCallbacks,
    private val random: SecureRandom = SecureRandom(),
    private val newEphemeral: () -> StaticKey = { SoftwareKey.generate(random) },
    private val wifiChannels: Boolean = false,
    private val allowPairing: Boolean = true,
) {
    enum class State { AWAIT_FIRST, AWAIT_HELLO, AWAIT_CONFIRM, ESTABLISHED, CLOSED }

    var state: State = State.AWAIT_FIRST
        private set

    /** True if this connection paired a new iPhone before its handshake. */
    var pairedOnThisConnection: Boolean = false
        private set

    private var sealer: RecordSealer? = null
    private var opener: RecordOpener? = null
    private var bulkSealer: RecordSealer? = null
    private var bulkOpener: RecordOpener? = null
    private var mediaKeys: Pair<ByteArray, ByteArray>? = null
    private var linkMediaSealer: LinkMediaSealer? = null
    private var linkMediaOpener: RecordOpener? = null
    private var linkMediaHandedOut = false

    fun onFrame(bytes: ByteArray) {
        if (state == State.CLOSED) return
        try {
            val frame = Frame.decode(bytes)
            when (frame.type) {
                FrameType.PAIR_HELLO -> onPairHello(frame.body)
                FrameType.HELLO -> onHello(frame.body)
                FrameType.RECORD -> onRecord(frame.body)
                FrameType.BULK -> onBulk(frame.body)
                FrameType.MEDIA -> onLinkMedia(frame.body)
                FrameType.PAIR_OK, FrameType.PAIR_FAIL -> fail("unexpected ${frame.type} from the remote")
            }
        } catch (e: ProtocolException) {
            fail(e.message ?: "protocol error")
        } catch (e: Exception) {
            // For example a Keystore error during key agreement: close now rather than leave the iPhone waiting.
            fail("internal error (${e.javaClass.simpleName})")
        }
    }

    /** Sends one message. Only valid after the handshake; returns false otherwise. */
    fun send(message: JSONObject): Boolean {
        if (state != State.ESTABLISHED) return false
        val s = sealer ?: return false
        if (s.exhausted) {
            fail("record counter limit reached; reconnecting to rekey")
            return false
        }
        val record = s.seal(message.toUtf8())
        if (record.size + 1 > Proto.MAX_FRAME) {
            // A bug on our side (pages must stay under 30 KB); never send an oversized frame.
            throw IllegalStateException("message too large for one frame")
        }
        callbacks.sendFrame(Frame(FrameType.RECORD, record).encode())
        return true
    }

    /** Seals one `BULK` record (Wi-Fi sessions, after the handshake). Returns the frame, or null if not possible. */
    fun sealBulk(plaintext: ByteArray): ByteArray? {
        if (state != State.ESTABLISHED) return null
        val s = bulkSealer ?: return null
        if (s.exhausted) {
            fail("bulk counter limit reached; reconnecting to rekey")
            return null
        }
        return Frame(FrameType.BULK, s.seal(plaintext)).encode()
    }

    /**
     * The media keys of this Wi-Fi session (B→R to seal, R→B to open), handed out once after the handshake.
     * The caller owns and wipes them.
     */
    fun takeMediaKeys(): Pair<ByteArray, ByteArray>? {
        if (state != State.ESTABLISHED) return null
        return mediaKeys.also { mediaKeys = null }
    }

    /**
     * The sealer for this Bluetooth or debug TCP session's `MEDIA` frames (B→R), handed out once after the handshake.
     * It stays tied to the session: closing the session wipes it.
     */
    fun takeLinkMediaSealer(): LinkMediaSealer? {
        if (state != State.ESTABLISHED || linkMediaHandedOut) return null
        linkMediaHandedOut = true
        return linkMediaSealer
    }

    fun close(reason: String) {
        if (state == State.CLOSED) return
        state = State.CLOSED
        sealer?.wipe()
        opener?.wipe()
        bulkSealer?.wipe()
        bulkOpener?.wipe()
        mediaKeys?.let { (a, b) -> a.wipe(); b.wipe() }
        mediaKeys = null
        linkMediaSealer?.wipe()
        linkMediaOpener?.wipe()
        callbacks.close(reason)
    }

    private fun onPairHello(body: ByteArray) {
        if (!allowPairing) throw ProtocolException("pairing isn't possible on this connection")
        if (state != State.AWAIT_FIRST) throw ProtocolException("pair_hello in the wrong state")
        // Malformed pair_hello (bad JSON, version, lengths or a point off the curve) gets bad_mac
        // (PROTOCOL.md clarifications), without touching the code on screen.
        val hello = try {
            PairHello.parse(body).also { P256.decodePublicKey(it.remotePublicKey) }
        } catch (_: ProtocolException) {
            return refusePairing(PairFailReason.BAD_MAC)
        }
        val secret = when (val claim = pairing.claim()) {
            is PairingClaim.Refused -> return refusePairing(claim.reason)
            is PairingClaim.Granted -> claim.secret
        }
        try {
            val t = PairingCrypto.transcript(identity.publicKey, hello.remotePublicKey, hello.nonce)
            if (!constantTimeEquals(PairingCrypto.remoteMac(secret, t), hello.mac)) {
                pairing.onBadMac()
                return refusePairing(PairFailReason.BAD_MAC)
            }
            val nonceB = ByteArray(Proto.PAIRING_NONCE_LENGTH).also(random::nextBytes)
            val macB = PairingCrypto.bridgeMac(secret, t, nonceB)
            pairing.onPaired(hello.remotePublicKey)
            pairedOnThisConnection = true
            callbacks.sendFrame(Frame(FrameType.PAIR_OK, PairOk(nonceB, macB).toJson().toUtf8()).encode())
            state = State.AWAIT_HELLO
            callbacks.onPaired()
        } finally {
            secret.wipe()
        }
    }

    private fun refusePairing(reason: PairFailReason) {
        callbacks.sendFrame(Frame(FrameType.PAIR_FAIL, pairFailJson(reason).toUtf8()).encode())
        close("pairing refused: ${reason.wire}")
    }

    private fun onHello(body: ByteArray) {
        if (state != State.AWAIT_FIRST && state != State.AWAIT_HELLO) {
            throw ProtocolException("hello in the wrong state")
        }
        val remoteStatic = callbacks.pinnedRemoteKey() ?: throw ProtocolException("not paired")
        val helloR = Hello.decode(body, Proto.ROLE_REMOTE)
        val ephemeral = newEphemeral()
        val randB = ByteArray(Proto.RANDOM_LENGTH).also(random::nextBytes)
        val helloB = Hello(Proto.ROLE_BRIDGE, ephemeral.publicKey, randB)
        val keys = SessionKeys.forBridge(identity, ephemeral, remoteStatic, helloR, helloB)
        sealer = RecordSealer(keys.bridgeToRemote, Proto.DIR_BRIDGE_TO_REMOTE, Proto.CHANNEL_CONTROL)
        opener = RecordOpener(keys.remoteToBridge, Proto.DIR_REMOTE_TO_BRIDGE, Proto.CHANNEL_CONTROL)
        if (wifiChannels) {
            val wifi = WifiKeys.from(keys)
            bulkSealer = RecordSealer(wifi.bulkBridgeToRemote, Proto.DIR_BRIDGE_TO_REMOTE, Proto.CHANNEL_BULK)
            bulkOpener = RecordOpener(wifi.bulkRemoteToBridge, Proto.DIR_REMOTE_TO_BRIDGE, Proto.CHANNEL_BULK)
            mediaKeys = wifi.mediaBridgeToRemote to wifi.mediaRemoteToBridge
        } else {
            val (br, rb) = keys.linkMedia()
            linkMediaSealer = LinkMediaSealer(br, Proto.DIR_BRIDGE_TO_REMOTE)
            linkMediaOpener = RecordOpener(rb, Proto.DIR_REMOTE_TO_BRIDGE, Proto.CHANNEL_LINK_MEDIA)
        }
        keys.wipe()
        callbacks.sendFrame(Frame(FrameType.HELLO, helloB.encode()).encode())
        // Our confirm: counter 0, empty plaintext.
        callbacks.sendFrame(Frame(FrameType.RECORD, sealer!!.seal(ByteArray(0))).encode())
        state = State.AWAIT_CONFIRM
    }

    private fun onRecord(body: ByteArray) {
        val o = opener ?: throw ProtocolException("record before the handshake")
        val (counter, plaintext) = o.open(body)
        when (state) {
            State.AWAIT_CONFIRM -> {
                if (counter != 0L || plaintext.isNotEmpty()) throw ProtocolException("bad confirm")
                state = State.ESTABLISHED
                callbacks.onEstablished()
            }
            State.ESTABLISHED -> {
                val message = try {
                    JSONObject(String(plaintext, Charsets.UTF_8))
                } catch (_: JSONException) {
                    // Authentic but not JSON: a peer bug, not an attack. Skip it.
                    return
                }
                callbacks.onMessage(message)
            }
            else -> throw ProtocolException("record in the wrong state")
        }
    }

    private fun onBulk(body: ByteArray) {
        if (!wifiChannels) throw ProtocolException("bulk frame outside a Wi-Fi session")
        if (state != State.ESTABLISHED) throw ProtocolException("bulk frame before the handshake")
        val o = bulkOpener ?: throw ProtocolException("no bulk keys")
        val (_, plaintext) = o.open(body)
        callbacks.onBulk(plaintext)
    }

    /** Ordered like the control channel: a replayed or forged `MEDIA` record closes the connection (D-072). */
    private fun onLinkMedia(body: ByteArray) {
        if (wifiChannels) throw ProtocolException("media frame on a Wi-Fi session")
        if (state != State.ESTABLISHED) throw ProtocolException("media frame before the handshake")
        val o = linkMediaOpener ?: throw ProtocolException("no link media keys")
        val (_, plaintext) = o.open(body)
        callbacks.onLinkMedia(plaintext)
    }

    private fun fail(reason: String) = close(reason)
}
