package app.pairline.android.audio

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 20 ms frames of 16 kHz mono PCM (640 bytes) in, Opus packets out (PROTOCOL.md, "Call audio", D-072). */
interface VoiceEncoder : AutoCloseable {
    /** Every packet the codec has ready after taking [frame]: usually one, none while it warms up. */
    fun encode(frame: ByteArray): List<ByteArray>
}

/** Opus packets of 20 ms in, 640-byte frames of 16 kHz mono PCM out. */
interface VoiceDecoder : AutoCloseable {
    /** The packet's 20 ms, or null if the codec gave nothing back (the jitter buffer then conceals it). */
    fun decode(packet: ByteArray): ByteArray?
}

/**
 * Opus with Android's own codecs (`MediaCodec`): no library of ours. Android 10 added the Opus encoder; the decoder is
 * older. Used for call audio inside the Bluetooth session, where raw PCM doesn't fit (D-072). Each instance belongs
 * to one thread.
 */
object Opus {
    const val SAMPLE_RATE: Int = AudioPackets.SAMPLE_RATE
    const val BITRATE: Int = 32_000
    private const val FRAME_US = 20_000L

    /** Wait this long for the codec's output after giving it a frame (it works on its own thread). */
    private const val OUTPUT_WAIT_US = 10_000L
    private const val INPUT_WAIT_US = 5_000L
    private const val MAX_INPUT_BYTES = 4_096

    private fun encoderFormat(): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            // A whole 20 ms frame must fit one input buffer, whatever the codec's default size.
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }

    /** RFC 7845's identification header for a mono stream: no pre-skip, 16 kHz input, no gain, mapping family 0. */
    private fun opusHead(): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("OpusHead".toByteArray(Charsets.US_ASCII))
        put(1) // version
        put(1) // channels
        putShort(0) // pre-skip
        putInt(SAMPLE_RATE)
        putShort(0) // output gain
        put(0) // mapping family
    }.array()

    private fun nanos(value: Long): ByteBuffer =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).apply { flip() }

    private fun decoderFormat(): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
            setByteBuffer("csd-1", nanos(0)) // codec delay
            setByteBuffer("csd-2", nanos(80_000_000)) // seek pre-roll
        }

    private val codecs by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS) }

    /** True if this phone has both halves. Checked once. */
    val available: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            runCatching {
                codecs.findEncoderForFormat(encoderFormat()) != null && codecs.findDecoderForFormat(decoderFormat()) != null
            }.getOrDefault(false)
    }

    fun encoder(): VoiceEncoder = Encoder()

    fun decoder(): VoiceDecoder = Decoder()

    private fun open(format: MediaFormat, encode: Boolean): MediaCodec {
        val name = (if (encode) codecs.findEncoderForFormat(format) else codecs.findDecoderForFormat(format))
            ?: throw IOException("this phone has no Opus ${if (encode) "encoder" else "decoder"}")
        val codec = MediaCodec.createByCodecName(name)
        try {
            codec.configure(format, null, null, if (encode) MediaCodec.CONFIGURE_FLAG_ENCODE else 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw IOException("couldn't start $name: ${e.message}", e)
        }
        return codec
    }

    private abstract class Base(format: MediaFormat, encode: Boolean) : AutoCloseable {
        protected val codec: MediaCodec = open(format, encode)
        private val info = MediaCodec.BufferInfo()
        private var pts = 0L

        /** Hands [bytes] to the codec; false if it had no free input buffer in time (the frame is then lost). */
        protected fun feed(bytes: ByteArray): Boolean {
            val index = codec.dequeueInputBuffer(INPUT_WAIT_US)
            if (index < 0) return false
            val buffer = codec.getInputBuffer(index) ?: return false
            buffer.clear()
            if (buffer.remaining() < bytes.size) {
                codec.queueInputBuffer(index, 0, 0, pts, 0)
                return false
            }
            buffer.put(bytes)
            codec.queueInputBuffer(index, 0, bytes.size, pts, 0)
            pts += FRAME_US
            return true
        }

        /** Output buffers ready now (waiting a little for the first), without codec-config buffers. */
        protected fun drain(onFormat: (MediaFormat) -> Unit = {}): List<ByteArray> {
            val out = ArrayList<ByteArray>(1)
            var wait = OUTPUT_WAIT_US
            while (true) {
                val index = codec.dequeueOutputBuffer(info, wait)
                when {
                    index >= 0 -> {
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0) {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                out += ByteArray(info.size).also { buffer.get(it) }
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (out.isNotEmpty()) wait = 0
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                    else -> return out // INFO_TRY_AGAIN_LATER (or the deprecated buffers-changed)
                }
            }
        }

        override fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private class Encoder : Base(encoderFormat(), encode = true), VoiceEncoder {
        override fun encode(frame: ByteArray): List<ByteArray> {
            feed(frame)
            return drain()
        }
    }

    private class Decoder : Base(decoderFormat(), encode = false), VoiceDecoder {
        /** What the decoder outputs (Android's decodes Opus at 48 kHz); converted to 16 kHz mono. */
        private var rate = 48_000
        private var channels = 1

        override fun decode(packet: ByteArray): ByteArray? {
            if (!feed(packet)) return null
            val pcm = drain { format ->
                rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            }.lastOrNull() ?: return null
            var samples = Pcm.toShorts(pcm)
            if (channels > 1) samples = ShortArray(samples.size / channels) { samples[it * channels] }
            val frame = Pcm.toBytes(Pcm.resample(samples, rate, SAMPLE_RATE))
            return if (frame.size == AudioPackets.FRAME_BYTES) frame else frame.copyOf(AudioPackets.FRAME_BYTES)
        }
    }
}
