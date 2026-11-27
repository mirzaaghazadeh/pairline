package app.pairline.android.privileged

import app.pairline.android.privileged.PrivilegedManager.Failure
import app.pairline.android.privileged.PrivilegedManager.State
import org.json.JSONObject

/**
 * Talk on iPhone's state for `status.talk` (D-107; PROTOCOL.md "Talk on iPhone's state"): whether call audio can move
 * to the iPhone, and if not what's missing, as fixed codes the iPhone turns into words. Nothing personal.
 */
data class TalkStatus(val state: String, val reason: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("state", state).put("reason", reason ?: JSONObject.NULL)

    companion object {
        const val READY = "ready"
        const val STARTING = "starting"
        const val SETUP = "setup"
        const val OFF = "off"
        const val WAITING = "waiting"
        const val FAILED = "failed"
        const val UNSUPPORTED = "unsupported"

        const val REASON_WIFI = "wifi"
        const val REASON_DEBUGGING = "debugging"
        const val REASON_PAIRING = "pairing"
        const val REASON_ROOT = "root"
        const val REASON_OTHER = "other"

        /**
         * Maps the helper's [state] and this phone's settings to `status.talk`.
         *
         * - [switchOn]: the Talk on iPhone switch.
         * - [paired]: Pairline is paired with Wireless debugging; with [rootMode] the two ways it can start the helper.
         * - [setUpBefore]: it was set up once (paired, or the helper ran), so a phone that lost its pairing reads as
         *   "pair again" rather than "set it up".
         * - [onWifi]: Wireless debugging can run only on Wi-Fi.
         */
        fun of(
            sdk: Int,
            state: State,
            failure: Failure?,
            switchOn: Boolean,
            paired: Boolean,
            rootMode: Boolean,
            setUpBefore: Boolean,
            onWifi: Boolean,
        ): TalkStatus {
            if (sdk < ANDROID_11 || state == State.UNSUPPORTED) return TalkStatus(UNSUPPORTED)
            val setUp = paired || rootMode
            // Not set up (or no longer): a pairing that Android forgot asks to pair again; otherwise the first setup.
            fun notSetUp(): TalkStatus = when {
                setUpBefore && switchOn -> TalkStatus(FAILED, REASON_PAIRING)
                setUpBefore -> TalkStatus(OFF)
                else -> TalkStatus(SETUP)
            }
            return when (state) {
                State.UNSUPPORTED -> TalkStatus(UNSUPPORTED)
                State.READY -> if (switchOn) TalkStatus(READY) else TalkStatus(OFF)
                // IDLE means ready to start: with the switch on, it starts right after.
                State.STARTING, State.IDLE -> when {
                    switchOn -> TalkStatus(STARTING)
                    setUp || setUpBefore -> TalkStatus(OFF)
                    else -> TalkStatus(SETUP)
                }
                State.FAILED -> when {
                    !switchOn && (setUp || setUpBefore) -> TalkStatus(OFF)
                    else -> TalkStatus(FAILED, reasonFor(failure))
                }
                State.NEEDS_DEVELOPER_OPTIONS, State.NEEDS_WIRELESS_DEBUGGING -> when {
                    !setUp -> notSetUp()
                    !switchOn -> TalkStatus(OFF)
                    // Set up and on: Pairline turns debugging back on by itself on Wi-Fi (D-059).
                    !onWifi -> TalkStatus(WAITING, REASON_WIFI)
                    else -> TalkStatus(WAITING, REASON_DEBUGGING)
                }
                State.NEEDS_PAIRING -> notSetUp()
            }
        }

        private fun reasonFor(failure: Failure?): String = when (failure) {
            Failure.NO_ROOT -> REASON_ROOT
            // Wireless debugging couldn't be found: it's off, or the phone isn't on a network where it runs.
            Failure.NO_SERVICE -> REASON_DEBUGGING
            Failure.NO_HELPER, Failure.OTHER, null -> REASON_OTHER
        }

        /** Android 11, `Build.VERSION_CODES.R`. */
        const val ANDROID_11 = 30
    }
}

/**
 * Talk on iPhone is on by default from D-107, on Android 11 and later. One exception, decided once when this version
 * first runs: a phone where the switch was never touched but Pairline is already paired with Wireless debugging (or
 * root mode is on) keeps it off, so D-059's turning debugging back on happens only for someone who set it up with
 * the switch on.
 */
object TalkDefault {
    fun on(sdk: Int): Boolean = sdk >= TalkStatus.ANDROID_11

    /** The value to store at the first run of this version, or null to leave it unset (the default then applies). */
    fun migrate(stored: Boolean?, paired: Boolean, rootMode: Boolean): Boolean? =
        if (stored == null && (paired || rootMode)) false else null
}
