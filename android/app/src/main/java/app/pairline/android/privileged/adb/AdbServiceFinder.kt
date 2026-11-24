package app.pairline.android.privileged.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Finds this phone's own Wireless debugging services, which Android announces with mDNS on the Wi-Fi network
 * (D-054): `_adb-tls-pairing._tcp` while "Pair device with pairing code" is open, `_adb-tls-connect._tcp` while
 * Wireless debugging is on. Other phones on the same Wi-Fi announce theirs too; only a service at one of this
 * phone's own addresses, on a port that is in use here, counts.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class AdbServiceFinder(private val context: Context, private val type: String) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val lock = Any()
    private var listener: NsdManager.DiscoveryListener? = null
    private var found = CountDownLatch(1)

    @Volatile
    var port: Int? = null
        private set

    /** Called with the port when a service is found, and with null when it goes away. */
    var onChange: ((Int?) -> Unit)? = null

    fun start() {
        synchronized(lock) { if (listener == null) discover() }
    }

    private fun discover() {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                if (port != null) {
                    port = null
                    found = CountDownLatch(1)
                    onChange?.invoke(null)
                }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { listener = null }
    }

    fun stop() {
        synchronized(lock) {
            listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            listener = null
        }
    }

    /** The port, waiting up to [timeoutMs] for one to be found. */
    fun await(timeoutMs: Long): Int? {
        port?.let { return it }
        found.await(timeoutMs, TimeUnit.MILLISECONDS)
        return port
    }

    /**
     * Resolves each found service on its own NsdManager. Before Android 14 one NsdManager resolves only one service
     * at a time, and a stale announcement (from an earlier Wireless debugging session) can stay unanswered forever,
     * which would block the live one. Each Context made here gets its own NsdManager.
     */
    @Suppress("DEPRECATION") // resolveService still works on every version; its replacement is Android 14+ only.
    private fun resolve(info: NsdServiceInfo) {
        val resolver = runCatching {
            context.createConfigurationContext(context.resources.configuration).getSystemService(NsdManager::class.java)
        }.getOrNull() ?: nsd
        runCatching {
            resolver.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host ?: return
                    if (!isThisPhone(host) || !inUseHere(info.port)) return
                    port = info.port
                    found.countDown()
                    onChange?.invoke(info.port)
                }
            })
        }
    }

    private fun isThisPhone(host: InetAddress): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .any { it.hostAddress == host.hostAddress }
    }.getOrDefault(false)

    /** Binding the port fails when adbd is listening on it here. */
    private fun inUseHere(port: Int): Boolean = try {
        ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", port), 1) }
        false
    } catch (_: IOException) {
        true
    }

    companion object {
        const val PAIRING = "_adb-tls-pairing._tcp"
        const val CONNECT = "_adb-tls-connect._tcp"
    }
}
