package app.pairline.android.link.remote.router

import app.pairline.android.protocol.Hex
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAT-PMP, then PCP, then UPnP against a fake router; never a real one. */
class PortMapperTest {
    private val router = FakeRouter()
    private val network = homeNetwork()
    private val mapper = PortMapper(router, network, SecureRandom())

    private fun mapped(): Mapping = (mapper.mapV4(47830) as V4Result.Mapped).mapping

    @Test
    fun natPmpMapsFirst() {
        val m = mapped()
        assertEquals(MapProtocol.NAT_PMP, m.protocol)
        assertEquals(v4("81.2.69.160"), m.external)
        assertEquals(47830, m.externalPort)
        assertEquals(7200L, m.lifetime)
        assertEquals(mapOf(47830 to (47830 to 7200L)), router.natMappings)
        assertEquals("0000", Hex.encode(router.datagrams[0])) // the external address first
        assertEquals("00020000bad6bad600001c20", Hex.encode(router.datagrams[1]))
        assertEquals(v4("192.168.1.23") to v4("192.168.1.1"), router.udpFrom.single())
        assertTrue(router.httpUrls.isEmpty())
    }

    @Test
    fun renewsAtHalfTheGrantedLifetime() {
        router.grantedLifetime = 3600
        val m = mapped()
        assertEquals(m.grantedAt + 1_800_000, m.renewAt)
        router.time += 1_800_000
        val renewed = mapper.renew(m)!!
        assertEquals(router.time, renewed.grantedAt)
        // Asks for the same external port again, with the full lifetime.
        assertEquals("00020000bad6bad600001c20", Hex.encode(router.datagrams.last()))
    }

    @Test
    fun renewalNoticesANewExternalAddress() {
        val m = mapped()
        router.external = "81.2.69.161"
        assertEquals(v4("81.2.69.161"), mapper.renew(m)!!.external)
        router.external = "100.64.3.4"
        assertNull(mapper.renew(m)) // no longer public: the caller maps afresh and reports it
    }

    @Test
    fun deleteSendsLifetimeZeroAndPortZero() {
        val m = mapped()
        assertTrue(mapper.delete(m))
        assertEquals("00020000bad6000000000000", Hex.encode(router.datagrams.last()))
        assertTrue(router.natMappings.isEmpty())
    }

    @Test
    fun carrierGradeNatIsReportedWithoutMapping() {
        router.external = "100.72.1.2"
        val result = mapper.mapV4(47830) as V4Result.NotPublic
        assertEquals(V4Kind.CARRIER_NAT, result.kind)
        assertEquals(1, router.datagrams.size) // only the address request
        assertTrue(router.natMappings.isEmpty())
    }

    @Test
    fun doubleNatIsReported() {
        router.external = "192.168.0.10"
        assertEquals(V4Kind.PRIVATE, (mapper.mapV4(47830) as V4Result.NotPublic).kind)
    }

    @Test
    fun pcpWhenNatPmpIsSilent() {
        router.natPmp = false
        router.pcp = true
        val m = mapped()
        assertEquals(MapProtocol.PCP, m.protocol)
        assertEquals(v4("81.2.69.160"), m.external)
        assertEquals(12, m.nonce!!.size)
        // NAT-PMP got its four tries (250 ms doubling) before PCP was asked.
        assertEquals(listOf(250L, 500L, 1000L, 2000L, 250L), router.waits)
        val renewed = mapper.renew(m)!!
        assertArrayEquals(m.nonce, renewed.nonce)
        assertArrayEquals(m.nonce, router.datagrams.last().copyOfRange(24, 36))
        assertTrue(mapper.delete(renewed))
        assertTrue(router.pcpMappings.isEmpty())
    }

    @Test
    fun pcpMappingOnAPrivateAddressIsDeletedAndReported() {
        router.natPmp = false
        router.pcp = true
        router.external = "10.20.30.40"
        assertEquals(V4Kind.PRIVATE, (mapper.mapV4(47830) as V4Result.NotPublic).kind)
        assertTrue(router.pcpMappings.isEmpty())
    }

