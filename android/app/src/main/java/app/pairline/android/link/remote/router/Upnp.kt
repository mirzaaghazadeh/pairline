package app.pairline.android.link.remote.router

import app.pairline.android.link.wifi.LocalAddresses
import app.pairline.android.log.Log
import java.io.StringReader
import java.net.Inet4Address
import java.net.InetAddress
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * An http URL on the home network: an IPv4 literal (never a name, so nothing is looked up), a port and a path. The
 * only kind of URL UPnP may use; [Upnp.isOnNetwork] decides whether one may be fetched.
 */
data class LanUrl(val host: Inet4Address, val port: Int, val path: String) {
    val hostHeader: String get() = if (port == 80) Addresses.v4Text(host) else "${Addresses.v4Text(host)}:$port"

    /** [reference] (absolute, or relative such as "/ctl/IPConn" or "ctl") against this URL; null if malformed. */
    fun resolve(reference: String): LanUrl? {
        val ref = reference.trim()
        if (!isPlain(ref)) return null
        if ("://" in ref) return parse(ref)
        if (ref.startsWith("//")) return null // "another host, same scheme": refused
        val joined = if (ref.startsWith("/")) ref else path.substringBefore('?').substringBeforeLast('/') + "/" + ref
        return LanUrl(host, port, joined.substringBefore('#'))
    }

    companion object {
        private const val MAX_URL = 512

        /**
         * Only plain HTTP to an IPv4 literal, with an optional port and path; no user info, no names, no https (a
         * router's UPnP is plain HTTP).
         */
        fun parse(text: String): LanUrl? {
            val t = text.trim()
            val scheme = "http://"
            if (!isPlain(t) || !t.startsWith(scheme, ignoreCase = true)) return null
            val rest = t.substring(scheme.length)
            val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, end)
            if ('@' in authority) return null
            val host = Addresses.parseV4(authority.substringBefore(':')) ?: return null
            val port = if (':' !in authority) {
                80
            } else {
                authority.substringAfter(':').takeIf { p -> p.length in 1..5 && p.all { it in '0'..'9' } }
                    ?.toInt()?.takeIf { it in 1..65535 } ?: return null
            }
            val path = rest.substring(end).substringBefore('#').let { if (it.startsWith("/")) it else "/$it" }
            return LanUrl(host, port, path)
        }

        /** Printable ASCII without spaces, and not too long: nothing that could split an HTTP request line. */
        private fun isPlain(text: String): Boolean = text.isNotEmpty() && text.length <= MAX_URL && text.all { it in '!'..'~' }
    }
}

/** A WAN connection service of the router, and where its SOAP actions go. */
data class UpnpService(val type: String, val control: LanUrl)

/** UPnP IGD messages: SSDP search, the device description and the few SOAP actions remote mode uses. */
object Upnp {
    val IGD_TYPES = listOf(
        "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
        "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
    )

    /** In order of preference. */
    val WAN_SERVICES = listOf(
        "urn:schemas-upnp-org:service:WANIPConnection:2",
        "urn:schemas-upnp-org:service:WANIPConnection:1",
        "urn:schemas-upnp-org:service:WANPPPConnection:1",
    )

    const val ERROR_NO_SUCH_ENTRY = 714
    const val ERROR_CONFLICT = 718
    const val ERROR_ONLY_PERMANENT_LEASES = 725

    fun searchMessages(): List<ByteArray> = IGD_TYPES.map { st ->
        ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n")
            .toByteArray(Charsets.US_ASCII)
    }

