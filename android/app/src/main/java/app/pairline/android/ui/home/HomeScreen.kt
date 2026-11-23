package app.pairline.android.ui.home

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.pairline.android.AppGraph
import app.pairline.android.BuildConfig
import app.pairline.android.R
import app.pairline.android.link.BluetoothStatus
import app.pairline.android.link.LinkSnapshot
import app.pairline.android.link.TransportKind
import app.pairline.android.protocol.Caps
import app.pairline.android.telephony.Perms
import app.pairline.android.telephony.SimSnapshot
import app.pairline.android.telephony.BatteryInfo
import app.pairline.android.ui.Maker
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.PermissionGroup
import app.pairline.android.ui.Route
import app.pairline.android.ui.SystemActions
import app.pairline.android.ui.components.LinkIllustration
import app.pairline.android.ui.components.LinkVisual
import app.pairline.android.ui.components.ListRow
import app.pairline.android.ui.components.LtrText
import app.pairline.android.ui.components.NoteBox
import app.pairline.android.ui.components.PairlineTopBar
import app.pairline.android.ui.components.PrimaryButton
import app.pairline.android.ui.components.SectionCard
import app.pairline.android.ui.components.StatusPill
import app.pairline.android.ui.components.Tone
import app.pairline.android.ui.components.toneColors
import app.pairline.android.ui.refreshTick
import app.pairline.android.ui.rememberPermissionRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class PhoneFacts(val battery: BatteryInfo?, val sims: List<SimSnapshot>, val caps: List<String>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navigate: (Route) -> Unit) {
    val context = LocalContext.current
    val link by AppGraph.link.state.collectAsState()
    val paired by AppGraph.peers.paired.collectAsState()
    val prefsTick by AppGraph.prefs.changes.collectAsState()
    val tick = refreshTick()
    val facts by produceState(PhoneFacts(null, emptyList(), emptyList()), tick, prefsTick) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                PhoneFacts(AppGraph.status.battery(), AppGraph.sims.snapshots(), AppGraph.status.caps())
            }
            delay(3_000)
        }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    val requestPermission = rememberPermissionRequester()
    var confirmUnpair by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            PairlineTopBar(stringResource(R.string.app_name), scrollBehavior = scroll) {
                IconButton(onClick = { navigate(Route.Help) }) { Icon(PIcons.Help, stringResource(R.string.help_title)) }
                IconButton(onClick = { navigate(Route.Settings) }) { Icon(PIcons.Settings, stringResource(R.string.settings_title)) }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            LinkCard(link, paired, prefsTick, now, navigate, onAllowBluetooth = { requestPermission(PermissionGroup.BLUETOOTH) })

            PhoneCard(facts)

            CapabilitiesCard(facts.caps, onFix = { fix ->
                when (fix) {
                    is Fix.Permission -> requestPermission(fix.group)
                    Fix.Settings -> navigate(Route.Settings)
                }
            })

            KeepRunningCard(tick, navigate)

            SectionCard(title = stringResource(R.string.home_actions)) {
                ListRow(
                    title = stringResource(if (paired) R.string.home_pair_new else R.string.home_pair),
                    subtitle = stringResource(R.string.home_pair_sub),
                    icon = PIcons.Qr,
                    onClick = { navigate(Route.Pairing) },
                )
                if (paired) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    ListRow(
                        title = stringResource(R.string.home_unpair),
                        subtitle = stringResource(R.string.home_unpair_sub),
                        icon = PIcons.LinkOff,
                        iconTone = Tone.BAD,
                        onClick = { confirmUnpair = true },
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListRow(
                    title = stringResource(R.string.diag_title),
                    subtitle = stringResource(R.string.home_diag_sub),
                    icon = PIcons.Report,
                    iconTone = Tone.NEUTRAL,
                    onClick = { navigate(Route.Diagnostics) },
                )
            }

            if (BuildConfig.DEBUG) {
                NoteBox(
                    stringResource(if (link.debugTcpListening) R.string.home_debug_tcp_on else R.string.home_debug_tcp_off),
                    Tone.NEUTRAL,
                    PIcons.Code,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmUnpair) {
        UnpairDialog(onDismiss = { confirmUnpair = false }) {
            confirmUnpair = false
            AppGraph.link.unpairLocally()
        }
    }
}

@Composable
fun UnpairDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(PIcons.LinkOff, null) },
        title = { Text(stringResource(R.string.unpair_title)) },
        text = { Text(stringResource(R.string.unpair_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.unpair_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun LinkCard(
    link: LinkSnapshot,
    paired: Boolean,
    @Suppress("UNUSED_PARAMETER") prefsTick: Int,
    now: Long,
    navigate: (Route) -> Unit,
    onAllowBluetooth: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = AppGraph.prefs
    val peer = prefs.peerName ?: stringResource(R.string.generic_iphone_name)
    val btProblem = link.bluetooth == BluetoothStatus.OFF || link.bluetooth == BluetoothStatus.NO_PERMISSION ||
        link.bluetooth == BluetoothStatus.UNSUPPORTED
    val (visual, tone, pill) = when {
        link.connected -> Triple(LinkVisual.CONNECTED, Tone.GOOD, R.string.state_connected)
        btProblem && !link.debugTcpListening -> Triple(LinkVisual.PROBLEM, Tone.BAD, R.string.state_problem)
        !paired -> Triple(LinkVisual.NOT_PAIRED, Tone.NEUTRAL, R.string.state_not_paired)
        else -> Triple(LinkVisual.SEARCHING, Tone.WARN, R.string.state_searching)
    }
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = toneColors(tone).container.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LinkIllustration(visual, Modifier.padding(horizontal = 8.dp))
            StatusPill(stringResource(pill), tone)
            val (title, body) = when {
                link.connected -> stringResource(R.string.home_connected_title, peer) to stringResource(
                    if (link.transport == TransportKind.DEBUG_TCP) R.string.home_connected_body_tcp else R.string.home_connected_body,
                )
                link.bluetooth == BluetoothStatus.OFF -> stringResource(R.string.notif_bt_off_title) to stringResource(R.string.home_bt_off_body)
                link.bluetooth == BluetoothStatus.NO_PERMISSION -> stringResource(R.string.home_bt_perm_title) to stringResource(R.string.home_bt_perm_body)
                link.bluetooth == BluetoothStatus.UNSUPPORTED -> stringResource(R.string.home_bt_unsupported_title) to stringResource(R.string.home_bt_unsupported_body)
                !paired -> stringResource(R.string.notif_not_paired_title) to stringResource(R.string.home_not_paired_body)
                else -> stringResource(R.string.home_waiting_title, peer) to waitingBody(prefs.lastSeen, now)
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                link.bluetooth == BluetoothStatus.OFF -> PrimaryButton(stringResource(R.string.action_turn_on_bt), { SystemActions.requestBluetoothOn(context) }, icon = PIcons.Bluetooth)
                link.bluetooth == BluetoothStatus.NO_PERMISSION -> PrimaryButton(stringResource(R.string.action_allow), onAllowBluetooth, icon = PIcons.Bluetooth)
                !paired && !link.connected -> PrimaryButton(stringResource(R.string.home_pair), { navigate(Route.Pairing) }, icon = PIcons.Qr)
            }
        }
    }
}

@Composable
private fun waitingBody(lastSeen: Long, now: Long): String {
    if (lastSeen <= 0) return stringResource(R.string.home_waiting_never)
    val ago = DateUtils.getRelativeTimeSpanString(lastSeen, now, DateUtils.MINUTE_IN_MILLIS).toString()
    return stringResource(R.string.home_waiting_body, ago)
}

@Composable
private fun PhoneCard(facts: PhoneFacts) {
    SectionCard(title = stringResource(R.string.home_this_phone)) {
        val b = facts.battery
        ListRow(
            title = stringResource(R.string.home_battery),
            subtitle = b?.let {
                stringResource(if (it.charging) R.string.home_battery_charging else R.string.home_battery_level, it.level)
            } ?: "…",
            icon = PIcons.Battery,
            iconTone = when {
                b == null -> Tone.NEUTRAL
                b.charging || b.level > 30 -> Tone.GOOD
                b.level > 15 -> Tone.WARN
                else -> Tone.BAD
            },
        )
        if (facts.sims.isEmpty()) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListRow(
                title = stringResource(R.string.home_no_sims),
                subtitle = stringResource(R.string.home_no_sims_sub),
                icon = PIcons.Sim,
                iconTone = Tone.WARN,
            )
        }
        for (sim in facts.sims) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SimRow(sim)
        }
    }
}

