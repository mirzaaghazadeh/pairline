package app.pairline.android.debugtools

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import app.pairline.android.AppGraph
import app.pairline.android.log.Log
import app.pairline.android.ui.ScreenshotMode

/**
 * DEBUG BUILDS ONLY (D-018). Lets tests pair and drive the app without a camera:
 *
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/pairing   # column `uri`
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/state
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/setup-done
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/screenshot-mode-on   # or -off
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/update-pretend/1.0.0  # or /off
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/update-check
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/update-state
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/update-start
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/update-install-cached/<name>/<sha256>
 *
 * Only the shell user (adb), root and the app itself may call it.
 */
class DevToolsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        checkCaller()
        if (uri.pathSegments.size == 3 && uri.pathSegments[0] == "update-install-cached") {
            // Checks and installs cache/updates/<name> (put there with `run-as`), expecting SHA-256 <hex>.
            AppGraph.updates.debugInstallCached(uri.pathSegments[1], uri.pathSegments[2])
            return MatrixCursor(arrayOf("started")).apply { addRow(arrayOf<Any>(true)) }
        }
        if (uri.pathSegments.size == 2 && uri.pathSegments[0] == "update-pretend") {
            // Pretend to run an older version, so the update path can be tried against real releases.
            val version = uri.pathSegments[1].takeIf { it != "off" }
            AppGraph.updates.debugVersionOverride = version
            Log.i("devtools", "update check pretends version ${version ?: "(off)"}")
            return MatrixCursor(arrayOf("version")).apply { addRow(arrayOf<Any>(version ?: "")) }
        }
        return when (uri.lastPathSegment) {
            "update-check" -> MatrixCursor(arrayOf("started")).apply {
                AppGraph.updates.checkNow()
                addRow(arrayOf<Any>(true))
            }
            "update-state" -> MatrixCursor(arrayOf("installed", "available", "last_checked", "last_error", "checking", "flow")).apply {
                val s = AppGraph.updates.status.value
                addRow(
                    arrayOf<Any>(
                        s.installedVersion,
                        s.available?.version?.toString() ?: "",
                        s.lastChecked,
                        s.lastError?.name ?: "",
                        s.checking,
                        AppGraph.updates.flow.value.toString(),
                    ),
                )
            }
            "update-start" -> MatrixCursor(arrayOf("started")).apply {
                AppGraph.updates.startUpdate()
                addRow(arrayOf<Any>(true))
            }
            "pairing" -> MatrixCursor(arrayOf("uri", "expires_at")).apply {
                // The secret is returned only to adb on a debug build, and never logged.
                val offer = AppGraph.pairing.currentOrNewOffer()
                addRow(arrayOf<Any>(offer.uri, offer.expiresAt))
            }
            "state" -> MatrixCursor(arrayOf("paired", "connected", "transport", "bluetooth", "tcp", "setup_done")).apply {
                val s = AppGraph.link.state.value
                addRow(
                    arrayOf<Any>(
                        AppGraph.peers.pinned() != null,
                        s.connected,
                        s.transport?.name ?: "",
                        s.bluetooth.name,
                        s.debugTcpListening,
                        AppGraph.prefs.setupDone,
                    ),
                )
            }
            "setup-done" -> MatrixCursor(arrayOf("setup_done")).apply {
                AppGraph.prefs.setupDone = true
                Log.i("devtools", "setup marked done by adb")
                addRow(arrayOf<Any>(true))
            }
            "screenshot-mode-on", "screenshot-mode-off" -> MatrixCursor(arrayOf("screenshot_mode")).apply {
                ScreenshotMode.enabled = uri.lastPathSegment == "screenshot-mode-on"
                Log.i("devtools", "screenshot mode ${if (ScreenshotMode.enabled) "on" else "off"}")
                addRow(arrayOf<Any>(ScreenshotMode.enabled))
            }
            else -> null
        }
    }

    private fun checkCaller() {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != ROOT_UID && uid != Process.myUid()) {
            throw SecurityException("devtools are for adb only")
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
    }
}
