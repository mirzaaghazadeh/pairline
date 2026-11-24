package app.pairline.android.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.TelephonyManager
import app.pairline.android.AppPrefs
import app.pairline.android.log.Log
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.RequestException
import app.pairline.android.protocol.putNullable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Follows calls on the bridge phone and relays them as `call.incoming` / `call.state`.
 *
 * Two sources:
 * - the companion [PairlineInCallService], when Android binds it: exact states, end causes, and full control
 *   (answer, reject, end, DTMF, mute, speaker);
 * - telephony callbacks otherwise: ringing / off-hook / idle per SIM, with answer and end through TelecomManager.
 * When the InCallService is bound it is the only source.
 */
class CallTracker(
    private val context: Context,
    private val sims: SimDirectory,
    private val prefs: AppPrefs,
    private val emit: (JSONObject) -> Unit,
    private val onCapabilityChange: () -> Unit,
) {
    private class Tracked(
        val id: String,
        val incoming: Boolean,
        var number: String?,
        var name: String?,
        var sim: Int?,
        var state: String,
        var since: Long,
        val subId: Int?,
    ) {
        var telecom: Call? = null
        var callback: Call.Callback? = null
        var incomingSent = false
        var wasRinging = state == CallMapping.RINGING
        var weRejected = false
        var weEnded = false
    }

    private val pool = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pairline-calls") }

    /** Runs on the calls thread and logs failures, which the executor would otherwise swallow. */
    private val executor = object {
        fun execute(block: () -> Unit) {
            pool.execute(guard(block))
        }

        fun schedule(block: () -> Unit, delay: Long, unit: TimeUnit) {
            pool.schedule(guard(block), delay, unit)
        }

        fun <T> submit(block: () -> T): java.util.concurrent.Future<T> = pool.submit(java.util.concurrent.Callable { block() })

        private fun guard(block: () -> Unit) = Runnable {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "call tracking failed", e)
            }
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val calls = LinkedHashMap<String, Tracked>()
    private val idPrefix = java.lang.Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36)
    private var nextId = 1
    private var lastIncomingNumber: Pair<String, Long>? = null
    private var lastDial: Triple<String, Int?, Long>? = null

    @Volatile
    private var service: PairlineInCallService? = null

    /** DTMF and audio controls work: the companion service is bound now or has been on this phone. */
    val inCallServiceAvailable: Boolean get() = service != null || prefs.companionBoundAt != 0L

    val inCallServiceBound: Boolean get() = service != null

    // ---- InCallService source ----

    fun onServiceBound(s: PairlineInCallService) {
        val first = prefs.companionBoundAt == 0L
        service = s
        prefs.companionBoundAt = System.currentTimeMillis()
        Log.i(TAG, "companion call service bound")
        if (first) onCapabilityChange()
    }

    fun onServiceUnbound() {
        service = null
        Log.i(TAG, "companion call service unbound")
    }

    fun onCallAdded(call: Call) = executor.execute {
        val details = call.details
        val incoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            details.callDirection != Call.Details.DIRECTION_OUTGOING
        } else {
            // Android 9 has no call direction: a call that arrives ringing is incoming, one being dialed outgoing.
            @Suppress("DEPRECATION")
            call.state == Call.STATE_RINGING
        }
        val number = details.handle?.takeIf { it.scheme == "tel" }?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        val state = CallMapping.state(call.state) ?: CallMapping.DIALING
        // The telephony callback may have seen this call first; adopt it so the id stays the same.
        val adopted = calls.values.firstOrNull {
            it.telecom == null && it.incoming == incoming && it.state != CallMapping.ENDED &&
                (number == null || it.number == null || PhoneNumbers.sameNumber(it.number, number))
        }
        val t = adopted ?: newCall(incoming, number, sims.slotForAccount(details.accountHandle), state, null)
        if (t.number == null) t.number = number
        t.telecom = call
        val cb = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) = executor.execute { telecomStateChanged(t, call) }
            override fun onDetailsChanged(call: Call, details: Call.Details) = executor.execute {
                if (t.number == null) {
                    t.number = details.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
                }
            }
        }
        t.callback = cb
        // The callback needs a Looper thread; it hops to our executor itself.
        call.registerCallback(cb, mainHandler)
        if (adopted == null) {
            announce(t)
        } else {
            telecomStateChanged(t, call)
        }
    }

    fun onCallRemoved(call: Call) = executor.execute {
        val t = calls.values.firstOrNull { it.telecom == call } ?: return@execute
        t.callback?.let { runCatching { call.unregisterCallback(it) } }
        if (t.state != CallMapping.ENDED) finish(t, call.details?.disconnectCause?.code)
    }

    private fun telecomStateChanged(t: Tracked, call: Call) {
        if (!calls.containsKey(t.id)) return // already ended and reported
        val mapped = CallMapping.state(call.state) ?: return
        if (mapped == CallMapping.ENDED) {
            finish(t, call.details?.disconnectCause?.code)
            return
        }
        if (mapped == t.state) return
        // A call never goes back to ringing or dialing once it's connected. A companion service that binds late
        // can hand us a stale snapshot after the telephony path already saw the call answered.
        if (CallMapping.isBackwards(t.state, mapped)) return
        t.state = mapped
        t.since = System.currentTimeMillis()
        if (mapped == CallMapping.RINGING) t.wasRinging = true
        if (mapped == CallMapping.RINGING && t.incoming && !t.incomingSent) sendIncoming(t)
        sendState(t, null)
    }

    // ---- Telephony source ----

    /** A per-SIM call-state change from TelephonyCallback / PhoneStateListener. */
    fun onTelephonyState(subId: Int, state: Int, number: String?) = executor.execute {
        if (calls.values.any { it.telecom != null }) return@execute // the InCallService is in charge
        number?.let { lastIncomingNumber = it to System.currentTimeMillis() }
        val mine = calls.values.filter { it.telecom == null && it.subId == subId }
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val ringing = mine.firstOrNull { it.state == CallMapping.RINGING }
                if (ringing != null) {
                    if (ringing.number == null && number != null) ringing.number = number
                    return@execute
                }
                if (mine.any { it.state != CallMapping.ENDED }) return@execute // call waiting: not tracked here
                val t = newCall(true, number ?: recentIncomingNumber(), sims.slotFor(subId), CallMapping.RINGING, subId)
                if (t.number != null) {
                    announce(t)
                } else {
                    // Android 12+ shares the number through a separate broadcast; give it a moment.
                    executor.schedule({ if (!t.incomingSent && t.state == CallMapping.RINGING) announce(t) }, 700, TimeUnit.MILLISECONDS)
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                val ringing = mine.firstOrNull { it.state == CallMapping.RINGING }
                if (ringing != null) {
                    if (!ringing.incomingSent) announce(ringing)
                    ringing.state = CallMapping.ACTIVE
                    ringing.since = System.currentTimeMillis()
                    sendState(ringing, null)
                } else if (mine.none { it.state != CallMapping.ENDED }) {
                    val dial = lastDial?.takeIf { System.currentTimeMillis() - it.third < 30_000 }
                    lastDial = null // used once: a later call placed on the phone itself isn't the iPhone's
                    val t = newCall(false, dial?.first, sims.slotFor(subId), CallMapping.DIALING, subId)
                    announce(t)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                for (t in mine) {
                    if (t.state == CallMapping.ENDED) continue
                    if (t.incoming && t.state == CallMapping.RINGING && !t.weRejected) {
                        // Missed or declined on the phone? The call log tells us, once Android writes it.
                        t.state = CallMapping.ENDED
                        executor.schedule({ finishFromCallLog(t) }, 1500, TimeUnit.MILLISECONDS)
                    } else {
                        finish(t, null)
                    }
                }
            }
        }
    }

    /** The incoming number from the PHONE_STATE broadcast (Android 12+ telephony path). */
    fun onIncomingNumber(number: String) = executor.execute {
        lastIncomingNumber = number to System.currentTimeMillis()
        calls.values.firstOrNull { it.telecom == null && it.state == CallMapping.RINGING && it.number == null }
            ?.let { t ->
                t.number = number
                if (!t.incomingSent) announce(t)
            }
    }

    private fun recentIncomingNumber(): String? =
        lastIncomingNumber?.takeIf { System.currentTimeMillis() - it.second < 5_000 }?.first

    // ---- Events ----

    private fun newCall(incoming: Boolean, number: String?, sim: Int?, state: String, subId: Int?): Tracked {
        val t = Tracked("c$idPrefix-${nextId++}", incoming, number, null, sim, state, System.currentTimeMillis(), subId)
        calls[t.id] = t
        return t
    }

    private fun announce(t: Tracked) {
        t.name = t.name ?: lookupName(t.number)
        if (t.incoming && t.state == CallMapping.RINGING && !t.incomingSent) sendIncoming(t)
        sendState(t, null)
    }

    private fun sendIncoming(t: Tracked) {
        t.incomingSent = true
        if (t.name == null) t.name = lookupName(t.number)
        Log.i(TAG, "incoming call (${t.id})")
        emit(
            JSONObject()
                .put("t", Msg.CALL_INCOMING)
                .put("call", t.id)
                .putNullable("sim", t.sim)
                .putNullable("from", t.number)
                .putNullable("name", t.name),
        )
    }

    private fun sendState(t: Tracked, reason: String?) {
        Log.i(TAG, "call ${t.id}: ${t.state}${reason?.let { " ($it)" } ?: ""}")
        emit(stateJson(t, reason))
    }

    private fun stateJson(t: Tracked, reason: String?) = JSONObject()
        .put("t", Msg.CALL_STATE)
        .put("call", t.id)
        .put("state", t.state)
        .put("dir", if (t.incoming) "in" else "out")
        .putNullable("number", t.number)
        .putNullable("name", t.name)
        .putNullable("sim", t.sim)
        .put("since", t.since)
        .apply { if (reason != null) put("reason", reason) }

    private fun finish(t: Tracked, cause: Int?) {
        val reason = CallMapping.endReason(cause, t.wasRinging && t.state == CallMapping.RINGING, t.incoming, t.weRejected, t.weEnded)
        endWith(t, reason)
    }

    private fun finishFromCallLog(t: Tracked) {
        endWith(t, CallMapping.reasonFromCallLog(latestCallLogType(t.since)))
    }

    /** Reports the end once: onCallRemoved and the last state callback can both arrive for the same call. */
    private fun endWith(t: Tracked, reason: String) {
        if (calls.remove(t.id) == null) return
        t.state = CallMapping.ENDED
        t.since = System.currentTimeMillis()
        sendState(t, reason)
    }

    /** After the iPhone reconnects: the current state of every ongoing call, so it can catch up. */
    fun replay() = executor.execute {
        for (t in calls.values) {
            if (t.state == CallMapping.ENDED) continue
            if (t.incoming && t.state == CallMapping.RINGING) sendIncoming(t)
            emit(stateJson(t, null))
        }
    }

    // ---- Commands from the iPhone ----

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun answer(id: String) {
        val call = onCall(id) { t -> t.telecom }
        if (call != null) {
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
        } else {
            requirePermission(Manifest.permission.ANSWER_PHONE_CALLS)
            val tm = telecom()
            // Telephony reports ringing a moment before Telecom has finished screening the call, and answering in
            // that gap does nothing. Retry briefly until the call leaves the ringing state.
            for (attempt in 0 until 8) {
                tm.acceptRingingCall()
                Thread.sleep(400)
                val state = onCall(id) { it.state }
                if (state != CallMapping.RINGING) return
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun reject(id: String) {
        val call = onCall(id) { t ->
            t.weRejected = true
            t.telecom
        }
        if (call != null) {
            call.reject(false, null)
        } else {
            requirePermission(Manifest.permission.ANSWER_PHONE_CALLS)
            val tm = telecom()
            // endCall() returns false while Telecom is still screening the call; retry briefly (see answer()).
            for (attempt in 0 until 15) {
                if (tm.endCall()) return
                Thread.sleep(200)
            }
            throw RequestException(ErrorCode.FAILED, "Android refused to end the call")
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun end(id: String) = onCall(id) { t ->
        t.weEnded = true
        val call = t.telecom
        if (call != null) {
            call.disconnect()
        } else {
            requirePermission(Manifest.permission.ANSWER_PHONE_CALLS)
            if (!telecom().endCall()) throw RequestException(ErrorCode.FAILED, "Android refused to end the call")
        }
    }

    fun dtmf(id: String, digit: Char) = onCall(id) { t ->
        val call = t.telecom ?: throw RequestException(ErrorCode.UNSUPPORTED, "DTMF needs the companion call service")
        call.playDtmfTone(digit)
        executor.schedule({ runCatching { call.stopDtmfTone() } }, 250, TimeUnit.MILLISECONDS)
    }

    @Suppress("DEPRECATION")
    fun audio(id: String, mute: Boolean?, speaker: Boolean?) = onCall(id) { _ ->
        val s = service ?: throw RequestException(ErrorCode.UNSUPPORTED, "audio controls need the companion call service")
        mute?.let { s.setMuted(it) }
        speaker?.let { s.setAudioRoute(if (it) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE) }
    }

    @SuppressLint("MissingPermission")
    fun dial(sim: Int?, raw: String) {
        requirePermission(Manifest.permission.CALL_PHONE)
        val number = PhoneNumbers.normalizeDialable(raw) ?: throw RequestException(ErrorCode.INVALID, "not a number")
        val subId = sims.subIdFor(sim, SimDirectory.Purpose.VOICE)
        if (sim != null && subId == null) throw RequestException(ErrorCode.INVALID, "no SIM in that slot")
        val extras = Bundle()
        subId?.let { sims.phoneAccountFor(it) }?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        lastDial = Triple(number, sim, System.currentTimeMillis())
        try {
            telecom().placeCall(Uri.fromParts("tel", number, null), extras)
            Log.i(TAG, "outgoing call placed (SIM ${sim?.let { it + 1 } ?: "default"})")
        } catch (e: SecurityException) {
            throw RequestException(ErrorCode.PERMISSION, "CALL_PHONE not granted")
        }
    }

    private fun <R> onCall(id: String, block: (Tracked) -> R): R {
        val result = executor.submit {
            val t = calls[id] ?: throw RequestException(ErrorCode.INVALID, "no such call")
            block(t)
        }
        try {
            return result.get(5, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun requirePermission(p: String) {
        if (!Perms.granted(context, p)) throw RequestException(ErrorCode.PERMISSION, "${p.substringAfterLast('.')} not granted")
    }

    private fun telecom(): TelecomManager = context.getSystemService(TelecomManager::class.java)
        ?: throw RequestException(ErrorCode.UNSUPPORTED, "no telecom service")

    /** The caller's name, if the number is in the bridge phone's contacts (READ_CONTACTS). */
    fun lookupName(number: String?): String? {
        if (number.isNullOrBlank() || !Perms.granted(context, Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    private fun latestCallLogType(since: Long): Int? {
        if (!Perms.granted(context, Manifest.permission.READ_CALL_LOG)) return null
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.TYPE),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf((since - 10_000).toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "calls"
    }
}
