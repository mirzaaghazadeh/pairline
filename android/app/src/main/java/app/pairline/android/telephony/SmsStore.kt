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

/** Reads SMS history and threads from Android's SMS store (READ_SMS). Never writes to it. */
class SmsStore(private val context: Context, private val sims: SimDirectory) {

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
        val where = StringBuilder("${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}")
        val args = ArrayList<String>()
        if (thread != null) {
            where.append(" AND ${Telephony.Sms.THREAD_ID} = ?")
            args += thread.toString()
        }
        if (before != null) {
            where.append(" AND ${Telephony.Sms.DATE} < ?")
            args += before.toString()
        }
        if (after != null) {
            where.append(" AND ${Telephony.Sms.DATE} > ?")
            args += after.toString()
        }
        val order = if (after != null) "${Telephony.Sms.DATE} ASC" else "${Telephony.Sms.DATE} DESC"
        val slots = slotMap()
        val rows = query(where.toString(), args.toTypedArray(), "$order LIMIT ${limit + 1}")
        val (page, _) = JsonPager.page(rows.iterator(), limit) { it.toJson(slots[it.subId]) }
        return JSONObject()
            .put("t", Msg.SMS_HISTORY)
            .put("id", id)
            .put("msgs", page.items)
            .put("more", page.more)
    }

    /** `sms.threads` reply: newest first; threads whose newest message is older than [before]. */
    fun threads(id: Long, before: Long?, limit: Int): JSONObject {
        requireRead()
        val all = LinkedHashMap<Long, ThreadAgg>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}",
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val thread = c.getLong(0)
                val agg = all.getOrPut(thread) {
                    ThreadAgg(thread, SmsMapping.snippet(c.getString(2).orEmpty()), c.getLong(3))
                }
                agg.count++
                c.getString(1)?.takeIf { it.isNotBlank() }?.let { if (agg.addrs.size < 10) agg.addrs += it }
            }
        }
        val candidates = all.values.asSequence().filter { before == null || it.time < before }.iterator()
        val (page, _) = JsonPager.page(candidates, limit) { it.toJson() }
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
            "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX} AND ${Telephony.Sms.DATE} >= ?",
            arrayOf(notBefore.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT 20",
        )
        return rows.firstOrNull { it.body == body && PhoneNumbers.sameNumber(it.address, address) }
    }

    /** Finds a message we just sent; Android writes it to the Sent box for apps that aren't the default SMS app. */
    fun findSent(address: String, body: String, notBefore: Long): SmsRow? {
        if (!Perms.granted(context, Manifest.permission.READ_SMS)) return null
        val rows = query(
            "${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_INBOX} AND ${Telephony.Sms.DATE} >= ?",
            arrayOf(notBefore.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT 20",
        )
        return rows.firstOrNull { it.body == body && PhoneNumbers.sameNumber(it.address, address) }
    }

    fun slotMap(): Map<Int, Int> = sims.activeSubscriptions().associate { it.subscriptionId to it.simSlotIndex }

    private fun query(selection: String, args: Array<String>, order: String): List<SmsRow> {
        val out = ArrayList<SmsRow>()
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, selection, args, order)?.use { c ->
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

    private class ThreadAgg(val thread: Long, val snippet: String, val time: Long) {
        val addrs = LinkedHashSet<String>()
        var count = 0

        fun toJson(): JSONObject = JSONObject()
            .put("thread", thread)
            .put("addrs", JSONArray(addrs.toList()))
            .put("snippet", snippet)
            .put("time", time)
            .put("count", count)
    }
}
