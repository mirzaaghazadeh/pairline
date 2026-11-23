package app.pairline.android.protocol

import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey

/** Loads `protocol/test-vectors/v0.json` (path passed by Gradle; falls back to walking up from the working dir). */
object TestVectors {
    val json: JSONObject by lazy { JSONObject(file().readText(Charsets.UTF_8)) }

    private fun file(): File {
        System.getProperty("pairline.vectors")?.let { path ->
            File(path).takeIf { it.isFile }?.let { return it }
        }
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "protocol/test-vectors/v0.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("protocol/test-vectors/v0.json not found")
    }

    fun hex(path: String): ByteArray = Hex.decode(string(path))

    fun string(path: String): String {
        val parts = path.split('.')
        var obj = json
        for (p in parts.dropLast(1)) obj = obj.getJSONObject(p)
        return obj.getString(parts.last())
    }

    fun key(name: String): SoftwareKey {
        val k = json.getJSONObject("keys").getJSONObject(name)
        val public = P256.decodePublicKey(Hex.decode(k.getString("public")))
        return SoftwareKey(P256.privateKeyFromScalar(Hex.decode(k.getString("private"))), public)
    }

    fun publicKey(name: String): ByteArray = Hex.decode(json.getJSONObject("keys").getJSONObject(name).getString("public"))

    fun ecPublic(name: String): ECPublicKey = P256.decodePublicKey(publicKey(name))
}

/** Returns fixed byte strings in order, then fails: makes "random" values deterministic in tests. */
class FixedRandom(vararg chunks: ByteArray) : SecureRandom() {
    private val queue = ArrayDeque(chunks.toList())

    override fun nextBytes(bytes: ByteArray) {
        val next = queue.removeFirstOrNull() ?: error("FixedRandom ran out of values")
        require(next.size == bytes.size) { "expected ${bytes.size} bytes, have ${next.size}" }
        next.copyInto(bytes)
    }
}
