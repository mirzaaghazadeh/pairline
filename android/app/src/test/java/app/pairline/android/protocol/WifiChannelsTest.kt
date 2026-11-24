package app.pairline.android.protocol

import app.pairline.android.protocol.TestVectors.hex
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** The M5 additions in protocol/test-vectors/v0.json: media replay window, bulk file data, the Wi-Fi pair link. */
class WifiChannelsTest {
    private val v = TestVectors.json

    @Test
    fun mediaReplayWindowMatchesTheVectors() {
        val w = v.getJSONObject("media_replay_window")
        assertEquals(Proto.MEDIA_REPLAY_WINDOW, w.getInt("size"))
        val opener = MediaOpener(hex("session.${w.getString("key")}"), w.getInt("direction"))
        val steps = w.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val result = opener.open(Hex.decode(step.getString("wire")))
            val counter = step.getLong("counter")
            if (step.getBoolean("accept")) {
                assertArrayEquals("step $i (counter $counter)", Hex.decode(step.getString("plaintext_hex")), result)
            } else {
                assertNull("step $i (counter $counter) must be dropped", result)
            }
        }
    }

    @Test
    fun mediaSealerNumbersEveryDatagramFromZero() {
        val key = hex("session.media_k_rb")
        val sealer = MediaSealer(key.copyOf(), Proto.DIR_REMOTE_TO_BRIDGE)
        // The vector's R→B probe is counter 0 with an empty plaintext.
        val probe = v.getJSONArray("records").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("key") == "media_k_rb" }
        }
        assertEquals(probe.getString("wire"), Hex.encode(sealer.seal(ByteArray(0))!!))
        val opener = MediaOpener(key, Proto.DIR_REMOTE_TO_BRIDGE)
        val second = sealer.seal(ByteArray(Proto.MEDIA_MAX_PLAINTEXT) { 7 })!!
        assertEquals(1L, Records.counterOf(second))
        assertEquals(Proto.MEDIA_MAX_PLAINTEXT + Proto.RECORD_OVERHEAD, second.size)
        assertEquals(Proto.MEDIA_MAX_PLAINTEXT, opener.open(second)!!.size)
        assertNull("a replay is dropped", opener.open(second))
    }

    @Test
    fun mediaOpenerDropsOversizedAndShortDatagrams() {
        val opener = MediaOpener(hex("session.media_k_br"), Proto.DIR_BRIDGE_TO_REMOTE)
        assertNull(opener.open(ByteArray(Proto.RECORD_OVERHEAD - 1)))
        assertNull(opener.open(ByteArray(Proto.RECORD_OVERHEAD + Proto.MEDIA_MAX_PLAINTEXT + 1)))
        // Wrong channel key (bulk instead of media) never authenticates.
        val bulk = MediaSealer(hex("session.bulk_k_br"), Proto.DIR_BRIDGE_TO_REMOTE).seal(byteArrayOf(1))!!
        assertNull(opener.open(bulk))
    }

    @Test
    fun replayWindowEdges() {
        val w = ReplayWindow()
        assertTrue(w.mayAccept(0))
        w.mark(0)
        assertFalse(w.mayAccept(0))
        w.mark(64)
        assertFalse("0 has left the 64-record window", w.mayAccept(0))
        assertTrue(w.mayAccept(1))
        w.mark(1000)
        assertTrue(w.mayAccept(937))
        assertFalse(w.mayAccept(936))
        assertFalse(w.mayAccept(1000))
        assertTrue(w.mayAccept(1001))
    }

    @Test
    fun bulkFileData() {
        val b = v.getJSONObject("bulk_file_data")
        val data = b.getString("data_utf8").toByteArray(Charsets.UTF_8)
        val plaintext = FileData(b.getLong("fid"), b.getLong("offset"), data).encode()
        assertEquals(b.getString("plaintext"), Hex.encode(plaintext))
        assertArrayEquals(plaintext, FileData.encode(b.getLong("fid"), b.getLong("offset"), data + ByteArray(5), data.size))
        val decoded = FileData.decode(plaintext)!!
        assertEquals(b.getLong("fid"), decoded.fid)
        assertEquals(b.getLong("offset"), decoded.offset)
        assertArrayEquals(data, decoded.data)

        val record = v.getJSONArray("records").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("key") == "bulk_k_br" }
        }
        val frame = Frame(FrameType.BULK, Hex.decode(record.getString("wire"))).encode()
        assertEquals(b.getString("frame"), Hex.encode(frame))
        assertEquals(b.getString("tcp"), Hex.encode(TcpFraming.encode(frame)))
        assertArrayEquals(frame, TcpFraming.read(ByteArrayInputStream(Hex.decode(b.getString("tcp")))))
        assertEquals(FrameType.BULK, Frame.decode(frame).type)

        assertNull("other first bytes are reserved", FileData.decode(byteArrayOf(2) + plaintext.copyOfRange(1, plaintext.size)))
        assertNull("no data", FileData.decode(plaintext.copyOf(13)))
    }

    @Test
    fun pairLinkWithWifiParameters() {
        val p = v.getJSONObject("pair_uri_wifi")
        val uri = PairingUri.parse(p.getString("uri"))!!
        val wifi = uri.wifi!!
        assertEquals(listOf("192.168.1.23", "fd12:3456:789a::1"), wifi.addresses)
        assertEquals(listOf(p.getJSONArray("ip").getString(0), p.getJSONArray("ip").getString(1)), wifi.addresses)
        assertEquals(p.getInt("port"), wifi.port)
        assertEquals(p.getString("ssid"), wifi.ssid)
        assertEquals(p.getString("pw"), wifi.password)
        // We write exactly the same link.
        assertEquals(p.getString("uri"), uri.encode())
        // The plain link (no Wi-Fi) still parses and has no Wi-Fi part.
        assertNull(PairingUri.parse(TestVectors.string("pairing.qr_uri"))!!.wifi)
        assertEquals(TestVectors.string("pairing.qr_uri"), PairingUri.parse(TestVectors.string("pairing.qr_uri"))!!.encode())
    }

    @Test
    fun wifiMessages() {
        val offer = JSONObject(Messages.wifiOffer(WifiHint(listOf("10.0.0.2"), 47820, "Net", "pw"), "5").toString())
        assertEquals("wifi.offer", offer.getString("t"))
        assertEquals("10.0.0.2", offer.getJSONArray("ip").getString(0))
        assertEquals(47820, offer.getInt("port"))
        assertEquals("Net", offer.getString("ssid"))
        assertEquals("pw", offer.getString("password"))
        assertEquals("5", offer.getString("band"))
        val none = JSONObject(Messages.wifiOffer(null, null).toString())
        assertEquals(0, none.getJSONArray("ip").length())
        for (field in listOf("port", "ssid", "password", "band")) assertTrue(field, none.has(field) && none.isNull(field))
        val media = JSONObject(Messages.wifiMedia(47820).toString())
        assertEquals("wifi.media", media.getString("t"))
        assertEquals(47820, media.getInt("port"))
        assertTrue(JSONObject(Messages.wifiMedia(null).toString()).isNull("port"))
        val file = JSONObject(Messages.fileOffer(7, "a.txt", 3, "text/plain").toString())
        assertEquals(listOf("file.offer", 7, "a.txt", 3, "text/plain"), listOf("t", "fid", "name", "size", "type").map(file::get))
    }
}
