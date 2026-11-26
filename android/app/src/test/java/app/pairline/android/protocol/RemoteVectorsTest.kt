package app.pairline.android.protocol

import app.pairline.android.protocol.MailboxDirection.BRIDGE_TO_REMOTE
import app.pairline.android.protocol.MailboxDirection.REMOTE_TO_BRIDGE
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/** Every value in protocol/test-vectors/v1-remote.json (remote mode, D-072), reproduced with JCA (AGENTS.md rule 8). */
class RemoteVectorsTest {
    private val v = TestVectors.remote
    private val invite = v.getJSONObject("invite")
    private val sB = TestVectors.key("sB")
    private val sR = TestVectors.key("sR")
    private val pubB = TestVectors.publicKey("sB")
    private val pubR = TestVectors.publicKey("sR")

    private fun JSONObject.hex(name: String): ByteArray = Hex.decode(getString(name))

    private fun JSONObject.utf8(name: String): ByteArray = getString(name).toByteArray(Charsets.UTF_8)

    /** A vector's fixed ephemeral key: the private scalar is in the JSON, its public half in the expected header. */
    private fun ephemeral(privateHex: String, sealed: ByteArray): SoftwareKey {
        val public = sealed.copyOfRange(Mailbox.BASE_HEADER_LENGTH, MailboxKind.SEALED.headerLength)
        return SoftwareKey(P256.privateKeyFromScalar(Hex.decode(privateHex)), P256.decodePublicKey(public))
    }

    @Test
    fun versionAndIdentityKeys() {
        assertEquals(Mailbox.VERSION, v.getInt("version"))
        assertEquals(v.getJSONObject("keys").getString("sB_public"), Hex.encode(pubB))
        assertEquals(v.getJSONObject("keys").getString("sR_public"), Hex.encode(pubR))
    }

    @Test
    fun mailboxBridgeToRemote() {
        val m = v.getJSONObject("mailbox_b_to_r")
        val ephemeralPublic = m.hex("ephemeral_public")
        val e = SoftwareKey(
            P256.privateKeyFromScalar(m.hex("ephemeral_private")),
            P256.decodePublicKey(ephemeralPublic),
        )
        assertArrayEquals(ephemeralPublic, e.publicKey)
        val recordId = m.hex("record_id")
        val time = m.getLong("time_ms")

        val header = MailboxHeader(MailboxKind.SEALED, BRIDGE_TO_REMOTE, recordId, time, ephemeralPublic)
        assertEquals(m.getString("header"), Hex.encode(header.encode()))
        assertEquals(m.getString("record_name"), header.recordName)

        // B's side: ECDH(e, sR) ‖ ECDH(sB, sR); R's side: ECDH(sR, e) ‖ ECDH(sR, sB).
        val ikm = e.agree(TestVectors.ecPublic("sR")) + sB.agree(TestVectors.ecPublic("sR"))
        assertEquals(m.getString("ikm"), Hex.encode(ikm))
        val ikmR = sR.agree(P256.decodePublicKey(ephemeralPublic)) + sR.agree(TestVectors.ecPublic("sB"))
        assertEquals(m.getString("ikm"), Hex.encode(ikmR))
        assertEquals(m.getString("salt"), Hex.encode(Sha256.digest(header.encode())))
        assertEquals(m.getString("key"), Hex.encode(Mailbox.recordKey(ikm, header.encode())))

        val plaintext = m.utf8("plaintext_utf8")
        val sealed = Mailbox.seal(sB, pubR, BRIDGE_TO_REMOTE, recordId, time, plaintext, e)
        assertEquals(m.getString("sealed"), Hex.encode(sealed))
        assertEquals(m.getString("sealed_b64u"), B64u.encode(sealed))

        val opened = Mailbox.open(sR, pubB, BRIDGE_TO_REMOTE, B64u.decodeOrNull(m.getString("sealed_b64u"))!!)
        assertArrayEquals(plaintext, opened.plaintext)
        assertEquals(MailboxKind.SEALED, opened.header.kind)
        assertEquals(BRIDGE_TO_REMOTE, opened.header.direction)
        assertEquals(m.getString("record_name"), opened.header.recordName)
        assertEquals(time, opened.header.timeMs)
        assertArrayEquals(ephemeralPublic, opened.header.ephemeralPublicKey)
    }

