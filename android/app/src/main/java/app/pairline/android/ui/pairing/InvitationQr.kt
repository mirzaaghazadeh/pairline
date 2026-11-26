package app.pairline.android.ui.pairing

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.File

/**
 * Reads the QR code in a picture of a remote-pairing invitation (D-080): a photo the phone's own camera app took, or a
 * picture the user chose (a screenshot sent by the person with the iPhone). It all happens on this phone, with ZXing,
 * which Pairline already uses to draw its own QR codes: no camera permission and no other library.
 */
object InvitationQr {
    /** The text of the QR code in [pixels] (ARGB, row by row), or null if there's none. Dark-on-light or inverted. */
    fun decode(pixels: IntArray, width: Int, height: Int): String? {
        val plain = RGBLuminanceSource(width, height, pixels)
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )
        // Dark on light first, then light on dark (a screenshot in dark mode); QRCodeReader itself ignores ALSO_INVERTED.
        for (source in listOf(plain, plain.invert())) {
            for (binarizer in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))) {
                try {
                    return QRCodeReader().decode(BinaryBitmap(binarizer), hints).text
                } catch (e: ReaderException) {
                    // Not found this way; a photo with uneven light may still read with the other binarizer.
                }
            }
        }
        return null
    }

    /** Reads the picture at [uri], scaled down to under twice [MAX_SIDE] a side, and decodes it. Blocking. */
    fun decode(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            decode(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private const val MAX_SIDE = 1600
}

/**
 * The one photo the camera app takes when the user scans an invitation: in Pairline's cache, shared with the camera
 * app only for that capture (FileProvider, `scan/`), and deleted as soon as it's read or the capture is cancelled.
 */
object InvitationPhoto {
    private const val DIR = "scan"
    private const val NAME = "invitation.jpg"

    fun create(context: Context): Uri {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, NAME)
        file.delete()
        file.createNewFile()
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun delete(context: Context) {
        File(File(context.cacheDir, DIR), NAME).delete()
    }
}
