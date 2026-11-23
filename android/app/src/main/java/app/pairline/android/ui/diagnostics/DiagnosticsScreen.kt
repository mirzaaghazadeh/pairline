package app.pairline.android.ui.diagnostics

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import app.pairline.android.R
import app.pairline.android.log.Log
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.SystemActions
import app.pairline.android.ui.components.NoteBox
import app.pairline.android.ui.components.PairlineTopBar
import app.pairline.android.ui.components.PrimaryButton
import app.pairline.android.ui.components.SecondaryButton
import app.pairline.android.ui.components.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows the diagnostics report exactly as it would be shared (AGENTS.md rule 9). "Share" hands this same text to
 * Android's share sheet; nothing is sent anywhere by Pairline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(back: () -> Unit) {
    val context = LocalContext.current
    val logVersion by Log.version.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    val report by produceState<String?>(null, logVersion) {
        value = withContext(Dispatchers.IO) { DiagnosticsReport.build(DiagnosticsReport.gather(context)) }
    }
    Scaffold(
        topBar = {
            PairlineTopBar(stringResource(R.string.diag_title), onBack = back) {
                IconButton(onClick = { confirmClear = true }) { Icon(PIcons.Delete, stringResource(R.string.diag_clear)) }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NoteBox(stringResource(R.string.diag_intro), Tone.GOOD, PIcons.Shield)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                // The report is English, technical text: always left to right.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    SelectionContainer(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                    ) {
                        Text(
                            report ?: "…",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    stringResource(R.string.diag_copy),
                    {
                        report?.let {
                            SystemActions.copyText(context, "Pairline diagnostics", it)
                            Toast.makeText(context, R.string.diag_copied, Toast.LENGTH_SHORT).show()
                        }
                    },
                    Modifier.weight(1f),
                    icon = PIcons.Copy,
                )
                PrimaryButton(
                    stringResource(R.string.diag_share),
                    { report?.let { SystemActions.shareText(context, "Pairline diagnostics", it) } },
                    Modifier.weight(1f),
                    icon = PIcons.Share,
                )
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.diag_clear)) },
            text = { Text(stringResource(R.string.diag_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    Log.clear()
                }) { Text(stringResource(R.string.diag_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
