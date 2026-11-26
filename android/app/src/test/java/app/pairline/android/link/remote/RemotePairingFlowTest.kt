package app.pairline.android.link.remote

import app.pairline.android.link.remote.RemotePairingFlow.Failure
import app.pairline.android.link.remote.RemotePairingFlow.Refusal
import app.pairline.android.link.remote.RemotePairingFlow.State
import app.pairline.android.protocol.B64u
import app.pairline.android.protocol.Hex
import app.pairline.android.protocol.Mailbox
import app.pairline.android.protocol.MailboxDirection
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.RemoteInvitation
import app.pairline.android.protocol.RemotePairing
import app.pairline.android.protocol.SoftwareKey
import app.pairline.android.protocol.StaticKey
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemotePairingFlowTest {
    private val random = SecureRandom()
    private val bridge = SoftwareKey.generate(random)
    private val phone = SoftwareKey.generate(random)
    private val deviceId = ByteArray(8) { (it + 1).toByte() }
    private val secret = ByteArray(32).also(random::nextBytes)
    private val clock = AtomicLong(1_764_000_000_000L)
    private val toBridge = "11".repeat(16)
    private val alert = "22".repeat(16)
    private val quiet = "33".repeat(16)
    private val cloud = FakeCloudKit()

    private class TestHost(private val key: StaticKey, private val id: ByteArray) : RemotePairingFlow.Host {
        @Volatile var pinned: ByteArray? = null
        @Volatile var completed: Pair<RemoteConfig, String>? = null
        @Volatile var abandoned = false
        override fun identity() = key
        override fun deviceId() = id
        override fun pin(remoteKey: ByteArray) { pinned = remoteKey }
        override fun complete(config: RemoteConfig, token: String) { completed = config to token }
        override fun abandon() {
            abandoned = true
            pinned = null
        }
    }

    private val host = TestHost(bridge, deviceId)
    private var flow = flow(debugBuild = true)

    private fun flow(debugBuild: Boolean) =
        RemotePairingFlow(host, cloud, debugBuild, { clock.get() }, random, pollMs = 5, dispatcher = Dispatchers.IO)

    @After
    fun tearDown() = flow.cancel()

    private fun link(
        backend: String = "public",
        expiresAt: Long = clock.get() / 1000 + 600,
        key: ByteArray = phone.publicKey,
        environment: String = "development",
    ) = "pairline://invite?v=1&k=${B64u.encode(key)}&s=${B64u.encode(secret)}&b=$backend&e=$environment" +
        "&t=tok%2Bx&m=$toBridge&a=$alert&q=$quiet&x=$expiresAt"

    private fun <T> eventually(probe: () -> T?): T {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            probe()?.let { return it }
            Thread.sleep(5)
        }
        fail("timed out; state ${flow.state.value}")
        throw AssertionError()
    }

    /** The iPhone reads the next invitation record the bridge wrote into the alert mailbox. */
    private fun phoneReadsInvite(): JSONObject = eventually {
        cloud.take(alert).firstOrNull()?.let {
            JSONObject(String(Mailbox.openInvite(secret, MailboxDirection.BRIDGE_TO_REMOTE, it.sealed).plaintext))
        }
    }

    private fun phoneSendsInvite(message: JSONObject) {
        val id = Mailbox.newRecordId(random)
        cloud.put(toBridge, Hex.encode(id), Mailbox.sealInvite(secret, MailboxDirection.REMOTE_TO_BRIDGE, id, clock.get(), message.toString().toByteArray()))
    }

    private fun phoneConfirms(from: StaticKey = phone) {
        val id = Mailbox.newRecordId(random)
        val plaintext = JSONObject().put("t", Msg.RPAIR_CONFIRM).toString().toByteArray()
        cloud.put(toBridge, Hex.encode(id), Mailbox.seal(from, bridge.publicKey, MailboxDirection.REMOTE_TO_BRIDGE, id, clock.get(), plaintext, random))
    }

    private fun nonceMessage(nonce: ByteArray) = JSONObject().put("t", Msg.RPAIR_NONCE).put("n", B64u.encode(nonce))

    /** Runs the iPhone's side up to the code: returns the code the iPhone shows. */
    private fun upToTheCode(): String {
        assertTrue(flow.open(link()))
        flow.allow()
        val hello = phoneReadsInvite()
        assertEquals(Msg.RPAIR_HELLO, hello.getString("t"))
        assertArrayEquals(bridge.publicKey, B64u.decodeOrNull(hello.getString("k")))
        assertArrayEquals(deviceId, B64u.decodeOrNull(hello.getString("id")))
        val nonceR = ByteArray(32).also(random::nextBytes)
        phoneSendsInvite(nonceMessage(nonceR))
        val reveal = phoneReadsInvite()
        assertEquals(Msg.RPAIR_REVEAL, reveal.getString("t"))
        val nonceB = B64u.decodeOrNull(reveal.getString("n"))!!
        // The commitment made before the bridge saw nonceR opens to the revealed nonce.
        assertArrayEquals(
            RemotePairing.commit(phone.publicKey, bridge.publicKey, nonceB),
            B64u.decodeOrNull(hello.getString("c")),
        )
        return RemotePairing.code(secret, phone.publicKey, bridge.publicKey, nonceR, nonceB)
    }

    @Test
    fun pairsWithTheInvitingIphone() {
        val code = upToTheCode()
        assertEquals(code, eventually { (flow.state.value as? State.Comparing)?.code })
        // The iPhone's user confirmed first; this phone's user confirms after.
        phoneConfirms()
        assertNull(host.pinned)
        flow.match()
        eventually { flow.state.value.takeIf { it == State.Paired } }
        assertArrayEquals(phone.publicKey, host.pinned)
        val (config, token) = host.completed!!
        assertEquals(RemoteConfig.of(RemoteInvitation.parse(link())!!), config)
        assertEquals(cloud.lastToken, token)
        // The bridge's own confirm, sealed with its identity key, woke the iPhone.
        val confirm = cloud.take(alert).single()
        val opened = Mailbox.open(phone, bridge.publicKey, MailboxDirection.BRIDGE_TO_REMOTE, confirm.sealed)
        assertEquals(Msg.RPAIR_CONFIRM, JSONObject(String(opened.plaintext)).getString("t"))
        // Handled records are gone.
        assertTrue(cloud.inBox(toBridge).isEmpty())
        assertFalse(host.abandoned)
    }

    @Test
    fun nothingIsSentBeforeAllow() {
        assertTrue(flow.open(link()))
        assertTrue(flow.state.value is State.Asking)
        Thread.sleep(50)
        assertEquals(0, cloud.requestCount())
        flow.cancel()
        assertEquals(State.Idle, flow.state.value)
        assertEquals(0, cloud.requestCount())
    }

    @Test
    fun refusesBadInvitations() {
        flow.open("pairline://invite?v=1&k=nope")
        assertEquals(State.Refused(Refusal.MALFORMED), flow.state.value)
        flow.open(link(expiresAt = clock.get() / 1000))
        assertEquals(State.Refused(Refusal.EXPIRED), flow.state.value)
        // A release build: the public database only in Production, which is what a TestFlight iPhone invites with.
        flow = flow(debugBuild = false)
        flow.open(link(backend = "public"))
        assertEquals(State.Refused(Refusal.UNSUPPORTED), flow.state.value)
        flow.open(link(backend = "private"))
        assertTrue(flow.state.value is State.Asking)
        flow.cancel()
        flow = flow(debugBuild = false)
        flow.open(link(backend = "public", environment = "production"))
        assertTrue(flow.state.value is State.Asking)
    }

    @Test
    fun givesUpWhenTheInvitationExpiresBeforeTheIphoneAnswers() {
        flow.open(link())
        flow.allow()
        phoneReadsInvite()
        clock.addAndGet(601_000)
        assertEquals(Failure.EXPIRED, eventually { (flow.state.value as? State.Failed)?.reason })
        assertNull(host.pinned)
    }

    @Test
    fun givesUpTenMinutesAfterTheCodeAppeared() {
        upToTheCode()
        eventually { flow.state.value as? State.Comparing }
        clock.addAndGet(RemotePairingFlow.CODE_TTL_MS)
        assertEquals(Failure.TIMED_OUT, eventually { (flow.state.value as? State.Failed)?.reason })
    }

    @Test
    fun cancellingAfterTheMatchForgetsTheIphoneAgain() {
        upToTheCode()
        eventually { flow.state.value as? State.Comparing }
        flow.match()
        eventually { flow.state.value as? State.Confirming }
        assertArrayEquals(phone.publicKey, host.pinned)
        flow.cancel()
        assertTrue(host.abandoned)
        assertNull(host.completed)
        assertEquals(State.Idle, flow.state.value)
    }

    @Test
    fun onlyTheInvitingIphonesConfirmCounts() {
        upToTheCode()
        eventually { flow.state.value as? State.Comparing }
        phoneConfirms(from = SoftwareKey.generate(random))
        flow.match()
        eventually { flow.state.value as? State.Confirming }
        Thread.sleep(50)
        assertTrue(flow.state.value is State.Confirming)
        phoneConfirms()
        eventually { flow.state.value.takeIf { it == State.Paired } }
    }

    @Test
    fun aSecondDifferentNonceStopsThePairing() {
        flow.open(link())
        flow.allow()
        phoneReadsInvite()
        phoneSendsInvite(nonceMessage(ByteArray(32) { 1 }))
        phoneSendsInvite(nonceMessage(ByteArray(32) { 2 }))
        assertEquals(Failure.BAD_ANSWER, eventually { (flow.state.value as? State.Failed)?.reason })
    }

    @Test
    fun fieldsAreCheckedExactly() {
        val invitation = RemoteInvitation.parse(link())!!
        val bad = listOf(
            JSONObject().put("t", Msg.RPAIR_NONCE).put("n", B64u.encode(ByteArray(31))),
            JSONObject().put("t", Msg.RPAIR_NONCE).put("n", B64u.encode(ByteArray(32)) + "="),
            JSONObject().put("t", Msg.RPAIR_NONCE).put("n", "not base64!"),
            JSONObject().put("t", Msg.RPAIR_NONCE),
        )
        for (message in bad) {
            val steps = RemotePairingSteps(invitation, bridge, deviceId, random)
            val id = Mailbox.newRecordId(random)
            val sealed = Mailbox.sealInvite(secret, MailboxDirection.REMOTE_TO_BRIDGE, id, clock.get(), message.toString().toByteArray())
            try {
                steps.receive(Hex.encode(id), sealed, clock.get())
                fail("accepted $message")
            } catch (_: RemotePairingSteps.BadAnswer) {
            }
        }
        // A record that isn't this pairing's (another invitation's secret) is left alone.
        val steps = RemotePairingSteps(invitation, bridge, deviceId, random)
        val id = Mailbox.newRecordId(random)
        val foreign = Mailbox.sealInvite(ByteArray(32), MailboxDirection.REMOTE_TO_BRIDGE, id, clock.get(), nonceMessage(ByteArray(32)).toString().toByteArray())
        assertFalse(steps.receive(Hex.encode(id), foreign, clock.get()))
        assertNull(steps.remoteNonce)
    }
}
