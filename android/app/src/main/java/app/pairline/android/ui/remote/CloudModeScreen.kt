package app.pairline.android.ui.remote

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import app.pairline.android.AppGraph
import app.pairline.android.R
import app.pairline.android.link.remote.Direct
import app.pairline.android.link.remote.DirectReason
import app.pairline.android.link.remote.DirectStatus
import app.pairline.android.link.remote.RemoteStatus
import app.pairline.android.link.remote.RemoteStore
import app.pairline.android.ui.ConnectionWay
import app.pairline.android.ui.Maker
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.Route
import app.pairline.android.ui.SystemActions
import app.pairline.android.ui.components.ListRow
import app.pairline.android.ui.components.NoteBox
import app.pairline.android.ui.components.PairlineTopBar
import app.pairline.android.ui.components.SectionCard
import app.pairline.android.ui.components.Tone
import app.pairline.android.ui.components.toneColors
import app.pairline.android.ui.refreshTick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Cloud mode on this phone (D-092, D-099), one level below the home screen: what it is with an everyday example, how
 * it's doing, what a phone left at home needs (charger, Wi-Fi, running in the background) with a way to fix each, and
 * whether calls from far away work. The router details (manual port forwarding, a fixed address) wait under Advanced.
 * Cloud mode itself is turned on from the iPhone, so there's no switch here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudModeScreen(navigate: (Route) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val remote by AppGraph.remote.state.collectAsState()
    val direct by AppGraph.direct.status.collectAsState()
    val link by AppGraph.link.state.collectAsState()
    val paired by AppGraph.peers.paired.collectAsState()
    val tick = refreshTick()
    val charging by produceState<Boolean?>(null, tick) {
        while (true) {
            value = withContext(Dispatchers.IO) { AppGraph.status.battery().charging }
            delay(3_000)
        }
    }
    val onWifi by produceState(SystemActions.onWifi(context), tick) {
        while (true) {
            delay(3_000)
            value = SystemActions.onWifi(context)
        }
    }
    val exempt = remember(tick) { SystemActions.isIgnoringBatteryOptimizations(context) }
    val restricted = remember(tick) {
        context.getSystemService(android.app.ActivityManager::class.java)?.isBackgroundRestricted == true
    }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { PairlineTopBar(stringResource(R.string.cloud_title), onBack = back, scrollBehavior = scroll) },
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
                stringResource(R.string.cloud_intro),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            NoteBox(stringResource(R.string.ways_cloud_example), Tone.NEUTRAL, PIcons.Home)

            SectionCard(title = stringResource(R.string.cloud_now)) {
                val viaIcloud = paired && !link.connected && remote.on && remote.problem == null
                val way = ConnectionWay.of(link, viaIcloud)
                val (title, text, tone) = cloudStatus(remote, way)
                ListRow(
                    title = title,
                    subtitle = text,
                    icon = PIcons.Cloud,
                    iconTone = tone,
                    showChevron = false,
                )
            }

            SectionCard(title = stringResource(R.string.cloud_needs_title)) {
                NeedRow(
                    ok = charging == true,
                    unknown = charging == null,
                    title = R.string.cloud_need_charger,
                    missing = R.string.cloud_need_charger_missing,
                    icon = PIcons.Battery,
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                NeedRow(
                    ok = onWifi,
                    title = R.string.cloud_need_wifi,
                    missing = R.string.cloud_need_wifi_missing,
                    icon = PIcons.Wifi,
                    fix = { SystemActions.openWifiSettings(context) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                NeedRow(
                    ok = exempt && !restricted,
                    title = R.string.home_battery_ok,
                    missing = if (restricted) R.string.home_background_restricted_sub else R.string.home_battery_restricted_sub,
                    icon = PIcons.Bolt,
                    fix = {
                        if (restricted) SystemActions.openAppSettings(context) else SystemActions.requestIgnoreBatteryOptimizations(context)
                    },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListRow(
                    title = stringResource(R.string.keepalive_title),
                    subtitle = stringResource(R.string.home_keepalive_sub, stringResource(remember { Maker.detect() }.label)),
                    icon = PIcons.Info,
                    iconTone = Tone.NEUTRAL,
                    onClick = { navigate(Route.KeepAlive) },
                )
            }

            if (remote.on && direct.active) {
                SectionCard(title = stringResource(R.string.cloud_calls_title)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DirectStatusLine(direct)
                        directHint(direct, detailed = true)?.let { HintText(it, indent = false) }
                    }
                }
                RouterDetails(direct)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The status row's title, text and tone: off (and where to turn it on), on and how, or paused and why. */
@Composable
private fun cloudStatus(remote: RemoteStatus, way: ConnectionWay?): Triple<String, String, Tone> = when {
    !remote.on -> Triple(stringResource(R.string.cloud_state_off), stringResource(R.string.cloud_turn_on), Tone.NEUTRAL)
    remote.problem == RemoteStore.Problem.TOKEN_REJECTED ->
        Triple(stringResource(R.string.cloud_state_paused), stringResource(R.string.cloud_paused_token), Tone.WARN)
    remote.problem == RemoteStore.Problem.PRIVATE_DATABASE_REFUSED ->
        Triple(stringResource(R.string.cloud_state_paused), stringResource(R.string.cloud_paused_refused), Tone.WARN)
    remote.retrying -> Triple(stringResource(R.string.state_cloud_offline), stringResource(R.string.cloud_retrying), Tone.WARN)
    way == ConnectionWay.CLOUD_ICLOUD ->
        Triple(stringResource(way.label), stringResource(R.string.home_via_icloud_body), Tone.GOOD)
    way == ConnectionWay.CLOUD_DIRECT ->
        Triple(stringResource(way.label), stringResource(R.string.home_connected_body_direct), Tone.GOOD)
    else -> Triple(stringResource(R.string.cloud_state_on), stringResource(R.string.cloud_on), Tone.GOOD)
}

@Composable
private fun NeedRow(
    ok: Boolean,
    @StringRes title: Int,
    @StringRes missing: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    unknown: Boolean = false,
    fix: (() -> Unit)? = null,
) {
    ListRow(
        title = stringResource(title),
        subtitle = if (ok || unknown) null else stringResource(missing),
        icon = if (ok) PIcons.CheckCircle else icon,
        iconTone = when {
            ok -> Tone.GOOD
            unknown -> Tone.NEUTRAL
            else -> Tone.WARN
        },
        showChevron = false,
        trailing = if (!ok && !unknown && fix != null) {
            { TextButton(onClick = fix) { Text(stringResource(R.string.action_fix)) } }
        } else {
            null
        },
    )
}

/** "Calls from far away: ready", or why not (D-092): the direct connection through the home router, in plain words. */
@Composable
fun DirectStatusLine(s: DirectStatus) {
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

/**
 * What fixes calls from far away, when the home's router, modem or provider is the reason (D-094): texts and codes work
 * anyway. [detailed] (the Cloud mode screen) names the router setting; the home screen's short version only says what
 * kind of help is needed and points here.
 */
@Composable
fun directHint(s: DirectStatus, detailed: Boolean): String? {
    if (s.direct == null || s.direct == Direct.OPEN || s.ipv6Unconfirmed) return null
    return when (s.reason) {
        DirectReason.NO_MAPPING, null ->
            stringResource(if (detailed) R.string.direct_hint_router else R.string.direct_short_router)
        DirectReason.DOUBLE_NAT, DirectReason.NO_PUBLIC_ADDRESS ->
            stringResource(if (detailed) R.string.direct_hint_modem else R.string.direct_short_modem)
        DirectReason.CARRIER_NAT -> stringResource(if (detailed) R.string.direct_hint_provider else R.string.direct_short_provider)
        DirectReason.NOT_ON_WIFI -> stringResource(R.string.direct_short_wifi)
        DirectReason.LISTEN_FAILED -> null
    }
}

/**
 * Advanced: the user's own port forwarding (D-095), for a router without UPnP or NAT-PMP: this phone's home address and
 * port, shown left to right in every language, why the address must stay the same and how.
 */
@Composable
private fun RouterDetails(s: DirectStatus) {
    val bidi = BidiFormatter.getInstance()
    val address = s.localV4?.let { bidi.unicodeWrap(it, TextDirectionHeuristicsCompat.LTR) } ?: return
    val port = s.port?.let { bidi.unicodeWrap(it.toString(), TextDirectionHeuristicsCompat.LTR) } ?: return
    val open = s.direct == Direct.OPEN
    val routerReason = s.reason == DirectReason.NO_MAPPING || s.reason == null
    val modemReason = s.reason == DirectReason.DOUBLE_NAT || s.reason == DirectReason.NO_PUBLIC_ADDRESS
    val notOpen = s.direct != null && !open && !s.ipv6Unconfirmed
    val failing = notOpen && (routerReason || modemReason)
    // Forwarding by hand can't help (no Wi-Fi, the provider's shared address, or this phone's own listener).
    if (notOpen && !failing) return
    // Nothing to forward by hand: it works, and the router opened the port by itself.
    if (!failing && s.mappedV4) return
    SectionCard(title = stringResource(R.string.cloud_router_title)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                failing && routerReason -> HintText(stringResource(R.string.direct_hint_router_manual, port, address), indent = false)
                failing -> HintText(stringResource(R.string.direct_hint_modem_manual, port, address), indent = false)
                // Not opened by the router itself: the user may have forwarded the port by hand; here is what to forward.
                else -> HintText(stringResource(R.string.direct_forward_info, port, address), indent = false)
            }
            // A hand-made rule points at one address: why this phone must keep it, and how (D-095).
            HintText(stringResource(R.string.direct_hint_fixed_address, address), indent = false)
            // Android 10+ gives each Wi-Fi network a random hardware address, which can break the router's reservation.
            if (Build.VERSION.SDK_INT >= 29) HintText(stringResource(R.string.direct_hint_device_mac), indent = false)
        }
    }
}

@Composable
fun HintText(text: String, indent: Boolean = true) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (indent) Modifier.padding(start = 26.dp) else Modifier,
    )
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
