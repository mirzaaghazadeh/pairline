package app.pairline.android.link.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.SparseIntArray
import androidx.annotation.RequiresApi
import androidx.core.location.LocationManagerCompat
import androidx.core.util.isEmpty
import app.pairline.android.log.Log
import app.pairline.android.telephony.Perms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface HotspotState {
    data object Off : HotspotState
    data object Starting : HotspotState

    /** Running. [band] is "2.4", "5" or "6" when Android reports it (Android 16+), else null. */
    data class On(val ssid: String, val password: String, val band: String?) : HotspotState
    data class Failed(val reason: HotspotFailure) : HotspotState
}

enum class HotspotFailure {
    /** The Wi-Fi permission (Android 13+) or location permission (Android 9–12) isn't granted. */
    PERMISSION,

    /** Android 9–12 need Location turned on to start a hotspot. */
    LOCATION_OFF,

    /** Another hotspot or tethering is on, Wi-Fi is in a mode that can't host one, or the app wasn't in front. */
    INCOMPATIBLE,

    /** The phone's policy (for example a work profile) forbids hotspots. */
    DISALLOWED,

    /** No channel available, or any other reason Android gave. */
    GENERIC,
}

/**
 * The bridge's own hotspot for the iPhone (M5, best effort): Android's local-only hotspot, which gives the iPhone a
 * network with the bridge but no internet. Android picks a random name and passphrase each time; they reach the
 * iPhone in `wifi.offer` (and the pair link) only. Started and stopped by the user, from the app's own screen:
 * Android starts a local-only hotspot only for an app in the foreground.
 *
 * Permissions: `CHANGE_WIFI_STATE` (install time) plus `NEARBY_WIFI_DEVICES` on Android 13+ (declared
 * `neverForLocation`), or `ACCESS_FINE_LOCATION` with Location turned on, on Android 9–12. Band choice needs Android
 * 16 (`startLocalOnlyHotspotWithConfiguration`); older versions let Android pick.
 */
class BridgeHotspot(private val context: Context) {
    private val wifi: WifiManager? = context.applicationContext.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val listeners = mutableListOf<() -> Unit>()

    private val _state = MutableStateFlow<HotspotState>(HotspotState.Off)
    val state: StateFlow<HotspotState> = _state

    val bandChoiceSupported: Boolean get() = Build.VERSION.SDK_INT >= 36

    /** Called on the main thread after every state change. */
    fun addListener(listener: () -> Unit) {
        synchronized(listeners) { listeners += listener }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners -= listener }
    }

    /** The runtime permissions the hotspot needs on this Android version. */
    fun requiredPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        Build.VERSION.SDK_INT >= 31 ->
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Android 9–12 also need Location switched on (the user does that; we never change settings, rule 5). */
    fun needsLocationOn(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return false
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return !LocationManagerCompat.isLocationEnabled(lm)
    }

    /** Starts the hotspot. Call from the UI while the app is in the foreground. [band]: "auto", "2.4" or "5". */
    @SuppressLint("MissingPermission") // checked just below
    fun start(band: String) = main.post {
        val wm = wifi ?: return@post set(HotspotState.Failed(HotspotFailure.GENERIC))
        if (reservation != null || _state.value == HotspotState.Starting) return@post
        if (!Perms.allGranted(context, requiredPermissions())) return@post set(HotspotState.Failed(HotspotFailure.PERMISSION))
        if (needsLocationOn()) return@post set(HotspotState.Failed(HotspotFailure.LOCATION_OFF))
        set(HotspotState.Starting)
        try {
            if (Build.VERSION.SDK_INT >= 36 && band != "auto" && startWithBand(wm, band)) return@post
            wm.startLocalOnlyHotspot(callback, main)
        } catch (e: SecurityException) {
            Log.w(TAG, "hotspot refused", e)
            set(HotspotState.Failed(HotspotFailure.PERMISSION))
        } catch (e: Exception) {
            Log.w(TAG, "hotspot didn't start", e)
            set(HotspotState.Failed(HotspotFailure.GENERIC))
        }
    }

    fun stop() = main.post {
        reservation?.close()
        reservation = null
        if (_state.value != HotspotState.Off) {
            Log.i(TAG, "hotspot stopped")
            set(HotspotState.Off)
        }
    }

    @RequiresApi(36)
    @SuppressLint("MissingPermission")
    private fun startWithBand(wm: WifiManager, band: String): Boolean = try {
        val channels = SparseIntArray().apply {
            put(if (band == "5") SoftApConfiguration.BAND_5GHZ else SoftApConfiguration.BAND_2GHZ, 0)
        }
        val config = SoftApConfiguration.Builder().setChannels(channels).build()
        wm.startLocalOnlyHotspotWithConfiguration(config, { main.post(it) }, callback)
        true
    } catch (e: Exception) {
        // Unsupported band on this phone, or a stricter permission check: let Android pick instead.
        Log.w(TAG, "hotspot band $band not available; letting Android choose", e)
        false
    }

    private val callback = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
            reservation = r
            val on = describe(r)
            if (on == null) {
                Log.w(TAG, "hotspot started without a readable configuration")
                r.close()
                reservation = null
                set(HotspotState.Failed(HotspotFailure.GENERIC))
                return
            }
            Log.i(TAG, "hotspot started (band ${on.band ?: "chosen by Android"})")
            set(on)
        }

        override fun onStopped() {
            reservation = null
            Log.i(TAG, "hotspot stopped by Android")
            set(HotspotState.Off)
        }

        override fun onFailed(reason: Int) {
            reservation = null
            Log.w(TAG, "hotspot failed (reason $reason)")
            set(
                HotspotState.Failed(
                    when (reason) {
                        ERROR_INCOMPATIBLE_MODE -> HotspotFailure.INCOMPATIBLE
                        ERROR_TETHERING_DISALLOWED -> HotspotFailure.DISALLOWED
                        else -> HotspotFailure.GENERIC
                    },
                ),
            )
        }
    }

    private fun describe(r: WifiManager.LocalOnlyHotspotReservation): HotspotState.On? {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= 33) {
                c.wifiSsid?.toString()?.removeSurrounding("\"")
            } else {
                @Suppress("DEPRECATION")
                c.ssid
            }
            val password = c.passphrase
            if (ssid.isNullOrEmpty() || password.isNullOrEmpty()) return null
            return HotspotState.On(ssid, password, if (Build.VERSION.SDK_INT >= 36) bandOf(c) else null)
        }
        @Suppress("DEPRECATION")
        val w = r.wifiConfiguration ?: return null
        @Suppress("DEPRECATION")
        val ssid = w.SSID?.removeSurrounding("\"")
        @Suppress("DEPRECATION")
        val password = w.preSharedKey?.removeSurrounding("\"")
        if (ssid.isNullOrEmpty() || password.isNullOrEmpty()) return null
        return HotspotState.On(ssid, password, null)
    }

    @RequiresApi(36)
    private fun bandOf(c: SoftApConfiguration): String? {
        val channels = c.channels
        if (channels.isEmpty()) return null
        return when (channels.keyAt(0)) {
            SoftApConfiguration.BAND_2GHZ -> "2.4"
            SoftApConfiguration.BAND_5GHZ -> "5"
            SoftApConfiguration.BAND_6GHZ -> "6"
            else -> null
        }
    }

    private fun set(state: HotspotState) {
        _state.value = state
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { runCatching { it() } }
    }

    private companion object {
        const val TAG = "hotspot"
    }
}
