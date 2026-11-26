package app.pairline.android.link.wifi

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android's own hotspot (Settings › Hotspot & tethering) as Pairline sees it without any privilege: whether it's on,
 * from Android's sticky tethering broadcast, and whether this phone has an internet connection it can share. Read
 * locally, nothing is looked up. Pairline turns it on or off only when the user flips the switch for it on the
 * iPhone (AGENTS.md rule 5, D-061, D-073).
 */
object PhoneHotspot {
    data class Info(
        /** Android's own Wi-Fi hotspot is on (not Pairline's local-only one). */
        val on: Boolean,
        /** This phone has a mobile-data or Wi-Fi connection to share (not counting a VPN). */
        val upstream: Boolean,
        /** A VPN is on. Android sends hotspot traffic around it, never through it. */
        val vpn: Boolean,
        /** This phone is itself on a Wi-Fi network (Wireless debugging works only then). */
        val onWifi: Boolean,
        /**
         * Bluetooth tethering is sharing: Android 13 and later list it as soon as it's on, Android 11 and 12 only
         * once a device has joined (D-073).
         */
        val bluetooth: Boolean = false,
    )

    // Android's own broadcasts (not in the public SDK, sent since Android 4, sticky). Their extras list the interfaces.
    const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
    const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val EXTRA_ACTIVE_TETHER = "tetherArray"
    private const val EXTRA_WIFI_AP_STATE = "wifi_state"
    private const val WIFI_AP_STATE_ENABLED = 13

    /** Tethering over USB, Bluetooth or Ethernet, which isn't a hotspot the iPhone can join. */
    private val NOT_WIFI = Regex("^(rndis|ncm|usb|bt-pan|bt|eth|rmnet).*")

    /** Android's interface for Bluetooth tethering. */
    private const val BLUETOOTH_IFACE = "bt-pan"

    fun filter() = IntentFilter().apply {
        addAction(ACTION_TETHER_STATE_CHANGED)
        addAction(ACTION_WIFI_AP_STATE_CHANGED)
    }

    /** [pairlineHotspotOn]: Pairline's own hotspot is running, so a bare "access point on" doesn't mean Android's. */
    fun read(context: Context, pairlineHotspotOn: Boolean): Info {
        val app = context.applicationContext
        val tether = sticky(app, ACTION_TETHER_STATE_CHANGED)
        val tethered = tether?.getStringArrayListExtra(EXTRA_ACTIVE_TETHER)
        val on = if (tethered != null) {
            tethered.any { !NOT_WIFI.matches(it) }
        } else {
            !pairlineHotspotOn &&
                sticky(app, ACTION_WIFI_AP_STATE_CHANGED)?.getIntExtra(EXTRA_WIFI_AP_STATE, 0) == WIFI_AP_STATE_ENABLED
        }
        var upstream = false
        var vpn = false
        var onWifi = false
        val cm = app.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            runCatching {
                @Suppress("DEPRECATION")
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> vpn = true
                        // Android 15 lists the hotspot itself as a Wi-Fi network with LOCAL_NETWORK (seen on a
                        // tester's phone): that's not this phone being on Wi-Fi.
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && isLocalOnly(caps) -> Unit
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                            onWifi = true
                            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) upstream = true
                        }
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> upstream = true
                    }
                }
            }
        }
        val bluetooth = tethered.orEmpty().any { it.startsWith(BLUETOOTH_IFACE) }
        return Info(on = on, upstream = upstream, vpn = vpn, onWifi = onWifi, bluetooth = bluetooth)
    }

    private fun isLocalOnly(caps: NetworkCapabilities): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)

    /** The last sticky broadcast for [action], without registering a receiver. */
    private fun sticky(context: Context, action: String): Intent? = runCatching {
        ContextCompat.registerReceiver(context, null, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
    }.getOrNull()
}
