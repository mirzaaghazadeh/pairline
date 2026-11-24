package app.pairline.android.ui.netshare

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pairline.android.AppGraph
import app.pairline.android.R
import app.pairline.android.link.LinkService
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.components.ListRow
import app.pairline.android.ui.components.SectionCard
import app.pairline.android.ui.components.Tone
import app.pairline.android.ui.refreshTick

/** Settings › Internet sharing (M8, D-048). Off by default; the iPhone turns sharing on and off once it's allowed. */
@Composable
fun NetShareSettings() {
    val context = LocalContext.current
    val prefs = AppGraph.prefs
    val prefsTick by prefs.changes.collectAsState()
    val session by AppGraph.netShare.session.collectAsState()
    val tick = refreshTick()
    val allowed = remember(prefsTick) { prefs.netShareAllowed }
    val directIran = remember(prefsTick) { prefs.netShareDirectIran }
    val vpn = remember(tick, session) { AppGraph.netShare.vpnActive() }

    SectionCard(title = stringResource(R.string.netshare_title)) {
        ListRow(
            title = stringResource(R.string.netshare_allow),
            subtitle = stringResource(R.string.netshare_allow_sub),
            icon = PIcons.Share,
            showChevron = false,
            onClick = { setAllowed(context, !allowed) },
            trailing = { Switch(checked = allowed, onCheckedChange = { setAllowed(context, it) }) },
        )
        if (allowed) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListRow(
                title = stringResource(if (session != null) R.string.netshare_active else R.string.netshare_idle),
                subtitle = stringResource(if (vpn) R.string.netshare_vpn_on else R.string.netshare_vpn_off),
                icon = if (vpn) PIcons.Lock else PIcons.Info,
                iconTone = if (session != null) Tone.GOOD else Tone.NEUTRAL,
                showChevron = false,
                onClick = if (session != null) ({ AppGraph.netShare.stop("stopped on the bridge") }) else null,
                trailing = null,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListRow(
                title = stringResource(R.string.netshare_direct_iran),
                subtitle = stringResource(R.string.netshare_direct_iran_sub),
                icon = PIcons.Language,
                showChevron = false,
                onClick = { prefs.netShareDirectIran = !directIran },
                trailing = { Switch(checked = directIran, onCheckedChange = { prefs.netShareDirectIran = it }) },
            )
        }
    }
}

private fun setAllowed(context: android.content.Context, on: Boolean) {
    AppGraph.prefs.netShareAllowed = on
    if (!on) AppGraph.netShare.stop("turned off on the bridge")
    LinkService.refresh(context)
}
