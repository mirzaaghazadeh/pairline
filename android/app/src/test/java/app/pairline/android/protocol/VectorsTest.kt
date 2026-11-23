package app.pairline.android.protocol

import app.pairline.android.protocol.TestVectors.hex
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

/** Every value in protocol/test-vectors/v0.json, reproduced with JCA (AGENTS.md rule 8). */
class VectorsTest {
    private val v = TestVectors.json

    @Test
    fun versionIsZero() = assertEquals(Proto.VERSION, v.getInt("version"))

    @Test
    fun hkdfRfc5869Case1() {
        val c = v.getJSONObject("hkdf_rfc5869_case1")
        val okm = Hkdf.derive(
            Hex.decode(c.getString("ikm")),
            Hex.decode(c.getString("salt")),
            Hex.decode(c.getString("info")),
            c.getInt("length"),
        )
        assertEquals(c.getString("okm"), Hex.encode(okm))
    }

    @Test
    fun publicKeysRoundTrip() {
        for (name in listOf("sB", "sR", "eB", "eR")) {
            val bytes = TestVectors.publicKey(name)
            assertArrayEquals(bytes, P256.encodePublicKey(P256.decodePublicKey(bytes)))
            assertArrayEquals(bytes, TestVectors.key(name).publicKey)
        }
    }

    @Test
    fun ecdh() {
        val sB = TestVectors.key("sB")
        val eB = TestVectors.key("eB")
        val eR = TestVectors.key("eR")
        val sR = TestVectors.key("sR")
        assertEquals(TestVectors.string("ecdh.eB_eR"), Hex.encode(eB.agree(TestVectors.ecPublic("eR"))))
        assertEquals(TestVectors.string("ecdh.sB_eR"), Hex.encode(sB.agree(TestVectors.ecPublic("eR"))))
        assertEquals(TestVectors.string("ecdh.eB_sR"), Hex.encode(eB.agree(TestVectors.ecPublic("sR"))))
        // And from R's side.
        assertEquals(TestVectors.string("ecdh.eB_eR"), Hex.encode(eR.agree(TestVectors.ecPublic("eB"))))
        assertEquals(TestVectors.string("ecdh.sB_eR"), Hex.encode(eR.agree(TestVectors.ecPublic("sB"))))
        assertEquals(TestVectors.string("ecdh.eB_sR"), Hex.encode(sR.agree(TestVectors.ecPublic("eB"))))
    }

    @Test
    fun rejectsPointsOffTheCurve() {
        val bad = TestVectors.publicKey("sB").copyOf()
        bad[64] = (bad[64].toInt() xor 1).toByte()
        assertThrows(ProtocolException::class.java) { P256.decodePublicKey(bad) }
        assertThrows(ProtocolException::class.java) { P256.decodePublicKey(ByteArray(65)) }
        assertThrows(ProtocolException::class.java) { P256.decodePublicKey(ByteArray(33)) }
    }

    @Test
    fun pairingTranscriptAndMacs() {
        val t = PairingCrypto.transcript(
            TestVectors.publicKey("sB"),
            TestVectors.publicKey("sR"),
            hex("pairing.nonce_r"),
        )
        assertEquals(TestVectors.string("pairing.transcript_t"), Hex.encode(t))
        val ps = hex("pairing.pairing_secret")
        assertEquals(TestVectors.string("pairing.mac_r"), Hex.encode(PairingCrypto.remoteMac(ps, t)))
        assertEquals(
            TestVectors.string("pairing.mac_b"),
            Hex.encode(PairingCrypto.bridgeMac(ps, t, hex("pairing.nonce_b"))),
        )
    }

    @Test
    fun pairingUri() {
        val uri = PairingUri(hex("pairing.device_id"), TestVectors.publicKey("sB"), hex("pairing.pairing_secret"))
        assertEquals(TestVectors.string("pairing.qr_uri"), uri.encode())
        val parsed = PairingUri.parse(TestVectors.string("pairing.qr_uri"))
        assertNotNull(parsed)
        assertArrayEquals(hex("pairing.device_id"), parsed!!.deviceId)
        assertArrayEquals(TestVectors.publicKey("sB"), parsed.publicKey)
        assertArrayEquals(hex("pairing.pairing_secret"), parsed.secret)
        assertNull(PairingUri.parse("pairline://pair?v=1&id=a&k=b&s=c"))
        assertNull(PairingUri.parse("https://example.com"))
    }

