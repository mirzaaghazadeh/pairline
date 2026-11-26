package app.pairline.android.privileged

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
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
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.annotation.RequiresApi
import app.pairline.android.BuildConfig
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import kotlin.system.exitProcess
import org.json.JSONObject

/**
 * Pairline's only code with shell rights (AGENTS.md rule 7, D-046, D-054). It runs in Pairline's helper process
 * ([HelperMain]) as the shell user; the app talks to it through [IPrivilegedService].
 *
 * - Only Pairline's own uid may call it ([checkCaller]); every argument is checked against a fixed list.
 * - It can do these things and nothing else: report what the phone allows, turn on Pairline's MANAGE_ONGOING_CALLS
 *   app-op, let Pairline write secure settings (D-059), capture the call's audio, feed audio into the call, silence
 *   this phone's earpiece and ringer during a relayed call, and, when the user flips the switch on the iPhone,
 *   start or stop the phone's hotspot or Bluetooth tethering, turn mobile data on or off, or pick the data SIM
 *   (D-073), and give Pairline its WRITE_SMS app-op so reading on the iPhone marks messages read here (D-074).
 * - The microphone mute and earpiece silence it sets during a relayed call are restored by [stopAudio], or when
 *   Pairline's process ends; the app-op and the permission are set only once the user turned on "Talk on iPhone".
 * - It writes no logs: errors go back to the app as exceptions, and the app logs them (redacted) in its own log.
 */
class PrivilegedService(private val context: Context) : IPrivilegedService.Stub() {

