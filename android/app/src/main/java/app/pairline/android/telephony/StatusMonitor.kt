package app.pairline.android.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.pairline.android.AppPrefs
import app.pairline.android.log.Log
import app.pairline.android.protocol.Msg
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

data class BatteryInfo(val level: Int, val charging: Boolean)

/**
 * Builds the `status` message and notices when it changes: battery, SIMs, signal, network type, roaming and
 * capabilities. Also forwards per-SIM call-state changes to the [CallTracker].
 */
class StatusMonitor(
    private val context: Context,
    private val sims: SimDirectory,
    private val prefs: AppPrefs,
    private val appVersion: String,
    private val inCallServiceAvailable: () -> Boolean,
) {
    /** Called (on a background thread) whenever something in the status may have changed. */
    var onChange: (() -> Unit)? = null

    /** Per-SIM call-state changes: subscription id, TelephonyManager.CALL_STATE_*, number if Android shares it. */
    var onCallState: ((subId: Int, state: Int, number: String?) -> Unit)? = null

    private val executor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pairline-radio") }
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val subscriptions = context.getSystemService(SubscriptionManager::class.java)
    private val registered = HashMap<Int, Any>()
    private var started = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = changed()
    }

    private val subscriptionListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            executor.execute { refreshRadioListeners() }
            changed()
        }
    }

    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(
            context,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            subscriptions?.addOnSubscriptionsChangedListener(executor, subscriptionListener)
        } catch (e: Exception) {
            Log.w(TAG, "can't watch SIM changes", e)
        }
        executor.execute { refreshRadioListeners() }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { subscriptions?.removeOnSubscriptionsChangedListener(subscriptionListener) }
        executor.execute { unregisterAll() }
    }

    /** Re-reads permissions (after the user grants one) and re-registers the radio listeners. */
    fun refresh() {
        executor.execute { refreshRadioListeners() }
        changed()
    }

    fun battery(): BatteryInfo {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryInfo(pct, charging)
    }

    fun caps(): List<String> {
        val pm = context.packageManager
        val hasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val messaging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
        } else {
            hasTelephony
        }
        val calling = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING)
        } else {
            hasTelephony
        }
        return CapsCalculator.compute(
            CapsInput(
                granted = Perms.grantedSet(context),
                hasMessaging = messaging,
                hasCalling = calling,
                activeSims = sims.activeCount(),
                inCallService = inCallServiceAvailable(),
                contactsAddEnabled = prefs.contactsAddEnabled,
            ),
        )
    }

    /** The `status` message; [id] is set when it answers `status.get`. */
    fun build(id: Long? = null): JSONObject {
        val b = battery()
        val json = JSONObject().put("t", Msg.STATUS)
        if (id != null) json.put("id", id)
        return json
            .put("battery", JSONObject().put("level", b.level).put("charging", b.charging))
            .put("sims", sims.simsJson())
            .put("app", appVersion)
            .put("caps", JSONArray(caps()))
            .put("time", System.currentTimeMillis())
    }

    private fun changed() {
        onChange?.invoke()
    }

    @SuppressLint("MissingPermission")
    private fun refreshRadioListeners() {
        unregisterAll()
        if (!started || telephony == null) return
        if (!Perms.granted(context, Manifest.permission.READ_PHONE_STATE)) return
        for (info in sims.activeSubscriptions()) {
            val subId = info.subscriptionId
            val tm = telephony.createForSubscriptionId(subId)
            try {
                registered[subId] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    RadioCallback(subId).also { tm.registerTelephonyCallback(executor, it) }
                } else {
                    legacyListener(subId, tm)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "can't listen to SIM ${info.simSlotIndex + 1}", e)
            }
        }
        changed()
    }

    @Suppress("DEPRECATION")
    private fun legacyListener(subId: Int, tm: TelephonyManager): PhoneStateListener {
        val listener = object : PhoneStateListener(executor) {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) = changed()
            override fun onServiceStateChanged(serviceState: ServiceState?) {
                serviceState?.let { sims.serviceStates[subId] = it.state }
                changed()
            }
            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                sims.displayOverrides[subId] = telephonyDisplayInfo.overrideNetworkType
                changed()
            }
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                onCallState?.invoke(subId, state, phoneNumber?.takeIf { it.isNotBlank() })
            }
        }
        tm.listen(
            listener,
            PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_SERVICE_STATE or
                PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED or PhoneStateListener.LISTEN_CALL_STATE,
        )
        return listener
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private inner class RadioCallback(private val subId: Int) :
        TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.DisplayInfoListener,
        TelephonyCallback.CallStateListener {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = changed()
        override fun onServiceStateChanged(serviceState: ServiceState) {
            sims.serviceStates[subId] = serviceState.state
            changed()
        }
        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            sims.displayOverrides[subId] = telephonyDisplayInfo.overrideNetworkType
            changed()
        }
        override fun onCallStateChanged(state: Int) {
            onCallState?.invoke(subId, state, null)
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterAll() {
        for ((subId, listener) in registered) {
            val tm = telephony?.createForSubscriptionId(subId) ?: continue
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && listener is TelephonyCallback) {
                    tm.unregisterTelephonyCallback(listener)
                } else if (listener is PhoneStateListener) {
                    tm.listen(listener, PhoneStateListener.LISTEN_NONE)
                }
            }
        }
        registered.clear()
    }

    private companion object {
        const val TAG = "status"
    }
}
