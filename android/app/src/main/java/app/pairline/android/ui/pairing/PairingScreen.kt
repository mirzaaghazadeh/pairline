package app.pairline.android.ui.pairing

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.core.content.ContextCompat
import app.pairline.android.AppGraph
import app.pairline.android.BuildConfig
import app.pairline.android.R
import app.pairline.android.link.BluetoothStatus
import app.pairline.android.link.LinkService
import app.pairline.android.link.PairingOutcome
import app.pairline.android.protocol.RemoteInvitation
import app.pairline.android.ui.CloudModeUi
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
import app.pairline.android.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // Saveable, like `started` below: after a rotation or language change the iPhone that just paired isn't "previous".
    val previousPeer by rememberSaveable { mutableStateOf(if (paired) AppGraph.prefs.peerName else null) }
    var started by rememberSaveable { mutableStateOf(false) }
    val requestPermission = rememberPermissionRequester()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(500)
        }
    }

    val view = LocalView.current
    DisposableEffect(Unit) {
        // Pairing needs the link: showing a code means the user wants Pairline running, even if they stopped it.
        LinkService.start(context, "pairing screen", byUser = true)
        if (!started) {
            // Opened now, not recreated: start over with a fresh code.
            pairing.resetOutcome()
            pairing.newOffer()
            started = true
        } else if (pairing.outcome.value != PairingOutcome.PAIRED) {
            // Recreated (rotation, language or theme change): keep the code the iPhone may be scanning.
            pairing.currentOrNewOffer()
        }
        view.keepScreenOn = true
        val activity = context as? Activity
        val window = activity?.window
        if (!BuildConfig.DEBUG) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            view.keepScreenOn = false
            if (!BuildConfig.DEBUG) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (activity?.isChangingConfigurations != true) pairing.cancel()
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
                // Cloud mode is turned on from the iPhone (D-092); the iPhone's device.info says whether it offers it.
                val prefsTick by AppGraph.prefs.changes.collectAsState()
                if (prefsTick >= 0 && CloudModeUi.offered(AppGraph.prefs.peerProto)) {
                    Text(
                        stringResource(R.string.pair_success_cloud),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                previousPeer?.let { name ->
                    NoteBox(stringResource(R.string.pair_replaces, name), Tone.WARN, PIcons.Warning)
                }
                NoteBox(stringResource(R.string.pair_keep_open), Tone.NEUTRAL, PIcons.Lock)
                if (CloudModeUi.pairingOverInternet) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    RemoteInvitationPaste()
                }
            }
        }
    }
}

/**
 * Remote pairing (D-092) for an invitation that didn't arrive as a link that opens Pairline: scan its QR code live
 * (the camera permission is asked on that tap and used only there), choose a picture of the QR code (a screenshot), or
 * paste the whole link. The clipboard is read only on a tap.
 */
@Composable
private fun RemoteInvitationPaste() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<Int?>(null) }
    var reading by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }

    fun open(text: String?, notFound: Int) {
        val invitation = text?.trim()
        if (invitation == null || !invitation.startsWith(RemoteInvitation.PREFIX)) {
            problem = notFound
            return
        }
        problem = null
        AppGraph.remotePairing.open(invitation)
    }

    fun read(uri: Uri) {
        reading = true
        scope.launch {
            val text = withContext(Dispatchers.Default) {
                try {
                    InvitationQr.decode(context, uri)
                } catch (e: Exception) {
                    Log.w("pairing", "couldn't read the picture: ${e.javaClass.simpleName}")
                    null
                }
            }
            reading = false
            open(text, R.string.remote_pair_scan_none)
        }
    }

    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else problem = R.string.remote_pair_scan_denied
    }
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) read(uri)
    }
    if (scanning) {
        InvitationScanner(
            onFound = { text ->
                scanning = false
                open(text, R.string.remote_pair_scan_none)
            },
            onUnavailable = {
                scanning = false
                problem = R.string.remote_pair_scan_no_camera
            },
            onDismiss = { scanning = false },
        )
    }

    Text(stringResource(R.string.remote_pair_section_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.remote_pair_paste_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    // Scan first (the iPhone's screen or a second screen), then the link most invitations arrive as, then a picture.
    SecondaryButton(stringResource(R.string.remote_pair_scan), {
        problem = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanning = true
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }, Modifier.fillMaxWidth(), icon = PIcons.Qr)
    SecondaryButton(stringResource(R.string.remote_pair_paste), {
        problem = null
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            ?.coerceToText(context)?.toString()
        open(text, R.string.remote_pair_paste_none)
    }, Modifier.fillMaxWidth(), icon = PIcons.Copy)
    SecondaryButton(stringResource(R.string.remote_pair_pick), {
        problem = null
        try {
            pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) {
            problem = R.string.remote_pair_scan_none
        }
    }, Modifier.fillMaxWidth(), icon = PIcons.Image)
    if (reading) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.remote_pair_scan_reading), style = MaterialTheme.typography.bodyMedium)
        }
    }
    problem?.let { NoteBox(stringResource(it), Tone.WARN, PIcons.Warning) }
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