    private val ownerUid: Int = context.packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0)

    /**
     * Audio calls are attributed to the shell package, which is what this process really is (uid 2000). A context
     * created from the system context inherits the system's app-ops package (or none), which makes the audio server
     * refuse to start recording ("didn't start"); [ShellIdentity] states the shell package and uid explicitly.
     */
    private val shell: Context = ShellIdentity(runCatching { context.createPackageContext(SHELL_PACKAGE, 0) }.getOrDefault(context))

    /**
     * An AudioManager made for [shell] itself: getSystemService would hand back one cached for the system context,
     * whose package doesn't match this process's uid (volume and interception calls check that).
     */
    private val audio: AudioManager by lazy {
        runCatching {
            AudioManager::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(shell)
        }.getOrElse { shell.getSystemService(AudioManager::class.java) }
    }

    /**
     * Telecom checks the calling package against the calling uid, and a TelecomManager from the system context names
     * the wrong package, so silenceRinger was refused (D-066). One made for [shell], like [audio].
     */
    private val telecom: TelecomManager? by lazy {
        runCatching {
            TelecomManager::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(shell)
        }.getOrElse { shell.getSystemService(TelecomManager::class.java) }
    }

    private val lock = Any()
    private var capture: Capture? = null
    private var inject: Inject? = null
    private var micMuteBefore: Boolean? = null
    private var voiceVolumeBefore: Int? = null

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
            .put("interceptable", granted(CALL_AUDIO_INTERCEPTION) && interceptable())
            .toString()
    }

    /**
     * Android 13's call-audio interception API (`AudioManager.isPstnCallAudioInterceptable`, a system API, so reached
     * by reflection): the platform's own way to take a mobile call's downlink and feed its uplink. Not every phone
     * maker enables it.
     */
    private fun interceptable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching { callAudioApi("isPstnCallAudioInterceptable") as Boolean }.getOrDefault(false)
    }

    /** Calls one of AudioManager's call-audio interception methods; its own errors come back as plain exceptions. */
    private fun callAudioApi(name: String, format: AudioFormat? = null): Any? {
        val am = audioManager()
        return try {
            if (format == null) {
                AudioManager::class.java.getMethod(name).invoke(am)
            } else {
                AudioManager::class.java.getMethod(name, AudioFormat::class.java).invoke(am, format)
            }
        } catch (e: InvocationTargetException) {
            // Only some exception types cross the binder: hand the app the platform's reason as one of them.
            val cause = e.targetException
            throw IllegalStateException(cause.message ?: cause.javaClass.simpleName)
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("call audio interception isn't available here (${e.javaClass.simpleName})")
        }
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
        return asHelper {
            synchronized(lock) {
                capture?.stop()
                capture = null
                val record = if (source == SOURCE_INTERCEPT) openInterceptRecord(sampleRate) else openRecord(source, sampleRate)
                val c = Capture(record, sampleRate)
                capture = c
                c.start()
            }
        }
    }

    /**
     * "Ring only on the iPhone" (D-066): stops this phone's ringtone and vibration for the ringing call, as the volume
     * key does. The shell user holds MODIFY_PHONE_STATE, which Telecom requires; the call keeps ringing on the iPhone
     * and can still be answered on either phone.
     */
    @SuppressLint("MissingPermission")
    override fun silenceRinger(): Boolean {
        checkCaller()
        return asHelper {
            // Failures reach the app as exceptions, which it logs.
            val t = telecom ?: throw IllegalStateException("no Telecom service")
            t.silenceRinger()
            true
        }
    }

    /**
     * Runs [block] as the helper itself. AudioRecord and AudioTrack made while a binder call is being handled take the
     * caller's uid and pid as their client (native `AudioRecord::set`), here Pairline's app, and the audio server
     * refuses that as a disguise ("createRecord uid 2000 tried to pass itself off as …", then "startInput failed",
     * seen on a tester's Android 15 phone, D-065): call audio never started. Without the caller's identity, the client
     * is the shell user, whose permissions the capture needs.
     */
    private inline fun <T> asHelper(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        try {
            return block()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun startInject(sampleRate: Int, muteMic: Boolean): ParcelFileDescriptor {
        checkCaller()
        if (sampleRate !in SAMPLE_RATES) throw IllegalArgumentException("sample rate $sampleRate isn't allowed")
        return asHelper {
            synchronized(lock) {
                inject?.stop()
                inject = null
                val am = audioManager()
                // Android 13's uplink injection first, where the phone offers it; else the telephony output device.
                val track = (if (interceptable()) openInterceptTrack(sampleRate) else null) ?: openTelephonyTrack(am, sampleRate)
                if (muteMic && micMuteBefore == null) {
                    micMuteBefore = am.isMicrophoneMute
                    am.isMicrophoneMute = true
                }
                val i = Inject(track, sampleRate)
                inject = i
                i.start()
            }
        }
    }

    override fun stopAudio() {
        checkCaller()
        synchronized(lock) { stopLocked() }
    }

    /**
     * During a relayed call, silences this phone's earpiece (the voice-call volume at 0, which the shell user may set
     * because it holds MODIFY_PHONE_STATE), so the caller's voice doesn't also play here. Restored by [stopAudio], or
     * when Pairline's process ends ([releaseAudio]).
     */
    override fun silenceEarpiece(on: Boolean): Boolean {
        checkCaller()
        synchronized(lock) {
            val am = audioManager()
            if (!on) {
                restoreEarpiece(am)
                return true
            }
            if (voiceVolumeBefore == null) {
                voiceVolumeBefore = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0)
            }
            return am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) == 0
        }
    }

    /**
     * Lets Pairline itself change secure settings (WRITE_SECURE_SETTINGS, a development permission the shell may
     * grant), so it can turn Developer options and Wireless debugging back on when this helper is needed again after
     * a restart (D-059). Only Pairline's own package, only this permission; [allow] false takes it back.
     */
    override fun allowSecureSettings(packageName: String?, allow: Boolean): Boolean {
        checkCaller()
        if (packageName != BuildConfig.APPLICATION_ID) throw SecurityException("only Pairline's own package")
        val verb = if (allow) "grant" else "revoke"
        val process = ProcessBuilder(CMD, "package", verb, packageName, WRITE_SECURE_SETTINGS)
            .redirectErrorStream(true)
            .start()
        process.inputStream.use { it.readBytes() }
        return process.waitFor() == 0
    }

    /**
     * Android's TetheringManager (a system API, so reached by reflection), made for [shell] like [audio]: the
     * tethering service checks that the calling package belongs to the calling uid, and the shell user holds
     * TETHER_PRIVILEGED, which starting tethering requires. Android 11 and later.
     */
    private val tethering: Any? by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@lazy null
        runCatching {
            val supplier = Supplier { serviceBinder(TETHERING_SERVICE) }
            Class.forName(TETHERING_MANAGER)
                .getConstructor(Context::class.java, Supplier::class.java)
                .newInstance(shell, supplier)
        }.getOrNull()
    }

    /**
     * Starts or stops the phone's own hotspot or Bluetooth tethering, as Android's Hotspot & tethering screen does
     * (with its carrier check). Only when the user flips the switch on the iPhone (D-073).
     */
    override fun setTethering(type: Int, on: Boolean): Int {
        checkCaller()
        if (type !in TETHERING_TYPES) throw IllegalArgumentException("tethering type $type isn't allowed")
        val manager = tethering ?: throw IllegalStateException("tethering isn't reachable on this phone")
        val cls = manager.javaClass
        try {
            if (!on) {
                cls.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(manager, type)
                return 0
            }
            val result = ArrayBlockingQueue<Int>(1)
            val callbackClass = Class.forName("$TETHERING_MANAGER\$StartTetheringCallback")
            val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
                when (method.name) {
                    "onTetheringStarted" -> { result.offer(0); null }
                    "onTetheringFailed" -> { result.offer(args?.firstOrNull() as? Int ?: TETHER_NO_ANSWER); null }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "pairline-tethering"
                    else -> null
                }
            }
            val builder = Class.forName("$TETHERING_MANAGER\$TetheringRequest\$Builder")
                .getConstructor(Int::class.javaPrimitiveType)
                .newInstance(type)
            val request = builder.javaClass.getMethod("build").invoke(builder)!!
            cls.getMethod("startTethering", request.javaClass, Executor::class.java, callbackClass)
                .invoke(manager, request, Executor { it.run() }, callback)
            return result.poll(TETHER_WAIT_MS, TimeUnit.MILLISECONDS) ?: TETHER_NO_ANSWER
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            throw IllegalStateException(cause.message ?: cause.javaClass.simpleName)
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("tethering isn't reachable on this phone (${e.javaClass.simpleName})")
        }
    }

    /** Mobile data on or off for the data SIM, with Android's own shell command. Only from the iPhone's switch (D-073). */
    override fun setMobileData(on: Boolean): Boolean {
        checkCaller()
        val verb = if (on) "enable" else "disable"
        // `svc data` exists on every version the helper runs on; newer ones also have `cmd phone data`.
        return run(SVC, "data", verb) || run(CMD, "phone", "data", verb)
    }

    /** Makes an active SIM the data SIM, as Android's SIM settings do. Only from the iPhone's choice (D-073). */
    @SuppressLint("MissingPermission") // the shell user holds READ_PHONE_STATE and MODIFY_PHONE_STATE
    override fun setDataSubscription(subId: Int): Boolean {
        checkCaller()
        val subs = subscriptions ?: throw IllegalStateException("no subscription service")
        val active = subs.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }
        if (subId !in active) throw IllegalArgumentException("subscription $subId isn't an active SIM")
        try {
            SubscriptionManager::class.java.getMethod("setDefaultDataSubId", Int::class.javaPrimitiveType).invoke(subs, subId)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            throw IllegalStateException(cause.message ?: cause.javaClass.simpleName)
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("can't choose the data SIM on this phone (${e.javaClass.simpleName})")
        }
        return true
    }

    /** A SubscriptionManager made for [shell], like [audio]: reading the SIM list checks the package against the uid. */
    private val subscriptions: SubscriptionManager? by lazy {
        runCatching {
            SubscriptionManager::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(shell)
        }.getOrElse { shell.getSystemService(SubscriptionManager::class.java) }
    }

    /** Runs one of Android's own shell commands; true if it exited with 0. */
    private fun run(vararg command: String): Boolean = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.inputStream.use { it.readBytes() }
        process.waitFor() == 0
    }.getOrDefault(false)

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun serviceBinder(name: String): IBinder? = runCatching {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as IBinder?
    }.getOrNull()

    /**
     * Pairline's own WRITE_SMS app-op (D-074): with it, Android's SMS store accepts Pairline marking messages read.
     * Only Pairline's own package, only this app-op; [allow] false puts it back to Android's default.
     */
    override fun allowWriteSms(packageName: String?, allow: Boolean): Boolean {
        checkCaller()
        if (packageName != BuildConfig.APPLICATION_ID) throw SecurityException("only Pairline's own package")
        return run(CMD, "appops", "set", packageName, "WRITE_SMS", if (allow) "allow" else "default")
    }

    /** Pairline's process ended: give the call back its microphone and earpiece. Helper-internal, not on the binder. */
    fun releaseAudio() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        capture?.stop()
        capture = null
        inject?.stop()
        inject = null
        micMuteBefore?.let { before -> runCatching { audioManager().isMicrophoneMute = before } }
        micMuteBefore = null
        runCatching { restoreEarpiece(audioManager()) }
    }

    private fun restoreEarpiece(am: AudioManager) {
        val before = voiceVolumeBefore ?: return
        voiceVolumeBefore = null
        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, before, 0)
    }

    private fun checkCaller() {
        val uid = Binder.getCallingUid()
        if (uid != ownerUid) throw SecurityException("uid $uid isn't Pairline")
    }

    private fun audioManager(): AudioManager = audio

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

    /**
     * The call's downlink through Android 13's interception API. It refuses ("not available in mode …") until Android
     * has switched the phone to its in-call audio mode, which can lag the call's dialing or answer; the app waits for
     * that and asks again.
     */
    private fun openInterceptRecord(rate: Int): AudioRecord {
        if (!interceptable()) throw IllegalStateException("this phone doesn't offer call audio interception")
        val record = callAudioApi("getCallDownlinkExtractionAudioRecord", pcmFormat(rate, AudioFormat.CHANNEL_IN_MONO))
            as? AudioRecord ?: throw IllegalStateException("call audio interception gave no recorder")
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("call audio interception didn't initialize")
        }
        return record
    }

    /** The call's uplink through Android 13's interception API; null (then the telephony output is used) if refused. */
    private fun openInterceptTrack(rate: Int): AudioTrack? {
        val track = runCatching {
            callAudioApi("getCallUplinkInjectionAudioTrack", pcmFormat(rate, AudioFormat.CHANNEL_OUT_MONO)) as? AudioTrack
        }.getOrNull() ?: return null
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return null
        }
        return track
    }

    private fun openTelephonyTrack(am: AudioManager, rate: Int): AudioTrack {
        val device = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            ?: throw IllegalStateException("this phone has no telephony audio output")
        val track = openTrack(rate)
        if (!track.setPreferredDevice(device)) {
            track.release()
            throw IllegalStateException("couldn't route audio to the telephony output")
        }
        return track
    }

    private fun pcmFormat(rate: Int, channels: Int): AudioFormat = AudioFormat.Builder()
        .setSampleRate(rate)
        .setChannelMask(channels)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .build()

    private fun openTrack(rate: Int): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(pcmFormat(rate, AudioFormat.CHANNEL_OUT_MONO))
            .setBufferSizeInBytes(maxOf(min, frameBytes(rate) * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * Who this process is to Android's audio server and app ops: the shell package with this process's uid (2000).
     * Package name, app-ops package and attribution all say so, and the target SDK is the running one (AudioManager
     * reports some modes differently to old targets).
     */
    private class ShellIdentity(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this

        @RequiresApi(Build.VERSION_CODES.S)
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()

        override fun getApplicationInfo(): ApplicationInfo =
            ApplicationInfo(super.getApplicationInfo()).also { it.targetSdkVersion = Build.VERSION.SDK_INT }
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
        /**
         * Bump when [IPrivilegedService] or what the helper does changes, so the app replaces a running older helper
         * (one keeps running across app updates). 4: recorders and players made as the helper (D-065).
         * 5: silenceRinger (D-066). 6: silenceRinger as the shell package. 7: the iPhone's internet switches (D-073).
         * 8: allowWriteSms (D-074).
         */
        const val VERSION = 8

        /** Not a MediaRecorder source: Android 13's call-audio interception API ([openInterceptRecord]). */
        const val SOURCE_INTERCEPT = 100

        /** TetheringManager.TETHERING_WIFI and TETHERING_BLUETOOTH: the only kinds the iPhone may switch (D-073). */
        const val TETHER_WIFI = 0
        const val TETHER_BLUETOOTH = 2

        /** setTethering's answer when Android didn't say within [TETHER_WAIT_MS]. */
        const val TETHER_NO_ANSWER = -1

        private val TETHERING_TYPES = setOf(TETHER_WIFI, TETHER_BLUETOOTH)
        private const val TETHER_WAIT_MS = 15_000L
        private const val TETHERING_MANAGER = "android.net.TetheringManager"
        private const val TETHERING_SERVICE = "tethering"

        private const val SHELL_PACKAGE = "com.android.shell"
        private const val CMD = "/system/bin/cmd"
        private const val SVC = "/system/bin/svc"
        private const val CALL_AUDIO_INTERCEPTION = "android.permission.CALL_AUDIO_INTERCEPTION"
        private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
        private val CAPTURE_SOURCES =
            setOf(MediaRecorder.AudioSource.VOICE_DOWNLINK, MediaRecorder.AudioSource.VOICE_CALL, SOURCE_INTERCEPT)
        private val SAMPLE_RATES = setOf(8_000, 16_000, 48_000)

        /** 20 ms of 16-bit mono audio. */
        fun frameBytes(rate: Int): Int = rate / 50 * 2
    }
}
