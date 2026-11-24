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
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/wifi              # Wi-Fi link state
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/media-echo-on     # or -off
 *     adb shell content query --uri 'content://app.pairline.android.debug.devtools/send-test-file?size=5000000'
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
        return when (uri.lastPathSegment) {
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
            "wifi" -> MatrixCursor(arrayOf("wifi", "transports", "addresses", "port", "media_open", "hotspot")).apply {
                // Debug builds only: local addresses help point fake_remote.py at a real phone on the same Wi-Fi.
                val s = AppGraph.link.state.value
                addRow(
                    arrayOf<Any>(
                        s.wifi.name,
                        s.transports.joinToString("+") { it.name },
                        s.wifiHint?.addresses?.joinToString(",") ?: "",
                        s.wifiHint?.port ?: 0,
                        s.mediaOpen,
                        AppGraph.hotspot.state.value.toString(),
                    ),
                )
            }
            "media-echo-on", "media-echo-off" -> MatrixCursor(arrayOf("media_echo")).apply {
                WifiDevTools.setEcho(uri.lastPathSegment == "media-echo-on")
                addRow(arrayOf<Any>(WifiDevTools.echo))
            }
            "send-test-file" -> MatrixCursor(arrayOf("size", "sha256")).apply {
                val size = uri.getQueryParameter("size")?.toLongOrNull()?.coerceIn(0, 1L shl 30) ?: 1_000_000
                addRow(arrayOf<Any>(size, WifiDevTools.sendTestFile(context!!, size)))
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
