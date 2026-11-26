package app.pairline.android.ui.pairing

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Reads the QR code of a remote-pairing invitation (D-092): in the live scanner's camera frames
 * ([InvitationScanner]), or in a picture the user chose (a screenshot of the iPhone's invitation). It all happens on
 * this phone, with ZXing, which Pairline already uses to draw its own QR codes: no other library.
 */
object InvitationQr {
    /** The text of the QR code in [pixels] (ARGB, row by row), or null if there's none. Dark-on-light or inverted. */
    fun decode(pixels: IntArray, width: Int, height: Int): String? = decode(RGBLuminanceSource(width, height, pixels))

    /** The text of the QR code in [plain] (a picture, or a camera frame's brightness plane), or null if there's none. */
    fun decode(plain: LuminanceSource): String? {
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
