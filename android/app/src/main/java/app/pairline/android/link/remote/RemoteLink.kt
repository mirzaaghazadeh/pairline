package app.pairline.android.link.remote

import app.pairline.android.link.MailboxRoute
import app.pairline.android.log.Log
import app.pairline.android.protocol.Hex
import app.pairline.android.protocol.Mailbox
import app.pairline.android.protocol.MailboxDirection
import app.pairline.android.protocol.MailboxHeader
import app.pairline.android.protocol.MailboxKind
import app.pairline.android.protocol.MailboxRecord
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.ProtocolException
import app.pairline.android.protocol.StaticKey
import app.pairline.android.protocol.requestId
import app.pairline.android.protocol.stringOrNull
import app.pairline.android.protocol.type
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** What the UI can show about remote mode. */
data class RemoteStatus(
    val on: Boolean = false,
    /** Checking stopped until the iPhone sends a new token. */
    val problem: RemoteStore.Problem? = null,
    /** The last request failed for a network or server reason; retrying with backoff. */
    val retrying: Boolean = false,
    val lastCheck: Long = 0,
    /** When a message from the iPhone last came through the mailbox (for the diagnostics report). */
    val lastReceived: Long = 0,
)

/**
 * Remote mode's mailbox (D-092, PROTOCOL.md "Remote mode"): the third path next to the Bluetooth and Wi-Fi sessions.
 * While no session is up, events and replies are sealed to the pinned iPhone (kind 1) and put into its alert or quiet
 * mailbox, and the R→B mailbox is checked every few seconds ([checkInterval], D-102: 3 s on the charger, 5–10 s on
 * battery), with the CPU kept awake for it. What's found there goes to the sessions' handler with
 * `LinkManager.MAILBOX_TOKEN`.
 *
 * Every request runs on one coroutine on [Dispatchers.IO], one at a time; the link thread only queues and wakes it.
 */
