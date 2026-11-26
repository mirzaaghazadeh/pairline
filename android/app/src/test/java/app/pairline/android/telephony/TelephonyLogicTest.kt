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
        Manifest.permission.WRITE_CONTACTS, Manifest.permission.WRITE_CALL_LOG,
    )

    private fun caps(
        granted: Set<String> = everything,
        messaging: Boolean = true,
        calling: Boolean = true,
        sims: Int = 1,
        inCall: Boolean = false,
        add: Boolean = false,
        listen: Boolean = false,
        talk: Boolean = false,
        share: Boolean = false,
        wifi: Boolean = false,
        link: Boolean = false,
        switches: Boolean = false,
        smsWrite: Boolean = false,
        tools: Boolean = false,
        mirror: Boolean = false,
        usage: Boolean = false,
        screen: Boolean = false,
        photos: Boolean = false,
    ) = CapsCalculator.compute(
        CapsInput(
            granted, messaging, calling, sims, inCall, add, listen, talk, share, wifi, link, switches, smsWrite, tools,
            mirror, usage, screen, photos,
        ),
    )

    @Test
    fun allPermissionsSingleSim() {
        assertEquals(
            listOf(
                Caps.SMS_RECEIVE, Caps.SMS_SEND, Caps.SMS_HISTORY, Caps.SMS_SCHEDULE, Caps.CALL_OBSERVE,
                Caps.CALL_CONTROL, Caps.CALL_DIAL, Caps.CALL_VOICEMAIL, Caps.CONTACTS, Caps.CALLLOG, Caps.USSD,
                Caps.CALLLOG_DELETE, Caps.BLOCK,
            ),
            caps(),
        )
    }

    @Test
    fun everythingIncludingOptionalOnes() {
        assertEquals(
            Caps.ALL,
            caps(
                sims = 2, inCall = true, add = true, listen = true, talk = true, share = true, wifi = true, link = true,
                switches = true, smsWrite = true, tools = true, mirror = true, usage = true, screen = true,
                photos = true,
            ),
        )
    }

    @Test
    fun readSyncHoldEditAndPhoneTools() {
        // D-074: read sync needs READ_SMS and the write app-op; hold the companion call service; editing the opt-in.
        assertEquals(false, Caps.SMS_READ in caps())
        assertEquals(true, Caps.SMS_READ in caps(smsWrite = true))
        assertEquals(false, Caps.SMS_READ in caps(granted = everything - Manifest.permission.READ_SMS, smsWrite = true))
        assertEquals(listOf(false, true), listOf(Caps.CALL_HOLD in caps(), Caps.CALL_HOLD in caps(inCall = true)))
        assertEquals(listOf(false, true), listOf(Caps.CONTACTS_EDIT in caps(), Caps.CONTACTS_EDIT in caps(add = true)))
        assertEquals(listOf(Caps.PHONE_FIND, Caps.PHONE_RINGER, Caps.BLOCK), caps(granted = emptySet(), tools = true))
    }

    @Test
    fun netSwitchFollowsTheHelperOnly() {
        // No permission or SIM needed: the helper does the switching (D-073).
        assertEquals(listOf(Caps.NET_SWITCH, Caps.BLOCK), caps(granted = emptySet(), switches = true))
        assertEquals(false, Caps.NET_SWITCH in caps())
    }

    @Test
    fun callAudioCapsNeedListenFirstAndCalling() {
        assertEquals(false, Caps.CALL_MEDIA_TALK in caps(talk = true))
        assertEquals(false, Caps.CALL_MEDIA_LINK in caps(link = true))
        assertEquals(true, Caps.CALL_MEDIA_LINK in caps(listen = true, link = true))
        assertEquals(listOf(true, false), caps(listen = true).let { listOf(Caps.CALL_MEDIA in it, Caps.CALL_MEDIA_TALK in it) })
        assertEquals(false, Caps.CALL_MEDIA in caps(calling = false, listen = true, talk = true))
    }

    @Test
    fun nothingGranted() {
        // The block list needs no permission.
        assertEquals(listOf(Caps.BLOCK), caps(granted = emptySet()))
    }

    @Test
    fun deletingCallsNeedsReadAndWrite() {
        assertEquals(false, Caps.CALLLOG_DELETE in caps(granted = everything - Manifest.permission.WRITE_CALL_LOG))
        assertEquals(false, Caps.CALLLOG_DELETE in caps(granted = everything - Manifest.permission.READ_CALL_LOG))
        assertEquals(true, Caps.CALLLOG_DELETE in caps())
    }

    @Test
    fun contactsAddNeedsBothToggleAndPermission() {
        assertEquals(false, Caps.CONTACTS_ADD in caps(add = false))
        assertEquals(false, Caps.CONTACTS_ADD in caps(granted = everything - Manifest.permission.WRITE_CONTACTS, add = true))
        assertEquals(true, Caps.CONTACTS_ADD in caps(add = true))
    }

    @Test
    fun noTelephonyHardware() {
        assertEquals(
            listOf(Caps.CONTACTS, Caps.CALLLOG, Caps.CALLLOG_DELETE, Caps.BLOCK),
            caps(messaging = false, calling = false),
        )
    }

    @Test
    fun callControlNeedsPhoneStateToo() {
        val c = caps(granted = setOf(Manifest.permission.ANSWER_PHONE_CALLS))
        assertEquals(listOf(Caps.BLOCK), c)
    }

    // ---- Block list (D-043) ----

    @Test
    fun matchKeysIgnoreFormatting() {
        assertEquals(PhoneNumbers.matchKey("+98 912 123 4567"), PhoneNumbers.matchKey("09121234567"))
        assertEquals(PhoneNumbers.matchKey("+1 (555) 010-0200"), PhoneNumbers.matchKey("555-010-0200"))
        assertEquals(PhoneNumbers.matchKey("۰۹۱۲۱۲۳۴۵۶۷"), PhoneNumbers.matchKey("+989121234567"))
        assertEquals("t:bank", PhoneNumbers.matchKey(" BANK "))
        assertEquals("t:1234", PhoneNumbers.matchKey("1234"))
        assertEquals("12345", PhoneNumbers.matchKey("12345"))
        assertNull(PhoneNumbers.matchKey("  "))
        assertNull(PhoneNumbers.matchKey(null))
        assertEquals(false, PhoneNumbers.matchKey("+15550100") == PhoneNumbers.matchKey("+15550101"))
    }

    @Test
    fun blockedNumbersMatchOtherSpellings() {
        val cases = listOf(
            Triple("+98 912 123 4567", "09121234567", true),
            Triple("+1 555 1234", "5551234", true), // 7-digit local form of a number saved with its country code
            Triple("+354 555 1234", "555 1234", true),
            Triple("+1 (555) 010-0200", "+15550100201", false),
            Triple("5551234", "5551235", false),
            Triple("12345", "12345", true), // short codes match exactly
            Triple("12345", "912345", false),
            Triple("BANK", "bank", true),
            Triple("BANK", "BANKX", false),
            Triple("5551234", null, false),
        )
        for ((blocked, number, expected) in cases) {
            assertEquals("$blocked vs $number", expected, PhoneNumbers.blockMatches(blocked, number))
            val matcher = BlockRules.Matcher(listOf(BlockedNumber(blocked, 0)))
            assertEquals("matcher: $blocked vs $number", expected, matcher.matches(number))
        }
        assertEquals(false, BlockRules.Matcher(emptyList()).matches("5551234"))
    }

    @Test
    fun blockableNumbersAndSenderNames() {
        assertEquals("+15551234567", PhoneNumbers.normalizeBlockable("⁦+1 555 123 4567⁩"))
        assertEquals("PROMO", PhoneNumbers.normalizeBlockable(" PROMO "))
        assertNull(PhoneNumbers.normalizeBlockable(""))
        assertNull(PhoneNumbers.normalizeBlockable("   "))
        assertNull(PhoneNumbers.normalizeBlockable("a\nb"))
        assertNull(PhoneNumbers.normalizeBlockable("x".repeat(65)))
    }

    @Test
    fun blockRulesAddRemoveAndDeduplicate() {
        val start = BlockRules.apply(emptyList(), listOf("+98 912 123 4567", "09121234567", "BANK"), emptyList(), 100)
        assertEquals(listOf("+989121234567", "BANK"), start.map { it.number })
        assertEquals(100L, start.first().addedAt)
        // Removing by another spelling of the same number works.
        val removed = BlockRules.apply(start, emptyList(), listOf("0912 123 4567"), 200)
        assertEquals(listOf("BANK"), removed.map { it.number })
        // Adding and removing the same number in one request leaves it off.
        assertEquals(listOf("BANK"), BlockRules.apply(removed, listOf("+15550100"), listOf("15550100"), 300).map { it.number })
        assertEquals(start, BlockRules.parse(BlockRules.encode(start)))
        assertEquals(emptyList<BlockedNumber>(), BlockRules.parse("not json"))
        val full = BlockRules.apply(emptyList(), (0 until 600).map { "+1555${1000000 + it}" }, emptyList(), 1)
        assertEquals(BlockRules.MAX_ENTRIES, full.size)
    }

    @Test
    fun callLogKeys() {
        assertEquals(99L, CallLogKeys.id("cl:99"))
        assertNull(CallLogKeys.id("c:99"))
        assertNull(CallLogKeys.id("cl:x"))
        assertNull(CallLogKeys.id("cl:-4"))
        assertNull(CallLogKeys.id("99"))
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
        // Call forwarding to an international number (D-082): '+' only right after a '*'.
        assertEquals("**21*+905551234567#", PhoneNumbers.normalizeUssd("**21*+90 555 123 45 67#"))
        assertEquals("##002#", PhoneNumbers.normalizeUssd("##002#"))
        assertNull(PhoneNumbers.normalizeUssd("**21+90555#"))
        assertNull(PhoneNumbers.normalizeUssd("*#"))
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

    @Test
    fun radioNames() {
        assertEquals("LTE", SimDirectory.radioName(android.telephony.TelephonyManager.NETWORK_TYPE_LTE, null))
        assertEquals(
            "NR NSA",
            SimDirectory.radioName(
                android.telephony.TelephonyManager.NETWORK_TYPE_LTE,
                android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
            ),
        )
        assertEquals("HSPA+", SimDirectory.radioName(android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP, null))
        assertEquals("EDGE", SimDirectory.radioName(android.telephony.TelephonyManager.NETWORK_TYPE_EDGE, null))
        assertNull(SimDirectory.radioName(android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN, null))
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
