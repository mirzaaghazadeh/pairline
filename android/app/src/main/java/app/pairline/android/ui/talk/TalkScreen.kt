package app.pairline.android.ui.talk

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.pairline.android.AppGraph
import app.pairline.android.R
import app.pairline.android.link.LinkService
import app.pairline.android.privileged.PrivilegedManager
import app.pairline.android.privileged.PrivilegedManager.State
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.components.ListRow
import app.pairline.android.ui.components.NoteBox
import app.pairline.android.ui.components.PairlineTopBar
import app.pairline.android.ui.components.SecondaryButton
import app.pairline.android.ui.components.SectionCard
import app.pairline.android.ui.components.Tone
import app.pairline.android.ui.refreshTick

/** Settings › Talk on iPhone (D-046, D-047). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkScreen(back: () -> Unit) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { PairlineTopBar(stringResource(R.string.talk_title), onBack = back, scrollBehavior = scroll) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(R.string.talk_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TalkSetupContent(showReset = true)
        }
    }
}

/** Short label for the Settings row: on, off, or what's missing. */
@Composable
fun talkSummary(): String {
    val state by AppGraph.privileged.state.collectAsState()
    val prefsTick by AppGraph.prefs.changes.collectAsState()
    val on = remember(prefsTick) { AppGraph.prefs.talkOnIphone }
    return when {
        state == State.UNSUPPORTED -> stringResource(R.string.talk_summary_unsupported)
        state != State.READY -> stringResource(R.string.talk_summary_needs_shizuku)
        on -> stringResource(R.string.talk_summary_on)
        else -> stringResource(R.string.talk_summary_off)
    }
}

/**
 * The checklist shared by setup and Settings: Android version, Shizuku installed, started, Pairline allowed, then
 * what this phone supports and the switch. Every step is done by the user; Pairline only opens the right screen.
 */
@Composable
fun ColumnScope.TalkSetupContent(showReset: Boolean) {
    val context = LocalContext.current
    val manager = AppGraph.privileged
    val state by manager.state.collectAsState()
    val probe by manager.probe.collectAsState()
    val prefsTick by AppGraph.prefs.changes.collectAsState()
    val tick = refreshTick()
    LaunchedEffect(tick) { manager.refresh() }
    val on = remember(prefsTick) { AppGraph.prefs.talkOnIphone }

    if (state == State.UNSUPPORTED) {
        NoteBox(stringResource(R.string.talk_android_too_old, Build.VERSION.RELEASE ?: "9"), Tone.WARN, PIcons.Warning)
        return
    }

    SectionCard(title = stringResource(R.string.talk_steps)) {
        val installed = state != State.NOT_INSTALLED
        StepRow(
            done = installed,
            title = stringResource(R.string.talk_step_install),
            subtitle = stringResource(R.string.talk_step_install_sub),
            action = if (!installed) stringResource(R.string.talk_get_shizuku) else null,
            onAction = { openShizukuDownload(context) },
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        val running = installed && state != State.NOT_RUNNING
        StepRow(
            done = running && state != State.OUTDATED,
            title = stringResource(if (state == State.OUTDATED) R.string.talk_step_outdated else R.string.talk_step_start),
            subtitle = stringResource(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) R.string.talk_step_start_sub else R.string.talk_step_start_sub_q,
            ),
            action = if (installed && !running) stringResource(R.string.talk_open_shizuku) else null,
            onAction = { openShizuku(context) },
            secondary = if (installed && !running && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                stringResource(R.string.talk_open_developer)
            } else {
                null
            },
            onSecondary = { openDeveloperOptions(context) },
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        val allowed = state == State.READY || state == State.CONNECTING
        StepRow(
            done = allowed,
            title = stringResource(R.string.talk_step_allow),
            subtitle = stringResource(if (state == State.DENIED) R.string.talk_allow_denied else R.string.talk_step_allow_sub),
            action = when (state) {
                State.NEEDS_PERMISSION -> stringResource(R.string.talk_allow)
                State.DENIED -> stringResource(R.string.talk_open_shizuku)
                else -> null
            },
            onAction = { if (state == State.DENIED) openShizuku(context) else manager.requestPermission() },
        )
    }

    if (state == State.READY) {
        val p = probe
        SectionCard(title = stringResource(R.string.talk_this_phone)) {
            ListRow(
                title = stringResource(if (p?.canListen == true) R.string.talk_listen_yes else R.string.talk_listen_no),
                icon = if (p?.canListen == true) PIcons.CheckCircle else PIcons.Warning,
                iconTone = if (p?.canListen == true) Tone.GOOD else Tone.WARN,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListRow(
                title = stringResource(if (p?.canTalk == true) R.string.talk_talk_yes else R.string.talk_talk_no),
                icon = if (p?.canTalk == true) PIcons.CheckCircle else PIcons.Info,
                iconTone = if (p?.canTalk == true) Tone.GOOD else Tone.NEUTRAL,
            )
        }
        SectionCard {
            ListRow(
                title = stringResource(R.string.talk_switch),
                subtitle = stringResource(R.string.talk_switch_sub),
                icon = PIcons.Phone,
                onClick = { setTalk(context, !on) },
                showChevron = false,
                trailing = { Switch(checked = on, onCheckedChange = { setTalk(context, it) }, enabled = p?.canListen == true) },
            )
            if (showReset && on) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListRow(
                    title = stringResource(R.string.talk_reset),
                    subtitle = stringResource(R.string.talk_reset_sub),
                    icon = PIcons.Refresh,
                    onClick = { AppGraph.audioRelay.resetLearning() },
                    showChevron = false,
                )
            }
        }
    }
    NoteBox(stringResource(R.string.talk_wifi_note), Tone.NEUTRAL, PIcons.Lock)
}

@Composable
private fun StepRow(
    done: Boolean,
    title: String,
    subtitle: String,
    action: String?,
    onAction: () -> Unit,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
) {
    Column {
        ListRow(
            title = title,
            subtitle = subtitle,
            icon = if (done) PIcons.CheckCircle else PIcons.Info,
            iconTone = if (done) Tone.GOOD else Tone.NEUTRAL,
        )
        if (action != null || secondary != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (action != null) SecondaryButton(action, onAction)
                if (secondary != null) {
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton(secondary, onSecondary)
                }
            }
        }
    }
}

private fun setTalk(context: Context, on: Boolean) {
    val prefs = AppGraph.prefs
    if (prefs.talkOnIphone == on) return
    prefs.talkOnIphone = on
    if (on && AppGraph.privileged.state.value == PrivilegedManager.State.READY) {
        AppGraph.io.execute { AppGraph.privileged.allowOngoingCalls() }
    }
    if (!on) AppGraph.audioRelay.stop(null, "turned off")
    LinkService.refresh(context)
}

/** Shizuku is on the Play Store; where there's no Play Store, its download page opens in the browser. */
private fun openShizukuDownload(context: Context) {
    if (!start(context, Intent(Intent.ACTION_VIEW, "market://details?id=${PrivilegedManager.SHIZUKU_PACKAGE}".toUri()))) {
        start(context, Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/download/".toUri()))
    }
}

private fun openShizuku(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(PrivilegedManager.SHIZUKU_PACKAGE)
    if (launch == null || !start(context, launch)) openShizukuDownload(context)
}

/** The user turns Wireless debugging on there themselves; Pairline never changes it (AGENTS.md rule 5). */
private fun openDeveloperOptions(context: Context) {
    if (!start(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))) {
        start(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
    }
}

private fun start(context: Context, intent: Intent): Boolean = try {
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
