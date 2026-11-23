package app.pairline.android.ui.pairing

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pairline.android.AppGraph
import app.pairline.android.BuildConfig
import app.pairline.android.R
import app.pairline.android.link.BluetoothStatus
import app.pairline.android.link.LinkService
import app.pairline.android.link.PairingOutcome
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.PermissionGroup
import app.pairline.android.ui.SystemActions
import app.pairline.android.ui.components.NoteBox
import app.pairline.android.ui.components.PrimaryButton
import app.pairline.android.ui.components.SecondaryButton
import app.pairline.android.ui.components.SuccessIllustration
import app.pairline.android.ui.components.Tone
import app.pairline.android.ui.rememberPermissionRequester
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay

/**
 * Shows the pairing QR code (PROTOCOL.md, "Pairing"): a single-use secret valid for 5 minutes, refreshed
 * automatically while the screen is open and forgotten when it closes. The screen stays on, and release builds
 * keep it out of screenshots and the recent-apps preview.
 */
@Composable
fun PairingContent(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pairing = AppGraph.pairing
    val offer by pairing.offer.collectAsState()
    val outcome by pairing.outcome.collectAsState()
    val link by AppGraph.link.state.collectAsState()
    val paired by AppGraph.peers.paired.collectAsState()
    val previousPeer = remember { if (paired) AppGraph.prefs.peerName else null }
    val requestPermission = rememberPermissionRequester()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(500)
        }
    }

    val view = LocalView.current
    DisposableEffect(Unit) {
        LinkService.start(context, "pairing screen")
        pairing.resetOutcome()
        pairing.newOffer()
        view.keepScreenOn = true
        val window = (context as? Activity)?.window
        if (!BuildConfig.DEBUG) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            view.keepScreenOn = false
            if (!BuildConfig.DEBUG) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            pairing.cancel()
        }
    }

    // A fresh code when the old one runs out, as long as nobody has paired yet.
    val current = offer
    LaunchedEffect(current, now, outcome) {
        if (outcome == PairingOutcome.NONE && current != null && now >= current.expiresAt) pairing.newOffer()
    }

    AnimatedContent(
        targetState = outcome == PairingOutcome.PAIRED,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "pairing",
        modifier = modifier,
    ) { done ->
        if (done) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SuccessIllustration()
                Text(stringResource(R.string.pair_success_title), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.pair_success_text),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (link.connected) {
                    NoteBox(stringResource(R.string.pair_connected), Tone.GOOD, PIcons.CheckCircle)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.pair_connecting), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.size(8.dp))
                PrimaryButton(stringResource(R.string.action_done), onDone)
            }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (link.bluetooth) {
                    BluetoothStatus.OFF -> NoteBox(stringResource(R.string.pair_need_bt), Tone.WARN, PIcons.Bluetooth)
                    BluetoothStatus.NO_PERMISSION -> NoteBox(stringResource(R.string.pair_need_bt_perm), Tone.WARN, PIcons.Bluetooth)
                    BluetoothStatus.UNSUPPORTED, BluetoothStatus.ADVERTISE_FAILED ->
                        NoteBox(stringResource(R.string.pair_bt_problem), Tone.BAD, PIcons.Error)
                    else -> Unit
                }
                if (link.bluetooth == BluetoothStatus.OFF) {
                    SecondaryButton(stringResource(R.string.action_turn_on_bt), { SystemActions.requestBluetoothOn(context) }, icon = PIcons.Bluetooth)
                } else if (link.bluetooth == BluetoothStatus.NO_PERMISSION) {
                    SecondaryButton(stringResource(R.string.action_allow), { requestPermission(PermissionGroup.BLUETOOTH) }, icon = PIcons.Bluetooth)
                }

                if (outcome == PairingOutcome.TOO_MANY_ATTEMPTS) {
                    NoteBox(stringResource(R.string.pair_too_many), Tone.BAD, PIcons.Warning)
                }

                Box(Modifier.widthIn(max = 320.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val o = offer
                    val qrDescription = stringResource(R.string.pair_qr_desc)
                    if (o != null) {
                        QrCode(
                            o.uri,
                            Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = qrDescription },
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                val remaining = offer?.let { ((it.expiresAt - now) / 1000).coerceAtLeast(0) } ?: 0
                val locale = LocalConfiguration.current.locales[0]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.pair_expires, String.format(locale, "%d:%02d", remaining / 60, remaining % 60)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SecondaryButton(stringResource(R.string.pair_new_code), { pairing.newOffer() }, icon = PIcons.Refresh)

                Steps(
                    listOf(
                        stringResource(R.string.pair_step_1),
                        stringResource(R.string.pair_step_2),
                        stringResource(R.string.pair_step_3),
                    ),
                )
                if (previousPeer != null) {
                    NoteBox(stringResource(R.string.pair_replaces, previousPeer), Tone.WARN, PIcons.Warning)
                }
                NoteBox(stringResource(R.string.pair_keep_open), Tone.NEUTRAL, PIcons.Lock)
            }
        }
    }
}

@Composable
private fun Steps(steps: List<String>) {
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        steps.forEachIndexed { i, text ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        String.format(locale, "%d", i + 1),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** The QR code itself: black modules on a white card in every theme, never mirrored. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix: BitMatrix = remember(text) {
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
        )
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        modifier = modifier
            .aspectRatio(1f)
            .shadow(6.dp, RoundedCornerShape(24.dp)),
    ) {
        Canvas(Modifier.padding(20.dp).aspectRatio(1f)) {
            val n = matrix.width
            val cell = size.minDimension / n
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (matrix[x, y]) {
                        drawRect(
                            Color.Black,
                            topLeft = Offset(x * cell, y * cell),
                            size = Size(cell + 0.5f, cell + 0.5f),
                        )
                    }
                }
            }
        }
    }
}
