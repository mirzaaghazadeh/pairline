package app.pairline.android.link.remote

import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.Hex
import app.pairline.android.protocol.Mailbox
import app.pairline.android.protocol.MailboxDirection
import app.pairline.android.protocol.Messages
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.RemoteBackend
import app.pairline.android.protocol.RemoteEnvironment
import app.pairline.android.protocol.RemoteMailboxes
import app.pairline.android.protocol.RequestException
import app.pairline.android.protocol.SoftwareKey
import app.pairline.android.protocol.StaticKey
import java.security.SecureRandom
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteLinkTest {
    private val random = SecureRandom()
    private val bridge = SoftwareKey.generate(random)
    private val phone = SoftwareKey.generate(random)
    private var now = 1_764_000_000_000L
    private val cloud = FakeCloudKit()
    private val kv = MemoryKeyValueStore()
    private val store = RemoteStore(kv, TestWrapper())
    private val delivered = mutableListOf<JSONObject>()
    private val dispatcher = QueueDispatcher()
    private val link = RemoteLink(
        store,
        identity = { bridge },
        peerKey = { phone.publicKey },
        lastSeen = { 0L },
        deliver = { delivered += it },
        http = cloud,
        clock = { now },
        random = random,
        dispatcher = dispatcher,
    )
    private val mb = RemoteMailboxes("11".repeat(16), "22".repeat(16), "33".repeat(16))
    private val config = RemoteConfig(RemoteBackend.PUBLIC, RemoteEnvironment.DEVELOPMENT, mb)

    /** Running, with remote mode on; the test drives [RemoteLink.step] itself. */
    private fun on() {
        link.start()
        dispatcher.tasks.clear()
        link.configure(config, "first-token")
    }

    /** What the iPhone reads from one of its mailboxes. */
    private fun phoneReads(box: String): List<JSONObject> = cloud.take(box).map {
        val record = Mailbox.open(phone, bridge.publicKey, MailboxDirection.BRIDGE_TO_REMOTE, it.sealed)
        assertEquals(it.name, record.header.recordName)
        JSONObject(String(record.plaintext, Charsets.UTF_8))
    }

    private fun phoneSends(
        message: JSONObject,
        time: Long = now,
        from: StaticKey = phone,
        direction: MailboxDirection = MailboxDirection.REMOTE_TO_BRIDGE,
    ): String {
        val id = Mailbox.newRecordId(random)
        val sealed = Mailbox.seal(from, bridge.publicKey, direction, id, time, message.toString().toByteArray(), random)
        cloud.put(mb.toBridge, Hex.encode(id), sealed)
        return Hex.encode(id)
    }

    private fun status(level: Int, time: Long) = JSONObject().put("t", Msg.STATUS)
        .put("battery", JSONObject().put("level", level).put("charging", false)).put("time", time)

    private fun callState(state: String, reason: String?) = JSONObject().put("t", Msg.CALL_STATE).put("call", "c1")
        .put("state", state).put("dir", "in").apply { if (reason != null) put("reason", reason) }

    @Test
    fun eventsGoSealedToTheAlertOrQuietMailbox() {
        on()
        link.send(JSONObject().put("t", Msg.SMS_NEW).put("msg", JSONObject().put("body", "Your code is 4821")))
        link.send(JSONObject().put("t", Msg.CALL_INCOMING).put("call", "c1"))
        link.send(callState("ringing", null))
        link.send(callState("ended", "missed"))
        link.send(Messages.wifiOffer(null, null)) // only for a session
        link.step()
        assertEquals(listOf(Msg.SMS_NEW, Msg.CALL_INCOMING, Msg.CALL_STATE), phoneReads(mb.alert).map { it.getString("t") })
        assertEquals(listOf("ringing"), phoneReads(mb.quiet).map { it.getString("state") })
        // Every reply refreshed the token, which replaced the stored one.
        assertEquals(cloud.lastToken, store.load())
    }

    @Test
    fun statusGoesOnlyWhenItsContentChanged() {
        on()
        link.send(status(50, 1))
        link.send(status(50, 2))
        link.send(status(49, 3))
        link.step()
        assertEquals(listOf(50, 49), phoneReads(mb.quiet).map { it.getJSONObject("battery").getInt("level") })
        // A session coming and going changes nothing the iPhone doesn't know: the same status doesn't go again.
        link.onSessionChanged(true)
        link.onSessionChanged(false)
        link.send(status(49, 4))
        link.step()
        assertTrue(phoneReads(mb.quiet).isEmpty())
        // A session carried a newer status meanwhile: going back to the old one is a change, so it goes.
        link.onSessionChanged(true)
        link.statusDelivered(status(30, 5))
        link.onSessionChanged(false)
        link.send(status(49, 6))
        link.step()
        assertEquals(listOf(49), phoneReads(mb.quiet).map { it.getJSONObject("battery").getInt("level") })
    }

    /**
     * The loop seen on a real bridge: each status record's push woke the iPhone, which came back over Wi-Fi for a few
     * seconds and was frozen again; the session ending made the next unchanged status (a signal flicker) another
     * record about 90 s later, and so on. Now a short session that carried the same status costs nothing.
     */
    @Test
    fun shortSessionsFromPushWakeUpsDontMakeMoreRecords() {
        on()
        link.send(status(50, 1))
        link.step()
        assertEquals(1, phoneReads(mb.quiet).size)
        repeat(5) { i ->
            link.onSessionChanged(true)
            link.statusDelivered(status(50, 10L + i))
            link.onSessionChanged(false)
            now += 90_000
            link.send(status(50, 20L + i))
            link.step()
            assertTrue("round $i", phoneReads(mb.quiet).isEmpty())
        }
    }

    @Test
    fun requestsFromTheMailboxAreHandledOnceAndDeleted() {
        on()
        val request = JSONObject().put("t", Msg.STATUS_GET).put("id", 7)
        val name = phoneSends(request)
        val sealed = cloud.inBox(mb.toBridge).single().sealed
        link.step()
        assertEquals(Msg.STATUS_GET, delivered.single().getString("t"))
        assertEquals(7, delivered.single().getInt("id"))
        assertTrue(cloud.inBox(mb.toBridge).isEmpty())
        assertTrue(store.isHandled(name))

        // The same record again (a replay, or a delete that didn't arrive): dropped, deleted again.
        cloud.put(mb.toBridge, name, sealed)
        now += RemoteLink.SLOW_CHECK_MS
        link.step()
        assertEquals(1, delivered.size)
        assertTrue(cloud.inBox(mb.toBridge).isEmpty())
    }

    @Test
    fun staleForeignAndMalformedRecordsAreDroppedSilently() {
        on()
        val ping = JSONObject().put("t", Msg.PING).put("id", 1)
        phoneSends(ping, time = now - Mailbox.MAX_AGE_MS - 1)
        phoneSends(ping, time = now + Mailbox.MAX_AHEAD_MS + 1)
        phoneSends(ping, from = SoftwareKey.generate(random))
        phoneSends(ping, direction = MailboxDirection.BRIDGE_TO_REMOTE)
        val id = Mailbox.newRecordId(random)
        cloud.put(mb.toBridge, Hex.encode(id), Mailbox.sealInvite(ByteArray(32), MailboxDirection.REMOTE_TO_BRIDGE, id, now, ByteArray(2)))
        // Authentic, but stored under a name that isn't its record id.
        val other = Mailbox.newRecordId(random)
        val plaintext = ping.toString().toByteArray()
        val sealed = Mailbox.seal(phone, bridge.publicKey, MailboxDirection.REMOTE_TO_BRIDGE, other, now, plaintext, random)
        cloud.put(mb.toBridge, "ff".repeat(16), sealed)
        cloud.put(mb.toBridge, "ee".repeat(16), byteArrayOf(1, 2, 3))
        assertEquals(7, cloud.inBox(mb.toBridge).size)
        link.step()
        assertTrue(delivered.isEmpty())
        assertTrue(cloud.inBox(mb.toBridge).isEmpty())
    }

    @Test
    fun aRejectedTokenStopsCheckingUntilANewOneArrives() {
        on()
        cloud.failures += 401
        assertNull(link.step())
        assertEquals(RemoteStore.Problem.TOKEN_REJECTED, store.problem)
        assertEquals(RemoteStore.Problem.TOKEN_REJECTED, link.state.value.problem)
        val requests = cloud.requestCount()
        now += RemoteLink.SLOW_CHECK_MS
        assertNull(link.step())
        assertEquals(requests, cloud.requestCount())

        link.updateToken("fresh")
        assertEquals(RemoteLink.FAST_CHECK_MS, link.step())
        assertNull(store.problem)
        assertEquals(cloud.lastToken, store.load())
    }

    @Test
    fun aRefusedPrivateDatabaseStopsCheckingToo() {
        on()
        cloud.failures += 403
        assertNull(link.step())
        assertEquals(RemoteStore.Problem.PRIVATE_DATABASE_REFUSED, link.state.value.problem)
    }

    @Test
    fun serverAndNetworkErrorsBackOff() {
        on()
        cloud.failures += listOf(503, 503)
        assertEquals(30_000L, link.step())
        assertTrue(link.state.value.retrying)
        val requests = cloud.requestCount()
        now += 10_000
        assertEquals(20_000L, link.step()) // still waiting: no request
        assertEquals(requests, cloud.requestCount())
        now += 20_000
        assertEquals(60_000L, link.step())
        now += 60_000
        // 90 s after remote.config: still within the burst after hearing from the iPhone.
        assertEquals(RemoteLink.BURST_CHECK_MS, link.step())
        assertFalse(link.state.value.retrying)
    }

    @Test
    fun checksOftenRightAfterThePhoneThenEvery30SecondsThenEvery2Minutes() {
        on() // remote.config just came from the iPhone
        assertEquals(RemoteLink.BURST_CHECK_MS, link.step())
        now += RemoteLink.BURST_MS
        assertEquals(RemoteLink.FAST_CHECK_MS, link.step())
        now += RemoteLink.RECENT_MS
        assertEquals(RemoteLink.SLOW_CHECK_MS, link.step())
        phoneSends(JSONObject().put("t", Msg.PING).put("id", 2))
        now += RemoteLink.SLOW_CHECK_MS
        assertEquals(RemoteLink.BURST_CHECK_MS, link.step())
    }

    @Test
    fun noChecksWhileASessionIsUpButQueuedRecordsStillGo() {
        on()
        link.onSessionChanged(true)
        assertNull(link.step())
        assertTrue(cloud.requests.none { it == "records/query" })
        link.send(JSONObject().put("t", Msg.SMS_NEW))
        assertNull(link.step())
        assertEquals(1, phoneReads(mb.alert).size)
        // The session ended: check once right away.
        link.onSessionChanged(false)
        link.step()
        assertEquals("records/query", cloud.requests.last())
    }

    @Test
    fun smallStatusChangesGoAtMostEveryTenMinutes() {
        on()
        link.send(status(83, now))
        link.step()
        assertEquals(1, phoneReads(mb.quiet).size)
        // Only the battery percent changed: held back for now…
        link.send(status(81, now + 1000))
        link.step()
        assertTrue(phoneReads(mb.quiet).isEmpty())
        // …and sent once ten minutes have passed since the last one.
        now += RemoteLink.STATUS_DETAIL_MS
        link.send(status(81, now))
        link.step()
        assertEquals(1, phoneReads(mb.quiet).size)
        // A change that matters goes at once.
        link.send(status(69, now + 1000))
        link.step()
        assertEquals(1, phoneReads(mb.quiet).size)
    }

    @Test
    fun statusKeyIgnoresSignalBarsAndSmallBatteryChanges() {
        fun status(level: Int, signal: Int, net: String, charging: Boolean = false) = JSONObject(
            """{"t":"status","time":${now + level},"battery":{"level":$level,"charging":$charging},
               "sims":[{"slot":0,"carrier":"C","signal":$signal,"net":"$net","roaming":false}]}""",
        )
        val base = RemoteLink.statusKey(status(83, 4, "LTE"))
        assertEquals(base, RemoteLink.statusKey(status(81, 1, "LTE")))
        assertTrue(base != RemoteLink.statusKey(status(79, 4, "LTE")))
        assertTrue(base != RemoteLink.statusKey(status(83, 4, "3G")))
        assertTrue(base != RemoteLink.statusKey(status(83, 4, "LTE", charging = true)))
    }

    @Test
    fun ownRecordsAreDeletedAfterADay() {
        on()
        link.send(JSONObject().put("t", Msg.SMS_NEW))
        link.step()
        assertEquals(1, cloud.inBox(mb.alert).size)
        now += 25 * 3_600_000L
        link.step()
        assertTrue(cloud.inBox(mb.alert).isEmpty())
        assertTrue(store.allSent().values.all { it.isEmpty() })
    }

    @Test
    fun forgettingDeletesTheStateAndRecordsAfterTheFarewell() {
        on()
        link.send(JSONObject().put("t", Msg.SMS_NEW))
        link.step()
        assertTrue(link.isActive)
        link.forget(Messages.unpair())
        assertFalse(link.isActive)
        assertTrue(kv.map.isEmpty())
        dispatcher.runAll()
        assertTrue(cloud.inBox(mb.alert).isEmpty())
        assertEquals(listOf(Msg.UNPAIR), phoneReads(mb.quiet).map { it.getString("t") })
    }

    @Test
    fun activeOnlyWhileRunningAndOn() {
        assertFalse(link.isActive)
        link.start()
        assertFalse(link.isActive)
        link.configure(config, "t")
        assertTrue(link.isActive)
        link.stop()
        assertFalse(link.isActive)
        assertNull(link.step())
    }

    @Test
    fun slotsAndStatusKeys() {
        assertEquals(MailboxSlot.ALERT, RemoteLink.slotFor(JSONObject().put("t", Msg.SMS_NEW)))
        assertEquals(MailboxSlot.ALERT, RemoteLink.slotFor(callState("ended", "missed")))
        assertEquals(MailboxSlot.QUIET, RemoteLink.slotFor(callState("ended", "remote")))
        assertEquals(MailboxSlot.QUIET, RemoteLink.slotFor(JSONObject().put("t", Msg.SMS_STATE)))
        assertEquals(MailboxSlot.QUIET, RemoteLink.slotFor(Messages.ok(3)))
        assertNull(RemoteLink.slotFor(Messages.wifiMedia(4000)))
        assertNull(RemoteLink.slotFor(JSONObject()))
        assertEquals(RemoteLink.statusKey(status(10, 1)), RemoteLink.statusKey(status(10, 2)))
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L), (1..7).map(RemoteLink::backoff))
    }

    @Test
    fun remoteConfigIsChecked() {
        val good = JSONObject().put("t", Msg.REMOTE_CONFIG).put("id", 1).put("on", true).put("backend", "private")
            .put("env", "development").put("token", "abc").put("mb", JSONObject().put("b", mb.toBridge).put("a", mb.alert).put("q", mb.quiet))
        val on = RemoteConfigRequest.parse(good, allowPublic = false) as RemoteConfigRequest.On
        assertEquals(RemoteBackend.PRIVATE, on.config.backend)
        assertEquals(mb, on.config.mailboxes)
        assertEquals("abc", on.token)
        assertEquals(RemoteConfigRequest.Off, RemoteConfigRequest.parse(JSONObject().put("on", false), allowPublic = false))

        val bad = listOf(
            JSONObject(good.toString()).put("backend", "public"), // release builds
            JSONObject(good.toString()).put("env", "staging"),
            JSONObject(good.toString()).put("token", "has space"),
            JSONObject(good.toString()).put("mb", JSONObject().put("b", mb.toBridge).put("a", mb.toBridge).put("q", mb.quiet)),
            JSONObject(good.toString()).apply { remove("on") },
        )
        for (message in bad) {
            try {
                RemoteConfigRequest.parse(message, allowPublic = false)
                fail("accepted $message")
            } catch (e: RequestException) {
                assertEquals(ErrorCode.INVALID, e.code)
            }
        }
        assertTrue(RemoteConfigRequest.parse(JSONObject(good.toString()).put("backend", "public"), allowPublic = true) is RemoteConfigRequest.On)
    }
}
