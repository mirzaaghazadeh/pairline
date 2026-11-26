package app.pairline.android.link.remote

import app.pairline.android.BuildConfig
import app.pairline.android.log.Log
import app.pairline.android.protocol.B64u
import app.pairline.android.protocol.Hex
import app.pairline.android.protocol.Mailbox
import app.pairline.android.protocol.MailboxDirection
import app.pairline.android.protocol.MailboxHeader
import app.pairline.android.protocol.MailboxKind
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.ProtocolException
import app.pairline.android.protocol.RemoteBackend
import app.pairline.android.protocol.RemoteInvitation
import app.pairline.android.protocol.RemotePairing
import app.pairline.android.protocol.StaticKey
import app.pairline.android.protocol.stringOrNull
import app.pairline.android.protocol.type
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The bridge's messages and checks in remote pairing (PROTOCOL.md, "Remote pairing" steps 3 to 7), with no network or
 * timing: [hello], [reveal] and [confirm] make the records to send, [receive] reads one from the R→B mailbox.
 */
internal class RemotePairingSteps(
    private val invitation: RemoteInvitation,
    private val me: StaticKey,
    private val deviceId: ByteArray,
    private val random: SecureRandom,
    private val bridgeNonce: ByteArray = ByteArray(RemotePairing.NONCE_LENGTH).also(random::nextBytes),
) {
    /** The iPhone sent something that fails a check: the pairing stops. */
    class BadAnswer(message: String) : Exception(message)

    class Outgoing(val name: String, val sealed: ByteArray)

    /** The iPhone's nonce, once its `rpair.nonce` arrived. */
    var remoteNonce: ByteArray? = null
        private set

    /** The iPhone's user confirmed: its kind 1 `rpair.confirm` decrypted. */
    var peerConfirmed: Boolean = false
        private set

    private val remoteKey = invitation.remotePublicKey

    /** `{"t":"rpair.hello","k":<pubB>,"id":<B device id>,"c":<commit>}`: B commits to its nonce first. */
    fun hello(now: Long): Outgoing = invite(
        now,
        JSONObject().put("t", Msg.RPAIR_HELLO)
            .put("k", B64u.encode(me.publicKey))
            .put("id", B64u.encode(deviceId))
            .put("c", B64u.encode(RemotePairing.commit(remoteKey, me.publicKey, bridgeNonce))),
    )

    fun reveal(now: Long): Outgoing = invite(now, JSONObject().put("t", Msg.RPAIR_REVEAL).put("n", B64u.encode(bridgeNonce)))

    /** Kind 1, sealed to the iPhone's key from the invitation. */
    fun confirm(now: Long): Outgoing {
        val id = Mailbox.newRecordId(random)
        val plaintext = JSONObject().put("t", Msg.RPAIR_CONFIRM).toString().toByteArray(Charsets.UTF_8)
        return Outgoing(
            Hex.encode(id),
            Mailbox.seal(me, remoteKey, MailboxDirection.BRIDGE_TO_REMOTE, id, now, plaintext, random),
        )
    }

    /** The six digits both people compare; only once [remoteNonce] is known. */
    fun code(): String = RemotePairing.code(
        invitation.inviteSecret, remoteKey, me.publicKey, checkNotNull(remoteNonce), bridgeNonce,
    )

    /**
     * Reads one record from the R→B mailbox. Returns true if it belonged to this pairing (delete it), false for
     * anything else, which is left alone. Throws [BadAnswer] when the iPhone's message fails a field check or a second,
     * different nonce arrives (someone else has the invitation).
     */
    fun receive(name: String, sealed: ByteArray, now: Long): Boolean {
        val header = try {
            MailboxHeader.parse(sealed)
        } catch (_: ProtocolException) {
            return false
        }
        if (header.direction != MailboxDirection.REMOTE_TO_BRIDGE || !header.isTimely(now) || header.recordName != name) {
            return false
        }
        return when (header.kind) {
            MailboxKind.INVITE -> {
                val record = try {
                    Mailbox.openInvite(invitation.inviteSecret, MailboxDirection.REMOTE_TO_BRIDGE, sealed)
                } catch (_: ProtocolException) {
                    return false
                }
                val message = json(record.plaintext) ?: throw BadAnswer("not JSON")
                if (message.type == Msg.RPAIR_NONCE) {
                    val nonce = field(message, "n", RemotePairing.NONCE_LENGTH)
                    val known = remoteNonce
                    if (known == null) remoteNonce = nonce
                    else if (!known.contentEquals(nonce)) throw BadAnswer("a second, different nonce")
                }
                true
            }
            MailboxKind.SEALED -> {
                val record = try {
                    Mailbox.open(me, remoteKey, MailboxDirection.REMOTE_TO_BRIDGE, sealed)
                } catch (_: ProtocolException) {
                    return false
                }
                // Anything else from the iPhone is for the link once paired.
                if (json(record.plaintext)?.type != Msg.RPAIR_CONFIRM) return false
                peerConfirmed = true
                true
            }
        }
    }

    private fun invite(now: Long, message: JSONObject): Outgoing {
        val id = Mailbox.newRecordId(random)
        val plaintext = message.toString().toByteArray(Charsets.UTF_8)
        return Outgoing(
            Hex.encode(id),
            Mailbox.sealInvite(invitation.inviteSecret, MailboxDirection.BRIDGE_TO_REMOTE, id, now, plaintext),
        )
    }

    private fun json(plaintext: ByteArray): JSONObject? =
        runCatching { JSONObject(String(plaintext, Charsets.UTF_8)) }.getOrNull()

    /** A base64url field without padding of exactly [length] bytes (PROTOCOL.md, "Remote pairing" step 5). */
    private fun field(message: JSONObject, name: String, length: Int): ByteArray {
        val text = message.stringOrNull(name) ?: throw BadAnswer("$name missing")
        if ('=' in text) throw BadAnswer("$name padded")
        return B64u.decodeOrNull(text)?.takeIf { it.size == length } ?: throw BadAnswer("$name malformed")
    }
}

