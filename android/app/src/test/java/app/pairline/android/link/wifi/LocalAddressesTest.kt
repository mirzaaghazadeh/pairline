package app.pairline.android.link.wifi

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Wi-Fi link accepts only the local network (PROTOCOL.md "Wi-Fi", AGENTS.md rule 2). */
class LocalAddressesTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun privateAndLinkLocalAreLocal() {
        for (a in listOf(
            "10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.1", "192.168.1.23", "169.254.10.20",
            "fd12:3456:789a::1", "fc00::1", "fe80::1", "::ffff:192.168.1.5",
        )) {
            assertTrue(a, LocalAddresses.isLocal(ip(a)))
        }
    }

    @Test
    fun everythingElseIsNot() {
        for (a in listOf(
            "8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "100.64.0.1", "127.0.0.1", "0.0.0.0",
            "224.0.0.251", "255.255.255.255", "2001:4860:4860::8888", "::1", "fec0::1", "ff02::fb",
            "::ffff:8.8.8.8",
        )) {
            assertFalse(a, LocalAddresses.isLocal(ip(a)))
        }
    }

    @Test
    fun linkLocalIpv6IsNotAdvertised() {
        assertTrue(LocalAddresses.isAdvertisable(ip("192.168.1.23")))
        assertTrue(LocalAddresses.isAdvertisable(ip("fd00::5")))
        assertFalse("needs a zone the iPhone can't know", LocalAddresses.isAdvertisable(ip("fe80::1")))
    }

    @Test
    fun formatDropsTheZone() {
        assertEquals("192.168.1.23", LocalAddresses.format(ip("192.168.1.23")))
        assertEquals("fd12:3456:789a:0:0:0:0:1", LocalAddresses.format(ip("fd12:3456:789a::1")))
    }

    @Test
    fun mobileInterfaceNames() {
        for (n in listOf("rmnet_data0", "ccmni1", "v4-rmnet_data0", "tun0", "ppp0", "clat4")) {
            assertTrue(n, InterfaceNames.looksMobileOrVpn(n))
        }
        for (n in listOf("wlan0", "wlan1", "swlan0", "ap0", "eth0", "rndis0", "p2p-wlan0-0")) {
            assertFalse(n, InterfaceNames.looksMobileOrVpn(n))
        }
    }
}
