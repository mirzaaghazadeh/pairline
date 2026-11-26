package app.pairline.android.link.remote.router

import app.pairline.android.protocol.BigEndian
import app.pairline.android.protocol.Hex
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal fun v4(text: String): Inet4Address = InetAddress.getByName(text) as Inet4Address
internal fun v6(text: String): Inet6Address = InetAddress.getByName(text) as Inet6Address

/** A home network: router 192.168.1.1, this phone 192.168.1.23/24, and optionally a global IPv6 address. */
internal fun homeNetwork(withV6: Boolean = false) = RouterNetwork(
    gatewayV4 = v4("192.168.1.1"),
    localV4 = v4("192.168.1.23"),
    prefixV4 = 24,
    gatewayV6 = if (withV6) v6("fe80::1") else null,
    globalV6 = if (withV6) v6("2a02:1234:5678:9abc::23") else null,
)

/**
 * A home router in memory: NAT-PMP and PCP on "UDP 5351", UPnP IGD over "SSDP" and "HTTP". Each protocol can be
 * switched on or off; it records what it was asked and which mappings it holds. Time only moves when a receive waits.
 */
internal class FakeRouter : RouterIo {
    var time = 1_764_000_000_000L
    var natPmp = true
    var pcp = false
    var upnp = false
    var external = "81.2.69.160"
    var grantedLifetime: Long? = null
    var upnpPermanentOnly = false
    var upnpConflictOn: Int? = null

    /** Extra SSDP answers (for example one pointing off the LAN). */
    val extraSsdp = ArrayList<SsdpReply>()
    var description: String = DESCRIPTION

    val waits = ArrayList<Long>()
    val datagrams = ArrayList<ByteArray>()
    val udpFrom = ArrayList<Pair<InetAddress, InetAddress>>()
    val httpUrls = ArrayList<LanUrl>()
    val soapActions = ArrayList<String>()
    val soapBodies = ArrayList<String>()

    /** internal port → (external port, lifetime) */
    val natMappings = HashMap<Int, Pair<Int, Long>>()

    /** nonce hex → (internal port, lifetime) */
    val pcpMappings = HashMap<String, Pair<Int, Long>>()

    /** external port → lease */
    val upnpMappings = HashMap<Int, Long>()

    override fun now(): Long = time

    override fun udp(local: InetAddress, gateway: InetAddress): RouterUdp {
        udpFrom += local to gateway
        return object : RouterUdp {
            val queue = ArrayDeque<ByteArray>()

            override fun send(datagram: ByteArray) {
                datagrams += datagram
                answer(local, datagram)?.let(queue::addLast)
            }

            override fun receive(timeoutMs: Long): ByteArray? {
                waits += timeoutMs
                queue.removeFirstOrNull()?.let { return it }
                time += timeoutMs
                return null
            }
        }
    }

    override fun ssdp(local: Inet4Address, messages: List<ByteArray>, waitMs: Long): List<SsdpReply> {
        val out = ArrayList(extraSsdp)
        if (upnp) {
            val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n" +
                "LOCATION: http://192.168.1.1:5000/rootDesc.xml\r\nSERVER: test\r\n\r\n"
            out += SsdpReply(v4("192.168.1.1"), reply.toByteArray())
        }
        time += waitMs
        return out
    }

    override fun http(url: LanUrl, method: String, headers: List<Pair<String, String>>, body: ByteArray?, maxBytes: Int): HttpReply? {
        httpUrls += url
        if (!upnp || url.host != v4("192.168.1.1") || url.port != 5000) return null
        if (method == "GET" && url.path == "/rootDesc.xml") return HttpReply(200, description.toByteArray())
        if (method != "POST" || url.path != "/ctl/IPConn") return HttpReply(404, ByteArray(0))
        val action = headers.first { it.first == "SOAPAction" }.second.trim('"').substringAfter('#')
        val text = String(body!!)
        soapActions += action
        soapBodies += text
        fun arg(name: String) = Regex("<$name>([^<]*)</$name>").find(text)?.groupValues?.get(1)
        return when (action) {
            "GetExternalIPAddress" -> ok(action, "<NewExternalIPAddress>$external</NewExternalIPAddress>")
            "AddPortMapping" -> {
                val port = arg("NewExternalPort")!!.toInt()
                val lease = arg("NewLeaseDuration")!!.toLong()
                when {
                    upnpPermanentOnly && lease != 0L -> fault(725, "OnlyPermanentLeasesSupported")
                    port == upnpConflictOn -> fault(718, "ConflictInMappingEntry")
                    else -> {
                        upnpMappings[port] = lease
                        ok(action, "")
                    }
                }
            }
            "DeletePortMapping" -> {
                val port = arg("NewExternalPort")!!.toInt()
                if (upnpMappings.remove(port) != null) ok(action, "") else fault(714, "NoSuchEntryInArray")
            }
            else -> fault(401, "Invalid Action")
        }
    }

