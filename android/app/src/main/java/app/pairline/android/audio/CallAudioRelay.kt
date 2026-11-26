package app.pairline.android.audio

import android.media.AudioManager
import android.os.ParcelFileDescriptor
import app.pairline.android.AppPrefs
import app.pairline.android.log.Log
import app.pairline.android.privileged.IPrivilegedService
import app.pairline.android.privileged.PrivilegedManager
import app.pairline.android.privileged.PrivilegedService
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.RequestException
import app.pairline.android.link.MediaChannel
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Relays a call's audio between this phone and the iPhone ("Talk on iPhone", D-047):
 * - the caller's voice is captured through the privileged service and sent to the iPhone as 20 ms packets;
 * - packets from the iPhone's microphone go through a [JitterBuffer] and are fed into the call's uplink.
 *
 * Audio travels only between the two phones, encrypted: as PCM on the Wi-Fi link's media channel, or as Opus inside
 * the Bluetooth session (D-072), whichever the iPhone asks for. It's never stored or logged; the log gets only counts
 * and levels. Nothing starts unless the iPhone asks during an active call and the user turned on "Talk on iPhone".
 */
class CallAudioRelay(
    private val privileged: PrivilegedManager,
    private val prefs: AppPrefs,
    private val emit: (JSONObject) -> Unit,
    /** Android's current audio mode (`AudioManager.getMode`). */
    private val audioMode: () -> Int = { AudioManager.MODE_IN_CALL },
    /** The link media channel of the Bluetooth (or debug TCP) session with this token, if it's still up (D-072). */
    private val linkMedia: (Long) -> PacketTransport? = { null },
    /** This phone can encode and decode Opus (Android 10+, checked once). */
    private val opusAvailable: () -> Boolean = { Opus.available },
) {
    /** A media channel: Wi-Fi's (lossy, unordered) or the Bluetooth session's. [send] never blocks. */
    interface PacketTransport {
        val isOpen: Boolean
        fun send(packet: ByteArray)
        fun setReceiver(receiver: ((ByteArray) -> Unit)?)
    }

    /** Where a call's audio travels (`call.media.start` `via`). */
    enum class Via(val wire: String) {
        WIFI("wifi"),
        LINK("link");

        companion object {
            fun of(wire: String?): Via? = when (wire) {
                null, WIFI.wire -> WIFI
                LINK.wire -> LINK
                else -> null
            }
        }
    }

    private val lock = Any()
    private var session: Session? = null

    @Volatile
    private var transport: PacketTransport? = null

    /** Called when capabilities may have changed (e.g. talking turned out not to work here). */
    var onCapsChanged: (() -> Unit)? = null

    /** A relay inside the Bluetooth session started (true) or ended (false): the link asks for its fastest setting. */
    var onLinkAudio: ((Boolean) -> Unit)? = null

    init {
        // Before the helper made its recorders as itself (D-065), interception failed on some phones and the relay
        // "learned" a silent fallback and counted talk failures. Start over once.
        if (prefs.audioLearningEpoch < LEARNING_EPOCH) {
            resetLearning()
            prefs.audioLearningEpoch = LEARNING_EPOCH
        }
    }

    /** The caller's voice can be relayed to the iPhone. */
    val canListen: Boolean
        get() = prefs.talkOnIphone &&
            privileged.state.value == PrivilegedManager.State.READY &&
            privileged.probe.value?.canListen == true

    /** …and the iPhone's microphone can be fed into the call. */
    val canTalk: Boolean
        get() = canListen && privileged.probe.value?.canTalk == true && prefs.audioTalkFailures < MAX_TALK_FAILURES

    /** …and the audio can also travel inside the Bluetooth session, as Opus (`call.media.link`, D-072). */
    val canLink: Boolean
        get() = canListen && opusAvailable()

    /** The link layer sets the Wi-Fi media channel while a Wi-Fi link is up, and null when it goes away. */
    fun setTransport(t: PacketTransport?) {
        transport = t
        if (t == null) stopIf("media channel closed") { it.via == Via.WIFI }
    }

    /** The Bluetooth (or debug TCP) session [token] ended: a relay inside it stops. */
    fun linkClosed(token: Long) = stopIf("Bluetooth session closed") { it.via == Via.LINK && it.token == token }

    /**
     * `call.media.start`: [callId] must be an active call (checked by the caller). [token] is the session the request
     * came on, which carries the audio when [via] is [Via.LINK]. Blocking; not on the main thread.
     */
    fun start(callId: String, wantTalk: Boolean, via: Via = Via.WIFI, token: Long = 0) {
        if (!prefs.talkOnIphone) throw RequestException(ErrorCode.UNSUPPORTED, "Talk on iPhone is off on the bridge")
        val service = privileged.service
            ?: throw RequestException(ErrorCode.UNSUPPORTED, "Pairline's helper isn't running on the bridge")
        if (!canListen) throw RequestException(ErrorCode.UNSUPPORTED, "this phone doesn't allow capturing call audio")
        val t = when (via) {
            Via.WIFI -> transport?.takeIf { it.isOpen }
                ?: throw RequestException(ErrorCode.UNSUPPORTED, "call audio needs the Wi-Fi link")
            Via.LINK -> {
                if (!opusAvailable()) throw RequestException(ErrorCode.UNSUPPORTED, "this phone has no Opus codec")
                linkMedia(token)?.takeIf { it.isOpen }
                    ?: throw RequestException(ErrorCode.UNSUPPORTED, "call audio over Bluetooth needs a Bluetooth session")
            }
        }
        synchronized(lock) {
            session?.let { current ->
                if (current.callId == callId && current.via == via && current.token == token) return
                session = null
                current.close("replaced")
            }
            val s = Session(callId, wantTalk && canTalk, service, t, via, token)
            session = s
            try {
                s.open()
            } catch (e: Exception) {
                session = null
                s.close("failed")
                state(callId, "failed", listen = false, talk = false, reason = e.message)
                throw e as? RequestException ?: RequestException(ErrorCode.FAILED, "call audio failed: ${e.message}")
            }
            state(callId, "active", listen = true, talk = s.talking, reason = s.talkProblem, via = via)
        }
    }

    /** `call.media.stop`, the call ending, or the media channel closing. [callId] null stops whatever runs. */
    fun stop(callId: String?, reason: String) = stopIf(reason) { callId == null || it.callId == callId }

    private fun stopIf(reason: String, matches: (Session) -> Boolean) {
        synchronized(lock) {
            val s = session ?: return
            if (!matches(s)) return
            session = null
            s.close(reason)
            state(s.callId, "stopped", listen = false, talk = false, reason = reason)
        }
    }

    /**
     * `call.media.feedback` after a relayed call: tries the other capture source next time if the iPhone couldn't
     * hear the caller, and leaves the bridge's microphone on (then gives up on talking) if the caller couldn't hear the
     * iPhone. This is how the relay settles on what works on this phone.
     */
    fun feedback(heard: Boolean?, heardMe: Boolean?) {
        if (heard == false && prefs.audioSilenceEarpiece) {
            // On a phone whose capture follows the call volume, silencing the earpiece also silenced the relay.
            prefs.audioSilenceEarpiece = false
            Log.i(TAG, "caller not heard on the iPhone: next call leaves this phone's earpiece on")
        } else if (heard == false) {
            // The next source in the phone's order, round and round.
            val order = captureOrder()
            val current = order.indexOf(prefs.audioCaptureSource).coerceAtLeast(0)
            prefs.audioCaptureSource = order[(current + 1) % order.size]
            Log.i(TAG, "caller not heard on the iPhone: next call captures from source ${prefs.audioCaptureSource}")
        }
        when (heardMe) {
            false -> if (prefs.audioMuteBridgeMic) {
                prefs.audioMuteBridgeMic = false
                Log.i(TAG, "iPhone not heard by the caller: next call keeps this phone's microphone on")
            } else {
                prefs.audioTalkFailures = prefs.audioTalkFailures + 1
                Log.i(TAG, "iPhone not heard by the caller again (${prefs.audioTalkFailures})")
            }
            true -> prefs.audioTalkFailures = 0
            null -> Unit
        }
        onCapsChanged?.invoke()
    }

    /** Turns relaying back to its first-try settings (Settings › Talk on iPhone › Reset). */
    fun resetLearning() {
        prefs.audioCaptureSource = 0
        prefs.audioMuteBridgeMic = false
        prefs.audioSilenceEarpiece = true
        prefs.audioTalkFailures = 0
        onCapsChanged?.invoke()
    }

    /**
     * The capture sources this phone may offer, best first: Android 13's interception API (made for exactly this,
     * where the phone maker enabled it), then the caller's side of the call, then both sides mixed.
     */
    private fun captureOrder(): List<Int> = buildList {
        if (privileged.probe.value?.interceptable == true) add(PrivilegedService.SOURCE_INTERCEPT)
        add(SOURCE_DOWNLINK)
        add(SOURCE_CALL)
    }

    private fun sourceName(source: Int): String = when (source) {
        PrivilegedService.SOURCE_INTERCEPT -> "interception"
        SOURCE_DOWNLINK -> "downlink"
        SOURCE_CALL -> "voice call"
        else -> "source $source"
    }

    /**
     * Waits (up to [CALL_MODE_WAIT_MS]) until Android has put the phone in its in-call audio mode. Right after a call is
     * dialed or answered the mode can still be "normal", and capture then fails or records nothing.
     */
    private fun awaitCallAudioMode(deadline: Long): Boolean {
        while (true) {
            if (audioMode() in CALL_MODES) return true
            if (System.nanoTime() > deadline) return false
            Thread.sleep(POLL_MS)
        }
    }

    private fun state(callId: String, state: String, listen: Boolean, talk: Boolean, reason: String?, via: Via? = null) {
        emit(
            JSONObject()
                .put("t", Msg.CALL_MEDIA_STATE)
                .put("call", callId)
                .put("state", state)
                .put("listen", listen)
                .put("talk", talk)
                .apply { if (via != null) put("via", via.wire) }
                .apply { if (reason != null) put("reason", reason) },
        )
    }

    private inner class Session(
        val callId: String,
        private val wantTalk: Boolean,
        private val service: IPrivilegedService,
        private val transport: PacketTransport,
        val via: Via,
        val token: Long,
    ) {
        @Volatile
        private var running = true
        private var captureFd: ParcelFileDescriptor? = null
        private var injectFd: ParcelFileDescriptor? = null
        private var captureRate = AudioPackets.SAMPLE_RATE

        /** Bluetooth delivers in bursts: hold 80 ms there, 60 ms on Wi-Fi (PROTOCOL.md, "Call audio"). */
        private val jitter = JitterBuffer(target = if (via == Via.LINK) LINK_JITTER_FRAMES else WIFI_JITTER_FRAMES)

        /** Opus for the Bluetooth session (D-072); the capture thread owns it. */
        private var encoder: VoiceEncoder? = null

        /** Opus packets from the iPhone are decoded here, in arrival order, off the link thread. */
        private val decodeThread = Executors.newSingleThreadExecutor { r -> Thread(r, "pairline-audio-decode") }
        private var decoder: VoiceDecoder? = null
        private var decoderBroken = false
        private var decodeFailures = 0
        private var sent = 0
        private var received = 0
        private var levelSum = 0L
        var talking = false
            private set
        var talkProblem: String? = null
            private set

        fun open() {
            if (via == Via.LINK) {
                encoder = try {
                    Opus.encoder()
                } catch (e: Exception) {
                    throw RequestException(ErrorCode.FAILED, "the Opus encoder didn't start: ${e.message}")
                }
            }
            // One deadline for waiting on the call's audio mode and for asking the interception API again.
            val deadline = System.nanoTime() + CALL_MODE_WAIT_MS * 1_000_000
            if (!awaitCallAudioMode(deadline)) {
                Log.w(TAG, "the phone isn't in its call audio mode yet (mode ${audioMode()}); trying anyway")
            }
            // The learned source first, then the others: phones differ in which ones open (some only one of them).
            // Whichever opens is remembered for the next call.
            val order = captureOrder()
            val preferred = prefs.audioCaptureSource.takeIf { it in order } ?: order.first()
            val sources = listOf(preferred) + (order - preferred)
            var problem: String? = null
            // Every source's reason, so a failure report (also shown on the iPhone) says what each one did.
            val problems = LinkedHashMap<Int, String>()
            var opened = preferred
            capture@ for (source in sources) {
                for (rate in CAPTURE_RATES) {
                    while (true) {
                        try {
                            captureFd = service.startCapture(source, rate)
                            captureRate = rate
                            opened = source
                            if (source != preferred) {
                                val why = problems[preferred] ?: problem
                                if (preferred == PrivilegedService.SOURCE_INTERCEPT) {
                                    // Interception can fail for one call (e.g. while it's still dialing) and work
                                    // for the next: keep asking it first (D-065).
                                    Log.i(TAG, "interception didn't open ($why); this call uses ${sourceName(source)}")
                                } else {
                                    prefs.audioCaptureSource = source
                                    Log.i(TAG, "capture source $preferred didn't open ($why); using $source from now on")
                                }
                            }
                            break@capture
                        } catch (e: RuntimeException) {
                            problem = e.message
                            problems.putIfAbsent(source, "${sourceName(source)} at $rate Hz: ${e.message}")
                            // The interception API refuses until the call's audio mode is set: ask again shortly.
                            val notYet = source == PrivilegedService.SOURCE_INTERCEPT &&
                                problem?.contains("not available in mode") == true
                            if (!notYet || System.nanoTime() > deadline) break
                            Thread.sleep(POLL_MS)
                        }
                    }
                }
            }
            if (captureFd == null) {
                val detail = problems.values.joinToString("; ").ifEmpty { problem ?: "no source" }
                val summary = "couldn't capture call audio (Android ${android.os.Build.VERSION.SDK_INT}, audio mode " +
                    "${audioMode()}, interception ${privileged.probe.value?.interceptable == true}): $detail"
                Log.w(TAG, summary)
                throw RequestException(ErrorCode.FAILED, summary)
            }
            // The caller's voice is on the iPhone now: don't also play it from this phone's earpiece (the capture
            // taps the call before the volume). Undone by stopAudio() at the end.
            if (prefs.audioSilenceEarpiece) {
                val silenced = runCatching { service.silenceEarpiece(true) }
                    .onFailure { Log.w(TAG, "couldn't silence this phone's earpiece: ${it.message}") }
                    .getOrDefault(false)
                if (silenced) Log.i(TAG, "this phone's earpiece is silent during the relay")
            }
            if (wantTalk) {
                try {
                    injectFd = service.startInject(AudioPackets.SAMPLE_RATE, prefs.audioMuteBridgeMic)
                    talking = true
                } catch (e: RuntimeException) {
                    talkProblem = e.message
                    Log.w(TAG, "feeding audio into the call failed: ${e.message}")
                }
            }
            transport.setReceiver(::received)
            Log.i(
                TAG,
                "relaying call audio over ${if (via == Via.LINK) "Bluetooth (Opus)" else "Wi-Fi (PCM)"}: capture " +
                    "$captureRate Hz from source $opened, talk $talking",
            )
            if (via == Via.LINK) onLinkAudio?.invoke(true)
            captureStarted = true
            Thread(::captureLoop, "pairline-audio-up").apply { isDaemon = true }.start()
            if (talking) Thread(::playoutLoop, "pairline-audio-down").apply { isDaemon = true }.start()
        }

        /** Once the capture thread runs, it owns [encoder] and closes it. */
        @Volatile
        private var captureStarted = false

        /** A packet from the iPhone: PCM goes straight to the jitter buffer, Opus is decoded first (on its thread). */
        private fun received(packet: ByteArray) {
            AudioPackets.decode(packet)?.let { (sequence, frame) ->
                received++
                jitter.put(sequence, frame)
                return
            }
            val (sequence, opus) = AudioPackets.decodeOpus(packet) ?: return
            if (!running || !opusAvailable()) return
            received++
            runCatching {
                decodeThread.execute {
                    if (!running || decoderBroken) return@execute
                    try {
                        val codec = decoder ?: Opus.decoder().also { decoder = it }
                        codec.decode(opus)?.let { jitter.put(sequence, it) } ?: decodeFailures++
                    } catch (e: Exception) {
                        // A codec that broke stays off for this call; the iPhone's voice doesn't reach the call then.
                        decoderBroken = true
                        runCatching { decoder?.close() }
                        decoder = null
                        Log.w(TAG, "decoding the iPhone's audio failed: ${e.message}")
                    }
                }
            }
        }

        /** Caller's voice → iPhone. */
        private fun captureLoop() {
            val fd = captureFd ?: return
            val input = ParcelFileDescriptor.AutoCloseInputStream(fd)
            val frameBytes = captureRate / 50 * 2
            val buffer = ByteArray(frameBytes)
            var sequence = 0
            try {
                while (running) {
                    if (!readFully(input, buffer)) break
                    val frame = if (captureRate == AudioPackets.SAMPLE_RATE) {
                        buffer.copyOf()
                    } else {
                        Pcm.toBytes(Pcm.resample(Pcm.toShorts(buffer), captureRate, AudioPackets.SAMPLE_RATE))
                            .copyOf(AudioPackets.FRAME_BYTES)
                    }
                    levelSum += Pcm.rms(frame)
                    val opus = encoder
                    if (opus == null) {
                        transport.send(AudioPackets.encode(sequence, frame))
                        sequence = (sequence + 1) and 0xFFFF
                        sent++
                    } else {
                        for (packet in opus.encode(frame)) {
                            if (packet.size > AudioPackets.MAX_OPUS_BYTES) continue
                            transport.send(AudioPackets.encodeOpus(sequence, packet))
                            sequence = (sequence + 1) and 0xFFFF
                            sent++
                        }
                    }
                }
            } catch (_: IOException) {
                // The service or close() ended the pipe.
            } catch (e: IllegalStateException) {
                // The Opus encoder failed (MediaCodec.CodecException is one): the caller's voice stops here.
                if (running) Log.w(TAG, "encoding the caller's voice failed: ${e.message}")
            } finally {
                runCatching { input.close() }
                runCatching { encoder?.close() }
            }
        }

        /** iPhone microphone → call, one frame every 20 ms whatever the network does. */
        private fun playoutLoop() {
            val fd = injectFd ?: return
            val out = ParcelFileDescriptor.AutoCloseOutputStream(fd)
            val silence = ByteArray(AudioPackets.FRAME_BYTES)
            var due = System.nanoTime()
            try {
                while (running) {
                    out.write(jitter.take() ?: silence)
                    due += FRAME_NANOS
                    val wait = due - System.nanoTime()
                    if (wait > 0) {
                        Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    } else if (wait < -RESYNC_NANOS) {
                        due = System.nanoTime() // fell far behind (the phone was busy): don't burst to catch up
                    }
                }
            } catch (_: IOException) {
                // The service or close() ended the pipe.
            } catch (_: InterruptedException) {
                // Closing.
            } finally {
                runCatching { out.close() }
            }
        }

        fun close(reason: String) {
            running = false
            transport.setReceiver(null)
            runCatching { service.stopAudio() }
            runCatching { captureFd?.close() }
            runCatching { injectFd?.close() }
            if (!captureStarted) runCatching { encoder?.close() }
            // The decoder belongs to its thread: close it there, after anything still queued.
            runCatching {
                decodeThread.execute {
                    runCatching { decoder?.close() }
                    decoder = null
                }
            }
            decodeThread.shutdown()
            if (via == Via.LINK) onLinkAudio?.invoke(false)
            val level = if (sent > 0) levelSum / sent else 0
            val opus = if (via == Via.LINK) ", Opus packets the codec dropped $decodeFailures" else ""
            Log.i(
                TAG,
                "call audio stopped ($reason): sent $sent frames (average level $level), received $received, " +
                    "quiet frames skipped ${jitter.skipped}$opus",
            )
        }

        private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
            var filled = 0
            while (filled < buffer.size) {
                val n = input.read(buffer, filled, buffer.size - filled)
                if (n < 0) return false
                filled += n
            }
            return true
        }
    }

    companion object {
        private const val TAG = "audio"
        const val SOURCE_DOWNLINK = 3 // MediaRecorder.AudioSource.VOICE_DOWNLINK
        const val SOURCE_CALL = 4 // MediaRecorder.AudioSource.VOICE_CALL
        private const val MAX_TALK_FAILURES = 2
        private val CAPTURE_RATES = listOf(16_000, 48_000, 8_000)
        private const val CALL_MODE_WAIT_MS = 4_000L

        /** See the `init` block: raise it when a helper fix makes the learned audio settings wrong. */
        private const val LEARNING_EPOCH = 3
        private const val POLL_MS = 30L

        /** In-call audio modes: a mobile call, a VoIP call, and Android 11/13's screening and redirect modes. */
        private val CALL_MODES = setOf(
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            4, // MODE_CALL_SCREENING (Android 11)
            5, // MODE_CALL_REDIRECT (Android 13)
            6, // MODE_COMMUNICATION_REDIRECT (Android 13)
        )
        private const val FRAME_NANOS = 20_000_000L
        private const val WIFI_JITTER_FRAMES = 3
        private const val LINK_JITTER_FRAMES = 4
        private const val RESYNC_NANOS = 200_000_000L
    }
}

/** A media channel (the Wi-Fi link's, or the Bluetooth session's) as the relay wants it (D-047, D-072). */
class MediaChannelTransport(private val channel: MediaChannel) : CallAudioRelay.PacketTransport {
    override val isOpen: Boolean get() = channel.isOpen
    override fun send(packet: ByteArray) = channel.send(packet)
    override fun setReceiver(receiver: ((ByteArray) -> Unit)?) {
        channel.onPacket = receiver
    }
}
