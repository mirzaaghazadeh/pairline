package app.pairline.android.link.remote

import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.RemoteBackend
import app.pairline.android.protocol.RemoteEnvironment
import app.pairline.android.protocol.RemoteInvitation
import app.pairline.android.protocol.RemoteMailboxes
import app.pairline.android.protocol.RequestException
import app.pairline.android.protocol.boolOrNull
import app.pairline.android.protocol.stringOrNull
import org.json.JSONObject

/** The three mailboxes (PROTOCOL.md, "Mailboxes in iCloud"); [zone] is the private backend's zone. */
enum class MailboxSlot(val zone: String) {
    TO_BRIDGE("ToBridge"),
    ALERT("ToPhone"),
    QUIET("ToPhoneQuiet"),
}

/** Where this phone's remote mode reads and writes: from `remote.config` or a remote pairing's invitation. */
data class RemoteConfig(
    val backend: RemoteBackend,
    val environment: RemoteEnvironment,
    val mailboxes: RemoteMailboxes,
) {
    fun address(slot: MailboxSlot): CloudKitWebClient.MailboxAddress = CloudKitWebClient.MailboxAddress(
        zone = slot.zone,
        id = when (slot) {
            MailboxSlot.TO_BRIDGE -> mailboxes.toBridge
            MailboxSlot.ALERT -> mailboxes.alert
            MailboxSlot.QUIET -> mailboxes.quiet
        },
    )

    companion object {
        fun of(invitation: RemoteInvitation) =
            RemoteConfig(invitation.backend, invitation.environment, invitation.mailboxes)

        /** A web auth token: non-empty printable ASCII without spaces, as in the invitation. */
        fun isToken(text: String): Boolean = text.length in 1..MAX_TOKEN && text.all { it in '!'..'~' }

        private const val MAX_TOKEN = 8192
    }
}

/** A token held only in memory: during remote pairing, and for the last clean-up after remote mode is turned off. */
class MemoryTokenStore(@Volatile private var token: String?) : CloudKitWebClient.TokenStore {
    override fun load(): String? = token

    override fun save(token: String) {
        this.token = token
    }
}

/** A checked `remote.config` request (PROTOCOL.md, "Remote messages"). */
sealed interface RemoteConfigRequest {
    data object Off : RemoteConfigRequest

    class On(val config: RemoteConfig, val token: String) : RemoteConfigRequest {
        /** Leaves out the token. */
        override fun toString(): String = "On(${config.backend.wire}, ${config.environment.wire})"
    }

    companion object {
        /**
         * Reads `on`, `backend`, `env`, `token` and `mb {b, a, q}`. Throws [RequestException] (`invalid`) for anything
         * missing or malformed, and for the public backend unless [allowPublic] (debug builds, D-072).
         */
        fun parse(message: JSONObject, allowPublic: Boolean): RemoteConfigRequest {
            val on = message.boolOrNull("on") ?: invalid("on missing")
            if (!on) return Off
            val backend = message.stringOrNull("backend")?.let(RemoteBackend::of) ?: invalid("bad backend")
            if (backend == RemoteBackend.PUBLIC && !allowPublic) invalid("backend not available in this build")
            val env = message.stringOrNull("env")?.let(RemoteEnvironment::of) ?: invalid("bad env")
            val token = message.stringOrNull("token")?.takeIf(RemoteConfig::isToken) ?: invalid("bad token")
            val mb = message.optJSONObject("mb") ?: invalid("mb missing")
            val mailboxes = RemoteMailboxes(
                toBridge = mb.stringOrNull("b") ?: invalid("mb.b missing"),
                alert = mb.stringOrNull("a") ?: invalid("mb.a missing"),
                quiet = mb.stringOrNull("q") ?: invalid("mb.q missing"),
            )
            if (!mailboxes.isValid()) invalid("bad mailbox ids")
            return On(RemoteConfig(backend, env, mailboxes), token)
        }

        private fun invalid(msg: String): Nothing = throw RequestException(ErrorCode.INVALID, msg)
    }
}
