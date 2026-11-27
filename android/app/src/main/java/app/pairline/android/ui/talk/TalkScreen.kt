package app.pairline.android.ui.talk

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import app.pairline.android.AppGraph
import app.pairline.android.R
import app.pairline.android.link.LinkService
import app.pairline.android.privileged.DeveloperSettings
import app.pairline.android.privileged.HelperCommand
import app.pairline.android.privileged.PrivilegedManager
import app.pairline.android.privileged.PrivilegedManager.Pairing
import app.pairline.android.privileged.PrivilegedManager.State
import app.pairline.android.privileged.TalkStatus
import app.pairline.android.ui.ClearSetupGuideOnResume
import app.pairline.android.ui.Maker
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.SetupGuide
import app.pairline.android.ui.SystemActions
import app.pairline.android.ui.components.ListRow
import app.pairline.android.ui.components.NoteBox
import app.pairline.android.ui.components.PairlineTopBar
import app.pairline.android.ui.components.SecondaryButton
import app.pairline.android.ui.components.SectionCard
import app.pairline.android.ui.components.Tone
import app.pairline.android.ui.refreshTick

/** Settings › Talk on iPhone (D-046, D-047, D-054). */
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

/** What `status.talk` says now (D-107), re-read when the helper, the settings or the screen change. */
@Composable
fun rememberTalkStatus(): TalkStatus {
    val state by AppGraph.privileged.state.collectAsState()
    val prefsTick by AppGraph.prefs.changes.collectAsState()
    val tick = refreshTick()
    return remember(state, prefsTick, tick) { AppGraph.privileged.talkStatus() }
}

/** Short label for the Settings and home rows: on, off, or what's missing and why (D-107). */
@Composable
fun talkSummary(): String = stringResource(talkSummaryRes(rememberTalkStatus()))

@StringRes
fun talkSummaryRes(talk: TalkStatus): Int = when (talk.state) {
    TalkStatus.UNSUPPORTED -> R.string.talk_summary_unsupported
    TalkStatus.READY -> R.string.talk_summary_on
    TalkStatus.OFF -> R.string.talk_summary_off
    TalkStatus.SETUP -> R.string.talk_summary_not_set_up
    TalkStatus.STARTING -> R.string.talk_summary_starting
    else -> when (talk.reason) {
        TalkStatus.REASON_WIFI -> R.string.talk_summary_waiting_wifi
        TalkStatus.REASON_DEBUGGING -> R.string.talk_summary_needs_wireless
        TalkStatus.REASON_PAIRING -> R.string.talk_summary_pair_again
        TalkStatus.REASON_ROOT -> R.string.talk_summary_failed_root
        else -> R.string.talk_summary_failed
    }
}

/**
 * The checklist shared by setup and Settings. Each step ticks itself as the user finishes it; Pairline only opens the
 * right screen and never changes a setting (AGENTS.md rule 5). Then: what this phone supports, the switch, and tips.
 */
@Composable
fun ColumnScope.TalkSetupContent(showReset: Boolean) {
    val manager = AppGraph.privileged
    val state by manager.state.collectAsState()
    val probe by manager.probe.collectAsState()
    val pairing by manager.pairing.collectAsState()
    val prefsTick by AppGraph.prefs.changes.collectAsState()
    val tick = refreshTick()
    LaunchedEffect(tick) { manager.refresh() }
    // While this screen is open the helper may run for its checks, even with Talk on iPhone off.
    val context = LocalContext.current
    DisposableEffect(Unit) {
        manager.setupOpen = true
        // The iPhone's "Set up on Android phone" notification led here, or isn't needed now (D-107).
        TalkPrompt.cancel(context)
        onDispose { manager.setupOpen = false }
    }
    val on = remember(prefsTick) { AppGraph.prefs.talkOnIphone }

    if (state == State.UNSUPPORTED) {
        NoteBox(stringResource(R.string.talk_android_too_old, Build.VERSION.RELEASE ?: "9"), Tone.WARN, PIcons.Warning)
        return
    }
    if (manager.rootMode) RootSteps(state) else WirelessSteps(state, pairing, tick)

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
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            WifiLinkRow()
        }
    }
    if (state == State.READY || state == State.IDLE) {
        SectionCard {
            ListRow(
                title = stringResource(R.string.talk_switch),
                subtitle = stringResource(R.string.talk_switch_sub),
                icon = PIcons.Phone,
                onClick = { setTalk(context, !on) },
                showChevron = false,
                trailing = {
                    Switch(checked = on, onCheckedChange = { setTalk(context, it) }, enabled = state == State.IDLE || probe?.canListen == true)
                },
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (manager.paired || state == State.READY)) ManageCard()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && state != State.READY) ComputerAlternative()
    NoteBox(stringResource(R.string.talk_wifi_note), Tone.NEUTRAL, PIcons.Lock)
}

