package app.pairline.android.link.remote.router

import app.pairline.android.link.remote.DirectNetworks
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressesTest {

    @Test
    fun classifiesTheRoutersInternetAddress() {
        mapOf(
            "81.2.69.160" to V4Kind.PUBLIC,
            "100.63.255.255" to V4Kind.PUBLIC,
            "100.128.0.1" to V4Kind.PUBLIC,
            "172.32.0.1" to V4Kind.PUBLIC,
            "100.64.0.1" to V4Kind.CARRIER_NAT,
            "100.127.255.254" to V4Kind.CARRIER_NAT,
            "10.1.2.3" to V4Kind.PRIVATE,
            "172.16.0.1" to V4Kind.PRIVATE,
            "172.31.255.255" to V4Kind.PRIVATE,
            "192.168.0.1" to V4Kind.PRIVATE,
            "0.0.0.0" to V4Kind.UNSPECIFIED,
            "127.0.0.1" to V4Kind.RESERVED,
            "169.254.1.1" to V4Kind.RESERVED,
            "192.0.2.5" to V4Kind.RESERVED,
            "198.51.100.5" to V4Kind.RESERVED,
            "203.0.113.5" to V4Kind.RESERVED,
            "198.18.0.1" to V4Kind.RESERVED,
            "224.0.0.1" to V4Kind.RESERVED,
            "255.255.255.255" to V4Kind.RESERVED,
        ).forEach { (text, kind) -> assertEquals(text, kind, Addresses.classifyV4(v4(text))) }
    }

    @Test
    fun globalIPv6() {
        assertTrue(Addresses.isGlobalV6(v6("2a02:1234::1")))
        assertTrue(Addresses.isGlobalV6(v6("2c0f:f000::1")))
        assertFalse(Addresses.isGlobalV6(v6("2001:db8::1"))) // documentation
        assertFalse(Addresses.isGlobalV6(v6("2001:0:1::1"))) // Teredo
        assertFalse(Addresses.isGlobalV6(v6("fe80::1")))
        assertFalse(Addresses.isGlobalV6(v6("fd00::1")))
        assertFalse(Addresses.isGlobalV6(v6("::1")))
        assertFalse(Addresses.isGlobalV6(v4("81.2.69.160")))
    }

    @Test
    fun remoteAddrFormats() {
        assertEquals("81.2.69.160:47830", Addresses.formatV4(v4("81.2.69.160"), 47830))
        assertEquals("[2a02:1234:5678:9abc::23]:47830", Addresses.formatV6(v6("2a02:1234:5678:9abc:0:0:0:23"), 47830))
        // RFC 5952: lowercase, no leading zeros, the longest zero run (the first when tied), never a single group.
        assertEquals("2001:db8::1:0:0:1", Addresses.v6Text(v6("2001:0DB8:0:0:1:0:0:1")))
        assertEquals("2001:db8:0:1:1:1:1:1", Addresses.v6Text(v6("2001:db8:0:1:1:1:1:1")))
        assertEquals("2001:0:0:1::1", Addresses.v6Text(v6("2001:0:0:1:0:0:0:1")))
        assertEquals("::1", Addresses.v6Text(v6("::1")))
        assertEquals("fe80::", Addresses.v6Text(v6("fe80::")))
        assertEquals("2a02:1234:5678:9abc:1:2:3:4", Addresses.v6Text(v6("2a02:1234:5678:9abc:1:2:3:4")))
    }

    @Test
    fun strictIPv4Literals() {
        assertEquals(v4("192.168.1.1"), Addresses.parseV4("192.168.1.1"))
        assertEquals(v4("0.0.0.0"), Addresses.parseV4("0.0.0.0"))
        listOf("192.168.1", "192.168.1.1.1", "192.168.01.1", "256.1.1.1", "a.b.c.d", "1.2.3.-4", "", "1..2.3", " 1.2.3.4")
            .forEach { assertNull(it, Addresses.parseV4(it)) }
    }

    @Test
    fun subnets() {
        assertTrue(Addresses.sameSubnet(v4("192.168.1.1"), v4("192.168.1.200"), 24))
        assertFalse(Addresses.sameSubnet(v4("192.168.1.1"), v4("192.168.2.1"), 24))
        assertTrue(Addresses.sameSubnet(v4("192.168.1.1"), v4("192.168.3.1"), 22))
        assertFalse(Addresses.sameSubnet(v4("192.168.1.1"), v6("::1"), 24))
    }

    @Test
    fun logHintsShowOnlyTheLastPart() {
        assertEquals("….1", Addresses.lanHint(v4("192.168.1.1")))
        assertEquals("…:23", Addresses.lanHint(v6("fe80::23")))
    }

    @Test
    fun choosesAStableGlobalIPv6Address() {
        val temporary = v6("2a02:1234::aaaa") to 0x01
        val stable = v6("2a02:1234::bbbb") to 0x80 // IFA_F_PERMANENT
        val deprecated = v6("2a02:1234::cccc") to 0x20
        val linkLocal = v6("fe80::1") to 0x80
        val ula = v6("fd00::1") to 0
        val v4 = (v4("192.168.1.23") as InetAddress) to 0
        assertEquals(stable.first, DirectNetworks.chooseGlobalV6(listOf(v4, linkLocal, temporary, stable, deprecated)))
        assertEquals(temporary.first, DirectNetworks.chooseGlobalV6(listOf(temporary, deprecated, ula)))
        assertNull(DirectNetworks.chooseGlobalV6(listOf(deprecated, linkLocal, ula, v4)))
    }
}
