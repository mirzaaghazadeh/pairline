package app.pairline.android.link.remote

import app.pairline.android.link.remote.router.FakeRouter
import app.pairline.android.link.remote.router.MapProtocol
import app.pairline.android.link.remote.router.homeNetwork
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.type
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `remote.check` ("Check my home connection"): the verdict, its wire form, and that it never maps anything. */
class HomeCheckTest {
    private val router = FakeRouter()

    private fun check(withV6: Boolean = false) = HomeCheck.run(DirectLink.Network(homeNetwork(withV6), router))

    @Test
    fun publicAddressThroughNatPmpIsLikely() {
        val r = check()
        assertTrue(r.likely)
        assertNull(r.reason)
        assertEquals(MapProtocol.NAT_PMP, r.router)
        assertFalse(r.ipv6)
        assertTrue(router.natMappings.isEmpty())
    }

    @Test
    fun reasons() {
        router.external = "100.72.1.2"
        assertEquals(DirectReason.CARRIER_NAT, check().reason)
        router.external = "192.168.0.10"
        assertEquals(DirectReason.DOUBLE_NAT, check().reason)
        router.external = "0.0.0.0"
        assertEquals(DirectReason.NO_PUBLIC_ADDRESS, check().reason)
        router.natPmp = false
        val none = check(withV6 = true)
        assertFalse(none.likely)
        assertEquals(DirectReason.NO_MAPPING, none.reason)
        assertNull(none.router)
        assertTrue(none.ipv6)
        assertEquals(DirectReason.NOT_ON_WIFI, HomeCheck.run(null).reason)
    }

    @Test
    fun pcpWithoutAnAddressIsLikely() {
        router.natPmp = false
        router.pcp = true
        val r = check()
        assertTrue(r.likely)
        assertEquals(MapProtocol.PCP, r.router)
        assertTrue(router.pcpMappings.isEmpty())
    }

    @Test
    fun wireFormat() {
        val likely = JSONObject(HomeCheck.reply(7, check(withV6 = true)).toString())
        assertEquals(setOf("t", "id", "direct", "reason", "router", "ipv6"), likely.keys().asSequence().toSet())
        assertEquals("remote.check", likely.getString("t"))
        assertEquals(7L, likely.getLong("id"))
        assertEquals("likely", likely.getString("direct"))
        assertTrue(likely.isNull("reason"))
        assertEquals("nat-pmp", likely.getString("router"))
        assertTrue(likely.getBoolean("ipv6"))
        router.external = "100.72.1.2"
        val cgnat = HomeCheck.reply(8, check())
        assertEquals(Msg.REMOTE_CHECK, cgnat.type)
        assertEquals("unlikely", cgnat.getString("direct"))
        assertEquals("cgnat", cgnat.getString("reason"))
        assertEquals("nat-pmp", cgnat.getString("router"))
        val offWifi = HomeCheck.reply(9, HomeCheck.run(null))
        assertEquals("not_wifi", offWifi.getString("reason"))
        assertTrue(offWifi.isNull("router"))
        assertFalse(offWifi.getBoolean("ipv6"))
    }

    @Test
    fun theLogLineHasNoAddress() {
        val line = HomeCheck.describe(check(withV6 = true))
        assertFalse(line.contains("81.2"))
        assertFalse(line.contains("2a02"))
        assertEquals("home connection check: a direct connection is likely (router answers NAT-PMP); this phone has IPv6", line)
    }
}
