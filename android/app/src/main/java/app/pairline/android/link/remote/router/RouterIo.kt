package app.pairline.android.link.remote.router

import java.io.Closeable
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The phone's Wi-Fi (or Ethernet) network as the direct connection sees it, read from Android's LinkProperties. Never
 * mobile data or a VPN.
 */
data class RouterNetwork(
    /** The default IPv4 gateway: the home router, which answers NAT-PMP, PCP and UPnP. */
    val gatewayV4: Inet4Address?,
    /** This phone's private IPv4 address on the network, and its prefix length. */
    val localV4: Inet4Address?,
    val prefixV4: Int,
    /** The IPv6 default router (usually link-local, with its zone): PCP for the IPv6 pinhole goes there. */
    val gatewayV6: Inet6Address?,
    /** This phone's global IPv6 address, if the network has IPv6. */
    val globalV6: Inet6Address?,
) {
    val hasV4: Boolean get() = gatewayV4 != null && localV4 != null
}

/** UDP with the router's port-mapping server (UDP 5351), from one local address. */
interface RouterUdp : Closeable {
    fun send(datagram: ByteArray)

    /** The next datagram from the router's port 5351, or null after [timeoutMs] (or on an error). */
    fun receive(timeoutMs: Long): ByteArray?

    override fun close() {}
}

/** One SSDP answer: who sent it and its bytes (at most 2 KB). */
class SsdpReply(val from: InetAddress, val bytes: ByteArray)

/** A plain HTTP reply: the status and the body (already limited in size). */
class HttpReply(val status: Int, val body: ByteArray)

/**
 * Everything that touches the network for one Wi-Fi network: bound to it, so nothing goes out over mobile data or a
 * VPN. The phone implementation is [AndroidRouterIo]; tests use a fake router.
 */
interface RouterIo {
    /** A UDP socket on [local] for the router's port-mapping server at [gateway]:5351. */
    fun udp(local: InetAddress, gateway: InetAddress): RouterUdp

    /** Sends each of [messages] to the SSDP multicast group from [local] and collects answers for [waitMs]. */
    fun ssdp(local: Inet4Address, messages: List<ByteArray>, waitMs: Long): List<SsdpReply>

    /**
     * One HTTP request to [url] (already checked to be on the LAN). Returns null on any connection error or when the
     * response is larger than [maxBytes]. Never follows redirects.
     */
    fun http(url: LanUrl, method: String, headers: List<Pair<String, String>>, body: ByteArray?, maxBytes: Int): HttpReply?

    /** Milliseconds; a fake router lets tests skip time. */
    fun now(): Long = System.currentTimeMillis()
}

/**
 * RFC 6886's retransmission: send, wait 250 ms, then double the wait for each try. The first reply [match] accepts
 * wins; a stray or malformed datagram is skipped. Null when nothing matching arrived.
 */
internal fun <T> RouterUdp.ask(request: ByteArray, tries: Int, now: () -> Long, match: (ByteArray) -> T?): T? {
    var wait = FIRST_WAIT_MS
    repeat(tries) {
        send(request)
        val deadline = now() + wait
        while (true) {
            val left = deadline - now()
            if (left <= 0) break
            val reply = receive(left) ?: break
            match(reply)?.let { return it }
        }
        wait *= 2
    }
    return null
}

internal const val FIRST_WAIT_MS = 250L

/** 250 + 500 + 1000 + 2000 ms: about 4 s per protocol before moving on. */
internal const val TRIES = 4

internal const val PORT_MAPPING_SERVER_PORT = 5351
