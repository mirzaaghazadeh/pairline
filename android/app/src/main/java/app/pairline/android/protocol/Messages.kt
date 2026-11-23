package app.pairline.android.protocol

import org.json.JSONArray
import org.json.JSONObject

/** Message types (PROTOCOL.md, "Catalog"). */
object Msg {
    const val DEVICE_INFO = "device.info"
    const val STATUS = "status"
    const val STATUS_GET = "status.get"
    const val PING = "ping"
    const val PONG = "pong"
    const val UNPAIR = "unpair"
    const val OK = "ok"
    const val ERROR = "error"
    const val SMS_NEW = "sms.new"
    const val SMS_SEND = "sms.send"
    const val SMS_STATE = "sms.state"
    const val SMS_THREADS = "sms.threads"
    const val SMS_HISTORY = "sms.history"
    const val CALL_INCOMING = "call.incoming"
    const val CALL_STATE = "call.state"
    const val CALL_ANSWER = "call.answer"
    const val CALL_REJECT = "call.reject"
    const val CALL_END = "call.end"
    const val CALL_DIAL = "call.dial"
    const val CALL_DTMF = "call.dtmf"
    const val CALL_AUDIO = "call.audio"
    const val CONTACTS_SYNC = "contacts.sync"
    const val CALLLOG_SYNC = "calllog.sync"
    const val CONTACTS_ADD = "contacts.add"
    const val USSD_RUN = "ussd.run"
    const val USSD_RESULT = "ussd.result"
}

/** Capabilities in `status.caps` (PROTOCOL.md, "Capabilities"). */
object Caps {
    const val SMS_RECEIVE = "sms.receive"
    const val SMS_SEND = "sms.send"
    const val SMS_HISTORY = "sms.history"
    const val CALL_OBSERVE = "call.observe"
    const val CALL_CONTROL = "call.control"
    const val CALL_DIAL = "call.dial"
    const val CALL_DTMF = "call.dtmf"
    const val CALL_AUDIO = "call.audio"
    const val CONTACTS = "contacts"
    const val CALLLOG = "calllog"
    const val CONTACTS_ADD = "contacts.add"
    const val USSD = "ussd"
    const val DUALSIM = "dualsim"

    /** In the order the spec lists them; status messages keep this order. */
    val ALL: List<String> = listOf(
        SMS_RECEIVE, SMS_SEND, SMS_HISTORY, CALL_OBSERVE, CALL_CONTROL, CALL_DIAL, CALL_DTMF, CALL_AUDIO,
        CONTACTS, CALLLOG, CONTACTS_ADD, USSD, DUALSIM,
    )
}

/** `error.code` values. */
enum class ErrorCode(val wire: String) {
    UNSUPPORTED("unsupported"),
    PERMISSION("permission"),
    INVALID("invalid"),
    BUSY("busy"),
    FAILED("failed"),
}

/** Thrown by request handlers to answer with an `error` message. */
class RequestException(val code: ErrorCode, message: String) : Exception(message)

object Messages {
    fun ok(id: Long): JSONObject = JSONObject().put("t", Msg.OK).put("id", id)

    /** [msg] is shown to developers, never contains user content, and is always English. */
    fun error(id: Long, code: ErrorCode, msg: String): JSONObject =
        JSONObject().put("t", Msg.ERROR).put("id", id).put("code", code.wire).put("msg", msg)

    fun ping(id: Long): JSONObject = JSONObject().put("t", Msg.PING).put("id", id)

    fun pong(id: Long): JSONObject = JSONObject().put("t", Msg.PONG).put("id", id)

    fun unpair(): JSONObject = JSONObject().put("t", Msg.UNPAIR)

    fun deviceInfo(name: String, os: String, app: String): JSONObject = JSONObject()
        .put("t", Msg.DEVICE_INFO)
        .put("name", name)
        .put("platform", "android")
        .put("os", os)
        .put("app", app)
        .put("proto", Proto.VERSION)
}

/** The message type, or null if it's missing. */
val JSONObject.type: String? get() = optString("t").takeIf { it.isNotEmpty() }

/** A request's integer `id`, or null if it's missing or not an integer. */
val JSONObject.requestId: Long? get() = longOrNull("id")

/** An optional string field: null if missing, JSON null, or not a string. */
fun JSONObject.stringOrNull(name: String): String? = (opt(name) as? String)

