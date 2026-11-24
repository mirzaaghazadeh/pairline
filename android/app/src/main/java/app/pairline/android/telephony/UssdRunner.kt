package app.pairline.android.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.telephony.TelephonyManager
import app.pairline.android.log.Log
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.RequestException
import app.pairline.android.protocol.putNullable
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Runs USSD codes (`ussd.run`) on a chosen SIM with TelephonyManager.sendUssdRequest (CALL_PHONE). */
class UssdRunner(private val context: Context, private val sims: SimDirectory) {
    private val thread = HandlerThread("pairline-ussd").apply { start() }
    private val handler = Handler(thread.looper)

    /** Starts the request; [emit] gets the `ussd.result` (with the request's id) when the network answers. */
    @SuppressLint("MissingPermission")
    fun run(id: Long, sim: Int?, rawCode: String, emit: (JSONObject) -> Unit) {
        if (!Perms.granted(context, Manifest.permission.CALL_PHONE)) {
            throw RequestException(ErrorCode.PERMISSION, "CALL_PHONE not granted")
        }
        val code = PhoneNumbers.normalizeUssd(rawCode) ?: throw RequestException(ErrorCode.INVALID, "not a USSD code")
        val subId = sims.subIdFor(sim, SimDirectory.Purpose.VOICE)
        if (sim != null && subId == null) throw RequestException(ErrorCode.INVALID, "no SIM in that slot")
        val base = context.getSystemService(TelephonyManager::class.java)
            ?: throw RequestException(ErrorCode.UNSUPPORTED, "no telephony")
        val tm = subId?.let { base.createForSubscriptionId(it) } ?: base
        val done = AtomicBoolean(false)
        fun reply(ok: Boolean, text: String?, error: String?) {
            if (!done.compareAndSet(false, true)) return
            Log.i(TAG, "USSD ${if (ok) "answered" else "failed"}")
            emit(
                JSONObject()
                    .put("t", Msg.USSD_RESULT)
                    .put("id", id)
                    .put("ok", ok)
                    .putNullable("text", text)
                    .putNullable("error", error),
            )
        }
        Log.i(TAG, "running a USSD code (SIM ${sim?.let { it + 1 } ?: "default"})")
        try {
            tm.sendUssdRequest(
                code,
                object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(telephonyManager: TelephonyManager, request: String, response: CharSequence) {
                        reply(true, response.toString(), null)
                    }

                    override fun onReceiveUssdResponseFailed(telephonyManager: TelephonyManager, request: String, failureCode: Int) {
                        reply(false, null, if (failureCode == TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL) "service unavailable" else "failed")
                    }
                },
                handler,
            )
        } catch (e: SecurityException) {
            throw RequestException(ErrorCode.PERMISSION, "CALL_PHONE not granted")
        }
        handler.postDelayed({ reply(false, null, "timeout") }, TIMEOUT_MS)
    }

    private companion object {
        const val TAG = "ussd"
        const val TIMEOUT_MS = 55_000L
    }
}