    @Test
    fun mailboxRemoteToBridge() {
        val m = v.getJSONObject("mailbox_r_to_b")
        val sealed = m.hex("sealed")
        val plaintext = m.utf8("plaintext_utf8")

        val opened = Mailbox.open(sB, pubR, REMOTE_TO_BRIDGE, sealed)
        assertArrayEquals(plaintext, opened.plaintext)
        assertEquals(MailboxKind.SEALED, opened.header.kind)
        assertEquals(REMOTE_TO_BRIDGE, opened.header.direction)
        assertEquals(m.getString("record_id"), opened.header.recordName)
        assertEquals(m.getLong("time_ms"), opened.header.timeMs)

        // R's sealing, reproduced.
        val e = ephemeral(m.getString("ephemeral_private"), sealed)
        val resealed = Mailbox.seal(sR, pubB, REMOTE_TO_BRIDGE, m.hex("record_id"), m.getLong("time_ms"), plaintext, e)
        assertEquals(m.getString("sealed"), Hex.encode(resealed))

        // B accepts only R's direction.
        assertThrows(ProtocolException::class.java) { Mailbox.open(sB, pubR, BRIDGE_TO_REMOTE, sealed) }
    }

    @Test
    fun mailboxMustFail() {
        val cases = v.getJSONArray("mailbox_must_fail")
        assertEquals(2, cases.length())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val sender = if (c.has("use_sender_public")) c.hex("use_sender_public") else pubB
            assertThrows(c.getString("why"), ProtocolException::class.java) {
                Mailbox.open(sR, sender, BRIDGE_TO_REMOTE, c.hex("sealed"))
            }
        }
    }

    @Test
    fun everyRecordGetsAFreshEphemeralKey() {
        val random = SecureRandom()
        val id = Mailbox.newRecordId(random)
        val text = "{\"t\":\"status\"}".toByteArray()
        val a = Mailbox.seal(sB, pubR, BRIDGE_TO_REMOTE, id, 1L, text, random)
        val b = Mailbox.seal(sB, pubR, BRIDGE_TO_REMOTE, id, 1L, text, random)
        assertFalse(a.contentEquals(b))
        assertArrayEquals(text, Mailbox.open(sR, pubB, BRIDGE_TO_REMOTE, a).plaintext)
        assertArrayEquals(text, Mailbox.open(sR, pubB, BRIDGE_TO_REMOTE, b).plaintext)
        // A record B sealed to R doesn't open on B.
        assertThrows(ProtocolException::class.java) { Mailbox.open(sB, pubR, BRIDGE_TO_REMOTE, a) }
    }

    @Test
    fun headerParsingIsStrict() {
        val sealed = v.getJSONObject("mailbox_b_to_r").hex("sealed")
        fun changed(offset: Int, value: Int) = sealed.copyOf().also { it[offset] = value.toByte() }
        assertNotNull(MailboxHeader.parse(sealed))
        val bad = listOf(
            changed(0, 0x00), // version 0
            changed(0, 0x02), // version 2
            changed(1, 0x00), // kind 0
            changed(1, 0x03), // kind 3
            changed(2, 0x41), // direction 'A'
            changed(19, 0x80), // time ≥ 2⁶³
            changed(27, 0x02), // ephemeral key not an uncompressed point
            changed(91, sealed[91].toInt() xor 1), // ephemeral key off the curve
            sealed.copyOf(MailboxKind.SEALED.headerLength + Proto.TAG_LENGTH - 1), // no room for the tag
            sealed.copyOf(Mailbox.BASE_HEADER_LENGTH - 1),
            ByteArray(0),
        )
        for (b in bad) assertThrows(ProtocolException::class.java) { MailboxHeader.parse(b) }

        // A kind 1 record relabeled as kind 2 parses as a header, but opens as neither kind.
        val relabeled = changed(1, 0x02)
        assertEquals(MailboxKind.INVITE, MailboxHeader.parse(relabeled).kind)
        assertThrows(ProtocolException::class.java) { Mailbox.open(sR, pubB, BRIDGE_TO_REMOTE, relabeled) }
        assertThrows(ProtocolException::class.java) {
            Mailbox.openInvite(invite.hex("invite_secret"), BRIDGE_TO_REMOTE, relabeled)
        }

        // At most 48 KB of plaintext.
        val head = MailboxHeader(MailboxKind.INVITE, BRIDGE_TO_REMOTE, ByteArray(16), 0).encode()
        assertNotNull(MailboxHeader.parse(head + ByteArray(Mailbox.MAX_PLAINTEXT + Proto.TAG_LENGTH)))
        assertThrows(ProtocolException::class.java) {
            MailboxHeader.parse(head + ByteArray(Mailbox.MAX_PLAINTEXT + Proto.TAG_LENGTH + 1))
        }
    }

    @Test
    fun receiverClockRule() {
        val t = 1_795_700_000_000L
        val header = MailboxHeader(MailboxKind.INVITE, REMOTE_TO_BRIDGE, ByteArray(16), t)
        assertTrue(header.isTimely(t))
        assertTrue(header.isTimely(t + Mailbox.MAX_AGE_MS))
        assertFalse(header.isTimely(t + Mailbox.MAX_AGE_MS + 1))
        assertTrue(header.isTimely(t - Mailbox.MAX_AHEAD_MS))
        assertFalse(header.isTimely(t - Mailbox.MAX_AHEAD_MS - 1))
    }

    @Test
    fun inviteKeysCommitAndCode() {
        val secret = invite.hex("invite_secret")
        val (kBR, kRB) = Mailbox.inviteKeys(secret)
        assertEquals(invite.getString("k_br"), Hex.encode(kBR))
        assertEquals(invite.getString("k_rb"), Hex.encode(kRB))
        assertEquals(
            invite.getString("commit"),
            Hex.encode(RemotePairing.commit(pubR, pubB, invite.hex("nonce_b"))),
        )
        assertEquals(
            invite.getString("code"),
            RemotePairing.code(secret, pubR, pubB, invite.hex("nonce_r"), invite.hex("nonce_b")),
        )
    }

    @Test
    fun inviteRecords() {
        val secret = invite.hex("invite_secret")
        // gen_vectors_remote.py seals them 0, 2 and 4 s after mailbox_b_to_r's time; the JSON has those times only
        // inside the sealed headers.
        val t0 = v.getJSONObject("mailbox_b_to_r").getLong("time_ms")
        val cases = listOf(
            Triple("hello", BRIDGE_TO_REMOTE, t0),
            Triple("nonce", REMOTE_TO_BRIDGE, t0 + 2000),
            Triple("reveal", BRIDGE_TO_REMOTE, t0 + 4000),
        )
        for ((name, direction, time) in cases) {
            val json = invite.utf8("${name}_json")
            val sealed = Mailbox.sealInvite(secret, direction, invite.hex("${name}_record_id"), time, json)
            assertEquals(name, invite.getString("${name}_sealed"), Hex.encode(sealed))

            val opened = Mailbox.openInvite(secret, direction, invite.hex("${name}_sealed"))
            assertArrayEquals(name, json, opened.plaintext)
            assertEquals(MailboxKind.INVITE, opened.header.kind)
            assertEquals(direction, opened.header.direction)
            assertEquals(invite.getString("${name}_record_id"), opened.header.recordName)
            assertEquals(time, opened.header.timeMs)

            val other = if (direction == BRIDGE_TO_REMOTE) REMOTE_TO_BRIDGE else BRIDGE_TO_REMOTE
            assertThrows(ProtocolException::class.java) { Mailbox.openInvite(secret, other, sealed) }
            assertThrows(ProtocolException::class.java) { Mailbox.openInvite(ByteArray(32), direction, sealed) }
            assertThrows(ProtocolException::class.java) { Mailbox.open(sB, pubR, direction, sealed) }
            val flipped = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            assertThrows(ProtocolException::class.java) { Mailbox.openInvite(secret, direction, flipped) }
        }

        // The messages carry the vector's values.
        val hello = JSONObject(invite.getString("hello_json"))
        assertEquals("rpair.hello", hello.getString("t"))
        assertEquals(B64u.encode(pubB), hello.getString("k"))
        assertEquals(B64u.encode(invite.hex("device_id")), hello.getString("id"))
        assertEquals(B64u.encode(invite.hex("commit")), hello.getString("c"))
        assertEquals(B64u.encode(invite.hex("nonce_r")), JSONObject(invite.getString("nonce_json")).getString("n"))
        assertEquals(B64u.encode(invite.hex("nonce_b")), JSONObject(invite.getString("reveal_json")).getString("n"))
    }

    @Test
    fun confirmRecord() {
        val expected = invite.hex("confirm_sealed")
        // PROTOCOL.md "Remote pairing" step 7; the JSON has no plaintext field for it.
        val plaintext = "{\"t\":\"rpair.confirm\"}".toByteArray(Charsets.UTF_8)
        val e = ephemeral(invite.getString("confirm_ephemeral_private"), expected)
        val time = invite.getLong("confirm_time_ms")
        val sealed = Mailbox.seal(sB, pubR, BRIDGE_TO_REMOTE, invite.hex("confirm_record_id"), time, plaintext, e)
        assertEquals(invite.getString("confirm_sealed"), Hex.encode(sealed))

        val opened = Mailbox.open(sR, pubB, BRIDGE_TO_REMOTE, expected)
        assertArrayEquals(plaintext, opened.plaintext)
        assertEquals(invite.getString("confirm_record_id"), opened.header.recordName)
        assertEquals(time, opened.header.timeMs)
    }

    @Test
    fun invitationUri() {
        val parsed = RemoteInvitation.parse(invite.getString("uri"))
        assertNotNull(parsed)
        val inv = parsed!!
        assertArrayEquals(pubR, inv.remotePublicKey)
        assertArrayEquals(invite.hex("invite_secret"), inv.inviteSecret)
        assertEquals(RemoteBackend.PUBLIC, inv.backend)
        assertEquals(RemoteEnvironment.DEVELOPMENT, inv.environment)
        assertEquals(invite.getString("token"), inv.token)
        val mb = invite.getJSONObject("mailboxes")
        assertEquals(RemoteMailboxes(mb.getString("b"), mb.getString("a"), mb.getString("q")), inv.mailboxes)
        assertEquals(invite.getLong("expiry"), inv.expiresAt)

        assertEquals(inv, RemoteInvitation.parse(invite.getString("uri")))
        assertFalse(inv.toString().contains(inv.token))
        assertFalse(inv.toString().contains(B64u.encode(inv.inviteSecret)))
    }

    @Test
    fun invitationUriRejectsMalformed() {
        val uri = invite.getString("uri")
        val mb = invite.getJSONObject("mailboxes")
        // The link with one parameter replaced, or dropped when value is null.
        fun param(name: String, value: String?): String {
            val params = uri.removePrefix(RemoteInvitation.PREFIX).split('&')
                .map { it.substringBefore('=') to it.substringAfter('=') }
            val changed = if (value == null) {
                params.filter { it.first != name }
            } else {
                params.map { if (it.first == name) name to value else it }
            }
            return RemoteInvitation.PREFIX + changed.joinToString("&") { "${it.first}=${it.second}" }
        }

        assertEquals(RemoteBackend.PRIVATE, RemoteInvitation.parse(param("b", "private"))?.backend)
        assertEquals(RemoteEnvironment.PRODUCTION, RemoteInvitation.parse(param("e", "production"))?.environment)
        assertEquals("a+b", RemoteInvitation.parse(param("t", "a+b"))?.token) // a raw '+' is a plus, not a space
        assertEquals("a+b", RemoteInvitation.parse(param("t", "a%2bb"))?.token)
        assertNotNull(RemoteInvitation.parse("$uri&z=1")) // unknown parameters are ignored

        val offCurve = pubR.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        val standardBase64 = Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 0xfb.toByte() })
        val bad = listOf(
            param("v", "0"), param("v", "2"), param("v", null),
            param("k", null), param("k", B64u.encode(pubR.copyOf(64))), param("k", B64u.encode(offCurve)),
            param("k", B64u.encode(ByteArray(65))), param("k", B64u.encode(pubR) + "="),
            param("s", null), param("s", B64u.encode(ByteArray(31))), param("s", B64u.encode(ByteArray(33))),
            param("s", standardBase64), param("s", B64u.encode(ByteArray(32)) + "%3D"),
            param("b", null), param("b", "shared"), param("b", "Public"),
            param("e", null), param("e", "sandbox"), param("e", "Production"),
            param("t", null), param("t", ""), param("t", "abc%2"), param("t", "abc%zz"), param("t", "a%20b"),
            param("t", "a%0Ab"), param("t", "a%C3%A9"), param("t", "aé"),
            param("m", null), param("m", mb.getString("b").uppercase()), param("m", mb.getString("b").dropLast(1)),
            param("m", "g" + mb.getString("b").drop(1)), param("m", mb.getString("a")),
            param("a", null), param("q", null), param("q", mb.getString("b")),
            param("x", null), param("x", ""), param("x", "-5"), param("x", "+5"), param("x", "17957006OO"),
            param("x", "1".repeat(13)),
            "$uri&v=1", "$uri&", "$uri&=1", "$uri&x",
            uri.replace("pairline://invite?", "pairline://pair?"),
            uri.replace("pairline://invite?", "PAIRLINE://invite?"),
            "https://example.com/invite?v=1", "",
        )
        for (u in bad) assertNull(u, RemoteInvitation.parse(u))
    }
}
