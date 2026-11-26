package app.pairline.android.protocol

import app.pairline.android.protocol.TestVectors.hex
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the bridge's session state machine as the iPhone would, using the shared vectors. */
class BridgeSessionTest {

    private class Gate(var secret: ByteArray?, var refuse: PairFailReason? = null) : PairingGate {
        var pinned: ByteArray? = null
        var badMacs = 0
        override fun claim(): PairingClaim {
            refuse?.let { return PairingClaim.Refused(it) }
            val s = secret ?: return PairingClaim.Refused(PairFailReason.EXPIRED)
            return PairingClaim.Granted(s.copyOf())
        }
        override fun onBadMac() { badMacs++ }
        override fun onPaired(remotePublicKey: ByteArray) {
            pinned = remotePublicKey
            secret = null
        }
    }

    private class Recorder(var pinned: ByteArray?) : SessionCallbacks {
        val frames = ArrayList<ByteArray>()
        val messages = ArrayList<JSONObject>()
        var closedReason: String? = null
        var established = false
        var paired = false
        override fun sendFrame(frame: ByteArray) { frames += frame }
        override fun close(reason: String) { closedReason = reason }
        override fun pinnedRemoteKey(): ByteArray? = pinned
        override fun onPaired() { paired = true }
        override fun onEstablished() { established = true }
        override fun onMessage(message: JSONObject) { messages += message }
    }

    private fun session(
        gate: Gate,
        recorder: Recorder,
        random: java.security.SecureRandom = FixedRandom(hex("session.rand_b")),
    ) = BridgeSession(
        identity = TestVectors.key("sB"),
        pairing = gate,
        callbacks = recorder,
        random = random,
        newEphemeral = { TestVectors.key("eB") },
    )

    private fun frame(type: FrameType, body: ByteArray) = Frame(type, body).encode()

    private val pairHello: ByteArray
        get() = TestVectors.json.getJSONObject("pairing").getJSONObject("pair_hello_json").toString().toByteArray()

    @Test
    fun pairsThenHandshakesThenExchangesRecords() {
        val gate = Gate(hex("pairing.pairing_secret"))
        val rec = Recorder(pinned = null)
        val s = session(gate, rec, FixedRandom(hex("pairing.nonce_b"), hex("session.rand_b")))

        s.onFrame(frame(FrameType.PAIR_HELLO, pairHello))
        assertNull(rec.closedReason)
        assertTrue(rec.paired)
        assertArrayEquals(TestVectors.publicKey("sR"), gate.pinned)
        val ok = Frame.decode(rec.frames.single())
        assertEquals(FrameType.PAIR_OK, ok.type)
        assertJsonEquals(
            TestVectors.json.getJSONObject("pairing").getJSONObject("pair_ok_json"),
            JSONObject(String(ok.body)),
        )

        // The pinned key is now known; continue with the handshake on the same connection.
        rec.pinned = gate.pinned
        rec.frames.clear()
        s.onFrame(hex("session.hello_r_frame"))
        assertEquals(2, rec.frames.size)
        assertEquals("10" + TestVectors.string("session.hello_b"), Hex.encode(rec.frames[0]))
        assertEquals("20" + TestVectors.string("session.confirm_b_to_r"), Hex.encode(rec.frames[1]))
        assertFalse(rec.established)

        s.onFrame(frame(FrameType.RECORD, hex("session.confirm_r_to_b")))
        assertTrue(rec.established)
        assertEquals(BridgeSession.State.ESTABLISHED, s.state)

        val recordR = TestVectors.json.getJSONArray("records").getJSONObject(1)
        s.onFrame(frame(FrameType.RECORD, Hex.decode(recordR.getString("wire"))))
        val msg = rec.messages.single()
        assertEquals("sms.send", msg.getString("t"))
        assertEquals("سلام 👋", msg.getString("body"))
        assertEquals(7L, msg.requestId)

        // Our first message uses counter 1 and decrypts with k_br.
        rec.frames.clear()
        assertTrue(s.send(JSONObject().put("t", "status")))
        val out = Frame.decode(rec.frames.single())
        val opener = RecordOpener(hex("session.k_br"), Proto.DIR_BRIDGE_TO_REMOTE, 0)
        opener.open(hex("session.confirm_b_to_r"))
        val (counter, plain) = opener.open(out.body)
        assertEquals(1L, counter)
        assertEquals("status", JSONObject(String(plain)).getString("t"))

        // A replayed record closes the connection.
        s.onFrame(frame(FrameType.RECORD, Hex.decode(recordR.getString("wire"))))
        assertNotNull(rec.closedReason)
        assertEquals(BridgeSession.State.CLOSED, s.state)
        assertFalse(s.send(JSONObject().put("t", "status")))
    }

