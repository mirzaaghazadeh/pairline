package app.pairline.android.files

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import app.pairline.android.AppPrefs
import app.pairline.android.log.Log
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.JsonPager
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.RequestException
import app.pairline.android.telephony.Perms
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/**
 * The Android phone's photos, browsed from the iPhone (D-088): small thumbnails made here (`media.list`), and the
 * original sent with the normal file transfer when the user taps one (`media.get`). Off until the user turns on
 * "Photos for the iPhone" in the bridge app and allows photo access; only over the Wi-Fi link, never Bluetooth.
 */
class PhotoLibrary(private val context: Context, private val prefs: AppPrefs, private val files: FileTransfers) {

    /** The Android permission that covers reading photos on this version. */
    val permission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun available(): Boolean = prefs.photosEnabled && Perms.granted(context, permission)

    private fun require() {
        if (!prefs.photosEnabled) throw RequestException(ErrorCode.PERMISSION, "photos are off in the bridge app")
        if (!Perms.granted(context, permission)) throw RequestException(ErrorCode.PERMISSION, "photo access not given")
    }

    /** One row of MediaStore: newest first, ordered by when it was added, then its id. */
    private class Photo(val id: Long, val added: Long, val taken: Long?, val width: Int, val height: Int, val size: Long)

    /**
     * `media.list`: photos older than [before] (the cursor from the last page, "seconds:id"), newest first, each with a
     * thumbnail of [THUMB_PX] pixels as base64 JPEG. Pages stay under the usual 30 KB.
     */
    fun list(id: Long, before: String?, limit: Int): JSONObject {
        require()
        val (secs, lastId) = parseCursor(before)
        val selection = if (secs != null) {
            "${MediaStore.Images.Media.DATE_ADDED} < $secs OR (${MediaStore.Images.Media.DATE_ADDED} = $secs AND ${MediaStore.Images.Media._ID} < $lastId)"
        } else {
            null
        }
        val rows = ArrayList<Photo>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE,
            ),
            selection,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
        )?.use { c ->
            while (c.moveToNext() && rows.size < limit + 1) {
                rows += Photo(
                    id = c.getLong(0),
                    added = c.getLong(1),
                    taken = if (c.isNull(2)) null else c.getLong(2),
                    width = c.getInt(3),
                    height = c.getInt(4),
                    size = c.getLong(5),
                )
            }
        }
        val (page, taken) = JsonPager.page(rows.iterator(), limit) { photo ->
            JSONObject()
                .put("mid", photo.id)
                .put("time", photo.taken ?: (photo.added * 1000))
                .put("w", photo.width)
                .put("h", photo.height)
                .put("size", photo.size)
                .put("thumb", thumbnail(photo.id) ?: "")
        }
        val last = taken.lastOrNull()
        return JSONObject()
            .put("t", Msg.MEDIA_LIST)
            .put("id", id)
            .put("items", page.items)
            .put("more", page.more)
            .put("cursor", last?.let { "${it.added}:${it.id}" } ?: JSONObject.NULL)
    }

    /** `media.get`: the original goes to the iPhone through the file transfer, over the Wi-Fi link. */
    fun get(mid: Long) {
        require()
        if (mid <= 0) throw RequestException(ErrorCode.INVALID, "bad mid")
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mid)
        if (files.send(listOf(uri), "image/jpeg") == 0) throw RequestException(ErrorCode.INVALID, "no such photo")
        Log.i(TAG, "a photo was asked for by the iPhone")
    }

    @Suppress("DEPRECATION")
    private fun thumbnail(mid: Long): String? = runCatching {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mid)
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null)
        } else {
            MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver, mid, MediaStore.Images.Thumbnails.MINI_KIND, null,
            ) ?: return@runCatching null
        }
        val scaled = scaleDown(bitmap, THUMB_PX)
        var quality = 55
        var bytes: ByteArray
        do {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            quality -= 15
        } while (bytes.size > MAX_THUMB_BYTES && quality > 10)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    private fun scaleDown(bitmap: Bitmap, px: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= px) return bitmap
        val f = px.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt().coerceAtLeast(1), (bitmap.height * f).toInt().coerceAtLeast(1), true)
    }

    companion object {
        private const val TAG = "photos"
        const val THUMB_PX = 128
        private const val MAX_THUMB_BYTES = 4_000

        /** "seconds:id" → (seconds, id); null parts for the first page. */
        fun parseCursor(raw: String?): Pair<Long?, Long> {
            val parts = raw?.split(':') ?: return null to 0L
            if (parts.size != 2) throw RequestException(ErrorCode.INVALID, "bad cursor")
            val secs = parts[0].toLongOrNull() ?: throw RequestException(ErrorCode.INVALID, "bad cursor")
            val id = parts[1].toLongOrNull() ?: throw RequestException(ErrorCode.INVALID, "bad cursor")
            return secs to id
        }

        fun limit(requested: Long?): Int = (requested ?: 30L).coerceIn(1, 60).toInt()
    }
}
