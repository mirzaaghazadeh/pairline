package app.pairline.android.link.remote

import app.pairline.android.protocol.RemoteBackend
import app.pairline.android.protocol.RemoteEnvironment
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remote mode's mailbox in the user's own iCloud (D-092, docs/PROTOCOL.md "Mailboxes in iCloud"), through CloudKit
 * Web Services. It reaches only [HOST], with Pairline's API token and the web auth token the user's iPhone handed
 * over; nothing else of the user's goes into a request but sealed records and their mailbox ids.
 *
 * Every response carries a refreshed web auth token, which replaces the stored one before anything else happens;
 * requests never overlap, so a lost response can't fork the token.
 */
class CloudKitWebClient(
    private val env: RemoteEnvironment,
    private val backend: RemoteBackend,
    private val tokens: TokenStore,
    private val http: Http = HttpsTransport,
) {
    /** Where a record goes: in the private backend a zone, in the public one a mailbox id (32 hex digits). */
    data class MailboxAddress(val zone: String, val id: String)

    data class MailRecord(val name: String, val sealed: ByteArray)

    /** Where the web auth token lives (encrypted on the phone); [save] is called with every refreshed token. */
    interface TokenStore {
        fun load(): String?
        fun save(token: String)
    }

    fun interface Http {
        fun post(url: String, body: String): Response
    }

    class Response(val status: Int, val headers: Map<String, String>, val body: String)

    /** The token is missing or no longer accepted: the iPhone has to send a fresh one (`remote.token`). */
    class TokenRejected(val status: Int) : IOException("web auth token rejected ($status)")

    /** Apple refuses this account's private database to web clients (D-092). */
    class PrivateDatabaseRefused : IOException("private database refused")

    class ServerError(val status: Int, val code: String?) : IOException("CloudKit $status ${code ?: ""}".trim())

    private val lock = Any()

    /** Writes one sealed record; [recordName] is the record id in hex (docs/PROTOCOL.md). */
    fun put(mailbox: MailboxAddress, recordName: String, sealed: ByteArray) {
        val fields = JSONObject()
        val text = b64u(sealed)
        if (text.length <= MAX_SEALED_TEXT) {
            fields.put("sealed", JSONObject().put("value", text))
        } else {
            fields.put("payload", JSONObject().put("value", Base64.getEncoder().encodeToString(sealed)))
        }
        if (backend == RemoteBackend.PUBLIC) fields.put("mailbox", JSONObject().put("value", mailbox.id))
        val operation = JSONObject().put("operationType", "create")
            .put("record", JSONObject().put("recordType", RECORD_TYPE).put("recordName", recordName).put("fields", fields))
        val result = call("records/modify", withZone(mailbox, JSONObject().put("operations", JSONArray().put(operation))))
        failedRecord(result)?.let { throw ServerError(200, it) }
    }

    /** The records waiting in [mailbox], oldest first (the R→B mailbox; the caller opens and deletes them). */
    fun fetch(mailbox: MailboxAddress, limit: Int = 50): List<MailRecord> {
        val body = JSONObject()
            .put("query", JSONObject().put("recordType", RECORD_TYPE).apply {
                if (backend == RemoteBackend.PUBLIC) {
                    put("filterBy", JSONArray().put(JSONObject().put("fieldName", "mailbox")
                        .put("comparator", "EQUALS").put("fieldValue", JSONObject().put("value", mailbox.id))))
                }
            })
            .put("resultsLimit", limit)
        val records = call("records/query", withZone(mailbox, body)).optJSONArray("records") ?: return emptyList()
        return (0 until records.length()).mapNotNull { i -> parseRecord(records.getJSONObject(i)) }
            .sortedBy { it.created }
            .map { it.record }
    }

    /** Deletes handled records; records that are already gone count as deleted. */
    fun delete(mailbox: MailboxAddress, names: List<String>) {
        if (names.isEmpty()) return
        val operations = JSONArray()
        names.forEach { name ->
            operations.put(JSONObject().put("operationType", "forceDelete").put("record", JSONObject().put("recordName", name)))
        }
        call("records/modify", withZone(mailbox, JSONObject().put("operations", operations)))
    }

    private class Parsed(val record: MailRecord, val created: Long)

    private fun parseRecord(json: JSONObject): Parsed? {
        if (json.has("serverErrorCode")) return null
        val name = json.optString("recordName").takeIf { it.isNotEmpty() } ?: return null
        val fields = json.optJSONObject("fields") ?: return null
        val sealed = fields.optJSONObject("sealed")?.optString("value")?.takeIf { it.isNotEmpty() }?.let(::fromB64u)
            ?: fields.optJSONObject("payload")?.optString("value")?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?: return null
        val created = json.optJSONObject("created")?.optLong("timestamp") ?: 0L
        return Parsed(MailRecord(name, sealed), created)
    }

    private fun withZone(mailbox: MailboxAddress, body: JSONObject): JSONObject =
        if (backend == RemoteBackend.PRIVATE) body.put("zoneID", JSONObject().put("zoneName", mailbox.zone)) else body

    private fun failedRecord(result: JSONObject): String? {
        val records = result.optJSONArray("records") ?: return null
        for (i in 0 until records.length()) {
            val code = records.getJSONObject(i).optString("serverErrorCode")
            if (code.isNotEmpty()) return code
        }
        return null
    }

    /** One request; stores the refreshed token before returning, and maps CloudKit's errors. */
    private fun call(path: String, body: JSONObject): JSONObject = synchronized(lock) {
        val token = tokens.load() ?: throw TokenRejected(0)
        val apiToken = API_TOKENS[env] ?: throw ServerError(0, "no API token for ${env.wire}")
        val url = "https://$HOST/database/1/$CONTAINER/${env.wire}/${backend.wire}/$path" +
            "?ckAPIToken=${encode(apiToken)}&ckWebAuthToken=${encode(token)}"
        val response = http.post(url, body.toString())
        refreshedToken(response.headers)?.let(tokens::save)
        val json = runCatching { JSONObject(response.body) }.getOrElse { JSONObject() }
        when (response.status) {
            200 -> json
            401, 421 -> throw TokenRejected(response.status)
            403 -> if (json.optString("reason").contains("private db access disabled")) {
                throw PrivateDatabaseRefused()
            } else {
                throw ServerError(403, json.optString("serverErrorCode").ifEmpty { null })
            }
            else -> throw ServerError(response.status, json.optString("serverErrorCode").ifEmpty { null })
        }
    }

    companion object {
        const val HOST = "api.apple-cloudkit.com"
        const val CONTAINER = "iCloud.app.pairline.ios"
        const val RECORD_TYPE = "Mail"

        /**
         * CloudKit API tokens are made for web pages (CloudKit Console › Tokens & Keys); one alone reads nothing, a
         * signed-in user's web auth token is needed too. Development serves debug iPhones; Production serves TestFlight
         * iPhones now and App Store iPhones once Cloud mode ships there (D-092).
         */
        private val API_TOKENS = mapOf(
            RemoteEnvironment.DEVELOPMENT to "88f1d920f369a9d3976a70aa7d7ddb5ac7faca8b1545f01fc9c2aef5ce435a1b",
            RemoteEnvironment.PRODUCTION to "77a377504d64d4de8978f7d16bdae9b7758a00ee1c0bdc513e62cf0a087edb0a",
        )

        /** A `sealed` text field of at most this many characters fits in the push; larger records use `payload`. */
        const val MAX_SEALED_TEXT = 2_700

        private val TOKEN_HEADERS = listOf("X-Apple-CloudKit-Web-Auth-Token", "X-Apple-CloudKit-Session")

        /** CloudKit returns refreshed tokens with a trailing `__` that it then refuses (D-092, measured). */
        fun normalizeToken(token: String): String = token.trim().removeSuffix("__")

        internal fun refreshedToken(headers: Map<String, String>): String? {
            for (name in TOKEN_HEADERS) {
                val value = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
                if (!value.isNullOrBlank()) return normalizeToken(value)
            }
            return null
        }

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun b64u(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun fromB64u(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()
    }

    /** HTTPS to [HOST] only, no redirects, no cookies, a generic User-Agent. */
    object HttpsTransport : Http {
        private const val MAX_REPLY = 2L * 1024 * 1024

        override fun post(url: String, body: String): Response {
            require(URL(url).let { it.protocol == "https" && it.host == HOST }) { "not CloudKit" }
            val connection = URL(url).openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("User-Agent", "Pairline-Bridge")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.use { String(readLimited(it), Charsets.UTF_8) } ?: ""
                val headers = connection.headerFields.entries
                    .filter { it.key != null && it.value.isNotEmpty() }
                    .associate { it.key to it.value.first() }
                return Response(status, headers, text)
            } finally {
                connection.disconnect()
            }
        }

        private fun readLimited(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_REPLY) throw IOException("reply too large")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
