package app.pairline.android.debugtools

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import app.pairline.android.AppGraph
import app.pairline.android.audio.AudioPackets
import app.pairline.android.audio.Opus
import app.pairline.android.audio.Pcm
import app.pairline.android.link.wifi.HotspotState
import app.pairline.android.log.Log
import app.pairline.android.ui.ScreenshotMode
import kotlin.math.PI
import kotlin.math.sin

/**
 * DEBUG BUILDS ONLY (D-018). Lets tests pair and drive the app without a camera:
 *
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/pairing   # column `uri`
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/state
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/setup-done
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/screenshot-mode-on   # or -off
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/wifi              # Wi-Fi link state
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/media-echo-on     # or -off
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/opus-selftest     # this phone's Opus
 *     adb shell content query --uri 'content://app.pairline.android.debug.devtools/send-test-file?size=5000000'
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

    /**
     * Encodes 50 frames (1 s) of a 440 Hz tone at 8000 peak with Android's Opus encoder and decodes every packet with
     * its decoder. A healthy codec gives about 50 packets of about 80 bytes (32 kbit/s) and a level near 5600 (RMS).
     */
    private fun opusSelfTest(): Array<Any> {
        if (!Opus.available) return arrayOf(false, 0, 0, 0, 0, "no Opus encoder or decoder on this phone")
        return try {
            Opus.encoder().use { encoder ->
                Opus.decoder().use { decoder ->
                    val sizes = ArrayList<Int>()
                    val levels = ArrayList<Int>()
                    for (n in 0 until 50) {
                        val tone = ShortArray(AudioPackets.FRAME_SAMPLES) { i ->
                            val t = (n * AudioPackets.FRAME_SAMPLES + i).toDouble() / AudioPackets.SAMPLE_RATE
                            (8000 * sin(2 * PI * 440 * t)).toInt().toShort()
                        }
                        for (packet in encoder.encode(Pcm.toBytes(tone))) {
                            sizes += packet.size
                            decoder.decode(packet)?.let { levels += Pcm.rms(it) }
                        }
                    }
                    val level = levels.takeLast(20).let { if (it.isEmpty()) 0 else it.sum() / it.size }
                    Log.i("devtools", "Opus self-test: ${sizes.size} packets, ${levels.size} decoded, level $level")
                    arrayOf(true, sizes.size, if (sizes.isEmpty()) 0 else sizes.sum() / sizes.size, levels.size, level, "")
                }
            }
        } catch (e: Exception) {
            arrayOf(true, 0, 0, 0, 0, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

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
                        // Never the passphrase, even here.
                        when (val h = AppGraph.hotspot.state.value) {
                            is HotspotState.On -> "on" + (h.band?.let { " ($it GHz)" } ?: "")
                            is HotspotState.Failed -> "failed: ${h.reason}"
                            else -> h.javaClass.simpleName.lowercase()
                        },
                    ),
                )
            }
            "opus-selftest" -> MatrixCursor(arrayOf("available", "packets", "avg_bytes", "decoded", "level", "error")).apply {
                // Call audio over Bluetooth (D-072): this phone's own Opus codec, a 440 Hz tone there and back.
                addRow(opusSelfTest())
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
