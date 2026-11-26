package app.pairline.android.link.remote

import app.pairline.android.link.KeyWrapper
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.json.JSONArray
import org.json.JSONObject

/**
 * CloudKit Web Services in memory, enough for remote mode: `records/modify` (create, forceDelete) and `records/query`
 * by mailbox (public backend) or zone (private). Every good reply refreshes the token, with CloudKit's trailing `__`.
 */
internal class FakeCloudKit : CloudKitWebClient.Http {
    class Stored(val box: String, val name: String, val sealed: ByteArray)

    private val records = LinkedHashMap<String, Stored>()
    private var tokens = 0

    /** Every request's path ("records/modify", "records/query"). */
    val requests = mutableListOf<String>()

    /** HTTP statuses to answer the next requests with, instead of handling them. */
    val failures = ArrayDeque<Int>()

    /** The token the last good reply handed out, as the client stores it. */
    @get:Synchronized
    val lastToken: String get() = "token-$tokens"

    @Synchronized
    override fun post(url: String, body: String): CloudKitWebClient.Response {
        val path = "records/" + url.substringAfter("/records/").substringBefore('?')
        requests += path
        failures.removeFirstOrNull()?.let { return CloudKitWebClient.Response(it, emptyMap(), errorBody(it)) }
        val json = JSONObject(body)
        val zone = json.optJSONObject("zoneID")?.optString("zoneName")
        val reply = when (path) {
            "records/modify" -> modify(json, zone)
            "records/query" -> query(json, zone)
            else -> error("unexpected request $path")
        }
        return CloudKitWebClient.Response(200, mapOf("X-Apple-CloudKit-Web-Auth-Token" to "token-${++tokens}__"), reply.toString())
    }

    /** The iPhone's side: put a record into [box] (a mailbox id or a zone). */
    @Synchronized
    fun put(box: String, name: String, sealed: ByteArray) {
        records[name] = Stored(box, name, sealed)
    }

    /** The records in [box], oldest first. */
    @Synchronized
    fun inBox(box: String): List<Stored> = records.values.filter { it.box == box }

    /** Reads and removes the records in [box], as the iPhone does once it handled them. */
    @Synchronized
    fun take(box: String): List<Stored> = inBox(box).onEach { records.remove(it.name) }

    @Synchronized
    fun requestCount(): Int = requests.size

    private fun modify(json: JSONObject, zone: String?): JSONObject {
        val out = JSONArray()
        val operations = json.getJSONArray("operations")
        for (i in 0 until operations.length()) {
            val op = operations.getJSONObject(i)
            val record = op.getJSONObject("record")
            val name = record.getString("recordName")
            when (op.getString("operationType")) {
                "create" -> {
                    if (name in records) {
                        out.put(JSONObject().put("recordName", name).put("serverErrorCode", "SERVER_RECORD_CHANGED"))
                        continue
                    }
                    val fields = record.getJSONObject("fields")
                    val sealed = fields.optJSONObject("sealed")?.getString("value")?.let(Base64.getUrlDecoder()::decode)
                        ?: Base64.getDecoder().decode(fields.getJSONObject("payload").getString("value"))
                    val box = zone ?: fields.getJSONObject("mailbox").getString("value")
                    records[name] = Stored(box, name, sealed)
                    out.put(JSONObject().put("recordName", name))
                }
                "forceDelete" -> {
                    records.remove(name)
                    out.put(JSONObject().put("recordName", name).put("deleted", true))
                }
                else -> error("unexpected operation")
            }
        }
        return JSONObject().put("records", out)
    }

    private fun query(json: JSONObject, zone: String?): JSONObject {
        val box = zone ?: json.getJSONObject("query").getJSONArray("filterBy").getJSONObject(0)
            .getJSONObject("fieldValue").getString("value")
        val limit = json.optInt("resultsLimit", 100)
        val out = JSONArray()
        inBox(box).take(limit).forEachIndexed { i, r ->
            out.put(
                JSONObject().put("recordName", r.name).put("created", JSONObject().put("timestamp", i))
                    .put("fields", JSONObject().put("sealed", JSONObject().put("value", b64u(r.sealed)))),
            )
        }
        return JSONObject().put("records", out)
    }

    private fun errorBody(status: Int): String = when (status) {
        401 -> """{"serverErrorCode":"AUTHENTICATION_FAILED"}"""
        403 -> """{"serverErrorCode":"ACCESS_DENIED","reason":"private db access disabled for this account"}"""
        else -> """{"serverErrorCode":"SERVICE_UNAVAILABLE"}"""
    }

    private fun b64u(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal class MemoryKeyValueStore : KeyValueStore {
    val map = HashMap<String, String>()

    override fun get(key: String): String? = map[key]

    override fun put(values: Map<String, String?>) = values.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }

    override fun clear() = map.clear()
}

/** AES-GCM with a key in memory, standing in for the Android Keystore key. */
internal class TestWrapper : KeyWrapper {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun wrap(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(plaintext)
    }

    override fun unwrap(blob: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
        c.updateAAD(aad)
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

/** Keeps launched coroutines until a test runs them (or never, for a loop the test drives by hand). */
internal class QueueDispatcher : CoroutineDispatcher() {
    val tasks = ArrayList<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks += block
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeAt(0).run()
    }
}
