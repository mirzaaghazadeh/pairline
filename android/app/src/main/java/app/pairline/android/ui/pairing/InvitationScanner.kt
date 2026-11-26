package app.pairline.android.ui.pairing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import app.pairline.android.R
import app.pairline.android.log.Log
import app.pairline.android.protocol.RemoteInvitation
import app.pairline.android.ui.components.SecondaryButton
import com.google.zxing.PlanarYUVLuminanceSource
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The live QR scanner for a remote-pairing invitation (D-092): the back camera's preview, with every frame it can keep
 * up with read by ZXing on this phone. Nothing is recorded or kept; the camera closes as the scanner does. Needs the
 * camera permission, which the caller asks for on the tap.
 */
@Composable
fun InvitationScanner(onFound: (String) -> Unit, onUnavailable: () -> Unit, onDismiss: () -> Unit) {
    val found by rememberUpdatedState(onFound)
    val unavailable by rememberUpdatedState(onUnavailable)
    var otherCode by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val camera = remember { QrCamera(context) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.remote_pair_scan_hint),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Box(contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { ctx -> SurfaceView(ctx).also(camera::attach) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(camera.aspectRatio),
                )
                Box(Modifier.size(220.dp).border(3.dp, Color.White, RoundedCornerShape(20.dp)))
            }
            if (otherCode) {
                Text(
                    stringResource(R.string.remote_pair_scan_other_code),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
            SecondaryButton(stringResource(R.string.action_cancel), onDismiss)
        }
    }
    DisposableEffect(camera) {
        camera.onText = { text ->
            if (text.trim().startsWith(RemoteInvitation.PREFIX)) {
                camera.stop()
                found(text.trim())
                true
            } else {
                otherCode = true
                false
            }
        }
        camera.onFailure = { unavailable() }
        onDispose { camera.stop() }
    }
}

/**
 * The back camera through Camera2 (no library): a preview on a [SurfaceView] and a small YUV stream whose brightness
 * plane goes to [InvitationQr]. Callbacks come on the main thread; [onText] returns true once the scan is done.
 */
private class QrCamera(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(CameraManager::class.java)
    private val main = Handler(app.mainLooper)
    private val thread = HandlerThread("pairline-qr-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "pairline-qr-decode") }
    private val busy = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    var onText: (String) -> Boolean = { false }
    var onFailure: () -> Unit = {}

    private val cameraId: String? = try {
        manager?.cameraIdList?.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager?.cameraIdList?.firstOrNull()
    } catch (e: Exception) {
        null
    }

    /** About 1280×720, the size every camera offers for both streams; the sensor's landscape width and height. */
    private val size: Size = cameraId?.let { id ->
        try {
            val map = manager?.getCameraCharacteristics(id)?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val yuv = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toSet().orEmpty()
            map?.getOutputSizes(SurfaceHolder::class.java)
                ?.filter { it in yuv && it.width <= 1920 && it.height <= 1080 }
                ?.minByOrNull { kotlin.math.abs(it.width * it.height - 1280 * 720) }
        } catch (e: Exception) {
            null
        }
    } ?: Size(1280, 720)

    /** Width over height on screen, in portrait: the sensor's landscape size turned upright. */
    val aspectRatio: Float = size.height.toFloat() / size.width

    fun attach(view: SurfaceView) {
        view.holder.setFixedSize(size.width, size.height)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = open(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = stop()
        })
    }

    private fun open(preview: Surface) {
        val id = cameraId
        if (id == null || manager == null ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            return fail("no camera or no permission")
        }
        val frames = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        frames.setOnImageAvailableListener({ r -> frame(r) }, handler)
        reader = frames
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (stopped.get()) return camera.close()
                    device = camera
                    startSession(camera, preview, frames.surface)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    fail("camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    fail("camera error $error")
                }
            }, handler)
        } catch (e: Exception) {
            fail(e.javaClass.simpleName)
        }
    }

    @Suppress("DEPRECATION") // createCaptureSession(List, …) is the one that exists on Android 9.
    private fun startSession(camera: CameraDevice, preview: Surface, frames: Surface) {
        try {
            camera.createCaptureSession(listOf(preview, frames), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (stopped.get()) return s.close()
                    session = s
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(preview)
                        addTarget(frames)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }.build()
                    try {
                        s.setRepeatingRequest(request, null, handler)
                    } catch (e: Exception) {
                        fail(e.javaClass.simpleName)
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) = fail("session not configured")
            }, handler)
        } catch (e: Exception) {
            fail(e.javaClass.simpleName)
        }
    }

    /** Takes the newest frame's brightness plane and reads it, skipping frames while the last one is still read. */
    private fun frame(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        image.use {
            if (stopped.get() || !busy.compareAndSet(false, true)) return
            val plane = it.planes[0]
            val width = it.width
            val height = it.height
            val stride = plane.rowStride
            val buffer = plane.buffer
            val luma = ByteArray(buffer.remaining())
            buffer.get(luma)
            decoder.execute {
                try {
                    val source = PlanarYUVLuminanceSource(luma, stride, height, 0, 0, width, height, false)
                    val text = InvitationQr.decode(source)
                    if (text != null && !stopped.get()) main.post { if (!stopped.get()) onText(text) }
                } catch (e: Exception) {
                    Log.w("pairing", "reading a camera frame failed: ${e.javaClass.simpleName}")
                } finally {
                    busy.set(false)
                }
            }
        }
    }

    private fun fail(why: String) {
        Log.w("pairing", "QR scanner: $why")
        main.post { if (!stopped.get()) onFailure() }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        handler.post {
            try {
                session?.close()
                device?.close()
                reader?.close()
            } catch (e: Exception) {
                Log.w("pairing", "closing the camera: ${e.javaClass.simpleName}")
            }
            session = null
            device = null
            reader = null
            thread.quitSafely()
        }
        decoder.shutdown()
    }
}
