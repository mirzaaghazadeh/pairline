package app.pairline.android.link.remote.router

import app.pairline.android.protocol.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Check my home connection" asks the router read-only: no mapping is ever made, renewed or deleted. */
class RouterProbeTest {
    private val router = FakeRouter()
    private val probe = RouterProbe(router, homeNetwork())

    private fun assertNothingMapped() {
        assertTrue(router.natMappings.isEmpty())
        assertTrue(router.pcpMappings.isEmpty())
        assertTrue(router.upnpMappings.isEmpty())
        assertTrue(router.soapActions.none { it != "GetExternalIPAddress" })
    }

    @Test
    fun natPmpAnswersWithAPublicAddress() {
        val r = probe.probe()
        assertEquals(MapProtocol.NAT_PMP, r.protocol)
        assertEquals(V4Kind.PUBLIC, r.external)
        assertEquals(listOf("0000"), router.datagrams.map(Hex::encode)) // the external-address request only
        assertNothingMapped()
    }

    @Test
    fun natPmpBehindCarrierGradeNat() {
        router.external = "100.72.1.2"
        val r = probe.probe()
        assertEquals(MapProtocol.NAT_PMP, r.protocol)
        assertEquals(V4Kind.CARRIER_NAT, r.external)
        assertNothingMapped()
    }

    @Test
    fun pcpAnnounceWithoutAnAddress() {
        router.natPmp = false
        router.pcp = true
        val r = probe.probe()
        assertEquals(MapProtocol.PCP, r.protocol)
        assertNull(r.external)
        // NAT-PMP's address request four times, then one ANNOUNCE: version 2, opcode 0, lifetime 0, this phone.
        val announce = router.datagrams.last()
        assertEquals(24, announce.size)
        assertEquals("0200000000000000", Hex.encode(announce.copyOfRange(0, 8)))
        assertEquals("00000000000000000000ffffc0a80117", Hex.encode(announce.copyOfRange(8, 24)))
        assertNothingMapped()
    }

    @Test
    fun pcpWithUpnpTellsTheAddress() {
        router.natPmp = false
        router.pcp = true
        router.upnp = true
        router.external = "192.168.0.10"
        val r = probe.probe()
        assertEquals(MapProtocol.PCP, r.protocol)
        assertEquals(V4Kind.PRIVATE, r.external)
        assertNothingMapped()
    }

    @Test
    fun upnpOnly() {
        router.natPmp = false
        router.upnp = true
        val r = probe.probe()
        assertEquals(MapProtocol.UPNP, r.protocol)
        assertEquals(V4Kind.PUBLIC, r.external)
        assertEquals(listOf("GetExternalIPAddress"), router.soapActions)
        assertNothingMapped()
    }

    @Test
    fun nothingAnswers() {
        router.natPmp = false
        val r = probe.probe()
        assertNull(r.protocol)
        assertNull(r.external)
        assertNothingMapped()
    }

    @Test
    fun noIpv4OnTheNetwork() {
        val r = RouterProbe(router, homeNetwork().copy(gatewayV4 = null)).probe()
        assertNull(r.protocol)
        assertTrue(router.datagrams.isEmpty())
    }
}