    @Test
    fun handshakeWithPinnedKey() {
        val rec = Recorder(pinned = TestVectors.publicKey("sR"))
        val s = session(Gate(null), rec)
        s.onFrame(hex("session.hello_r_frame"))
        s.onFrame(frame(FrameType.RECORD, hex("session.confirm_r_to_b")))
        assertTrue(rec.established)
        assertNull(rec.closedReason)
    }

    @Test
    fun badMacIsRefused() {
        val gate = Gate(ByteArray(32) { 7 })
        val rec = Recorder(null)
        session(gate, rec).onFrame(frame(FrameType.PAIR_HELLO, pairHello))
        assertPairFail(rec, "bad_mac")
        assertEquals(1, gate.badMacs)
        assertNull(gate.pinned)
    }

    @Test
    fun expiredAndUsedAndBusyAreRefused() {
        for (reason in listOf(PairFailReason.EXPIRED, PairFailReason.USED, PairFailReason.BUSY)) {
            val rec = Recorder(null)
            session(Gate(hex("pairing.pairing_secret"), refuse = reason), rec)
                .onFrame(frame(FrameType.PAIR_HELLO, pairHello))
            assertPairFail(rec, reason.wire)
        }
    }

    @Test
    fun directConnectionRefusesPairingSilently() {
        // Remote mode's direct connection (D-092): a valid PAIR_HELLO with the code on screen still gets nothing.
        val gate = Gate(hex("pairing.pairing_secret"))
        val rec = Recorder(pinned = null)
        BridgeSession(TestVectors.key("sB"), gate, rec, allowPairing = false).onFrame(frame(FrameType.PAIR_HELLO, pairHello))
        assertNotNull(rec.closedReason)
        assertTrue(rec.frames.isEmpty())
        assertNull(gate.pinned)
        assertEquals(0, gate.badMacs)

        // The handshake with the pinned iPhone works as on Wi-Fi.
        val ok = Recorder(pinned = TestVectors.publicKey("sR"))
        val s = BridgeSession(
            TestVectors.key("sB"), Gate(null), ok, FixedRandom(hex("session.rand_b")), { TestVectors.key("eB") }, allowPairing = false,
        )
        s.onFrame(hex("session.hello_r_frame"))
        s.onFrame(frame(FrameType.RECORD, hex("session.confirm_r_to_b")))
        assertTrue(ok.established)
    }

    @Test
    fun helloWithoutPairingCloses() {
        val rec = Recorder(pinned = null)
        session(Gate(null), rec).onFrame(hex("session.hello_r_frame"))
        assertNotNull(rec.closedReason)
        assertTrue(rec.frames.isEmpty())
    }

    @Test
    fun wrongPeerFailsConfirm() {
        // Pinned to a different key: B derives different keys, so R's confirm doesn't decrypt.
        val rec = Recorder(pinned = TestVectors.publicKey("eR"))
        val s = session(Gate(null), rec)
        s.onFrame(hex("session.hello_r_frame"))
        s.onFrame(frame(FrameType.RECORD, hex("session.confirm_r_to_b")))
        assertFalse(rec.established)
        assertNotNull(rec.closedReason)
    }

    @Test
    fun unknownFrameTypeCloses() {
        val rec = Recorder(TestVectors.publicKey("sR"))
        session(Gate(null), rec).onFrame(byteArrayOf(0x7f, 1, 2))
        assertNotNull(rec.closedReason)
    }

    @Test
    fun recordBeforeHandshakeCloses() {
        val rec = Recorder(TestVectors.publicKey("sR"))
        session(Gate(null), rec).onFrame(frame(FrameType.RECORD, hex("session.confirm_r_to_b")))
        assertNotNull(rec.closedReason)
    }

    @Test
    fun confirmMustBeEmptyAndCounterZero() {
        val rec = Recorder(TestVectors.publicKey("sR"))
        val s = session(Gate(null), rec)
        s.onFrame(hex("session.hello_r_frame"))
        val notEmpty = Records.seal(hex("session.k_rb"), Proto.DIR_REMOTE_TO_BRIDGE, 0, 0, "x".toByteArray())
        s.onFrame(frame(FrameType.RECORD, notEmpty))
        assertFalse(rec.established)
        assertNotNull(rec.closedReason)
    }

