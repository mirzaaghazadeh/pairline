package app.pairline.android.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.pairline.android.BuildConfig
import app.pairline.android.log.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * Pairline's side of Shizuku (D-046). Shizuku is a separate app the user installs and starts (with Wireless
 * debugging, a computer, or root); Pairline never starts it, never turns debugging on (AGENTS.md rule 5) and works
 * fully without it. With it, the [PrivilegedService] can relay call audio and enable the companion call service.
 */
class PrivilegedManager(private val context: Context) {

    enum class State {
        /** Android 9: the shell user has no call-audio rights there, so Shizuku wouldn't help. */
        UNSUPPORTED,
        NOT_INSTALLED,

        /** Installed but not started (it needs starting again after every reboot unless it runs with root). */
        NOT_RUNNING,

        /** A Shizuku older than version 13, which can't host Pairline's service. */
        OUTDATED,
        NEEDS_PERMISSION,
        DENIED,
        CONNECTING,
        READY,
    }

    /** What the shell user may do on this phone, from [PrivilegedService.probe]. */
    data class Probe(
        val sdk: Int,
        val root: Boolean,
        val captureOutput: Boolean,
        val modifyPhoneState: Boolean,
        val telephonyOutput: Boolean,
    ) {
        /** The caller's voice can be captured. */
        val canListen: Boolean get() = captureOutput
        /** The iPhone's microphone can be fed into the call. */
        val canTalk: Boolean get() = telephonyOutput && (modifyPhoneState || root)
    }

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State.NOT_INSTALLED)
    private val _probe = MutableStateFlow<Probe?>(null)
    private var started = false
    private var bound = false

    @Volatile
    var service: IPrivilegedService? = null
        private set

    val state: StateFlow<State> = _state
    val probe: StateFlow<Probe?> = _probe

    /** Called on every change, so the bridge can resend its capabilities. */
    var onChange: (() -> Unit)? = null

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, PrivilegedService::class.java.name))
            .daemon(false)
            .processNameSuffix("privileged")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE * 100 + PrivilegedService.VERSION)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = binder?.takeIf { it.pingBinder() }?.let { IPrivilegedService.Stub.asInterface(it) }
            service = s
            if (s == null) {
                Log.w(TAG, "privileged service connected without a live binder")
                set(State.NOT_RUNNING)
                return
            }
            val p = runCatching { parseProbe(s.probe()) }
                .onFailure { Log.w(TAG, "privileged probe failed", it) }
                .getOrNull()
            _probe.value = p
            Log.i(TAG, "privileged service ready (sdk ${p?.sdk}, listen ${p?.canListen}, talk ${p?.canTalk}, root ${p?.root})")
            set(State.READY)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            Log.i(TAG, "privileged service disconnected")
            refresh()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { main.post { refresh() } }
    private val binderDead = Shizuku.OnBinderDeadListener {
        main.post {
            service = null
            bound = false
            refresh()
        }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST_CODE) {
            main.post {
                Log.i(TAG, "Shizuku permission ${if (result == PackageManager.PERMISSION_GRANTED) "granted" else "denied"}")
                refresh()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            set(State.UNSUPPORTED)
            return
        }
        Shizuku.addBinderReceivedListenerSticky(binderReceived, main)
        Shizuku.addBinderDeadListener(binderDead, main)
        Shizuku.addRequestPermissionResultListener(permissionResult, main)
        refresh()
    }

    /** Re-reads Shizuku's state and binds the service when everything is in place. Main thread. */
    fun refresh() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return set(State.UNSUPPORTED)
        if (!isShizukuInstalled()) return set(State.NOT_INSTALLED)
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return set(State.NOT_RUNNING)
        if (runCatching { Shizuku.isPreV11() || Shizuku.getVersion() < 13 }.getOrDefault(true)) return set(State.OUTDATED)
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        if (!granted) {
            val denied = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
            return set(if (denied) State.DENIED else State.NEEDS_PERMISSION)
        }
        if (service != null) return set(State.READY)
        set(State.CONNECTING)
        if (!bound) {
            bound = true
            runCatching { Shizuku.bindUserService(args, connection) }.onFailure {
                bound = false
                Log.w(TAG, "couldn't start the privileged service", it)
                set(State.NOT_RUNNING)
            }
        }
    }

    /** Shows Shizuku's own permission dialog. The user decides there; Pairline can't grant itself anything. */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }.onFailure { Log.w(TAG, "permission request failed", it) }
    }

    /**
     * Turns on the MANAGE_ONGOING_CALLS app-op so Android binds the companion call service on the next call (keypad,
     * mute and speaker from the iPhone). Only when the user sets up "Talk on iPhone". Off the main thread.
     */
    fun allowOngoingCalls(): Boolean {
        val s = service ?: return false
        return runCatching { s.allowOngoingCalls(BuildConfig.APPLICATION_ID) }
            .onFailure { Log.w(TAG, "couldn't enable the companion call service", it) }
            .getOrDefault(false)
    }

    fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        // Sui (Shizuku inside a root module) has no app but still delivers a binder.
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    }

    private fun set(s: State) {
        if (s != State.READY) {
            if (_state.value == State.READY) Log.i(TAG, "privileged service: $s")
        }
        val changed = _state.value != s
        _state.value = s
        if (changed) onChange?.invoke()
    }

    companion object {
        private const val TAG = "privileged"
        private const val REQUEST_CODE = 4701
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        fun parseProbe(json: String): Probe {
            val o = JSONObject(json)
            return Probe(
                sdk = o.optInt("sdk"),
                root = o.optBoolean("root"),
                captureOutput = o.optBoolean("captureOutput"),
                modifyPhoneState = o.optBoolean("modifyPhoneState"),
                telephonyOutput = o.optBoolean("telephonyOutput"),
            )
        }
    }
}
