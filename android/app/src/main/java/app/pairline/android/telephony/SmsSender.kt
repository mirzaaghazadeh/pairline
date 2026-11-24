package app.pairline.android.telephony

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import androidx.core.net.toUri
import app.pairline.android.AppGraph
import app.pairline.android.log.Log
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.RequestException
import app.pairline.android.protocol.putNullable
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Sends SMS for the iPhone (`sms.send`): picks the SIM, splits long texts into a multipart SMS, and reports
 * `sms.state` events as Android confirms sending and delivery.
 */
class SmsSender(
    private val context: Context,
    private val sims: SimDirectory,
    private val store: SmsStore,
    private val emit: (JSONObject) -> Unit,
) {
    private class Pending(val cid: String, val to: String, val body: String, val parts: Int, val startedAt: Long) {
        var sent = 0
        var delivered = 0
        var failed = false
        var key: String? = null
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Sent and delivered reports are handled one at a time, in the order Android delivers them. */
    val results: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "pairline-sms-results") }

    /** Forgets messages whose delivery report never came, so their text doesn't stay in memory. */
    private fun prune() {
        val cutoff = System.currentTimeMillis() - PENDING_TTL_MS
        pending.values.removeIf { it.startedAt < cutoff }
    }

    /** Hands the message to the radio queue; throws [RequestException] if it can't. */
    fun send(sim: Int?, rawTo: String, body: String, cid: String) {
        prune()
        if (!Perms.granted(context, Manifest.permission.SEND_SMS)) {
            throw RequestException(ErrorCode.PERMISSION, "SEND_SMS not granted")
        }
        val to = PhoneNumbers.normalizeSmsRecipient(rawTo)
            ?: throw RequestException(ErrorCode.INVALID, "to is not a phone number")
        if (body.isEmpty()) throw RequestException(ErrorCode.INVALID, "empty body")
        if (body.length > MAX_BODY) throw RequestException(ErrorCode.INVALID, "body too long")
        if (cid.isEmpty() || cid.length > 128) throw RequestException(ErrorCode.INVALID, "bad cid")
        if (pending.containsKey(cid)) throw RequestException(ErrorCode.BUSY, "cid already in use")

        val subId = sims.subIdFor(sim, SimDirectory.Purpose.SMS)
        if (sim != null && subId == null) throw RequestException(ErrorCode.INVALID, "no SIM in that slot")
        val manager = smsManager(subId)
        val parts = manager.divideMessage(body)
        val p = Pending(cid, to, body, parts.size, System.currentTimeMillis())
        pending[cid] = p
        val sentIntents = ArrayList<PendingIntent>()
        val deliveryIntents = ArrayList<PendingIntent>()
        for (i in parts.indices) {
            sentIntents += resultIntent(ACTION_SENT, cid, i)
            deliveryIntents += resultIntent(ACTION_DELIVERED, cid, i)
        }
        try {
            if (parts.size == 1) {
                manager.sendTextMessage(to, null, body, sentIntents[0], deliveryIntents[0])
            } else {
                manager.sendMultipartTextMessage(to, null, parts, sentIntents, deliveryIntents)
            }
            Log.i(TAG, "SMS queued (${parts.size} part(s), SIM ${sim?.let { it + 1 } ?: "default"})")
        } catch (e: Exception) {
            pending.remove(cid)
            Log.w(TAG, "SMS send failed to start", e)
            throw RequestException(ErrorCode.FAILED, "the SMS couldn't be queued")
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(subId: Int?): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subId != null) base.createForSubscriptionId(subId) else base
        } else {
            if (subId != null) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    private fun resultIntent(action: String, cid: String, part: Int): PendingIntent {
        val intent = Intent(context, SmsResultReceiver::class.java)
            .setAction(action)
            // A unique data URI keeps each part's PendingIntent distinct.
            .setData("pairline-sms://$action/${Uri.encode(cid)}/$part".toUri())
            .putExtra(EXTRA_CID, cid)
            .putExtra(EXTRA_PART, part)
        // Mutable so Android can attach the delivery report PDU; the intent is explicit, so nothing else gets it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, cid.hashCode() * 31 + part, intent, flags)
    }

    internal fun onSent(cid: String, resultCode: Int) {
        val p = pending[cid] ?: return
        val ok = resultCode == Activity.RESULT_OK
        synchronized(p) {
            if (p.failed) return
            if (!ok) {
                p.failed = true
                pending.remove(cid)
                Log.w(TAG, "SMS failed (code $resultCode)")
                emit(state(cid, "failed", null, sendError(resultCode)))
                return
            }
            p.sent++
            if (p.sent < p.parts) return
        }
        Log.i(TAG, "SMS sent")
        // Android stores the sent message itself; look up its key so the iPhone can match history.
        p.key = lookupKey(p)
        emit(state(cid, "sent", p.key, null))
    }

    internal fun onDelivered(cid: String, intent: Intent) {
        val p = pending[cid] ?: return
        val status = deliveryStatus(intent)
        synchronized(p) {
            if (p.failed) return
            when {
                status == null || status < 0x20 -> p.delivered++
                status >= 0x40 -> {
                    p.failed = true
                    pending.remove(cid)
                    emit(state(cid, "failed", p.key, "not delivered"))
                    return
                }
                else -> return // still pending at the carrier; another report follows
            }
            if (p.delivered < p.parts) return
            pending.remove(cid)
        }
        Log.i(TAG, "SMS delivered")
        emit(state(cid, "delivered", p.key ?: lookupKey(p), null))
    }

    private fun lookupKey(p: Pending): String? =
        runCatching { store.findSent(p.to, p.body, p.startedAt - 5_000)?.let { SmsMapping.key(it.id) } }.getOrNull()

    private fun deliveryStatus(intent: Intent): Int? {
        val pdu = intent.getByteArrayExtra("pdu") ?: return null
        val format = intent.getStringExtra("format") ?: SmsMessage.FORMAT_3GPP
        return runCatching { SmsMessage.createFromPdu(pdu, format)?.status }.getOrNull()
    }

    private fun state(cid: String, state: String, key: String?, error: String?): JSONObject = JSONObject()
        .put("t", Msg.SMS_STATE)
        .put("cid", cid)
        .put("state", state)
        .putNullable("key", key)
        .putNullable("error", error)

    private fun sendError(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
        SmsManager.RESULT_ERROR_NULL_PDU -> "invalid message"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "sending limit reached"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED ->
            "short code not allowed"
        else -> "failed ($code)"
    }

    companion object {
        private const val TAG = "sms"
        private const val MAX_BODY = 10_000
        private const val PENDING_TTL_MS = 6 * 3600 * 1000L
        const val ACTION_SENT = "app.pairline.android.SMS_SENT"
        const val ACTION_DELIVERED = "app.pairline.android.SMS_DELIVERED"
        const val EXTRA_CID = "cid"
        const val EXTRA_PART = "part"
    }
}

/** Receives Android's sent and delivered callbacks for messages the iPhone sent. Not exported. */
class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cid = intent.getStringExtra(SmsSender.EXTRA_CID) ?: return
        val code = resultCode
        val sender = AppGraph.smsSender
        val pending = goAsync()
        sender.results.execute {
            try {
                when (intent.action) {
                    SmsSender.ACTION_SENT -> sender.onSent(cid, code)
                    SmsSender.ACTION_DELIVERED -> sender.onDelivered(cid, intent)
                }
            } catch (e: Exception) {
                Log.e("sms", "couldn't handle an SMS report", e)
            } finally {
                pending.finish()
            }
        }
    }
}
