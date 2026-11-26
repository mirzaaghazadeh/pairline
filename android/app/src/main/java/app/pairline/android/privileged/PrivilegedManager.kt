package app.pairline.android.privileged

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import app.pairline.android.AppPrefs
import app.pairline.android.log.Log
import app.pairline.android.privileged.adb.AdbConnection
import app.pairline.android.privileged.adb.AdbKey
import app.pairline.android.privileged.adb.AdbNotPairedException
import app.pairline.android.privileged.adb.AdbPairingClient
import app.pairline.android.privileged.adb.AdbServiceFinder
import app.pairline.android.privileged.adb.AdbWrongCodeException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Pairline's helper with shell rights, for "Talk on iPhone" (D-047, D-054; it replaced Shizuku). On Android 11+
 * Pairline pairs once with the phone's own Wireless debugging (the user types the 6-digit code), then starts the
 * helper through it whenever it's needed and Wireless debugging is on. On Android 10 the user starts it from a
 * computer. Pairline never turns Developer options or Wireless debugging on itself (AGENTS.md rule 5): it watches
 * those settings and opens the right screen.
 *
 * The helper runs only while it's needed: Talk on iPhone is on, or its setup screen is open.
 */
class PrivilegedManager(private val context: Context, private val prefs: AppPrefs) {

    enum class State {
        /**
         * Android 9 and 10: the shell user may not capture call audio there (CAPTURE_AUDIO_OUTPUT, even RECORD_AUDIO,
         * arrived for it in Android 11; checked on an Android 10 phone), so the helper couldn't carry calls (D-056).
         */
        UNSUPPORTED,
        NEEDS_DEVELOPER_OPTIONS,

        /** Off, which is also how Android leaves it after every restart. */
        NEEDS_WIRELESS_DEBUGGING,
        NEEDS_PAIRING,

        /** Ready to start, but not needed now (Talk on iPhone is off). */
        IDLE,
        STARTING,
        READY,

        /** It didn't start; [failure] says why. */
        FAILED,
    }

    /** NO_ROOT: root mode, but `su` is missing or the root app said no (D-075). */
    enum class Failure { NO_SERVICE, NO_HELPER, NO_ROOT, OTHER }

    enum class Pairing { IDLE, WAITING, FOUND, PAIRING, WRONG_CODE, FAILED, DONE }

    /** What the shell user may do on this phone, from [PrivilegedService.probe]. */
    data class Probe(
        val sdk: Int,
        val root: Boolean,
        val captureOutput: Boolean,
        val modifyPhoneState: Boolean,
        val telephonyOutput: Boolean,
        /** Android 13's call-audio interception API works here for the shell user. */
        val interceptable: Boolean = false,
    ) {
        /** The caller's voice can be captured. */
        val canListen: Boolean get() = captureOutput || interceptable
        /** The iPhone's microphone can be fed into the call. */
        val canTalk: Boolean get() = interceptable || (telephonyOutput && (modifyPhoneState || root))
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pairline-helper-start") }
    private val _state = MutableStateFlow(if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) State.UNSUPPORTED else State.NEEDS_PAIRING)
    private val _probe = MutableStateFlow<Probe?>(null)
    private val _pairing = MutableStateFlow(Pairing.IDLE)
    private var started = false

    @Volatile private var starting = false
    @Volatile private var attached: CountDownLatch? = null
    @Volatile private var pairingFinder: AdbServiceFinder? = null

    @Volatile
    var service: IPrivilegedService? = null
        private set

    val state: StateFlow<State> = _state
    val probe: StateFlow<Probe?> = _probe
    val pairing: StateFlow<Pairing> = _pairing

    /** Why the last start failed (shown on the setup screen). */
    @Volatile
    var failure: Failure? = null
        private set

    /** Called on every state change, so the bridge can resend its capabilities. */
    var onChange: (() -> Unit)? = null

    /** The Talk on iPhone setup screen is open: run the helper for its checks even while Talk is off. */
    var setupOpen: Boolean = false
        set(value) {
            field = value
            main.post { refresh() }
        }

    private val wanted: Boolean get() = (prefs.talkOnIphone && !prefs.bridgeStopped) || setupOpen

