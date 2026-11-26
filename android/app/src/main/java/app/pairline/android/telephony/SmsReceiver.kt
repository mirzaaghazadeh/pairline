package app.pairline.android.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import app.pairline.android.AppGraph
import app.pairline.android.log.Log
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.putNullable
import org.json.JSONObject

/**
 * Relays each incoming SMS to the iPhone as `sms.new` (RECEIVE_SMS), unless the sender is blocked (D-043). Pairline
 * isn't the default SMS app, so Android's own messaging app still stores and shows the message as usual.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val subId = intent.getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            intent.getIntExtra("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID),
        )
        val receivedAt = System.currentTimeMillis()
        // One broadcast can hold a multipart message (and rarely, several senders).
        val bySender = parts.groupBy { it.displayOriginatingAddress ?: it.originatingAddress.orEmpty() }
        val pending = goAsync()
        AppGraph.io.execute {
            try {
                for ((from, msgs) in bySender) {
                    val body = msgs.joinToString("") { it.displayMessageBody ?: it.messageBody.orEmpty() }
                    relay(from, body, subId, receivedAt)
                }
            } catch (e: Exception) {
                Log.e(TAG, "couldn't relay an SMS", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun relay(from: String, body: String, subId: Int, receivedAt: Long) {
        if (AppGraph.blockList.isBlocked(from)) {
            Log.i(TAG, "SMS from a blocked number; not relayed")
            return
        }
        Log.i(TAG, "SMS received")
        if (!AppGraph.link.canSendEvents) {
            Log.i(TAG, "iPhone not connected; it will get the message from history when it reconnects")
            return
        }
        val store = AppGraph.smsStore
        val slot = AppGraph.sims.slotFor(subId)
        // Wait briefly for the default SMS app to store it, so the key matches what history returns later.
        var row: SmsRow? = null
        val deadline = System.currentTimeMillis() + LOOKUP_WINDOW_MS
        while (row == null && System.currentTimeMillis() < deadline) {
            row = store.findIncoming(from, body, receivedAt - 60_000)
            if (row == null) Thread.sleep(250)
        }
        val msg = row?.toJson(slot ?: store.slotMap()[row.subId]) ?: JSONObject()
            .put("key", "rx:$receivedAt")
            .put("thread", JSONObject.NULL)
            .put("addr", from)
            .put("body", body)
            .put("time", receivedAt)
            .put("box", "in")
            .putNullable("sim", slot)
            .put("state", "received")
        AppGraph.link.send(JSONObject().put("t", Msg.SMS_NEW).put("msg", msg))
    }

    private companion object {
        const val TAG = "sms"
        const val LOOKUP_WINDOW_MS = 3_000L
    }
}
