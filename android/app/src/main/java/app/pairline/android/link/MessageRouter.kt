package app.pairline.android.link

import android.os.Build
import app.pairline.android.AppPrefs
import app.pairline.android.BuildConfig
import app.pairline.android.audio.CallAudioRelay
import app.pairline.android.log.Log
import app.pairline.android.netshare.NetShare
import app.pairline.android.netshare.NetSwitchRequest
import app.pairline.android.netshare.NetSwitches
import app.pairline.android.protocol.Caps
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.Messages
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.RequestException
import app.pairline.android.protocol.boolOrNull
import app.pairline.android.protocol.intOrNull
import app.pairline.android.protocol.longOrNull
import app.pairline.android.protocol.requestId
import app.pairline.android.protocol.stringOrNull
import app.pairline.android.protocol.type
import app.pairline.android.telephony.BlockList
import app.pairline.android.telephony.BlockRules
import app.pairline.android.telephony.CallTracker
import app.pairline.android.telephony.ContactsSync
import app.pairline.android.telephony.PhoneNumbers
import app.pairline.android.telephony.SmsMapping
import app.pairline.android.telephony.SmsSender
import app.pairline.android.telephony.SmsStore
import app.pairline.android.telephony.StatusMonitor
import app.pairline.android.telephony.UssdRunner
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns the iPhone's requests into telephony calls and sends the replies (PROTOCOL.md, "Messages").
 * Requests run off the link thread; replies go back through [LinkManager.send].
 */
