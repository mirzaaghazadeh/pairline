package app.pairline.android.link.remote

import app.pairline.android.link.remote.router.FakeRouter
import app.pairline.android.link.remote.router.RouterNetwork
import app.pairline.android.link.remote.router.homeNetwork
import app.pairline.android.link.remote.router.v4
import app.pairline.android.link.remote.router.v6
import app.pairline.android.protocol.Hex
import app.pairline.android.protocol.Messages
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.type
import java.net.InetAddress
import java.security.SecureRandom
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectLinkTest {

    private class FakeServer : DirectServer {
        var port: Int? = 47830
        val listened = ArrayList<List<InetAddress>>()
        var closes = 0

        override fun listen(addresses: List<InetAddress>): Int? {
            listened += addresses
            return port
        }

        override fun close() {
            closes++
        }
    }

    private val router = FakeRouter()
    private val server = FakeServer()
    private val sent = ArrayList<JSONObject>()
    private val leftovers = MemoryKeyValueStore()
    private val direct = DirectLink(server, { sent += it }, leftovers, router::now, SecureRandom(), QueueDispatcher())

    private fun on(network: RouterNetwork = homeNetwork()) {
        direct.setActive(true)
        direct.setNetwork(DirectLink.Network(network, router))
    }

    /** The messages sent since the last call, as compact strings. */
    private fun taken(): List<String> = sent.map { m ->
        when (m.type) {
            Msg.REMOTE_ADDR -> "addr ${m.opt("v4")} ${m.opt("v6")}"
            Msg.REMOTE_STATE -> "state ${m.getString("direct")} ${m.opt("reason")}"
            else -> m.toString()
        }
    }.also { sent.clear() }

    @Test
    fun natPmpGivesAnOpenDirectConnection() {
        on()
        val wait = direct.step()!!
        assertEquals(listOf("addr 81.2.69.160:47830 null", "state open null"), taken())
        assertEquals(listOf(v4("192.168.1.23")), server.listened.single())
        assertEquals(DirectLink.CHECK_MS, wait)
        assertEquals(
            DirectStatus(true, Direct.OPEN, null, false, localV4 = "192.168.1.23", port = 47830, mappedV4 = true),
            direct.status.value,
        )
    }

    /**
     * Manual port forwarding (D-095): a router with no NAT-PMP, PCP or UPnP still gets a listener, and the screen shows
     * the address and port to forward by hand. Neither goes into a message.
     */
    @Test
    fun withoutARouterProtocolTheStatusSaysWhatToForwardByHand() {
        router.natPmp = false
        on()
        direct.step()
        assertEquals(listOf("addr null null", "state blocked no_mapping"), taken())
        val status = direct.status.value
        assertEquals(DirectReason.NO_MAPPING, status.reason)
        assertEquals("192.168.1.23", status.localV4)
        assertEquals(47830, status.port)
        assertFalse(status.mappedV4)
        assertEquals(listOf(v4("192.168.1.23")), server.listened.single())
    }

    /**
     * The user turns NAT-PMP on in the router and taps "Check my home connection": the router answers, so the mapping
     * is made at once, not at the half-hourly retry.
     */
    @Test
    fun aCheckThatFindsTheRouterAnsweringMapsAtOnce() {
        router.natPmp = false
        on()
        direct.step()
        assertEquals(listOf("addr null null", "state blocked no_mapping"), taken())
        router.natPmp = true
        assertTrue(direct.check().router != null)
        direct.step()
        assertEquals(listOf("addr 81.2.69.160:47830 null", "state open null"), taken())
        assertEquals(1, router.natMappings.size)
    }

    @Test
    fun offWiFiThereIsNothingToForward() {
        direct.setActive(true)
        direct.setNetwork(null)
        direct.step()
        val status = direct.status.value
        assertEquals(DirectReason.NOT_ON_WIFI, status.reason)
        assertNull(status.localV4)
        assertNull(status.port)
    }

    @Test
    fun theExactMessages() {
        on()
        direct.step()
        // Nulls are written out (org.json's key order differs between the JVM and Android, so fields are compared).
        val addr = sent[0]
        assertEquals(setOf("t", "v4", "v6"), addr.keys().asSequence().toSet())
        assertEquals("remote.addr", addr.getString("t"))
        assertEquals("81.2.69.160:47830", addr.getString("v4"))
        assertTrue(addr.isNull("v6") && addr.has("v6"))
        val state = sent[1]
        assertEquals(setOf("t", "direct", "reason"), state.keys().asSequence().toSet())
        assertEquals("remote.state", state.getString("t"))
        assertEquals("open", state.getString("direct"))
        assertTrue(state.isNull("reason") && state.has("reason"))
    }

    @Test
    fun nothingIsSentWhileNothingChanges() {
        on()
        direct.step()
        taken()
        direct.step()
        direct.setNetwork(DirectLink.Network(homeNetwork(), router)) // the same network again
        direct.step()
        assertTrue(taken().isEmpty())
        assertEquals(1, router.natMappings.size)
    }

    @Test
    fun renewsBeforeExpiryAndReportsANewExternalAddress() {
        on()
        direct.step()
        taken()
        val datagrams = router.datagrams.size
        router.time += DirectLink.CHECK_MS
        direct.step()
        assertTrue(router.datagrams.size > datagrams) // renewed
        assertTrue(taken().isEmpty()) // same address: nothing to tell
        router.external = "81.2.69.161"
        router.time += DirectLink.CHECK_MS
        direct.step()
        assertEquals(listOf("addr 81.2.69.161:47830 null"), taken())
    }

    @Test
    fun aRouterThatStopsAnsweringIsAskedAgainSoon() {
        on()
        direct.step()
        taken()
        router.natPmp = false
        router.time += DirectLink.CHECK_MS
        assertEquals(DirectLink.LISTEN_RETRY_MS, direct.step())
        assertEquals(listOf("addr null null", "state blocked no_mapping"), taken())
        router.natPmp = true
        router.time += DirectLink.LISTEN_RETRY_MS
        direct.step()
        assertEquals(listOf("addr 81.2.69.160:47830 null", "state open null"), taken())
    }

    @Test
    fun aShortLifetimeIsRenewedAtHalfOfIt() {
        router.grantedLifetime = 600
        on()
        assertEquals(300_000L, direct.step())
    }

    @Test
    fun carrierGradeNatIsBlocked() {
        router.external = "100.72.1.2"
        on()
        assertEquals(DirectLink.RECHECK_MS, direct.step())
        assertEquals(listOf("addr null null", "state blocked cgnat"), taken())
        assertTrue(router.natMappings.isEmpty())
        assertEquals(DirectReason.CARRIER_NAT, direct.status.value.reason)
    }

    @Test
    fun doubleNatAndNoMapping() {
        router.external = "192.168.0.2"
        on()
        direct.step()
        assertEquals(listOf("addr null null", "state blocked no_public_address"), taken())

        val other = FakeRouter().apply { natPmp = false }
        val link = DirectLink(server, { sent += it }, leftovers, other::now, SecureRandom(), QueueDispatcher())
        link.setActive(true)
        link.setNetwork(DirectLink.Network(homeNetwork(), other))
        link.step()
        assertEquals(listOf("addr null null", "state blocked no_mapping"), taken())
    }

    @Test
    fun offWifiIsBlocked() {
        on()
        direct.step()
        taken()
        direct.setNetwork(null)
        assertNull(direct.step())
        assertEquals(listOf("addr null null", "state blocked not_wifi"), taken())
        assertEquals(1, server.closes)
        // The old router can't be reached any more: no delete was tried; the mapping expires by itself.
        assertEquals(1, router.natMappings.size)
    }

    @Test
    fun nothingIsSentBeforeTheNetworkIsKnown() {
        direct.setActive(true)
        assertNull(direct.step())
        assertTrue(taken().isEmpty())
        assertTrue(server.listened.isEmpty())
    }

    @Test
    fun anotherRouterMapsAgain() {
        on()
        direct.step()
        taken()
        val second = FakeRouter().apply { external = "81.2.69.200" }
        direct.setNetwork(
            DirectLink.Network(RouterNetwork(v4("10.0.0.1"), v4("10.0.0.5"), 24, null, null), second),
        )
        direct.step()
        assertEquals(listOf("addr 81.2.69.200:47830 null"), taken())
        assertEquals(listOf(v4("10.0.0.5")), server.listened.last())
        assertEquals(1, second.natMappings.size)
    }

    @Test
    fun aNewAddressOnTheSameRouterDeletesTheOldMapping() {
        on()
        direct.step()
        taken()
        direct.setNetwork(DirectLink.Network(homeNetwork().copy(localV4 = v4("192.168.1.24")), router))
        direct.step()
        // Deleted from the old address (lifetime 0, port 0), then mapped again from the new one.
        assertTrue(router.datagrams.any { Hex.encode(it) == "00020000bad6000000000000" })
        assertEquals(v4("192.168.1.24"), router.udpFrom.last().first)
        assertTrue(taken().isEmpty()) // same public address and port
    }

    @Test
    fun turningRemoteModeOffClosesAndDeletes() {
        on()
        direct.step()
        taken()
        direct.setActive(false)
        assertNull(direct.step())
        assertEquals(1, server.closes)
        assertTrue(router.natMappings.isEmpty())
        assertEquals("00020000bad6000000000000", Hex.encode(router.datagrams.last()))
        assertTrue(taken().isEmpty())
        assertFalse(direct.status.value.active)
        // On again: everything is announced afresh.
        direct.setActive(true)
        direct.step()
        assertEquals(listOf("addr 81.2.69.160:47830 null", "state open null"), taken())
    }

    @Test
    fun announceResendsEvenWhenUnchanged() {
        on()
        direct.step()
        taken()
        direct.announce()
        direct.step()
        assertEquals(listOf("addr 81.2.69.160:47830 null", "state open null"), taken())
    }

    @Test
    fun ipv6PinholeMakesItOpen() {
        router.natPmp = false
        router.pcp = true
        router.external = "100.64.1.1" // IPv4 behind carrier-grade NAT; IPv6 opened through PCP
        on(homeNetwork(withV6 = true))
        direct.step()
        assertEquals(listOf("addr null [2a02:1234:5678:9abc::23]:47830", "state open null"), taken())
        assertEquals(listOf(v4("192.168.1.23"), homeNetwork(withV6 = true).globalV6), server.listened.single())
        direct.setActive(false)
        direct.step()
        assertTrue(router.pcpMappings.isEmpty()) // the pinhole is closed too
    }

    @Test
    fun anUnconfirmedIPv6AddressIsOfferedButNotCalledOpen() {
        router.external = "100.64.1.1"
        on(homeNetwork(withV6 = true))
        direct.step()
        assertEquals(listOf("addr null [2a02:1234:5678:9abc::23]:47830", "state none cgnat"), taken())
        assertTrue(direct.status.value.ipv6Unconfirmed)
    }

    @Test
    fun onlyPublicAddressesAreOffered() {
        router.external = "100.64.1.1"
        on(homeNetwork(withV6 = true).copy(globalV6 = v6("2001:db8::23")))
        direct.step()
        assertEquals(listOf("addr null null", "state blocked cgnat"), taken())
    }

    @Test
    fun aListenerThatCantStartIsReported() {
        server.port = null
        on()
        assertEquals(DirectLink.LISTEN_RETRY_MS, direct.step())
        assertEquals(listOf("addr null null", "state none listener_failed"), taken())
        assertTrue(router.datagrams.isEmpty())
    }

    @Test
    fun aPermanentUpnpMappingIsRememberedUntilDeleted() {
        router.natPmp = false
        router.upnp = true
        router.upnpPermanentOnly = true
        on()
        direct.step()
        assertEquals(listOf("addr 81.2.69.160:47830 null", "state open null"), taken())
        assertEquals("47830", leftovers.get("upnp.external"))
        direct.setActive(false)
        direct.step()
        assertTrue(router.upnpMappings.isEmpty())
        assertNull(leftovers.get("upnp.external"))
    }

    @Test
    fun aPermanentMappingLeftByAnEarlierRunIsDeletedFirst() {
        router.natPmp = false
        router.upnp = true
        router.upnpMappings[47830] = 0L
        leftovers.put(
            mapOf(
                "upnp.gateway" to "192.168.1.1",
                "upnp.host" to "192.168.1.1",
                "upnp.port" to "5000",
                "upnp.path" to "/ctl/IPConn",
                "upnp.type" to "urn:schemas-upnp-org:service:WANIPConnection:1",
                "upnp.external" to "47830",
            ),
        )
        on()
        direct.step()
        assertEquals("DeletePortMapping", router.soapActions.first())
        assertEquals(mapOf(47830 to 7200L), router.upnpMappings)
        assertNull(leftovers.get("upnp.gateway"))
    }

    @Test
    fun remoteAddrAndStateGoToTheQuietMailbox() {
        assertEquals(MailboxSlot.QUIET, RemoteLink.slotFor(Messages.remoteAddr("81.2.69.160:47830", null)))
        assertEquals(MailboxSlot.QUIET, RemoteLink.slotFor(Messages.remoteState("blocked", "cgnat")))
    }
}
