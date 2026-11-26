package app.pairline.android.telephony

import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.RequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** D-084: checks on messages scheduled from the iPhone, and which are due. */
class ScheduleRulesTest {
    private val now = 1_764_000_000_000L
    private fun msg(cid: String = "c-1", to: String = "+90 555 123 45 67", body: String = "hi", at: Long = now + 60_000) =
        ScheduledMessage(cid, null, to, body, at)

    private fun rejects(code: ErrorCode, block: () -> Unit) {
        try {
            block()
            fail("expected ${code.wire}")
        } catch (e: RequestException) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun acceptsAndNormalizes() {
        val ok = ScheduleRules.validate(emptyList(), msg(), now)
        assertEquals("+905551234567", ok.to)
        // A minute in the past (clock skew) is fine: it goes right away.
        ScheduleRules.validate(emptyList(), msg(at = now - 60_000), now)
    }

    @Test
    fun rejectsBadInput() {
        rejects(ErrorCode.INVALID) { ScheduleRules.validate(emptyList(), msg(to = "*100#"), now) }
        rejects(ErrorCode.INVALID) { ScheduleRules.validate(emptyList(), msg(body = ""), now) }
        rejects(ErrorCode.INVALID) { ScheduleRules.validate(emptyList(), msg(at = now - 3_600_000), now) }
        rejects(ErrorCode.INVALID) { ScheduleRules.validate(emptyList(), msg(at = now + ScheduleRules.MAX_AHEAD_MS + 1), now) }
        rejects(ErrorCode.BUSY) { ScheduleRules.validate(listOf(msg()), msg(), now) }
        val full = (0 until ScheduleRules.MAX_ITEMS).map { msg(cid = "c$it") }
        rejects(ErrorCode.BUSY) { ScheduleRules.validate(full, msg(cid = "new"), now) }
    }

    @Test
    fun dueIncludesTheNextFewSeconds() {
        val items = listOf(msg(cid = "a", at = now - 1), msg(cid = "b", at = now + 10_000), msg(cid = "c", at = now + 60_000))
        assertEquals(listOf("a", "b"), ScheduleRules.due(items, now).map { it.cid })
    }

    @Test
    fun parsesStoredItems() {
        val m = msg()
        assertEquals(m, ScheduledMessage.parse(m.toJson()))
        assertNull(ScheduledMessage.parse(org.json.JSONObject().put("cid", "x")))
        val done = ScheduledDone("c-1", "sent", "sms:5", null, now)
        assertEquals(done, ScheduledDone.parse(done.store()))
    }
}
