package app.pairline.android.debugtools

import android.content.Context
import app.pairline.android.AppGraph
import app.pairline.android.files.FileSource
import app.pairline.android.log.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * DEBUG BUILDS ONLY (D-018). Test hooks for the Wi-Fi link, driven through [DevToolsProvider]:
 * - media echo: every packet the iPhone (or fake_remote.py) sends on the media channel comes straight back, until
 *   call audio (M6) sets its own handler or echo is turned off;
 * - a test file of random bytes queued for the iPhone, so file transfer can be tested without the share sheet.
 */
object WifiDevTools {
    @Volatile
    var echo: Boolean = false
        private set
    private var watcher: Job? = null

    fun setEcho(on: Boolean) {
        echo = on
        if (watcher != null) return
        watcher = AppGraph.link.scope.launch {
            AppGraph.link.media.collect { channel ->
                if (channel != null && channel.onPacket == null) {
                    channel.onPacket = { packet -> if (echo) channel.send(packet) }
                }
            }
        }
        Log.i("devtools", "media echo ${if (on) "on" else "off"}")
    }

    /** Writes [size] random bytes to the cache and queues them for the iPhone. Returns the file's SHA-256 (hex). */
    fun sendTestFile(context: Context, size: Long): String {
        val dir = File(context.cacheDir, "devtools").apply { mkdirs() }
        val file = File(dir, "pairline-test-$size.bin")
        val digest = MessageDigest.getInstance("SHA-256")
        val random = SecureRandom()
        file.outputStream().buffered().use { out ->
            val buffer = ByteArray(64 * 1024)
            var left = size
            while (left > 0) {
                val n = minOf(left, buffer.size.toLong()).toInt()
                random.nextBytes(buffer)
                out.write(buffer, 0, n)
                digest.update(buffer, 0, n)
                left -= n
            }
        }
        AppGraph.files.enqueue(FileSource(file.name, size, "application/octet-stream") { FileInputStream(file) })
        Log.i("devtools", "queued a $size-byte test file")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
