package app.pairline.android.ui.setup

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.pairline.android.AppGraph
import app.pairline.android.R
import app.pairline.android.link.LinkService
import app.pairline.android.link.PairingOutcome
import app.pairline.android.ui.GrantState
import app.pairline.android.ui.Maker
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.PermissionGroup
import app.pairline.android.ui.SystemActions
import app.pairline.android.ui.components.BatteryIllustration
import app.pairline.android.ui.components.LinkIllustration
import app.pairline.android.ui.components.LinkVisual
import app.pairline.android.ui.components.NoteBox
import app.pairline.android.ui.components.PrimaryButton
import app.pairline.android.ui.components.PulseIcon
import app.pairline.android.ui.components.QuietButton
import app.pairline.android.ui.components.SuccessIllustration
import app.pairline.android.ui.components.Tone
import app.pairline.android.ui.grantState
import app.pairline.android.ui.pairing.PairingContent
import app.pairline.android.ui.refreshTick
import app.pairline.android.ui.rememberPermissionRequester
import app.pairline.android.ui.talk.TalkSetupContent

/** One screen of the setup wizard. */
sealed interface SetupStep {
    data object Welcome : SetupStep
    data object Privacy : SetupStep
    data object Android11 : SetupStep
    data class Permission(val group: PermissionGroup) : SetupStep
    data object Battery : SetupStep
    data class Autostart(val maker: Maker) : SetupStep
    data object Pair : SetupStep

    /** Optional: call audio on the iPhone through Pairline's helper (D-047, D-054). Android 11+ only (D-056). */
    data object Talk : SetupStep
    data object Done : SetupStep

    companion object {
        fun all(sdk: Int = Build.VERSION.SDK_INT, maker: Maker = Maker.detect()): List<SetupStep> = buildList {
            add(Welcome)
            add(Privacy)
            if (sdk <= Build.VERSION_CODES.R) add(Android11)
            PermissionGroup.wizard.forEach { add(Permission(it)) }
            add(Battery)
            if (maker != Maker.OTHER) add(Autostart(maker))
            add(Pair)
            // Call audio needs Android 11+ (D-056).
            if (sdk >= Build.VERSION_CODES.R) add(Talk)
            add(Done)
        }
    }
}

/**
 * The first-run wizard: what Pairline does, the privacy promise, the Android 11 notice, permissions one group at
 * a time, background running, maker-specific tips, pairing, done. Every system setting is changed by the user
 * in Android's own screens (AGENTS.md rule 5).
 */
@Composable
fun SetupFlow(onFinished: () -> Unit) {
    val steps = remember { SetupStep.all() }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var forward by remember { mutableIntStateOf(1) }
    val step = steps[index]
    fun go(delta: Int) {
        forward = delta
        index = (index + delta).coerceIn(0, steps.lastIndex)
    }
    val next = { go(1) }
    BackHandler(enabled = index > 0) { go(-1) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp)) {
                    if (index > 0 && step != SetupStep.Done) {
                        IconButton(onClick = { go(-1) }) {
                            Icon(PIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                }
                LinearProgressIndicator(
                    progress = { (index + 1f) / steps.size },
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Spacer(Modifier.width(48.dp))
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val dir = if (rtl) -forward else forward
                    (slideInHorizontally { it * dir / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 3 } + fadeOut())
                },
                label = "setup",
                modifier = Modifier.weight(1f),
            ) { s ->
                when (s) {
                    SetupStep.Welcome -> WelcomeStep(next)
                    SetupStep.Privacy -> PrivacyStep(next)
                    SetupStep.Android11 -> Android11Step(next)
                    is SetupStep.Permission -> PermissionStep(s.group, next)
                    SetupStep.Battery -> BatteryStep(next)
                    is SetupStep.Autostart -> AutostartStep(s.maker, next)
                    SetupStep.Pair -> PairStep(next)
                    SetupStep.Talk -> TalkStep(next)
                    SetupStep.Done -> DoneStep(onFinished)
                }
            }
        }
    }
}

/** Shared layout: a scrollable body with an illustration, and buttons pinned at the bottom. */
@Composable
private fun StepScaffold(
    illustration: @Composable () -> Unit,
    title: String,
    body: String?,
    buttons: @Composable ColumnScope.() -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            illustration()
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (body != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(20.dp))
            extra()
            Spacer(Modifier.height(16.dp))
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = buttons,
        )
    }
}

