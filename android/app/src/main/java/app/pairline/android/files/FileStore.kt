package app.pairline.android.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Where a received file is written while it arrives. */
interface FileSink {
    fun write(data: ByteArray)

    /** The file is complete: make it visible. Returns a content URI that opens it. */
    fun commit(): Uri

    /** Drop the partial file. */
    fun abort()
}

/** A file the user shared, opened while Android's permission to read it was still valid. */
class FileSource(val name: String, val size: Long, val mime: String?, private val open: () -> InputStream) {
    fun openStream(): InputStream = open()
}

/**
 * Storage for transfers. Received files go to Downloads/Pairline through MediaStore on Android 10+ (no storage
 * permission needed); on Android 9 to Pairline's own folder in shared storage (Android/data/…/Download/Pairline),
 * opened through a FileProvider, so no storage permission is needed there either.
 */
object FileStore {
    private const val FOLDER = "Pairline"
    private const val RESERVE_BYTES = 64L * 1024 * 1024

    /** A received name, made safe: no path separators, control characters or leading dots; at most 255 bytes. */
    fun sanitizeName(raw: String?): String {
        var name = (raw ?: "").replace(Regex("[\\u0000-\\u001f\\u007f/\\\\:*?\"<>|]"), "_").trim().trimStart('.')
        if (name.isEmpty()) name = "file"
        while (name.toByteArray(Charsets.UTF_8).size > 255) name = name.dropLast(1)
        return name
    }

    fun hasRoomFor(context: Context, size: Long): Boolean {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return try {
            StatFs(dir.path).availableBytes > size + RESERVE_BYTES
        } catch (_: IllegalArgumentException) {
            true
        }
    }

    fun createSink(context: Context, name: String, mime: String?): FileSink? =
        if (Build.VERSION.SDK_INT >= 29) MediaStoreSink.create(context, name, mime) else LegacySink.create(context, name)

    /**
     * Opens a shared item right away (the read permission Android gave the share sheet may not last) and learns its
     * name, size and type. Only `content:` URIs: a `file:` URI could point into Pairline's own private files.
     */
    fun openSource(context: Context, uri: Uri, fallbackMime: String?): FileSource? {
        if (uri.scheme != "content") return null
        if (uri.authority == context.packageName + ".files") return null
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: fallbackMime
        val pfd: ParcelFileDescriptor = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
        if (pfd.statSize >= 0) size = pfd.statSize
        val safeName = sanitizeName(name ?: uri.lastPathSegment)
        if (size >= 0) {
            return FileSource(safeName, size, mime) { ParcelFileDescriptor.AutoCloseInputStream(pfd) }
        }
        // A stream without a known length: copy it to the cache first, to learn the size (up to the limit).
        val dir = File(context.cacheDir, "outgoing").apply { mkdirs() }
        val copy = File.createTempFile("share", ".tmp", dir)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                FileOutputStream(copy).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > app.pairline.android.protocol.Proto.FILE_MAX_SIZE) throw java.io.IOException("too large")
                        out.write(buffer, 0, n)
                    }
                }
            }
        } catch (_: Exception) {
            copy.delete()
            return null
        }
        return FileSource(safeName, copy.length(), mime) {
            object : java.io.FileInputStream(copy) {
                override fun close() {
                    super.close()
                    copy.delete()
                }
            }
        }
    }

    @RequiresApi(29)
    private class MediaStoreSink(private val context: Context, private val uri: Uri, private val out: OutputStream) :
        FileSink {
        override fun write(data: ByteArray) = out.write(data)

        override fun commit(): Uri {
            out.close()
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        }

        override fun abort() {
            runCatching { out.close() }
            runCatching { context.contentResolver.delete(uri, null, null) }
        }

        companion object {
            fun create(context: Context, name: String, mime: String?): FileSink? {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = runCatching {
                    resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                }.getOrNull() ?: return null
                val out = runCatching { resolver.openOutputStream(uri) }.getOrNull()
                if (out == null) {
                    runCatching { resolver.delete(uri, null, null) }
                    return null
                }
                return MediaStoreSink(context, uri, out.buffered(256 * 1024))
            }
        }
    }

    private class LegacySink(private val context: Context, private val part: File, private val target: File) : FileSink {
        private val out = FileOutputStream(part).buffered(256 * 1024)

        override fun write(data: ByteArray) = out.write(data)

        override fun commit(): Uri {
            out.close()
            if (!part.renameTo(target)) throw java.io.IOException("rename failed")
            return FileProvider.getUriForFile(context, context.packageName + ".files", target)
        }

        override fun abort() {
            runCatching { out.close() }
            part.delete()
        }

        companion object {
            fun create(context: Context, name: String): FileSink? {
                val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
                val dir = File(base, FOLDER).apply { mkdirs() }
                val target = freeName(dir, name)
                return runCatching { LegacySink(context, File(dir, target.name + ".part"), target) }.getOrNull()
            }

            private fun freeName(dir: File, name: String): File {
                var file = File(dir, name)
                if (!file.exists()) return file
                val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
                val stem = name.substring(0, dot)
                val ext = name.substring(dot)
                var i = 1
                while (file.exists()) file = File(dir, "$stem ($i)$ext").also { i++ }
                return file
            }
        }
    }
}