/**
 * Remote pairing on the bridge (D-072, PROTOCOL.md "Remote pairing"): the person at home opens the iPhone's
 * invitation, allows it, and both people compare a 6-digit code on a phone call. Nothing is sent before [allow]. The
 * phones meet in the invitation's mailboxes; the records are deleted once handled.
 */
class RemotePairingFlow(
    private val host: Host,
    private val http: CloudKitWebClient.Http = CloudKitWebClient.HttpsTransport,
    private val allowPublic: Boolean = BuildConfig.DEBUG,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val pollMs: Long = POLL_MS,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    interface Host {
        fun identity(): StaticKey
        fun deviceId(): ByteArray

        /** The user confirmed the codes: pin [remoteKey], replacing any paired iPhone. */
        fun pin(remoteKey: ByteArray)

        /** Both sides confirmed: remote mode on with the invitation's mailboxes and the latest token. */
        fun complete(config: RemoteConfig, token: String)

        /** The pairing stopped after [pin]: forget that iPhone again. */
        fun abandon()
    }

    /** Why an invitation is refused when it's opened. */
    enum class Refusal { MALFORMED, EXPIRED, UNSUPPORTED }

    enum class Failure {
        /** The iPhone didn't answer before the invitation expired. */
        EXPIRED,

        /** The codes weren't confirmed on both phones within 10 minutes. */
        TIMED_OUT,

        /** iCloud refused the invitation's token. */
        TOKEN_REJECTED,

        /** Apple refuses this account's private database to web clients. */
        ICLOUD_REFUSED,

        /** The iPhone's answer failed a check (or two different answers came). */
        BAD_ANSWER,

        /** Something failed on this phone. */
        INTERNAL,
    }

    sealed interface State {
        data object Idle : State

        data class Refused(val reason: Refusal) : State

        /** The invitation is on screen, waiting for Allow. Holds the invitation's secret: never log it. */
        class Asking(val invitation: RemoteInvitation) : State {
            val expiresAtMs: Long get() = invitation.expiresAt * 1000
        }

        data class Waiting(val expiresAtMs: Long, val networkTrouble: Boolean = false) : State

        /** [code] is 6 ASCII digits. */
        data class Comparing(val code: String, val deadline: Long, val networkTrouble: Boolean = false) : State

        data class Confirming(val deadline: Long, val networkTrouble: Boolean = false) : State

        data object Paired : State

        data class Failed(val reason: Failure) : State
    }

    private class Stop(val reason: Failure) : Exception()

    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> Log.e(TAG, "remote pairing failed", e) },
    )
    private val lock = Any()
    private var run = 0
    private var job: Job? = null
    private var pinned = false

    /** This phone's user tapped "The codes match" in the current run. A flag, so a tap is never lost. */
    @Volatile
    private var matched = false

    /** Wakes the run when [matched] is set; a lost wake-up only delays it by one poll. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** A pairing is under way; a new invitation link is ignored meanwhile. */
    val isBusy: Boolean get() = _state.value.let { it is State.Waiting || it is State.Comparing || it is State.Confirming }

    /** Shows an invitation link (`pairline://invite?…`). Returns false while another pairing is under way. */
    fun open(link: String): Boolean {
        synchronized(lock) {
            if (isBusy) {
                Log.i(TAG, "invitation ignored: a remote pairing is under way")
                return false
            }
            val invitation = RemoteInvitation.parse(link)
            val next = when {
                invitation == null -> State.Refused(Refusal.MALFORMED)
                invitation.expiresAt * 1000 <= clock() -> State.Refused(Refusal.EXPIRED)
                invitation.backend == RemoteBackend.PUBLIC && !allowPublic -> State.Refused(Refusal.UNSUPPORTED)
                else -> State.Asking(invitation)
            }
            _state.value = next
            Log.i(TAG, "invitation opened: ${(next as? State.Refused)?.reason?.name?.lowercase() ?: "valid"}")
            return true
        }
    }

    /** The user allowed it: the first record goes out now. */
    fun allow() {
        synchronized(lock) {
            val invitation = (_state.value as? State.Asking)?.invitation ?: return
            if (invitation.expiresAt * 1000 <= clock()) {
                _state.value = State.Refused(Refusal.EXPIRED)
                return
            }
            val id = ++run
            pinned = false
            matched = false
            _state.value = State.Waiting(invitation.expiresAt * 1000)
            Log.i(TAG, "remote pairing allowed")
            job = scope.launch { pair(id, invitation) }
        }
    }

    /** The user tapped "The codes match". */
    fun match() {
        synchronized(lock) {
            if (_state.value !is State.Comparing) return
            matched = true
        }
        wake.trySend(Unit)
    }

    /** Cancel, Back, or Close: stops everything. A pin made after "The codes match" is undone unless both confirmed. */
    fun cancel() {
        synchronized(lock) {
            run++
            job?.cancel()
            job = null
            if (pinned) {
                pinned = false
                host.abandon()
            }
            if (_state.value != State.Idle) Log.i(TAG, "remote pairing closed")
            _state.value = State.Idle
        }
    }

    private suspend fun pair(id: Int, invitation: RemoteInvitation) {
        val tokens = MemoryTokenStore(invitation.token)
        val config = RemoteConfig.of(invitation)
        val client = CloudKitWebClient(invitation.environment, invitation.backend, tokens, http)
        val toBridge = config.address(MailboxSlot.TO_BRIDGE)
        val alert = config.address(MailboxSlot.ALERT)
        val expires = invitation.expiresAt * 1000
        try {
            val steps = RemotePairingSteps(invitation, host.identity(), host.deviceId(), random)
            // Steps 3–5 before the invitation expires. Everything for the iPhone goes in the alert mailbox: it wakes it.
            put(id, client, alert, steps.hello(clock()), expires, Failure.EXPIRED)
            Log.i(TAG, "rpair.hello sent; waiting for the iPhone")
            while (steps.remoteNonce == null) {
                if (clock() >= expires) throw Stop(Failure.EXPIRED)
                delay(pollMs)
                poll(id, client, toBridge, steps)
            }
            put(id, client, alert, steps.reveal(clock()), expires, Failure.EXPIRED)
            val deadline = clock() + CODE_TTL_MS
            update(id) { State.Comparing(steps.code(), deadline) }
            Log.i(TAG, "comparison code shown")

            // Until this phone's user taps "The codes match". The iPhone's confirm may arrive first.
            while (!matched) {
                if (clock() >= deadline) throw Stop(Failure.TIMED_OUT)
                poll(id, client, toBridge, steps)
                withTimeoutOrNull(pollMs) { wake.receive() }
            }
            if (!pin(id, invitation.remotePublicKey)) return
            update(id) { State.Confirming(deadline) }
            put(id, client, alert, steps.confirm(clock()), deadline, Failure.TIMED_OUT)
            Log.i(TAG, "rpair.confirm sent; waiting for the iPhone's")
            while (!steps.peerConfirmed) {
                if (clock() >= deadline) throw Stop(Failure.TIMED_OUT)
                delay(pollMs)
                poll(id, client, toBridge, steps)
            }
            finish(id, config, tokens.load() ?: invitation.token)
        } catch (e: Stop) {
            fail(id, e.reason)
        } catch (_: CloudKitWebClient.TokenRejected) {
            fail(id, Failure.TOKEN_REJECTED)
        } catch (_: CloudKitWebClient.PrivateDatabaseRefused) {
            fail(id, Failure.ICLOUD_REFUSED)
        } catch (e: RemotePairingSteps.BadAnswer) {
            Log.w(TAG, "the iPhone's answer failed a check (${e.message})")
            fail(id, Failure.BAD_ANSWER)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ours, not the iPhone's (a Keystore that isn't ready, for example).
            Log.e(TAG, "remote pairing failed on this phone", e)
            fail(id, Failure.INTERNAL)
        }
    }

    /** Writes [record], retrying network errors until [deadline]. */
    private suspend fun put(
        id: Int,
        client: CloudKitWebClient,
        box: CloudKitWebClient.MailboxAddress,
        record: RemotePairingSteps.Outgoing,
        deadline: Long,
        late: Failure,
    ) {
        while (true) {
            if (!isCurrent(id)) throw Stop(late)
            try {
                client.put(box, record.name, record.sealed)
                trouble(id, false)
                return
            } catch (e: CloudKitWebClient.TokenRejected) {
                throw e
            } catch (e: CloudKitWebClient.PrivateDatabaseRefused) {
                throw e
            } catch (e: CloudKitWebClient.ServerError) {
                // Refused inside a good reply: a retry of a record that did arrive. Anything else is retried.
                if (e.status == 200) return
                trouble(id, true)
            } catch (e: IOException) {
                Log.w(TAG, "couldn't reach iCloud (${RemoteLink.describe(e)}); retrying")
                trouble(id, true)
            }
            if (clock() >= deadline) throw Stop(late)
            delay(pollMs)
        }
    }

    /** Reads the R→B mailbox once and deletes the records of this pairing. Network errors only mark trouble. */
    private fun poll(id: Int, client: CloudKitWebClient, box: CloudKitWebClient.MailboxAddress, steps: RemotePairingSteps) {
        try {
            val now = clock()
            val mine = client.fetch(box).filter { steps.receive(it.name, it.sealed, now) }.map { it.name }
            client.delete(box, mine)
            trouble(id, false)
        } catch (e: CloudKitWebClient.TokenRejected) {
            throw e
        } catch (e: CloudKitWebClient.PrivateDatabaseRefused) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "couldn't read the mailbox (${RemoteLink.describe(e)})")
            trouble(id, true)
        }
    }

    private fun isCurrent(id: Int): Boolean = synchronized(lock) { id == run }

    private fun update(id: Int, next: (State) -> State) {
        synchronized(lock) {
            if (id == run) _state.value = next(_state.value)
        }
    }

    private fun trouble(id: Int, on: Boolean) = update(id) { s ->
        when (s) {
            is State.Waiting -> s.copy(networkTrouble = on)
            is State.Comparing -> s.copy(networkTrouble = on)
            is State.Confirming -> s.copy(networkTrouble = on)
            else -> s
        }
    }

    private fun pin(id: Int, remoteKey: ByteArray): Boolean {
        synchronized(lock) {
            if (id != run) return false
            host.pin(remoteKey)
            pinned = true
            Log.i(TAG, "codes confirmed on this phone; iPhone key pinned")
            return true
        }
    }

    private fun finish(id: Int, config: RemoteConfig, token: String) {
        synchronized(lock) {
            if (id != run) return
            host.complete(config, token)
            pinned = false
            job = null
            _state.value = State.Paired
            Log.i(TAG, "paired from far away")
        }
    }

    private fun fail(id: Int, reason: Failure) {
        synchronized(lock) {
            if (id != run) return
            if (pinned) {
                pinned = false
                host.abandon()
            }
            job = null
            _state.value = State.Failed(reason)
            Log.i(TAG, "remote pairing stopped: ${reason.name.lowercase()}")
        }
    }

    companion object {
        private const val TAG = "rpair"
        const val POLL_MS = 3_000L

        /** Both sides give up 10 minutes after the codes appear. */
        const val CODE_TTL_MS = 10 * 60_000L
    }
}
