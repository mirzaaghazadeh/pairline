package app.pairline.android.telephony

import android.Manifest
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Telephony
import android.telecom.Call
import android.telecom.DisconnectCause
import app.pairline.android.protocol.Caps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelephonyLogicTest {

    // ---- Capabilities ----

    private val everything = setOf(
        Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
    )

    private fun caps(
        granted: Set<String> = everything,
        messaging: Boolean = true,
        calling: Boolean = true,
        sims: Int = 1,
        inCall: Boolean = false,
        add: Boolean = false,
        wifi: Boolean = false,
    ) = CapsCalculator.compute(CapsInput(granted, messaging, calling, sims, inCall, add, wifi))

    @Test
    fun allPermissionsSingleSim() {
        assertEquals(
            listOf(
                Caps.SMS_RECEIVE, Caps.SMS_SEND, Caps.SMS_HISTORY, Caps.CALL_OBSERVE, Caps.CALL_CONTROL, Caps.CALL_DIAL,
                Caps.CONTACTS, Caps.CALLLOG, Caps.USSD,
            ),
            caps(),
        )
    }

    @Test
    fun everythingIncludingOptionalOnes() {
        assertEquals(Caps.ALL, caps(sims = 2, inCall = true, add = true, wifi = true))
    }

    @Test
    fun nothingGranted() {
        assertEquals(emptyList<String>(), caps(granted = emptySet()))
    }

    @Test
    fun contactsAddNeedsBothToggleAndPermission() {
        assertEquals(false, Caps.CONTACTS_ADD in caps(add = false))
        assertEquals(false, Caps.CONTACTS_ADD in caps(granted = everything - Manifest.permission.WRITE_CONTACTS, add = true))
        assertEquals(true, Caps.CONTACTS_ADD in caps(add = true))
    }

    @Test
    fun noTelephonyHardware() {
        assertEquals(listOf(Caps.CONTACTS, Caps.CALLLOG), caps(messaging = false, calling = false))
    }

    @Test
    fun callControlNeedsPhoneStateToo() {
        val c = caps(granted = setOf(Manifest.permission.ANSWER_PHONE_CALLS))
        assertEquals(emptyList<String>(), c)
    }

    // ---- Phone numbers ----

    @Test
    fun dialableNumbers() {
        assertEquals("+15551234567", PhoneNumbers.normalizeDialable("+1 (555) 123-4567"))
        assertEquals("09121234567", PhoneNumbers.normalizeDialable("۰۹۱۲ ۱۲۳ ۴۵۶۷"))
        assertEquals("09121234567", PhoneNumbers.normalizeDialable("٠٩١٢١٢٣٤٥٦٧"))
        assertEquals("+989121234567", PhoneNumbers.normalizeDialable("⁦+98 912 123 4567⁩"))
        assertEquals("5551234,,1#", PhoneNumbers.normalizeDialable("555 1234,,1#"))
        assertNull(PhoneNumbers.normalizeDialable(""))
        assertNull(PhoneNumbers.normalizeDialable("hello"))
        assertNull(PhoneNumbers.normalizeDialable("555+1234"))
        assertNull(PhoneNumbers.normalizeDialable("++5551234"))
        assertNull(PhoneNumbers.normalizeDialable("1".repeat(65)))
        assertNull(PhoneNumbers.normalizeDialable("+-"))
    }

    @Test
    fun smsRecipients() {
        assertEquals("+15551234567", PhoneNumbers.normalizeSmsRecipient("+1 555 123 4567"))
        assertEquals("1234", PhoneNumbers.normalizeSmsRecipient("1234"))
        assertNull(PhoneNumbers.normalizeSmsRecipient("*100#"))
        assertNull(PhoneNumbers.normalizeSmsRecipient("Mom"))
    }

    @Test
    fun ussdCodes() {
        assertEquals("*100#", PhoneNumbers.normalizeUssd("*100#"))
        assertEquals("*123*1#", PhoneNumbers.normalizeUssd(" *123*1# "))
        assertEquals("*140*11#", PhoneNumbers.normalizeUssd("*۱۴۰*۱۱#"))
        assertEquals("#31#", PhoneNumbers.normalizeUssd("#31#"))
        assertNull(PhoneNumbers.normalizeUssd("100"))
        assertNull(PhoneNumbers.normalizeUssd("*100"))
        assertNull(PhoneNumbers.normalizeUssd("*100#;"))
        assertNull(PhoneNumbers.normalizeUssd("+*100#"))
    }

    @Test
    fun dtmfDigits() {
        assertEquals('5', PhoneNumbers.dtmfDigit("5"))
        assertEquals('#', PhoneNumbers.dtmfDigit("#"))
        assertEquals('A', PhoneNumbers.dtmfDigit("a"))
        assertEquals('7', PhoneNumbers.dtmfDigit("۷"))
        assertNull(PhoneNumbers.dtmfDigit("12"))
        assertNull(PhoneNumbers.dtmfDigit("x"))
        assertNull(PhoneNumbers.dtmfDigit(""))
    }

    @Test
    fun numberComparison() {
        assertEquals(true, PhoneNumbers.sameNumber("+989121234567", "09121234567"))
        assertEquals(true, PhoneNumbers.sameNumber("+1 555 123 4567", "5551234567"))
        assertEquals(false, PhoneNumbers.sameNumber("+989121234567", "+989121234568"))
        assertEquals(false, PhoneNumbers.sameNumber(null, "1"))
        assertEquals(true, PhoneNumbers.sameNumber("1234", "1234"))
    }

    // ---- SMS mapping ----

    @Test
    fun smsBoxesAndStates() {
        assertEquals("in", SmsMapping.box(Telephony.Sms.MESSAGE_TYPE_INBOX))
        assertEquals("out", SmsMapping.box(Telephony.Sms.MESSAGE_TYPE_SENT))
        assertEquals("received", SmsMapping.state(Telephony.Sms.MESSAGE_TYPE_INBOX, -1))
        assertEquals("sent", SmsMapping.state(Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_NONE))
        assertEquals("sent", SmsMapping.state(Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_PENDING))
        assertEquals("delivered", SmsMapping.state(Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_COMPLETE))
        assertEquals("failed", SmsMapping.state(Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_FAILED))
        assertEquals("failed", SmsMapping.state(Telephony.Sms.MESSAGE_TYPE_FAILED, -1))
        assertEquals("pending", SmsMapping.state(Telephony.Sms.MESSAGE_TYPE_OUTBOX, -1))
        assertEquals("pending", SmsMapping.state(Telephony.Sms.MESSAGE_TYPE_QUEUED, -1))
        assertEquals("sms:42", SmsMapping.key(42))
    }

    @Test
    fun smsLimitsAndSnippets() {
        assertEquals(50, SmsMapping.historyLimit(null))
        assertEquals(100, SmsMapping.historyLimit(500))
        assertEquals(1, SmsMapping.historyLimit(0))
        assertEquals(50, SmsMapping.threadsLimit(null))
        assertEquals("short", SmsMapping.snippet("short"))
        val long = SmsMapping.snippet("x".repeat(500))
        assertEquals(160, long.length)
        assertEquals('…', long.last())
    }

    // ---- Calls ----

    @Test
    fun callStates() {
        assertEquals("dialing", CallMapping.state(Call.STATE_DIALING))
        assertEquals("dialing", CallMapping.state(Call.STATE_CONNECTING))
        assertEquals("ringing", CallMapping.state(Call.STATE_RINGING))
        assertEquals("active", CallMapping.state(Call.STATE_ACTIVE))
        assertEquals("held", CallMapping.state(Call.STATE_HOLDING))
        assertEquals("ended", CallMapping.state(Call.STATE_DISCONNECTED))
        assertNull(CallMapping.state(Call.STATE_DISCONNECTING))
    }

    @Test
    fun connectedCallsNeverGoBackToRinging() {
        assertEquals(true, CallMapping.isBackwards("active", "ringing"))
        assertEquals(true, CallMapping.isBackwards("held", "dialing"))
        assertEquals(false, CallMapping.isBackwards("ringing", "active"))
        assertEquals(false, CallMapping.isBackwards("active", "held"))
        assertEquals(false, CallMapping.isBackwards("dialing", "active"))
    }

    @Test
    fun endReasons() {
        fun r(cause: Int?, ringing: Boolean = false, incoming: Boolean = true, rejected: Boolean = false, ended: Boolean = false) =
            CallMapping.endReason(cause, ringing, incoming, rejected, ended)
        assertEquals("busy", r(DisconnectCause.BUSY, incoming = false))
        assertEquals("rejected", r(DisconnectCause.REJECTED, ringing = true))
        assertEquals("missed", r(DisconnectCause.MISSED, ringing = true))
        assertEquals("missed", r(DisconnectCause.REMOTE, ringing = true))
        assertEquals("missed", r(DisconnectCause.CANCELED, ringing = true))
        assertEquals("remote", r(DisconnectCause.REMOTE))
        assertEquals("local", r(DisconnectCause.LOCAL))
        assertEquals("rejected", r(DisconnectCause.LOCAL, ringing = true))
        assertEquals("local", r(DisconnectCause.CANCELED, incoming = false))
        assertEquals("failed", r(DisconnectCause.ERROR, incoming = false))
        assertEquals("rejected", r(DisconnectCause.REMOTE, ringing = true, rejected = true))
        assertEquals("local", r(DisconnectCause.REMOTE, ended = true))
        assertEquals("remote", r(null))
        assertEquals("missed", r(null, ringing = true))
    }

    @Test
    fun callLogTypes() {
        assertEquals("in", CallMapping.callLogType(CallLog.Calls.INCOMING_TYPE))
        assertEquals("out", CallMapping.callLogType(CallLog.Calls.OUTGOING_TYPE))
        assertEquals("missed", CallMapping.callLogType(CallLog.Calls.MISSED_TYPE))
        assertEquals("rejected", CallMapping.callLogType(CallLog.Calls.REJECTED_TYPE))
        assertEquals("blocked", CallMapping.callLogType(CallLog.Calls.BLOCKED_TYPE))
        assertEquals("voicemail", CallMapping.callLogType(CallLog.Calls.VOICEMAIL_TYPE))
        assertEquals("rejected", CallMapping.reasonFromCallLog(CallLog.Calls.REJECTED_TYPE))
        assertEquals("missed", CallMapping.reasonFromCallLog(CallLog.Calls.MISSED_TYPE))
        assertEquals("missed", CallMapping.reasonFromCallLog(null))
    }

    @Test
    fun networkGenerations() {
        assertEquals("5G", SimDirectory.networkGeneration(android.telephony.TelephonyManager.NETWORK_TYPE_NR))
        assertEquals("LTE", SimDirectory.networkGeneration(android.telephony.TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("3G", SimDirectory.networkGeneration(android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP))
        assertEquals("2G", SimDirectory.networkGeneration(android.telephony.TelephonyManager.NETWORK_TYPE_EDGE))
        assertEquals("none", SimDirectory.networkGeneration(android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN))
    }

    // ---- Contacts ----

    @Test
    fun cursorsRoundTripAndRejectJunk() {
        val c = ContactsCursor(1764000000000, 42, 1763000000000)
        assertEquals(c, ContactsCursor.parse(c.encode()))
        assertNull(ContactsCursor.parse("garbage"))
        assertNull(ContactsCursor.parse("c1.x.1.2"))
        assertNull(ContactsCursor.parse(null))
        assertEquals(CallLogCursor(99), CallLogCursor.parse(CallLogCursor(99).encode()))
        assertNull(CallLogCursor.parse("c1.1.2.3"))
    }

    @Test
    fun phoneLabels() {
        assertEquals("mobile", ContactLabels.phone(Phone.TYPE_MOBILE, null))
        assertEquals("work", ContactLabels.phone(Phone.TYPE_WORK, null))
        assertEquals("Boat", ContactLabels.phone(Phone.TYPE_CUSTOM, "Boat"))
        assertEquals("other", ContactLabels.phone(Phone.TYPE_CUSTOM, " "))
        assertEquals(Phone.TYPE_MOBILE, ContactLabels.phoneType(null))
        assertEquals(Phone.TYPE_HOME, ContactLabels.phoneType("Home"))
        assertEquals(Phone.TYPE_CUSTOM, ContactLabels.phoneType("Boat"))
    }
}
