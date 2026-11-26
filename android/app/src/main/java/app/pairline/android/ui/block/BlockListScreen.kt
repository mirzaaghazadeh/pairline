package app.pairline.android.ui.block

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.pairline.android.AppGraph
import app.pairline.android.R
import app.pairline.android.telephony.CallScreening
import app.pairline.android.telephony.PhoneNumbers
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalContext
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.components.ListRow
import app.pairline.android.ui.components.NoteBox
import app.pairline.android.ui.components.PairlineTopBar
import app.pairline.android.ui.components.SectionCard
import app.pairline.android.ui.components.Tone
import app.pairline.android.ui.components.ltr

/**
 * Call screening (D-085, Android 10+): make Pairline Bridge the call-screening app so blocked numbers never ring, and
 * "Silence unknown callers", which the iPhone can switch too.
 */
@Composable
private fun ScreeningCard(tick: Int) {
    if (!CallScreening.supported()) return
    val context = LocalContext.current
    var held by remember { mutableStateOf(CallScreening.held(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        held = CallScreening.held(context)
        AppGraph.router.statusChanged()
    }
    val silence = remember(tick) { AppGraph.blockList.silenceUnknown }
    SectionCard(title = stringResource(R.string.screen_title)) {
        ListRow(
            title = stringResource(R.string.screen_role),
            subtitle = stringResource(if (held) R.string.screen_role_on else R.string.screen_role_off),
            icon = PIcons.Block,
            iconTone = if (held) Tone.GOOD else Tone.ACCENT,
            onClick = if (held) null else {
                { CallScreening.requestIntent(context)?.let(request::launch) }
            },
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        ListRow(
            title = stringResource(R.string.screen_unknown),
            subtitle = stringResource(R.string.screen_unknown_sub),
            icon = PIcons.Person,
            iconTone = Tone.NEUTRAL,
            trailing = {
                Switch(
                    checked = silence && held,
                    enabled = held,
                    onCheckedChange = { on ->
                        if (AppGraph.blockList.setSilenceUnknown(on)) AppGraph.router.blockListChanged()
                    },
                )
            },
        )
    }
}

/** The bridge's own view of the block list (D-043): the same list the iPhone edits. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockListScreen(back: () -> Unit) {
    val tick by AppGraph.blockList.changes.collectAsState()
    val entries = remember(tick) { AppGraph.blockList.all().sortedByDescending { it.addedAt } }
    var input by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    fun change(add: List<String>, remove: List<String>) {
        if (AppGraph.blockList.update(add, remove)) AppGraph.router.blockListChanged()
    }

    fun submit() {
        val number = PhoneNumbers.normalizeBlockable(input)
        if (number == null) {
            invalid = true
            return
        }
        change(listOf(number), emptyList())
        input = ""
        invalid = false
    }

    Scaffold(topBar = { PairlineTopBar(stringResource(R.string.block_title), onBack = back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NoteBox(stringResource(R.string.block_intro), Tone.NEUTRAL, PIcons.Info)
            ScreeningCard(tick)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        invalid = false
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.block_add_hint)) },
                    isError = invalid,
                    supportingText = if (invalid) ({ Text(stringResource(R.string.block_invalid)) }) else null,
                    // Numbers read left to right in every language.
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                TextButton(onClick = { submit() }, enabled = input.isNotBlank()) { Text(stringResource(R.string.block_add)) }
            }
            Text(
                stringResource(R.string.block_prefix_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionCard(title = stringResource(R.string.block_title)) {
                if (entries.isEmpty()) {
                    Text(
                        stringResource(R.string.block_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                entries.forEachIndexed { i, entry ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    ListRow(
                        title = ltr(entry.number),
                        icon = PIcons.Block,
                        iconTone = Tone.BAD,
                        trailing = {
                            IconButton(onClick = { change(emptyList(), listOf(entry.number)) }) {
                                Icon(PIcons.Close, stringResource(R.string.block_remove, ltr(entry.number)))
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
