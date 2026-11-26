package app.pairline.android.ui.home

import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pairline.android.AppGraph
import app.pairline.android.BuildConfig
import app.pairline.android.R
import app.pairline.android.files.TransferItem
import app.pairline.android.files.TransferPhase
import app.pairline.android.link.BluetoothStatus
import app.pairline.android.link.LinkService
import app.pairline.android.link.LinkSnapshot
import app.pairline.android.link.TransportKind
import app.pairline.android.link.WifiLinkStatus
import app.pairline.android.link.remote.Direct
import app.pairline.android.link.remote.DirectReason
import app.pairline.android.link.remote.DirectStatus
import app.pairline.android.protocol.Caps
import app.pairline.android.telephony.Perms
import app.pairline.android.telephony.SimSnapshot
import app.pairline.android.telephony.BatteryInfo
import app.pairline.android.ui.Maker
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.PermissionGroup
import app.pairline.android.ui.Route
import app.pairline.android.ui.ScreenshotMode
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
import app.pairline.android.ui.update.UpdateBanner
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

            UpdateBanner()

            if (paired && link.wifi != WifiLinkStatus.OFF) FilesCard()

            PhoneCard(facts)

            CapabilitiesCard(facts.caps, onFix = { fix ->
                when (fix) {
                    is Fix.Permission -> requestPermission(fix.group)
                    Fix.Settings -> navigate(Route.Settings)
                    Fix.Talk -> navigate(Route.Talk)
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
    // With the Wi-Fi link listening, a Bluetooth problem isn't a dead end: the iPhone can still come over Wi-Fi.
    val wifiReady = link.wifi == WifiLinkStatus.LISTENING
    // The user stopped Pairline in Settings: say so, and offer Start, instead of the (stale) link state.
    if (prefs.bridgeStopped) {
        StoppedCard()
        return
    }
    val (visual, tone, pill) = when {
        link.connected -> Triple(LinkVisual.CONNECTED, Tone.GOOD, R.string.state_connected)
        btProblem && !link.debugTcpListening && !wifiReady -> Triple(LinkVisual.PROBLEM, Tone.BAD, R.string.state_problem)
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
                    when {
                        link.transport == TransportKind.DIRECT -> R.string.home_connected_body_direct
                        link.transport == TransportKind.DEBUG_TCP && !ScreenshotMode.enabled && TransportKind.WIFI !in link.transports ->
                            R.string.home_connected_body_tcp
                        TransportKind.WIFI in link.transports &&
                            link.transports.any { it != TransportKind.WIFI && it != TransportKind.DIRECT } -> R.string.home_connected_body_both
                        TransportKind.WIFI in link.transports -> R.string.home_connected_body_wifi
                        else -> R.string.home_connected_body
                    },
                )
                link.bluetooth == BluetoothStatus.OFF && wifiReady && paired ->
                    stringResource(R.string.home_waiting_title, peer) to stringResource(R.string.home_bt_off_wifi_body)
                link.bluetooth == BluetoothStatus.OFF -> stringResource(R.string.notif_bt_off_title) to stringResource(R.string.home_bt_off_body)
                link.bluetooth == BluetoothStatus.NO_PERMISSION -> stringResource(R.string.home_bt_perm_title) to stringResource(R.string.home_bt_perm_body)
                link.bluetooth == BluetoothStatus.UNSUPPORTED -> stringResource(R.string.home_bt_unsupported_title) to stringResource(R.string.home_bt_unsupported_body)
                !paired -> stringResource(R.string.notif_not_paired_title) to stringResource(R.string.home_not_paired_body)
                else -> stringResource(R.string.home_waiting_title, peer) to waitingBody(prefs.lastSeen, now)
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val direct by AppGraph.direct.status.collectAsState()
            if (paired && direct.active) DirectLine(direct)
            when {
                link.bluetooth == BluetoothStatus.OFF -> PrimaryButton(stringResource(R.string.action_turn_on_bt), { SystemActions.requestBluetoothOn(context) }, icon = PIcons.Bluetooth)
                link.bluetooth == BluetoothStatus.NO_PERMISSION -> PrimaryButton(stringResource(R.string.action_allow), onAllowBluetooth, icon = PIcons.Bluetooth)
                !paired && !link.connected -> PrimaryButton(stringResource(R.string.home_pair), { navigate(Route.Pairing) }, icon = PIcons.Qr)
            }
        }
    }
}

