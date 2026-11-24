package app.pairline.android.telephony

import android.provider.CallLog
import android.telecom.Call
import android.telecom.DisconnectCause

/** Pure mappings from Android's call states and causes to the protocol (unit-tested). */
object CallMapping {
    const val DIALING = "dialing"
    const val RINGING = "ringing"
    const val ACTIVE = "active"
    const val HELD = "held"
    const val ENDED = "ended"

    /** `call.state.state` for an android.telecom.Call state, or null for transitional states we skip. */
    @Suppress("DEPRECATION")
    fun state(telecomState: Int): String? = when (telecomState) {
        Call.STATE_NEW, Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT,
        Call.STATE_PULLING_CALL,
        -> DIALING
        Call.STATE_RINGING, STATE_SIMULATED_RINGING, STATE_AUDIO_PROCESSING -> RINGING
        Call.STATE_ACTIVE -> ACTIVE
        Call.STATE_HOLDING -> HELD
        Call.STATE_DISCONNECTED -> ENDED
        else -> null // DISCONNECTING: wait for DISCONNECTED
    }

    /** True for a change that would take a connected call back to ringing or dialing. */
    fun isBackwards(from: String, to: String): Boolean =
        (from == ACTIVE || from == HELD) && (to == RINGING || to == DIALING)

    /**
     * `call.state.reason` when a call ends.
     * [wasRinging]: the call never got past ringing. [incoming]: the call's direction.
     * [weRejected]/[weEnded]: the iPhone asked for it.
     */
    fun endReason(
        cause: Int?,
        wasRinging: Boolean,
        incoming: Boolean,
        weRejected: Boolean,
        weEnded: Boolean,
    ): String {
        if (weRejected) return "rejected"
        if (weEnded) return "local"
        return when (cause) {
            DisconnectCause.BUSY -> "busy"
            DisconnectCause.REJECTED -> "rejected"
            DisconnectCause.MISSED -> "missed"
            DisconnectCause.ERROR, DisconnectCause.RESTRICTED, DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED -> "failed"
            DisconnectCause.LOCAL -> if (incoming && wasRinging) "rejected" else "local"
            DisconnectCause.REMOTE -> if (incoming && wasRinging) "missed" else "remote"
            DisconnectCause.CANCELED -> if (incoming && wasRinging) "missed" else "local"
            DisconnectCause.ANSWERED_ELSEWHERE, DisconnectCause.CALL_PULLED -> "local"
            else -> if (incoming && wasRinging) "missed" else "remote"
        }
    }

    /** End reason for a ringing call that ended, judged from the call log entry Android wrote for it. */
    fun reasonFromCallLog(type: Int?): String = when (type) {
        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> "rejected"
        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "local"
        else -> "missed"
    }

    /** Call-log `type` for the protocol's Call-log entry. */
    fun callLogType(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "in"
        CallLog.Calls.OUTGOING_TYPE -> "out"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        else -> "in"
    }

    // Constants added after API 30's Call class; their values are stable.
    private const val STATE_SIMULATED_RINGING = 13
    private const val STATE_AUDIO_PROCESSING = 12
}
