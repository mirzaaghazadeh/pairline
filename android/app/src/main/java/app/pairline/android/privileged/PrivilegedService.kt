package app.pairline.android.privileged

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import app.pairline.android.BuildConfig
import java.io.IOException
import kotlin.system.exitProcess
import org.json.JSONObject

/**
 * Pairline's only code with shell rights (AGENTS.md rule 7, D-046, D-054). It runs in Pairline's helper process
 * ([HelperMain]) as the shell user; the app talks to it through [IPrivilegedService].
 *
 * - Only Pairline's own uid may call it ([checkCaller]); every argument is checked against a fixed list.
 * - It can do four things: report what the phone allows, turn on Pairline's MANAGE_ONGOING_CALLS app-op, capture
 *   the call's audio, and feed audio into the call. Nothing else is reachable.
 * - It never touches settings the user didn't ask for (rule 5): the microphone mute it sets during a relayed call is
 *   restored by [stopAudio], and the app-op is set only when the user turns on "Talk on iPhone".
 * - It writes no logs: errors go back to the app as exceptions, and the app logs them (redacted) in its own log.
 */
class PrivilegedService(private val context: Context) : IPrivilegedService.Stub() {

    private val ownerUid: Int = context.packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0)

    /**
     * Audio calls are attributed to the shell package, which is what this process really is (uid 2000). Attributing
     * them to Pairline's package from the shell uid would fail Android's package/uid check.
     */
    private val shell: Context = runCatching { context.createPackageContext(SHELL_PACKAGE, 0) }.getOrDefault(context)

    private val lock = Any()
    private var capture: Capture? = null
    private var inject: Inject? = null
    private var micMuteBefore: Boolean? = null

    override fun destroy() {
        checkCaller()
        synchronized(lock) { stopLocked() }
        // Reply first, then end the helper process.
        Thread { Thread.sleep(100); exitProcess(0) }.start()
    }

    override fun version(): Int = VERSION

    override fun probe(): String {
        checkCaller()
        fun granted(permission: String) =
            context.checkPermission(permission, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED
        val outputs = audioManager().getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return JSONObject()
            .put("sdk", Build.VERSION.SDK_INT)
            .put("root", Process.myUid() == 0)
            .put("captureOutput", granted("android.permission.CAPTURE_AUDIO_OUTPUT"))
            .put("modifyPhoneState", granted("android.permission.MODIFY_PHONE_STATE"))
            .put("modifyAudioRouting", granted("android.permission.MODIFY_AUDIO_ROUTING"))
            .put("telephonyOutput", outputs.any { it.type == AudioDeviceInfo.TYPE_TELEPHONY })
            .toString()
    }

    override fun allowOngoingCalls(packageName: String?): Boolean {
        checkCaller()
        if (packageName != BuildConfig.APPLICATION_ID) throw SecurityException("only Pairline's own package")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false // the app-op exists from Android 12
        val process = ProcessBuilder(CMD, "appops", "set", packageName, "MANAGE_ONGOING_CALLS", "allow")
            .redirectErrorStream(true)
            .start()
        process.inputStream.use { it.readBytes() }
        return process.waitFor() == 0
    }

    override fun startCapture(source: Int, sampleRate: Int): ParcelFileDescriptor {
        checkCaller()
        if (source !in CAPTURE_SOURCES) throw IllegalArgumentException("capture source $source isn't allowed")
        if (sampleRate !in SAMPLE_RATES) throw IllegalArgumentException("sample rate $sampleRate isn't allowed")
        synchronized(lock) {
            capture?.stop()
            capture = null
            val c = Capture(openRecord(source, sampleRate), sampleRate)
            capture = c
            return c.start()
        }
    }

    override fun startInject(sampleRate: Int, muteMic: Boolean): ParcelFileDescriptor {
        checkCaller()
        if (sampleRate !in SAMPLE_RATES) throw IllegalArgumentException("sample rate $sampleRate isn't allowed")
        synchronized(lock) {
            inject?.stop()
            inject = null
            val am = audioManager()
            val device = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
                ?: throw IllegalStateException("this phone has no telephony audio output")
            val track = openTrack(sampleRate)
            if (!track.setPreferredDevice(device)) {
                track.release()
                throw IllegalStateException("couldn't route audio to the telephony output")
            }
            if (muteMic && micMuteBefore == null) {
                micMuteBefore = am.isMicrophoneMute
                am.isMicrophoneMute = true
            }
            val i = Inject(track, sampleRate)
            inject = i
            return i.start()
        }
    }

    override fun stopAudio() {
        checkCaller()
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        capture?.stop()
        capture = null
        inject?.stop()
        inject = null
        micMuteBefore?.let { before -> runCatching { audioManager().isMicrophoneMute = before } }
        micMuteBefore = null
    }

    private fun checkCaller() {
        val uid = Binder.getCallingUid()
        if (uid != ownerUid) throw SecurityException("uid $uid isn't Pairline")
    }

    private fun audioManager(): AudioManager = shell.getSystemService(AudioManager::class.java)

    @SuppressLint("MissingPermission") // the shell user holds RECORD_AUDIO and, on Android 10+, CAPTURE_AUDIO_OUTPUT
    private fun openRecord(source: Int, rate: Int): AudioRecord {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw IllegalArgumentException("this phone can't capture at $rate Hz")
        val format = AudioFormat.Builder()
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val builder = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, frameBytes(rate) * 8))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setContext(shell)
        val record = try {
            builder.build()
        } catch (e: UnsupportedOperationException) {
            throw IllegalStateException("capture source $source isn't available: ${e.message}")
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("capture source $source didn't initialize")
        }
        return record
    }

    private fun openTrack(rate: Int): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, frameBytes(rate) * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Reads 20 ms frames from the call and writes them into a pipe the app reads. */
    private class Capture(private val record: AudioRecord, rate: Int) {
        private val frameBytes = frameBytes(rate)

        @Volatile
        private var running = true

        fun start(): ParcelFileDescriptor {
            val (read, write) = ParcelFileDescriptor.createPipe()
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                read.close()
                write.close()
                throw IllegalStateException("call audio capture didn't start")
            }
            Thread({
                val out = ParcelFileDescriptor.AutoCloseOutputStream(write)
                val buffer = ByteArray(frameBytes)
                try {
                    while (running) {
                        var filled = 0
                        while (filled < frameBytes && running) {
                            val n = record.read(buffer, filled, frameBytes - filled)
                            if (n < 0) {
                                running = false
                                break
                            }
                            filled += n
                        }
                        if (filled == frameBytes) out.write(buffer)
                    }
                } catch (_: IOException) {
                    // The app closed its end of the pipe.
                } finally {
                    runCatching { out.close() }
                    runCatching { record.stop() }
                    record.release()
                }
            }, "pairline-capture").apply { isDaemon = true }.start()
            return read
        }

        fun stop() {
            running = false
        }
    }

    /** Reads frames the app writes into a pipe and plays them into the call through the telephony output. */
    private class Inject(private val track: AudioTrack, rate: Int) {
        private val frameBytes = frameBytes(rate)

        @Volatile
        private var running = true

        fun start(): ParcelFileDescriptor {
            val (read, write) = ParcelFileDescriptor.createPipe()
            track.play()
            Thread({
                val input = ParcelFileDescriptor.AutoCloseInputStream(read)
                val buffer = ByteArray(frameBytes)
                try {
                    while (running) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n > 0) track.write(buffer, 0, n)
                    }
                } catch (_: IOException) {
                    // The app closed its end of the pipe.
                } finally {
                    runCatching { input.close() }
                    runCatching { track.stop() }
                    track.release()
                }
            }, "pairline-inject").apply { isDaemon = true }.start()
            return write
        }

        fun stop() {
            running = false
        }
    }

    companion object {
        /** Bump when [IPrivilegedService] changes, so the app replaces a running older service. */
        const val VERSION = 1

        private const val SHELL_PACKAGE = "com.android.shell"
        private const val CMD = "/system/bin/cmd"
        private val CAPTURE_SOURCES = setOf(MediaRecorder.AudioSource.VOICE_DOWNLINK, MediaRecorder.AudioSource.VOICE_CALL)
        private val SAMPLE_RATES = setOf(8_000, 16_000, 48_000)

        /** 20 ms of 16-bit mono audio. */
        fun frameBytes(rate: Int): Int = rate / 50 * 2
    }
}