    fun start() {
        if (started) return
        started = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        HelperInbox.listen(::attach)
        val observer = object : ContentObserver(main) {
            // A setting changed (e.g. Wireless debugging came back on after a restart): try again from scratch.
            override fun onChange(selfChange: Boolean) {
                if (_state.value == State.FAILED) failure = null
                refresh()
            }
        }
        val resolver = context.contentResolver
        for (key in listOf(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, Settings.Global.ADB_ENABLED, ADB_WIFI_ENABLED)) {
            runCatching { resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer) }
        }
        // Wireless debugging works only on a Wi-Fi network: when one comes up, the helper may be started again.
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        main.post { refresh() }
                    }
                },
            )
        }
        refresh()
    }

    /** Re-reads the settings and starts or stops the helper as needed. Main thread. */
    fun refresh() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return set(State.UNSUPPORTED)
        val s = service
        if (s != null && s.asBinder().pingBinder()) {
            // Not needed any more: stop it, but only if Pairline can start it again by itself, or if the user
            // stopped Pairline (then everything stops, even if Talk on iPhone needs Wireless debugging again later).
            if (!wanted && (canRestart() || prefs.bridgeStopped)) {
                // Talk on iPhone switched off: also take back Pairline's right to change secure settings.
                stopHelper(revokeSettings = !prefs.talkOnIphone)
                return set(nextWithoutHelper())
            }
            autoEnableFailures = 0
            return set(State.READY)
        }
        if (wanted && paired && !rootMode) turnDebuggingBackOn()
        set(nextWithoutHelper())
        if (_state.value == State.IDLE && wanted) startHelper()
    }

    /** Root mode (D-075): the helper starts through the phone's own `su`, without Developer options or Wi-Fi. */
    val rootMode: Boolean get() = prefs.rootMode

    private fun nextWithoutHelper(): State = when {
        rootMode && starting -> State.STARTING
        rootMode && _state.value == State.FAILED && failure != null -> State.FAILED
        rootMode -> State.IDLE
        !developerOptionsOn() -> State.NEEDS_DEVELOPER_OPTIONS
        wirelessDebuggingOn() == false -> State.NEEDS_WIRELESS_DEBUGGING
        !paired -> State.NEEDS_PAIRING
        starting -> State.STARTING
        _state.value == State.FAILED && failure != null -> State.FAILED
        else -> State.IDLE
    }

    val paired: Boolean get() = prefs.adbPaired && AdbKey.exists(context)

    private fun canRestart(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (rootMode || (paired && wirelessDebuggingOn() != false))

    /** "Try again" after a failure. */
    fun retry() {
        failure = null
        set(State.IDLE)
        refresh()
    }

    private fun startHelper() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || starting) return
        starting = true
        failure = null
        set(State.STARTING)
        worker.execute {
            val result = runCatching { launch() }
            starting = false
            main.post {
                result.onFailure { e ->
                    when (e) {
                        is AdbNotPairedException -> {
                            Log.i(TAG, "Wireless debugging no longer accepts Pairline's key; pairing again is needed")
                            prefs.adbPaired = false
                        }
                        is StartException -> {
                            Log.w(TAG, "helper didn't start: ${e.failure}")
                            failure = e.failure
                        }
                        else -> {
                            Log.w(TAG, "helper didn't start", e)
                            failure = Failure.OTHER
                        }
                    }
                }
                if (service == null && failure != null) set(State.FAILED) else refresh()
            }
        }
    }

    /** Worker thread: finds Wireless debugging, runs the start command and waits for the helper to attach. */
    private fun launch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (rootMode) return launchWithRoot()
        val key = AdbKey.loadOrCreate(context)
        val finder = AdbServiceFinder(context, AdbServiceFinder.CONNECT)
        val port = try {
            finder.start()
            finder.await(FIND_TIMEOUT_MS)
        } finally {
            finder.stop()
        }
        port ?: throw StartException(Failure.NO_SERVICE)
        val latch = CountDownLatch(1)
        attached = latch
        AdbConnection(port, key).use { adb ->
            adb.connect()
            // Right after Wireless debugging comes on, adbd can close a command without running it. The command ends
            // by printing a marker, so a missing marker means "try again". The helper's own output goes to /dev/null;
            // anything else printed is the shell's (e.g. a missing tool).
            for (attempt in 1..START_ATTEMPTS) {
                val printed = adb.shell(HelperCommand.forShell(context) + " echo $STARTED_MARKER").trim()
                if (printed.contains(STARTED_MARKER)) {
                    val other = printed.replace(STARTED_MARKER, "").trim()
                    if (other.isNotEmpty()) Log.w(TAG, "start command said: ${other.take(300)}")
                    break
                }
                if (attempt == START_ATTEMPTS) throw StartException(Failure.NO_HELPER)
                Thread.sleep(1_000)
            }
            // Stay connected until the helper has handed itself over: adbd ends what the command started when the
            // connection closes, and the helper needs a moment to detach into its own session.
            if (!latch.await(ATTACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw StartException(Failure.NO_HELPER)
        }
    }

    /**
     * Root mode (D-075): the same start command, run as the shell user (uid 2000) through the phone's own `su`
     * (Magisk, KernelSU…), whose app asks the user once. The helper is then exactly what Wireless debugging starts:
     * shell rights, not root. Worker thread.
     */
    private fun launchWithRoot() {
        val latch = CountDownLatch(1)
        attached = latch
        val printed = try {
            val process = ProcessBuilder("su", SHELL_UID, "-c", HelperCommand.forShell(context) + " echo $STARTED_MARKER")
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            out
        } catch (e: java.io.IOException) {
            Log.w(TAG, "no su on this phone")
            throw StartException(Failure.NO_ROOT)
        }
        if (!printed.contains(STARTED_MARKER)) {
            Log.w(TAG, "su didn't run the start command: ${printed.trim().take(200)}")
            throw StartException(Failure.NO_ROOT)
        }
        if (!latch.await(ATTACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw StartException(Failure.NO_HELPER)
        Log.i(TAG, "helper started with root")
    }

    /** A `su` binary is on this phone (root mode can work). Doesn't run it, so no root prompt. */
    fun rootAvailable(): Boolean = SU_PATHS.any { java.io.File(it).exists() }

    /** The helper handed over its binder ([HelperProvider]); any thread. */
    private fun attach(binder: IBinder) {
        val s = IPrivilegedService.Stub.asInterface(binder)
        val version = runCatching { s.version() }.getOrDefault(-1)
        if (version != PrivilegedService.VERSION) {
            // A helper from another Pairline version: replace it.
            runCatching { s.destroy() }
            return
        }
        val p = runCatching { parseProbe(s.probe()) }.onFailure { Log.w(TAG, "helper probe failed", it) }.getOrNull()
        runCatching {
            binder.linkToDeath({
                main.post {
                    if (service?.asBinder() == binder) {
                        service = null
                        _probe.value = null
                        Log.i(TAG, "helper stopped")
                        refresh()
                    }
                }
            }, 0)
        }
        service = s
        _probe.value = p
        Log.i(TAG, "helper ready (sdk ${p?.sdk}, listen ${p?.canListen}, talk ${p?.canTalk}, interception ${p?.interceptable})")
        attached?.countDown()
        main.post {
            failure = null
            refresh()
        }
    }

    private fun stopHelper(revokeSettings: Boolean = false) {
        val s = service ?: return
        service = null
        _probe.value = null
        worker.execute {
            if (revokeSettings && canWriteSecureSettings()) {
                runCatching { s.allowSecureSettings(context.packageName, false) }
                Log.i(TAG, "secure-settings permission returned")
            }
            if (revokeSettings && canWriteSms()) {
                runCatching { s.allowWriteSms(context.packageName, false) }
                Log.i(TAG, "write-SMS app-op returned")
            }
            runCatching { s.destroy() }
        }
        Log.i(TAG, "helper stopped (not needed)")
    }

    /**
     * Lets Pairline turn Wireless debugging back on by itself later (D-059). Called once the helper is ready and Talk
     * on iPhone is on; off the main thread.
     */
    fun allowSecureSettings(): Boolean {
        if (!canWriteSecureSettings()) {
            val s = service ?: return false
            val granted = runCatching { s.allowSecureSettings(context.packageName, true) }
                .onFailure { Log.w(TAG, "couldn't get the secure-settings permission", it) }
                .getOrDefault(false)
            if (!granted) return false
            Log.i(TAG, "Pairline may now turn Wireless debugging back on by itself")
        }
        keepAuthorization()
        return true
    }

    fun canWriteSecureSettings(): Boolean =
        context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private var lastAutoEnable = 0L
    private var autoEnableFailures = 0

    /**
     * Talk on iPhone is on and its helper is gone (after a restart, or Android switched Wireless debugging off with
     * Wi-Fi): turn Developer options, USB debugging (some phones switch Wireless debugging off with it) and Wireless
     * debugging back on, as the user chose (D-059). Only on a Wi-Fi network, where Wireless debugging can run, and
     * backing off if it keeps going off again (e.g. the user declines Android's "allow on this network" prompt).
     */
    private fun turnDebuggingBackOn() {
        if (!prefs.talkOnIphone || prefs.bridgeStopped || starting) return
        val resolver = context.contentResolver
        val off = listOf(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, Settings.Global.ADB_ENABLED, ADB_WIFI_ENABLED)
            .filter { runCatching { Settings.Global.getInt(resolver, it, 0) != 1 }.getOrDefault(false) }
        if (off.isEmpty() || !canWriteSecureSettings() || !onWifi()) return
        val now = SystemClock.elapsedRealtime()
        val wait = AUTO_ENABLE_BASE_MS shl autoEnableFailures.coerceAtMost(7)
        if (lastAutoEnable != 0L && now - lastAutoEnable < wait) return
        lastAutoEnable = now
        autoEnableFailures++
        runCatching { off.forEach { Settings.Global.putInt(resolver, it, 1) } }
            .onSuccess { Log.i(TAG, "turned back on for the helper: ${off.joinToString()}") }
            .onFailure { Log.w(TAG, "couldn't turn debugging on", it) }
    }

    /**
     * Android forgets debugging authorizations unused for 7 days, which would make the user pair Pairline again after
     * a long uptime. With the secure-settings permission, switch that expiry off (Developer options' "Disable adb
     * authorization timeout"), as part of keeping Talk on iPhone working by itself (D-059).
     */
    private fun keepAuthorization() {
        if (!canWriteSecureSettings()) return
        val resolver = context.contentResolver
        runCatching {
            if (Settings.Global.getLong(resolver, ADB_ALLOWED_CONNECTION_TIME, -1L) != 0L) {
                Settings.Global.putLong(resolver, ADB_ALLOWED_CONNECTION_TIME, 0L)
                Log.i(TAG, "debugging authorizations no longer expire")
            }
        }.onFailure { Log.w(TAG, "couldn't keep the debugging authorization", it) }
    }

    private fun onWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching {
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrDefault(false)
    }

    /**
     * Turns on the MANAGE_ONGOING_CALLS app-op so Android binds the companion call service on the next call (keypad,
     * mute and speaker from the iPhone). Only when the user turns on "Talk on iPhone". Off the main thread.
     */
    /** Silences this phone's ringtone and vibration for the ringing call (D-066). False without the helper. */
    fun silenceRinger(): Boolean {
        val s = service ?: return false
        return runCatching { s.silenceRinger() }
            .onFailure { Log.w(TAG, "couldn't silence the ringer", it) }
            .getOrDefault(false)
    }

    /**
     * The iPhone's internet switches (D-073), only when the user flips one there. Each needs the helper (null
     * without it) and throws what the helper threw; off the main thread.
     */
    fun setTethering(type: Int, on: Boolean): Int? = service?.setTethering(type, on)

    fun setMobileData(on: Boolean): Boolean? = service?.setMobileData(on)

    fun setDataSubscription(subId: Int): Boolean? = service?.setDataSubscription(subId)

    /** Read sync (D-074): Pairline's WRITE_SMS app-op, given with Talk on iPhone. Off the main thread. */
    fun allowWriteSms(): Boolean {
        if (canWriteSms()) return true
        val s = service ?: return false
        return runCatching { s.allowWriteSms(context.packageName, true) }
            .onFailure { Log.w(TAG, "couldn't get the write-SMS app-op", it) }
            .getOrDefault(false)
            .also { if (it) Log.i(TAG, "messages read on the iPhone are now marked read here too") }
    }

    /** Pairline may mark SMS read (its WRITE_SMS app-op is allowed, or it's the default messaging app). */
    fun canWriteSms(): Boolean = runCatching {
        val ops = context.getSystemService(android.app.AppOpsManager::class.java)
        val uid = android.os.Process.myUid()
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(OPSTR_WRITE_SMS, uid, context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(OPSTR_WRITE_SMS, uid, context.packageName)
        }
        mode == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun allowOngoingCalls(): Boolean {
        val s = service ?: return false
        return runCatching { s.allowOngoingCalls(context.packageName) }
            .onFailure { Log.w(TAG, "couldn't enable the companion call service", it) }
            .getOrDefault(false)
    }

    // region Pairing

    /** Starts looking for "Pair device with pairing code" and shows the notification the code is typed into. */
    fun beginPairing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (pairingFinder == null) {
            pairingFinder = AdbServiceFinder(context, AdbServiceFinder.PAIRING).also { finder ->
                finder.onChange = { port ->
                    main.post {
                        val now = _pairing.value
                        if (port != null && (now == Pairing.WAITING || now == Pairing.FAILED)) setPairing(Pairing.FOUND)
                    }
                }
                finder.start()
            }
        }
        setPairing(if (pairingFinder?.port != null) Pairing.FOUND else Pairing.WAITING)
        main.removeCallbacks(pairingTimeout)
        main.postDelayed(pairingTimeout, PAIRING_TIMEOUT_MS)
    }

    private val pairingTimeout = Runnable { cancelPairing() }

    /** The code the user typed into the notification. Worker thread (from [PairingCodeReceiver]). */
    fun submitPairingCode(typed: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val code = typed.filter(Char::isDigit)
        if (code.length != 6) {
            setPairing(Pairing.WRONG_CODE)
            return
        }
        val finder = pairingFinder ?: run { beginPairing(); pairingFinder } ?: return
        setPairing(Pairing.PAIRING)
        val port = finder.await(FIND_TIMEOUT_MS)
        if (port == null) {
            setPairing(Pairing.FAILED)
            return
        }
        try {
            AdbPairingClient(port, code, AdbKey.loadOrCreate(context)).pair()
            prefs.adbPaired = true
            // The user set this up to hear calls on the iPhone: turn it on (the switch is on the same screen).
            prefs.talkOnIphone = true
            Log.i(TAG, "paired with Wireless debugging")
            finishPairing(Pairing.DONE)
            main.post {
                failure = null
                refresh()
            }
        } catch (_: AdbWrongCodeException) {
            setPairing(Pairing.WRONG_CODE)
        } catch (e: Exception) {
            Log.w(TAG, "pairing failed", e)
            setPairing(Pairing.FAILED)
        }
    }

    fun cancelPairing() = finishPairing(Pairing.IDLE)

    private fun finishPairing(result: Pairing) {
        main.removeCallbacks(pairingTimeout)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pairingFinder?.stop()
        pairingFinder = null
        setPairing(result)
    }

    private fun setPairing(p: Pairing) {
        _pairing.value = p
        PairingNotification.show(context, p)
    }

    /** "Forget pairing": stops the helper and deletes Pairline's key. */
    fun forget() {
        cancelPairing()
        stopHelper(revokeSettings = true)
        prefs.adbPaired = false
        worker.execute { AdbKey.delete(context) }
        refresh()
    }

    // endregion

    fun developerOptionsOn(): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1 }
            .getOrDefault(false)

    /** Null when Android doesn't let apps read it; then Pairline just tries. */
    fun wirelessDebuggingOn(): Boolean? =
        runCatching { Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1 }.getOrNull()

    private fun set(s: State) {
        val changed = _state.value != s
        if (changed && (s == State.READY || _state.value == State.READY)) Log.i(TAG, "helper: $s")
        _state.value = s
        if (changed) onChange?.invoke()
    }

    private class StartException(val failure: Failure) : Exception(failure.name)

    companion object {
        private const val TAG = "privileged"
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
        private const val OPSTR_WRITE_SMS = "android:write_sms"

        /** The shell user: root mode starts the helper as it, never as root itself (D-075). */
        private const val SHELL_UID = "2000"
        private val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su",
        )
        private const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"
        /** 15 s, doubled after each try that didn't bring the helper back (at most ~32 min). */
        private const val AUTO_ENABLE_BASE_MS = 15_000L
        private const val FIND_TIMEOUT_MS = 8_000L
        // The first start after an install can be slow (Android verifies the APK's code for the new process).
        private const val ATTACH_TIMEOUT_MS = 20_000L
        private const val START_ATTEMPTS = 3
        private const val STARTED_MARKER = "pairline-helper-launched"
        private const val PAIRING_TIMEOUT_MS = 10 * 60_000L

        fun parseProbe(json: String): Probe {
            val o = JSONObject(json)
            return Probe(
                sdk = o.optInt("sdk"),
                root = o.optBoolean("root"),
                captureOutput = o.optBoolean("captureOutput"),
                modifyPhoneState = o.optBoolean("modifyPhoneState"),
                telephonyOutput = o.optBoolean("telephonyOutput"),
                interceptable = o.optBoolean("interceptable"),
            )
        }
    }
}
