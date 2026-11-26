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
    const val CALL_MEDIA_START = "call.media.start"
    const val CALL_MEDIA_STOP = "call.media.stop"
    const val CALL_MEDIA_STATE = "call.media.state"
    const val CALL_MEDIA_FEEDBACK = "call.media.feedback"
    const val NET_SHARE = "net.share"
    const val NET_SWITCH = "net.switch"
    const val SMS_READ = "sms.read"
    const val CALL_HOLD = "call.hold"
    const val CONTACTS_UPDATE = "contacts.update"
    const val PHONE_FIND = "phone.find"
    const val PHONE_RINGER = "phone.ringer"
    const val NOTIF_POSTED = "notif.posted"
    const val NOTIF_REMOVED = "notif.removed"
    const val NOTIF_DISMISS = "notif.dismiss"
    const val NOTIF_REPLY = "notif.reply"
    const val CONTACTS_SYNC = "contacts.sync"
    const val CALLLOG_SYNC = "calllog.sync"
    const val CONTACTS_ADD = "contacts.add"
    const val USSD_RUN = "ussd.run"
    const val USSD_RESULT = "ussd.result"
    const val WIFI_OFFER = "wifi.offer"
    const val WIFI_MEDIA = "wifi.media"
    const val FILE_OFFER = "file.offer"
    const val FILE_ANSWER = "file.answer"
    const val FILE_DONE = "file.done"
    const val FILE_CANCEL = "file.cancel"
    const val CALLLOG_DELETE = "calllog.delete"
    const val BLOCK_LIST = "block.list"
    const val BLOCK_SET = "block.set"

    // D-084: messages sent later by the bridge, the mobile-data meter, and ringing the iPhone.
    const val SMS_SCHEDULE = "sms.schedule"
    const val SMS_UNSCHEDULE = "sms.unschedule"
    const val SMS_SCHEDULED = "sms.scheduled"
    const val DATA_USAGE = "data.usage"
    const val REMOTE_RING = "remote.ring"

    // D-088: the bridge phone's photos, browsed from the iPhone over Wi-Fi.
    const val MEDIA_LIST = "media.list"
    const val MEDIA_GET = "media.get"

    // D-089: picture and group messages.
    const val MMS_PART = "mms.part"
    const val MMS_ATTACH = "mms.attach"
    const val MMS_SEND = "mms.send"
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
    const val WIFI = "wifi"
    const val FILES = "files"

    /** Call audio can be heard on the iPhone ("Talk on iPhone" set up, Pairline's helper running, capture allowed). D-047. */
    const val CALL_MEDIA = "call.media"

    /** …and the iPhone's microphone can be fed into the call. */
    const val CALL_MEDIA_TALK = "call.media.talk"

    /** …and the audio can travel inside the Bluetooth session, as Opus (`call.media.start` `via: "link"`, D-072). */
    const val CALL_MEDIA_LINK = "call.media.link"

    /** The user allows internet sharing for the iPhone (M8, D-048). */
    const val NET_SHARE = "net.share"

    /**
     * The iPhone may switch this phone's hotspot, Bluetooth tethering, mobile data and data SIM (`net.switch`, through
     * Talk on iPhone's helper, D-073).
     */
    const val NET_SWITCH = "net.switch"

    /** A conversation read on the iPhone is marked read on the bridge too (`sms.read`, D-074). */
    const val SMS_READ = "sms.read"

    /** A call can be put on hold from the iPhone (`call.hold`, the companion call service, D-074). */
    const val CALL_HOLD = "call.hold"

    /** Contacts can be edited from the iPhone (`contacts.update`, with "Add contacts from iPhone", D-074). */
    const val CONTACTS_EDIT = "contacts.edit"

    /** The iPhone can make this phone ring loudly (`phone.find`, D-074). */
    const val PHONE_FIND = "phone.find"

    /** The iPhone can switch the ringer between sound, vibrate and silent (`phone.ringer`, D-074). */
    const val PHONE_RINGER = "phone.ringer"

    /** Notifications from apps the user chose are mirrored (`notif.posted`, notification access given, D-077). */
    const val NOTIF_MIRROR = "notif.mirror"

    /**
     * `call.dial` takes `voicemail: true`, `status.sims` carries `vm`, and USSD codes may carry a '+' number after a
     * '*' for call forwarding (D-082).
     */
    const val CALL_VOICEMAIL = "call.voicemail"

    /** Messages can be given to the bridge to send later (`sms.schedule`, D-084). */
    const val SMS_SCHEDULE = "sms.schedule"

    /** The bridge reports its mobile data use (`data.usage`, Usage access given, D-084). */
    const val DATA_USAGE = "data.usage"

    /** Call-history entries can be deleted on the bridge too (D-043). */
    const val CALLLOG_DELETE = "calllog.delete"

    /** The bridge keeps a block list (D-043). */
    const val BLOCK = "block"

    /**
     * Pairline is Android's call-screening app (D-085): blocked numbers don't ring at all, and "Silence unknown
     * callers" (`block.set` `unknown`) works.
     */
    const val CALL_SCREEN = "call.screen"

    /** The iPhone may browse and fetch this phone's photos (opt-in, photo access given, D-088). */
    const val MEDIA_PHOTOS = "media.photos"

    /**
     * Picture and group messages (D-089): `sms.history` and `sms.threads` include MMS, `mms.part` sends an attachment,
     * and `mms.attach` / `mms.send` send one.
     */
    const val MMS = "mms"

    /** In the order the spec lists them; status messages keep this order. */
    val ALL: List<String> = listOf(
        SMS_RECEIVE, SMS_SEND, SMS_HISTORY, SMS_READ, SMS_SCHEDULE, MMS, CALL_OBSERVE, CALL_CONTROL, CALL_DIAL, CALL_DTMF,
        CALL_AUDIO, CALL_HOLD, CALL_VOICEMAIL, CONTACTS, CALLLOG, CONTACTS_ADD, CONTACTS_EDIT, USSD, DUALSIM, CALL_MEDIA,
        CALL_MEDIA_TALK,
        CALL_MEDIA_LINK, NET_SHARE, NET_SWITCH, PHONE_FIND, PHONE_RINGER, NOTIF_MIRROR, DATA_USAGE, WIFI, FILES,
        CALLLOG_DELETE, BLOCK, CALL_SCREEN, MEDIA_PHOTOS,
    )

    /** What the iPhone can receive, in its `device.info` `caps`: `remote.ring` needs this (D-084). */
    const val REMOTE_RING = "ring"
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

    /** Where the iPhone can reach this bridge over Wi-Fi; `ip: []` when there's no Wi-Fi now (M5). */
    fun wifiOffer(hint: WifiHint?, band: String?): JSONObject = JSONObject()
        .put("t", Msg.WIFI_OFFER)
        .put("ip", JSONArray(hint?.addresses.orEmpty()))
        .putNullable("port", hint?.port)
        .putNullable("ssid", hint?.ssid)
        .putNullable("password", hint?.password)
        .putNullable("band", band)

    /** The UDP port of this Wi-Fi session's media channel, or null when there is none. */
    fun wifiMedia(port: Int?): JSONObject = JSONObject().put("t", Msg.WIFI_MEDIA).putNullable("port", port)

    fun fileOffer(fid: Long, name: String, size: Long, mime: String?): JSONObject = JSONObject()
        .put("t", Msg.FILE_OFFER)
        .put("fid", fid)
        .put("name", name)
        .put("size", size)
        .putNullable("type", mime)

    fun fileAnswer(fid: Long, accept: Boolean, reason: String? = null): JSONObject = JSONObject()
        .put("t", Msg.FILE_ANSWER)
        .put("fid", fid)
        .put("accept", accept)
        .putNullable("reason", reason)

    fun fileDone(fid: Long, ok: Boolean, reason: String? = null): JSONObject = JSONObject()
        .put("t", Msg.FILE_DONE)
        .put("fid", fid)
        .put("ok", ok)
        .putNullable("reason", reason)

    fun fileCancel(fid: Long, reason: String? = null): JSONObject = JSONObject()
        .put("t", Msg.FILE_CANCEL)
        .put("fid", fid)
        .putNullable("reason", reason)
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
