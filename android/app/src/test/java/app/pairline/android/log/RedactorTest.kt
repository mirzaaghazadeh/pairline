package app.pairline.android.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logs must never contain phone numbers, emails, MAC addresses, pairing links or keys (AGENTS.md rule 9). */
class RedactorTest {
    private fun r(s: String) = Redactor.redact(s)

    @Test
    fun phoneNumbersInManyFormats() {
        for (n in listOf(
            "+15551234567", "+1 555 123 4567", "(555) 123-4567", "555-123-4567", "09121234567",
            "+98 912 123 4567", "0912.123.4567", "+۹۸۹۱۲۱۲۳۴۵۶۷", "٠٩١٢١٢٣٤٥٦٧", "5551234",
        )) {
            val out = r("call from $n ended")
            assertEquals("call from [number] ended", out)
        }
    }

    @Test
    fun shortNumbersStay() {
        assertEquals("MTU 517, status 133, SIM 2", r("MTU 517, status 133, SIM 2"))
        assertEquals("protocol 0, 3 part(s)", r("protocol 0, 3 part(s)"))
        assertEquals("USSD *100#", r("USSD *100#"))
    }

    @Test
    fun emails() {
        assertEquals("from [email] and [email]", r("from ada@example.com and نوید@مثال.ایران"))
    }

    @Test
    fun macAddresses() {
        assertEquals("device [mac] connected", r("device AA:BB:CC:11:22:33 connected"))
        assertEquals("device [mac]", r("device aa-bb-cc-11-22-33"))
    }

    @Test
    fun pairingLinksAndKeys() {
        val uri = "pairline://pair?v=0&id=obLD1OX2Bxg&k=BMFsdl_Cv9ICFPZCaC7GYdjB09xa6od52g2idP9YxYsGiCAMGGL1RyeNUEZz7Tqo&s=RckDaSOwfAzcD1hyQhuXOt7zUoCYIJD"
        assertEquals("link [pairing-link]", r("link $uri"))
        assertEquals("key [key] end", r("key RckDaSOwfAzcD1hyQhuXOt7zUoCYIJD-JzmMNjaFz9s end"))
        assertEquals("hex [key]", r("hex a1b2c3d4e5f60718a1b2c3d4e5f60718"))
    }

    @Test
    fun ipAddresses() {
        assertEquals("mapped [ip] for the iPhone", r("mapped 81.2.69.160:47830 for the iPhone"))
        assertEquals("router [ip]", r("router 10.0.0.1"))
        assertEquals("listening on [ip]", r("listening on [2a02:1234:5678:9abc::23]:47830"))
        assertEquals("from [ip] now", r("from 2a02:1234:5678:9abc::23 now"))
        assertEquals("gateway [ip]", r("gateway fe80::1%wlan0"))
        assertEquals("full [ip]", r("full 2001:0db8:0000:0000:0000:ff00:0042:8329"))
        // What the direct connection logs on purpose stays: a LAN hint, a port, a protocol, a time.
        assertEquals("NAT-PMP: router ….1 mapped TCP 47830 to port 47830", r("NAT-PMP: router ….1 mapped TCP 47830 to port 47830"))
        assertEquals("2026-11-24 01:52:22.443 I/direct: renewed", r("2026-11-24 01:52:22.443 I/direct: renewed"))
        assertEquals("Android 16.0.1", r("Android 16.0.1"))
    }

    @Test
    fun ourTimestampsStay() {
        assertEquals("2026-11-24 01:52:22.443 I/link: connected", r("2026-11-24 01:52:22.443 I/link: connected"))
        assertEquals("Paired: yes, since 2026-11-24 10:22", r("Paired: yes, since 2026-11-24 10:22"))
        assertEquals("[number] called", r("0912-123-4567 called"))
    }

    @Test
    fun ordinaryWordsStay() {
        val s = "BridgeSessionCallbacksImplementation closed: handshake timed out"
        assertEquals(s, r(s))
    }

    @Test
    fun nothingPersonalSurvivesAMixedLine() {
        val out = r("SMS from +989121234567 (ada@example.com) via 00:11:22:33:44:55")
        assertFalse(out.contains("912"))
        assertFalse(out.contains("ada"))
        assertFalse(out.contains("00:11"))
        assertTrue(out.contains("[number]") && out.contains("[email]") && out.contains("[mac]"))
    }
}