/** Remote mode's direct connection through the home router (D-080): open, or why not. */
@Composable
private fun DirectLine(s: DirectStatus) {
    val open = s.direct == Direct.OPEN
    val text = when {
        s.direct == null -> stringResource(R.string.direct_checking)
        open -> stringResource(R.string.direct_open)
        s.ipv6Unconfirmed -> stringResource(R.string.direct_ipv6_unconfirmed)
        else -> stringResource(R.string.direct_not_possible, stringResource(directReasonText(s.reason)))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (open) PIcons.CheckCircle else PIcons.Info,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (open) toneColors(Tone.GOOD).content else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@StringRes
private fun directReasonText(reason: DirectReason?): Int = when (reason) {
    DirectReason.NOT_ON_WIFI -> R.string.direct_reason_wifi
    DirectReason.CARRIER_NAT -> R.string.direct_reason_carrier_nat
    DirectReason.DOUBLE_NAT -> R.string.direct_reason_double_nat
    DirectReason.NO_PUBLIC_ADDRESS -> R.string.direct_reason_no_address
    DirectReason.NO_MAPPING, null -> R.string.direct_reason_no_mapping
    DirectReason.LISTEN_FAILED -> R.string.direct_reason_listen
}

@Composable
private fun StoppedCard() {
    val context = LocalContext.current
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = toneColors(Tone.NEUTRAL).container.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LinkIllustration(LinkVisual.NOT_PAIRED, Modifier.padding(horizontal = 8.dp))
            StatusPill(stringResource(R.string.state_stopped), Tone.NEUTRAL)
            Text(stringResource(R.string.home_stopped_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.home_stopped_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(
                stringResource(R.string.settings_start_bridge),
                { LinkService.start(context, "started on the home screen", byUser = true) },
                icon = PIcons.Power,
            )
        }
    }
}

/** "Send a file to iPhone" and the latest transfers (M5). Received files are announced in notifications. */
@Composable
private fun FilesCard() {
    val context = LocalContext.current
    val items by AppGraph.files.items.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val app = context.applicationContext
            Thread({ runCatching { AppGraph.files.send(uris, null) } }, "pairline-pick").start()
            android.widget.Toast.makeText(
                app,
                if (AppGraph.link.wifiToken != null) R.string.share_sending else R.string.share_waiting,
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
    SectionCard(title = stringResource(R.string.home_files)) {
        ListRow(
            title = stringResource(R.string.home_send_file),
            subtitle = stringResource(
                if (Build.VERSION.SDK_INT >= 29) R.string.home_send_file_sub else R.string.home_send_file_sub_legacy,
            ),
            icon = PIcons.File,
            onClick = { pick.launch(arrayOf("*/*")) },
        )
        for (item in items.take(3)) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListRow(
                title = item.name,
                subtitle = transferText(item),
                icon = if (item.outgoing) PIcons.Share else PIcons.File,
                iconTone = when (item.phase) {
                    TransferPhase.DONE -> Tone.GOOD
                    TransferPhase.FAILED -> Tone.BAD
                    TransferPhase.CANCELLED -> Tone.NEUTRAL
                    else -> Tone.ACCENT
                },
                onClick = when {
                    item.phase == TransferPhase.DONE && item.uri != null -> {
                        { openReceived(context, item) }
                    }
                    item.phase in setOf(TransferPhase.WAITING, TransferPhase.OFFERED, TransferPhase.ACTIVE) -> {
                        { AppGraph.files.cancel(item.id) }
                    }
                    else -> null
                },
                showChevron = false,
            )
        }
    }
}

@Composable
private fun transferText(item: TransferItem): String = when (item.phase) {
    TransferPhase.WAITING -> stringResource(R.string.file_waiting_wifi)
    TransferPhase.OFFERED, TransferPhase.ACTIVE -> {
        val label = stringResource(if (item.outgoing) R.string.file_sending else R.string.file_receiving)
        if (item.size > 0 && item.transferred > 0) "$label ${item.transferred * 100 / item.size}%" else label
    }
    TransferPhase.DONE -> stringResource(
        when {
            item.outgoing -> R.string.file_sent
            Build.VERSION.SDK_INT >= 29 -> R.string.file_received
            else -> R.string.file_received_legacy
        },
    )
    TransferPhase.CANCELLED -> stringResource(R.string.file_cancelled)
    TransferPhase.FAILED -> stringResource(if (item.outgoing) R.string.file_failed_send else R.string.file_failed_receive)
}

private fun openReceived(context: android.content.Context, item: TransferItem) {
    val uri = item.uri ?: return
    val type = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"
    val view = android.content.Intent(android.content.Intent.ACTION_VIEW)
        .setDataAndType(uri, type)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(android.content.Intent.createChooser(view, null)) }
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

/** Four signal bars, growing left to right in every language, like Android's own signal icon. */
@Composable
private fun SignalBars(level: Int) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.size(28.dp, 20.dp)) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * 3) / 4
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4
            val x = i * (w + gap)
            drawRoundRect(if (i < level) on else off, Offset(x, size.height - h), Size(w, h), CornerRadius(2.dp.toPx()))
        }
    }
}

sealed interface Fix {
    data class Permission(val group: PermissionGroup) : Fix
    data object Settings : Fix
    /** Talk on iPhone's setup (Wireless debugging, D-054). Optional, so its rows offer "Set up" instead of a warning. */
    data object Talk : Fix
}

private data class Feature(val cap: String, @StringRes val title: Int, @StringRes val missing: Int, val fix: Fix?)

/** Call audio on the iPhone needs Android 11 or later (D-047, D-056). */
private val talkSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

