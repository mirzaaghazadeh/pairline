package app.pairline.android.ui.update

import android.content.ActivityNotFoundException
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pairline.android.AppGraph
import app.pairline.android.R
import app.pairline.android.log.Log
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.SystemActions
import app.pairline.android.ui.components.ListRow
import app.pairline.android.ui.components.SectionCard
import app.pairline.android.ui.components.Tone
import app.pairline.android.ui.components.ltr
import app.pairline.android.ui.components.toneColors
import app.pairline.android.update.InstallFlow
import app.pairline.android.update.UpdateFailure

@Composable
fun failureText(reason: UpdateFailure): String = stringResource(
    when (reason) {
        UpdateFailure.NETWORK -> R.string.update_error_network
        UpdateFailure.RESPONSE -> R.string.update_error_response
        UpdateFailure.CHECKSUM -> R.string.update_error_checksum
        UpdateFailure.SIGNATURE -> R.string.update_error_signature
        UpdateFailure.PACKAGE -> R.string.update_error_package
        UpdateFailure.STORAGE -> R.string.update_error_storage
        UpdateFailure.INSTALL -> R.string.update_error_install
    },
)

/** Home: shown while a newer release is available. */
@Composable
fun UpdateBanner() {
    val context = LocalContext.current
    val status by AppGraph.updates.status.collectAsState()
    val release = status.available ?: return
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = toneColors(Tone.ACCENT).container),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(PIcons.Download, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.update_available_title, ltr(release.version.toString())),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                stringResource(R.string.update_banner_body, ltr(status.installedVersion)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { AppGraph.updates.startUpdate() }) { Text(stringResource(R.string.update_action)) }
                TextButton(onClick = { SystemActions.openUrl(context, release.notesUrl) }) {
                    Text(stringResource(R.string.update_whats_new))
                }
            }
        }
    }
}

/** Settings: the switch, "Check now", the last check and the installed version. */
@Composable
fun UpdatesSection() {
    val context = LocalContext.current
    val status by AppGraph.updates.status.collectAsState()
    val updates = AppGraph.updates
    SectionCard(title = stringResource(R.string.settings_updates)) {
        ListRow(
            title = stringResource(R.string.settings_updates_auto),
            subtitle = stringResource(R.string.settings_updates_auto_sub),
            icon = PIcons.Refresh,
            onClick = { updates.enabled = !status.enabled },
            trailing = { Switch(checked = status.enabled, onCheckedChange = null) },
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        val lastLine = when {
            status.checking -> stringResource(R.string.settings_updates_checking)
            status.lastError != null -> failureText(status.lastError!!)
            status.lastChecked <= 0 -> stringResource(R.string.settings_updates_never)
            status.available == null -> stringResource(
                R.string.settings_updates_current,
                ltr(status.installedVersion),
                relative(status.lastChecked),
            )
            else -> stringResource(R.string.settings_updates_last, relative(status.lastChecked))
        }
        ListRow(
            title = stringResource(R.string.settings_updates_check_now),
            subtitle = lastLine,
            icon = PIcons.Download,
            iconTone = if (status.lastError != null) Tone.WARN else Tone.ACCENT,
            onClick = if (status.checking) null else ({ updates.checkNow() }),
            trailing = if (status.checking) {
                { CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp) }
            } else {
                null
            },
        )
        status.available?.let { release ->
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListRow(
                title = stringResource(R.string.update_available_title, ltr(release.version.toString())),
                subtitle = stringResource(R.string.settings_updates_installed, ltr(status.installedVersion)),
                icon = PIcons.Download,
                iconTone = Tone.GOOD,
                onClick = { updates.startUpdate() },
                trailing = { TextButton(onClick = { updates.startUpdate() }) { Text(stringResource(R.string.update_action)) } },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListRow(
                title = stringResource(R.string.update_whats_new),
                icon = PIcons.OpenExternal,
                onClick = { SystemActions.openUrl(context, release.notesUrl) },
            )
        }
    }
}

@Composable
private fun relative(ms: Long): String =
    DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** The update in progress, on top of any screen: permission, download, checks, Android's installer, or a failure. */
@Composable
fun UpdateFlowDialog() {
    val context = LocalContext.current
    val flow by AppGraph.updates.flow.collectAsState()
    val status by AppGraph.updates.status.collectAsState()
    val version = status.available?.version?.toString().orEmpty()
    val updates = AppGraph.updates
    when (val f = flow) {
        InstallFlow.Idle -> Unit
        InstallFlow.NeedsPermission -> AlertDialog(
            onDismissRequest = { updates.dismiss() },
            icon = { Icon(PIcons.Download, null) },
            title = { Text(stringResource(R.string.update_permission_title)) },
            text = { Text(stringResource(R.string.update_permission_body)) },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        context.startActivity(updates.permissionSettingsIntent())
                    } catch (e: ActivityNotFoundException) {
                        Log.w("update", "no install-apps settings screen", e)
                        SystemActions.openAppSettings(context)
                    }
                }) { Text(stringResource(R.string.action_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { updates.dismiss() }) { Text(stringResource(R.string.action_cancel)) } },
        )
        is InstallFlow.Downloading -> ProgressDialog(
            text = stringResource(R.string.update_downloading, ltr(version)),
            detail = if (f.total > 0) {
                "${Formatter.formatShortFileSize(context, f.done)} / ${Formatter.formatShortFileSize(context, f.total)}"
            } else {
                null
            },
            progress = if (f.total > 0) (f.done.toFloat() / f.total).coerceIn(0f, 1f) else null,
        )
        InstallFlow.Verifying -> ProgressDialog(stringResource(R.string.update_verifying), null, null)
        is InstallFlow.WaitingForUser -> AlertDialog(
            onDismissRequest = { updates.dismiss() },
            icon = { Icon(PIcons.Download, null) },
            title = { Text(stringResource(R.string.update_dialog_title)) },
            text = { Text(stringResource(R.string.update_waiting)) },
            confirmButton = {
                if (f.confirm != null) {
                    TextButton(onClick = { updates.onConfirmationNeeded(f.confirm) }) {
                        Text(stringResource(R.string.update_install))
                    }
                }
            },
            dismissButton = { TextButton(onClick = { updates.dismiss() }) { Text(stringResource(R.string.action_close)) } },
        )
        is InstallFlow.Failed -> AlertDialog(
            onDismissRequest = { updates.dismiss() },
            icon = { Icon(PIcons.Warning, null, tint = toneColors(Tone.WARN).content) },
            title = { Text(stringResource(R.string.update_failed_title)) },
            text = { Text(failureText(f.reason)) },
            confirmButton = { TextButton(onClick = { updates.dismiss() }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun ProgressDialog(text: String, detail: String?, progress: Float?) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(PIcons.Download, null) },
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text)
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (detail != null) {
                    Text(ltr(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {},
    )
}
