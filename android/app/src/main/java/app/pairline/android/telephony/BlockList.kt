package app.pairline.android.telephony

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.pairline.android.protocol.Msg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One blocked number or sender name, as the user entered it, and when. */
data class BlockedNumber(val number: String, val addedAt: Long) {
    fun toJson(): JSONObject = JSONObject().put("n", number).put("time", addedAt)
}

/** The list operations, kept pure so they're unit-tested. */
object BlockRules {
    /** 500 entries of ~45 bytes keep a `block.list` reply under the 30 KB page size (PROTOCOL.md). */
    const val MAX_ENTRIES = 500
    const val MAX_PER_REQUEST = 100

    /**
     * Removes the entries that [remove] matches and adds the numbers in [add] that no entry covers yet (both with
     * [PhoneNumbers.blockMatches], so another spelling of the same number counts as the same).
     */
    fun apply(current: List<BlockedNumber>, add: List<String>, remove: List<String>, now: Long): List<BlockedNumber> {
        val out = current.filterTo(ArrayList()) { entry -> remove.none { PhoneNumbers.blockMatches(entry.number, it) } }
        for (raw in add) {
            val number = PhoneNumbers.normalizeBlockable(raw) ?: continue
            if (remove.any { PhoneNumbers.blockMatches(number, it) }) continue
            if (out.any { PhoneNumbers.blockMatches(it.number, number) }) continue
            if (out.size >= MAX_ENTRIES) break
            out += BlockedNumber(number, now)
        }
        return out
    }

    /**
     * [PhoneNumbers.blockMatches] against a whole list, prepared once so checking every row of a large SMS store
     * stays cheap: long numbers by their endings, short codes and sender names by [PhoneNumbers.matchKey].
     */
    class Matcher(list: List<BlockedNumber>) {
        private val long: List<String>
        private val short: Set<String>

        init {
            val (l, s) = list.partition { PhoneNumbers.digits(it.number).length >= PhoneNumbers.MIN_MATCH }
            long = l.map { PhoneNumbers.digits(it.number) }
            short = s.mapNotNullTo(HashSet()) { PhoneNumbers.matchKey(it.number) }
        }

        val isEmpty: Boolean get() = long.isEmpty() && short.isEmpty()

        fun matches(number: String?): Boolean {
            if (number.isNullOrBlank() || isEmpty) return false
            val d = PhoneNumbers.digits(number)
            if (d.length >= PhoneNumbers.MIN_MATCH) return long.any { PhoneNumbers.sameEnding(it, d) }
            return PhoneNumbers.matchKey(number) in short
        }
    }

    fun parse(json: String?): List<BlockedNumber> {
        val array = runCatching { JSONArray(json ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val n = o.optString("n").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            BlockedNumber(n, o.optLong("time"))
        }
    }

    fun encode(list: List<BlockedNumber>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
}

/**
 * The block list (D-043). The bridge holds the authoritative copy so it works while the iPhone is away: texts from a
 * blocked number aren't relayed or listed for the iPhone, and its calls aren't shown there (and are declined here
 * when Pairline can end calls). Stored in app-private preferences, excluded from backups; never logged.
 */
class BlockList(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("blocklist", Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile
    private var entries: List<BlockedNumber> = BlockRules.parse(prefs.getString(KEY, null))

    @Volatile
    private var matcher = BlockRules.Matcher(entries)

    private val _changes = MutableStateFlow(0)

    /** Bumps on every change, so the bridge's screen can recompose. */
    val changes: StateFlow<Int> = _changes

    fun all(): List<BlockedNumber> = entries

    /** Whether [number] (any formatting) is on the list. Unknown or empty numbers are never blocked. */
    fun isBlocked(number: String?): Boolean = matcher.matches(number)

    /** Applies additions and removals; returns whether anything changed. */
    fun update(add: List<String>, remove: List<String>): Boolean = synchronized(lock) {
        val next = BlockRules.apply(entries, add, remove, System.currentTimeMillis())
        if (next == entries) return false
        entries = next
        matcher = BlockRules.Matcher(next)
        prefs.edit { putString(KEY, BlockRules.encode(next)) }
        _changes.value++
        true
    }

    /** `block.list`: the reply to `block.list` / `block.set` (with [id]) or the event after a change here (without). */
    fun toMessage(id: Long?): JSONObject = JSONObject()
        .put("t", Msg.BLOCK_LIST)
        .apply { if (id != null) put("id", id) }
        .put("numbers", JSONArray().apply { entries.forEach { put(it.toJson()) } })

    private companion object {
        const val KEY = "numbers"
    }
}