/** An optional integer field: null if missing, JSON null, or not a whole number. */
fun JSONObject.longOrNull(name: String): Long? = wholeNumber(opt(name))

private fun wholeNumber(raw: Any?): Long? = when (raw) {
    is Int -> raw.toLong()
    is Long -> raw
    is Number -> raw.toDouble().takeIf { it == Math.floor(it) && !it.isInfinite() }?.toLong()
    else -> null
}

fun JSONObject.intOrNull(name: String): Int? = longOrNull(name)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

fun JSONObject.boolOrNull(name: String): Boolean? = opt(name) as? Boolean

/** Puts [value], or JSON null when it's null (org.json drops plain nulls). */
fun JSONObject.putNullable(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)

/**
 * Builds one page of a list reply so the whole message stays under [budgetBytes] of UTF-8 JSON
 * (PROTOCOL.md: frames are at most 65,535 bytes, so replies are paged at 30 KB).
 */
class JsonPage(val items: JSONArray, val count: Int, val more: Boolean, val lastIndex: Int)

object JsonPager {
    /** Room left for the envelope around the list (`t`, `id`, `more`, cursors…). */
    const val ENVELOPE_RESERVE = 512

    /**
     * Takes items in order until [limit] items or the byte budget is reached. [more] is true if any
     * item was left out. A single item larger than the budget is skipped (it can't be sent at all).
     */
    fun <T> page(
        source: Iterator<T>,
        limit: Int,
        budgetBytes: Int = Proto.PAGE_BUDGET_BYTES,
        encode: (T) -> JSONObject,
    ): Pair<JsonPage, List<T>> {
        val array = JSONArray()
        val taken = ArrayList<T>()
        var used = ENVELOPE_RESERVE
        var more = false
        while (source.hasNext()) {
            if (taken.size >= limit) {
                more = true
                break
            }
            val item = source.next()
            val json = encode(item)
            val size = utf8Length(json.toString()) + 1
            if (size + ENVELOPE_RESERVE > budgetBytes) continue // can never fit; skip it
            if (used + size > budgetBytes) {
                more = true
                break
            }
            array.put(json)
            taken += item
            used += size
        }
        return JsonPage(array, taken.size, more, taken.size - 1) to taken
    }

    /**
     * Pages a list sorted by time (either direction) whose cursor is a strict time bound (`before` / `after`).
     * Items sharing one time are never split across pages: a page may go over [limit] to finish such a group,
     * and if the byte budget would cut a group, the whole group moves to the next page (PROTOCOL.md
     * clarifications). [sourceExhausted] says whether [items] holds everything that's left.
     */
    fun <T> pageByTime(
        items: List<T>,
        limit: Int,
        sourceExhausted: Boolean,
        time: (T) -> Long,
        budgetBytes: Int = Proto.PAGE_BUDGET_BYTES,
        encode: (T) -> JSONObject,
    ): Pair<JsonPage, List<T>> {
        class Taken<T>(val index: Int, val item: T, val json: JSONObject)
        val taken = ArrayList<Taken<T>>()
        var used = ENVELOPE_RESERVE
        var i = 0
        while (i < items.size) {
            val item = items[i]
            val lastTime = taken.lastOrNull()?.let { time(it.item) }
            if (taken.size >= limit && time(item) != lastTime) break
            val json = encode(item)
            val size = utf8Length(json.toString()) + 1
            if (size + ENVELOPE_RESERVE > budgetBytes) {
                i++ // can never be sent; skip it
                continue
            }
            if (used + size > budgetBytes) {
                if (lastTime != null && time(item) == lastTime) {
                    val groupStart = taken.indexOfFirst { time(it.item) == lastTime }
                    if (groupStart > 0) {
                        while (taken.size > groupStart) taken.removeAt(taken.lastIndex)
                    }
                }
                break
            }
            taken += Taken(i, item, json)
            used += size
            i++
        }
        val consumed = taken.lastOrNull()?.let { it.index + 1 } ?: i
        val more = consumed < items.size || !sourceExhausted
        val array = JSONArray().apply { taken.forEach { put(it.json) } }
        return JsonPage(array, taken.size, more, taken.size - 1) to taken.map { it.item }
    }

    fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return n
    }
}
