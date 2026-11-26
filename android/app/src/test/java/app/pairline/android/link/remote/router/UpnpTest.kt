package app.pairline.android.link.remote.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpnpTest {
    private val network = homeNetwork()
    private val location = LanUrl.parse("http://192.168.1.1:5000/rootDesc.xml")!!

    // --- SSDP ------------------------------------------------------------------------------------------------

    @Test
    fun searchAsksForBothIgdVersions() {
        val texts = Upnp.searchMessages().map { String(it) }
        assertEquals(2, texts.size)
        assertTrue(texts.all { it.startsWith("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n") })
        assertTrue(texts[0].contains("ST: urn:schemas-upnp-org:device:InternetGatewayDevice:2\r\n"))
        assertTrue(texts[1].contains("ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n"))
        assertTrue(texts.all { it.endsWith("\r\n\r\n") })
    }

    @Test
    fun ssdpLocation() {
        val reply = "HTTP/1.1 200 OK\r\nCache-Control: max-age=120\r\nlocation:  http://192.168.1.1:5000/rootDesc.xml \r\n\r\n"
        assertEquals("http://192.168.1.1:5000/rootDesc.xml", Upnp.location(reply.toByteArray()))
        assertNull(Upnp.location("HTTP/1.1 404 Not Found\r\nLOCATION: http://192.168.1.1/\r\n\r\n".toByteArray()))
        assertNull(Upnp.location("NOTIFY * HTTP/1.1\r\nLOCATION: http://192.168.1.1/\r\n\r\n".toByteArray()))
        assertNull(Upnp.location("HTTP/1.1 200 OK\r\nST: x\r\n\r\n".toByteArray()))
        assertNull(Upnp.location(ByteArray(3000) { 'A'.code.toByte() }))
    }

    // --- URLs ------------------------------------------------------------------------------------------------

    @Test
    fun lanUrlsAreIPv4LiteralsOverPlainHttp() {
        assertEquals(LanUrl(v4("192.168.1.1"), 5000, "/rootDesc.xml"), location)
        assertEquals(LanUrl(v4("192.168.1.1"), 80, "/"), LanUrl.parse("HTTP://192.168.1.1"))
        assertEquals("/a?b=1", LanUrl.parse("http://192.168.1.1/a?b=1#frag")!!.path)
        assertEquals("192.168.1.1:5000", location.hostHeader)
        listOf(
            "https://192.168.1.1/desc.xml",
            "http://router.local/desc.xml",
            "http://192.168.1.1@8.8.8.8/desc.xml",
            "http://user@192.168.1.1/desc.xml",
            "http://192.168.001.001/desc.xml",
            "http://192.168.1/desc.xml",
            "http://[fe80::1]/desc.xml",
            "http://192.168.1.1:0/",
            "http://192.168.1.1:65536/",
            "http://192.168.1.1:80x/",
            "http://192.168.1.1/a\r\nHost: evil",
            "http://192.168.1.1/a b",
            "file:///etc/passwd",
            "http://192.168.1.1/" + "a".repeat(600),
        ).forEach { assertNull(it, LanUrl.parse(it)) }
    }

    @Test
    fun relativeUrlsResolveOnTheSameHost() {
        assertEquals(LanUrl(v4("192.168.1.1"), 5000, "/ctl/IPConn"), location.resolve("/ctl/IPConn"))
        assertEquals(LanUrl(v4("192.168.1.1"), 5000, "/ctl"), location.resolve("ctl"))
        assertEquals(LanUrl(v4("192.168.1.1"), 49152, "/x"), location.resolve("http://192.168.1.1:49152/x"))
        assertNull(location.resolve("//8.8.8.8/x"))
        assertNull(location.resolve("/a\nb"))
    }

    @Test
    fun onlyLanAddressesNextToTheGatewayMayBeFetched() {
        assertTrue(Upnp.isOnNetwork(location, network))
        assertTrue(Upnp.isOnNetwork(LanUrl.parse("http://192.168.1.77:1900/x")!!, network))
        assertFalse(Upnp.isOnNetwork(LanUrl.parse("http://8.8.8.8/x")!!, network)) // the internet
        assertFalse(Upnp.isOnNetwork(LanUrl.parse("http://192.168.2.1/x")!!, network)) // another LAN
        assertFalse(Upnp.isOnNetwork(LanUrl.parse("http://10.0.0.1/x")!!, network))
        assertFalse(Upnp.isOnNetwork(LanUrl.parse("http://127.0.0.1/x")!!, network)) // this phone
        assertFalse(Upnp.isOnNetwork(location, network.copy(gatewayV4 = null)))
    }

    // --- Description -----------------------------------------------------------------------------------------

    @Test
    fun descriptionFindsTheNestedWanService() {
        val services = Upnp.parseDescription(FakeRouter.DESCRIPTION, location)
        assertEquals(
            listOf(UpnpService("urn:schemas-upnp-org:service:WANIPConnection:1", LanUrl(v4("192.168.1.1"), 5000, "/ctl/IPConn"))),
            services,
        )
    }

    @Test
    fun descriptionPrefersIpOverPppAndHonoursUrlBase() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0"><URLBase>http://192.168.1.1:49000/</URLBase><device>
              <serviceList>
                <service><serviceType>urn:schemas-upnp-org:service:WANPPPConnection:1</serviceType><controlURL>ppp</controlURL></service>
                <service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:2</serviceType><controlURL>/ip2</controlURL></service>
              </serviceList>
            </device></root>
        """.trimIndent()
        val services = Upnp.parseDescription(xml, location)
        assertEquals(listOf("/ip2", "/ppp"), services.map { it.control.path })
        assertEquals(49000, services[0].control.port)
    }

    @Test
    fun aControlUrlOffTheLanIsParsedButNeverAllowed() {
        val xml = FakeRouter.DESCRIPTION.replace("<controlURL>/ctl/IPConn</controlURL>", "<controlURL>http://8.8.8.8/ctl</controlURL>")
        val service = Upnp.parseDescription(xml, location).single()
        assertFalse(Upnp.isOnNetwork(service.control, network))
    }

    @Test
    fun maliciousXmlIsRefused() {
        val laughs = """<?xml version="1.0"?>
            <!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">]>
            <root><device><serviceList><service><serviceType>&lol2;</serviceType></service></serviceList></device></root>"""
        val xxe = """<?xml version="1.0"?><!DOCTYPE root [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <root><URLBase>&x;</URLBase></root>"""
        val lowercase = """<?xml version="1.0"?><!doctype root SYSTEM "http://8.8.8.8/x.dtd"><root/>"""
        val deep = "<a>".repeat(SafeXml.MAX_DEPTH + 1) + "</a>".repeat(SafeXml.MAX_DEPTH + 1)
        val wide = "<root>" + "<s/>".repeat(SafeXml.MAX_NODES + 1) + "</root>"
        val huge = "<root>" + " ".repeat(SafeXml.MAX_CHARS) + "</root>"
        for (xml in listOf(laughs, xxe, lowercase, deep, wide, huge, "<root><unclosed></root>", "not xml", "")) {
            assertNull(SafeXml.parse(xml))
            assertTrue(Upnp.parseDescription(xml, location).isEmpty())
        }
        assertNotNull(SafeXml.parse("<a>".repeat(10) + "</a>".repeat(10)))
    }

    @Test
    fun longTextIsCappedNotFatal() {
        val root = SafeXml.parse("<root><v>" + "x".repeat(5000) + "</v></root>")!!
        assertTrue(root.child("v")!!.value.length <= 2048)
    }

    // --- SOAP ------------------------------------------------------------------------------------------------

    @Test
    fun soapRequestBody() {
        val body = String(Upnp.soapBody("urn:schemas-upnp-org:service:WANIPConnection:1", "AddPortMapping", listOf("NewPortMappingDescription" to "a<b&c")))
        assertTrue(body.startsWith("<?xml version=\"1.0\"?>\r\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "))
        assertTrue(body.contains("<u:AddPortMapping xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:1\">"))
        assertTrue(body.contains("<NewPortMappingDescription>a&lt;b&amp;c</NewPortMappingDescription>"))
        assertTrue(body.contains("</u:AddPortMapping></s:Body></s:Envelope>"))
        assertEquals(
            listOf("Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPAction" to "\"urn:x#GetExternalIPAddress\""),
            Upnp.soapHeaders("urn:x", "GetExternalIPAddress"),
        )
    }

    @Test
    fun soapReplies() {
        val ok = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:GetExternalIPAddressResponse xmlns:u="urn:schemas-upnp-org:service:WANIPConnection:1">
            <NewExternalIPAddress> 81.2.69.160 </NewExternalIPAddress></u:GetExternalIPAddressResponse></s:Body></s:Envelope>"""
        val parsed = Upnp.parseSoapReply(HttpReply(200, ok.toByteArray())) as Upnp.SoapResult.Ok
        assertEquals("81.2.69.160", parsed.values["NewExternalIPAddress"])

        val fault = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><detail>
            <UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>725</errorCode>
            <errorDescription>OnlyPermanentLeasesSupported</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
        assertEquals(725, (Upnp.parseSoapReply(HttpReply(500, fault.toByteArray())) as Upnp.SoapResult.Fault).code)
        assertNull((Upnp.parseSoapReply(HttpReply(500, "oops".toByteArray())) as Upnp.SoapResult.Fault).code)
        // A 200 with an empty body (some routers answer AddPortMapping like that) is success.
        assertTrue(Upnp.parseSoapReply(HttpReply(200, ByteArray(0))) is Upnp.SoapResult.Ok)
    }

    // --- HTTP ------------------------------------------------------------------------------------------------

    @Test
    fun httpRequestLine() {
        val text = String(LanHttp.request("POST", location.resolve("/ctl/IPConn")!!, listOf("SOAPAction" to "\"a#b\""), "xy".toByteArray()))
        assertEquals(
            "POST /ctl/IPConn HTTP/1.1\r\nHost: 192.168.1.1:5000\r\nConnection: close\r\nUser-Agent: Pairline\r\n" +
                "SOAPAction: \"a#b\"\r\nContent-Length: 2\r\n\r\nxy",
            text,
        )
    }

    @Test
    fun httpReplies() {
        val plain = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nServer: x\r\n\r\nhello".toByteArray()
        assertTrue(LanHttp.isComplete(plain, plain.size))
        assertFalse(LanHttp.isComplete(plain, plain.size - 1))
        assertEquals("hello", String(LanHttp.parse(plain, 100)!!.body))
        assertNull(LanHttp.parse(plain, 4)) // over the limit

        val chunked = "HTTP/1.1 500 Internal Server Error\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n3;x=y\r\nabc\r\n0\r\n\r\n".toByteArray()
        assertTrue(LanHttp.isComplete(chunked, chunked.size))
        val reply = LanHttp.parse(chunked, 100)!!
        assertEquals(500, reply.status)
        assertEquals("helloabc", String(reply.body))
        assertNull(LanHttp.parse(chunked, 6))

        val untilClose = "HTTP/1.0 200 OK\r\n\r\nbody".toByteArray()
        assertFalse(LanHttp.isComplete(untilClose, untilClose.size))
        assertEquals("body", String(LanHttp.parse(untilClose, 100)!!.body))

        assertNull(LanHttp.parse("SSH-2.0-OpenSSH\r\n\r\n".toByteArray(), 100))
        assertNull(LanHttp.parse("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort".toByteArray(), 100))
        assertNull(LanHttp.parse("HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n".toByteArray(), 100))
        assertNull(LanHttp.parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n".toByteArray(), 100))
    }
}
