package app.pairline.android.telephony

import android.Manifest
import android.content.Context
import android.database.Cursor
import android.provider.Telephony
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.JsonPager
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.RequestException
import app.pairline.android.protocol.putNullable
import org.json.JSONArray
import org.json.JSONObject

/** One SMS row as the protocol's Message object. */
data class SmsRow(
    val id: Long,
    val thread: Long,
    val address: String,
    val body: String,
    val time: Long,
    val type: Int,
    val status: Int,
    val subId: Int,
) {
    fun toJson(slot: Int?): JSONObject = JSONObject()
        .put("key", SmsMapping.key(id))
        .put("thread", thread)
        .put("addr", address)
        .put("body", body)
        .put("time", time)
        .put("box", SmsMapping.box(type))
        .putNullable("sim", slot)
        .put("state", SmsMapping.state(type, status))
}

/** Pure mappings from Android's SMS columns to the protocol (unit-tested). */
object SmsMapping {
    fun key(id: Long) = "sms:$id"

    fun box(type: Int): String = if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) "in" else "out"

    fun state(type: Int, status: Int): String = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> "received"
        Telephony.Sms.MESSAGE_TYPE_SENT -> when {
            status == Telephony.Sms.STATUS_COMPLETE -> "delivered"
            status >= Telephony.Sms.STATUS_FAILED -> "failed"
            else -> "sent"
        }
        Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
        Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> "pending"
        else -> "sent"
    }

    /** Thread snippets are shortened so a page of threads stays small. */
    fun snippet(body: String, max: Int = 160): String =
        if (body.length <= max) body else body.take(max - 1).trimEnd() + "…"

    /** `sms.history` limit: default 50, at most 100 (PROTOCOL.md clarifications). */
    fun historyLimit(requested: Long?): Int = (requested ?: 50L).coerceIn(1, 100).toInt()

    /** `sms.threads` limit: default 50. */
    fun threadsLimit(requested: Long?): Int = (requested ?: 50L).coerceIn(1, 200).toInt()
}

/**
 * Reads SMS history and threads from Android's SMS store (READ_SMS). Never writes to it. Texts received from a
 * blocked number ([isBlocked], D-043) are left out of what the iPhone gets; texts sent to it stay.
 */