    @Test
    fun pairHelloAndPairOkJson() {
        val pairing = v.getJSONObject("pairing")
        val hello = PairHello.parse(pairing.getJSONObject("pair_hello_json").toString().toByteArray())
        assertArrayEquals(TestVectors.publicKey("sR"), hello.remotePublicKey)
        assertArrayEquals(hex("pairing.nonce_r"), hello.nonce)
        assertArrayEquals(hex("pairing.mac_r"), hello.mac)
        assertJsonEquals(pairing.getJSONObject("pair_hello_json"), hello.toJson())

        val ok = PairOk(hex("pairing.nonce_b"), hex("pairing.mac_b")).toJson()
        assertJsonEquals(pairing.getJSONObject("pair_ok_json"), ok)
    }

    @Test
    fun helloBodies() {
        val helloR = Hello(Proto.ROLE_REMOTE, TestVectors.publicKey("eR"), hex("session.rand_r"))
        val helloB = Hello(Proto.ROLE_BRIDGE, TestVectors.publicKey("eB"), hex("session.rand_b"))
        assertEquals(TestVectors.string("session.hello_r"), Hex.encode(helloR.encode()))
        assertEquals(TestVectors.string("session.hello_b"), Hex.encode(helloB.encode()))
        assertEquals(
            TestVectors.string("session.hello_r_frame"),
            Hex.encode(Frame(FrameType.HELLO, helloR.encode()).encode()),
        )
        val decoded = Hello.decode(hex("session.hello_r"), Proto.ROLE_REMOTE)
        assertArrayEquals(TestVectors.publicKey("eR"), decoded.ephemeralPublicKey)
        assertArrayEquals(hex("session.rand_r"), decoded.random)
        assertThrows(ProtocolException::class.java) { Hello.decode(hex("session.hello_r"), Proto.ROLE_BRIDGE) }
        assertThrows(ProtocolException::class.java) { Hello.decode(hex("session.hello_r").copyOf(98), Proto.ROLE_REMOTE) }
        val wrongVersion = hex("session.hello_r").also { it[0] = 1 }
        assertThrows(ProtocolException::class.java) { Hello.decode(wrongVersion, Proto.ROLE_REMOTE) }
    }

    @Test
    fun sessionKeysFromBothSides() {
        val helloR = Hello.decode(hex("session.hello_r"), Proto.ROLE_REMOTE)
        val helloB = Hello.decode(hex("session.hello_b"), Proto.ROLE_BRIDGE)
        assertEquals(
            TestVectors.string("session.salt"),
            Hex.encode(Sha256.digest(helloR.encode(), helloB.encode())),
        )
        val ikm = TestVectors.key("eB").agree(TestVectors.ecPublic("eR")) +
            TestVectors.key("sB").agree(TestVectors.ecPublic("eR")) +
            TestVectors.key("eB").agree(TestVectors.ecPublic("sR"))
        assertEquals(TestVectors.string("session.ikm"), Hex.encode(ikm))

        val b = SessionKeys.forBridge(TestVectors.key("sB"), TestVectors.key("eB"), TestVectors.publicKey("sR"), helloR, helloB)
        val r = SessionKeys.forRemote(TestVectors.key("sR"), TestVectors.key("eR"), TestVectors.publicKey("sB"), helloR, helloB)
        for (keys in listOf(b, r)) {
            assertEquals(TestVectors.string("session.okm"), Hex.encode(keys.okm))
            assertEquals(TestVectors.string("session.k_br"), Hex.encode(keys.bridgeToRemote))
            assertEquals(TestVectors.string("session.k_rb"), Hex.encode(keys.remoteToBridge))
            val (mediaBR, mediaRB) = keys.media()
            val (bulkBR, bulkRB) = keys.bulk()
            assertEquals(TestVectors.string("session.media_k_br"), Hex.encode(mediaBR))
            assertEquals(TestVectors.string("session.media_k_rb"), Hex.encode(mediaRB))
            assertEquals(TestVectors.string("session.bulk_k_br"), Hex.encode(bulkBR))
            assertEquals(TestVectors.string("session.bulk_k_rb"), Hex.encode(bulkRB))
        }
    }

