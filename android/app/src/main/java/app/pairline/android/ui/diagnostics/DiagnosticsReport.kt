package app.pairline.android.ui.diagnostics

import android.content.Context
import android.os.Build
import app.pairline.android.AppGraph
import app.pairline.android.BuildConfig
import app.pairline.android.link.LinkSnapshot
import app.pairline.android.log.Log
import app.pairline.android.log.Redactor
import app.pairline.android.telephony.Perms
import app.pairline.android.ui.LocaleHelper
import app.pairline.android.ui.Maker
import app.pairline.android.ui.PermissionGroup
import app.pairline.android.ui.SystemActions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything the diagnostics report says, gathered first so the text itself is built by a pure function. */
data class DiagnosticsFacts(
    val appVersion: String,
    val buildType: String,
    val androidRelease: String,
    val sdk: Int,
    val device: String,
    val language: String,
    val keyStorage: String,
    val paired: Boolean,
    val pairedAt: Long,
    val peerApp: String?,
    val peerOs: String?,
    val link: LinkSnapshot,
    val lastSeen: Long,
    val batteryExempt: Boolean,
    val maker: String,
    val permissions: Map<String, Boolean>,
    val caps: List<String>,
    val simCount: Int,
    val companionBound: Boolean,
    val logLines: List<String>,
)

/**
 * The readable diagnostics report (AGENTS.md rule 9). It contains no phone numbers, names, message text, keys or
 * Bluetooth addresses; every line goes through [Redactor] as well. The screen shows exactly this text before it's
 * shared.
 */
object DiagnosticsReport {
    fun gather(context: Context): DiagnosticsFacts {
        val prefs = AppGraph.prefs
        return DiagnosticsFacts(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            buildType = BuildConfig.BUILD_TYPE,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            language = LocaleHelper.current(context).ifEmpty { "system (${Locale.getDefault().toLanguageTag()})" },
            keyStorage = AppGraph.identity.storage?.name?.lowercase() ?: "not loaded",
            paired = AppGraph.peers.pinned() != null,
            pairedAt = prefs.pairedAt,
            peerApp = prefs.peerApp,
            peerOs = prefs.peerOs,
            link = AppGraph.link.state.value,
            lastSeen = prefs.lastSeen,
            batteryExempt = SystemActions.isIgnoringBatteryOptimizations(context),
            maker = Maker.detect().name.lowercase(),
            permissions = PermissionGroup.wizard.associate { g -> g.name.lowercase() to Perms.allGranted(context, g.permissions) } +
                ("write_contacts" to Perms.allGranted(context, Perms.WRITE_CONTACTS)),
            caps = AppGraph.status.caps(),
            simCount = AppGraph.sims.activeCount(),
            companionBound = prefs.companionBoundAt != 0L,
            logLines = Log.storedLines(400),
        )
    }

    fun build(f: DiagnosticsFacts, now: Long = System.currentTimeMillis()): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        fun t(ms: Long) = if (ms > 0) time.format(Date(ms)) else "never"
        val l = f.link
        val text = buildString {
            appendLine("Pairline Bridge diagnostics")
            appendLine("Created: ${t(now)}")
            appendLine()
            appendLine("App: ${f.appVersion}, ${f.buildType}")
            appendLine("Android: ${f.androidRelease} (API ${f.sdk}), ${f.device}, maker profile: ${f.maker}")
            appendLine("Language: ${f.language}")
            appendLine("Identity key: ${f.keyStorage}")
            appendLine()
            appendLine("Paired: ${if (f.paired) "yes, since ${t(f.pairedAt)}" else "no"}")
            if (f.paired) appendLine("iPhone app: ${f.peerApp ?: "?"}, iOS ${f.peerOs ?: "?"}")
            appendLine("Link service: ${if (l.running) "running" else "stopped"}")
            appendLine("Bluetooth: ${l.bluetooth.name.lowercase()}, advertising: ${yes(l.advertising)}")
            appendLine("Connected: ${if (l.connected) "yes, via ${l.transport?.name?.lowercase()} since ${t(l.connectedSince)}" else "no, last seen ${t(f.lastSeen)}"}")
            if (f.buildType == "debug") appendLine("Debug TCP transport listening: ${yes(l.debugTcpListening)}")
            appendLine("Background running allowed: ${yes(f.batteryExempt)}")
            appendLine()
            appendLine("Permissions: " + f.permissions.entries.joinToString(", ") { "${it.key} ${if (it.value) "yes" else "no"}" })
            appendLine("Capabilities: ${f.caps.joinToString(", ").ifEmpty { "none" }}")
            appendLine("Active SIMs: ${f.simCount}")
            appendLine("Companion call service has been bound: ${yes(f.companionBound)}")
            appendLine()
            appendLine("Recent log (${f.logLines.size} lines, oldest first):")
            f.logLines.forEach { appendLine(it) }
        }
        // Belt and braces: the log is redacted when written, and the whole report again here.
        return text.lines().joinToString("\n") { line -> if (line.startsWith("Created:")) line else Redactor.redact(line) }
    }

    private fun yes(b: Boolean) = if (b) "yes" else "no"
}
