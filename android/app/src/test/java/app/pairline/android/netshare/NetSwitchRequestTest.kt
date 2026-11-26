package app.pairline.android.netshare

import app.pairline.android.privileged.PrivilegedService
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.RequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** `net.switch` (PROTOCOL.md, D-073): what the iPhone may ask for, and how Android's refusals reach it. */
class NetSwitchRequestTest {
    @Test
    fun parsesEachSwitch() {
        assertEquals(
            NetSwitchRequest.Tethering(PrivilegedService.TETHER_WIFI, true),
            NetSwitchRequest.parse("hotspot", true, null),
        )
        assertEquals(
            NetSwitchRequest.Tethering(PrivilegedService.TETHER_BLUETOOTH, false),
            NetSwitchRequest.parse("bluetooth", false, null),
        )
        assertEquals(NetSwitchRequest.Data(false), NetSwitchRequest.parse("data", false, 1))
        assertEquals(NetSwitchRequest.DataSim(1), NetSwitchRequest.parse("dataSim", null, 1))
    }

    @Test
    fun onlyTheWifiAndBluetoothTetheringKinds() {
        // The helper refuses every other kind (USB, Ethernet, …); the request can't even name one.
        assertEquals(0, PrivilegedService.TETHER_WIFI)
        assertEquals(2, PrivilegedService.TETHER_BLUETOOTH)
    }

    @Test
    fun rejectsMissingOrUnknownFields() {
        for ((what, on, sim) in listOf(
            Triple(null, true, null),
            Triple("airplane", true, null),
            Triple("usb", true, null),
            Triple("hotspot", null, null),
            Triple("data", null, 0),
            Triple("dataSim", true, null),
        )) {
            val e = assertThrows(RequestException::class.java) { NetSwitchRequest.parse(what, on, sim) }
            assertEquals("$what", ErrorCode.INVALID, e.code)
        }
    }

    @Test
    fun tetheringErrorsReachTheIphone() {
        assertEquals(ErrorCode.PERMISSION, NetSwitchRequest.tetheringError(11).code) // carrier check failed
        assertEquals(ErrorCode.PERMISSION, NetSwitchRequest.tetheringError(13).code)
        assertEquals(ErrorCode.PERMISSION, NetSwitchRequest.tetheringError(14).code)
        assertEquals(ErrorCode.UNSUPPORTED, NetSwitchRequest.tetheringError(3).code)
        assertEquals(ErrorCode.FAILED, NetSwitchRequest.tetheringError(PrivilegedService.TETHER_NO_ANSWER).code)
        assertEquals(ErrorCode.FAILED, NetSwitchRequest.tetheringError(5).code)
    }

    @Test
    fun stateJsonKeepsUnknownMobileDataAsNull() {
        val json = NetState(hotspot = true, bluetooth = false, data = null).toJson()
        assertEquals(true, json.getBoolean("hotspot"))
        assertEquals(false, json.getBoolean("bluetooth"))
        assertEquals(true, json.isNull("data"))
        assertEquals(false, NetState(false, true, false).toJson().getBoolean("data"))
    }
}
