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
import android.os.Handler
import android.os.HandlerThread
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
    /** Call audio relay readiness: (listen, talk). D-047. */
    private val mediaCaps: () -> Pair<Boolean, Boolean> = { false to false },
) {
    /** Called (on a background thread) whenever something in the status may have changed. */
    var onChange: (() -> Unit)? = null

    /** Per-SIM call-state changes: subscription id, TelephonyManager.CALL_STATE_*, number if Android shares it. */
    var onCallState: ((subId: Int, state: Int, number: String?) -> Unit)? = null

    /**
     * One thread for radio callbacks and refreshes. It has a Looper because on Android 9 and 10 the
     * PhoneStateListener and OnSubscriptionsChangedListener deliver to the looper of the thread that creates them.
     */
    private val radioThread = HandlerThread("pairline-radio").apply { start() }
    private val radioHandler = Handler(radioThread.looper)

    /** Runs radio callbacks and refreshes; an unexpected error is logged instead of killing the thread. */
    private val executor: Executor = Executor { task ->
        radioHandler.post {
            try {
                task.run()
            } catch (e: Exception) {
                Log.e(TAG, "radio task failed", e)
            }
        }
    }
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val subscriptions = context.getSystemService(SubscriptionManager::class.java)
    private val registered = HashMap<Int, Any>()
    private var started = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = changed()
    }

    /** Created on the radio thread (see [radioThread]); touched only there. */
    private var subscriptionListener: SubscriptionManager.OnSubscriptionsChangedListener? = null

    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(
            context,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        executor.execute {
            val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() {
                    executor.execute { refreshRadioListeners() }
                    changed()
                }
            }
            subscriptionListener = listener
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    subscriptions?.addOnSubscriptionsChangedListener(executor, listener)
                } else {
                    // Android 9 and 10: delivered on this (radio) thread's looper.
                    @Suppress("DEPRECATION")
                    subscriptions?.addOnSubscriptionsChangedListener(listener)
                }
            } catch (e: Exception) {
                Log.w(TAG, "can't watch SIM changes", e)
            }
            refreshRadioListeners()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(batteryReceiver) }
        executor.execute {
            subscriptionListener?.let { listener -> runCatching { subscriptions?.removeOnSubscriptionsChangedListener(listener) } }
            subscriptionListener = null
            unregisterAll()
        }
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
        val (mediaListen, mediaTalk) = mediaCaps()
        return CapsCalculator.compute(
            CapsInput(
                granted = Perms.grantedSet(context),
                hasMessaging = messaging,
                hasCalling = calling,
                activeSims = sims.activeCount(),
                inCallService = inCallServiceAvailable(),
                contactsAddEnabled = prefs.contactsAddEnabled,
                mediaListen = mediaListen,
                mediaTalk = mediaTalk,
                netShare = prefs.netShareAllowed,
                wifiLink = prefs.wifiLinkEnabled,
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

    /**
     * Android 9–11. Runs on the radio thread, so the listener (created with the no-argument constructor) delivers
     * on that thread's looper on every version, including Android 9, which has no Executor constructor.
     */
    @Suppress("DEPRECATION")
    private fun legacyListener(subId: Int, tm: TelephonyManager): PhoneStateListener {
        val listener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) = changed()
            override fun onServiceStateChanged(serviceState: ServiceState?) {
                serviceState?.let { sims.serviceStates[subId] = it.state }
                changed()
            }
            // Only called on Android 11 (5G NSA shows as an override of the LTE network type).
            @RequiresApi(Build.VERSION_CODES.R)
            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                sims.displayOverrides[subId] = telephonyDisplayInfo.overrideNetworkType
                changed()
            }
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                onCallState?.invoke(subId, state, phoneNumber?.takeIf { it.isNotBlank() })
            }
        }
        var events = PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_SERVICE_STATE or
            PhoneStateListener.LISTEN_CALL_STATE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) events = events or PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED
        tm.listen(listener, events)
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