/** Root mode (Settings › Advanced, D-075): one step, the helper started through the phone's own root. */
@Composable
private fun RootSteps(state: State) {
    val manager = AppGraph.privileged
    SectionCard(title = stringResource(R.string.talk_steps)) {
        StepRow(
            done = state == State.READY,
            title = stringResource(R.string.talk_root_title),
            subtitle = stringResource(
                when {
                    state == State.READY -> R.string.talk_root_running
                    state == State.STARTING -> R.string.talk_root_starting
                    state == State.FAILED && manager.failure == PrivilegedManager.Failure.NO_ROOT -> R.string.talk_root_failed
                    state == State.FAILED -> R.string.talk_start_failed
                    else -> R.string.talk_root_idle
                },
            ),
            action = if (state == State.FAILED) stringResource(R.string.talk_try_again) else null,
            onAction = { manager.retry() },
        )
    }
}

/** Android 11+: Developer options, Wireless debugging, pairing, then the helper starts by itself. */
@Composable
private fun WirelessSteps(state: State, pairing: Pairing, tick: Int) {
    val context = LocalContext.current
    val manager = AppGraph.privileged
    val ready = state == State.READY
    val developer = ready || state != State.NEEDS_DEVELOPER_OPTIONS
    val wireless = ready || (developer && state != State.NEEDS_WIRELESS_DEBUGGING)
    val paired = ready || manager.paired
    val notifications = remember(tick) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val maker = remember { Maker.detect() }

    // The step's instructions wait in a notification while the user is in Android Settings (D-074).
    ClearSetupGuideOnResume()
    val devTitle = stringResource(R.string.talk_step_dev)
    val devText = stringResource(devSteps(maker))
    val wirelessTitle = stringResource(R.string.talk_step_wireless)
    val wirelessText = stringResource(R.string.talk_step_wireless_sub)
    SectionCard(title = stringResource(R.string.talk_steps)) {
        StepRow(
            done = developer,
            title = devTitle,
            subtitle = if (developer) stringResource(R.string.talk_step_dev_done) else devText,
            action = if (!developer) stringResource(R.string.talk_open_about) else null,
            onAction = {
                SetupGuide.show(context, devTitle, devText)
                start(context, DeveloperSettings.aboutPhone())
            },
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        StepRow(
            done = wireless,
            title = wirelessTitle,
            subtitle = if (wireless) stringResource(R.string.talk_step_wireless_done) else wirelessText,
            action = if (developer && !wireless) stringResource(R.string.talk_open_developer) else null,
            onAction = {
                SetupGuide.show(context, wirelessTitle, wirelessText)
                openWirelessDebugging(context)
            },
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        val pairSub = when {
            paired -> R.string.talk_pair_done
            !notifications -> R.string.talk_pair_notifications
            pairing == Pairing.WAITING -> R.string.talk_pair_waiting
            pairing == Pairing.FOUND -> R.string.talk_pair_found
            pairing == Pairing.PAIRING -> R.string.talk_pair_pairing
            pairing == Pairing.WRONG_CODE -> R.string.talk_pair_wrong
            pairing == Pairing.FAILED -> R.string.talk_pair_failed
            else -> R.string.talk_step_pair_sub
        }
        StepRow(
            done = paired,
            title = stringResource(R.string.talk_step_pair),
            subtitle = stringResource(pairSub),
            action = when {
                paired || !wireless || pairing == Pairing.PAIRING -> null
                !notifications -> stringResource(R.string.talk_allow_notifications)
                else -> stringResource(R.string.talk_pair)
            },
            onAction = {
                if (!notifications) {
                    start(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                } else {
                    manager.beginPairing()
                    openWirelessDebugging(context)
                }
            },
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        StepRow(
            done = ready,
            title = stringResource(R.string.talk_step_start),
            subtitle = stringResource(
                when {
                    ready -> R.string.talk_start_running
                    state == State.STARTING -> R.string.talk_start_starting
                    state == State.FAILED && manager.failure == PrivilegedManager.Failure.NO_SERVICE -> R.string.talk_start_no_service
                    state == State.FAILED -> R.string.talk_start_failed
                    state == State.IDLE -> R.string.talk_start_idle
                    else -> R.string.talk_start_auto
                },
            ),
            action = if (state == State.FAILED) stringResource(R.string.talk_try_again) else null,
            onAction = { manager.retry() },
        )
    }
}

/** Fallback: the computer command, for phones without Wi-Fi (no Wireless debugging) or when pairing doesn't work. */
@Composable
private fun ComputerAlternative() {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    val command = remember(open) { if (open) HelperCommand.computerCommand(context) else null }
    SectionCard {
        ListRow(
            title = stringResource(R.string.talk_computer_title),
            subtitle = if (open) stringResource(R.string.talk_computer_sub) else null,
            icon = PIcons.Code,
            onClick = { open = !open },
            showChevron = false,
            trailing = null,
        )
        if (open && command != null) CommandBox(command)
    }
}

@Composable
private fun CommandBox(command: String) {
    val context = LocalContext.current
    val copied = stringResource(R.string.talk_command_copied)
    Column(Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 12.dp)) {
        SelectionContainer {
            Text(
                command,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.padding(top = 8.dp))
        SecondaryButton(stringResource(R.string.talk_copy_command), {
            SystemActions.copyText(context, "Pairline", command)
            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
        }, icon = PIcons.Copy)
    }
}

/**
 * Call audio travels over the Wi-Fi link (D-047): with the iPhone linked over Bluetooth only, Talk on iPhone is set
 * up but the audio stays here. Says so, and offers Android's own hotspot (it also gives the iPhone internet).
 */
@Composable
private fun WifiLinkRow() {
    val context = LocalContext.current
    val link by AppGraph.link.state.collectAsState()
    val overWifi = app.pairline.android.link.TransportKind.WIFI in link.transports
    when {
        overWifi -> ListRow(
            title = stringResource(R.string.talk_wifi_yes),
            icon = PIcons.CheckCircle,
            iconTone = Tone.GOOD,
        )
        link.connected -> Column {
            ListRow(
                title = stringResource(R.string.talk_wifi_no),
                subtitle = stringResource(R.string.talk_wifi_no_sub),
                icon = PIcons.Warning,
                iconTone = Tone.WARN,
            )
            Row(Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 12.dp)) {
                SecondaryButton(stringResource(R.string.settings_phone_hotspot_open), { SystemActions.openTetherSettings(context) })
            }
        }
        else -> ListRow(
            title = stringResource(R.string.talk_wifi_unknown),
            icon = PIcons.Info,
            iconTone = Tone.NEUTRAL,
        )
    }
}

@Composable
private fun ManageCard() {
    SectionCard(title = stringResource(R.string.talk_manage)) {
        ListRow(
            title = stringResource(R.string.talk_tip_restart),
            subtitle = stringResource(R.string.talk_tip_restart_sub),
            icon = PIcons.Refresh,
            showChevron = false,
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        ListRow(
            title = stringResource(R.string.talk_tip_hotspot),
            subtitle = stringResource(R.string.talk_tip_hotspot_sub),
            icon = PIcons.Hotspot,
            showChevron = false,
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        ListRow(
            title = stringResource(R.string.talk_tip_banking),
            subtitle = stringResource(R.string.talk_tip_banking_sub),
            icon = PIcons.Shield,
            showChevron = false,
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        var confirmForget by remember { mutableStateOf(false) }
        ListRow(
            title = stringResource(R.string.talk_forget),
            subtitle = stringResource(R.string.talk_forget_sub),
            icon = PIcons.Delete,
            iconTone = Tone.WARN,
            // Undoing it means pairing with Wireless debugging again, so it asks first (D-064).
            onClick = { confirmForget = true },
            showChevron = false,
        )
        if (confirmForget) {
            AlertDialog(
                onDismissRequest = { confirmForget = false },
                title = { Text(stringResource(R.string.talk_forget_confirm_title)) },
                text = { Text(stringResource(R.string.talk_forget_confirm_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmForget = false
                        AppGraph.privileged.forget()
                    }) { Text(stringResource(R.string.talk_forget), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }
    }
}

private fun devSteps(maker: Maker): Int = when (maker) {
    Maker.SAMSUNG -> R.string.dev_steps_samsung
    Maker.XIAOMI -> R.string.dev_steps_xiaomi
    Maker.OPPO -> R.string.dev_steps_oppo
    Maker.VIVO -> R.string.dev_steps_vivo
    Maker.HUAWEI, Maker.OTHER -> R.string.dev_steps_other
}

@Composable
private fun StepRow(
    done: Boolean,
    title: String,
    subtitle: String,
    action: String?,
    onAction: () -> Unit,
) {
    Column {
        ListRow(
            title = title,
            subtitle = subtitle,
            icon = if (done) PIcons.CheckCircle else PIcons.Info,
            iconTone = if (done) Tone.GOOD else Tone.NEUTRAL,
        )
        if (action != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryButton(action, onAction)
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

private fun setTalk(context: Context, on: Boolean) {
    val prefs = AppGraph.prefs
    if (prefs.talkOnIphone == on) return
    prefs.talkOnIphone = on
    if (on && AppGraph.privileged.state.value == State.READY) {
        AppGraph.io.execute { AppGraph.privileged.allowOngoingCalls() }
    }
    if (!on) AppGraph.audioRelay.stop(null, "turned off")
    AppGraph.privileged.refresh()
    LinkService.refresh(context)
}

/**
 * The user turns Wireless debugging on, and pairs, there themselves (AGENTS.md rule 5). Its own page where the Settings
 * app opens it (D-064), else Developer options scrolled to it, else About phone.
 */
private fun openWirelessDebugging(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && start(context, DeveloperSettings.wirelessDebuggingPage())) return
    if (!start(context, DeveloperSettings.wirelessDebugging())) start(context, DeveloperSettings.aboutPhone())
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
