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
    /** Call audio can be relayed to the iPhone ("Talk on iPhone" ready, D-047). */
    val mediaListen: Boolean = false,
    /** …and the iPhone's voice can be fed into the call. */
    val mediaTalk: Boolean = false,
    /** The user allows internet sharing (M8). */
    val netShare: Boolean = false,
    /** The Wi-Fi link is switched on (M5): files can go both ways over a Wi-Fi session. */
    val wifiLink: Boolean = false,
    /** Call audio can also travel inside the Bluetooth session as Opus (with [mediaListen], D-072). */
    val mediaLink: Boolean = false,
    /** The iPhone may switch this phone's internet sharing (Talk on iPhone's helper running, D-073). */
    val netSwitch: Boolean = false,
    /** Pairline may mark SMS read (its WRITE_SMS app-op, D-074). */
    val smsWrite: Boolean = false,
    /** Find phone and the ringer switch are available (D-074). */
    val phoneTools: Boolean = false,
    /** Notification access is given, so chosen apps' notifications are mirrored (D-077). */
    val notifMirror: Boolean = false,
    /** Usage access is given, so the iPhone can show the mobile data used (D-084). */
    val dataUsage: Boolean = false,
    /** Pairline holds Android's call-screening role (D-085). */
    val callScreen: Boolean = false,
    /** "Photos for the iPhone" is on and photo access is given (D-088). */
    val photos: Boolean = false,
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
            if (has(Manifest.permission.READ_SMS) && input.smsWrite) caps += Caps.SMS_READ
            // The bridge keeps them and sends them at the time (D-084).
            if (has(Manifest.permission.SEND_SMS)) caps += Caps.SMS_SCHEDULE
            // Picture and group messages: read from Android's store, sent through its MMS service (D-089).
            if (has(Manifest.permission.READ_SMS) && has(Manifest.permission.SEND_SMS)) caps += Caps.MMS
        }
        if (input.hasCalling) {
            if (phoneState) caps += Caps.CALL_OBSERVE
            if (phoneState && has(Manifest.permission.ANSWER_PHONE_CALLS)) caps += Caps.CALL_CONTROL
            if (has(Manifest.permission.CALL_PHONE)) caps += Caps.CALL_DIAL
            if (has(Manifest.permission.CALL_PHONE) && phoneState) caps += Caps.CALL_VOICEMAIL
            if (input.inCallService) {
                caps += Caps.CALL_DTMF
                caps += Caps.CALL_AUDIO
                caps += Caps.CALL_HOLD
            }
            if (has(Manifest.permission.CALL_PHONE) && phoneState) caps += Caps.USSD
            if (input.mediaListen && phoneState) {
                caps += Caps.CALL_MEDIA
                if (input.mediaTalk) caps += Caps.CALL_MEDIA_TALK
                if (input.mediaLink) caps += Caps.CALL_MEDIA_LINK
            }
        }
        if (has(Manifest.permission.READ_CONTACTS)) caps += Caps.CONTACTS
        if (has(Manifest.permission.READ_CALL_LOG)) caps += Caps.CALLLOG
        if (input.contactsAddEnabled && has(Manifest.permission.WRITE_CONTACTS)) {
            caps += Caps.CONTACTS_ADD
            if (has(Manifest.permission.READ_CONTACTS)) caps += Caps.CONTACTS_EDIT
        }
        if (input.phoneTools) {
            caps += Caps.PHONE_FIND
            caps += Caps.PHONE_RINGER
        }
        if (input.notifMirror) caps += Caps.NOTIF_MIRROR
        if (input.dataUsage) caps += Caps.DATA_USAGE
        if (input.activeSims >= 2) caps += Caps.DUALSIM
        if (input.netShare) caps += Caps.NET_SHARE
        if (input.netSwitch) caps += Caps.NET_SWITCH
        if (input.wifiLink) {
            caps += Caps.WIFI
            caps += Caps.FILES
        }
        // Deleting needs the keys from the call-log sync, so both permissions.
        if (has(Manifest.permission.READ_CALL_LOG) && has(Manifest.permission.WRITE_CALL_LOG)) caps += Caps.CALLLOG_DELETE
        // The block list needs no permission: it filters what the bridge relays (D-043).
        caps += Caps.BLOCK
        // This bridge takes `remote.relay` and can wait at the user's own server (D-106); the iPhone offers it only then.
        caps += Caps.RELAY
        if (input.callScreen && input.hasCalling) caps += Caps.CALL_SCREEN
        // Photos travel with the file transfer, so only with the Wi-Fi link on.
        if (input.photos && input.wifiLink) caps += Caps.MEDIA_PHOTOS
        return Caps.ALL.filter { it in caps }
    }
}
