package app.pairline.android.netshare

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.pairline.android.AppPrefs
import app.pairline.android.log.Log
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.RequestException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Starts and stops internet sharing for the paired iPhone (M8, D-048). Off unless the user allows it in the bridge
 * app; the iPhone then turns it on and off with `net.share`. The port (47830 when free) and the credentials stay the
 * same from one start to the next, so the iPhone's proxy settings keep working; switching sharing off on the bridge
 * retires the credentials (D-058).
 */
class NetShare(private val context: Context, private val prefs: AppPrefs) {

    data class Session(val host: String, val port: Int, val username: String, val password: String) {
        val pacUrl: String get() = "http://${if (':' in host) "[$host]" else host}:$port/proxy.pac"
    }

    private val lock = Any()
    private var proxy: ShareProxy? = null
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    /** The paired iPhone's address on the Wi-Fi link (M5), when known. Only it may use HTTP without a password. */
    @Volatile
    var peerAddress: () -> InetAddress? = { null }

    /** Called when sharing starts or stops, so the bridge can tell the iPhone. */
    var onChange: (() -> Unit)? = null

    fun start(): Session = synchronized(lock) {
        if (!prefs.netShareAllowed) throw RequestException(ErrorCode.PERMISSION, "internet sharing is off on the bridge")
        _session.value?.let { return it }
        val address = localAddress()
            ?: throw RequestException(ErrorCode.UNSUPPORTED, "the bridge isn't on a Wi-Fi network or hotspot")
        // The same port and credentials every time (until sharing is switched off on the bridge), so the proxy URL
        // pasted into the iPhone's Wi-Fi settings and the Telegram proxy keep working the next time.
        val (username, password) = credentials()
        var current: Session? = null
        val p = ShareProxy(
            bindAddress = address,
            username = username,
            password = password,
            pac = { current?.let { Pac.build(it.host, it.port, directSuffixes()) } ?: "" },
            allowedClient = { peerAddress() },
        )
        val port = try {
            try {
                p.start(PREFERRED_PORT)
            } catch (e: java.io.IOException) {
                Log.w(TAG, "proxy port $PREFERRED_PORT is busy; using another one")
                p.start()
            }
        } catch (e: java.io.IOException) {
            throw RequestException(ErrorCode.FAILED, "couldn't open the proxy port")
        }
        val s = Session(address.hostAddress ?: return@synchronized fail(p), port, username, password)
        current = s
        proxy = p
        _session.value = s
        Log.i(TAG, "internet sharing on (VPN ${if (vpnActive()) "on" else "off"})")
        onChange?.invoke()
        s
    }

    fun stop(reason: String) = synchronized(lock) {
        val p = proxy ?: return@synchronized
        proxy = null
        p.stop()
        _session.value = null
        Log.i(TAG, "internet sharing off ($reason), ${p.connectionsServed} connections")
        onChange?.invoke()
    }

    fun directSuffixes(): List<String> = if (prefs.netShareDirectIran) Pac.IRAN else emptyList()

    /** Whether this phone's own traffic goes through a VPN right now (read locally, no lookups). */
    fun vpnActive(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching {
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        }.getOrDefault(false)
    }

    /**
     * The address the iPhone reaches this phone on: an IPv4 address of a Wi-Fi or hotspot interface, preferring the
     * iPhone's subnet when the Wi-Fi link knows its address.
     */
    fun localAddress(): InetAddress? {
        val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .filter { WIFI_NAMES.any { prefix -> it.name.startsWith(prefix) } }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address && ShareProxy.isLocal(it.address) }
        val peer = peerAddress() as? Inet4Address
        if (peer != null) {
            candidates.firstOrNull { sameSubnet(it.address, peer, it.networkPrefixLength.toInt()) }?.let { return it.address }
        }
        return candidates.firstOrNull()?.address
    }

    private fun fail(p: ShareProxy): Nothing {
        p.stop()
        throw RequestException(ErrorCode.FAILED, "no usable address")
    }

    private fun sameSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
        val x = a.address
        val y = b.address
        if (x.size != y.size) return false
        var bits = prefix
        for (i in x.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    /** The stored credentials, or new ones (kept until sharing is switched off on the bridge). */
    private fun credentials(): Pair<String, String> {
        prefs.netShareCredentials?.split(':', limit = 2)?.takeIf { it.size == 2 && it.all(String::isNotEmpty) }?.let {
            return it[0] to it[1]
        }
        val made = ("pl-" + token(6)) to token(20)
        prefs.netShareCredentials = "${made.first}:${made.second}"
        return made
    }

    private fun token(length: Int): String {
        val random = SecureRandom()
        return String(CharArray(length) { ALPHABET[random.nextInt(ALPHABET.length)] })
    }

    companion object {
        private const val TAG = "netshare"
        /** Next to the Wi-Fi link's 47820; another one is used only if something else holds it. */
        const val PREFERRED_PORT = 47830
        private const val ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val WIFI_NAMES = listOf("wlan", "ap", "swlan", "softap", "wifi", "eth")
    }
}
