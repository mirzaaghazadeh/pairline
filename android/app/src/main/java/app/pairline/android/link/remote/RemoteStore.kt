package app.pairline.android.link.remote

import android.content.Context
import androidx.core.content.edit
import app.pairline.android.link.KeyWrapper
import app.pairline.android.link.KeystoreAesWrapper
import app.pairline.android.log.Log
import app.pairline.android.protocol.B64u
import app.pairline.android.protocol.Mailbox
import app.pairline.android.protocol.RemoteBackend
import app.pairline.android.protocol.RemoteEnvironment
import app.pairline.android.protocol.RemoteMailboxes
import org.json.JSONObject

/** The few strings remote mode keeps: SharedPreferences on the phone, a map in tests. */
interface KeyValueStore {
    fun get(key: String): String?

    /** Writes every entry (null removes the key), on disk before it returns. */
    fun put(values: Map<String, String?>)

    fun clear()
}

class PrefsKeyValueStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(values: Map<String, String?>) = prefs.edit(commit = true) {
        values.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
    }

    override fun clear() = prefs.edit(commit = true) { clear() }
}

/**
 * Remote mode's state on this phone (D-080): whether it's on, where the mailboxes are, the web auth token (encrypted
 * with an AES-GCM key that stays in Android Keystore, like the wrapped identity key), the records this phone has
 * handled (kept 14 days, PROTOCOL.md "Receiving") and the ones it sent (deleted from iCloud after a day). Excluded
 * from backups like all app data. [clear] deletes all of it.
 */
