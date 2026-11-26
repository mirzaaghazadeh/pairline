package app.pairline.android.ui.mirror

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pairline.android.AppGraph
import app.pairline.android.R
import app.pairline.android.mirror.NotificationMirror
import app.pairline.android.ui.ClearSetupGuideOnResume
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.RestrictedSettings
import app.pairline.android.ui.SetupGuide
import app.pairline.android.ui.components.ListRow
import app.pairline.android.ui.components.NoteBox
import app.pairline.android.ui.components.PairlineTopBar
import app.pairline.android.ui.components.PrimaryButton
import app.pairline.android.ui.components.SectionCard
import app.pairline.android.ui.components.Tone
import app.pairline.android.ui.refreshTick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(val pkg: String, val label: String)

/**
 * Settings › Notifications from apps (D-077): notification access, then the apps whose notifications reach the
 * iPhone. None are chosen at first. Calls and texts are already relayed, so they aren't listed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MirrorScreen(back: () -> Unit) {
    val context = LocalContext.current
    val prefs = AppGraph.prefs
    val prefsTick by prefs.changes.collectAsState()
    val chosen = remember(prefsTick) { prefs.mirrorApps }
    val tick = refreshTick()
    val access = remember(tick) { NotificationMirror.accessGranted(context) }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    ClearSetupGuideOnResume()

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != context.packageName }
                .map { AppEntry(it, NotificationMirror.label(context, it)) }
                .sortedBy { it.label.lowercase() }
        }
    }

    fun set(pkg: String, on: Boolean) {
        prefs.mirrorApps = if (on) chosen + pkg else chosen - pkg
    }

    Scaffold(topBar = { PairlineTopBar(stringResource(R.string.mirror_title), onBack = back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NoteBox(stringResource(R.string.mirror_intro), Tone.NEUTRAL, PIcons.Info)
            SectionCard(title = stringResource(R.string.mirror_access)) {
                ListRow(
                    title = stringResource(if (access) R.string.mirror_access_on else R.string.mirror_access_off),
                    subtitle = if (access) null else stringResource(R.string.mirror_access_sub),
                    icon = if (access) PIcons.CheckCircle else PIcons.Notifications,
                    iconTone = if (access) Tone.GOOD else Tone.WARN,
                )
                if (!access) {
                    val title = stringResource(R.string.mirror_access)
                    val steps = stringResource(R.string.mirror_access_steps)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        PrimaryButton(stringResource(R.string.mirror_allow), {
                            // Installed from a file, Android shows notification access as a restricted setting first.
                            SetupGuide.show(context, title, steps)
                            runCatching {
                                context.startActivity(NotificationMirror.accessSettings().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure { RestrictedSettings.guide(context) }
                        })
                    }
                }
            }
            SectionCard(title = stringResource(R.string.mirror_apps)) {
                val list = apps
                if (list == null) {
                    Text(
                        stringResource(R.string.mirror_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    list.forEachIndexed { i, app ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        val on = app.pkg in chosen
                        ListRow(
                            title = app.label,
                            icon = PIcons.Notifications,
                            iconTone = if (on) Tone.GOOD else Tone.NEUTRAL,
                            onClick = { set(app.pkg, !on) },
                            trailing = { Switch(checked = on, onCheckedChange = { set(app.pkg, it) }) },
                        )
                    }
                }
            }
        }
    }
}
