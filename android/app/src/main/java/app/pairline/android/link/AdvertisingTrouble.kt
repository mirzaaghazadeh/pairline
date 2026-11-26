package app.pairline.android.link

/**
 * Whether Bluetooth advertising keeps failing, so the home screen can say so: some older phones' chips refuse every
 * request (a Galaxy Note 3 on Android 10 answers ADVERTISE_FAILED_TOO_MANY_ADVERTISERS each time), and the iPhone then
 * can't find the bridge over Bluetooth at all. [BleTransport] retries every 30 s, so one failure is often just a busy
 * moment; only [THRESHOLD] in a row, with no success between them, counts.
 */
class AdvertisingTrouble {
    private var failures = 0

    /** The transport's latest status; returns whether advertising keeps failing. Any thread. */
    @Synchronized
    fun onStatus(status: BluetoothStatus, advertising: Boolean): Boolean {
        when {
            advertising -> failures = 0
            status == BluetoothStatus.ADVERTISE_FAILED -> failures++
            // Off, no permission or no BLE at all: the home screen says that instead.
            status == BluetoothStatus.OFF || status == BluetoothStatus.NO_PERMISSION ||
                status == BluetoothStatus.UNSUPPORTED -> failures = 0
        }
        return failures >= THRESHOLD
    }

    companion object {
        const val THRESHOLD = 2
    }
}
