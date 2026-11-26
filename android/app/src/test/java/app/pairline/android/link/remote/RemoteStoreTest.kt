package app.pairline.android.link.remote

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
        assertTrue(kv.map.isEmpty())
        assertNull(store.load())
    }
}
