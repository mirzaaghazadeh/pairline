package app.pairline.android.telephony

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import app.pairline.android.AppGraph
import app.pairline.android.R
import app.pairline.android.log.Log
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.RequestException
import app.pairline.android.protocol.putNullable
import app.pairline.android.tools.AccessPrompt
import org.json.JSONArray
import org.json.JSONObject

/** One message the iPhone asked the bridge to send later (`sms.schedule`, D-084). */
data class ScheduledMessage(val cid: String, val sim: Int?, val to: String, val body: String, val at: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("cid", cid)
        .putNullable("sim", sim)
        .put("to", to)
        .put("body", body)
        .put("at", at)

    companion object {
        fun parse(o: JSONObject): ScheduledMessage? {
            val cid = o.optString("cid").takeIf { it.isNotEmpty() } ?: return null
            val to = o.optString("to").takeIf { it.isNotEmpty() } ?: return null
            val body = o.optString("body").takeIf { it.isNotEmpty() } ?: return null
            val at = o.optLong("at").takeIf { it > 0 } ?: return null
            val sim = if (o.isNull("sim") || !o.has("sim")) null else o.optInt("sim")
            return ScheduledMessage(cid, sim, to, body, at)
        }
    }
}

/** The checks on a new scheduled message, kept pure for unit tests. */
object ScheduleRules {
    const val MAX_ITEMS = 100
    const val MAX_BODY = 10_000

    /** Up to a year ahead; a time a little in the past (clock skew) sends right away. */
    const val MAX_AHEAD_MS = 366L * 24 * 3600 * 1000
    const val PAST_GRACE_MS = 5L * 60 * 1000

    /** A due message is sent this early, so one alarm can take several that are a few seconds apart. */
    const val EARLY_MS = 15_000L

    fun validate(existing: List<ScheduledMessage>, m: ScheduledMessage, now: Long): ScheduledMessage {
        if (m.cid.length > 128) throw RequestException(ErrorCode.INVALID, "bad cid")
        if (existing.any { it.cid == m.cid }) throw RequestException(ErrorCode.BUSY, "cid already in use")
        if (existing.size >= MAX_ITEMS) throw RequestException(ErrorCode.BUSY, "too many scheduled messages")
        val to = PhoneNumbers.normalizeSmsRecipient(m.to) ?: throw RequestException(ErrorCode.INVALID, "to is not a phone number")
        if (m.body.isEmpty() || m.body.length > MAX_BODY) throw RequestException(ErrorCode.INVALID, "bad body")
        if (m.at < now - PAST_GRACE_MS || m.at > now + MAX_AHEAD_MS) throw RequestException(ErrorCode.INVALID, "bad time")
        if (m.sim != null && (m.sim < 0 || m.sim > 7)) throw RequestException(ErrorCode.INVALID, "bad sim")
        return m.copy(to = to)
    }

    fun due(items: List<ScheduledMessage>, now: Long): List<ScheduledMessage> = items.filter { it.at <= now + EARLY_MS }

    /** How long, and how many, finished messages are remembered for an iPhone that was away when they went. */
    const val DONE_TTL_MS = 7L * 24 * 3600 * 1000
    const val MAX_DONE = 50
}

/** A scheduled message the bridge handed to the radio (or couldn't): reported in `sms.scheduled` `done`. */
data class ScheduledDone(val cid: String, val state: String, val key: String?, val error: String?, val time: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("cid", cid)
        .put("state", state)
        .putNullable("key", key)
        .putNullable("error", error)

    fun store(): JSONObject = toJson().put("time", time)

    companion object {
        fun parse(o: JSONObject): ScheduledDone? {
            val cid = o.optString("cid").takeIf { it.isNotEmpty() } ?: return null
            val state = o.optString("state").takeIf { it.isNotEmpty() } ?: return null
            return ScheduledDone(
                cid,
                state,
                o.optString("key").takeIf { !o.isNull("key") && it.isNotEmpty() },
                o.optString("error").takeIf { !o.isNull("error") && it.isNotEmpty() },
                o.optLong("time"),
            )
        }
    }
}

/**
 * Messages written on the iPhone to be sent later (D-084). The bridge keeps them, so they go out on time even when
 * the iPhone is away or off: one alarm for the earliest, which sends everything due through [SmsSender] with the
 * iPhone's `cid` (so `sms.state` follows when the iPhone is linked) and arms the next. Kept in app-private storage,
 * excluded from backups like everything else, never logged, and dropped once handed to the radio.
 */
