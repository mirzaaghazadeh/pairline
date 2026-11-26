package app.pairline.android.link

import android.os.SystemClock
import app.pairline.android.AppGraph
import app.pairline.android.log.Log
import app.pairline.android.privileged.PrivilegedManager

/**
 * "Restart Bluetooth when stuck" (Settings › Advanced, D-078, opt-in): Android's Bluetooth stack sometimes stops
 * advertising and only comes back when Bluetooth is turned off and on. When advertising fails three times within ten
 * minutes while the iPhone isn't linked (some phones can't advertise during a connection, which is fine), Talk on
 * iPhone's helper restarts Bluetooth, at most once an hour. Without the opt-in or the helper nothing happens.
 */
object BluetoothRecovery {
    private const val FAILURES = 3
    private const val WINDOW_MS = 10 * 60_000L
    private const val MIN_GAP_MS = 60 * 60_000L

    private val failures = ArrayDeque<Long>()
    private var lastRestart = 0L

    @Synchronized
    fun onStatus(status: BluetoothStatus) {
        if (status == BluetoothStatus.ON) {
            failures.clear()
            return
        }
        if (status != BluetoothStatus.ADVERTISE_FAILED || AppGraph.link.isConnected) return
        val now = SystemClock.elapsedRealtime()
        failures.addLast(now)
        while (failures.isNotEmpty() && now - failures.first() > WINDOW_MS) failures.removeFirst()
        if (failures.size < FAILURES || (lastRestart != 0L && now - lastRestart < MIN_GAP_MS)) return
        if (!AppGraph.prefs.bluetoothRestart || AppGraph.privileged.state.value != PrivilegedManager.State.READY) return
        lastRestart = now
        failures.clear()
        Log.w(TAG, "Bluetooth keeps failing to advertise: restarting it")
        AppGraph.io.execute {
            val ok = AppGraph.privileged.restartBluetooth()
            Log.i(TAG, if (ok == true) "Bluetooth restarted" else "Bluetooth restart didn't work")
        }
    }

    private const val TAG = "bt-recovery"
}
