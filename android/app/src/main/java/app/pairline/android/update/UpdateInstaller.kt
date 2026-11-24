package app.pairline.android.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import app.pairline.android.AppGraph
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Hands a verified APK to Android's own installer through a [PackageInstaller] session. The session always requires
 * the user's confirmation on Android's screen (AGENTS.md rule 6); nothing is installed silently. The file is hashed
 * again while it's copied into the session, and the session is abandoned if it changed after verification.
 */
internal object UpdateInstaller {
    const val ACTION_STATUS = "app.pairline.android.action.UPDATE_INSTALL_STATUS"

    fun install(context: Context, file: File, expectedSha256: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            session.openWrite("pairline-bridge.apk", 0, file.length()).use { out ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                }
                session.fsync(out)
            }
            if (ApkVerifier.hex(digest.digest()) != expectedSha256) throw IOException("the file changed after it was checked")
            val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                // The installer adds the status extras, so the intent must be mutable on Android 12+. It is explicit.
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            runCatching { session.abandon() }
            throw e
        } finally {
            session.close()
        }
    }
}

/** Results from the install session. Not exported: only the system, through our own PendingIntent, can reach it. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.ACTION_STATUS) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                AppGraph.updates.onConfirmationNeeded(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> AppGraph.updates.onInstallFinished(true, status)
            else -> AppGraph.updates.onInstallFinished(false, status)
        }
    }
}
