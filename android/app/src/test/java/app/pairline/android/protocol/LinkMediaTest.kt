package app.pairline.android.protocol

import app.pairline.android.protocol.TestVectors.hex
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Call audio inside the Bluetooth session (D-072): `link_media` in protocol/test-vectors/v0.json. */
class LinkMediaTest {
    private val v = TestVectors.json.getJSONObject("link_media")

    private fun hexList(name: String): List<ByteArray> {
        val a = v.getJSONArray(name)
        return (0 until a.length()).map { Hex.decode(a.getString(it)) }
    }

    @Test
    fun keysComeFromTheSession() {
        val (br, rb) = SessionKeys(hex("session.okm")).linkMedia()
        assertEquals(TestVectors.string("session.link_media_k_br"), Hex.encode(br))
        assertEquals(TestVectors.string("session.link_media_k_rb"), Hex.encode(rb))
    }

    @Test
    fun packsAndUnpacksTheVectorsPackets() {
        val packets = hexList("packets_b_to_r")
        val plaintext = LinkMediaPackets.pack(packets)
        assertEquals(v.getString("plaintext_b_to_r"), Hex.encode(plaintext))
        val back = LinkMediaPackets.unpack(plaintext)!!
        assertEquals(packets.size, back.size)
        packets.zip(back).forEach { (a, b) -> assertArrayEquals(a, b) }
        assertEquals(v.getString("plaintext_r_to_b"), Hex.encode(LinkMediaPackets.pack(hexList("packets_r_to_b"))))
    }

