package app.pairline.android.telephony

import android.Manifest
import app.pairline.android.protocol.Caps

/** Everything that decides `status.caps`, gathered so the rules below stay pure and testable. */
data class CapsInput(
    val granted: Set<String>,
    val hasMessaging: Boolean,
    val hasCalling: Boolean,
    val activeSims: Int,
    /** The companion InCallService is bound now, or has been bound on this phone before. */
    val inCallService: Boolean,
    val contactsAddEnabled: Boolean,
    /** The Wi-Fi link is switched on (M5): files can go both ways over a Wi-Fi session. */
    val wifiLink: Boolean = false,
)

/**
 * The bridge lists only what this phone can do right now: the permission is granted and the hardware
 * supports it (PROTOCOL.md, "Capabilities"). Returned in the spec's order.
 */
object CapsCalculator {
    fun compute(input: CapsInput): List<String> {
        fun has(p: String) = p in input.granted
        val phoneState = has(Manifest.permission.READ_PHONE_STATE)
        val caps = HashSet<String>()
        if (input.hasMessaging) {
            if (has(Manifest.permission.RECEIVE_SMS)) caps += Caps.SMS_RECEIVE
            if (has(Manifest.permission.SEND_SMS)) caps += Caps.SMS_SEND
            if (has(Manifest.permission.READ_SMS)) caps += Caps.SMS_HISTORY
        }
        if (input.hasCalling) {
            if (phoneState) caps += Caps.CALL_OBSERVE
            if (phoneState && has(Manifest.permission.ANSWER_PHONE_CALLS)) caps += Caps.CALL_CONTROL
            if (has(Manifest.permission.CALL_PHONE)) caps += Caps.CALL_DIAL
            if (input.inCallService) {
                caps += Caps.CALL_DTMF
                caps += Caps.CALL_AUDIO
            }
            if (has(Manifest.permission.CALL_PHONE) && phoneState) caps += Caps.USSD
        }
        if (has(Manifest.permission.READ_CONTACTS)) caps += Caps.CONTACTS
        if (has(Manifest.permission.READ_CALL_LOG)) caps += Caps.CALLLOG
        if (input.contactsAddEnabled && has(Manifest.permission.WRITE_CONTACTS)) caps += Caps.CONTACTS_ADD
        if (input.activeSims >= 2) caps += Caps.DUALSIM
        if (input.wifiLink) {
            caps += Caps.WIFI
            caps += Caps.FILES
        }
        return Caps.ALL.filter { it in caps }
    }
}
