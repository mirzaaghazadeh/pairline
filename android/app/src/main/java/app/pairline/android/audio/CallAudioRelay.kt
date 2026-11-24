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
import java.io.IOException
import java.io.InputStream
import org.json.JSONObject

/**
 * Relays a call's audio between this phone and the iPhone ("Talk on iPhone", D-047):
 * - the caller's voice is captured through the privileged service and sent to the iPhone as 20 ms packets;
 * - packets from the iPhone's microphone go through a [JitterBuffer] and are fed into the call's uplink.
 *
 * Audio travels only over the encrypted media channel of the Wi-Fi link and is never stored or logged; the log gets
 * only counts and levels. Nothing starts unless the iPhone asks during an active call and the user turned on
 * "Talk on iPhone".
 */
class CallAudioRelay(
    private val privileged: PrivilegedManager,
    private val prefs: AppPrefs,
    private val emit: (JSONObject) -> Unit,
    /** Android's current audio mode (`AudioManager.getMode`). */
    private val audioMode: () -> Int = { AudioManager.MODE_IN_CALL },
) {
    /** The Wi-Fi link's encrypted media channel (M5). Lossy and unordered; [send] never blocks. */
    interface PacketTransport {
        val isOpen: Boolean
        fun send(packet: ByteArray)
        fun setReceiver(receiver: ((ByteArray) -> Unit)?)
    }

    private val lock = Any()
    private var session: Session? = null

    @Volatile
    private var transport: PacketTransport? = null

    /** Called when capabilities may have changed (e.g. talking turned out not to work here). */
    var onCapsChanged: (() -> Unit)? = null

    /** The caller's voice can be relayed to the iPhone. */
    val canListen: Boolean
        get() = prefs.talkOnIphone &&
            privileged.state.value == PrivilegedManager.State.READY &&
            privileged.probe.value?.canListen == true

    /** …and the iPhone's microphone can be fed into the call. */
    val canTalk: Boolean
        get() = canListen && privileged.probe.value?.canTalk == true && prefs.audioTalkFailures < MAX_TALK_FAILURES

    /** The link layer sets the media channel while a Wi-Fi link is up, and null when it goes away. */
    fun setTransport(t: PacketTransport?) {
        transport = t
        if (t == null) stop(null, "media channel closed")
    }

    /** `call.media.start`: [callId] must be an active call (checked by the caller). Blocking; not on the main thread. */
    fun start(callId: String, wantTalk: Boolean) {
        if (!prefs.talkOnIphone) throw RequestException(ErrorCode.UNSUPPORTED, "Talk on iPhone is off on the bridge")
        val service = privileged.service
            ?: throw RequestException(ErrorCode.UNSUPPORTED, "Pairline's helper isn't running on the bridge")
        if (!canListen) throw RequestException(ErrorCode.UNSUPPORTED, "this phone doesn't allow capturing call audio")
        val t = transport?.takeIf { it.isOpen }
            ?: throw RequestException(ErrorCode.UNSUPPORTED, "call audio needs the Wi-Fi link")
        synchronized(lock) {
            if (session?.callId == callId) return
            session?.close("replaced")
            val s = Session(callId, wantTalk && canTalk, service, t)
            session = s
            try {
                s.open()
            } catch (e: Exception) {
                session = null
                s.close("failed")
                state(callId, "failed", listen = false, talk = false, reason = e.message)
                throw e as? RequestException ?: RequestException(ErrorCode.FAILED, "call audio failed: ${e.message}")
            }
            state(callId, "active", listen = true, talk = s.talking, reason = s.talkProblem)
        }
    }

    /** `call.media.stop`, the call ending, or the media channel closing. [callId] null stops whatever runs. */
    fun stop(callId: String?, reason: String) {
        synchronized(lock) {
            val s = session ?: return
            if (callId != null && s.callId != callId) return
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
        if (heard == false) {
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

    private fun state(callId: String, state: String, listen: Boolean, talk: Boolean, reason: String?) {
        emit(
            JSONObject()
                .put("t", Msg.CALL_MEDIA_STATE)
                .put("call", callId)
                .put("state", state)
                .put("listen", listen)
                .put("talk", talk)
                .apply { if (reason != null) put("reason", reason) },
        )
    }

    private inner class Session(
        val callId: String,
        private val wantTalk: Boolean,
        private val service: IPrivilegedService,
        private val transport: PacketTransport,
    ) {
        @Volatile
        private var running = true
        private var captureFd: ParcelFileDescriptor? = null
        private var injectFd: ParcelFileDescriptor? = null
        private var captureRate = AudioPackets.SAMPLE_RATE
        private val jitter = JitterBuffer()
        private var sent = 0
        private var received = 0
        private var levelSum = 0L
        var talking = false
            private set
        var talkProblem: String? = null
            private set

        fun open() {
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
            var opened = preferred
            capture@ for (source in sources) {
                for (rate in CAPTURE_RATES) {
                    while (true) {
                        try {
                            captureFd = service.startCapture(source, rate)
                            captureRate = rate
                            opened = source
                            if (source != preferred) {
                                prefs.audioCaptureSource = source
                                Log.i(TAG, "capture source $preferred didn't open; using $source from now on")
                            }
                            break@capture
                        } catch (e: RuntimeException) {
                            problem = e.message
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
                Log.w(TAG, "call audio capture failed (sources $sources): $problem")
                throw RequestException(ErrorCode.FAILED, "couldn't capture call audio: $problem")
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
            transport.setReceiver { packet ->
                AudioPackets.decode(packet)?.let { (sequence, frame) ->
                    received++
                    jitter.put(sequence, frame)
                }
            }
            Log.i(TAG, "relaying call audio: capture $captureRate Hz from source $opened, talk $talking")
            Thread(::captureLoop, "pairline-audio-up").apply { isDaemon = true }.start()
            if (talking) Thread(::playoutLoop, "pairline-audio-down").apply { isDaemon = true }.start()
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
                    transport.send(AudioPackets.encode(sequence, frame))
                    sequence = (sequence + 1) and 0xFFFF
                    sent++
                }
            } catch (_: IOException) {
                // The service or close() ended the pipe.
            } finally {
                runCatching { input.close() }
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
            val level = if (sent > 0) levelSum / sent else 0
            Log.i(TAG, "call audio stopped ($reason): sent $sent frames (average level $level), received $received")
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
        private const val RESYNC_NANOS = 200_000_000L
    }
}
