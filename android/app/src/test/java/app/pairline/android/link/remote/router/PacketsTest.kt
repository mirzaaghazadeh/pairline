package app.pairline.android.link.remote.router

import app.pairline.android.protocol.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAT-PMP (RFC 6886) and PCP (RFC 6887) packets, built by hand from the RFCs' layouts. */
class PacketsTest {

    // --- NAT-PMP ---------------------------------------------------------------------------------------------

    @Test
    fun natPmpExternalAddressRequestIsTwoBytes() {
        assertEquals("0000", Hex.encode(NatPmp.externalAddressRequest()))
    }

    @Test
    fun natPmpMapRequestLayout() {
        // version 0, opcode 2 (TCP), reserved, internal port 47830 (0xbad6), suggested 47830, lifetime 7200 (0x1c20)
        assertEquals("00020000bad6bad600001c20", Hex.encode(NatPmp.mapRequest(47830, 47830, 7200)))
        // RFC 6886 §3.4: a delete has lifetime 0 and suggested external port 0.
        assertEquals("00020000bad6000000000000", Hex.encode(NatPmp.mapRequest(47830, 0, 0)))
    }

    @Test
    fun natPmpAddressReply() {
        // version 0, opcode 128, result 0, epoch 57600 s, 81.2.69.160
        val reply = NatPmp.parseAddressReply(Hex.decode("00800000" + "0000e100" + "510245a0"))!!
        assertEquals(0, reply.result)
        assertEquals(0xe100L, reply.epoch)
        assertEquals(v4("81.2.69.160"), reply.external)
    }

    @Test
    fun natPmpAddressReplyErrorAndJunk() {
        // result 3: network failure; no address.
        val failed = NatPmp.parseAddressReply(Hex.decode("0080000300000001"))!!
        assertEquals(3, failed.result)
        assertNull(failed.external)
        assertNull(NatPmp.parseAddressReply(Hex.decode("0082000000000001510245a0"))) // a mapping reply's opcode
        assertNull(NatPmp.parseAddressReply(Hex.decode("0180000000000001510245a0"))) // version 1
        assertNull(NatPmp.parseAddressReply(Hex.decode("00800000"))) // short
        assertNull(NatPmp.parseAddressReply(Hex.decode("00800000000000015102"))) // success but no address
    }

    @Test
    fun natPmpMapReply() {
        val reply = NatPmp.parseMapReply(Hex.decode("00820000" + "00000e10" + "bad6" + "c350" + "00000e10"))!!
        assertEquals(0, reply.result)
        assertEquals(47830, reply.internalPort)
        assertEquals(50000, reply.externalPort)
        assertEquals(3600L, reply.lifetime)
        assertNull(NatPmp.parseMapReply(Hex.decode("00810000" + "00000e10" + "bad6c35000000e10"))) // UDP opcode
        assertNull(NatPmp.parseMapReply(Hex.decode("00820000" + "00000e10" + "bad6"))) // short
        assertEquals(2, NatPmp.parseMapReply(Hex.decode("00820002" + "00000e10"))!!.result) // refused
    }

    @Test
    fun natPmpClientRetriesAt250MsDoublingThenGivesUp() {
        val router = FakeRouter().apply { natPmp = false }
        val client = NatPmpClient(router.udp(v4("192.168.1.23"), v4("192.168.1.1")), router::now)
        assertNull(client.externalAddress())
        assertEquals(listOf(250L, 500L, 1000L, 2000L), router.waits)
        assertEquals(4, router.datagrams.size)
    }

    @Test
    fun natPmpClientSkipsAStrayDatagram() {
        val router = FakeRouter()
        val udp = router.udp(v4("192.168.1.23"), v4("192.168.1.1"))
        var first = true
        val wrapped = object : RouterUdp {
            override fun send(datagram: ByteArray) = udp.send(datagram)
            override fun receive(timeoutMs: Long): ByteArray? {
                if (first) {
                    first = false
                    return Hex.decode("0082000000000001bad6bad600001c20") // a mapping reply, not ours
                }
                return udp.receive(timeoutMs)
            }
        }
        assertEquals(v4("81.2.69.160"), NatPmpClient(wrapped, router::now).externalAddress())
    }

    // --- PCP -------------------------------------------------------------------------------------------------

