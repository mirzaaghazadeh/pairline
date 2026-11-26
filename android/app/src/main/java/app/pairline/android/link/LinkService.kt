package app.pairline.android.link

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.telephony.TelephonyManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.pairline.android.AppGraph
import app.pairline.android.audio.MediaChannelTransport
import app.pairline.android.link.remote.DirectNetworkWatcher
import app.pairline.android.link.wifi.HotspotState
import app.pairline.android.link.wifi.WifiTransport
import app.pairline.android.log.Log
import app.pairline.android.ui.LocaleHelper
import app.pairline.android.ui.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the link to the iPhone running: a `connectedDevice` foreground service with a persistent notification
 * that shows the link state. Started after setup, at boot and after app updates.
 */
class LinkService : Service() {
    private val graph = AppGraph
    private var transports: List<LinkTransport> = emptyList()
    private var ble: BleTransport? = null
    private var wifi: WifiTransport? = null
    private var directWatcher: DirectNetworkWatcher? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!enterForeground() || graph.prefs.bridgeStopped) {
            stopSelf()
            return
        }
        Log.i(TAG, "link service started")
        val link = graph.link
        link.handler = graph.router
        link.wifiListener = graph.files
        link.start()
        // Remote mode (D-080): checks the mailbox in iCloud while no session is up, if the iPhone turned it on.
        graph.remote.start()
        // Its direct connection: while remote mode is on and the phone is on Wi-Fi, a public listener and a port
        // opened on the home router; closed and deleted when remote mode ends.
        graph.direct.start()
        directWatcher = DirectNetworkWatcher(this) { graph.direct.setNetwork(it) }.also { it.start() }
        scope.launch {
            graph.remote.state.map { it.on }.distinctUntilChanged().collect { graph.direct.setActive(it) }
        }

        val bleTransport = BleTransport(this, link, { graph.identity.deviceId }) { bt, advertising ->
            link.updateState { it.copy(bluetooth = bt, advertising = advertising) }
            BluetoothRecovery.onStatus(bt)
        }
        ble = bleTransport
        transports = listOf(bleTransport) + ExtraTransports.create(this, link)
        transports.forEach { it.start() }
        applyWifiSetting()

        // Where the iPhone finds us on Wi-Fi changed: tell it (wifi.offer) and refresh a QR code on screen.
        scope.launch {
            link.state.map { it.wifiHint to it.hotspotBand }.distinctUntilChanged().collect {
                graph.router.wifiChanged()
                graph.pairing.refreshWifi()
            }
        }
        scope.launch {
            graph.hotspot.state.collect { s ->
                link.updateState { it.copy(hotspotBand = (s as? HotspotState.On)?.band) }
            }
        }
        // Call audio (M6) rides on the Wi-Fi session's media channel, or inside the Bluetooth session (D-072); a relay
        // stops when its channel or session closes.
        scope.launch {
            link.media.collect { channel -> graph.audioRelay.setTransport(channel?.let(::MediaChannelTransport)) }
        }
        link.onLinkMediaClosed = graph.audioRelay::linkClosed
        graph.audioRelay.onLinkAudio = { on -> bleTransport.setLowLatency(on) }

        graph.status.onChange = graph.router::statusChanged
        graph.status.onCallState = graph.calls::onTelephonyState
        graph.status.start()
        graph.netSwitches.start()

        ContextCompat.registerReceiver(
            this,
            bluetoothReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        // Protected system broadcast: carries the caller's number on Android 12+ (READ_CALL_LOG).
        ContextCompat.registerReceiver(
            this,
            phoneStateReceiver,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )

        scope.launch {
            // Remote mode's state too: with no nearby link, the notification says the iPhone is reached through iCloud.
            combine(link.state, graph.peers.paired, graph.prefs.changes, graph.remote.state) { s, paired, _, remote ->
                Triple(s, paired, remote.on && remote.problem == null)
            }
                .distinctUntilChanged()
                .collect { (s, paired, _) -> Notifications.updateLink(this@LinkService, s, paired, graph.prefs.peerName) }
        }
        // The daily update check (D-042): looks every hour whether a day has passed since the last one.
        scope.launch {
            while (isActive) {
                graph.updates.checkIfDue("daily")
                delay(UPDATE_LOOK_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() needs a startForeground() answer, not only the first one: Android may have
        // taken the service out of the foreground meanwhile ("Restrict background activity" does), and then
        // crashes the app if a new start isn't answered. Repeating it only refreshes the notification.
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (graph.prefs.bridgeStopped) {
            // A start that slipped past start()'s check (e.g. Android restarting a sticky service): stay stopped.
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ADB_PAIR) {
            // The Wireless debugging code typed into Pairline's notification (D-054): pair while this foreground
            // service keeps the process alive.
            val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
            graph.io.execute { graph.privileged.submitPairingCode(code) }
        }
        if (intent?.action == ACTION_REFRESH) {
            // Permissions or settings may have changed. start() is a no-op while the GATT server runs, so a
            // live connection isn't disturbed; it retries if Bluetooth wasn't usable before.
            ble?.start()
            applyWifiSetting()
            graph.status.refresh()
        }
        return START_STICKY
    }

    /** Starts or stops the Wi-Fi link to match Settings (on by default). */
    private fun applyWifiSetting() {
        val link = graph.link
        if (graph.prefs.wifiLinkEnabled) {
            if (wifi != null) return
            val transport = WifiTransport(this, link, graph.hotspot) { status, hint ->
                link.updateState { it.copy(wifi = status, wifiHint = hint) }
            }
            wifi = transport
            transport.start()
        } else {
            val transport = wifi ?: return
            wifi = null
            transport.stop()
            graph.hotspot.stop()
            link.updateState { it.copy(wifi = WifiLinkStatus.OFF, wifiHint = null) }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "link service stopped")
        runCatching { unregisterReceiver(bluetoothReceiver) }
        runCatching { unregisterReceiver(phoneStateReceiver) }
        graph.status.stop()
        graph.netSwitches.stop()
        transports.forEach { it.stop() }
        transports = emptyList()
        wifi?.stop()
        wifi = null
        directWatcher?.stop()
        directWatcher = null
        graph.direct.stop()
        graph.remote.stop()
        graph.link.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        val notification = Notifications.linkNotification(this, graph.link.state.value, graph.peers.paired.value, graph.prefs.peerName)
        return try {
            // ServiceCompat ignores the type below Android 10, so the inlined constant is safe on Android 9.
            @Suppress("InlinedApi")
            ServiceCompat.startForeground(
                this,
                Notifications.LINK_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            true
        } catch (e: Exception) {
            // Android 14+ requires a Bluetooth permission before a connectedDevice service can start.
            Log.w(TAG, "couldn't start in the foreground", e)
            graph.link.updateState { it.copy(running = false, bluetooth = BluetoothStatus.NO_PERMISSION) }
            false
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth turned on")
                    ble?.start()
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "Bluetooth turned off")
                    ble?.stop()
                    graph.link.updateState { it.copy(bluetooth = BluetoothStatus.OFF, advertising = false) }
                }
            }
        }
    }

    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) return
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            if (!number.isNullOrBlank()) graph.calls.onIncomingNumber(number)
        }
    }

    companion object {
        private const val TAG = "service"
        private const val UPDATE_LOOK_MS = 3600_000L
        const val ACTION_REFRESH = "app.pairline.android.action.REFRESH"
        private const val ACTION_ADB_PAIR = "app.pairline.android.action.ADB_PAIR"
        private const val EXTRA_CODE = "code"

        /**
         * Hands a Wireless debugging pairing code to the running link service. False when Pairline is stopped or Android
         * refuses the start; the caller then pairs by itself.
         */
        fun pairWithCode(context: Context, code: String): Boolean {
            if (AppGraph.prefs.bridgeStopped) return false
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, LinkService::class.java).setAction(ACTION_ADB_PAIR).putExtra(EXTRA_CODE, code),
                )
                true
            } catch (e: Exception) {
                Log.w(TAG, "couldn't hand the pairing code to the link service", e)
                false
            }
        }

        /** Starts the link unless the user stopped Pairline; [byUser] (Start, pairing, setup) clears that first. */
        fun start(context: Context, reason: String, byUser: Boolean = false) {
            val prefs = AppGraph.prefs
            if (byUser && prefs.bridgeStopped) {
                prefs.bridgeStopped = false
                Log.i(TAG, "started again by the user ($reason)")
                AppGraph.privileged.refresh()
            }
            if (prefs.bridgeStopped) {
                Log.d(TAG, "not starting ($reason): stopped by the user")
                return
            }
            try {
                ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java))
                Log.d(TAG, "start requested ($reason)")
            } catch (e: Exception) {
                // Android 12+ blocks starting foreground services from the background in some cases.
                Log.w(TAG, "couldn't start the link service ($reason)", e)
            }
        }

        /** Re-checks permissions, Bluetooth and status after the user changed something. */
        fun refresh(context: Context) {
            if (AppGraph.prefs.bridgeStopped) return
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, LinkService::class.java).setAction(ACTION_REFRESH),
                )
            } catch (e: Exception) {
                Log.w(TAG, "couldn't refresh the link service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkService::class.java))
        }

        /**
         * Settings › Stop Pairline: ends the link, the hotspot, internet sharing and the helper, and keeps them off
         * (also after a reboot or an update) until the user starts Pairline again. Main thread.
         */
        fun stopByUser(context: Context) {
            Log.i(TAG, "stopped by the user")
            AppGraph.prefs.bridgeStopped = true
            AppGraph.netShare.stop("Pairline stopped")
            AppGraph.hotspot.stop()
            stop(context)
            AppGraph.privileged.refresh()
        }
    }
}

/** Restarts the link after a reboot and after the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (AppGraph.prefs.setupDone) LinkService.start(context, intent.action ?: "boot")
            }
        }
    }
}