@Composable
private fun SimRow(sim: SimSnapshot) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        app.pairline.android.ui.components.IconBadge(PIcons.Sim, if (sim.signal > 0) Tone.ACCENT else Tone.NEUTRAL)
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            val title = buildString {
                append(stringResource(R.string.home_sim_slot, sim.slot + 1))
                if (sim.carrier.isNotBlank()) append(" · ").append(sim.carrier)
            }
            Text(title, style = MaterialTheme.typography.bodyLarge)
            val details = buildList {
                add(if (sim.net == "none") stringResource(R.string.home_no_service) else sim.net)
                if (sim.roaming) add(stringResource(R.string.home_roaming))
                if (sim.defaultSms && sim.defaultVoice) add(stringResource(R.string.home_default_both))
                else if (sim.defaultSms) add(stringResource(R.string.home_default_sms))
                else if (sim.defaultVoice) add(stringResource(R.string.home_default_calls))
            }
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            sim.number?.let { LtrText(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        SignalBars(sim.signal)
    }
}

/** Four signal bars; they grow from the start edge, so they mirror in RTL like the system's. */
@Composable
private fun SignalBars(level: Int) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(Modifier.size(28.dp, 20.dp)) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * 3) / 4
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4
            val x = if (rtl) size.width - (i + 1) * w - i * gap else i * (w + gap)
            drawRoundRect(if (i < level) on else off, Offset(x, size.height - h), Size(w, h), CornerRadius(2.dp.toPx()))
        }
    }
}

