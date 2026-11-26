package app.pairline.android.netshare

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.pairline.android.link.wifi.BridgeHotspot
import app.pairline.android.link.wifi.HotspotState
import app.pairline.android.link.wifi.PhoneHotspot
import app.pairline.android.log.Log
import app.pairline.android.privileged.PrivilegedManager
import app.pairline.android.privileged.PrivilegedService
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.RequestException
import app.pairline.android.telephony.SimDirectory
import org.json.JSONObject

/** `status.net` (PROTOCOL.md): the Android phone's own internet sharing as the iPhone sees it (D-073). */
data class NetState(val hotspot: Boolean, val bluetooth: Boolean, val data: Boolean?) {
    fun toJson(): JSONObject = JSONObject()
        .put("hotspot", hotspot)
        .put("bluetooth", bluetooth)
        .put("data", data ?: JSONObject.NULL)
}

/** What a `net.switch` request asks for (PROTOCOL.md), checked before anything is switched. */
sealed interface NetSwitchRequest {
    /** [type] is PrivilegedService.TETHER_WIFI (the phone's hotspot) or TETHER_BLUETOOTH. */
    data class Tethering(val type: Int, val on: Boolean) : NetSwitchRequest
    data class Data(val on: Boolean) : NetSwitchRequest
    data class DataSim(val slot: Int) : NetSwitchRequest

    companion object {
        fun parse(what: String?, on: Boolean?, sim: Int?): NetSwitchRequest = when (what) {
            "hotspot" -> Tethering(PrivilegedService.TETHER_WIFI, on ?: invalid("on missing"))
            "bluetooth" -> Tethering(PrivilegedService.TETHER_BLUETOOTH, on ?: invalid("on missing"))
            "data" -> Data(on ?: invalid("on missing"))
            "dataSim" -> DataSim(sim ?: invalid("sim missing"))
            null -> invalid("what missing")
            else -> invalid("unknown switch")
        }

        /** Android's tethering error ([PrivilegedService.setTethering]) as the iPhone gets it. */
        fun tetheringError(code: Int): RequestException = when (code) {
            // The carrier's entitlement check said no, or couldn't be done.
            PROVISIONING_FAILED, ENTITLEMENT_UNKNOWN -> RequestException(ErrorCode.PERMISSION, "carrier refused tethering ($code)")
            NO_CHANGE_PERMISSION, NO_ACCESS_PERMISSION -> RequestException(ErrorCode.PERMISSION, "Android refused tethering ($code)")
            UNSUPPORTED, UNKNOWN_TYPE -> RequestException(ErrorCode.UNSUPPORTED, "no such tethering on this phone ($code)")
            SERVICE_UNAVAILABLE -> RequestException(ErrorCode.FAILED, "tethering unavailable ($code), is Bluetooth on?")
            PrivilegedService.TETHER_NO_ANSWER -> RequestException(ErrorCode.FAILED, "Android didn't answer")
            else -> RequestException(ErrorCode.FAILED, "tethering error $code")
        }

        private fun invalid(msg: String): Nothing = throw RequestException(ErrorCode.INVALID, msg)

        // TetheringManager.TETHER_ERROR_* (system API constants, stable since Android 11).
        private const val SERVICE_UNAVAILABLE = 2
        private const val UNSUPPORTED = 3
        private const val PROVISIONING_FAILED = 11
        private const val ENTITLEMENT_UNKNOWN = 13
        private const val NO_CHANGE_PERMISSION = 14
        private const val NO_ACCESS_PERMISSION = 15
        private const val UNKNOWN_TYPE = 16
    }
}

/**
 * The Android phone's own internet sharing as the iPhone sees and switches it (D-073): its hotspot, Bluetooth
 * tethering, mobile data and data SIM. Reading needs no privilege and stays on this phone. Switching goes through Talk
 * on iPhone's helper, and only when the user flips the switch on the iPhone (AGENTS.md rules 5 and 7).
 */