    @Test
    fun malformedRecordsAreIgnored() {
        val good = Hex.decode(v.getString("plaintext_b_to_r"))
        assertNull("a trailing byte", LinkMediaPackets.unpack(good + byteArrayOf(0)))
        assertNull("cut short", LinkMediaPackets.unpack(good.copyOf(good.size - 1)))
        assertNull("a packet below 3 bytes", LinkMediaPackets.unpack(byteArrayOf(0, 2, 2, 0)))
        assertNull("empty", LinkMediaPackets.unpack(ByteArray(0)))
        assertNull("over 1200 bytes", LinkMediaPackets.unpack(ByteArray(1201)))
        assertThrows(IllegalArgumentException::class.java) { LinkMediaPackets.pack(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { LinkMediaPackets.pack(listOf(ByteArray(1199))) }
    }

    @Test
    fun theVectorsFramesOpenAndSeal() {
        val key = v.getString("key_br")
        val frame = Frame.decode(Hex.decode(v.getString("frame_b_to_r")))
        assertEquals(FrameType.MEDIA, frame.type)
        val (counter, plaintext) = Records.open(hex("session.$key"), Proto.DIR_BRIDGE_TO_REMOTE, Proto.CHANNEL_LINK_MEDIA, frame.body)
        assertEquals(v.getLong("counter_b_to_r"), counter)
        assertEquals(v.getString("plaintext_b_to_r"), Hex.encode(plaintext))

        // The iPhone's side: its first frame is counter 0.
        val sealer = LinkMediaSealer(hex("session.link_media_k_rb"), Proto.DIR_REMOTE_TO_BRIDGE)
        assertEquals(v.getString("frame_r_to_b"), Hex.encode(sealer.seal(Hex.decode(v.getString("plaintext_r_to_b")))!!))
        sealer.wipe()
        assertNull("a wiped sealer seals nothing", sealer.seal(byteArrayOf(2, 0, 0)))
    }

    @Test
    fun mustFailEntriesDontOpen() {
        val fails = v.getJSONArray("must_fail")
        for (i in 0 until fails.length()) {
            val f = fails.getJSONObject(i)
            assertThrows(f.getString("why"), ProtocolException::class.java) {
                Records.open(hex("session.${f.getString("key")}"), Proto.DIR_BRIDGE_TO_REMOTE, f.getInt("channel"),
                    Hex.decode(f.getString("wire")))
            }
        }
    }

    @Test
    fun framingOnBleAndTcp() {
        val frame = Hex.decode(v.getString("frame_b_to_r"))
        val fragments = BleFragmenter.fragment(frame, v.getInt("ble_att_payload"))
        val expected = v.getJSONArray("ble_fragments_b_to_r")
        assertEquals(expected.length(), fragments.size)
        fragments.forEachIndexed { i, f -> assertEquals(expected.getString(i), Hex.encode(f)) }
        assertEquals(v.getString("tcp_b_to_r"), Hex.encode(TcpFraming.encode(frame)))
    }

    private class Recorder : SessionCallbacks {
        val media = ArrayList<ByteArray>()
        var closedReason: String? = null
        var established = false
        override fun sendFrame(frame: ByteArray) {}
        override fun close(reason: String) { closedReason = reason }
        override fun pinnedRemoteKey(): ByteArray = TestVectors.publicKey("sR")
        override fun onEstablished() { established = true }
        override fun onMessage(message: JSONObject) {}
        override fun onLinkMedia(plaintext: ByteArray) { media += plaintext }
    }

    private fun established(wifi: Boolean, rec: Recorder) = BridgeSession(
        identity = TestVectors.key("sB"),
        pairing = object : PairingGate {
            override fun claim(): PairingClaim = PairingClaim.Refused(PairFailReason.EXPIRED)
            override fun onBadMac() {}
            override fun onPaired(remotePublicKey: ByteArray) {}
        },
        callbacks = rec,
        random = FixedRandom(hex("session.rand_b")),
        newEphemeral = { TestVectors.key("eB") },
        wifiChannels = wifi,
    ).also { s ->
        assertNull("no sealer before the handshake", s.takeLinkMediaSealer())
        s.onFrame(hex("session.hello_r_frame"))
        s.onFrame(Frame(FrameType.RECORD, hex("session.confirm_r_to_b")).encode())
        assertTrue(rec.established)
    }

    @Test
    fun bluetoothSessionCarriesMediaFrames() {
        val rec = Recorder()
        val s = established(wifi = false, rec)
        val frame = Hex.decode(v.getString("frame_r_to_b"))
        s.onFrame(frame)
        assertNull(rec.closedReason)
        assertEquals(v.getString("plaintext_r_to_b"), Hex.encode(rec.media.single()))

        // Our side seals from counter 0 with link_media_k_br: the vector's record is our fourth.
        val sealer = s.takeLinkMediaSealer()!!
        assertNull("handed out once", s.takeLinkMediaSealer())
        repeat(3) { sealer.seal(byteArrayOf(2, 0, 0)) }
        assertEquals(v.getString("frame_b_to_r"), Hex.encode(sealer.seal(Hex.decode(v.getString("plaintext_b_to_r")))!!))

        // Ordered like the control channel: a replay closes the connection.
        s.onFrame(frame)
        assertNotNull(rec.closedReason)
        assertNull("closing the session wipes the sealer", sealer.seal(byteArrayOf(2, 0, 0)))
    }

    @Test
    fun aForgedMediaFrameCloses() {
        val rec = Recorder()
        val s = established(wifi = false, rec)
        val frame = Hex.decode(v.getString("frame_r_to_b"))
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 1).toByte()
        s.onFrame(frame)
        assertNotNull(rec.closedReason)
        assertTrue(rec.media.isEmpty())
    }

    @Test
    fun mediaFramesDontBelongOnWifiOrBeforeTheHandshake() {
        val rec = Recorder()
        val s = established(wifi = true, rec)
        assertNull("Wi-Fi has the UDP media channel instead", s.takeLinkMediaSealer())
        s.onFrame(Hex.decode(v.getString("frame_r_to_b")))
        assertNotNull(rec.closedReason)

        val early = Recorder()
        BridgeSession(
            identity = TestVectors.key("sB"),
            pairing = object : PairingGate {
                override fun claim(): PairingClaim = PairingClaim.Refused(PairFailReason.EXPIRED)
                override fun onBadMac() {}
                override fun onPaired(remotePublicKey: ByteArray) {}
            },
            callbacks = early,
        ).onFrame(Hex.decode(v.getString("frame_r_to_b")))
        assertNotNull(early.closedReason)
    }
}