    /** The LOCATION of an SSDP answer ("HTTP/1.1 200 OK" plus headers), or null. */
    fun location(reply: ByteArray): String? {
        if (reply.size > 2048) return null
        val lines = String(reply, Charsets.ISO_8859_1).split('\n').map { it.trimEnd('\r') }
        val status = lines.firstOrNull() ?: return null
        if (!status.startsWith("HTTP/1.1 200") && !status.startsWith("HTTP/1.0 200")) return null
        return lines.drop(1).firstOrNull { it.substringBefore(':').trim().equals("location", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Whether a UPnP URL may be fetched: its host is a private or link-local address on the same network as the
     * gateway. Anything else — the internet, another network, a name — is never contacted.
     */
    fun isOnNetwork(url: LanUrl, network: RouterNetwork): Boolean {
        val gateway = network.gatewayV4 ?: return false
        val prefix = network.prefixV4.takeIf { it in 8..32 } ?: 24
        return LocalAddresses.isLocal(url.host) && Addresses.sameSubnet(url.host, gateway, prefix)
    }

    /** The WAN connection services in a device description, best first. Their URLs still need [isOnNetwork]. */
    fun parseDescription(xml: String, location: LanUrl): List<UpnpService> {
        val root = SafeXml.parse(xml) ?: return emptyList()
        val base = root.child("URLBase")?.value?.takeIf { it.isNotEmpty() }?.let { LanUrl.parse(it) } ?: location
        return root.all("service").mapNotNull { service ->
            val type = service.child("serviceType")?.value?.takeIf { it in WAN_SERVICES } ?: return@mapNotNull null
            val control = service.child("controlURL")?.value?.let(base::resolve) ?: return@mapNotNull null
            UpnpService(type, control)
        }.distinct().sortedBy { WAN_SERVICES.indexOf(it.type) }.toList()
    }

    fun soapBody(service: String, action: String, args: List<Pair<String, String>>): ByteArray = buildString {
        append("<?xml version=\"1.0\"?>\r\n")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(escape(service)).append("\">")
        for ((name, value) in args) append('<').append(name).append('>').append(escape(value)).append("</").append(name).append('>')
        append("</u:").append(action).append("></s:Body></s:Envelope>\r\n")
    }.toByteArray(Charsets.UTF_8)

    fun soapHeaders(service: String, action: String): List<Pair<String, String>> = listOf(
        "Content-Type" to "text/xml; charset=\"utf-8\"",
        "SOAPAction" to "\"$service#$action\"",
    )

    sealed interface SoapResult {
        class Ok(val values: Map<String, String>) : SoapResult

        /** A UPnP error ([code] from `UPnPError`, null if the router didn't say). */
        class Fault(val code: Int?) : SoapResult
    }

    /** A SOAP reply: the response's arguments (`NewExternalIPAddress` …), or the UPnP error code. */
    fun parseSoapReply(reply: HttpReply): SoapResult {
        val root = SafeXml.parse(String(reply.body, Charsets.UTF_8))
        root?.all("UPnPError")?.firstOrNull()?.let { return SoapResult.Fault(it.child("errorCode")?.value?.toIntOrNull()) }
        if (reply.status != 200) return SoapResult.Fault(null)
        val response = root?.all("Body")?.firstOrNull()?.children?.firstOrNull() ?: return SoapResult.Ok(emptyMap())
        return SoapResult.Ok(response.children.associate { it.name to it.value })
    }

    private fun escape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}

/** Talks UPnP IGD to the home router. Each call blocks for a few seconds at most. */
class UpnpClient(private val io: RouterIo, private val network: RouterNetwork) {

    class Found(val service: UpnpService, val external: Inet4Address)

    /**
     * SSDP search on the Wi-Fi network, then the device descriptions at LAN addresses next to the gateway (the
     * gateway's own first), then the first WAN service that tells its external address.
     */
    fun discover(): Found? {
        val local = network.localV4 ?: return null
        val locations = io.ssdp(local, Upnp.searchMessages(), SSDP_WAIT_MS)
            .mapNotNull { Upnp.location(it.bytes) }
            .distinct()
            .mapNotNull { text -> LanUrl.parse(text).also { if (it == null) Log.d(TAG, "ignored an SSDP answer with an unusable LOCATION") } }
            .filter { url -> Upnp.isOnNetwork(url, network).also { if (!it) Log.w(TAG, "ignored an SSDP answer pointing off this network") } }
            .sortedBy { if (it.host == network.gatewayV4) 0 else 1 }
            .take(MAX_DESCRIPTIONS)
        for (location in locations) {
            val reply = io.http(location, "GET", emptyList(), null, MAX_DESCRIPTION_BYTES) ?: continue
            if (reply.status != 200) continue
            val services = Upnp.parseDescription(String(reply.body, Charsets.UTF_8), location)
                .filter { Upnp.isOnNetwork(it.control, network) }
            for (service in services) {
                externalAddress(service)?.let { return Found(service, it) }
            }
        }
        return null
    }

    fun externalAddress(service: UpnpService): Inet4Address? {
        val result = call(service, "GetExternalIPAddress", emptyList()) as? Upnp.SoapResult.Ok ?: return null
        return result.values["NewExternalIPAddress"]?.trim()?.let(Addresses::parseV4)
    }

    /** 0 when mapped; otherwise the UPnP error code, -1 for an error without one, null when nothing answered. */
    fun addPortMapping(service: UpnpService, externalPort: Int, internalPort: Int, client: Inet4Address, lease: Long): Int? =
        when (
            val r = call(
                service,
                "AddPortMapping",
                listOf(
                    "NewRemoteHost" to "",
                    "NewExternalPort" to externalPort.toString(),
                    "NewProtocol" to "TCP",
                    "NewInternalPort" to internalPort.toString(),
                    "NewInternalClient" to Addresses.v4Text(client),
                    "NewEnabled" to "1",
                    "NewPortMappingDescription" to DESCRIPTION,
                    "NewLeaseDuration" to lease.toString(),
                ),
            )
        ) {
            null -> null
            is Upnp.SoapResult.Ok -> 0
            is Upnp.SoapResult.Fault -> r.code ?: -1
        }

    /** True when deleted, or when the router no longer had it. */
    fun deletePortMapping(service: UpnpService, externalPort: Int): Boolean =
        when (
            val r = call(
                service,
                "DeletePortMapping",
                listOf("NewRemoteHost" to "", "NewExternalPort" to externalPort.toString(), "NewProtocol" to "TCP"),
            )
        ) {
            null -> false
            is Upnp.SoapResult.Ok -> true
            is Upnp.SoapResult.Fault -> r.code == Upnp.ERROR_NO_SUCH_ENTRY
        }

    private fun call(service: UpnpService, action: String, args: List<Pair<String, String>>): Upnp.SoapResult? {
        if (!Upnp.isOnNetwork(service.control, network)) return null
        val reply = io.http(
            service.control,
            "POST",
            Upnp.soapHeaders(service.type, action),
            Upnp.soapBody(service.type, action, args),
            MAX_SOAP_BYTES,
        ) ?: return null
        return Upnp.parseSoapReply(reply)
    }

    companion object {
        private const val TAG = "direct"
        const val SSDP_WAIT_MS = 2_500L
        const val MAX_DESCRIPTIONS = 3
        const val MAX_DESCRIPTION_BYTES = 64 * 1024
        const val MAX_SOAP_BYTES = 16 * 1024

        /** What the router's port-forwarding page shows for the mapping. */
        const val DESCRIPTION = "Pairline"
    }
}

/** A small element tree: names without prefixes, trimmed text. */
class XmlNode(val name: String) {
    val children = ArrayList<XmlNode>()
    internal val text = StringBuilder()

    val value: String get() = text.toString().trim()

    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }

    /** This node and every descendant called [name], in document order. */
    fun all(name: String): Sequence<XmlNode> = sequence {
        if (this@XmlNode.name == name) yield(this@XmlNode)
        for (c in children) yieldAll(c.all(name))
    }
}

/**
 * Reads XML from the router defensively, with the platform's SAX parser (no dependency): at most [MAX_CHARS], no
 * document type at all (so no entity can be declared: nothing external is fetched and nothing expands), at most
 * [MAX_NODES] elements nested [MAX_DEPTH] deep, and the text handed over as characters so the declared encoding can't
 * change what was checked. Null for anything else.
 */
object SafeXml {
    const val MAX_CHARS = 64 * 1024
    const val MAX_NODES = 2_000
    const val MAX_DEPTH = 32
    private const val MAX_TEXT = 2_048

    fun parse(text: String): XmlNode? {
        if (text.length > MAX_CHARS) return null
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) return null
        val handler = TreeHandler()
        return try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
            }
            factory.newSAXParser().parse(InputSource(StringReader(text)), handler)
            handler.root
        } catch (_: Exception) {
            null
        }
    }

    private class TreeHandler : DefaultHandler() {
        var root: XmlNode? = null
        private val stack = ArrayList<XmlNode>()
        private var count = 0

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            if (++count > MAX_NODES || stack.size >= MAX_DEPTH) throw SAXException("document too large")
            val node = XmlNode((qName ?: localName ?: "").substringAfterLast(':'))
            if (stack.isEmpty()) root = node else stack.last().children += node
            stack += node
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            val node = stack.lastOrNull() ?: return
            if (node.text.length + length <= MAX_TEXT) node.text.append(ch, start, length)
        }

        /** Never reached without a document type; refuses to read anything external all the same. */
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))
    }
}

