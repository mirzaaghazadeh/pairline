package app.pairline.android.link.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.core.content.ContextCompat
import app.pairline.android.link.ConnectionListener
import app.pairline.android.link.LinkConnection
import app.pairline.android.link.LinkTransport
import app.pairline.android.link.MediaCapable
import app.pairline.android.link.MediaEndpoint
import app.pairline.android.link.TransportKind
import app.pairline.android.link.WifiLinkStatus
import app.pairline.android.log.Log
import app.pairline.android.protocol.PairingUri
import app.pairline.android.protocol.TcpFraming
import app.pairline.android.protocol.WifiHint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The production Wi-Fi link (M5, PROTOCOL.md "Wi-Fi"; D-044). Listens for the iPhone on TCP port 47820 on each local
 * address of the phone's Wi-Fi and hotspot interfaces — never on mobile data, VPNs or public addresses — and closes
 * any connection from a peer that isn't on the local network. Frames carry a 4-byte length, like the debug TCP
 * link; each connection runs the full pairing, handshake and records. Advertises `_pairline._tcp` over DNS-SD with a
 * random name. Re-binds when networks or the hotspot change.
 *
 * This package is the only place in the app (with `update/` and `netshare/`) allowed to open sockets
 * (`.github/scripts/network-code-check.sh`).
 */