class SmsStore(
    private val context: Context,
    private val sims: SimDirectory,
    private val isBlocked: (String?) -> Boolean = { false },
) {

    private fun hidden(row: SmsRow) = row.type == Telephony.Sms.MESSAGE_TYPE_INBOX && isBlocked(row.address)

    private val projection = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.THREAD_ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE,
        Telephony.Sms.STATUS,
        Telephony.Sms.SUBSCRIPTION_ID,
    )

    private fun requireRead() {
        if (!Perms.granted(context, Manifest.permission.READ_SMS)) {
            throw RequestException(ErrorCode.PERMISSION, "READ_SMS not granted")
        }
    }

    /** `sms.history` reply. */
    fun history(id: Long, thread: Long?, before: Long?, after: Long?, limit: Int): JSONObject {
        requireRead()
        if (before != null && after != null) throw RequestException(ErrorCode.INVALID, "use before or after, not both")
        // Numbers are inlined (they're Longs): "?" arguments are text, which some providers compare as text.
        fun where(bound: Long?, inclusive: Boolean): String {
            val w = StringBuilder("${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}")
            if (thread != null) w.append(" AND ${Telephony.Sms.THREAD_ID} = $thread")
            val op = if (after != null) (if (inclusive) ">=" else ">") else (if (inclusive) "<=" else "<")
            if (bound != null) w.append(" AND ${Telephony.Sms.DATE} $op $bound")
            return w.toString()
        }
        val order = if (after != null) "${Telephony.Sms.DATE} ASC" else "${Telephony.Sms.DATE} DESC"
        val slots = slotMap()
        // Extra rows so a group of messages with the same time can be kept together on one page.
        val fetch = limit + SAME_TIME_SLACK
        // Blocked senders' texts are dropped, so read on until the page can be filled (or the store ends). Each
        // round continues at the last time read, inclusively, and skips rows it has already seen.
        val rows = ArrayList<SmsRow>()
        val seen = HashSet<Long>()
        var bound = after ?: before
        var inclusive = false
        var exhausted = false
        var rounds = 0
        while (rounds++ < MAX_ROUNDS) {
            val batch = query(where(bound, inclusive), "$order, ${Telephony.Sms._ID} ASC LIMIT $fetch")
            exhausted = batch.size < fetch
            batch.filter { seen.add(it.id) && !hidden(it) }.let { rows += it }
            if (exhausted || rows.size >= fetch || batch.isEmpty()) break
            if (batch.last().time == bound && inclusive) {
                exhausted = false
                break // a whole batch with one time: give up on filling this page rather than loop
            }
            bound = batch.last().time
            inclusive = true
        }
        val (page, _) = JsonPager.pageByTime(rows, limit, exhausted, { it.time }) { it.toJson(slots[it.subId]) }
        return JSONObject()
            .put("t", Msg.SMS_HISTORY)
            .put("id", id)
            .put("msgs", page.items)
            .put("more", page.more)
    }

    /**
     * `sms.read` (D-074): the iPhone read this conversation, so its received messages are marked read here too. Android
     * applies it only with the WRITE_SMS app-op, which Talk on iPhone's helper gives Pairline (otherwise the default
     * messaging app alone may write). Returns how many messages changed.
     */
    fun markRead(thread: Long): Int {
        requireRead()
        val values = android.content.ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        return context.contentResolver.update(
            Telephony.Sms.CONTENT_URI,
            values,
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0 AND ${Telephony.Sms.TYPE} = ?",
            arrayOf(thread.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
        )
    }

    /** `sms.threads` reply: newest first; threads whose newest message is older than [before]. */
    fun threads(id: Long, before: Long?, limit: Int): JSONObject {
        requireRead()
        // One pass without message bodies (fast on large stores); snippets are loaded only for the page.
        val all = LinkedHashMap<Long, ThreadAgg>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms._ID, Telephony.Sms.TYPE),
            "${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}",
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                // A blocked sender's texts don't count: a thread of only those isn't listed (D-043).
                if (c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX && isBlocked(c.getString(1))) continue
                val thread = c.getLong(0)
                val agg = all.getOrPut(thread) { ThreadAgg(thread, c.getLong(3), c.getLong(2)) }
                agg.count++
                c.getString(1)?.takeIf { it.isNotBlank() }?.let { if (agg.addrs.size < 10) agg.addrs += it }
            }
        }
        val candidates = all.values.filter { before == null || it.time < before }
        val window = candidates.take(limit + SAME_TIME_SLACK)
        val bodies = bodiesOf(window.map { it.latestId })
        window.forEach { it.snippet = SmsMapping.snippet(bodies[it.latestId].orEmpty()) }
        val (page, _) = JsonPager.pageByTime(window, limit, window.size == candidates.size, { it.time }) { it.toJson() }
        return JSONObject()
            .put("t", Msg.SMS_THREADS)
            .put("id", id)
            .put("threads", page.items)
            .put("more", page.more)
    }

    /** Finds a just-received message in the store (written by the default SMS app). */
    fun findIncoming(address: String, body: String, notBefore: Long): SmsRow? {
        if (!Perms.granted(context, Manifest.permission.READ_SMS)) return null
        val rows = query(
            "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX} AND ${Telephony.Sms.DATE} >= $notBefore",
            "${Telephony.Sms.DATE} DESC LIMIT 20",
        )
        return rows.firstOrNull { it.body == body && PhoneNumbers.sameNumber(it.address, address) }
    }

    /** Finds a message we just sent; Android writes it to the Sent box for apps that aren't the default SMS app. */
    fun findSent(address: String, body: String, notBefore: Long): SmsRow? {
        if (!Perms.granted(context, Manifest.permission.READ_SMS)) return null
        val rows = query(
            "${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_INBOX} AND ${Telephony.Sms.DATE} >= $notBefore",
            "${Telephony.Sms.DATE} DESC LIMIT 20",
        )
        return rows.firstOrNull { it.body == body && PhoneNumbers.sameNumber(it.address, address) }
    }

    fun slotMap(): Map<Int, Int> = sims.activeSubscriptions().associate { it.subscriptionId to it.simSlotIndex }

    private fun query(selection: String, order: String): List<SmsRow> {
        val out = ArrayList<SmsRow>()
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, selection, null, order)?.use { c ->
            while (c.moveToNext()) out += row(c)
        }
        return out
    }

    private fun row(c: Cursor) = SmsRow(
        id = c.getLong(0),
        thread = c.getLong(1),
        address = c.getString(2).orEmpty(),
        body = c.getString(3).orEmpty(),
        time = c.getLong(4),
        type = c.getInt(5),
        status = if (c.isNull(6)) Telephony.Sms.STATUS_NONE else c.getInt(6),
        subId = if (c.isNull(7)) -1 else c.getInt(7),
    )

    private companion object {
        const val SAME_TIME_SLACK = 200

        /** History queries per page when blocked senders' texts are skipped (each reads `limit + 200` rows). */
        const val MAX_ROUNDS = 20
    }

    private fun bodiesOf(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, String>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY),
            "${Telephony.Sms._ID} IN (${ids.joinToString(",")})",
            null,
            null,
        )?.use { c -> while (c.moveToNext()) out[c.getLong(0)] = c.getString(1).orEmpty() }
        return out
    }

    private class ThreadAgg(val thread: Long, val latestId: Long, val time: Long) {
        val addrs = LinkedHashSet<String>()
        var count = 0
        var snippet = ""

        fun toJson(): JSONObject = JSONObject()
            .put("thread", thread)
            .put("addrs", JSONArray(addrs.toList()))
            .put("snippet", snippet)
            .put("time", time)
            .put("count", count)
    }
}