@Composable
private fun Bullet(icon: ImageVector, text: String, tone: Tone = Tone.ACCENT) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        app.pairline.android.ui.components.IconBadge(icon, tone, 36.dp)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun WelcomeStep(next: () -> Unit) {
    StepScaffold(
        illustration = { LinkIllustration(LinkVisual.CONNECTED, showTraffic = true) },
        title = stringResource(R.string.welcome_title),
        body = stringResource(R.string.welcome_body),
        buttons = { PrimaryButton(stringResource(R.string.action_get_started), next) },
        extra = {
            Bullet(PIcons.Phone, stringResource(R.string.welcome_point_calls))
            Bullet(PIcons.Sms, stringResource(R.string.welcome_point_sms))
            Bullet(PIcons.Person, stringResource(R.string.welcome_point_contacts))
            Bullet(PIcons.Dialpad, stringResource(R.string.welcome_point_ussd))
        },
    )
}

@Composable
private fun PrivacyStep(next: () -> Unit) {
    StepScaffold(
        illustration = { PulseIcon(PIcons.Shield, Tone.GOOD) },
        title = stringResource(R.string.privacy_title),
        body = stringResource(R.string.privacy_body),
        buttons = { PrimaryButton(stringResource(R.string.action_continue), next) },
        extra = {
            Bullet(PIcons.Lock, stringResource(R.string.privacy_point_e2e), Tone.GOOD)
            Bullet(PIcons.Person, stringResource(R.string.privacy_point_accounts), Tone.GOOD)
            Bullet(PIcons.Shield, stringResource(R.string.privacy_point_tracking), Tone.GOOD)
            Bullet(PIcons.Code, stringResource(R.string.privacy_point_source), Tone.GOOD)
            Bullet(PIcons.Download, stringResource(R.string.privacy_point_updates), Tone.GOOD)
        },
    )
}

/** Shown on Android 9, 10 and 11, which no longer get security updates (D-038). */
@Composable
private fun Android11Step(next: () -> Unit) {
    val version = when (Build.VERSION.SDK_INT) {
        Build.VERSION_CODES.P -> 9
        Build.VERSION_CODES.Q -> 10
        else -> 11
    }
    StepScaffold(
        illustration = { PulseIcon(PIcons.Warning, Tone.WARN) },
        title = stringResource(R.string.android11_title, version),
        body = stringResource(R.string.android11_body, version),
        buttons = { PrimaryButton(stringResource(R.string.action_understood), next) },
        extra = { NoteBox(stringResource(R.string.android11_tip), Tone.NEUTRAL, PIcons.Info) },
    )
}

