package app.pairline.android.link

import app.pairline.android.protocol.Frame
import app.pairline.android.protocol.FrameType
import app.pairline.android.protocol.Hex
import app.pairline.android.protocol.LinkMediaPackets
import app.pairline.android.protocol.LinkMediaSealer
import app.pairline.android.protocol.Proto
import app.pairline.android.protocol.RecordOpener
import app.pairline.android.protocol.TestVectors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Call audio inside the Bluetooth session (D-072): batching, dropping late audio, counter order. */
class LinkMediaChannelTest {
    private class FakeConnection(override val pullsMedia: Boolean) : LinkConnection {
        override val kind = TransportKind.BLUETOOTH
        override val label = "test"
        val sent = ArrayList<ByteArray>()
        var ready = 0
        override fun send(frame: ByteArray) {
            sent += frame
        }
        override fun close() {}
        override fun mediaReady(source: MediaFrameSource) {
            ready++
        }
    }

    private val key = TestVectors.hex("session.link_media_k_br")

    private fun channel(connection: LinkConnection) =
        LinkMediaChannel(connection, LinkMediaSealer(key.copyOf(), Proto.DIR_BRIDGE_TO_REMOTE))

    private fun packet(sequence: Int, size: Int = 80) =
        ByteArray(3 + size).also {
            it[0] = 2
            it[1] = (sequence ushr 8).toByte()
            it[2] = sequence.toByte()
        }

    /** What the iPhone gets out of a frame: the counter and the packets in it. */
    private fun open(opener: RecordOpener, frame: ByteArray): Pair<Long, List<ByteArray>> {
        val f = Frame.decode(frame)
        assertEquals(FrameType.MEDIA, f.type)
        val (counter, plaintext) = opener.open(f.body)
        return counter to LinkMediaPackets.unpack(plaintext)!!
    }

    @Test
    fun aPullingConnectionGetsEverythingWaitingInOneFrame() {
        val connection = FakeConnection(pullsMedia = true)
        val c = channel(connection)
        repeat(3) { c.send(packet(it)) }
        assertEquals("told each time, sends nothing itself", 3, connection.ready)
        assertTrue(connection.sent.isEmpty())

        val opener = RecordOpener(key.copyOf(), Proto.DIR_BRIDGE_TO_REMOTE, Proto.CHANNEL_LINK_MEDIA)
        val (counter, packets) = open(opener, c.nextFrame()!!)
        assertEquals(0L, counter)
        assertEquals(listOf(0, 1, 2), packets.map { it[2].toInt() })
        assertNull("nothing left", c.nextFrame())

        c.send(packet(3))
        val (next, more) = open(opener, c.nextFrame()!!)
        assertEquals(1L, next)
        assertEquals(1, more.size)
        assertEquals(2, c.framesSent)
        assertEquals(4, c.packetsSent)
    }

    @Test
    fun audioOlderThan200msIsDropped() {
        val c = channel(FakeConnection(pullsMedia = true))
        repeat(15) { c.send(packet(it)) }
        assertEquals(5, c.packetsDropped)
        val opener = RecordOpener(key.copyOf(), Proto.DIR_BRIDGE_TO_REMOTE, Proto.CHANNEL_LINK_MEDIA)
        val (_, packets) = open(opener, c.nextFrame()!!)
        assertEquals("the newest 10 are kept", (5 until 15).toList(), packets.map { it[2].toInt() })
    }

    @Test
    fun aFrameNeverGoesOver1200Bytes() {
        val c = channel(FakeConnection(pullsMedia = true))
        repeat(LinkMediaChannel.MAX_WAITING) { c.send(packet(it, size = 380)) }
        val opener = RecordOpener(key.copyOf(), Proto.DIR_BRIDGE_TO_REMOTE, Proto.CHANNEL_LINK_MEDIA)
        var total = 0
        var frames = 0
        while (true) {
            val frame = c.nextFrame() ?: break
            val (_, packets) = open(opener, frame)
            assertTrue(packets.sumOf { 2 + it.size } <= Proto.MEDIA_MAX_PLAINTEXT)
            total += packets.size
            frames++
        }
        assertEquals(LinkMediaChannel.MAX_WAITING, total)
        assertEquals("three 385-byte packets per frame", 4, frames)
    }

    @Test
    fun otherConnectionsGetEachFrameAtOnceInOrder() {
        val connection = FakeConnection(pullsMedia = false)
        val c = channel(connection)
        repeat(3) { c.send(packet(it)) }
        assertEquals(0, connection.ready)
        val opener = RecordOpener(key.copyOf(), Proto.DIR_BRIDGE_TO_REMOTE, Proto.CHANNEL_LINK_MEDIA)
        assertEquals(listOf(0L, 1L, 2L), connection.sent.map { open(opener, it).first })
    }

    @Test
    fun receivedRecordsAreSplitIntoPackets() {
        val c = channel(FakeConnection(pullsMedia = true))
        val got = ArrayList<ByteArray>()
        c.onPacket = { got += it }
        val v = TestVectors.json.getJSONObject("link_media")
        c.receive(Hex.decode(v.getString("plaintext_b_to_r")))
        assertEquals(2, got.size)
        assertArrayEquals(Hex.decode(v.getJSONArray("packets_b_to_r").getString(1)), got[1])
        c.receive(byteArrayOf(0, 9, 1)) // malformed: ignored
        assertEquals(2, got.size)
    }

    @Test
    fun aClosedChannelSendsNothing() {
        val connection = FakeConnection(pullsMedia = false)
        val c = channel(connection)
        c.close()
        assertFalse(c.isOpen)
        c.send(packet(0))
        assertTrue(connection.sent.isEmpty())
        assertNull(c.nextFrame())
    }
}