class NetSwitches(
    private val context: Context,
    private val privileged: PrivilegedManager,
    private val sims: SimDirectory,
    private val pairlineHotspot: BridgeHotspot,
) {
    /** Called (on any thread) when something [state] reports may have changed. */
    var onChange: (() -> Unit)? = null

    /**
     * Android 11 and 12 list Bluetooth tethering only once a device has joined: until then, on if Pairline turned it
     * on (and Bluetooth stayed on since).
     */
    @Volatile
    private var bluetoothTurnedOn = false
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON
            ) {
                bluetoothTurnedOn = false
            }
            onChange?.invoke()
        }
    }

    fun start() {
        if (started) return
        started = true
        val filter = PhoneHotspot.filter().apply { addAction(BluetoothAdapter.ACTION_STATE_CHANGED) }
        // Protected system broadcasts; see WifiTransport for why the receiver is registered exported.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** The iPhone may switch: Android 11 or later, and Talk on iPhone's helper is running. */
    val canSwitch: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && privileged.state.value == PrivilegedManager.State.READY

    fun state(): NetState {
        val phone = PhoneHotspot.read(context, pairlineHotspot.state.value is HotspotState.On)
        val bluetooth = phone.bluetooth || (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && bluetoothTurnedOn)
        return NetState(hotspot = phone.on, bluetooth = bluetooth, data = mobileData())
    }

    /** Mobile data on the data SIM, or null if Android won't say. */
    @SuppressLint("MissingPermission") // ACCESS_NETWORK_STATE is enough for isDataEnabled
    private fun mobileData(): Boolean? = runCatching {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        val sub = SubscriptionManager.getDefaultDataSubscriptionId()
        (if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) tm.createForSubscriptionId(sub) else tm).isDataEnabled
    }.getOrNull()

    /** A `net.switch` request (PROTOCOL.md); throws [RequestException] with what went wrong. Off the main thread. */
    fun set(request: NetSwitchRequest) {
        if (!canSwitch) throw RequestException(ErrorCode.UNSUPPORTED, "the helper isn't running")
        try {
            when (request) {
                is NetSwitchRequest.Tethering -> tethering(request)
                is NetSwitchRequest.Data -> data(request.on)
                is NetSwitchRequest.DataSim -> dataSim(request.slot)
            }
        } finally {
            onChange?.invoke()
        }
    }

    private fun tethering(request: NetSwitchRequest.Tethering) {
        val name = if (request.type == PrivilegedService.TETHER_WIFI) "hotspot" else "Bluetooth tethering"
        if (!request.on) {
            // The iPhone may be on that very hotspot: answer first, then stop it.
            Thread({
                Thread.sleep(STOP_DELAY_MS)
                runCatching { privileged.setTethering(request.type, false) }
                    .onFailure { Log.w(TAG, "couldn't stop the $name", it) }
                if (request.type == PrivilegedService.TETHER_BLUETOOTH) bluetoothTurnedOn = false
                Log.i(TAG, "$name stopped from the iPhone")
                onChange?.invoke()
            }, "pairline-net-switch").start()
            return
        }
        // Most phones run one access point at a time: Pairline's own hotspot gives way to the phone's.
        if (request.type == PrivilegedService.TETHER_WIFI && pairlineHotspot.state.value !is HotspotState.Off) {
            pairlineHotspot.stop()
            waitFor { pairlineHotspot.state.value is HotspotState.Off }
        }
        val code = helper { privileged.setTethering(request.type, true) }
        if (code != 0) throw NetSwitchRequest.tetheringError(code)
        if (request.type == PrivilegedService.TETHER_BLUETOOTH) bluetoothTurnedOn = true
        Log.i(TAG, "$name started from the iPhone")
    }

    private fun data(on: Boolean) {
        helper { privileged.setMobileData(on) }
        if (!waitFor { mobileData() == on }) throw RequestException(ErrorCode.FAILED, "mobile data didn't change")
        Log.i(TAG, "mobile data turned ${if (on) "on" else "off"} from the iPhone")
    }

    private fun dataSim(slot: Int) {
        val sub = sims.activeSubscriptions().firstOrNull { it.simSlotIndex == slot }?.subscriptionId
            ?: throw RequestException(ErrorCode.INVALID, "no SIM in that slot")
        helper { privileged.setDataSubscription(sub) }
        if (!waitFor { SubscriptionManager.getDefaultDataSubscriptionId() == sub }) {
            throw RequestException(ErrorCode.FAILED, "the data SIM didn't change")
        }
        Log.i(TAG, "data SIM set to SIM ${slot + 1} from the iPhone")
    }

    /** Runs a helper call: null (no helper) and its errors become the iPhone's error. */
    private fun <T> helper(call: () -> T?): T {
        val result = try {
            call()
        } catch (e: RequestException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the helper couldn't switch", e)
            throw RequestException(ErrorCode.FAILED, e.message ?: "the helper failed")
        }
        return result ?: throw RequestException(ErrorCode.UNSUPPORTED, "the helper isn't running")
    }

    /** Polls [done] for a few seconds (Android applies these switches asynchronously). */
    private fun waitFor(done: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + SETTLE_MS
        while (true) {
            if (runCatching(done).getOrDefault(false)) return true
            if (System.currentTimeMillis() > until) return false
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val TAG = "net-switch"
        const val STOP_DELAY_MS = 400L
        const val SETTLE_MS = 4_000L
        const val POLL_MS = 200L
    }
}
