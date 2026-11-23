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
import app.pairline.android.log.Log
import app.pairline.android.ui.LocaleHelper
import app.pairline.android.ui.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the link to the iPhone running: a `connectedDevice` foreground service with a persistent notification
 * that shows the link state. Started after setup, at boot and after app updates.
 */
class LinkService : Service() {
    private val graph = AppGraph
    private var transports: List<LinkTransport> = emptyList()
    private var ble: BleTransport? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!enterForeground()) {
            stopSelf()
            return
        }
        Log.i(TAG, "link service started")
        val link = graph.link
        link.handler = graph.router
        link.start()

        val bleTransport = BleTransport(this, link, { graph.identity.deviceId }) { bt, advertising ->
            link.updateState { it.copy(bluetooth = bt, advertising = advertising) }
        }
        ble = bleTransport
        transports = listOf(bleTransport) + ExtraTransports.create(this, link)
        transports.forEach { it.start() }

        graph.status.onChange = graph.router::statusChanged
        graph.status.onCallState = graph.calls::onTelephonyState
        graph.status.start()

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
            combine(link.state, graph.peers.paired, graph.prefs.changes) { s, paired, _ -> s to paired }
                .distinctUntilChanged()
                .collect { (s, paired) -> Notifications.updateLink(this@LinkService, s, paired, graph.prefs.peerName) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            // Permissions or settings changed: re-check Bluetooth and the status.
            ble?.let {
                it.stop()
                it.start()
            }
            graph.status.refresh()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "link service stopped")
        runCatching { unregisterReceiver(bluetoothReceiver) }
        runCatching { unregisterReceiver(phoneStateReceiver) }
        graph.status.stop()
        transports.forEach { it.stop() }
        transports = emptyList()
        graph.link.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        val notification = Notifications.linkNotification(this, graph.link.state.value, graph.peers.paired.value, graph.prefs.peerName)
        return try {
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
        const val ACTION_REFRESH = "app.pairline.android.action.REFRESH"

        fun start(context: Context, reason: String) {
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