class RemoteLink(
    private val store: RemoteStore,
    private val identity: () -> StaticKey,
    private val peerKey: () -> ByteArray?,
    /** When a session last heard from the iPhone. */
    private val lastSeen: () -> Long,
    /** Hands a message read from the mailbox to the link thread. */
    private val deliver: (JSONObject) -> Unit,
    private val http: CloudKitWebClient.Http = CloudKitWebClient.HttpsTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** The phone is on its charger: the mailbox is checked every [CHARGING_CHECK_MS] (D-102). */
    private val charging: () -> Boolean = { false },
    /**
     * True while the mailbox is checked on a timer, false while this waits to be woken (a session is up, or remote
     * mode is off). With the screen off Android lets the CPU sleep and the timer would fire minutes late, so the
     * caller holds a partial wake lock while this is true (D-102).
     */
    private val keepAwake: (Boolean) -> Unit = {},
) : MailboxRoute {

    private class Outgoing(val slot: MailboxSlot, val plaintext: ByteArray, val time: Long, val recordId: ByteArray)

    private class Ready(val config: RemoteConfig, val peer: ByteArray, val client: CloudKitWebClient)

    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> Log.e(TAG, "remote task failed", e) },
    )
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val outbox = ArrayDeque<Outgoing>()
    private var job: Job? = null
    private var client: Pair<RemoteConfig, CloudKitWebClient>? = null

    @Volatile private var running = false
    @Volatile private var sessionUp = false
    @Volatile private var lastReceived = 0L
    @Volatile private var lastStatusKey: String? = null
    @Volatile private var lastStatusDetail: String? = null
    @Volatile private var lastStatusSentAt = 0L
    @Volatile private var nextCheckAt = 0L
    @Volatile private var retryAt = 0L
    @Volatile private var failures = 0
    private var nextSweepAt = 0L
    private var lastCheck = 0L

    private val _state = MutableStateFlow(RemoteStatus())
    val state: StateFlow<RemoteStatus> = _state

    /** Starts with the link service. */
    fun start() {
        if (running) return
        running = true
        nextCheckAt = 0
        job = scope.launch { loop() }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
    }

    override val isActive: Boolean get() = running && store.isOn

    override fun send(message: JSONObject) {
        val slot = slotFor(message) ?: return
        if (message.type == Msg.STATUS && message.requestId == null) {
            // Only when it changed since the iPhone last got one, by any path (PROTOCOL.md, "Remote messages"): at
            // once for what matters (statusKey), and at most every STATUS_DETAIL_MS for the rest (signal bars,
            // battery percent), so the iPhone's battery and signal stay roughly current without a record, and a push
            // that wakes the iPhone, for every flicker.
            val key = statusKey(message)
            val detail = statusDetail(message)
            val now = clock()
            val matters = key != lastStatusKey
            val detailDue = detail != lastStatusDetail && now - lastStatusSentAt >= STATUS_DETAIL_MS
            if (!matters && !detailDue) return
            remember(key, detail, now)
        }
        val plaintext = message.toString().toByteArray(Charsets.UTF_8)
        if (plaintext.size > Mailbox.MAX_PLAINTEXT) {
            Log.w(TAG, "${message.type} is too large for the mailbox; dropped")
            return
        }
        synchronized(outbox) {
            if (outbox.size >= MAX_OUTBOX) {
                outbox.removeFirst()
                Log.w(TAG, "mailbox queue full; dropped the oldest record")
            }
            outbox.addLast(Outgoing(slot, plaintext, clock(), Mailbox.newRecordId(random)))
        }
        // Which way a message went (the sessions log theirs): only its type and mailbox, never ids or content.
        Log.i(TAG, "${message.type} goes through iCloud (${slot.name.lowercase()} mailbox)")
        wake.trySend(Unit)
    }

    override fun onSessionChanged(up: Boolean) {
        sessionUp = up
        if (!up) {
            // A session ending changes nothing the iPhone doesn't know: the statuses it got on the session were noted
            // (statusDelivered), so only a status that differs from those goes into the mailbox. (Forgetting them here
            // made every short session, such as a push waking the iPhone for a few seconds, cost another record and
            // push a minute later, over and over.)
            // Check once right after a session ends.
            nextCheckAt = 0
            wake.trySend(Unit)
        }
    }

    override fun statusDelivered(status: JSONObject) {
        if (status.type != Msg.STATUS) return
        remember(statusKey(status), statusDetail(status), clock())
    }

    private fun remember(key: String, detail: String, at: Long) {
        lastStatusKey = key
        lastStatusDetail = detail
        lastStatusSentAt = at
    }

    /** `remote.config` with `on: true`. */
    fun configure(config: RemoteConfig, token: String) {
        store.turnOn(config, token)
        lastReceived = clock()
        nextCheckAt = 0
        retryAt = 0
        failures = 0
        publish()
        Log.i(TAG, "remote mode on (${config.backend.wire}, ${config.environment.wire})")
        wake.trySend(Unit)
        onConfigured?.invoke()
    }

    /** Remote mode was just turned on (by `remote.config` or a remote pairing): introduce this phone to the iPhone. */
    var onConfigured: (() -> Unit)? = null

    /** `remote.token`: a fresh web auth token; checking resumes if it had stopped. */
    fun updateToken(token: String) {
        if (!store.isOn) return
        store.save(token)
        if (store.problem != null) Log.i(TAG, "new web auth token; checking the mailbox again")
        store.problem = null
        retryAt = 0
        failures = 0
        publish()
        wake.trySend(Unit)
    }

    /** `remote.config` with `on: false`: stops, and deletes this phone's remote state and records. */
    fun turnOff() {
        Log.i(TAG, "remote mode turned off by the iPhone")
        forget(farewell = null)
    }

    override fun forget(farewell: JSONObject?) {
        val config = store.config()
        val token = config?.let { store.load() }
        val peer = peerKey()
        val sent = store.allSent()
        store.clear()
        synchronized(outbox) { outbox.clear() }
        lastStatusKey = null
        publish()
        if (config == null || token == null) return
        Log.i(TAG, "remote mode off; deleting this phone's records")
        scope.launch { cleanUp(config, token, peer, sent, farewell) }
    }

    /** One last use of the old token: the farewell, then this phone's records that may still be in iCloud. */
    private fun cleanUp(
        config: RemoteConfig,
        token: String,
        peer: ByteArray?,
        sent: Map<MailboxSlot, List<String>>,
        farewell: JSONObject?,
    ) {
        val c = CloudKitWebClient(config.environment, config.backend, MemoryTokenStore(token), http)
        try {
            if (farewell != null && peer != null) {
                val item = Outgoing(MailboxSlot.QUIET, farewell.toString().toByteArray(Charsets.UTF_8), clock(), Mailbox.newRecordId(random))
                c.put(config.address(item.slot), Hex.encode(item.recordId), seal(peer, item))
            }
            sent.forEach { (slot, names) -> names.chunked(DELETE_BATCH).forEach { c.delete(config.address(slot), it) } }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't delete every record (${describe(e)})")
        }
    }

    private suspend fun loop() {
        if (store.isOn && peerKey() == null) {
            Log.i(TAG, "no paired iPhone; remote mode off")
            store.clear()
        }
        publish()
        while (currentCoroutineContext().isActive) {
            val wait = step()
            keepAwake(wait != null)
            when {
                wait == null -> wake.receive()
                wait > 0 -> withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    /**
     * One pass: sends what's queued, checks the R→B mailbox when it's due and no session is up, and deletes this
     * phone's day-old records. Returns how long to wait before the next pass, or null to wait until woken.
     */
    internal fun step(): Long? {
        val ready = ready() ?: return null
        val now = clock()
        if (now < retryAt) return retryAt - now
        try {
            flush(ready)
            if (!sessionUp && now >= nextCheckAt) {
                val full = check(ready)
                lastCheck = clock()
                nextCheckAt = if (full) {
                    lastCheck
                } else {
                    lastCheck + checkInterval(lastCheck, maxOf(lastSeen(), lastReceived), charging())
                }
            }
            if (now >= nextSweepAt) {
                sweep(ready)
                nextSweepAt = now + SWEEP_EVERY_MS
            }
            if (failures > 0) Log.i(TAG, "mailbox reachable again after $failures failed attempt(s)")
            failures = 0
        } catch (_: CloudKitWebClient.TokenRejected) {
            stopFor(RemoteStore.Problem.TOKEN_REJECTED)
            return null
        } catch (_: CloudKitWebClient.PrivateDatabaseRefused) {
            stopFor(RemoteStore.Problem.PRIVATE_DATABASE_REFUSED)
            return null
        } catch (e: Exception) {
            // Network, CloudKit or a Keystore that isn't ready yet: try again later.
            val wait = backoff(++failures)
            retryAt = clock() + wait
            Log.w(TAG, "mailbox request failed (${describe(e)}); retrying in ${wait / 1000} s")
            publish(retrying = true)
            return wait
        }
        publish()
        return when {
            synchronized(outbox) { outbox.isNotEmpty() } -> 0
            sessionUp -> null
            else -> (nextCheckAt - clock()).coerceAtLeast(0)
        }
    }

    private fun ready(): Ready? {
        if (!running || store.problem != null) return null
        val config = store.config() ?: return null
        val peer = peerKey() ?: return null
        val c = client?.takeIf { it.first == config }?.second
            ?: CloudKitWebClient(config.environment, config.backend, store, http).also { client = config to it }
        return Ready(config, peer, c)
    }

    private fun flush(ready: Ready) {
        while (true) {
            val next = synchronized(outbox) { outbox.firstOrNull() } ?: return
            try {
                ready.client.put(ready.config.address(next.slot), Hex.encode(next.recordId), seal(ready.peer, next))
                store.recordSent(next.slot, Hex.encode(next.recordId), next.time)
            } catch (e: CloudKitWebClient.ServerError) {
                // A refused record inside a good reply (for example a retry of one that did arrive) isn't retried.
                if (e.status != 200) throw e
                Log.w(TAG, "CloudKit refused a mailbox record (${e.code})")
            }
            synchronized(outbox) { outbox.remove(next) }
        }
    }

    /** A retry reuses the record id and time, so the iPhone sees one record even if a reply got lost. */
    private fun seal(peer: ByteArray, item: Outgoing): ByteArray = Mailbox.seal(
        identity(), peer, MailboxDirection.BRIDGE_TO_REMOTE, item.recordId, item.time, item.plaintext, random,
    )

    /** Reads the R→B mailbox once. Returns true if it was full, so it's read again at once. */
    private fun check(ready: Ready): Boolean {
        val box = ready.config.address(MailboxSlot.TO_BRIDGE)
        val records = ready.client.fetch(box, FETCH_LIMIT)
        if (records.isEmpty()) return false
        val now = clock()
        val handled = ArrayList<String>()
        val messages = ArrayList<JSONObject>()
        val dropped = LinkedHashMap<String, Int>()
        for (record in records) {
            val opened = open(ready, record, now, dropped) ?: continue
            handled += record.name
            runCatching { JSONObject(String(opened.plaintext, Charsets.UTF_8)) }.getOrNull()?.let(messages::add)
        }
        // Remembered before they're handled, deleted after: a record is never handled twice.
        store.markHandled(handled, now)
        if (messages.isNotEmpty()) lastReceived = now
        messages.forEach(deliver)
        ready.client.delete(box, records.map { it.name })
        // Which kinds of message came (never their contents), and why any record was dropped.
        val kinds = messages.joinToString(", ") { it.optString("t", "?") }
        val why = if (dropped.isEmpty()) "" else " (" + dropped.entries.joinToString(", ") { "${it.key}: ${it.value}" } + ")"
        Log.i(TAG, "mailbox: ${messages.size} message(s) from the iPhone${if (kinds.isEmpty()) "" else " [$kinds]"}, " +
            "${records.size - messages.size} dropped$why")
        return records.size >= FETCH_LIMIT
    }

    /**
     * The record if it's timely, new, from the paired iPhone and authentic; otherwise null, with the reason counted in
     * [dropped] for the log (a tester's text that never went out needs to say why).
     */
    private fun open(
        ready: Ready,
        record: CloudKitWebClient.MailRecord,
        now: Long,
        dropped: MutableMap<String, Int>,
    ): MailboxRecord? {
        fun drop(why: String): MailboxRecord? {
            dropped[why] = (dropped[why] ?: 0) + 1
            return null
        }
        val header = try {
            MailboxHeader.parse(record.sealed)
        } catch (_: ProtocolException) {
            return drop("unreadable")
        }
        if (header.kind != MailboxKind.SEALED || header.direction != MailboxDirection.REMOTE_TO_BRIDGE) {
            return drop("not for this phone")
        }
        if (!header.isTimely(now)) {
            return drop(if (header.timeMs > now) "dated ahead of this phone's clock" else "older than 14 days")
        }
        if (header.recordName != record.name) return drop("renamed")
        if (store.isHandled(record.name)) return drop("already handled")
        return try {
            Mailbox.open(identity(), ready.peer, MailboxDirection.REMOTE_TO_BRIDGE, record.sealed)
        } catch (_: ProtocolException) {
            drop("not from the paired iPhone")
        }
    }

    /** A sender deletes its own records older than one day (PROTOCOL.md, "Mailboxes in iCloud"). */
    private fun sweep(ready: Ready) {
        val cutoff = clock() - SENT_KEEP_MS
        for (slot in listOf(MailboxSlot.ALERT, MailboxSlot.QUIET)) {
            store.sentBefore(slot, cutoff).chunked(DELETE_BATCH).forEach { names ->
                ready.client.delete(ready.config.address(slot), names)
                store.dropSent(slot, names)
            }
        }
    }

    private fun stopFor(problem: RemoteStore.Problem) {
        store.problem = problem
        Log.w(
            TAG,
            when (problem) {
                RemoteStore.Problem.TOKEN_REJECTED -> "iCloud refused the web auth token; waiting for a new one from the iPhone"
                RemoteStore.Problem.PRIVATE_DATABASE_REFUSED ->
                    "Apple refuses this account's private database to web clients; waiting for a new token"
            },
        )
        publish()
    }

    private fun publish(retrying: Boolean = false) {
        _state.value = RemoteStatus(store.isOn, store.problem, retrying, lastCheck, lastReceived)
    }

    companion object {
        private const val TAG = "remote"
        private const val MAX_OUTBOX = 200
        private const val FETCH_LIMIT = 50
        private const val DELETE_BATCH = 100
        private const val SWEEP_EVERY_MS = 3_600_000L
        private const val SENT_KEEP_MS = 24 * 3_600_000L

        /** A status that changed only in signal bars or battery percent goes at most this often. */
        const val STATUS_DETAIL_MS = 10 * 60_000L

        /*
         * How often to check the R→B mailbox (Apple pushes only to Apple devices, so the bridge has to look), D-102:
         * on the charger every [CHARGING_CHECK_MS], so a text from the iPhone goes out within seconds; on battery every
         * [BURST_CHECK_MS] for [BURST_MS] after a record from the iPhone, otherwise every [BATTERY_CHECK_MS].
         * (D-092's 30 s and 2 minutes made the first text after a quiet spell wait minutes.)
         */
        const val CHARGING_CHECK_MS = 3_000L
        const val BURST_MS = 2 * 60_000L
        const val BURST_CHECK_MS = 5_000L
        const val BATTERY_CHECK_MS = 10_000L
        const val RETRY_FIRST_MS = 30_000L
        const val RETRY_MAX_MS = 10 * 60_000L

        /** Events that only make sense on a session (Wi-Fi, files, call audio); replies always go. */
        private val SESSION_ONLY = setOf(
            Msg.WIFI_OFFER, Msg.WIFI_MEDIA, Msg.FILE_OFFER, Msg.FILE_ANSWER, Msg.FILE_DONE, Msg.FILE_CANCEL,
            Msg.CALL_MEDIA_STATE,
        )

        /**
         * The alert mailbox for what the user should see (`sms.new`, `call.incoming`, a missed call's `call.state`),
         * the quiet one for everything else, null for an event that only makes sense on a session.
         */
        fun slotFor(message: JSONObject): MailboxSlot? {
            val type = message.type ?: return null
            if (message.requestId == null && type in SESSION_ONLY) return null
            return when {
                type == Msg.SMS_NEW || type == Msg.CALL_INCOMING -> MailboxSlot.ALERT
                type == Msg.CALL_STATE && isMissedCall(message) -> MailboxSlot.ALERT
                else -> MailboxSlot.QUIET
            }
        }

        private fun isMissedCall(m: JSONObject): Boolean =
            m.stringOrNull("state") == "ended" && m.stringOrNull("dir") == "in" && m.stringOrNull("reason") == "missed"

        /**
         * What in a status is worth a mailbox record: everything but its `time`, the SIMs' signal bars and the battery
         * level's last digit. Each record costs an iCloud write and a push to the iPhone, so flickering bars or a
         * battery dropping one percent at a time don't send one; a new network type, roaming, a SIM, charging or each
         * 10 % of battery do.
         */
        /** Everything in a status but its `time`: a change here is a detail worth sending at most every 10 minutes. */
        fun statusDetail(status: JSONObject): String = JSONObject(status.toString()).apply { remove("time") }.toString()

        fun statusKey(status: JSONObject): String = JSONObject(status.toString()).apply {
            remove("time")
            optJSONObject("battery")?.let { battery ->
                if (battery.has("level")) battery.put("level", battery.optInt("level") / 10 * 10)
            }
            optJSONArray("sims")?.let { sims ->
                for (i in 0 until sims.length()) sims.optJSONObject(i)?.remove("signal")
            }
        }.toString()

        fun checkInterval(now: Long, lastHeard: Long, charging: Boolean = false): Long = when {
            charging -> CHARGING_CHECK_MS
            now - lastHeard < BURST_MS -> BURST_CHECK_MS
            else -> BATTERY_CHECK_MS
        }

        /** 30 s, 1, 2, 4, 8 minutes, then 10 minutes. */
        fun backoff(failures: Int): Long =
            (RETRY_FIRST_MS shl (failures - 1).coerceIn(0, 10)).coerceAtMost(RETRY_MAX_MS)

        /** For the log: never a URL (it carries the token) or a server's free text. */
        internal fun describe(e: Exception): String = when (e) {
            is CloudKitWebClient.ServerError -> "CloudKit ${e.status} ${e.code ?: ""}".trim()
            else -> e.javaClass.simpleName
        }
    }
}