    private fun ok(action: String, inner: String) = HttpReply(
        200,
        ("<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:${action}Response xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:1\">$inner</u:${action}Response>" +
            "</s:Body></s:Envelope>").toByteArray(),
    )

    private fun fault(code: Int, text: String) = HttpReply(
        500,
        ("<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault>" +
            "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode>" +
            "<errorDescription>$text</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>").toByteArray(),
    )

    private fun answer(local: InetAddress, d: ByteArray): ByteArray? = when (d[0].toInt()) {
        0 -> if (natPmp) answerNatPmp(d) else null
        2 -> when {
            pcp -> answerPcp(local, d)
            natPmp -> byteArrayOf(0, (0x80 + d[1]).toByte(), 0, 1) + BigEndian.u32(EPOCH) // "unsupported version"
            else -> null
        }
        else -> null
    }

    private fun answerNatPmp(d: ByteArray): ByteArray? {
        val op = d[1].toInt()
        val head = byteArrayOf(0, (0x80 + op).toByte(), 0, 0) + BigEndian.u32(EPOCH)
        if (op == 0) return head + v4(external).address
        val internal = BigEndian.readU16(d, 4)
        val suggested = BigEndian.readU16(d, 6)
        val lifetime = BigEndian.readU32(d, 8)
        if (lifetime == 0L) {
            natMappings.remove(internal)
            return head + BigEndian.u16(internal) + BigEndian.u16(0) + BigEndian.u32(0)
        }
        val externalPort = if (suggested == 0) internal else suggested
        val granted = grantedLifetime ?: lifetime
        natMappings[internal] = externalPort to granted
        return head + BigEndian.u16(internal) + BigEndian.u16(externalPort) + BigEndian.u32(granted)
    }

    private fun answerPcp(local: InetAddress, d: ByteArray): ByteArray {
        val lifetime = BigEndian.readU32(d, 4)
        val nonce = d.copyOfRange(24, 36)
        val internal = BigEndian.readU16(d, 40)
        val suggested = BigEndian.readU16(d, 42)
        val key = Hex.encode(nonce)
        val externalPort = if (lifetime == 0L) 0 else if (suggested == 0) internal else suggested
        val granted = if (lifetime == 0L) 0L else grantedLifetime ?: lifetime
        if (lifetime == 0L) pcpMappings.remove(key) else pcpMappings[key] = internal to granted
        val externalAddress = if (local is Inet6Address) Pcp.as16(local) else Pcp.as16(v4(external))
        return byteArrayOf(2, 0x81.toByte(), 0, 0) + BigEndian.u32(granted) + BigEndian.u32(EPOCH) + ByteArray(12) +
            nonce + byteArrayOf(6, 0, 0, 0) + BigEndian.u16(internal) + BigEndian.u16(externalPort) + externalAddress
    }

    companion object {
        const val EPOCH = 3600L

        /** Like miniupnpd's: the IGD with its WAN device and connection device nested, relative control URLs. */
        val DESCRIPTION = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <specVersion><major>1</major><minor>0</minor></specVersion>
              <device>
                <deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
                <friendlyName>Router</friendlyName>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>
                    <controlURL>/ctl/L3F</controlURL>
                  </service>
                </serviceList>
                <deviceList>
                  <device>
                    <deviceType>urn:schemas-upnp-org:device:WANDevice:1</deviceType>
                    <deviceList>
                      <device>
                        <deviceType>urn:schemas-upnp-org:device:WANConnectionDevice:1</deviceType>
                        <serviceList>
                          <service>
                            <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
                            <serviceId>urn:upnp-org:serviceId:WANIPConn1</serviceId>
                            <controlURL>/ctl/IPConn</controlURL>
                            <eventSubURL>/evt/IPConn</eventSubURL>
                            <SCPDURL>/WANIPCn.xml</SCPDURL>
                          </service>
                        </serviceList>
                      </device>
                    </deviceList>
                  </device>
                </deviceList>
              </device>
            </root>
        """.trimIndent()
    }
}
