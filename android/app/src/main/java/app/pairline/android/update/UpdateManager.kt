package app.pairline.android.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri
import app.pairline.android.BuildConfig
import app.pairline.android.log.Log
import app.pairline.android.ui.Notifications
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Why a check or an update didn't work. Shown to the user in plain words; logged as the enum name. */
enum class UpdateFailure { NETWORK, RESPONSE, CHECKSUM, SIGNATURE, PACKAGE, STORAGE, INSTALL }

/** What the last check found. */
data class UpdateStatus(
    val enabled: Boolean,
    /** Last successful check (ms), or 0. */
    val lastChecked: Long,
    val checking: Boolean,
    /** Set when the last attempt failed. */
    val lastError: UpdateFailure?,
    /** A newer release than the installed app, if the last check found one. */
    val available: ReleaseCandidate?,
    val installedVersion: String,
)

/** The user-started update, from "Update" to Android's install screen. */
sealed interface InstallFlow {
    data object Idle : InstallFlow
    /** "Install unknown apps" is off for Pairline; the user turns it on in Android's settings. */
    data object NeedsPermission : InstallFlow
    data class Downloading(val done: Long, val total: Long) : InstallFlow
    data object Verifying : InstallFlow
    /** Android's confirmation screen is open, or can be opened again with [confirm]. */
    data class WaitingForUser(val confirm: Intent?) : InstallFlow
    data class Failed(val reason: UpdateFailure) : InstallFlow
}

/**
 * The daily update check against GitHub Releases and the verified update (D-041, D-042). On by default, explained
 * during setup, off with one switch in Settings. Installing always goes through Android's own confirmation screen.
 */
class UpdateManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e(TAG, "update task failed", e) },
    )
    private val running = AtomicBoolean(false)
    private val installing = AtomicBoolean(false)

    /** Debug builds only: pretend to be another version, so the update path can be tested (devtools provider). */
    @Volatile
    var debugVersionOverride: String? = null
        set(value) {
            field = value
            publish()
        }

    private val _status = MutableStateFlow(readStatus(checking = false))
    val status: StateFlow<UpdateStatus> = _status

    private val _flow = MutableStateFlow<InstallFlow>(InstallFlow.Idle)
    val flow: StateFlow<InstallFlow> = _flow

    private val installedVersion: String
        get() = debugVersionOverride?.takeIf { BuildConfig.DEBUG } ?: BuildConfig.VERSION_NAME

    /**
     * A debug build (`app.pairline.android.debug`) can't install a release from GitHub: to Android it's another app,
     * signed with another key, so the download always ended in "isn't a newer version of this app". It offers one only
     * while devtools set a version to test the update flow with ([debugVersionOverride]); new debug builds come from
     * the developer's computer.
     */
    private val offersGitHubReleases: Boolean
        get() = !BuildConfig.DEBUG || debugVersionOverride != null

    var enabled: Boolean
        get() = prefs.getBoolean(ENABLED, true)
        set(value) {
            prefs.edit { putBoolean(ENABLED, value) }
            Log.i(TAG, "automatic update check turned ${if (value) "on" else "off"}")
            publish()
        }

    // ---- Checking ----

    /** The daily check: runs when it's on and a day has passed (3 hours after a failure). */
    fun checkIfDue(reason: String) {
        if (!enabled) return
        val due = UpdateSchedule.isDue(System.currentTimeMillis(), prefs.getLong(LAST_ATTEMPT, 0), prefs.getString(LAST_ERROR, null) != null)
        if (due) check(reason)
    }

    /** "Check now" in Settings; works with the automatic check turned off too. */
    fun checkNow() = check("user")

    private fun check(reason: String) {
        if (!running.compareAndSet(false, true)) return
        publish(checking = true)
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                prefs.edit { putLong(LAST_ATTEMPT, now) }
                val failure: UpdateFailure? = try {
                    val latest = ReleasePicker.latest(GitHubClient.releases())
                    prefs.edit {
                        putLong(LAST_CHECKED, now)
                        remove(LAST_ERROR)
                        if (latest != null) putString(LATEST, latest.toJson().toString()) else remove(LATEST)
                    }
                    if (!offersGitHubReleases) {
                        Log.i(TAG, "update check ($reason): a debug build; GitHub's ${latest?.version ?: "-"} is the release app, which it can't install")
                    } else if (ReleasePicker.isNewer(latest, installedVersion)) {
                        Log.i(TAG, "update check ($reason): $installedVersion → ${latest!!.version} available")
                        notifyOnce(latest)
                    } else {
                        Log.i(TAG, "update check ($reason): $installedVersion is current")
                    }
                    null
                } catch (e: GitHubClient.HttpException) {
                    Log.w(TAG, "update check ($reason) failed: GitHub answered ${e.code}")
                    UpdateFailure.RESPONSE
                } catch (e: IOException) {
                    Log.w(TAG, "update check ($reason) failed: network", e)
                    UpdateFailure.NETWORK
                } catch (e: org.json.JSONException) {
                    Log.w(TAG, "update check ($reason) failed: unexpected reply")
                    UpdateFailure.RESPONSE
                }
                if (failure != null) prefs.edit { putString(LAST_ERROR, failure.name) }
            } finally {
                running.set(false)
                publish(checking = false)
            }
        }
    }

    private fun notifyOnce(release: ReleaseCandidate) {
        val version = release.version.toString()
        if (prefs.getString(NOTIFIED, null) == version) return
        prefs.edit { putString(NOTIFIED, version) }
        Notifications.showUpdate(context, version)
    }

    // ---- Updating ----

    /** "Update": asks for "install unknown apps" if needed, then downloads, verifies and opens Android's installer. */
    fun startUpdate() {
        val release = _status.value.available ?: return
        if (!context.packageManager.canRequestPackageInstalls()) {
            Log.i(TAG, "update needs the install-apps permission")
            // Android may restart the app when the user allows it; remember to carry on afterwards.
            prefs.edit { putLong(WAITING_FOR_PERMISSION, System.currentTimeMillis()) }
            _flow.value = InstallFlow.NeedsPermission
            return
        }
        prefs.edit { remove(WAITING_FOR_PERMISSION) }
        if (!installing.compareAndSet(false, true)) return
        _flow.value = InstallFlow.Downloading(0, release.apkSize)
        scope.launch {
            val dir = File(context.cacheDir, "updates")
            try {
                dir.deleteRecursively()
                if (!dir.mkdirs()) throw IOException("no cache directory")
                val file = File(dir, release.apkName)
                var lastShown = 0L
                val sha = GitHubClient.download(release.apkUrl, file, release.apkSize) { done, total ->
                    if (done - lastShown >= 64 * 1024 || done == total) {
                        lastShown = done
                        _flow.value = InstallFlow.Downloading(done, total)
                    }
                }
                if (sha != release.sha256) {
                    Log.w(TAG, "update ${release.version} not installed: checksum check failed")
                    fail(UpdateFailure.CHECKSUM)
                    return@launch
                }
                verifyAndInstall(file, release.sha256, release.version.toString())
            } catch (e: GitHubClient.HttpException) {
                Log.w(TAG, "update download failed: GitHub answered ${e.code}")
                fail(UpdateFailure.NETWORK)
            } catch (e: IOException) {
                Log.w(TAG, "update download or install failed", e)
                fail(if (lowOnSpace(release.apkSize)) UpdateFailure.STORAGE else UpdateFailure.NETWORK)
            } catch (e: SecurityException) {
                Log.w(TAG, "update install refused", e)
                fail(UpdateFailure.INSTALL)
            } finally {
                installing.set(false)
            }
        }
    }

    /** The checks every update file goes through, then Android's installer. Never installs an unverified file. */
    private fun verifyAndInstall(file: File, sha256: String, version: String) {
        _flow.value = InstallFlow.Verifying
        val problem = ApkVerifier.verify(context, file, sha256)
        if (problem != null) {
            Log.w(TAG, "update $version not installed: ${problem.name.lowercase()} check failed")
            fail(UpdateFailure.valueOf(problem.name))
            return
        }
        Log.i(TAG, "update $version verified; opening Android's installer")
        _flow.value = InstallFlow.WaitingForUser(null)
        UpdateInstaller.install(context, file, sha256)
        file.delete()
    }

    /**
     * Debug builds only (devtools provider): runs the same checks and installer on an APK already in the app's cache,
     * so the install path can be tested with a locally built update. Release builds refuse.
     */
    fun debugInstallCached(name: String, sha256: String) {
        if (!BuildConfig.DEBUG || !installing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val file = File(File(context.cacheDir, "updates"), File(name).name)
                verifyAndInstall(file, sha256.lowercase(), "local")
            } catch (e: Exception) {
                Log.w(TAG, "local test update failed", e)
                fail(UpdateFailure.INSTALL)
            } finally {
                installing.set(false)
            }
        }
    }

    /** Whether a failed download was probably a full disk (twice the APK: the cache copy and Android's session). */
    private fun lowOnSpace(apkSize: Long): Boolean = runCatching {
        val storage = context.getSystemService(StorageManager::class.java) ?: return false
        storage.getAllocatableBytes(storage.getUuidForPath(context.cacheDir)) < apkSize * 2
    }.getOrDefault(false)

    private fun fail(reason: UpdateFailure) {
        _flow.value = InstallFlow.Failed(reason)
        File(context.cacheDir, "updates").deleteRecursively()
    }

    /** Android's own "Install unknown apps" screen for Pairline. The user decides (AGENTS.md rule 5). */
    fun permissionSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    /**
     * The activity came back: continue if the user just allowed installing apps. Android restarts the app when that
     * permission changes on some versions, so a request from the last 10 minutes counts too.
     */
    fun onResume() {
        val asked = prefs.getLong(WAITING_FOR_PERMISSION, 0)
        val recent = asked > 0 && System.currentTimeMillis() - asked in 0..600_000L
        if ((_flow.value == InstallFlow.NeedsPermission || recent) && context.packageManager.canRequestPackageInstalls()) {
            prefs.edit { remove(WAITING_FOR_PERMISSION) }
            _flow.value = InstallFlow.Idle
            startUpdate()
        }
    }

    /** From [UpdateInstallReceiver]: Android wants the user to confirm. */
    fun onConfirmationNeeded(confirm: Intent?) {
        _flow.value = InstallFlow.WaitingForUser(confirm)
        if (confirm == null) return
        try {
            context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Android may block opening it from the background; the dialog offers a button instead.
            Log.w(TAG, "couldn't open the install screen by itself", e)
        }
    }

    fun onInstallFinished(success: Boolean, status: Int) {
        if (success) {
            Log.i(TAG, "update installed")
            _flow.value = InstallFlow.Idle
        } else {
            // STATUS_FAILURE_ABORTED (3) is the user tapping Cancel on Android's screen: not an error to show.
            Log.i(TAG, "update not installed (installer status $status)")
            _flow.value = if (status == 3) InstallFlow.Idle else InstallFlow.Failed(UpdateFailure.INSTALL)
        }
        File(context.cacheDir, "updates").deleteRecursively()
    }

    fun dismiss() {
        if (installing.get()) return
        prefs.edit { remove(WAITING_FOR_PERMISSION) }
        _flow.value = InstallFlow.Idle
    }

    // ---- State ----

    private fun publish(checking: Boolean = _status.value.checking) {
        _status.value = readStatus(checking)
    }

    private fun readStatus(checking: Boolean): UpdateStatus {
        val latest = prefs.getString(LATEST, null)?.let { runCatching { ReleaseCandidate.fromJson(JSONObject(it)) }.getOrNull() }
        val version = installedVersion
        return UpdateStatus(
            enabled = enabled,
            lastChecked = prefs.getLong(LAST_CHECKED, 0),
            checking = checking,
            lastError = prefs.getString(LAST_ERROR, null)?.let { runCatching { UpdateFailure.valueOf(it) }.getOrNull() },
            available = latest?.takeIf { offersGitHubReleases && ReleasePicker.isNewer(it, version) },
            installedVersion = version,
        )
    }

    private companion object {
        const val TAG = "update"
        const val ENABLED = "enabled"
        const val LAST_CHECKED = "last_checked"
        const val LAST_ATTEMPT = "last_attempt"
        const val LAST_ERROR = "last_error"
        const val LATEST = "latest"
        const val NOTIFIED = "notified_version"
        const val WAITING_FOR_PERMISSION = "waiting_for_permission"
    }
}