@Composable
private fun PermissionStep(group: PermissionGroup, next: () -> Unit) {
    val activity = LocalActivity.current ?: return
    val tick = refreshTick()
    val state = remember(tick, group) { grantState(activity, group) }
    val request = rememberPermissionRequester { g ->
        if (grantState(activity, g) == GrantState.GRANTED) next()
    }
    val tone = when (state) {
        GrantState.GRANTED -> Tone.GOOD
        GrantState.DENIED, GrantState.BLOCKED -> Tone.WARN
        GrantState.NOT_ASKED -> Tone.ACCENT
    }
    StepScaffold(
        illustration = { PulseIcon(group.icon, tone) },
        title = stringResource(group.title),
        body = stringResource(group.why),
        buttons = {
            when (state) {
                GrantState.GRANTED -> PrimaryButton(stringResource(R.string.action_continue), next)
                GrantState.NOT_ASKED -> {
                    PrimaryButton(stringResource(R.string.action_allow), { request(group) })
                    QuietButton(stringResource(if (group.required) R.string.action_skip_for_now else R.string.action_not_now), next)
                }
                GrantState.DENIED -> {
                    PrimaryButton(stringResource(R.string.action_try_again), { request(group) })
                    QuietButton(stringResource(R.string.action_skip_for_now), next)
                }
                GrantState.BLOCKED -> {
                    PrimaryButton(stringResource(R.string.action_open_app_info), { SystemActions.openAppSettings(activity) })
                    QuietButton(stringResource(R.string.action_skip_for_now), next)
                }
            }
        },
        extra = {
            Text(
                stringResource(group.detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            when (state) {
                GrantState.GRANTED -> NoteBox(stringResource(R.string.perm_granted_note), Tone.GOOD, PIcons.CheckCircle)
                GrantState.DENIED -> NoteBox(stringResource(R.string.perm_denied_note), Tone.WARN, PIcons.Warning)
                GrantState.BLOCKED -> NoteBox(
                    stringResource(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) R.string.perm_blocked_note_13 else R.string.perm_blocked_note,
                    ),
                    Tone.WARN,
                    PIcons.Warning,
                )
                GrantState.NOT_ASKED -> if (!group.required) {
                    NoteBox(stringResource(R.string.perm_optional_note), Tone.NEUTRAL, PIcons.Info)
                }
            }
        },
    )
}

@Composable
private fun BatteryStep(next: () -> Unit) {
    val context = LocalContext.current
    val tick = refreshTick()
    val exempt = remember(tick) { SystemActions.isIgnoringBatteryOptimizations(context) }
    StepScaffold(
        illustration = { BatteryIllustration() },
        title = stringResource(R.string.battery_title),
        body = stringResource(R.string.battery_body),
        buttons = {
            if (exempt) {
                PrimaryButton(stringResource(R.string.action_continue), next)
            } else {
                PrimaryButton(stringResource(R.string.battery_allow), { SystemActions.requestIgnoreBatteryOptimizations(context) })
                QuietButton(stringResource(R.string.action_skip_for_now), next)
            }
        },
        extra = {
            if (exempt) {
                NoteBox(stringResource(R.string.battery_done_note), Tone.GOOD, PIcons.CheckCircle)
            } else {
                NoteBox(stringResource(R.string.battery_note), Tone.NEUTRAL, PIcons.Info)
            }
        },
    )
}

@Composable
private fun AutostartStep(maker: Maker, next: () -> Unit) {
    val context = LocalContext.current
    StepScaffold(
        illustration = { PulseIcon(PIcons.Bolt, Tone.ACCENT) },
        title = stringResource(R.string.autostart_title, stringResource(maker.label)),
        body = stringResource(R.string.autostart_body),
        buttons = {
            PrimaryButton(stringResource(R.string.action_open_settings), { maker.open(context) }, icon = PIcons.OpenExternal)
            QuietButton(stringResource(R.string.action_done_continue), next)
        },
        extra = { NoteBox(stringResource(maker.steps), Tone.NEUTRAL, PIcons.Info) },
    )
}

/** Optional "Talk on iPhone" step: everything can be finished later in Settings. */
@Composable
private fun TalkStep(next: () -> Unit) {
    StepScaffold(
        illustration = { PulseIcon(PIcons.Phone, Tone.ACCENT) },
        title = stringResource(R.string.talk_setup_title),
        body = stringResource(R.string.talk_body),
        buttons = {
            PrimaryButton(stringResource(R.string.action_continue), next)
            QuietButton(stringResource(R.string.talk_setup_later), next)
        },
        extra = { TalkSetupContent(showReset = false) },
    )
}

@Composable
private fun PairStep(next: () -> Unit) {
    val outcome by AppGraph.pairing.outcome.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.pair_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            PairingContent(onDone = next)
            Spacer(Modifier.height(16.dp))
        }
        if (outcome != PairingOutcome.PAIRED) {
            QuietButton(stringResource(R.string.pair_later), next, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun DoneStep(onFinished: () -> Unit) {
    val context = LocalContext.current
    val paired by AppGraph.peers.paired.collectAsState()
    StepScaffold(
        illustration = { SuccessIllustration() },
        title = stringResource(R.string.done_title),
        body = stringResource(if (paired) R.string.done_body_paired else R.string.done_body_unpaired),
        buttons = {
            PrimaryButton(stringResource(R.string.action_open_pairline), {
                AppGraph.prefs.setupDone = true
                LinkService.start(context, "setup finished", byUser = true)
                onFinished()
            })
        },
        extra = {
            NoteBox(stringResource(R.string.done_note), Tone.NEUTRAL, PIcons.Notifications)
        },
    )
}
