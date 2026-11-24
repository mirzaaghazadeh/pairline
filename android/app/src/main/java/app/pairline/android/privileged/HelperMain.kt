package app.pairline.android.privileged

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import app.pairline.android.BuildConfig
import java.io.File
import kotlin.system.exitProcess

/**
 * Pairline's helper process (D-054). Pairline starts it as the shell user through this phone's own Wireless
 * debugging (or the user starts it from a computer on Android 10) with `app_process`, from Pairline's own APK:
 * see [HelperCommand]. It hosts [PrivilegedService], Pairline's only code with shell rights (AGENTS.md rule 7), and
 * hands it to the app through [HelperProvider].
 *
 * It runs no other code, writes no logs (rule 9) and stops by itself: when Pairline's APK changes (an update), after
 * 10 minutes without Pairline running, when Pairline asks ([PrivilegedService.destroy]), and at every restart.
 */
object HelperMain {
    private const val RETRY_MS = 5_000L
    private const val GIVE_UP_MS = 10 * 60_000L

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }
        val user = args.firstOrNull { it.startsWith("--user=") }?.substringAfter('=')?.toIntOrNull() ?: 0
        val service = try {
            PrivilegedService(packageContext(BuildConfig.APPLICATION_ID, user))
        } catch (e: Throwable) {
            System.err.println("pairline-helper: couldn't start: $e")
            exitProcess(1)
        }
        Link(service, user).attach()
        Looper.loop()
        exitProcess(0)
    }

    /** Keeps [service] handed to Pairline, again after Pairline's process restarts. */
    private class Link(private val service: PrivilegedService, private val user: Int) {
        private val main = Handler(Looper.getMainLooper())
        private val apk = File(System.getenv("CLASSPATH").orEmpty())
        private var appToken: IBinder? = null
        private var missingSince = 0L

        fun attach() {
            val token = runCatching { HelperHandoff.send(service, user) }.getOrNull()
            if (token == null) {
                waitForApp()
                return
            }
            appToken = token
            missingSince = 0L
            // When Pairline's process ends, give the call its own microphone and earpiece back (a relayed call can't
            // go on without Pairline), then wait for it to come back (or stop, see below).
            runCatching {
                token.linkToDeath({ main.post { service.releaseAudio(); appToken = null; waitForApp() } }, 0)
            }.onFailure { main.post { waitForApp() } }
        }

        private fun waitForApp() {
            if (!apk.isFile) exitProcess(0) // Pairline was updated or removed: this helper's code is out of date.
            val now = SystemClock.elapsedRealtime()
            if (missingSince == 0L) missingSince = now
            if (now - missingSince > GIVE_UP_MS) exitProcess(0)
            if (appRunning()) attach() else main.postDelayed(::waitForApp, RETRY_MS)
        }

        /** Whether Pairline's main process is running (the shell user can see every process). */
        private fun appRunning(): Boolean = File("/proc").listFiles { f -> f.name.all(Char::isDigit) }.orEmpty().any { dir ->
            runCatching { File(dir, "cmdline").readText().substringBefore('\u0000') == BuildConfig.APPLICATION_ID }
                .getOrDefault(false)
        }
    }

    /** A Context for Pairline's package inside this process, as Android's own shell tools build one. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun packageContext(packageName: String, user: Int): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        val system = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        val flags = Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
        if (user == 0) return system.createPackageContext(packageName, flags)
        return Context::class.java
            .getMethod("createPackageContextAsUser", String::class.java, Int::class.javaPrimitiveType, UserHandle::class.java)
            .invoke(system, packageName, flags, UserHandle.getUserHandleForUid(user * 100_000)) as Context
    }
}

/**
 * Hands the helper's binder to Pairline: the helper calls Pairline's [HelperProvider], which only the shell (or
 * root) user may call. Runs in the helper process, where Android's internal interfaces are reachable.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal object HelperHandoff {
    const val AUTHORITY_SUFFIX = ".helper"
    const val METHOD_ATTACH = "attach"
    const val KEY_SERVICE = "service"
    const val KEY_TOKEN = "token"

    /** Pairline's token (a binder whose death means Pairline's process ended), or null if Pairline didn't answer. */
    fun send(service: IBinder, user: Int): IBinder? {
        val authority = BuildConfig.APPLICATION_ID + AUTHORITY_SUFFIX
        val serviceManager = Class.forName("android.os.ServiceManager")
        val amBinder = serviceManager.getMethod("getService", String::class.java).invoke(null, "activity") as IBinder
        val am = Class.forName("android.app.IActivityManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, amBinder)!!
        val holder = am.javaClass.getMethod(
            "getContentProviderExternal",
            String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java,
        ).invoke(am, authority, user, null, authority) ?: return null
        val provider = holder.javaClass.getField("provider").get(holder) ?: return null
        try {
            val extras = Bundle().apply { putBinder(KEY_SERVICE, service) }
            val reply = call(provider, authority, extras) ?: return null
            return reply.getBinder(KEY_TOKEN)
        } finally {
            runCatching {
                am.javaClass.getMethod(
                    "removeContentProviderExternalAsUser",
                    String::class.java, IBinder::class.java, Int::class.javaPrimitiveType,
                ).invoke(am, authority, null, user)
            }
        }
    }

    /** IContentProvider.call, whose parameters changed in Android 11 and 12. */
    private fun call(provider: Any, authority: String, extras: Bundle): Bundle? {
        val methods = provider.javaClass.methods.filter { it.name == "call" }
        val shell = "com.android.shell"
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val source = AttributionSource.Builder(Process.myUid()).setPackageName(shell).build()
                methods.first { it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java }
                    .invoke(provider, source, authority, HelperHandoff.METHOD_ATTACH, null, extras)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                methods.first { it.parameterTypes.size == 6 }
                    .invoke(provider, shell, null, authority, HelperHandoff.METHOD_ATTACH, null, extras)
            else ->
                methods.first { it.parameterTypes.size == 5 && it.parameterTypes[0] == String::class.java }
                    .invoke(provider, shell, authority, HelperHandoff.METHOD_ATTACH, null, extras)
        } as Bundle?
    }
}
