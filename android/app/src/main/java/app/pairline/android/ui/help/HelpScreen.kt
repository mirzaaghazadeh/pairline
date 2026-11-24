package app.pairline.android.ui.help

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pairline.android.R
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.components.IconBadge
import app.pairline.android.ui.components.PairlineTopBar
import app.pairline.android.ui.components.SectionCard

private class Faq(@StringRes val q: Int, @StringRes val a: Int)

private class FaqSection(@StringRes val title: Int, val icon: ImageVector, val items: List<Faq>)

private val sections = listOf(
    FaqSection(
        R.string.help_section_setup,
        PIcons.Qr,
        listOf(
            Faq(R.string.help_q_what, R.string.help_a_what),
            Faq(R.string.help_q_iphone, R.string.help_a_iphone),
            Faq(R.string.help_q_pair, R.string.help_a_pair),
            Faq(R.string.help_q_spare, R.string.help_a_spare),
            Faq(R.string.help_q_default_sms, R.string.help_a_default_sms),
        ),
    ),
    FaqSection(
        R.string.help_section_battery,
        PIcons.Battery,
        listOf(
            Faq(R.string.help_q_disconnects, R.string.help_a_disconnects),
            Faq(R.string.help_q_battery_use, R.string.help_a_battery_use),
            Faq(R.string.help_q_notification, R.string.help_a_notification),
            Faq(R.string.help_q_distance, R.string.help_a_distance),
        ),
    ),
    FaqSection(
        R.string.help_section_trouble,
        PIcons.Warning,
        listOf(
            Faq(R.string.help_q_not_found, R.string.help_a_not_found),
            Faq(R.string.help_q_no_sms, R.string.help_a_no_sms),
            Faq(R.string.help_q_call_audio, R.string.help_a_call_audio),
            Faq(R.string.help_q_dtmf, R.string.help_a_dtmf),
            Faq(R.string.help_q_restricted, R.string.help_a_restricted),
        ),
    ),
    // Talk on iPhone (Wireless debugging and Pairline's helper, D-047/D-054) and internet sharing (D-048).
    FaqSection(
        R.string.help_section_talk,
        PIcons.Phone,
        listOf(
            Faq(R.string.help_q_shizuku, R.string.help_a_shizuku),
            Faq(R.string.help_q_restart, R.string.help_a_restart),
            Faq(R.string.help_q_listen_only, R.string.help_a_listen_only),
            Faq(R.string.help_q_debugging, R.string.help_a_debugging),
            Faq(R.string.help_q_netshare, R.string.help_a_netshare),
        ),
    ),
    FaqSection(
        R.string.help_section_permissions,
        PIcons.Lock,
        listOf(
            Faq(R.string.help_q_perm_nearby, R.string.help_a_perm_nearby),
            Faq(R.string.help_q_perm_sms, R.string.help_a_perm_sms),
            Faq(R.string.help_q_perm_phone, R.string.help_a_perm_phone),
            Faq(R.string.help_q_perm_calllog, R.string.help_a_perm_calllog),
            Faq(R.string.help_q_perm_contacts, R.string.help_a_perm_contacts),
            Faq(R.string.help_q_perm_notif, R.string.help_a_perm_notif),
            Faq(R.string.help_q_perm_battery, R.string.help_a_perm_battery),
        ),
    ),
    FaqSection(
        R.string.help_section_privacy,
        PIcons.Shield,
        listOf(
            Faq(R.string.help_q_data, R.string.help_a_data),
            Faq(R.string.help_q_logs, R.string.help_a_logs),
            Faq(R.string.help_q_unpair, R.string.help_a_unpair),
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(back: () -> Unit) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { PairlineTopBar(stringResource(R.string.help_title), onBack = back, scrollBehavior = scroll) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            for (section in sections) {
                SectionCard {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(section.icon)
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(section.title), style = MaterialTheme.typography.titleMedium)
                    }
                    section.items.forEach { faq ->
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        FaqItem(faq)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FaqItem(faq: Faq) {
    var open by rememberSaveable(faq.q) { mutableStateOf(false) }
    val angle by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(faq.q), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(PIcons.ExpandMore, null, Modifier.rotate(angle), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            Text(
                stringResource(faq.a),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
