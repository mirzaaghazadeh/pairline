package app.pairline.android.ui.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvitationQrTest {
    /** As long as a real invitation: version, keys, secret, mailboxes and an iCloud token. */
    private val invitation = "pairline://invite?v=1&k=" + "B".repeat(87) + "&s=" + "c".repeat(43) + "&b=pub&e=dev" +
        "&t=" + "T".repeat(120) + "&m=" + "m".repeat(22) + "&a=" + "a".repeat(22) + "&q=" + "q".repeat(22) + "&x=1764170000"

    /** The QR code of [text] as ARGB pixels, [scale] pixels a module, with a quiet zone; optionally light on dark. */
    private fun picture(text: String, scale: Int, inverted: Boolean = false): Triple<IntArray, Int, Int> {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4),
        )
        val width = matrix.width * scale
        val height = matrix.height * scale
        val pixels = IntArray(width * height) { i ->
            val dark = matrix[(i % width) / scale, (i / width) / scale] != inverted
            if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Triple(pixels, width, height)
    }

    @Test
    fun readsAnInvitation() {
        val (pixels, width, height) = picture(invitation, scale = 6)
        assertEquals(invitation, InvitationQr.decode(pixels, width, height))
    }

    @Test
    fun readsALightOnDarkScreenshot() {
        val (pixels, width, height) = picture(invitation, scale = 5, inverted = true)
        assertEquals(invitation, InvitationQr.decode(pixels, width, height))
    }

    @Test
    fun aPictureWithoutACodeGivesNothing() {
        val width = 400
        val height = 300
        val pixels = IntArray(width * height) { i -> if ((i / width + i % width) % 7 == 0) 0xFF808080.toInt() else -1 }
        assertNull(InvitationQr.decode(pixels, width, height))
    }
}