sealed interface Fix {
    data class Permission(val group: PermissionGroup) : Fix
    data object Settings : Fix
}

private data class Feature(val cap: String, @StringRes val title: Int, @StringRes val missing: Int, val fix: Fix?)

private val features = listOf(
    Feature(Caps.SMS_RECEIVE, R.string.cap_sms_receive, R.string.cap_missing_sms, Fix.Permission(PermissionGroup.SMS)),
    Feature(Caps.SMS_SEND, R.string.cap_sms_send, R.string.cap_missing_sms, Fix.Permission(PermissionGroup.SMS)),
    Feature(Caps.SMS_HISTORY, R.string.cap_sms_history, R.string.cap_missing_sms, Fix.Permission(PermissionGroup.SMS)),
    Feature(Caps.CALL_OBSERVE, R.string.cap_call_observe, R.string.cap_missing_phone, Fix.Permission(PermissionGroup.PHONE)),
    Feature(Caps.CALL_CONTROL, R.string.cap_call_control, R.string.cap_missing_phone, Fix.Permission(PermissionGroup.PHONE)),
    Feature(Caps.CALL_DIAL, R.string.cap_call_dial, R.string.cap_missing_phone, Fix.Permission(PermissionGroup.PHONE)),
    Feature(Caps.CALL_DTMF, R.string.cap_call_dtmf, R.string.cap_missing_companion, null),
    Feature(Caps.CONTACTS, R.string.cap_contacts, R.string.cap_missing_contacts, Fix.Permission(PermissionGroup.CONTACTS)),
    Feature(Caps.CALLLOG, R.string.cap_calllog, R.string.cap_missing_calllog, Fix.Permission(PermissionGroup.CALL_LOG)),
    Feature(Caps.CONTACTS_ADD, R.string.cap_contacts_add, R.string.cap_missing_contacts_add, Fix.Settings),
    Feature(Caps.USSD, R.string.cap_ussd, R.string.cap_missing_phone, Fix.Permission(PermissionGroup.PHONE)),
    Feature(Caps.DUALSIM, R.string.cap_dualsim, R.string.cap_missing_dualsim, null),
)

@Composable
private fun CapabilitiesCard(caps: List<String>, onFix: (Fix) -> Unit) {
    SectionCard(title = stringResource(R.string.home_supports), contentPadding = PaddingValues(vertical = 4.dp)) {
        features.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            val ok = f.cap in caps
            ListRow(
                title = stringResource(f.title),
                subtitle = if (ok) null else stringResource(f.missing),
                icon = if (ok) PIcons.CheckCircle else if (f.fix != null) PIcons.Warning else PIcons.Info,
                iconTone = if (ok) Tone.GOOD else if (f.fix != null) Tone.WARN else Tone.NEUTRAL,
                showChevron = false,
                trailing = if (!ok && f.fix != null) {
                    { TextButton(onClick = { onFix(f.fix) }) { Text(stringResource(R.string.action_fix)) } }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun KeepRunningCard(tick: Int, navigate: (Route) -> Unit) {
    val context = LocalContext.current
    val exempt = remember(tick) { SystemActions.isIgnoringBatteryOptimizations(context) }
    val notifications = remember(tick) { Perms.allGranted(context, Perms.NOTIFICATIONS) }
    val maker = remember { Maker.detect() }
    SectionCard(title = stringResource(R.string.home_keep_running)) {
        ListRow(
            title = stringResource(if (exempt) R.string.home_battery_ok else R.string.home_battery_restricted),
            subtitle = stringResource(if (exempt) R.string.home_battery_ok_sub else R.string.home_battery_restricted_sub),
            icon = if (exempt) PIcons.CheckCircle else PIcons.Warning,
            iconTone = if (exempt) Tone.GOOD else Tone.WARN,
            showChevron = false,
            trailing = if (exempt) null else {
                { TextButton(onClick = { SystemActions.requestIgnoreBatteryOptimizations(context) }) { Text(stringResource(R.string.action_fix)) } }
            },
        )
        if (!notifications) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListRow(
                title = stringResource(R.string.home_notifications_off),
                subtitle = stringResource(R.string.home_notifications_off_sub),
                icon = PIcons.Notifications,
                iconTone = Tone.WARN,
                showChevron = false,
                trailing = { TextButton(onClick = { SystemActions.openNotificationSettings(context) }) { Text(stringResource(R.string.action_fix)) } },
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        ListRow(
            title = stringResource(R.string.keepalive_title),
            subtitle = stringResource(R.string.home_keepalive_sub, stringResource(maker.label)),
            icon = PIcons.Bolt,
            onClick = { navigate(Route.KeepAlive) },
        )
    }
}
