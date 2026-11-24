package app.pairline.android.audio

/**
 * Call-audio packets on the Wi-Fi media channel (PROTOCOL.md, "Call audio"). One packet is one 20 ms frame:
 *
 *     kind (1 byte) ‖ sequence (2 bytes, big-endian, wraps) ‖ samples
 *
 * Kind [KIND_PCM16]: 16-bit little-endian mono PCM at 16 kHz, 320 samples (640 bytes). Receivers drop unknown kinds.
 * The media channel encrypts every packet (AES-256-GCM, channel 1); this layer only frames audio.
 */
object AudioPackets {
    const val KIND_PCM16: Int = 0x01
    const val SAMPLE_RATE: Int = 16_000
    const val FRAME_SAMPLES: Int = SAMPLE_RATE / 50
    const val FRAME_BYTES: Int = FRAME_SAMPLES * 2
    const val HEADER_BYTES: Int = 3

    fun encode(sequence: Int, frame: ByteArray): ByteArray {
        require(frame.size == FRAME_BYTES) { "a frame is $FRAME_BYTES bytes" }
        val out = ByteArray(HEADER_BYTES + FRAME_BYTES)
        out[0] = KIND_PCM16.toByte()
        out[1] = (sequence ushr 8 and 0xFF).toByte()
        out[2] = (sequence and 0xFF).toByte()
        frame.copyInto(out, HEADER_BYTES)
        return out
    }

    /** The sequence number and frame, or null for anything that isn't a well-formed audio packet. */
    fun decode(packet: ByteArray): Pair<Int, ByteArray>? {
        if (packet.size != HEADER_BYTES + FRAME_BYTES || (packet[0].toInt() and 0xFF) != KIND_PCM16) return null
        val sequence = (packet[1].toInt() and 0xFF shl 8) or (packet[2].toInt() and 0xFF)
        return sequence to packet.copyOfRange(HEADER_BYTES, packet.size)
    }
}

/**
 * Evens out network jitter: holds a few frames before playing, plays them in sequence order, and covers a lost
 * frame by repeating the last one at half volume (twice at most), then silence. Thread-safe.
 */
class JitterBuffer(
    private val frameBytes: Int = AudioPackets.FRAME_BYTES,
    /** Frames to hold before playing starts: 3 × 20 ms = 60 ms. */
    private val target: Int = 3,
    /**
     * A hard limit of 1 s waiting. Below it, extra delay (a burst after a Wi-Fi stall, or a sender clock running a
     * little fast) drains by skipping quiet frames only ([take]), so no speech is cut; only past it does the buffer jump.
     */
    private val capacity: Int = 50,
) {
    /** Quiet frames skipped to bring the delay back down (for the call's log line). */
    var skipped = 0
        private set
    private val frames = HashMap<Int, ByteArray>()
    private var next = -1
    private var buffering = true
    private var last: ByteArray? = null
    private var misses = 0

    @Synchronized
    fun put(sequence: Int, frame: ByteArray) {
        if (frame.size != frameBytes) return
        val s = sequence and 0xFFFF
        if (next < 0) next = s
        if (buffering) {
            if (diff(s, next) < 0) next = s
        } else if (diff(s, next) < 0) {
            return // arrived after its turn
        }
        frames[s] = frame
        if (buffering && frames.size >= target) buffering = false
        if (frames.size > capacity) {
            // Far behind (the network stalled, then delivered a burst): keep only the newest [target] frames.
            val newest = frames.keys.maxWith { a, b -> diff(a, b) }
            next = (newest - target + 1) and 0xFFFF
            frames.keys.removeAll { diff(it, next) < 0 }
        }
    }

    /** The next 20 ms to play, or null while buffering (play silence). Call once per frame period. */
    @Synchronized
    fun take(): ByteArray? {
        if (buffering) return null
        // More than [DRAIN_ABOVE] frames waiting: drop one quiet frame per call until back near the target. Pauses
        // between words get a little shorter; speech is never cut.
        while (frames.size > target + DRAIN_ABOVE) {
            val waiting = frames[next] ?: break
            if (Pcm.rms(waiting) > QUIET_RMS) break
            frames.remove(next)
            next = (next + 1) and 0xFFFF
            skipped++
            break
        }
        val frame = frames.remove(next)
        next = (next + 1) and 0xFFFF
        if (frame != null) {
            last = frame
            misses = 0
            return frame
        }
        misses++
        if (misses > RESET_AFTER_MISSES) {
            reset()
            return null
        }
        val previous = last ?: return null
        return if (misses <= CONCEAL_FRAMES) Pcm.scale(previous, 0.5f.pow(misses)) else null
    }

    @Synchronized
    fun reset() {
        frames.clear()
        next = -1
        buffering = true
        last = null
        misses = 0
    }

    private fun Float.pow(n: Int): Float {
        var r = 1f
        repeat(n) { r *= this }
        return r
    }

    companion object {
        private const val CONCEAL_FRAMES = 2

        /** Frames above the target before quiet frames are skipped: 4 × 20 ms of slack for ordinary Wi-Fi jitter. */
        private const val DRAIN_ABOVE = 4

        /** Below this level (16-bit RMS, about −46 dBFS) a frame counts as quiet. */
        const val QUIET_RMS = 160

        /** 25 frames (half a second) with nothing: start over and buffer again. */
        private const val RESET_AFTER_MISSES = 25

        /** Signed distance a − b on the 16-bit sequence circle. */
        fun diff(a: Int, b: Int): Int = ((a - b + 32768) and 0xFFFF) - 32768
    }
}

/** 16-bit little-endian mono PCM helpers. */
object Pcm {
    fun toShorts(bytes: ByteArray): ShortArray =
        ShortArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }

    fun toBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = (v shr 8 and 0xFF).toByte()
        }
        return out
    }

    fun scale(bytes: ByteArray, gain: Float): ByteArray {
        val s = toShorts(bytes)
        for (i in s.indices) s[i] = (s[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        return toBytes(s)
    }

    /**
     * Converts between the rates the bridge may capture at (8, 16 and 48 kHz). Downsampling averages each output
     * sample's input span (a simple low-pass that keeps speech clear); upsampling interpolates linearly.
     */
    fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || input.isEmpty()) return input
        val outLength = (input.size.toLong() * to / from).toInt()
        val out = ShortArray(outLength)
        if (to < from) {
            val ratio = from.toDouble() / to
            for (i in 0 until outLength) {
                val start = (i * ratio).toInt()
                val end = minOf(input.size, ((i + 1) * ratio).toInt()).coerceAtLeast(start + 1)
                var sum = 0L
                for (j in start until end) sum += input[j]
                out[i] = (sum / (end - start)).toInt().toShort()
            }
        } else {
            val ratio = from.toDouble() / to
            for (i in 0 until outLength) {
                val pos = i * ratio
                val a = pos.toInt().coerceAtMost(input.size - 1)
                val b = (a + 1).coerceAtMost(input.size - 1)
                val t = pos - a
                out[i] = (input[a] * (1 - t) + input[b] * t).toInt().toShort()
            }
        }
        return out
    }

    /** Root-mean-square level of a frame, 0…32767: tells silence from speech in diagnostics. */
    fun rms(bytes: ByteArray): Int {
        val s = toShorts(bytes)
        if (s.isEmpty()) return 0
        var sum = 0.0
        for (v in s) sum += v.toDouble() * v
        return kotlin.math.sqrt(sum / s.size).toInt()
    }
}
