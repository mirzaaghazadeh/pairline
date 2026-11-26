package app.pairline.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFramesTest {
    private fun frame(fill: Int) = Pcm.toBytes(ShortArray(AudioPackets.FRAME_SAMPLES) { fill.toShort() })

    @Test
    fun packetRoundTrip() {
        val f = frame(1234)
        val packet = AudioPackets.encode(0xBEEF, f)
        assertEquals(AudioPackets.HEADER_BYTES + AudioPackets.FRAME_BYTES, packet.size)
        assertEquals(0x01, packet[0].toInt())
        assertEquals(0xBE.toByte(), packet[1])
        assertEquals(0xEF.toByte(), packet[2])
        val (seq, decoded) = AudioPackets.decode(packet)!!
        assertEquals(0xBEEF, seq)
        assertArrayEquals(f, decoded)
    }

    @Test
    fun malformedPacketsAreDropped() {
        assertNull(AudioPackets.decode(ByteArray(10)))
        val wrongKind = AudioPackets.encode(1, frame(0)).also { it[0] = 0x7F }
        assertNull(AudioPackets.decode(wrongKind))
    }

    @Test
    fun opusPacketsCarryTheirKindAndSequence() {
        val opus = ByteArray(80) { it.toByte() }
        val packet = AudioPackets.encodeOpus(0xFFFF, opus)
        assertEquals(AudioPackets.KIND_OPUS, packet[0].toInt())
        val (seq, back) = AudioPackets.decodeOpus(packet)!!
        assertEquals(0xFFFF, seq)
        assertArrayEquals(opus, back)
        assertNull("not PCM", AudioPackets.decode(packet))
        assertNull("not Opus", AudioPackets.decodeOpus(AudioPackets.encode(1, frame(0))))
        assertNull("empty", AudioPackets.decodeOpus(byteArrayOf(2, 0, 1)))
        assertNull("too long", AudioPackets.decodeOpus(byteArrayOf(2, 0, 1) + ByteArray(AudioPackets.MAX_OPUS_BYTES + 1)))
    }

    @Test
    fun viaDefaultsToWifi() {
        assertEquals(CallAudioRelay.Via.WIFI, CallAudioRelay.Via.of(null))
        assertEquals(CallAudioRelay.Via.WIFI, CallAudioRelay.Via.of("wifi"))
        assertEquals(CallAudioRelay.Via.LINK, CallAudioRelay.Via.of("link"))
        assertNull(CallAudioRelay.Via.of("carrier pigeon"))
    }

    @Test
    fun jitterBufferWaitsThenPlaysInOrder() {
        val jb = JitterBuffer(target = 3)
        jb.put(11, frame(11))
        assertNull(jb.take()) // still buffering
        jb.put(10, frame(10))
        jb.put(12, frame(12))
        assertArrayEquals(frame(10), jb.take())
        assertArrayEquals(frame(11), jb.take())
        assertArrayEquals(frame(12), jb.take())
    }

    @Test
    fun lostFrameIsConcealedThenSilenced() {
        val jb = JitterBuffer(target = 1)
        jb.put(0, frame(1000))
        assertArrayEquals(frame(1000), jb.take())
        val concealed = jb.take() // frame 1 never arrived
        assertNotNull(concealed)
        assertEquals(500, Pcm.toShorts(concealed!!)[0].toInt())
        assertEquals(250, Pcm.toShorts(jb.take()!!)[0].toInt())
        assertNull(jb.take()) // two concealed frames at most
    }

    @Test
    fun lateFramesAreIgnoredAndSequenceWraps() {
        val jb = JitterBuffer(target = 2)
        jb.put(65535, frame(1))
        jb.put(0, frame(2))
        assertArrayEquals(frame(1), jb.take())
        assertArrayEquals(frame(2), jb.take())
        jb.put(65535, frame(9)) // already played: dropped
        jb.put(1, frame(3))
        assertArrayEquals(frame(3), jb.take())
    }

    @Test
    fun burstAfterStallDoesNotBuildDelay() {
        val jb = JitterBuffer(target = 3, capacity = 6)
        for (s in 0 until 3) jb.put(s, frame(s))
        jb.take()
        for (s in 3 until 20) jb.put(s, frame(s))
        // Skipped ahead to the newest three frames.
        assertArrayEquals(frame(17), jb.take())
    }

    @Test
    fun extraDelayDrainsThroughQuietFramesOnly() {
        val jb = JitterBuffer(target = 3)
        // A burst: 15 frames waiting, frames 1-3 quiet, the rest speech.
        for (s in 0 until 15) jb.put(s, frame(if (s in 1..3) 0 else 3000))
        assertArrayEquals(frame(3000), jb.take()) // frame 0 (speech): never skipped
        assertArrayEquals(frame(0), jb.take()) // frame 1 skipped, frame 2 played
        assertArrayEquals(frame(3000), jb.take()) // frame 3 skipped, frame 4 played
        assertEquals(2, jb.skipped)
        // From here on only speech waits: every frame plays, in order.
        for (s in 5 until 15) assertArrayEquals(frame(3000), jb.take())
        assertEquals(2, jb.skipped)
    }

    @Test
    fun resampleKeepsDurationAndLevel() {
        val tone48 = ShortArray(960) { 1000 }
        val down = Pcm.resample(tone48, 48_000, 16_000)
        assertEquals(320, down.size)
        assertTrue(down.all { it.toInt() == 1000 })
        val up = Pcm.resample(ShortArray(160) { 2000 }, 8_000, 16_000)
        assertEquals(320, up.size)
        assertTrue(up.all { it.toInt() == 2000 })
    }

    @Test
    fun rmsTellsSilenceFromSound() {
        assertEquals(0, Pcm.rms(frame(0)))
        assertEquals(3000, Pcm.rms(frame(3000)))
    }
}