private val features = listOf(
    Feature(Caps.SMS_RECEIVE, R.string.cap_sms_receive, R.string.cap_missing_sms, Fix.Permission(PermissionGroup.SMS)),
    Feature(Caps.SMS_SEND, R.string.cap_sms_send, R.string.cap_missing_sms, Fix.Permission(PermissionGroup.SMS)),
    Feature(Caps.SMS_HISTORY, R.string.cap_sms_history, R.string.cap_missing_sms, Fix.Permission(PermissionGroup.SMS)),
    Feature(Caps.CALL_OBSERVE, R.string.cap_call_observe, R.string.cap_missing_phone, Fix.Permission(PermissionGroup.PHONE)),
    Feature(Caps.CALL_CONTROL, R.string.cap_call_control, R.string.cap_missing_phone, Fix.Permission(PermissionGroup.PHONE)),
    Feature(Caps.CALL_DIAL, R.string.cap_call_dial, R.string.cap_missing_phone, Fix.Permission(PermissionGroup.PHONE)),
    Feature(
        Caps.CALL_MEDIA, R.string.settings_talk,
        if (talkSupported) R.string.cap_missing_talk else R.string.talk_summary_unsupported,
        if (talkSupported) Fix.Talk else null,
    ),
    // The keypad, mute and speaker come with the same setup (Pairline's helper allows its in-call service).
    Feature(
        Caps.CALL_DTMF, R.string.cap_call_dtmf,
        if (talkSupported) R.string.cap_missing_dtmf_talk else R.string.cap_missing_companion,
        if (talkSupported) Fix.Talk else null,
    ),
    Feature(Caps.CONTACTS, R.string.cap_contacts, R.string.cap_missing_contacts, Fix.Permission(PermissionGroup.CONTACTS)),
    Feature(Caps.CALLLOG, R.string.cap_calllog, R.string.cap_missing_calllog, Fix.Permission(PermissionGroup.CALL_LOG)),
    Feature(Caps.CALLLOG_DELETE, R.string.cap_calllog_delete, R.string.cap_missing_calllog, Fix.Permission(PermissionGroup.CALL_LOG)),
    Feature(Caps.CONTACTS_ADD, R.string.cap_contacts_add, R.string.cap_missing_contacts_add, Fix.Settings),
    Feature(Caps.USSD, R.string.cap_ussd, R.string.cap_missing_phone, Fix.Permission(PermissionGroup.PHONE)),
    Feature(Caps.DUALSIM, R.string.cap_dualsim, R.string.cap_missing_dualsim, null),
)

@Composable
private fun CapabilitiesCard(caps: List<String>, onFix: (Fix) -> Unit) {
    val prefsTick by AppGraph.prefs.changes.collectAsState()
    val talkOn = remember(prefsTick) { AppGraph.prefs.talkOnIphone }
    val helper by AppGraph.privileged.state.collectAsState()
    SectionCard(title = stringResource(R.string.home_supports), contentPadding = PaddingValues(vertical = 4.dp)) {
        features.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            val ok = f.cap in caps
            // Talk on iPhone is set up; Android binds the call service (keypad, mute, speaker) on the next call.
            val nextCall = !ok && f.cap == Caps.CALL_DTMF && talkSupported && talkOn &&
                helper == app.pairline.android.privileged.PrivilegedManager.State.READY
            val fix = if (nextCall) null else f.fix
            val needsFix = fix != null && fix != Fix.Talk
            ListRow(
                title = stringResource(f.title),
                subtitle = when {
                    ok -> null
                    nextCall -> stringResource(R.string.cap_dtmf_next_call)
                    // Turned on but not working, e.g. the helper stopped after a restart: say what's missing.
                    f.cap == Caps.CALL_MEDIA && talkSupported && talkOn -> app.pairline.android.ui.talk.talkSummary()
                    else -> stringResource(f.missing)
                },
                icon = if (ok) PIcons.CheckCircle else if (needsFix) PIcons.Warning else PIcons.Info,
                iconTone = if (ok) Tone.GOOD else if (needsFix) Tone.WARN else Tone.NEUTRAL,
                showChevron = false,
                trailing = if (!ok && fix != null) {
                    {
                        TextButton(onClick = { onFix(fix) }) {
                            Text(stringResource(if (fix == Fix.Talk) R.string.action_set_up else R.string.action_fix))
                        }
                    }
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
    // App info › Battery › "Restrict background activity": Android then stops Pairline's link in the background.
    val restricted = remember(tick) {
        context.getSystemService(android.app.ActivityManager::class.java)?.isBackgroundRestricted == true
    }
    val maker = remember { Maker.detect() }
    SectionCard(title = stringResource(R.string.home_keep_running)) {
        if (restricted) {
            ListRow(
                title = stringResource(R.string.home_background_restricted),
                subtitle = stringResource(R.string.home_background_restricted_sub),
                icon = PIcons.Warning,
                iconTone = Tone.WARN,
                showChevron = false,
                trailing = { TextButton(onClick = { SystemActions.openAppSettings(context) }) { Text(stringResource(R.string.action_fix)) } },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        }
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
