package app.pairline.android.ui.keepalive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pairline.android.R
import app.pairline.android.ui.Maker
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.SystemActions
import app.pairline.android.ui.components.ListRow
import app.pairline.android.ui.components.NoteBox
import app.pairline.android.ui.components.PairlineTopBar
import app.pairline.android.ui.components.PrimaryButton
import app.pairline.android.ui.components.SectionCard
import app.pairline.android.ui.components.Tone
import app.pairline.android.ui.refreshTick

/**
 * How to keep Pairline running: Android's battery exemption and each maker's own background settings. Pairline
 * only opens these screens; the user changes the settings (AGENTS.md rule 5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepAliveScreen(back: () -> Unit) {
    val context = LocalContext.current
    val tick = refreshTick()
    val exempt = remember(tick) { SystemActions.isIgnoringBatteryOptimizations(context) }
    val maker = remember { Maker.detect() }
    Scaffold(topBar = { PairlineTopBar(stringResource(R.string.keepalive_title), onBack = back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(R.string.keepalive_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            SectionCard(title = stringResource(R.string.keepalive_step_battery)) {
                ListRow(
                    title = stringResource(if (exempt) R.string.home_battery_ok else R.string.home_battery_restricted),
                    subtitle = stringResource(if (exempt) R.string.home_battery_ok_sub else R.string.battery_note),
                    icon = if (exempt) PIcons.CheckCircle else PIcons.Battery,
                    iconTone = if (exempt) Tone.GOOD else Tone.WARN,
                    showChevron = false,
                )
                if (!exempt) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        PrimaryButton(stringResource(R.string.battery_allow), { SystemActions.requestIgnoreBatteryOptimizations(context) })
                    }
                }
            }
            SectionCard(title = stringResource(R.string.keepalive_step_maker, stringResource(maker.label))) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(maker.steps), style = MaterialTheme.typography.bodyLarge)
                    PrimaryButton(stringResource(R.string.action_open_settings), { maker.open(context) }, icon = PIcons.OpenExternal)
                }
            }
            SectionCard(title = stringResource(R.string.keepalive_other_makers)) {
                Maker.entries.filter { it != maker && it != Maker.OTHER }.forEachIndexed { i, m ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    MakerTips(m)
                }
            }
            NoteBox(stringResource(R.string.keepalive_general), Tone.NEUTRAL, PIcons.Info)
            NoteBox(stringResource(R.string.keepalive_never_changes), Tone.GOOD, PIcons.Shield)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MakerTips(maker: Maker) {
    var open by remember { mutableStateOf(false) }
    ListRow(
        title = stringResource(maker.label),
        icon = PIcons.Phone,
        iconTone = Tone.NEUTRAL,
        onClick = { open = !open },
        trailing = { Icon(PIcons.ExpandMore, null, Modifier.rotate(if (open) 180f else 0f)) },
    )
    AnimatedVisibility(open) {
        Text(
            stringResource(maker.steps),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 72.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}
