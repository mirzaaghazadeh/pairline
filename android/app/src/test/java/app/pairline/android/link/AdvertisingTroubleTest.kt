package app.pairline.android.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertisingTroubleTest {
    @Test
    fun oneFailureIsABusyMomentButRepeatedOnesShow() {
        val t = AdvertisingTrouble()
        assertFalse(t.onStatus(BluetoothStatus.ON, false)) // GATT server up, advertising requested
        assertFalse(t.onStatus(BluetoothStatus.ADVERTISE_FAILED, false))
        assertTrue(t.onStatus(BluetoothStatus.ADVERTISE_FAILED, false))
        // Another path reporting Bluetooth as on (no advertising) doesn't hide it…
        assertTrue(t.onStatus(BluetoothStatus.ON, false))
        // …but advertising working does.
        assertFalse(t.onStatus(BluetoothStatus.ON, true))
    }

    @Test
    fun bluetoothOffOrNoPermissionResetsIt() {
        val t = AdvertisingTrouble()
        t.onStatus(BluetoothStatus.ADVERTISE_FAILED, false)
        assertTrue(t.onStatus(BluetoothStatus.ADVERTISE_FAILED, false))
        assertFalse(t.onStatus(BluetoothStatus.OFF, false))
        assertFalse(t.onStatus(BluetoothStatus.ADVERTISE_FAILED, false))
    }
}
