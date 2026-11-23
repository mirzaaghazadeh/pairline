package app.pairline.android.protocol

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import kotlin.random.Random

class FramingAndMessagesTest {

    @Test
    fun fragmentRoundTripAtManySizes() {
        val rnd = Random(42)
        for (size in listOf(1, 2, 16, 17, 18, 19, 20, 100, 1000, 65_535)) {
            val frame = rnd.nextBytes(size)
            for (payload in listOf(4, 20, 23, 182, 244, 509, 512)) {
                val fragments = BleFragmenter.fragment(frame, payload)
                fragments.forEach { assertTrue(it.size <= payload) }
                assertEquals(0x01, fragments.first()[0].toInt() and 0x01)
                assertEquals(0x02, fragments.last()[0].toInt() and 0x02)
                val r = BleReassembler()
                var result: ByteArray? = null
                fragments.forEachIndexed { i, f ->
                    val out = r.accept(f)
                    if (i < fragments.size - 1) assertNull(out) else result = out
                }
                assertArrayEquals(frame, result)
            }
        }
    }

    @Test
    fun singleFragmentHasHeader03() {
        val f = BleFragmenter.fragment(byteArrayOf(0x20, 1, 2), 20)
        assertEquals(1, f.size)
        assertArrayEquals(byteArrayOf(0x03, 0x00, 0x03, 0x20, 1, 2), f[0])
    }

    @Test
    fun reassemblerRejectsMalformedInput() {
        assertThrows(ProtocolException::class.java) { BleReassembler().accept(byteArrayOf(0x00, 1, 2)) }
        assertThrows(ProtocolException::class.java) { BleReassembler().accept(byteArrayOf(0x03, 0, 0)) }
        assertThrows(ProtocolException::class.java) { BleReassembler().accept(byteArrayOf(0x03, 0, 1, 1, 2)) }
        assertThrows(ProtocolException::class.java) { BleReassembler().accept(byteArrayOf(0x03, 0, 3, 1)) }
        assertThrows(ProtocolException::class.java) { BleReassembler().accept(ByteArray(0)) }
        val r = BleReassembler()
        assertNull(r.accept(byteArrayOf(0x01, 0, 4, 1)))
        assertThrows(ProtocolException::class.java) { r.accept(byteArrayOf(0x01, 0, 4, 1)) }
    }

    @Test
    fun tcpFramingRejectsBadLengths() {
        assertThrows(ProtocolException::class.java) {
            TcpFraming.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0)))
        }
        assertThrows(ProtocolException::class.java) {
            TcpFraming.read(ByteArrayInputStream(byteArrayOf(0, 1, 0, 0)))
        }
        assertThrows(EOFException::class.java) {
            TcpFraming.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 5, 1, 2)))
        }
        assertThrows(EOFException::class.java) {
            TcpFraming.read(ByteArrayInputStream(byteArrayOf(0, 0)))
        }
    }

    @Test
    fun frameDecodeRejectsUnknownAndEmpty() {
        assertThrows(ProtocolException::class.java) { Frame.decode(ByteArray(0)) }
        assertThrows(ProtocolException::class.java) { Frame.decode(byteArrayOf(0x11)) }
        assertThrows(ProtocolException::class.java) { Frame.decode(ByteArray(65_536) { 0x20 }) }
        assertEquals(FrameType.PAIR_FAIL, Frame.decode(byteArrayOf(0x03)).type)
    }

    @Test
    fun sealerStopsBeforeTheCounterLimit() {
        val key = ByteArray(32)
        val sealer = RecordSealer(key, 1, 0)
        assertFalse(sealer.exhausted)
        val opener = RecordOpener(key, 1, 0)
        val tooHigh = Records.seal(key, 1, Proto.COUNTER_LIMIT, 0, ByteArray(0))
        assertThrows(ProtocolException::class.java) { opener.open(tooHigh) }
    }

    @Test
    fun hexAndBase64url() {
        assertEquals("00ff10", Hex.encode(byteArrayOf(0, -1, 16)))
        assertArrayEquals(byteArrayOf(0, -1, 16), Hex.decode("00FF10"))
        assertEquals("_-8", B64u.encode(byteArrayOf(-1, -17)))
        assertArrayEquals(byteArrayOf(-1, -17), B64u.decodeOrNull("_-8"))
        assertNull(B64u.decodeOrNull("+/8"))
        assertNull(B64u.decodeOrNull("a"))
    }

    @Test
    fun constantTimeCompare() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun requestIdParsing() {
        assertEquals(7L, JSONObject("{\"id\":7}").requestId)
        assertEquals(9_000_000_000L, JSONObject("{\"id\":9000000000}").requestId)
        assertEquals(3L, JSONObject("{\"id\":3.0}").requestId)
        assertNull(JSONObject("{\"id\":3.5}").requestId)
        assertNull(JSONObject("{\"id\":\"7\"}").requestId)
        assertNull(JSONObject("{}").requestId)
        assertNull(JSONObject("{\"id\":null}").requestId)
    }

    @Test
    fun optionalFields() {
        val o = JSONObject("{\"a\":null,\"b\":\"x\",\"c\":2,\"d\":true}")
        assertNull(o.stringOrNull("a"))
        assertEquals("x", o.stringOrNull("b"))
        assertNull(o.stringOrNull("c"))
        assertNull(o.intOrNull("a"))
        assertEquals(2, o.intOrNull("c"))
        assertEquals(true, o.boolOrNull("d"))
        assertNull(o.boolOrNull("b"))
        assertEquals("t", JSONObject("{\"t\":\"t\"}").type)
        assertNull(JSONObject("{\"t\":\"\"}").type)
        assertTrue(JSONObject().putNullable("x", null).isNull("x"))
    }

    @Test
    fun pagerStaysWithinBudgetAndLimit() {
        val items = (1..1000).map { "message number $it with some padding text to take space" }
        val (page, taken) = JsonPager.page(items.iterator(), limit = 100) { JSONObject().put("body", it) }
        assertEquals(100, page.count)
        assertTrue(page.more)
        assertEquals(items.take(100), taken)

        val big = (1..1000).map { "x".repeat(500) }
        val (p2, t2) = JsonPager.page(big.iterator(), limit = 1000) { JSONObject().put("body", it) }
        assertTrue(p2.more)
        val envelope = JSONObject().put("t", "sms.history").put("id", 1).put("msgs", p2.items).put("more", true)
        assertTrue(JsonPager.utf8Length(envelope.toString()) <= Proto.PAGE_BUDGET_BYTES)
        assertTrue(t2.size > 40)

        val (p3, _) = JsonPager.page(listOf("a", "b").iterator(), limit = 10) { JSONObject().put("v", it) }
        assertFalse(p3.more)
        assertEquals(2, p3.count)
    }

    @Test
    fun pagerSkipsItemsThatCanNeverFit() {
        val items = listOf("a", "x".repeat(40_000), "b")
        val (page, taken) = JsonPager.page(items.iterator(), limit = 10) { JSONObject().put("v", it) }
        assertEquals(listOf("a", "b"), taken)
        assertFalse(page.more)
    }

    @Test
    fun utf8LengthCountsMultibyte() {
        assertEquals(1, JsonPager.utf8Length("a"))
        assertEquals(2, JsonPager.utf8Length("س"))
        assertEquals(4, JsonPager.utf8Length("👋"))
        assertEquals("سلام 👋".toByteArray().size, JsonPager.utf8Length("سلام 👋"))
    }
}
