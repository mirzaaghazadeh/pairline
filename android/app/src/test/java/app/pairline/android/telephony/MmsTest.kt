package app.pairline.android.telephony

import android.provider.Telephony
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-089: picture and group messages. */
class MmsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun uintvarsAndLengths() {
        fun uv(v: Long) = ByteArrayOutputStream().also { MmsPdu.uintvar(it, v) }.toByteArray()
        assertArrayEquals(bytes(0x00), uv(0))
        assertArrayEquals(bytes(0x7F), uv(127))
        assertArrayEquals(bytes(0x81, 0x00), uv(128))
        assertArrayEquals(bytes(0x83, 0xFF, 0x7F), uv(65_535))
        fun vl(n: Int) = ByteArrayOutputStream().also { MmsPdu.valueLength(it, n) }.toByteArray()
        assertArrayEquals(bytes(30), vl(30))
        assertArrayEquals(bytes(31, 31), vl(31))
        fun ts(s: String) = ByteArrayOutputStream().also { MmsPdu.textString(it, s) }.toByteArray()
        assertArrayEquals(bytes('a'.code, 0), ts("a"))
        assertEquals(0x7F, ts("é")[0].toInt() and 0xFF)
    }

    @Test
    fun sendRequestLayout() {
        val pdu = MmsPdu.sendRequest(
            "T1",
            listOf("+905551234567", "+905557654321"),
            listOf(MmsPdu.Part("text/plain", "text.txt", "hi".toByteArray())),
        )
        // Message type m-send-req first, then the transaction id "T1".
        assertArrayEquals(bytes(0x8C, 0x80, 0x98, 'T'.code, '1'.code, 0x00, 0x8D, 0x92), pdu.copyOfRange(0, 8))
        // From: insert-address-token.
        assertArrayEquals(bytes(0x89, 0x01, 0x81), pdu.copyOfRange(8, 11))
        val text = String(pdu, Charsets.ISO_8859_1)
        assertTrue(text.contains("+905551234567/TYPE=PLMN\u0000"))
        assertTrue(text.contains("+905557654321/TYPE=PLMN\u0000"))
        // Content-Type multipart.mixed, one part; its body is the text.
        val ct = pdu.indexOfFirst { (it.toInt() and 0xFF) == 0xA3 }
        assertEquals(0x84, pdu[ct - 1].toInt() and 0xFF)
        assertEquals(1, pdu[ct + 1].toInt())
        assertTrue(text.endsWith("hi"))
    }

    @Test
    fun keysStatesAndParticipants() {
        assertEquals("mms:42", MmsMapping.key(42))
        assertEquals(42L, MmsMapping.id("mms:42"))
        assertNull(MmsMapping.id("sms:42"))
        assertEquals("received", MmsMapping.state(Telephony.Mms.MESSAGE_BOX_INBOX))
        assertEquals("failed", MmsMapping.state(Telephony.Mms.MESSAGE_BOX_FAILED))
        val group = MmsRow(
            1, 7, 0, Telephony.Mms.MESSAGE_BOX_INBOX, 1, from = "+905551111111",
            recipients = listOf("+905559999999", "+905552222222", "insert-address-token"),
            parts = listOf(MmsPart(1, "application/smil", null, null), MmsPart(2, "text/plain", null, "hello"),
                MmsPart(3, "image/jpeg", "a.jpg", null)),
        )
        val others = MmsMapping.others(group) { PhoneNumbers.sameNumber(it, "+905559999999") }
        assertEquals(listOf("+905551111111", "+905552222222"), others)
        assertEquals("hello", group.text)
        assertEquals(listOf(3L), MmsMapping.attachments(group.parts).map { it.pid })
        assertEquals("photo.png", MmsName.safe("../photo.png", "image/png"))
        assertEquals("image.jpg", MmsName.safe(null, "image/jpeg"))
    }
}
