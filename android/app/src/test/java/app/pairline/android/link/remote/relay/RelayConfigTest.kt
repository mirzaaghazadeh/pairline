package app.pairline.android.link.remote.relay

import app.pairline.android.protocol.B64u
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.Hex
import app.pairline.android.protocol.RequestException
import app.pairline.android.protocol.TestVectors
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `remote.relay` and the preamble against `protocol/test-vectors/v1-remote.json` (`relay`, D-106). */
class RelayConfigTest {
    private val vectors = TestVectors.remote.getJSONObject("relay")

    private fun parse(relay: Any?, debug: Boolean = false) = RelayConfig.parse(relay, debugBuild = debug)

    @Test
    fun theVectorMessageParses() {
        val message = vectors.getJSONObject("remote_relay")
        val config = parse(JSONObject(message.toString()))!!
        assertEquals("relay.example.net", config.host)
        assertEquals(443, config.port)
        assertArrayEquals(Hex.decode(vectors.getString("fingerprint")), config.fingerprint)
        assertArrayEquals(Hex.decode(vectors.getString("access_key")), config.key)
        assertArrayEquals(Hex.decode(vectors.getString("rendezvous_id")), config.rendezvous)
        // Round trip through the stored form.
        assertEquals(config, RelayConfig.fromStored(config.toJson(), debugBuild = false))
    }

    @Test
    fun theVectorMessagesThatMustFailFail() {
        val bad = vectors.getJSONArray("remote_relay_must_fail")
        for (i in 0 until bad.length()) {
            val item = bad.getJSONObject(i)
            try {
                parse(item.getJSONObject("relay"))
                fail("accepted: ${item.getString("why")}")
            } catch (e: RequestException) {
                assertEquals(item.getString("why"), ErrorCode.INVALID, e.code)
            }
        }
    }

    @Test
    fun thePreamblesMatch() {
        val config = parse(JSONObject(vectors.getJSONObject("remote_relay").toString()))!!
        val bridge = config.preamble(RelayConfig.ROLE_BRIDGE)
        assertEquals(RelayConfig.PREAMBLE_LENGTH, bridge.size)
        assertEquals(vectors.getString("preamble_bridge"), Hex.encode(bridge))
        assertEquals(vectors.getString("preamble_remote"), Hex.encode(config.preamble(RelayConfig.ROLE_REMOTE)))
    }

    @Test
    fun nullRemovesAndAMissingFieldIsInvalid() {
        assertNull(parse(JSONObject.NULL))
        try {
            parse(null)
            fail("a missing relay was accepted")
        } catch (e: RequestException) {
            assertEquals(ErrorCode.INVALID, e.code)
        }
    }

    @Test
    fun hostsAsTheLinkRulesHaveThem() {
        fun host(h: String, debug: Boolean = false) = RelayConfig.checkHost(h, debug)
        assertEquals("5.6.7.8", host("5.6.7.8"))
        assertEquals("2a01:4f8:c17:1234::1", host("2a01:4f8:c17:1234:0:0:0:1"))
        assertEquals("relay.example.net", host("Relay.Example.NET"))
        assertNull(host("relay"))
        assertNull(host("relay.local"))
        assertNull(host("relay.lan"))
        assertNull(host("localhost"))
        assertNull(host("relay_1.example.net"))
        assertNull(host("-relay.example.net"))
        assertNull(host("1.2.3"))
        assertNull(host("192.168.1.10"))
        assertNull(host("100.64.0.1"))
        assertNull(host("127.0.0.1"))
        assertNull(host("203.0.113.5"))
        assertNull(host("010.1.2.3"))
        assertNull(host("fd00::1"))
        assertNull(host("2001:db8::1"))
        assertNull(host("2002::1"))
        assertNull(host("fe80::1%wlan0"))
        assertNull(host("[2a01:4f8::1]"))
        assertNull(host("1:2:3:4:5:6:7:8:9"))
        assertNull(host("1::2::3"))
        // Debug builds also reach a relay on the development Mac (the emulator's 10.0.2.2, loopback).
        assertEquals("10.0.2.2", host("10.0.2.2", debug = true))
        assertEquals("127.0.0.1", host("127.0.0.1", debug = true))
        assertEquals("::1", host("::1", debug = true))
        assertNull(host("100.64.0.1", debug = true))
        assertNull(host("relay.local", debug = true))
    }

    @Test
    fun ipv6TextIsParsedWithoutLookups() {
        assertEquals("::ffff:1.2.3.4".let { RelayConfig.parseV6(it) }?.address?.let(Hex::encode), "00000000000000000000ffff01020304")
        assertNotNull(RelayConfig.parseV6("2a01:4f8:c17:1234::1"))
        assertNotNull(RelayConfig.parseV6("::"))
        assertNull(RelayConfig.parseV6("2a01:4f8:c17:12345::1"))
        assertNull(RelayConfig.parseV6("2a01:4f8:c17:1234:1"))
        assertNull(RelayConfig.parseV6("relay.example.net"))
    }

    @Test
    fun portsAreWholeNumbersOnly() {
        val base = JSONObject(vectors.getJSONObject("remote_relay").toString())
        assertEquals(65535, parse(JSONObject(base.toString()).put("port", 65535))!!.port)
        for (bad in listOf<Any>(0, 65536, -1, "443", 443.5, true)) {
            try {
                parse(JSONObject(base.toString()).put("port", bad))
                fail("port $bad accepted")
            } catch (e: RequestException) {
                assertEquals(ErrorCode.INVALID, e.code)
            }
        }
    }

    @Test
    fun keysAreCanonicalBase64Url() {
        val base = JSONObject(vectors.getJSONObject("remote_relay").toString())
        val key = Hex.decode(vectors.getString("access_key"))
        // Standard base64 with + or / is refused, as is a 33-byte key.
        val standard = java.util.Base64.getEncoder().withoutPadding().encodeToString(key)
        if (standard != B64u.encode(key)) {
            try {
                parse(JSONObject(base.toString()).put("k", standard))
                fail("standard base64 accepted")
            } catch (_: RequestException) {
            }
        }
        try {
            parse(JSONObject(base.toString()).put("k", B64u.encode(key + byteArrayOf(1))))
            fail("33 bytes accepted")
        } catch (_: RequestException) {
        }
    }

    @Test
    fun nothingSecretInToString() {
        val config = parse(JSONObject(vectors.getJSONObject("remote_relay").toString()))!!
        val text = config.toString()
        assertFalse(text.contains("relay.example.net"))
        assertFalse(text.contains(B64u.encode(config.key)))
        assertTrue(text.isNotEmpty())
    }
}