/** HTTP/1.1 for the router's UPnP: one request per connection, replies by Content-Length, chunks or end of stream. */
object LanHttp {
    fun request(method: String, url: LanUrl, headers: List<Pair<String, String>>, body: ByteArray?): ByteArray {
        val head = buildString {
            append(method).append(' ').append(url.path).append(" HTTP/1.1\r\n")
            append("Host: ").append(url.hostHeader).append("\r\n")
            append("Connection: close\r\n")
            append("User-Agent: Pairline\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            if (body != null) append("Content-Length: ").append(body.size).append("\r\n")
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        return if (body == null) head else head + body
    }

    /** True once [bytes] (the first [size]) hold a whole reply, so a server that keeps the connection open isn't waited for. */
    fun isComplete(bytes: ByteArray, size: Int): Boolean {
        val end = headerEnd(bytes, size)
        if (end < 0) return false
        val headers = headers(bytes, end)
        headers["content-length"]?.toIntOrNull()?.let { return size - end >= it }
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return String(bytes, end, size - end, Charsets.ISO_8859_1).let { it.endsWith("\r\n0\r\n\r\n") || it == "0\r\n\r\n" }
        }
        return false
    }

    /** The status and body of a complete reply, or null if it's malformed or its body is larger than [maxBody]. */
    fun parse(bytes: ByteArray, maxBody: Int): HttpReply? {
        val end = headerEnd(bytes, bytes.size)
        if (end < 0) return null
        val statusLine = String(bytes, 0, minOf(end, 200), Charsets.ISO_8859_1).substringBefore("\r\n")
        val parts = statusLine.split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/1.")) return null
        val status = parts[1].takeIf { s -> s.length == 3 && s.all { it in '0'..'9' } }?.toInt() ?: return null
        val headers = headers(bytes, end)
        val rest = bytes.copyOfRange(end, bytes.size)
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> unchunk(rest, maxBody) ?: return null
            headers["content-length"] != null -> {
                val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 0..maxBody } ?: return null
                if (rest.size < length) return null
                rest.copyOf(length)
            }
            else -> rest
        }
        return if (body.size > maxBody) null else HttpReply(status, body)
    }

    /** Index just past the blank line that ends the headers, or -1. */
    private fun headerEnd(bytes: ByteArray, size: Int): Int {
        for (i in 3 until minOf(size, MAX_HEADER)) {
            if (bytes[i] == LF && bytes[i - 1] == CR && bytes[i - 2] == LF && bytes[i - 3] == CR) return i + 1
        }
        return -1
    }

    private fun headers(bytes: ByteArray, end: Int): Map<String, String> =
        String(bytes, 0, end, Charsets.ISO_8859_1).split("\r\n").drop(1)
            .filter { ':' in it }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }

    private fun unchunk(data: ByteArray, maxBody: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var pos = 0
        while (true) {
            val lineEnd = (pos until data.size - 1).firstOrNull { data[it] == CR && data[it + 1] == LF } ?: return null
            val sizeText = String(data, pos, lineEnd - pos, Charsets.ISO_8859_1).substringBefore(';').trim()
            val size = sizeText.takeIf { it.length in 1..6 }?.toIntOrNull(16) ?: return null
            pos = lineEnd + 2
            if (size == 0) return out.toByteArray()
            if (out.size() + size > maxBody || pos + size + 2 > data.size) return null
            out.write(data, pos, size)
            pos += size + 2
        }
    }

    private const val CR = '\r'.code.toByte()
    private const val LF = '\n'.code.toByte()
    private const val MAX_HEADER = 8 * 1024
}

/** For the log: a LAN address reduced to its last part. */
internal fun InetAddress.hint(): String = Addresses.lanHint(this)
