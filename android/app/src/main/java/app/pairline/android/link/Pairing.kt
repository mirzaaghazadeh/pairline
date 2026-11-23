package app.pairline.android.link

import android.content.Context
import androidx.core.content.edit
import app.pairline.android.log.Log
import app.pairline.android.protocol.B64u
import app.pairline.android.protocol.PairFailReason
import app.pairline.android.protocol.PairingClaim
import app.pairline.android.protocol.PairingGate
import app.pairline.android.protocol.PairingUri
import app.pairline.android.protocol.Proto
import app.pairline.android.protocol.wipe
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The pinned identity key of the paired iPhone. One peer per device in v0. */
interface PeerKeyStore {
    fun pinned(): ByteArray?
    fun pin(publicKey: ByteArray)
    fun clear()
}

class PrefsPeerKeyStore(context: Context) : PeerKeyStore {
    private val prefs = context.getSharedPreferences("pairline.peer", Context.MODE_PRIVATE)
    private val _paired = MutableStateFlow(pinned() != null)
    val paired: StateFlow<Boolean> = _paired

    override fun pinned(): ByteArray? = prefs.getString(KEY, null)?.let(B64u::decodeOrNull)
        ?.takeIf { it.size == Proto.PUBLIC_KEY_LENGTH }

    override fun pin(publicKey: ByteArray) {
        // commit = true: the key must be on disk before PAIR_OK goes out.
        prefs.edit(commit = true) { putString(KEY, B64u.encode(publicKey)) }
        _paired.value = true
    }

    override fun clear() {
        prefs.edit(commit = true) { remove(KEY) }
        _paired.value = false
    }

    private companion object {
        const val KEY = "remote_identity_v0"
    }
}

/** A QR code on screen. [uri] holds the secret: show it only as a QR code, never log it. */
class PairingOffer(val uri: String, val createdAt: Long, val expiresAt: Long)

enum class PairingOutcome { NONE, PAIRED, TOO_MANY_ATTEMPTS }

/**
 * Creates and checks single-use pairing secrets (PROTOCOL.md, "Pairing"): 32 random bytes, valid for
 * 5 minutes, used once, held only in memory.
 */
class PairingManager(
    private val deviceId: () -> ByteArray,
    private val identityPublicKey: () -> ByteArray,
    private val peers: PeerKeyStore,
    private val onPairedHook: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) : PairingGate {

    private var secret: ByteArray? = null
    private var expiresAt = 0L
    private var used = false
    private var badMacs = 0

    private val _offer = MutableStateFlow<PairingOffer?>(null)
    val offer: StateFlow<PairingOffer?> = _offer

    private val _outcome = MutableStateFlow(PairingOutcome.NONE)

    /** What happened to the last offer; the pairing screen reacts to it. */
    val outcome: StateFlow<PairingOutcome> = _outcome

    /** Shows a new QR code, replacing any previous one. */
    @Synchronized
    fun newOffer(): PairingOffer {
        secret?.wipe()
        val s = ByteArray(Proto.PAIRING_SECRET_LENGTH).also(random::nextBytes)
        secret = s
        val now = clock()
        expiresAt = now + Proto.PAIRING_SECRET_TTL_MS
        used = false
        badMacs = 0
        _outcome.value = PairingOutcome.NONE
        val offer = PairingOffer(PairingUri(deviceId(), identityPublicKey(), s).encode(), now, expiresAt)
        _offer.value = offer
        Log.i(TAG, "pairing code shown")
        return offer
    }

    /** The current offer if it's still usable, else a new one (debug tools). */
    @Synchronized
    fun currentOrNewOffer(): PairingOffer {
        val current = _offer.value
        return if (current != null && !used && secret != null && clock() < expiresAt) current else newOffer()
    }

    /** The QR code left the screen: forget the secret. */
    @Synchronized
    fun cancel() {
        secret?.wipe()
        secret = null
        _offer.value = null
    }

    @Synchronized
    override fun claim(): PairingClaim {
        if (used) return PairingClaim.Refused(PairFailReason.USED)
        val s = secret ?: return PairingClaim.Refused(PairFailReason.EXPIRED)
        if (clock() >= expiresAt) {
            cancel()
            return PairingClaim.Refused(PairFailReason.EXPIRED)
        }
        return PairingClaim.Granted(s.copyOf())
    }

    @Synchronized
    override fun onBadMac() {
        badMacs++
        Log.w(TAG, "pairing attempt with a wrong code ($badMacs)")
        if (badMacs >= MAX_BAD_MACS) {
            cancel()
            _outcome.value = PairingOutcome.TOO_MANY_ATTEMPTS
        }
    }

    @Synchronized
    override fun onPaired(remotePublicKey: ByteArray) {
        peers.pin(remotePublicKey)
        used = true
        secret?.wipe()
        secret = null
        _offer.value = null
        _outcome.value = PairingOutcome.PAIRED
        Log.i(TAG, "paired with a new iPhone")
        onPairedHook()
    }

    fun resetOutcome() {
        _outcome.value = PairingOutcome.NONE
    }

    private companion object {
        const val TAG = "pairing"
        const val MAX_BAD_MACS = 5
    }
}
