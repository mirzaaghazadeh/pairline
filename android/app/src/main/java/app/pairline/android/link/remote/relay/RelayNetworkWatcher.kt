package app.pairline.android.link.remote.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import app.pairline.android.log.Log

/**
 * Tells [RelayClient] when the phone's default network changes (Wi-Fi, Ethernet or mobile data: the relay is reached
 * on whichever the phone uses), so the waiting connection moves to the new one at once instead of timing out on the
 * old one. The first report, at registration, only sets the starting point.
 */
class RelayNetworkWatcher(context: Context, private val onChange: () -> Unit) {
    private val cm: ConnectivityManager? = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var last: Network? = null

    @Volatile
    private var seen = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val first = !seen
            seen = true
            if (network == last) return
            last = network
            if (!first) onChange()
        }

        override fun onLost(network: Network) {
            if (network == last) last = null
        }
    }

    fun start() {
        try {
            cm?.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "can't watch the default network", e)
        }
    }

    fun stop() {
        runCatching { cm?.unregisterNetworkCallback(callback) }
    }

    private companion object {
        const val TAG = "relay"
    }
}