    @Test
    fun secondHelloAfterEstablishedCloses() {
        val rec = Recorder(TestVectors.publicKey("sR"))
        val s = session(Gate(null), rec)
        s.onFrame(hex("session.hello_r_frame"))
        s.onFrame(frame(FrameType.RECORD, hex("session.confirm_r_to_b")))
        s.onFrame(hex("session.hello_r_frame"))
        assertNotNull(rec.closedReason)
    }

    @Test
    fun malformedPairHelloGetsBadMac() {
        val badPoint = JSONObject(TestVectors.json.getJSONObject("pairing").getJSONObject("pair_hello_json").toString())
            .put("k", B64u.encode(ByteArray(65).also { it[0] = 4 }))
        for (body in listOf(
            "{\"t\":\"pair_hello\",\"v\":0}",
            "not json",
            "{\"t\":\"pair_hello\",\"v\":1,\"k\":\"\",\"n\":\"\",\"mac\":\"\"}",
            badPoint.toString(),
        )) {
            val gate = Gate(hex("pairing.pairing_secret"))
            val rec = Recorder(null)
            session(gate, rec).onFrame(frame(FrameType.PAIR_HELLO, body.toByteArray()))
            assertPairFail(rec, "bad_mac")
            assertEquals(0, gate.badMacs) // a malformed message doesn't count against the code
        }
    }

    @Test
    fun wifiSessionCarriesBulkRecordsAndHandsOutMediaKeys() {
        val rec = Recorder(pinned = TestVectors.publicKey("sR"))
        val bulk = ArrayList<ByteArray>()
        val callbacks = object : SessionCallbacks by rec {
            override fun onBulk(plaintext: ByteArray) {
                bulk += plaintext
            }
        }
        val s = BridgeSession(
            identity = TestVectors.key("sB"),
            pairing = Gate(null),
            callbacks = callbacks,
            random = FixedRandom(hex("session.rand_b")),
            newEphemeral = { TestVectors.key("eB") },
            wifiChannels = true,
        )
        assertNull("no media keys before the handshake", s.takeMediaKeys())
        s.onFrame(hex("session.hello_r_frame"))
        s.onFrame(frame(FrameType.RECORD, hex("session.confirm_r_to_b")))
        assertTrue(rec.established)

        // R→B bulk record, counter 0, sealed with bulk_k_rb.
        val data = FileData(7, 0, "abc".toByteArray()).encode()
        val wire = Records.seal(hex("session.bulk_k_rb"), Proto.DIR_REMOTE_TO_BRIDGE, 0, Proto.CHANNEL_BULK, data)
        s.onFrame(frame(FrameType.BULK, wire))
        assertNull(rec.closedReason)
        assertArrayEquals(data, bulk.single())

        // Our bulk records start at counter 0 with bulk_k_br: the vector's record.
        val vector = TestVectors.json.getJSONObject("bulk_file_data")
        val out = s.sealBulk(Hex.decode(vector.getString("plaintext")))!!
        assertEquals(vector.getString("frame"), Hex.encode(out))

        val (seal, open) = s.takeMediaKeys()!!
        assertEquals(TestVectors.string("session.media_k_br"), Hex.encode(seal))
        assertEquals(TestVectors.string("session.media_k_rb"), Hex.encode(open))
        assertNull("handed out once", s.takeMediaKeys())

        // A replayed bulk record closes the connection, like on the control channel.
        s.onFrame(frame(FrameType.BULK, wire))
        assertNotNull(rec.closedReason)
    }

    @Test
    fun bulkFrameOutsideWifiCloses() {
        val rec = Recorder(pinned = TestVectors.publicKey("sR"))
        val s = session(Gate(null), rec)
        s.onFrame(hex("session.hello_r_frame"))
        s.onFrame(frame(FrameType.RECORD, hex("session.confirm_r_to_b")))
        assertTrue(rec.established)
        assertNull(s.sealBulk(byteArrayOf(1)))
        assertNull(s.takeMediaKeys())
        val wire = Records.seal(hex("session.bulk_k_rb"), Proto.DIR_REMOTE_TO_BRIDGE, 0, Proto.CHANNEL_BULK, byteArrayOf(1))
        s.onFrame(frame(FrameType.BULK, wire))
        assertNotNull(rec.closedReason)
    }

    private fun assertPairFail(rec: Recorder, reason: String) {
        val f = Frame.decode(rec.frames.single())
        assertEquals(FrameType.PAIR_FAIL, f.type)
        val json = JSONObject(String(f.body))
        assertEquals("pair_fail", json.getString("t"))
        assertEquals(0, json.getInt("v"))
        assertEquals(reason, json.getString("reason"))
        assertNotNull(rec.closedReason)
    }
}