    @Test
    fun upnpWhenNeitherNatPmpNorPcpAnswers() {
        router.natPmp = false
        router.upnp = true
        val m = mapped()
        assertEquals(MapProtocol.UPNP, m.protocol)
        assertEquals(7200L, m.lifetime)
        assertEquals(mapOf(47830 to 7200L), router.upnpMappings)
        assertEquals(listOf("GetExternalIPAddress", "AddPortMapping"), router.soapActions)
        val add = router.soapBodies.last()
        assertTrue(add.contains("<NewInternalClient>192.168.1.23</NewInternalClient>"))
        assertTrue(add.contains("<NewProtocol>TCP</NewProtocol>"))
        assertTrue(add.contains("<NewPortMappingDescription>Pairline</NewPortMappingDescription>"))
        assertTrue(mapper.delete(m))
        assertTrue(router.upnpMappings.isEmpty())
    }

    @Test
    fun upnpPermanentLeaseWhenTheRouterAllowsNothingElse() {
        router.natPmp = false
        router.upnp = true
        router.upnpPermanentOnly = true
        val m = mapped()
        assertEquals(0L, m.lifetime)
        assertEquals(mapOf(47830 to 0L), router.upnpMappings)
        assertEquals(m.grantedAt + PortMapper.PERMANENT_CHECK_MS, m.renewAt)
        assertNotNull(mapper.renew(m)) // looked at again with lease 0
        assertTrue(mapper.delete(m)) // and deleted when remote mode ends
        assertTrue(router.upnpMappings.isEmpty())
    }

    @Test
    fun upnpConflictTriesOneOtherPort() {
        router.natPmp = false
        router.upnp = true
        router.upnpConflictOn = 47830
        val m = mapped()
        assertTrue(m.externalPort in 49152..65535)
        assertEquals(47830, m.internalPort)
    }

    @Test
    fun upnpCarrierGradeNat() {
        router.natPmp = false
        router.upnp = true
        router.external = "100.100.100.100"
        assertEquals(V4Kind.CARRIER_NAT, (mapper.mapV4(47830) as V4Result.NotPublic).kind)
        assertEquals(listOf("GetExternalIPAddress"), router.soapActions)
    }

    @Test
    fun upnpNeverFollowsALocationOffTheLan() {
        router.natPmp = false
        router.extraSsdp += SsdpReply(v4("192.168.1.66"), ssdp("http://8.8.8.8:80/desc.xml"))
        router.extraSsdp += SsdpReply(v4("192.168.1.66"), ssdp("http://192.168.2.1:80/desc.xml"))
        router.extraSsdp += SsdpReply(v4("192.168.1.66"), ssdp("http://evil.example/desc.xml"))
        assertEquals(V4Result.NoMapping, mapper.mapV4(47830))
        assertTrue(router.httpUrls.isEmpty())
    }

    @Test
    fun upnpNeverCallsAControlUrlOffTheLan() {
        router.natPmp = false
        router.upnp = true
        router.description = FakeRouter.DESCRIPTION.replace("<controlURL>/ctl/IPConn</controlURL>", "<controlURL>http://8.8.8.8/ctl</controlURL>")
        assertEquals(V4Result.NoMapping, mapper.mapV4(47830))
        assertEquals(listOf("/rootDesc.xml"), router.httpUrls.map { it.path })
    }

    @Test
    fun noRouterSupportMeansNoMapping() {
        router.natPmp = false
        assertEquals(V4Result.NoMapping, mapper.mapV4(47830))
        assertEquals(8, router.datagrams.size) // four NAT-PMP and four PCP tries
    }

    @Test
    fun ipv6PinholeThroughPcpFromThePhonesOwnAddress() {
        val withV6 = homeNetwork(withV6 = true)
        router.pcp = true
        val pinhole = PortMapper(router, withV6, SecureRandom()).pinholeV6(47830)!!
        assertEquals(withV6.globalV6, pinhole.external)
        assertEquals(47830, pinhole.externalPort)
        assertTrue(pinhole.isV6)
        assertEquals(withV6.globalV6 to withV6.gatewayV6, router.udpFrom.single())
        assertEquals(Hex.encode(Pcp.as16(withV6.globalV6!!)), Hex.encode(router.datagrams.single().copyOfRange(8, 24)))
        assertTrue(PortMapper(router, withV6, SecureRandom()).delete(pinhole))
        assertTrue(router.pcpMappings.isEmpty())
    }

    @Test
    fun noIPv6PinholeWithoutIPv6() {
        router.pcp = true
        assertNull(mapper.pinholeV6(47830))
        assertTrue(router.datagrams.isEmpty())
    }

    private fun ssdp(location: String) = "HTTP/1.1 200 OK\r\nLOCATION: $location\r\n\r\n".toByteArray()
}
