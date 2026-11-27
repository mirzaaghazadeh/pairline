package app.pairline.android.link.remote

import app.pairline.android.link.remote.relay.RelayConfig
import app.pairline.android.protocol.B64u
import app.pairline.android.protocol.Mailbox
import app.pairline.android.protocol.RemoteBackend
import app.pairline.android.protocol.RemoteEnvironment
import app.pairline.android.protocol.RemoteMailboxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteStoreTest {
    private val kv = MemoryKeyValueStore()
    private val wrapper = TestWrapper()
    private val config = RemoteConfig(
        RemoteBackend.PRIVATE,
        RemoteEnvironment.DEVELOPMENT,
        RemoteMailboxes("11".repeat(16), "22".repeat(16), "33".repeat(16)),
    )

    @Test
    fun theTokenIsEncryptedAtRestAndSurvivesARestart() {
        val token = "web-auth-token_with+chars/0123456789"
        RemoteStore(kv, wrapper).turnOn(config, token)
        assertTrue(kv.map.values.none { it.contains(token) })
        val again = RemoteStore(kv, wrapper)
        assertEquals(config, again.config())
        assertEquals(token, again.load())
        again.save("refreshed")
        assertEquals("refreshed", RemoteStore(kv, wrapper).load())
    }

    @Test
    fun aTokenThatCantBeDecryptedIsMissing() {
        RemoteStore(kv, wrapper).turnOn(config, "t")
        // Another Keystore key (the old one was lost): the iPhone has to send a new token.
        val other = RemoteStore(kv, TestWrapper())
        assertTrue(other.isOn)
        assertNull(other.load())
    }

    @Test
    fun handledIdsAreKeptFourteenDays() {
        val store = RemoteStore(kv, wrapper)
        store.turnOn(config, "t")
        val t0 = 1_764_000_000_000L
        store.markHandled(listOf("aa"), t0)
        store.markHandled(listOf("bb"), t0 + Mailbox.MAX_AGE_MS - 1)
        assertTrue(store.isHandled("aa"))
        store.markHandled(listOf("cc"), t0 + Mailbox.MAX_AGE_MS + 1)
        val again = RemoteStore(kv, wrapper)
        assertFalse(again.isHandled("aa"))
        assertTrue(again.isHandled("bb"))
        assertTrue(again.isHandled("cc"))
    }

    @Test
    fun sentRecordsAreTrackedPerMailbox() {
        val store = RemoteStore(kv, wrapper)
        store.turnOn(config, "t")
        store.recordSent(MailboxSlot.ALERT, "a1", 100)
        store.recordSent(MailboxSlot.QUIET, "q1", 200)
        store.recordSent(MailboxSlot.QUIET, "q2", 300)
        assertEquals(listOf("q1"), store.sentBefore(MailboxSlot.QUIET, 250))
        store.dropSent(MailboxSlot.QUIET, listOf("q1"))
        assertEquals(mapOf(MailboxSlot.ALERT to listOf("a1"), MailboxSlot.QUIET to listOf("q2")), RemoteStore(kv, wrapper).allSent())
    }

    @Test
    fun aProblemIsRememberedUntilRemoteModeIsTurnedOnAgain() {
        val store = RemoteStore(kv, wrapper)
        store.turnOn(config, "t")
        store.problem = RemoteStore.Problem.PRIVATE_DATABASE_REFUSED
        assertEquals(RemoteStore.Problem.PRIVATE_DATABASE_REFUSED, RemoteStore(kv, wrapper).problem)
        store.turnOn(config, "t2")
        assertNull(store.problem)
    }

    @Test
    fun clearDeletesEverything() {
        val store = RemoteStore(kv, wrapper)
        store.turnOn(config, "t")
        store.markHandled(listOf("aa"), 1)
        store.recordSent(MailboxSlot.ALERT, "a1", 1)
        store.problem = RemoteStore.Problem.TOKEN_REJECTED
        store.clear()
        assertTrue(kv.map.isEmpty())
        assertFalse(store.isOn)
        assertNull(store.load())
        assertFalse(store.isHandled("aa"))
        assertTrue(store.allSent().values.all { it.isEmpty() })
    }

    @Test
    fun nothingIsWrittenWhileRemoteModeIsOff() {
        // A request that was under way when remote mode was turned off comes back with a refreshed token.
        val store = RemoteStore(kv, wrapper)
        store.save("late")
        store.markHandled(listOf("aa"), 1)
        store.recordSent(MailboxSlot.QUIET, "q1", 1)
        store.problem = RemoteStore.Problem.TOKEN_REJECTED
        assertFalse(store.setRelay(relay))
        assertTrue(kv.map.isEmpty())
        assertNull(store.load())
        assertNull(store.relay(debugBuild = false))
    }

    private val relay = RelayConfig("relay.example.net", 443, ByteArray(32) { 1 }, ByteArray(32) { 2 }, ByteArray(16) { 3 })

    /** The user's own server (D-106): encrypted at rest like the token, and gone with remote mode or a new setup. */
    @Test
    fun theRelayIsEncryptedAtRestAndGoesWithRemoteMode() {
        val store = RemoteStore(kv, wrapper)
        store.turnOn(config, "t")
        assertNull(store.relay(debugBuild = false))
        assertTrue(store.setRelay(relay))
        assertTrue(kv.map.values.none { it.contains("relay.example.net") || it.contains(B64u.encode(relay.key)) })
        assertEquals(relay, RemoteStore(kv, wrapper).relay(debugBuild = false))
        // Removed by the iPhone.
        assertTrue(store.setRelay(null))
        assertNull(RemoteStore(kv, wrapper).relay(debugBuild = false))
        // A new Cloud mode setup starts without one.
        store.setRelay(relay)
        store.turnOn(config, "t2")
        assertNull(RemoteStore(kv, wrapper).relay(debugBuild = false))
        // Remote mode off (or the pairing forgotten).
        store.setRelay(relay)
        store.clear()
        assertNull(store.relay(debugBuild = false))
        assertTrue(kv.map.isEmpty())
    }

    /** A debug build's local relay isn't taken back by a release build. */
    @Test
    fun aStoredRelayIsCheckedAgain() {
        val store = RemoteStore(kv, wrapper)
        store.turnOn(config, "t")
        store.setRelay(RelayConfig("10.0.2.2", 47840, ByteArray(32), ByteArray(32), ByteArray(16)))
        assertNull(RemoteStore(kv, wrapper).relay(debugBuild = false))
        assertEquals("10.0.2.2", RemoteStore(kv, wrapper).relay(debugBuild = true)?.host)
    }
}