class WifiTransport(
    context: Context,
    private val listener: ConnectionListener,
    private val hotspot: BridgeHotspot,
    private val onState: (WifiLinkStatus, WifiHint?) -> Unit,
) : LinkTransport {
    override val kind = TransportKind.WIFI

    private val app = context.applicationContext
    private val connectivity: ConnectivityManager? = app.getSystemService(ConnectivityManager::class.java)
    private val nsd: NsdManager? = app.getSystemService(NsdManager::class.java)
    private val control = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pairline-wifi") }
    private val labels = AtomicInteger(1)
    private val random = SecureRandom()

    /** Listening sockets by local address (with its scope). Touched on the control thread only. */
    private val listeners = HashMap<String, ServerSocket>()

    @Volatile
    private var running = false
    private var port = PREFERRED_PORT
    private var periodic: ScheduledFuture<*>? = null
    private var pendingRescan: ScheduledFuture<*>? = null
    private var serviceName: String? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var registeredPort = 0
    private var lastHint: WifiHint? = null
    private var lastStatus: WifiLinkStatus? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleRescan()
        override fun onLost(network: Network) = scheduleRescan()
        override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = scheduleRescan()
    }
    private val hotspotListener: () -> Unit = {
        // The hotspot interface takes a moment to get its address.
        scheduleRescan(1_500)
        scheduleRescan(5_000)
    }

    /**
     * Android's own hotspot (Settings › Hotspot & tethering) isn't a Network the callback above sees, so its state
     * changes come as broadcasts. Over it the iPhone also gets this phone's internet, which Pairline's own
     * local-only hotspot can't give.
     */
    private val tetherReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = hotspotListener()
    }

    override fun start() {
        if (running) return
        running = true
        control.execute {
            serviceName = "pairline-" + ByteArray(4).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            try {
                connectivity?.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    networkCallback,
                )
            } catch (e: Exception) {
                Log.w(TAG, "can't watch Wi-Fi networks; rescanning on a timer only", e)
            }
            hotspot.addListener(hotspotListener)
            runCatching {
                val filter = IntentFilter().apply {
                    addAction(ACTION_WIFI_AP_STATE_CHANGED)
                    addAction(ACTION_TETHER_STATE_CHANGED)
                }
                // Protected system broadcasts (only Android sends them). "Not exported" would block them on Android 12
                // and older, where AndroidX implements it with a permission the system's broadcasts don't carry.
                ContextCompat.registerReceiver(app, tetherReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            }
            periodic = control.scheduleWithFixedDelay({ rescan() }, RESCAN_EVERY_S, RESCAN_EVERY_S, TimeUnit.SECONDS)
            rescan()
        }
    }

    override fun stop() {
        if (!running) return
        running = false
        control.execute {
            runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
            runCatching { app.unregisterReceiver(tetherReceiver) }
            hotspot.removeListener(hotspotListener)
            periodic?.cancel(false)
            pendingRescan?.cancel(false)
            unregisterService()
            listeners.values.forEach { runCatching { it.close() } }
            listeners.clear()
            connections.values.forEach { it.close() }
            lastHint = null
            lastStatus = WifiLinkStatus.OFF
            onState(WifiLinkStatus.OFF, null)
            Log.i(TAG, "Wi-Fi link stopped")
        }
    }

    /** Looks at the networks again soon (several changes in a row cause one rescan). */
    fun scheduleRescan(delayMs: Long = 800) {
        if (!running) return
        runCatching {
            control.schedule({ rescan() }, delayMs, TimeUnit.MILLISECONDS).also { pendingRescan = it }
        }
    }

    // --- Addresses and listeners (control thread) -----------------------------------------------------------

    private fun rescan() {
        if (!running) return
        val wanted = try {
            eligibleAddresses()
        } catch (e: Exception) {
            Log.w(TAG, "couldn't list network interfaces", e)
            return
        }
        val wantedKeys = wanted.associateBy { it.hostAddress ?: "" }
        for ((key, socket) in listeners.toList()) {
            if (key !in wantedKeys) {
                runCatching { socket.close() }
                listeners.remove(key)
            }
        }
        for ((key, address) in wantedKeys) {
            if (key in listeners) continue
            bind(address)?.let { socket ->
                listeners[key] = socket
                Thread({ acceptLoop(socket) }, "pairline-wifi-accept").apply { isDaemon = true }.start()
            }
        }
        val bound = listeners.keys.mapNotNull { key -> wantedKeys[key] }
        val advertised = bound.filter(LocalAddresses::isAdvertisable)
            .sortedBy { if (it is Inet4Address) 0 else 1 }
            .map(LocalAddresses::format)
            .distinct()
            .take(PairingUri.MAX_ADDRESSES)
        val status = if (listeners.isEmpty()) WifiLinkStatus.NO_NETWORK else WifiLinkStatus.LISTENING
        val spot = hotspot.state.value as? HotspotState.On
        val hint = if (advertised.isEmpty()) null else WifiHint(advertised, port, spot?.ssid, spot?.password)
        if (listeners.isEmpty()) unregisterService() else registerService()
        if (hint != lastHint || status != lastStatus) {
            if (status != lastStatus) {
                Log.i(TAG, if (status == WifiLinkStatus.LISTENING) "listening on ${listeners.size} local address(es), port $port" else "no local Wi-Fi network")
            }
            lastHint = hint
            lastStatus = status
            onState(status, hint)
        }
    }

    /** Local addresses on interfaces that are up and aren't mobile data or a VPN. */
    private fun eligibleAddresses(): List<InetAddress> {
        val mobile = mobileOrVpnInterfaces()
        val out = ArrayList<InetAddress>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return out
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback || nif.name.startsWith("dummy")) continue
            val excluded = nif.name in mobile || InterfaceNames.looksMobileOrVpn(nif.name)
            if (excluded && !WifiDebug.allowEmulatorMobileInterface) continue
            for (address in nif.inetAddresses) {
                if (LocalAddresses.isLocal(address)) out += address
            }
        }
        return out
    }

    private fun mobileOrVpnInterfaces(): Set<String> {
        val cm = connectivity ?: return emptySet()
        val out = HashSet<String>()
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                cm.getLinkProperties(network)?.interfaceName?.let(out::add)
            }
        }
        return out
    }

    private fun bind(address: InetAddress): ServerSocket? {
        fun tryPort(p: Int): ServerSocket? = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, p), BACKLOG)
            }
        } catch (_: IOException) {
            null
        }
        tryPort(port)?.let { return it }
        // Our usual port is taken. With no other listener yet, move every address to a free port.
        if (listeners.isEmpty()) {
            val socket = tryPort(0) ?: return null
            port = socket.localPort
            Log.i(TAG, "port $PREFERRED_PORT is taken; using $port")
            return socket
        }
        Log.w(TAG, "couldn't listen on one local address")
        return null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            val peer = socket.inetAddress
            if (peer == null || !LocalAddresses.isLocal(peer)) {
                Log.w(TAG, "refused a connection from outside the local network")
                runCatching { socket.close() }
                continue
            }
            if (connections.size >= MAX_CONNECTIONS) {
                Log.w(TAG, "too many Wi-Fi connections; refusing one")
                runCatching { socket.close() }
                continue
            }
            WifiConnection(socket, "wifi#${labels.getAndIncrement()}").start()
        }
    }

    // --- DNS-SD (control thread) -----------------------------------------------------------------------------

    private fun registerService() {
        val manager = nsd ?: return
        if (registration != null && registeredPort == port) return
        unregisterService()
        val info = NsdServiceInfo().apply {
            serviceName = this@WifiTransport.serviceName
            serviceType = SERVICE_TYPE
            port = this@WifiTransport.port
            setAttribute("v", "0")
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Log.i(TAG, "advertising on the local network (DNS-SD)")
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.w(TAG, "DNS-SD registration failed ($error)")
                control.execute { if (registration === this) registration = null }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
            registration = reg
            registeredPort = port
        } catch (e: Exception) {
            Log.w(TAG, "DNS-SD isn't available", e)
        }
    }

    private fun unregisterService() {
        val reg = registration ?: return
        registration = null
        runCatching { nsd?.unregisterService(reg) }
    }

    // --- Connections ----------------------------------------------------------------------------------------

    private val connections = java.util.concurrent.ConcurrentHashMap<String, WifiConnection>()

    private inner class WifiConnection(private val socket: Socket, override val label: String) :
        LinkConnection, MediaCapable {
        override val kind = TransportKind.WIFI
        override val peerAddress: InetAddress? = socket.inetAddress
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "pairline-$label-write") }
        private val backlog = Semaphore(MAX_BACKLOG)
        private val output by lazy { BufferedOutputStream(socket.getOutputStream(), 64 * 1024) }

        @Volatile
        private var closed = false

        fun start() {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            connections[label] = this
            listener.onOpened(this)
            Thread({ readLoop() }, "pairline-$label-read").apply { isDaemon = true }.start()
        }

        private fun readLoop() {
            var reason = "closed by the peer"
            try {
                val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
                while (!closed) {
                    val frame = TcpFraming.read(input) ?: break
                    // Wait while the link thread is behind (a fast file transfer), so frames don't pile up.
                    backlog.acquire()
                    listener.onFrame(this, frame)
                }
            } catch (e: Exception) {
                reason = if (closed) "closed" else "read error (${e.javaClass.simpleName})"
            } finally {
                closed = true
                runCatching { socket.close() }
                writer.shutdown()
                connections.remove(label)
                listener.onClosed(this, reason)
            }
        }

        override fun frameProcessed() = backlog.release()

        override fun send(frame: ByteArray) = send(frame) {}

        override fun send(frame: ByteArray, onSent: () -> Unit) {
            if (closed) return onSent()
            try {
                writer.execute {
                    try {
                        if (!closed) {
                            output.write(TcpFraming.encode(frame))
                            output.flush()
                        }
                    } catch (_: IOException) {
                        closed = true
                        runCatching { socket.close() }
                    } finally {
                        onSent()
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                onSent()
            }
        }

        /** Closes after frames already queued (such as a final `unpair`) are written, or after 2 s at most. */
        override fun close() {
            if (closed) return
            closed = true
            runCatching { writer.execute { runCatching { output.flush() }; runCatching { socket.close() } } }
            Thread({
                Thread.sleep(2_000)
                runCatching { socket.close() }
            }, "pairline-$label-close").apply { isDaemon = true }.start()
        }

        override fun openMedia(sealKey: ByteArray, openKey: ByteArray): MediaEndpoint? {
            val local = socket.localAddress ?: return null
            val peer = socket.inetAddress ?: return null
            val media = MediaSocket.open(local, peer, PREFERRED_PORT, sealKey, openKey, label, networkOf(local))
            if (media == null) Log.w(TAG, "$label: no media socket")
            return media
        }
    }

    /** The network that owns [address] (a Wi-Fi network; none for the hotspot, which isn't a Network). */
    private fun networkOf(address: InetAddress): Network? {
        val cm = connectivity ?: return null
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val lp = cm.getLinkProperties(network) ?: continue
            if (lp.linkAddresses.any { it.address == address }) return network
        }
        return null
    }

    companion object {
        private const val TAG = "wifi"
        const val PREFERRED_PORT = 47820
        const val SERVICE_TYPE = "_pairline._tcp"
        private const val BACKLOG = 8
        private const val RESCAN_EVERY_S = 20L

        // Android's own broadcasts for its hotspot and tethering (not in the public SDK, sent since Android 4).
        private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
        private const val MAX_CONNECTIONS = 8
        private const val MAX_BACKLOG = 32
    }
}
