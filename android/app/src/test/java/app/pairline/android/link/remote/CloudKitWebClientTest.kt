package app.pairline.android.link.remote

import app.pairline.android.link.remote.CloudKitWebClient.MailboxAddress as Mailbox
import app.pairline.android.protocol.RemoteBackend as Backend
import app.pairline.android.protocol.RemoteEnvironment as Env
import java.net.URLDecoder
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloudKitWebClientTest {
    private class MemoryTokens(var token: String?) : CloudKitWebClient.TokenStore {
        override fun load() = token
        override fun save(token: String) { this.token = token }
    }

    private class FakeHttp(private val replies: MutableList<CloudKitWebClient.Response>) : CloudKitWebClient.Http {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<JSONObject>()
        override fun post(url: String, body: String): CloudKitWebClient.Response {
            urls += url
            bodies += JSONObject(body)
            return replies.removeAt(0)
        }
    }

    private fun ok(body: String = "{}", token: String? = "next+token/==__") =
        CloudKitWebClient.Response(200, token?.let { mapOf("X-Apple-CloudKit-Web-Auth-Token" to it) } ?: emptyMap(), body)

    private val toBridge = Mailbox(zone = "ToBridge", id = "00112233445566778899aabbccddeeff")
    private val alert = Mailbox(zone = "ToPhone", id = "ffeeddccbbaa99887766554433221100")

    @Test
    fun storesTheRefreshedTokenWithoutTheTrailingSeparators() {
        val tokens = MemoryTokens("first")
        val http = FakeHttp(mutableListOf(ok(), ok()))
        val client = CloudKitWebClient(Env.DEVELOPMENT, Backend.PUBLIC, tokens, http)
        client.put(alert, "aa".repeat(16), ByteArray(10))
        assertEquals("next+token/==", tokens.token)
        client.put(alert, "bb".repeat(16), ByteArray(10))
        // The second request carries the refreshed token, percent-encoded.
        val query = http.urls[1].substringAfter('?')
        val sent = query.split('&').first { it.startsWith("ckWebAuthToken=") }.substringAfter('=')
        assertEquals("next+token/==", URLDecoder.decode(sent, "UTF-8"))
        assertTrue(http.urls[1].startsWith("https://api.apple-cloudkit.com/database/1/iCloud.app.pairline.ios/development/public/records/modify?"))
    }

    @Test
    fun smallRecordsRideInSealedAndLargeOnesInPayload() {
        val http = FakeHttp(mutableListOf(ok(), ok()))
        val client = CloudKitWebClient(Env.DEVELOPMENT, Backend.PUBLIC, MemoryTokens("t"), http)
        client.put(alert, "aa".repeat(16), ByteArray(300) { it.toByte() })
        client.put(alert, "bb".repeat(16), ByteArray(4000))
        val small = http.bodies[0].getJSONArray("operations").getJSONObject(0).getJSONObject("record").getJSONObject("fields")
        val large = http.bodies[1].getJSONArray("operations").getJSONObject(0).getJSONObject("record").getJSONObject("fields")
        assertTrue(small.has("sealed") && !small.has("payload"))
        assertEquals(alert.id, small.getJSONObject("mailbox").getString("value"))
        assertTrue(large.has("payload") && !large.has("sealed"))
        assertFalse(http.bodies[0].has("zoneID"))
    }

    @Test
    fun thePrivateBackendUsesZonesAndNoMailboxField() {
        val http = FakeHttp(mutableListOf(ok()))
        val client = CloudKitWebClient(Env.DEVELOPMENT, Backend.PRIVATE, MemoryTokens("t"), http)
        client.put(alert, "aa".repeat(16), ByteArray(20))
        assertEquals("ToPhone", http.bodies[0].getJSONObject("zoneID").getString("zoneName"))
        val fields = http.bodies[0].getJSONArray("operations").getJSONObject(0).getJSONObject("record").getJSONObject("fields")
        assertFalse(fields.has("mailbox"))
        assertTrue(http.urls[0].contains("/development/private/records/modify?"))
    }

    @Test
    fun fetchReturnsReadableRecordsOldestFirst() {
        val sealedA = byteArrayOf(1, 2, 3)
        val sealedB = byteArrayOf(4, 5, 6)
        val reply = """{"records":[
            {"recordName":"b","created":{"timestamp":2000},"fields":{"sealed":{"value":"BAUG"}}},
            {"recordName":"a","created":{"timestamp":1000},"fields":{"payload":{"value":"AQID"}}},
            {"recordName":"bad","serverErrorCode":"NOT_FOUND"},
            {"recordName":"empty","fields":{}}]}"""
        val http = FakeHttp(mutableListOf(ok(reply)))
        val client = CloudKitWebClient(Env.DEVELOPMENT, Backend.PUBLIC, MemoryTokens("t"), http)
        val records = client.fetch(toBridge)
        assertEquals(listOf("a", "b"), records.map { it.name })
        assertArrayEquals(sealedA, records[0].sealed)
        assertArrayEquals(sealedB, records[1].sealed)
        val filter = http.bodies[0].getJSONObject("query").getJSONArray("filterBy").getJSONObject(0)
        assertEquals("mailbox", filter.getString("fieldName"))
        assertEquals(toBridge.id, filter.getJSONObject("fieldValue").getString("value"))
    }

    @Test
    fun mapsCloudKitErrors() {
        val cases = listOf(
            CloudKitWebClient.Response(421, emptyMap(), """{"serverErrorCode":"AUTHENTICATION_REQUIRED"}""") to
                CloudKitWebClient.TokenRejected::class.java,
            CloudKitWebClient.Response(401, emptyMap(), """{"serverErrorCode":"AUTHENTICATION_FAILED"}""") to
                CloudKitWebClient.TokenRejected::class.java,
            CloudKitWebClient.Response(403, emptyMap(),
                """{"serverErrorCode":"ACCESS_DENIED","reason":"private db access disabled for this account"}""") to
                CloudKitWebClient.PrivateDatabaseRefused::class.java,
            CloudKitWebClient.Response(503, emptyMap(), """{"serverErrorCode":"SERVICE_UNAVAILABLE"}""") to
                CloudKitWebClient.ServerError::class.java,
        )
        for ((response, expected) in cases) {
            val client = CloudKitWebClient(Env.DEVELOPMENT, Backend.PRIVATE, MemoryTokens("t"), FakeHttp(mutableListOf(response)))
            try {
                client.fetch(toBridge)
                fail("expected ${expected.simpleName}")
            } catch (e: Exception) {
                assertTrue("${e::class.java} for ${response.status}", expected.isInstance(e))
            }
        }
    }

    @Test
    fun noTokenMeansNoRequest() {
        val http = FakeHttp(mutableListOf())
        val client = CloudKitWebClient(Env.DEVELOPMENT, Backend.PUBLIC, MemoryTokens(null), http)
        try {
            client.fetch(toBridge)
            fail("expected TokenRejected")
        } catch (_: CloudKitWebClient.TokenRejected) {
        }
        assertTrue(http.urls.isEmpty())
    }

    @Test
    fun aFailedRecordInsideA200IsAnError() {
        val reply = """{"records":[{"recordName":"x","serverErrorCode":"QUOTA_EXCEEDED"}]}"""
        val client = CloudKitWebClient(Env.DEVELOPMENT, Backend.PUBLIC, MemoryTokens("t"), FakeHttp(mutableListOf(ok(reply))))
        try {
            client.put(alert, "aa".repeat(16), ByteArray(1))
            fail("expected ServerError")
        } catch (e: CloudKitWebClient.ServerError) {
            assertEquals("QUOTA_EXCEEDED", e.code)
        }
    }
}
