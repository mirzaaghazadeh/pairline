package app.pairline.android.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * The only code in the release app that talks to the internet on its own (AGENTS.md rule 2, D-041/D-042): one
 * request for GitHub's public release list, and the APK download when the user taps Update. HTTPS only, GitHub
 * hosts only (redirects are followed by hand and checked), no cookies, no user data, and a generic User-Agent.
 */
internal object GitHubClient {
    const val RELEASES_URL = "https://api.github.com/repos/mirzaaghazadeh/pairline/releases?per_page=20"

    private const val USER_AGENT = "Pairline-Bridge"
    private const val MAX_JSON_BYTES = 4L * 1024 * 1024
    private const val MAX_APK_BYTES = 150L * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    class HttpException(val code: Int) : IOException("HTTP $code")

    /** GitHub's release list, as text. */
    fun releases(): String = open(RELEASES_URL, "application/vnd.github+json").use { response ->
        String(readLimited(response.stream, MAX_JSON_BYTES), Charsets.UTF_8)
    }

    /**
     * Downloads [url] into [dest] and returns the SHA-256 (lowercase hex) of what was written. [expectedSize] (if
     * known) caps the download; [onProgress] gets bytes so far and the total (or -1).
     */
    fun download(url: String, dest: File, expectedSize: Long, onProgress: (Long, Long) -> Unit): String {
        val limit = if (expectedSize > 0) minOf(expectedSize, MAX_APK_BYTES) else MAX_APK_BYTES
        val digest = MessageDigest.getInstance("SHA-256")
        open(url, "application/octet-stream").use { response ->
            val total = response.length.takeIf { it > 0 } ?: expectedSize
            if (total > limit) throw IOException("download larger than expected")
            dest.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = response.stream.read(buffer)
                    if (n < 0) break
                    done += n
                    if (done > limit) throw IOException("download larger than expected")
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    onProgress(done, total)
                }
                out.fd.sync()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class Response(val connection: HttpsURLConnection, val stream: InputStream, val length: Long) : AutoCloseable {
        override fun close() {
            runCatching { stream.close() }
            connection.disconnect()
        }
    }

    /** Opens [start], following up to five redirects as long as each one stays on HTTPS and a GitHub host. */
    private fun open(start: String, accept: String): Response {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            if (!GitHubHosts.isAllowedUrl(url)) throw IOException("not a GitHub address")
            val connection = URL(url).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (URI(url).host == "api.github.com") connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val code = connection.responseCode
            when (code) {
                HttpsURLConnection.HTTP_OK -> return Response(connection, connection.inputStream, connection.contentLengthLong)
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    url = URI(url).resolve(location ?: throw IOException("redirect without a location")).toString()
                }
                else -> {
                    connection.disconnect()
                    throw HttpException(code)
                }
            }
        }
        throw IOException("too many redirects")
    }

    private fun readLimited(input: InputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > max) throw IOException("reply too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
