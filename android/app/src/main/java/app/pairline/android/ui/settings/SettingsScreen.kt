package app.pairline.android.ui.settings

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.pairline.android.AppGraph
import app.pairline.android.BuildConfig
import app.pairline.android.R
import app.pairline.android.link.LinkService
import app.pairline.android.link.WifiLinkStatus
import app.pairline.android.link.wifi.HotspotFailure
import app.pairline.android.link.wifi.HotspotState
import app.pairline.android.link.wifi.PhoneHotspot
import app.pairline.android.log.Log
import app.pairline.android.privileged.PrivilegedManager
import app.pairline.android.telephony.Perms
import app.pairline.android.ui.GrantState
import app.pairline.android.ui.LocaleHelper
import app.pairline.android.ui.Maker
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.PermissionGroup
import app.pairline.android.ui.Route
import app.pairline.android.ui.SystemActions
import app.pairline.android.ui.UiEvents
import app.pairline.android.ui.components.ListRow
import app.pairline.android.ui.components.LtrText
import app.pairline.android.ui.components.NoteBox
import app.pairline.android.ui.components.PairlineTopBar
import app.pairline.android.ui.components.SectionCard
import app.pairline.android.ui.components.StatusPill
import app.pairline.android.ui.components.Tone
import app.pairline.android.ui.components.ltr
import app.pairline.android.ui.grantState
import app.pairline.android.ui.home.UnpairDialog
import app.pairline.android.ui.refreshTick
import app.pairline.android.ui.rememberPermissionRequester
import app.pairline.android.ui.update.UpdatesSection
import java.util.Date
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navigate: (Route) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val prefsTick by AppGraph.prefs.changes.collectAsState()
    val paired by AppGraph.peers.paired.collectAsState()
    val tick = refreshTick()
    val prefs = AppGraph.prefs
    var languageDialog by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var confirmSetup by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val stopped = remember(prefsTick) { prefs.bridgeStopped }
    val contactsAdd = remember(prefsTick, tick) { prefs.contactsAddEnabled && Perms.allGranted(context, Perms.WRITE_CONTACTS) }
    val writeContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        prefs.contactsAddEnabled = granted
        UiEvents.bump()
        LinkService.refresh(context)
    }
    val exempt = remember(tick) { SystemActions.isIgnoringBatteryOptimizations(context) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { PairlineTopBar(stringResource(R.string.settings_title), onBack = back, scrollBehavior = scroll) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SectionCard(title = stringResource(R.string.settings_general)) {
                ListRow(
                    title = stringResource(R.string.settings_language),
                    subtitle = languageName(LocaleHelper.current(context)),
                    icon = PIcons.Language,
                    onClick = { languageDialog = true },
                )
            }

            SectionCard(title = stringResource(R.string.settings_iphone)) {
                if (paired) {
                    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                    val since = prefs.pairedAt.takeIf { it > 0 }?.let { localDate(it, locale) }
                    ListRow(
                        title = prefs.peerName ?: stringResource(R.string.generic_iphone_name),
                        subtitle = listOfNotNull(
                            prefs.peerOs?.let { stringResource(R.string.settings_ios_version, ltr(it)) },
                            prefs.peerApp?.let { stringResource(R.string.settings_app_version, ltr(it)) },
                            since?.let { stringResource(R.string.settings_paired_since, it) },
                        ).joinToString("\n").ifEmpty { null },
                        icon = PIcons.Phone,
                        iconTone = Tone.GOOD,
                    )
                } else {
                    ListRow(
                        title = stringResource(R.string.notif_not_paired_title),
                        subtitle = stringResource(R.string.home_not_paired_body),
                        icon = PIcons.Phone,
                        iconTone = Tone.NEUTRAL,
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListRow(
                    title = stringResource(R.string.settings_contacts_add),
                    subtitle = stringResource(R.string.settings_contacts_add_sub),
                    icon = PIcons.PersonAdd,
                    onClick = {
                        if (contactsAdd) {
                            prefs.contactsAddEnabled = false
                            LinkService.refresh(context)
                        } else if (Perms.allGranted(context, Perms.WRITE_CONTACTS)) {
                            prefs.contactsAddEnabled = true
                            LinkService.refresh(context)
                        } else {
                            prefs.markRequested(Perms.WRITE_CONTACTS)
                            writeContacts.launch(Manifest.permission.WRITE_CONTACTS)
                        }
                    },
                    trailing = { Switch(checked = contactsAdd, onCheckedChange = null) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListRow(
                    title = stringResource(R.string.settings_talk),
                    subtitle = app.pairline.android.ui.talk.talkSummary(),
                    icon = PIcons.Phone,
                    onClick = { navigate(Route.Talk) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                // D-066: while the iPhone is linked, this phone stays quiet for calls that ring there.
                val ringOnIphone = remember(prefsTick) { prefs.ringOnIphoneOnly }
                val helper by AppGraph.privileged.state.collectAsState()
                ListRow(
                    title = stringResource(R.string.settings_ring_iphone_only),
                    subtitle = stringResource(
                        if (helper == PrivilegedManager.State.READY || !ringOnIphone) {
                            R.string.settings_ring_iphone_only_sub
                        } else {
                            R.string.settings_ring_iphone_only_needs_talk
                        },
                    ),
                    icon = PIcons.Notifications,
                    iconTone = if (ringOnIphone && helper != PrivilegedManager.State.READY) Tone.WARN else Tone.ACCENT,
                    onClick = { prefs.ringOnIphoneOnly = !ringOnIphone },
                    trailing = { Switch(checked = ringOnIphone, onCheckedChange = null) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListRow(
                    title = stringResource(R.string.block_title),
                    subtitle = stringResource(R.string.block_settings_sub),
                    icon = PIcons.Block,
                    onClick = { navigate(Route.BlockList) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                // Notifications from apps the user chose, on the iPhone (D-077).
                val mirrorCount = remember(prefsTick) { prefs.mirrorApps.size }
                ListRow(
                    title = stringResource(R.string.mirror_title),
                    subtitle = if (mirrorCount == 0) {
                        stringResource(R.string.mirror_settings_sub)
                    } else {
                        stringResource(R.string.mirror_settings_count, mirrorCount)
                    },
                    icon = PIcons.Notifications,
                    onClick = { navigate(Route.Mirror) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListRow(
                    title = stringResource(if (paired) R.string.home_pair_new else R.string.home_pair),
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
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { confirmUnpair = true },
                    )
                }
            }

            WifiSection(prefsTick)

            app.pairline.android.ui.netshare.NetShareSettings()

            SectionCard(title = stringResource(R.string.home_keep_running)) {
                ListRow(
                    title = stringResource(R.string.settings_background),
                    subtitle = stringResource(if (exempt) R.string.home_battery_ok else R.string.home_battery_restricted),
                    icon = PIcons.Battery,
                    iconTone = if (exempt) Tone.GOOD else Tone.WARN,
                    onClick = { SystemActions.requestIgnoreBatteryOptimizations(context) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListRow(
                    title = stringResource(R.string.keepalive_title),
                    subtitle = stringResource(R.string.home_keepalive_sub, stringResource(Maker.detect().label)),
                    icon = PIcons.Bolt,
                    onClick = { navigate(Route.KeepAlive) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListRow(
                    title = stringResource(R.string.permissions_title),
                    subtitle = stringResource(R.string.settings_permissions_sub),
                    icon = PIcons.Lock,
                    onClick = { navigate(Route.Permissions) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                if (stopped) {
                    ListRow(
                        title = stringResource(R.string.settings_start_bridge),
                        subtitle = stringResource(R.string.settings_start_bridge_sub),
                        icon = PIcons.Power,
                        iconTone = Tone.WARN,
                        onClick = { LinkService.start(context, "started in Settings", byUser = true) },
                    )
                } else {
                    ListRow(
                        title = stringResource(R.string.settings_stop_bridge),
                        subtitle = stringResource(R.string.settings_stop_bridge_sub),
                        icon = PIcons.Power,
                        iconTone = Tone.BAD,
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { confirmStop = true },
                    )
                }
            }

            UpdatesSection()

            AdvancedSection()

            SectionCard(title = stringResource(R.string.settings_support)) {
                ListRow(
                    title = stringResource(R.string.help_title),
                    subtitle = stringResource(R.string.settings_help_sub),
                    icon = PIcons.Help,
                    onClick = { navigate(Route.Help) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListRow(
                    title = stringResource(R.string.diag_title),
                    subtitle = stringResource(R.string.home_diag_sub),
                    icon = PIcons.Report,
                    onClick = { navigate(Route.Diagnostics) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                // Telegram links follow the app's language (strings.xml); opening one only hands the link to the system.
                val channelUrl = stringResource(R.string.telegram_channel_url)
                ListRow(
                    title = stringResource(R.string.settings_telegram_channel),
                    subtitle = stringResource(R.string.settings_telegram_channel_sub),
                    icon = PIcons.Send,
                    onClick = { SystemActions.openUrl(context, channelUrl) },
                    trailing = { androidx.compose.material3.Icon(PIcons.OpenExternal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                val groupUrl = stringResource(R.string.telegram_group_url)
                ListRow(
                    title = stringResource(R.string.settings_telegram_group),
                    subtitle = stringResource(R.string.settings_telegram_group_sub),
                    icon = PIcons.Forum,
                    onClick = { SystemActions.openUrl(context, groupUrl) },
                    trailing = { androidx.compose.material3.Icon(PIcons.OpenExternal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListRow(
                    title = stringResource(R.string.settings_rerun_setup),
                    icon = PIcons.Refresh,
                    onClick = { confirmSetup = true },
                )
            }

            SectionCard(title = stringResource(R.string.settings_about)) {
                ListRow(
                    title = stringResource(R.string.settings_version),
                    icon = PIcons.Info,
                    showChevron = false,
                    trailing = { LtrText("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListRow(
                    title = stringResource(R.string.settings_license),
                    subtitle = stringResource(R.string.settings_license_sub),
                    icon = PIcons.Report,
                    showChevron = false,
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListRow(
                    title = stringResource(R.string.settings_source),
                    subtitle = stringResource(R.string.settings_source_sub),
                    icon = PIcons.Code,
                    onClick = { SystemActions.openUrl(context, SystemActions.SOURCE_URL) },
                    trailing = { androidx.compose.material3.Icon(PIcons.OpenExternal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_privacy), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_privacy_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (languageDialog) {
        LanguageDialog(
            current = LocaleHelper.current(context),
            onDismiss = { languageDialog = false },
        ) { tag ->
            languageDialog = false
            LocaleHelper.set(context, tag)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // Android 11–12 has no per-app language: restart the screens ourselves.
                LinkService.stop(context)
                LinkService.start(context, "language changed")
                activity?.recreate()
            }
        }
    }
    if (confirmUnpair) {
        UnpairDialog(onDismiss = { confirmUnpair = false }) {
            confirmUnpair = false
            AppGraph.link.unpairLocally()
        }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.settings_stop_bridge_confirm_title)) },
            text = { Text(stringResource(R.string.settings_stop_bridge_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    LinkService.stopByUser(context)
                }) { Text(stringResource(R.string.action_stop), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmSetup) {
        AlertDialog(
            onDismissRequest = { confirmSetup = false },
            title = { Text(stringResource(R.string.settings_rerun_setup)) },
            text = { Text(stringResource(R.string.settings_rerun_setup_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSetup = false
                    prefs.setupDone = false
                }) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = { TextButton(onClick = { confirmSetup = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * The Wi-Fi link switch (on by default) and the bridge hotspot (M5). The hotspot starts only from here: Android
 * starts a local-only hotspot only for an app in the foreground, and only when the user asks.
 */
@Composable
private fun WifiSection(@Suppress("UNUSED_PARAMETER") prefsTick: Int) {
    val context = LocalContext.current
    val prefs = AppGraph.prefs
    val link by AppGraph.link.state.collectAsState()
    val hotspotState by AppGraph.hotspot.state.collectAsState()
    val hotspot = AppGraph.hotspot
    val enabled = prefs.wifiLinkEnabled
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) hotspot.start(prefs.hotspotBand)
    }
    SectionCard(title = stringResource(R.string.settings_wifi)) {
        ListRow(
            title = stringResource(R.string.settings_wifi_link),
            subtitle = stringResource(R.string.settings_wifi_link_sub),
            icon = PIcons.Wifi,
            onClick = {
                prefs.wifiLinkEnabled = !enabled
                LinkService.refresh(context)
            },
            trailing = { Switch(checked = enabled, onCheckedChange = null) },
        )
        if (!enabled) return@SectionCard
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        val listening = link.wifi == WifiLinkStatus.LISTENING
        val phoneHotspot = rememberPhoneHotspot(pairlineHotspotOn = hotspotState is HotspotState.On)
        ListRow(
            title = stringResource(
                when {
                    !listening -> R.string.settings_wifi_no_network
                    phoneHotspot.on && !phoneHotspot.onWifi -> R.string.settings_wifi_listening_hotspot
                    else -> R.string.settings_wifi_listening
                },
            ),
            icon = PIcons.Wifi,
            iconTone = if (listening) Tone.GOOD else Tone.NEUTRAL,
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        PhoneHotspotRow(phoneHotspot)
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        // Pairline's own hotspot has no internet, so it waits behind this row (D-061), opened while it runs.
        val on = hotspotState as? HotspotState.On
        var advanced by rememberSaveable { mutableStateOf(false) }
        val expanded = advanced || hotspotState != HotspotState.Off
        ListRow(
            title = stringResource(R.string.settings_pairline_hotspot),
            subtitle = stringResource(R.string.settings_pairline_hotspot_sub),
            icon = PIcons.Hotspot,
            iconTone = Tone.NEUTRAL,
            onClick = { advanced = !advanced },
            showChevron = false,
            trailing = {
                Icon(
                    PIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        if (!expanded) return@SectionCard
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        ListRow(
            title = stringResource(R.string.settings_hotspot),
            subtitle = when (val s = hotspotState) {
                is HotspotState.On -> stringResource(R.string.settings_hotspot_on, ltr(s.ssid)) + "\n" +
                    stringResource(R.string.settings_hotspot_iphone)
                HotspotState.Starting -> stringResource(R.string.settings_hotspot_starting)
                is HotspotState.Failed -> stringResource(
                    when (s.reason) {
                        HotspotFailure.PERMISSION -> R.string.hotspot_failed_permission
                        HotspotFailure.LOCATION_OFF -> R.string.hotspot_failed_location
                        HotspotFailure.INCOMPATIBLE -> R.string.hotspot_failed_incompatible
                        HotspotFailure.DISALLOWED -> R.string.hotspot_failed_disallowed
                        HotspotFailure.GENERIC -> R.string.hotspot_failed_generic
                    },
                )
                HotspotState.Off -> stringResource(R.string.settings_hotspot_sub)
            },
            icon = PIcons.Hotspot,
            iconTone = if (on != null) Tone.GOOD else if (hotspotState is HotspotState.Failed) Tone.WARN else Tone.ACCENT,
        )
        // Below the text rather than beside it, so the explanation keeps the full width.
        TextButton(
            onClick = {
                when {
                    on != null || hotspotState == HotspotState.Starting -> hotspot.stop()
                    Perms.allGranted(context, hotspot.requiredPermissions()) -> hotspot.start(prefs.hotspotBand)
                    else -> {
                        prefs.markRequested(hotspot.requiredPermissions())
                        askPermissions.launch(hotspot.requiredPermissions().toTypedArray())
                    }
                }
            },
            modifier = Modifier.padding(start = 64.dp, bottom = 8.dp),
        ) {
            Text(
                stringResource(
                    if (on != null || hotspotState == HotspotState.Starting) {
                        R.string.settings_hotspot_stop
                    } else {
                        R.string.settings_hotspot_start
                    },
                ),
            )
        }
        if (hotspot.bandChoiceSupported) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListRow(
                title = stringResource(R.string.settings_hotspot_band),
                subtitle = stringResource(
                    when (prefs.hotspotBand) {
                        "2.4" -> R.string.settings_hotspot_band_24
                        "5" -> R.string.settings_hotspot_band_5
                        else -> R.string.settings_hotspot_band_auto
                    },
                ),
                icon = PIcons.Hotspot,
                iconTone = Tone.NEUTRAL,
                // Takes effect the next time the hotspot starts.
                onClick = {
                    prefs.hotspotBand = when (prefs.hotspotBand) {
                        "auto" -> "2.4"
                        "2.4" -> "5"
                        else -> "auto"
                    }
                },
            )
        }
    }
}

/**
 * Settings › Advanced (D-075): root mode, for rooted phones only. Talk on iPhone's helper then starts through the
 * phone's own `su` as the shell user, instead of Wireless debugging: no Developer options, and it keeps working after
 * a restart without Wi-Fi.
 */
@Composable
private fun AdvancedSection() {
    val prefs = AppGraph.prefs
    val prefsTick by prefs.changes.collectAsState()
    val on = remember(prefsTick) { prefs.rootMode }
    val available = remember { AppGraph.privileged.rootAvailable() }
    SectionCard(title = stringResource(R.string.settings_advanced)) {
        ListRow(
            title = stringResource(R.string.settings_root),
            subtitle = stringResource(if (available || on) R.string.settings_root_sub else R.string.settings_root_none),
            icon = PIcons.Shield,
            iconTone = if (on) Tone.GOOD else Tone.NEUTRAL,
            onClick = if (available || on) ({ setRootMode(!on) }) else null,
            trailing = { Switch(checked = on, enabled = available || on, onCheckedChange = { setRootMode(it) }) },
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        // D-078: Bluetooth off and on when Android's stack is stuck; needs Talk on iPhone's helper.
        val restart = remember(prefsTick) { prefs.bluetoothRestart }
        ListRow(
            title = stringResource(R.string.settings_bt_restart),
            subtitle = stringResource(R.string.settings_bt_restart_sub),
            icon = PIcons.Bluetooth,
            iconTone = if (restart) Tone.GOOD else Tone.NEUTRAL,
            onClick = { prefs.bluetoothRestart = !restart },
            trailing = { Switch(checked = restart, onCheckedChange = { prefs.bluetoothRestart = it }) },
        )
    }
}

private fun setRootMode(on: Boolean) {
    AppGraph.prefs.rootMode = on
    Log.i("settings", "root mode ${if (on) "on" else "off"}")
    AppGraph.privileged.retry()
}

/**
 * Android's own hotspot (D-061), the way to link the phones away from Wi-Fi: it shares this phone's internet and the
 * Wi-Fi link listens on it. The row opens Android's hotspot settings (only opens them; rule 5) and says whether the
 * iPhone gets internet over it, and why not; also whether Bluetooth tethering is on, and, with the helper running,
 * that the iPhone can switch these (D-073).
 */
@Composable
private fun PhoneHotspotRow(info: PhoneHotspot.Info) {
    val context = LocalContext.current
    val helper by AppGraph.privileged.state.collectAsState()
    // Talk on iPhone's helper starts through Wireless debugging, which Android runs only on a Wi-Fi network (D-054).
    val helperNote = info.on && !info.onWifi && AppGraph.prefs.talkOnIphone && helper !in setOf(
        PrivilegedManager.State.READY,
        PrivilegedManager.State.STARTING,
        PrivilegedManager.State.UNSUPPORTED,
    )
    val text = stringResource(
        when {
            !info.on -> R.string.settings_phone_hotspot_off
            !info.upstream -> R.string.settings_phone_hotspot_no_internet
            info.vpn -> R.string.settings_phone_hotspot_vpn
            else -> R.string.settings_phone_hotspot_on
        },
    )
    val notes = listOfNotNull(
        text,
        stringResource(R.string.settings_phone_bluetooth_on).takeIf { info.bluetooth },
        stringResource(R.string.settings_phone_hotspot_helper).takeIf { helperNote },
        // The iPhone's switches (D-073) need the helper running, so Talk on iPhone on.
        stringResource(R.string.settings_phone_hotspot_remote).takeIf { helper == PrivilegedManager.State.READY },
    )
    ListRow(
        title = stringResource(R.string.settings_phone_hotspot),
        subtitle = notes.joinToString("\n\n"),
        icon = PIcons.Hotspot,
        iconTone = when {
            !info.on && info.bluetooth -> Tone.GOOD
            !info.on -> Tone.ACCENT
            !info.upstream || info.vpn || helperNote -> Tone.WARN
            else -> Tone.GOOD
        },
        onClick = { SystemActions.openTetherSettings(context) },
        showChevron = false,
        trailing = { Icon(PIcons.OpenExternal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

/** [PhoneHotspot] while the screen shows it: again on every hotspot broadcast, and every few seconds for data and VPN. */
@Composable
private fun rememberPhoneHotspot(pairlineHotspotOn: Boolean): PhoneHotspot.Info {
    val context = LocalContext.current
    var info by remember { mutableStateOf(PhoneHotspot.read(context, pairlineHotspotOn)) }
    DisposableEffect(pairlineHotspotOn) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                info = PhoneHotspot.read(context, pairlineHotspotOn)
            }
        }
        // Protected system broadcasts; see WifiTransport for why the receiver is registered exported.
        ContextCompat.registerReceiver(context, receiver, PhoneHotspot.filter(), ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(pairlineHotspotOn) {
        while (true) {
            delay(PHONE_HOTSPOT_REFRESH_MS)
            info = PhoneHotspot.read(context, pairlineHotspotOn)
        }
    }
    return info
}

private const val PHONE_HOTSPOT_REFRESH_MS = 4_000L

/** A medium date in the user's language; Persian uses the Solar Hijri calendar, as Iranian users expect. */
private fun localDate(ms: Long, locale: java.util.Locale): String {
    val uLocale = if (locale.language == "fa") {
        android.icu.util.ULocale("fa_IR@calendar=persian")
    } else {
        android.icu.util.ULocale.forLocale(locale)
    }
    return android.icu.text.DateFormat.getDateInstance(android.icu.text.DateFormat.MEDIUM, uLocale).format(Date(ms))
}

/** Language names are shown in their own language, so anyone can find theirs. */
@Composable
private fun languageName(tag: String): String = when (tag) {
    "en" -> "English"
    "fa" -> "فارسی"
    "ar" -> "العربية"
    "tr" -> "Türkçe"
    else -> stringResource(R.string.settings_language_system)
}

@Composable
private fun LanguageDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column {
                for (tag in LocaleHelper.SUPPORTED) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = tag == current, onClick = { onPick(tag) }, role = Role.RadioButton)
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = tag == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(languageName(tag), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Every permission group with its state and a way to fix it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(back: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current ?: return
    val tick = refreshTick()
    val request = rememberPermissionRequester()
    Scaffold(topBar = { PairlineTopBar(stringResource(R.string.permissions_title), onBack = back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.permissions_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            SectionCard {
                PermissionGroup.wizard.forEachIndexed { i, g ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val state = remember(tick, g) { grantState(activity, g) }
                    ListRow(
                        title = stringResource(g.title),
                        subtitle = stringResource(g.why),
                        icon = g.icon,
                        iconTone = if (state == GrantState.GRANTED) Tone.GOOD else if (g.required) Tone.WARN else Tone.NEUTRAL,
                        onClick = if (state == GrantState.GRANTED) null else ({ request(g) }),
                        trailing = {
                            when (state) {
                                GrantState.GRANTED -> StatusPill(stringResource(R.string.perm_state_granted), Tone.GOOD)
                                GrantState.BLOCKED -> StatusPill(stringResource(R.string.perm_state_blocked), Tone.WARN)
                                else -> StatusPill(stringResource(if (g.required) R.string.perm_state_needed else R.string.perm_state_optional), if (g.required) Tone.WARN else Tone.NEUTRAL)
                            }
                        },
                    )
                }
            }
            NoteBox(
                stringResource(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) R.string.perm_blocked_note_13 else R.string.perm_blocked_note),
                Tone.NEUTRAL,
                PIcons.Info,
                modifier = Modifier.clickable { SystemActions.openAppSettings(context) },
            )
            app.pairline.android.ui.components.SecondaryButton(
                stringResource(R.string.action_open_app_info),
                { SystemActions.openAppSettings(context) },
                icon = PIcons.OpenExternal,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