class ScheduledSms(
    private val context: Context,
    private val sender: SmsSender,
    /** Sends an event to the iPhone when it's linked (`sms.state` for a message that couldn't go). */
    private val emit: (JSONObject) -> Unit,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("scheduled_sms", Context.MODE_PRIVATE)
    private val lock = Any()

    init {
        // The sender reports every message's outcome; keep the ones that were scheduled, with the stored key, so an
        // iPhone that was away can match them to its own copy later.
        sender.onOutcome = { cid, state, key, error -> recordOutcome(cid, state, key, error) }
    }

    fun all(): List<ScheduledMessage> = synchronized(lock) { load() }

    fun add(m: ScheduledMessage) {
        synchronized(lock) {
            val items = load()
            save(items + ScheduleRules.validate(items, m, System.currentTimeMillis()))
        }
        Log.i(TAG, "message scheduled")
        arm()
    }

    fun remove(cid: String): Boolean {
        val removed = synchronized(lock) {
            val items = load()
            val next = items.filterNot { it.cid == cid }
            if (next.size != items.size) save(next)
            next.size != items.size
        }
        if (removed) arm()
        return removed
    }

    /** Sends everything due now (from the alarm), then arms the next one. */
    fun sendDue() {
        val due = synchronized(lock) {
            val items = load()
            val due = ScheduleRules.due(items, System.currentTimeMillis())
            if (due.isNotEmpty()) save(items - due.toSet())
            due
        }
        for (m in due) {
            try {
                synchronized(lock) { saveDone(loadDone() + ScheduledDone(m.cid, "pending", null, null, System.currentTimeMillis())) }
                sender.send(m.sim, m.to, m.body, m.cid)
                Log.i(TAG, "scheduled message handed to the radio")
            } catch (e: RequestException) {
                Log.w(TAG, "scheduled message not sent: ${e.code.wire}")
                val error = e.message ?: e.code.wire
                recordOutcome(m.cid, "failed", null, error)
                emit(
                    JSONObject().put("t", Msg.SMS_STATE).put("cid", m.cid).put("state", "failed")
                        .put("key", JSONObject.NULL).put("error", error),
                )
            }
        }
        arm()
    }

    /** The radio's answer for a message this list sent; other messages are ignored. */
    private fun recordOutcome(cid: String, state: String, key: String?, error: String?) = synchronized(lock) {
        val done = loadDone()
        val i = done.indexOfFirst { it.cid == cid }
        if (i < 0) return@synchronized
        val old = done[i]
        // "delivered" after "sent" keeps the key; "failed" after "sent" (not delivered) too.
        val updated = old.copy(state = state, key = key ?: old.key, error = error)
        saveDone(done.toMutableList().also { it[i] = updated })
    }

    private fun loadDone(): List<ScheduledDone> {
        val array = runCatching { JSONArray(prefs.getString(KEY_DONE, null) ?: return emptyList()) }.getOrNull()
            ?: return emptyList()
        val cutoff = System.currentTimeMillis() - ScheduleRules.DONE_TTL_MS
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(ScheduledDone::parse) }
            .filter { it.time >= cutoff }
    }

    private fun saveDone(items: List<ScheduledDone>) {
        val kept = items.takeLast(ScheduleRules.MAX_DONE)
        prefs.edit { putString(KEY_DONE, JSONArray().apply { kept.forEach { put(it.store()) } }.toString()) }
    }

    /** One alarm for the earliest message; exact when Android allows it, else as close as Doze lets it be. */
    fun arm() {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent()
        val next = all().minOfOrNull { it.at }
        if (next == null) {
            am.cancel(pi)
            return
        }
        if (exactAllowed(am)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    /** Android 13+ asks the user before an app may set exact alarms ("Alarms & reminders"). */
    fun exactAllowed(am: AlarmManager? = context.getSystemService(AlarmManager::class.java)): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am?.canScheduleExactAlarms() == true

    /** After a message was scheduled without exact timing: ask the user once to allow it. */
    fun askForExactTiming() {
        if (exactAllowed() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        AccessPrompt.show(
            context,
            AccessPrompt.ID_EXACT_ALARM,
            R.string.schedule_access_title,
            R.string.schedule_access_text,
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}")),
        )
    }

    /**
     * `sms.scheduled`: the waiting messages, soonest first; `done`, what happened to the ones sent in the last week
     * (for an iPhone that was away then); and whether the bridge can send at the exact minute.
     */
    fun toMessage(id: Long): JSONObject = JSONObject()
        .put("t", Msg.SMS_SCHEDULED)
        .put("id", id)
        .put("items", JSONArray().apply { all().sortedBy { it.at }.forEach { put(it.toJson()) } })
        .put("done", JSONArray().apply { synchronized(lock) { loadDone() }.forEach { put(it.toJson()) } })
        .put("exact", exactAllowed())

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ScheduledSmsReceiver::class.java).setAction(ACTION_SEND_DUE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun load(): List<ScheduledMessage> {
        val array = runCatching { JSONArray(prefs.getString(KEY, null) ?: return emptyList()) }.getOrNull()
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(ScheduledMessage::parse) }
    }

    private fun save(items: List<ScheduledMessage>) {
        prefs.edit { putString(KEY, JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()) }
    }

    companion object {
        private const val TAG = "schedule"
        private const val KEY = "items"
        private const val KEY_DONE = "done"
        const val ACTION_SEND_DUE = "app.pairline.android.SEND_SCHEDULED"
    }
}

/** The scheduled-message alarm; reached only through Pairline's own PendingIntent. */
class ScheduledSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduledSms.ACTION_SEND_DUE) return
        val pending = goAsync()
        AppGraph.io.execute {
            try {
                AppGraph.scheduledSms.sendDue()
            } catch (e: Exception) {
                Log.e("schedule", "couldn't send scheduled messages", e)
            } finally {
                pending.finish()
            }
        }
    }
}