class MessageRouter(
    private val link: LinkManager,
    private val prefs: AppPrefs,
    private val status: StatusMonitor,
    private val smsStore: SmsStore,
    private val smsSender: SmsSender,
    private val calls: CallTracker,
    private val contacts: ContactsSync,
    private val ussd: UssdRunner,
    private val audio: CallAudioRelay,
    private val netShare: NetShare,
    private val files: FileMessageHandler,
    private val blockList: BlockList,
    private val netSwitches: NetSwitches,
) : MessageHandler {

    /** `net.share` reply or event: the proxy's address and this session's credentials (PROTOCOL.md, M8). */
    fun netShareReply(id: Long?): JSONObject {
        val s = netShare.session.value
        return JSONObject()
            .put("t", Msg.NET_SHARE)
            .apply { if (id != null) put("id", id) }
            .put("on", s != null)
            .apply {
                if (s != null) {
                    put("host", s.host)
                    put("port", s.port)
                    put("user", s.username)
                    put("pass", s.password)
                    put("pac", s.pacUrl)
                    put("vpn", netShare.vpnActive())
                    put("direct", JSONArray(netShare.directSuffixes()))
                }
            }
    }

    /** Sharing was stopped on the bridge (the user turned it off): tell the iPhone. */
    fun netShareChanged() {
        if (netShare.session.value == null) link.send(netShareReply(null))
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e(TAG, "router task failed", e) },
    )
    private var ticker: Job? = null
    private val lastStatusKey = AtomicLong(0)
    private val lastStatusSent = AtomicLong(0)
    private var statusPushPending: Job? = null

    /**
     * Called when a session is ready: device.info and status on that session; if it now carries events, also
     * calls in progress and where the iPhone can find us on Wi-Fi.
     */
    override fun onEstablished(token: Long, kind: TransportKind, carriesEvents: Boolean) {
        scope.launch {
            link.reply(token, Messages.deviceInfo(deviceName(), Build.VERSION.RELEASE ?: "", BuildConfig.VERSION_NAME))
            if (carriesEvents) {
                sendStatus(force = true)
                calls.replay()
                wifiChanged()
            } else {
                link.reply(token, status.build())
            }
        }
        startTicker()
    }

    /** Bluetooth dropped and Wi-Fi stayed (or the reverse): the new event session gets the current state. */
    override fun onEventSessionChanged(token: Long) {
        scope.launch {
            sendStatus(force = true)
            calls.replay()
            wifiChanged()
        }
    }

    /** The Wi-Fi addresses, port or hotspot changed: tell the iPhone (`wifi.offer`, PROTOCOL.md "Wi-Fi"). */
    fun wifiChanged() {
        if (!link.isConnected) return
        val s = link.state.value
        link.send(Messages.wifiOffer(s.wifiHint, s.hotspotBand))
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            // At least every 60 s (PROTOCOL.md): wake when the last status turns 60 s old.
            while (isActive) {
                val age = System.currentTimeMillis() - lastStatusSent.get()
                delay((STATUS_INTERVAL_MS - age).coerceIn(1_000, STATUS_INTERVAL_MS))
                if (System.currentTimeMillis() - lastStatusSent.get() >= STATUS_INTERVAL_MS - 500) sendStatus(force = true)
            }
        }
    }

    override fun onDisconnected() {
        ticker?.cancel()
        ticker = null
        audio.stop(null, "link closed")
    }

    /** Something in the status may have changed; sends it if it really did (at most every 2 s). */
    fun statusChanged() {
        if (!link.isConnected) return
        if (statusPushPending?.isActive == true) return
        statusPushPending = scope.launch {
            delay(STATUS_DEBOUNCE_MS)
            sendStatus(force = false)
        }
    }

    private fun sendStatus(force: Boolean) {
        val json = status.build()
        val key = contentKey(json)
        if (!force && key == lastStatusKey.get()) return
        lastStatusKey.set(key)
        lastStatusSent.set(System.currentTimeMillis())
        link.send(json)
    }

    /** A hash of everything but the timestamp, to skip identical status messages. */
    private fun contentKey(json: JSONObject): Long {
        val copy = JSONObject(json.toString())
        copy.remove("time")
        return copy.toString().hashCode().toLong()
    }

    /** The session of the message being handled. Set and read on the link thread only. */
    private var currentToken = 0L

    override fun onMessage(message: JSONObject, token: Long) {
        val type = message.type ?: return
        currentToken = token
        when (type) {
            Msg.DEVICE_INFO -> onDeviceInfo(message)
            Msg.PING -> message.requestId?.let { link.reply(token, Messages.pong(it)) }
            Msg.FILE_OFFER, Msg.FILE_ANSWER, Msg.FILE_DONE, Msg.FILE_CANCEL -> files.onMessage(message, token)
            Msg.PONG -> Unit
            Msg.UNPAIR -> link.onRemoteUnpair()
            Msg.STATUS_GET -> request(message) { id -> status.build(id) }
            Msg.SMS_SEND -> request(message) { id -> smsSend(id, message) }
            Msg.SMS_THREADS -> request(message) { id ->
                smsStore.threads(id, message.longOrNull("before"), SmsMapping.threadsLimit(message.longOrNull("limit")))
            }
            Msg.SMS_HISTORY -> request(message) { id ->
                smsStore.history(
                    id,
                    message.longOrNull("thread"),
                    message.longOrNull("before"),
                    message.longOrNull("after"),
                    SmsMapping.historyLimit(message.longOrNull("limit")),
                )
            }
            Msg.CALL_ANSWER -> request(message) { id -> calls.answer(callId(message)); Messages.ok(id) }
            Msg.CALL_REJECT -> request(message) { id -> calls.reject(callId(message)); Messages.ok(id) }
            Msg.CALL_END -> request(message) { id -> calls.end(callId(message)); Messages.ok(id) }
            Msg.CALL_DIAL -> request(message) { id ->
                val number = message.stringOrNull("number") ?: invalid("number missing")
                calls.dial(sim(message), number)
                Messages.ok(id)
            }
            Msg.CALL_DTMF -> request(message) { id ->
                val digit = message.stringOrNull("digit")?.let(PhoneNumbers::dtmfDigit) ?: invalid("bad digit")
                calls.dtmf(callId(message), digit)
                Messages.ok(id)
            }
            Msg.CALL_AUDIO -> request(message) { id ->
                calls.audio(callId(message), message.boolOrNull("mute"), message.boolOrNull("speaker"))
                Messages.ok(id)
            }
            Msg.CALL_MEDIA_START -> request(message) { id ->
                val call = callId(message)
                if (!calls.isInProgress(call)) invalid("call isn't in progress")
                val via = CallAudioRelay.Via.of(message.stringOrNull("via")) ?: invalid("unknown via")
                // With via "link", the audio rides inside the session this request came on (D-072).
                audio.start(call, wantTalk = message.boolOrNull("talk") ?: true, via = via, token = token)
                Messages.ok(id)
            }
            Msg.CALL_MEDIA_STOP -> request(message) { id ->
                audio.stop(callId(message), "stopped by the iPhone")
                Messages.ok(id)
            }
            Msg.CALL_MEDIA_FEEDBACK -> request(message) { id ->
                audio.feedback(message.boolOrNull("heard"), message.boolOrNull("heardMe"))
                Messages.ok(id)
            }
            Msg.NET_SHARE -> request(message) { id ->
                // Sharing keeps running across link drops; it stops when the iPhone or the user turns it off.
                if (message.boolOrNull("on") == false) netShare.stop("stopped by the iPhone") else netShare.start()
                netShareReply(id)
            }
            Msg.NET_SWITCH -> request(message) { id ->
                // The user flipped a switch on the iPhone (D-073); the status that follows shows the result.
                netSwitches.set(NetSwitchRequest.parse(message.stringOrNull("what"), message.boolOrNull("on"), sim(message)))
                statusChanged()
                Messages.ok(id)
            }
            Msg.CONTACTS_SYNC -> request(message) { id -> contacts.contacts(id, cursor(message)) }
            Msg.CALLLOG_SYNC -> request(message) { id -> contacts.callLog(id, cursor(message)) }
            Msg.CONTACTS_ADD -> request(message) { id -> contactsAdd(id, message) }
            Msg.CALLLOG_DELETE -> request(message) { id -> calllogDelete(id, message) }
            Msg.BLOCK_LIST -> request(message) { id -> blockList.toMessage(id) }
            Msg.BLOCK_SET -> request(message) { id -> blockSet(id, message) }
            Msg.USSD_RUN -> request(message) { id ->
                ussd.run(id, sim(message), message.stringOrNull("code") ?: invalid("code missing")) {
                    link.reply(token, it)
                }
                null // the reply is the ussd.result with the same id, sent when the network answers
            }
            else -> message.requestId?.let { id ->
                // Unknown request: answer so the iPhone doesn't wait (PROTOCOL.md clarifications).
                link.reply(token, Messages.error(id, ErrorCode.UNSUPPORTED, "unknown type"))
            }
        }
    }

    /** Runs a request off the link thread and answers with its reply or an `error`, on the session that asked. */
    private fun request(message: JSONObject, handler: (Long) -> JSONObject?) {
        val id = message.requestId ?: return // a request without an id can't be answered
        val type = message.type
        val token = currentToken // on the link thread: the session that asked
        scope.launch {
            val reply = try {
                handler(id)
            } catch (e: RequestException) {
                Log.i(TAG, "$type → error ${e.code.wire}")
                Messages.error(id, e.code, e.message ?: e.code.wire)
            } catch (e: SecurityException) {
                Log.w(TAG, "$type → permission error", e)
                Messages.error(id, ErrorCode.PERMISSION, "permission denied")
            } catch (e: Exception) {
                Log.e(TAG, "$type failed", e)
                Messages.error(id, ErrorCode.FAILED, "internal error")
            }
            reply?.let { link.reply(token, it) }
        }
    }

    private fun smsSend(id: Long, m: JSONObject): JSONObject {
        val to = m.stringOrNull("to") ?: invalid("to missing")
        val body = m.stringOrNull("body") ?: invalid("body missing")
        val cid = m.stringOrNull("cid") ?: (m.opt("cid") as? Number)?.toString() ?: invalid("cid missing")
        smsSender.send(sim(m), to, body, cid)
        return Messages.ok(id)
    }

    private fun contactsAdd(id: Long, m: JSONObject): JSONObject {
        if (!prefs.contactsAddEnabled) {
            throw RequestException(ErrorCode.PERMISSION, "adding contacts is turned off in the bridge app")
        }
        val name = m.stringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: invalid("bad name")
        val array = m.optJSONArray("numbers") ?: JSONArray()
        val numbers = ArrayList<Pair<String, String?>>()
        for (i in 0 until minOf(array.length(), 10)) {
            val entry = array.opt(i)
            val (raw, label) = when (entry) {
                is JSONObject -> entry.stringOrNull("n") to entry.stringOrNull("label")
                is String -> entry to null
                else -> null to null
            }
            val n = raw?.let(PhoneNumbers::normalizeDialable) ?: invalid("bad number")
            numbers += n to label?.take(40)
        }
        if (numbers.isEmpty()) invalid("numbers missing")
        contacts.add(name, numbers)
        Log.i(TAG, "contact added from the iPhone")
        return Messages.ok(id)
    }

    /** `calllog.delete`: `keys` (call-log keys) or `all: true`. Only what the user deleted on the iPhone. */
    private fun calllogDelete(id: Long, m: JSONObject): JSONObject {
        val all = m.boolOrNull("all") == true
        val keys = strings(m.optJSONArray("keys"), MAX_DELETE_KEYS)
        if (!all && keys.isEmpty()) invalid("keys missing")
        val deleted = contacts.deleteCalls(keys, all)
        Log.i(TAG, "call history: $deleted ${if (all) "(all)" else "selected"} entries deleted from the iPhone")
        return Messages.ok(id)
    }

    /** `block.set`: `add` and `remove` (numbers or sender names) → the updated `block.list`. */
    private fun blockSet(id: Long, m: JSONObject): JSONObject {
        val add = strings(m.optJSONArray("add"), BlockRules.MAX_PER_REQUEST)
        val remove = strings(m.optJSONArray("remove"), BlockRules.MAX_PER_REQUEST)
        if (add.isEmpty() && remove.isEmpty()) invalid("add or remove missing")
        if (add.any { PhoneNumbers.normalizeBlockable(it) == null }) invalid("bad number")
        if (blockList.update(add, remove)) Log.i(TAG, "block list changed from the iPhone (${blockList.all().size} entries)")
        return blockList.toMessage(id)
    }

    /** After the list changed in the bridge app itself: the iPhone's copy follows (`block.list` without an id). */
    fun blockListChanged() {
        if (link.isConnected) scope.launch { link.send(blockList.toMessage(null)) }
    }

    private fun strings(array: JSONArray?, max: Int): List<String> {
        if (array == null) return emptyList()
        if (array.length() > max) invalid("too many entries")
        return (0 until array.length()).map { array.opt(it) as? String ?: invalid("not a string") }
    }

    private fun onDeviceInfo(m: JSONObject) {
        prefs.peerName = m.stringOrNull("name")?.take(60)
        prefs.peerOs = m.stringOrNull("os")?.take(20)
        prefs.peerApp = m.stringOrNull("app")?.take(20)
        val caps = m.optJSONArray("caps")
        link.peerAcceptsFiles = caps != null && (0 until caps.length()).any { caps.opt(it) == Caps.FILES }
        files.onPeerCaps()
        Log.i(TAG, "iPhone app ${prefs.peerApp ?: "?"}, iOS ${prefs.peerOs ?: "?"}, protocol ${m.opt("proto")}")
    }

    /** `call` ids are opaque: a string from us, but accept a number too. */
    private fun callId(m: JSONObject): String = when (val raw = m.opt("call")) {
        is String -> raw
        is Number -> raw.toString()
        else -> invalid("call missing")
    }

    private fun cursor(m: JSONObject): String? = when (val raw = m.opt("since")) {
        is String -> raw
        is Number -> raw.toString()
        else -> null
    }

    private fun sim(m: JSONObject): Int? = m.intOrNull("sim")?.also { if (it < 0 || it > 7) invalid("bad sim") }

    private fun invalid(msg: String): Nothing = throw RequestException(ErrorCode.INVALID, msg)

    /** A generic model name (never the user-chosen device name), like the iPhone's "iPhone". */
    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }

    private companion object {
        const val TAG = "router"
        const val STATUS_INTERVAL_MS = 60_000L
        const val STATUS_DEBOUNCE_MS = 2_000L
        const val MAX_DELETE_KEYS = 500
    }
}