class RemoteStore(
    private val kv: KeyValueStore,
    private val wrapper: KeyWrapper,
) : CloudKitWebClient.TokenStore {

    /** Why remote mode stopped checking until the iPhone sends a new token. */
    enum class Problem(val wire: String) {
        TOKEN_REJECTED("token"),
        PRIVATE_DATABASE_REFUSED("private_db");

        companion object {
            fun of(wire: String?): Problem? = entries.firstOrNull { it.wire == wire }
        }
    }

    private var config: RemoteConfig? = null
    private var configLoaded = false
    private var token: String? = null
    private var tokenLoaded = false
    private val handled = TimedIds(HANDLED)
    private val sent = mapOf(MailboxSlot.ALERT to TimedIds(SENT_ALERT), MailboxSlot.QUIET to TimedIds(SENT_QUIET))

    /** Remote mode is on: the config is complete. */
    val isOn: Boolean get() = config() != null

    @Synchronized
    fun config(): RemoteConfig? {
        if (!configLoaded) {
            config = readConfig()
            configLoaded = true
        }
        return config
    }

    /** Turns remote mode on with [config] and a fresh [token]; clears a recorded problem. */
    @Synchronized
    fun turnOn(config: RemoteConfig, token: String) {
        kv.put(
            mapOf(
                ON to "1",
                BACKEND to config.backend.wire,
                ENV to config.environment.wire,
                MB_TO_BRIDGE to config.mailboxes.toBridge,
                MB_ALERT to config.mailboxes.alert,
                MB_QUIET to config.mailboxes.quiet,
                TOKEN to wrapToken(token),
                PROBLEM to null,
            ),
        )
        this.config = config
        this.token = token
        configLoaded = true
        tokenLoaded = true
    }

    /** The web auth token, decrypted once and then kept in memory. */
    @Synchronized
    override fun load(): String? {
        if (!tokenLoaded) {
            token = kv.get(TOKEN)?.let(::unwrapToken)
            tokenLoaded = true
        }
        return token
    }

    /**
     * A refreshed token after each request, or a new one from the iPhone (`remote.token`). Like every write below, it's
     * ignored while remote mode is off: a request still under way when it was turned off leaves nothing behind.
     */
    @Synchronized
    override fun save(token: String) {
        if (!isOn || token == load()) return
        kv.put(mapOf(TOKEN to wrapToken(token)))
        this.token = token
    }

    var problem: Problem?
        @Synchronized get() = Problem.of(kv.get(PROBLEM))

        @Synchronized set(value) {
            if (isOn) kv.put(mapOf(PROBLEM to value?.wire))
        }

    @Synchronized
    fun isHandled(recordName: String): Boolean = recordName in handled.map()

    /** Remembers handled record ids for 14 days; older ones are dropped. */
    @Synchronized
    fun markHandled(recordNames: Collection<String>, now: Long) {
        if (!isOn || recordNames.isEmpty()) return
        val map = handled.map()
        recordNames.forEach { map[it] = now }
        map.entries.removeIf { it.value < now - Mailbox.MAX_AGE_MS }
        kv.put(mapOf(handled.persist()))
    }

    /** A record this phone wrote into [slot]; deleted from iCloud a day later if the iPhone hasn't already. */
    @Synchronized
    fun recordSent(slot: MailboxSlot, recordName: String, time: Long) {
        if (!isOn) return
        val ids = sent[slot] ?: return
        val map = ids.map()
        map[recordName] = time
        while (map.size > MAX_SENT) map.remove(map.keys.first())
        kv.put(mapOf(ids.persist()))
    }

    /** The records this phone wrote into [slot] before [time]. */
    @Synchronized
    fun sentBefore(slot: MailboxSlot, time: Long): List<String> =
        sent[slot]?.map()?.filterValues { it < time }?.keys?.toList().orEmpty()

    /** Everything this phone wrote that may still be in iCloud. */
    @Synchronized
    fun allSent(): Map<MailboxSlot, List<String>> = sent.mapValues { (_, ids) -> ids.map().keys.toList() }

    @Synchronized
    fun dropSent(slot: MailboxSlot, recordNames: Collection<String>) {
        if (!isOn) return
        val ids = sent[slot] ?: return
        ids.map().keys.removeAll(recordNames.toSet())
        kv.put(mapOf(ids.persist()))
    }

    /** Remote mode off, or the pairing forgotten: deletes everything above. */
    @Synchronized
    fun clear() {
        kv.clear()
        config = null
        token = null
        configLoaded = true
        tokenLoaded = true
        handled.reset()
        sent.values.forEach { it.reset() }
    }

    private fun readConfig(): RemoteConfig? {
        if (kv.get(ON) != "1") return null
        val backend = kv.get(BACKEND)?.let(RemoteBackend::of) ?: return null
        val env = kv.get(ENV)?.let(RemoteEnvironment::of) ?: return null
        val mailboxes = RemoteMailboxes(
            kv.get(MB_TO_BRIDGE) ?: return null,
            kv.get(MB_ALERT) ?: return null,
            kv.get(MB_QUIET) ?: return null,
        )
        return RemoteConfig(backend, env, mailboxes).takeIf { mailboxes.isValid() }
    }

    private fun wrapToken(token: String): String = B64u.encode(wrapper.wrap(token.toByteArray(Charsets.UTF_8), TOKEN_AAD))

    /** Null if the Keystore key is gone or the blob was changed: the iPhone then sends a new token. */
    private fun unwrapToken(blob: String): String? = try {
        B64u.decodeOrNull(blob)?.let { String(wrapper.unwrap(it, TOKEN_AAD), Charsets.UTF_8) }
    } catch (e: Exception) {
        Log.w(TAG, "the stored web auth token can't be read", e)
        null
    }

    /** Record names with a time, kept as a JSON object under one key. */
    private inner class TimedIds(private val key: String) {
        private var cached: LinkedHashMap<String, Long>? = null

        fun map(): LinkedHashMap<String, Long> = cached ?: read().also { cached = it }

        fun persist(): Pair<String, String?> = key to map().takeIf { it.isNotEmpty() }?.let { JSONObject(it).toString() }

        fun reset() {
            cached = null
        }

        private fun read(): LinkedHashMap<String, Long> {
            val out = LinkedHashMap<String, Long>()
            val json = kv.get(key)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return out
            json.keys().asSequence().sortedBy { json.optLong(it) }.forEach { out[it] = json.optLong(it) }
            return out
        }
    }

    companion object {
        private const val TAG = "remote"
        private const val ON = "on"
        private const val BACKEND = "backend"
        private const val ENV = "env"
        private const val MB_TO_BRIDGE = "mb.b"
        private const val MB_ALERT = "mb.a"
        private const val MB_QUIET = "mb.q"
        private const val TOKEN = "token"
        private const val PROBLEM = "problem"
        private const val HANDLED = "handled"
        private const val SENT_ALERT = "sent.a"
        private const val SENT_QUIET = "sent.q"
        private const val MAX_SENT = 1000
        private val TOKEN_AAD = "pairline remote token v1".toByteArray(Charsets.US_ASCII)

        fun create(context: Context): RemoteStore =
            RemoteStore(PrefsKeyValueStore(context, "pairline.remote"), KeystoreAesWrapper("pairline.remote.wrap.v1"))
    }
}