    @Test
    fun confirmRecords() {
        val rb = Records.seal(hex("session.k_rb"), Proto.DIR_REMOTE_TO_BRIDGE, 0, 0, ByteArray(0))
        val br = Records.seal(hex("session.k_br"), Proto.DIR_BRIDGE_TO_REMOTE, 0, 0, ByteArray(0))
        assertEquals(TestVectors.string("session.confirm_r_to_b"), Hex.encode(rb))
        assertEquals(TestVectors.string("session.confirm_b_to_r"), Hex.encode(br))
        val sealer = RecordSealer(hex("session.k_br"), Proto.DIR_BRIDGE_TO_REMOTE, 0)
        assertEquals(TestVectors.string("session.confirm_b_to_r"), Hex.encode(sealer.seal(ByteArray(0))))
        assertEquals(1L, sealer.nextCounter)
    }

    @Test
    fun records() {
        val records = v.getJSONArray("records")
        for (i in 0 until records.length()) {
            val r = records.getJSONObject(i)
            val key = hex("session.${r.getString("key")}")
            val direction = r.getInt("direction")
            val counter = r.getLong("counter")
            val channel = r.getInt("channel")
            val plaintext = if (r.has("plaintext_utf8")) {
                r.getString("plaintext_utf8").toByteArray(Charsets.UTF_8)
            } else {
                Hex.decode(r.getString("plaintext_hex"))
            }
            assertEquals(r.getString("nonce"), Hex.encode(Records.nonce(direction, counter)))
            assertEquals(r.getString("ad"), Hex.encode(Records.associatedData(channel)))
            val wire = Records.seal(key, direction, counter, channel, plaintext)
            assertEquals(r.getString("wire"), Hex.encode(wire))
            val (c, opened) = Records.open(key, direction, channel, Hex.decode(r.getString("wire")))
            assertEquals(counter, c)
            assertArrayEquals(plaintext, opened)
        }
    }

    @Test
    fun mustFail() {
        val cases = v.getJSONArray("must_fail")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val key = hex("session.${c.getString("key")}")
            assertThrows(c.getString("why"), ProtocolException::class.java) {
                Records.open(key, c.getInt("direction"), c.getInt("channel"), Hex.decode(c.getString("wire")))
            }
        }
    }

    @Test
    fun replayIsRejected() {
        val wire = Hex.decode(v.getJSONArray("records").getJSONObject(0).getString("wire"))
        val opener = RecordOpener(hex("session.k_br"), Proto.DIR_BRIDGE_TO_REMOTE, 0)
        opener.open(wire)
        assertEquals(1L, opener.lastAccepted)
        assertThrows(ProtocolException::class.java) { opener.open(wire) }
        // A lower counter is rejected too, even if it authenticates.
        val confirm = hex("session.confirm_b_to_r")
        assertThrows(ProtocolException::class.java) { opener.open(confirm) }
    }

    @Test
    fun framing() {
        val f = v.getJSONObject("framing")
        val recordB = Hex.decode(v.getJSONArray("records").getJSONObject(0).getString("wire"))
        val frame = Frame(FrameType.RECORD, recordB).encode()
        assertEquals(f.getString("frame"), Hex.encode(frame))

        val small = BleFragmenter.fragment(frame, f.getInt("ble_att_payload")).map(Hex::encode)
        assertEquals(jsonStrings(f.getJSONArray("ble_fragments")), small)
        val single = BleFragmenter.fragment(frame, f.getInt("ble_single_fragment_att_payload")).map(Hex::encode)
        assertEquals(jsonStrings(f.getJSONArray("ble_single_fragment")), single)

        for (fragments in listOf(f.getJSONArray("ble_fragments"), f.getJSONArray("ble_single_fragment"))) {
            val reassembler = BleReassembler()
            val parts = jsonStrings(fragments).map(Hex::decode)
            parts.dropLast(1).forEach { assertNull(reassembler.accept(it)) }
            assertArrayEquals(frame, reassembler.accept(parts.last()))
        }

        assertEquals(f.getString("tcp"), Hex.encode(TcpFraming.encode(frame)))
        val stream = ByteArrayInputStream(Hex.decode(f.getString("tcp")))
        assertArrayEquals(frame, TcpFraming.read(stream))
        assertNull(TcpFraming.read(stream))
    }

    private fun jsonStrings(array: org.json.JSONArray): List<String> = List(array.length()) { array.getString(it) }
}

fun assertJsonEquals(expected: JSONObject, actual: JSONObject) {
    val expectedKeys = expected.keys().asSequence().toSet()
    assertEquals(expectedKeys, actual.keys().asSequence().toSet())
    for (k in expectedKeys) assertEquals("field $k", expected.get(k).toString(), actual.get(k).toString())
}