    private val nonce = Hex.decode("0102030405060708090a0b0c")

    @Test
    fun pcpMapRequestLayoutIPv4() {
        val request = Pcp.mapRequest(v4("192.168.1.23"), nonce, 47830, 47830, 7200)
        val expected = "02" + "01" + "0000" + "00001c20" + // version, R=0 + MAP, reserved, lifetime
            "00000000000000000000ffffc0a80117" + // client: ::ffff:192.168.1.23
            "0102030405060708090a0b0c" + // nonce
            "06" + "000000" + "bad6" + "bad6" + // TCP, reserved, internal and suggested port
            "00000000000000000000ffff00000000" // suggested external: IPv4's all-zeros address
        assertEquals(expected, Hex.encode(request))
        assertEquals(Pcp.REQUEST_LENGTH, request.size)
    }

    @Test
    fun pcpMapRequestLayoutIPv6() {
        val request = Pcp.mapRequest(v6("2a02:1234:5678:9abc::23"), nonce, 47830, 47830, 7200)
        assertEquals("2a02123456789abc0000000000000023", Hex.encode(request.copyOfRange(8, 24)))
        assertEquals("00000000000000000000000000000000", Hex.encode(request.copyOfRange(44, 60))) // "::"
    }

    @Test
    fun pcpMapReplyParses() {
        val reply = "02" + "81" + "00" + "00" + "00001c20" + "00000e10" + "000000000000000000000000" +
            "0102030405060708090a0b0c" + "06000000" + "bad6" + "c350" + "00000000000000000000ffff510245a0"
        val parsed = Pcp.parseMapReply(Hex.decode(reply), nonce, 47830)!!
        assertEquals(0, parsed.result)
        assertEquals(7200L, parsed.lifetime)
        assertEquals(50000, parsed.externalPort)
        assertEquals(v4("81.2.69.160"), parsed.external)
        // Another nonce or internal port: not ours.
        assertNull(Pcp.parseMapReply(Hex.decode(reply), Hex.decode("ffffffffffffffffffffffff"), 47830))
        assertNull(Pcp.parseMapReply(Hex.decode(reply), nonce, 47831))
        // A request (R bit clear) or a short packet isn't a reply.
        assertNull(Pcp.parseMapReply(Hex.decode("0201" + reply.substring(4)), nonce, 47830))
        assertNull(Pcp.parseMapReply(Hex.decode(reply.substring(0, 40)), nonce, 47830))
    }

    @Test
    fun pcpErrorReplyKeepsTheResult() {
        // NOT_AUTHORIZED (2), header only.
        val reply = Pcp.parseMapReply(Hex.decode("02810002" + "00000000" + "00000e10" + "00".repeat(12)), nonce, 47830)!!
        assertEquals(2, reply.result)
    }

    @Test
    fun natPmpOnlyRouterAnswersPcpWithUnsupportedVersion() {
        assertTrue(Pcp.isNatPmpOnlyAnswer(Hex.decode("0081000100000e10")))
        assertFalse(Pcp.isNatPmpOnlyAnswer(Hex.decode("0080000000000e10")))
        val router = FakeRouter() // NAT-PMP only
        val client = PcpClient(router.udp(v4("192.168.1.23"), v4("192.168.1.1")), v4("192.168.1.23"), router::now)
        assertNull(client.map(nonce, 47830, 47830, 7200))
        assertEquals(1, router.datagrams.size) // no retries once the router said it doesn't know version 2
    }

    @Test
    fun pcpClientMapsAndDeletesWithTheSameNonce() {
        val router = FakeRouter().apply { natPmp = false; pcp = true }
        val client = PcpClient(router.udp(v4("192.168.1.23"), v4("192.168.1.1")), v4("192.168.1.23"), router::now)
        val mapped = client.map(nonce, 47830, 47830, 7200)!!
        assertEquals(47830, mapped.externalPort)
        assertEquals(mapOf(Hex.encode(nonce) to (47830 to 7200L)), router.pcpMappings)
        assertTrue(client.delete(nonce, 47830))
        assertTrue(router.pcpMappings.isEmpty())
        assertArrayEquals(nonce, router.datagrams.last().copyOfRange(24, 36))
        assertEquals("00000000", Hex.encode(router.datagrams.last().copyOfRange(4, 8)))
    }
}
