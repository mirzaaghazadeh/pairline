package app.pairline.android.ui.remote

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pairline.android.AppGraph
import app.pairline.android.R
import app.pairline.android.link.LinkService
import app.pairline.android.link.remote.RemotePairingFlow.Failure
import app.pairline.android.link.remote.RemotePairingFlow.Refusal
import app.pairline.android.link.remote.RemotePairingFlow.State
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.components.LtrText
import app.pairline.android.ui.components.NoteBox
import app.pairline.android.ui.components.PairlineTopBar
import app.pairline.android.ui.components.PrimaryButton
import app.pairline.android.ui.components.PulseIcon
import app.pairline.android.ui.components.QuietButton
import app.pairline.android.ui.components.SuccessIllustration
import app.pairline.android.ui.components.Tone
import app.pairline.android.ui.components.ltr
import kotlinx.coroutines.delay

/**
 * Remote pairing (D-080, PROTOCOL.md "Remote pairing"): an iPhone far away invited this phone. The screen says who
 * asks and what allowing it means, sends nothing before Allow, then shows the 6-digit code to compare on a phone
 * call. It covers every other screen while open, and keeps the display on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemotePairingScreen() {
    val flow = AppGraph.remotePairing
    val state by flow.state.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(500)
        }
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler { flow.cancel() }

    Scaffold(topBar = { PairlineTopBar(stringResource(R.string.remote_pair_title), onBack = flow::cancel) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                State.Idle -> Unit
                is State.Refused -> Problem(stringResource(R.string.remote_pair_refused_title), refusalText(s.reason), flow::cancel)
                is State.Asking -> Asking(s, now, flow::allow, flow::cancel)
                is State.Waiting -> Waiting(
                    stringResource(R.string.remote_pair_waiting),
                    stringResource(R.string.remote_pair_waiting_body),
                    s.networkTrouble,
                    flow::cancel,
                )
                is State.Comparing -> Comparing(s, now, flow::match, flow::cancel)
                is State.Confirming -> Waiting(
                    stringResource(R.string.remote_pair_confirming),
                    stringResource(R.string.remote_pair_confirming_body),
                    s.networkTrouble,
                    flow::cancel,
                )
                State.Paired -> Paired(flow::cancel)
                is State.Failed -> Problem(stringResource(R.string.remote_pair_failed_title), failureText(s.reason), flow::cancel)
            }
        }
    }
}

@Composable
private fun ColumnScope.Asking(state: State.Asking, now: Long, onAllow: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val paired by AppGraph.peers.paired.collectAsState()
    PulseIcon(PIcons.PersonAdd, Tone.ACCENT, size = 120.dp)
    Title(stringResource(R.string.remote_pair_ask_title))
    Body(stringResource(R.string.remote_pair_ask_body))
    NoteBox(stringResource(R.string.remote_pair_ask_only_if), Tone.WARN, PIcons.Warning)
    if (paired) {
        // The model name ("iPhone 15") stays left to right inside a Persian or Arabic sentence.
        val name = AppGraph.prefs.peerName?.let(::ltr) ?: stringResource(R.string.generic_iphone_name)
        NoteBox(stringResource(R.string.remote_pair_replaces, name), Tone.WARN, PIcons.LinkOff)
    }
    NoteBox(stringResource(R.string.remote_pair_ask_next), Tone.NEUTRAL, PIcons.Phone)
    Remaining(R.string.remote_pair_expires, state.expiresAtMs - now)
    PrimaryButton(
        stringResource(R.string.action_allow),
        {
            // Pairing needs the link running, even if the user had stopped Pairline.
            LinkService.start(context, "remote pairing", byUser = true)
            onAllow()
        },
    )
    QuietButton(stringResource(R.string.action_cancel), onCancel)
}

@Composable
private fun ColumnScope.Waiting(title: String, body: String, networkTrouble: Boolean, onCancel: () -> Unit) {
    CircularProgressIndicator(Modifier.padding(top = 24.dp).size(48.dp))
    Title(title)
    Body(body)
    if (networkTrouble) NoteBox(stringResource(R.string.remote_pair_network), Tone.WARN, PIcons.Warning)
    QuietButton(stringResource(R.string.action_cancel), onCancel)
}

@Composable
private fun ColumnScope.Comparing(state: State.Comparing, now: Long, onMatch: () -> Unit, onCancel: () -> Unit) {
    Title(stringResource(R.string.remote_pair_code_title))
    Body(stringResource(R.string.remote_pair_code_body))
    val spoken = state.code.toList().joinToString(" ")
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken },
    ) {
        // Always left to right, also in Persian and Arabic: it's read out digit by digit.
        LtrText(
            state.code.substring(0, 3) + " " + state.code.substring(3),
            style = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        )
    }
    Remaining(R.string.remote_pair_code_expires, state.deadline - now)
    if (state.networkTrouble) NoteBox(stringResource(R.string.remote_pair_network), Tone.WARN, PIcons.Warning)
    NoteBox(stringResource(R.string.remote_pair_code_mismatch), Tone.NEUTRAL, PIcons.Shield)
    PrimaryButton(stringResource(R.string.remote_pair_match), onMatch, icon = PIcons.CheckCircle)
    QuietButton(stringResource(R.string.action_cancel), onCancel)
}

@Composable
private fun ColumnScope.Paired(onDone: () -> Unit) {
    SuccessIllustration()
    Title(stringResource(R.string.remote_pair_done_title))
    Body(stringResource(R.string.remote_pair_done_body))
    PrimaryButton(stringResource(R.string.action_done), onDone)
}

@Composable
private fun ColumnScope.Problem(title: String, text: String, onClose: () -> Unit) {
    PulseIcon(PIcons.Error, Tone.BAD, size = 120.dp)
    Title(title)
    Body(text)
    PrimaryButton(stringResource(R.string.action_close), onClose)
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "… expires in 9:41", in the app's language and digits. */
@Composable
private fun Remaining(@StringRes label: Int, millis: Long) {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val locale = LocalConfiguration.current.locales[0]
    Text(
        stringResource(label, String.format(locale, "%d:%02d", seconds / 60, seconds % 60)),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun refusalText(reason: Refusal): String = stringResource(
    when (reason) {
        Refusal.MALFORMED -> R.string.remote_pair_refused_malformed
        Refusal.EXPIRED -> R.string.remote_pair_refused_expired
        Refusal.UNSUPPORTED -> R.string.remote_pair_refused_unsupported
    },
)

@Composable
private fun failureText(reason: Failure): String = stringResource(
    when (reason) {
        Failure.EXPIRED -> R.string.remote_pair_failed_expired
        Failure.TIMED_OUT -> R.string.remote_pair_failed_timeout
        Failure.TOKEN_REJECTED -> R.string.remote_pair_failed_token
        Failure.ICLOUD_REFUSED -> R.string.remote_pair_failed_icloud
        Failure.BAD_ANSWER -> R.string.remote_pair_failed_bad_answer
        Failure.INTERNAL -> R.string.remote_pair_failed_internal
    },
)
