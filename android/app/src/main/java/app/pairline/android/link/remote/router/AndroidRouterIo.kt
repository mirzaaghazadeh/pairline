package app.pairline.android.link.remote.router

import android.net.Network
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * [RouterIo] on the phone: every socket is bound to the Wi-Fi [network] and to this phone's address on it, so nothing
 * leaves over mobile data or a VPN. UDP goes only to the gateway's port 5351 (the socket is connected to it, so
 * nothing else is received), SSDP only to the LAN multicast group, HTTP only to URLs [Upnp.isOnNetwork] allowed.
 */
class AndroidRouterIo(private val network: Network?) : RouterIo {

    override fun udp(local: InetAddress, gateway: InetAddress): RouterUdp {
        val socket = DatagramSocket(null)
        try {
            socket.bind(InetSocketAddress(local, 0))
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(gateway, PORT_MAPPING_SERVER_PORT))
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return object : RouterUdp {
            override fun send(datagram: ByteArray) {
                try {
                    socket.send(DatagramPacket(datagram, datagram.size))
                } catch (_: IOException) {
                    // No route right now: the retries and the caller's timeout handle it.
                }
            }

            override fun receive(timeoutMs: Long): ByteArray? {
                val buffer = ByteArray(MAX_DATAGRAM)
                val packet = DatagramPacket(buffer, buffer.size)
                return try {
                    socket.soTimeout = timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                    socket.receive(packet)
                    buffer.copyOf(packet.length)
                } catch (_: IOException) {
                    // A timeout, or ICMP "port unreachable" from a router without the server.
                    null
                }
            }

            override fun close() = socket.close()
        }
    }

    override fun ssdp(local: Inet4Address, messages: List<ByteArray>, waitMs: Long): List<SsdpReply> {
        val out = ArrayList<SsdpReply>()
        val socket = MulticastSocket(null)
        try {
            socket.bind(InetSocketAddress(local, 0))
            network?.bindSocket(socket)
            NetworkInterface.getByInetAddress(local)?.let { socket.networkInterface = it }
            socket.timeToLive = SSDP_TTL
            val group = InetSocketAddress(InetAddress.getByAddress(SSDP_GROUP), SSDP_PORT)
            for (m in messages) socket.send(DatagramPacket(m, m.size, group))
            val deadline = now() + waitMs
            val buffer = ByteArray(SSDP_MAX)
            while (out.size < MAX_SSDP_REPLIES) {
                val left = deadline - now()
                if (left <= 0) break
                val packet = DatagramPacket(buffer, buffer.size)
                socket.soTimeout = left.toInt().coerceAtLeast(1)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                packet.address?.let { out += SsdpReply(it, buffer.copyOf(packet.length)) }
            }
        } catch (_: IOException) {
            // No multicast on this network: no UPnP.
        } finally {
            socket.close()
        }
        return out
    }

    override fun http(url: LanUrl, method: String, headers: List<Pair<String, String>>, body: ByteArray?, maxBytes: Int): HttpReply? {
        val socket = Socket()
        return try {
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(url.host, url.port), HTTP_TIMEOUT_MS)
            socket.soTimeout = HTTP_TIMEOUT_MS
            socket.getOutputStream().apply {
                write(LanHttp.request(method, url, headers, body))
                flush()
            }
            val input = socket.getInputStream()
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            val deadline = now() + HTTP_TIMEOUT_MS
            val limit = maxBytes + HEADER_ALLOWANCE
            while (now() < deadline) {
                val n = input.read(chunk)
                if (n < 0) break
                buffer.write(chunk, 0, n)
                if (buffer.size() > limit) return null
                val bytes = buffer.toByteArray()
                if (LanHttp.isComplete(bytes, bytes.size)) break
            }
            LanHttp.parse(buffer.toByteArray(), maxBytes)
        } catch (_: IOException) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val MAX_DATAGRAM = 1_100
        private const val SSDP_PORT = 1900
        private val SSDP_GROUP = byteArrayOf(239.toByte(), 255.toByte(), 255.toByte(), 250.toByte())
        private const val SSDP_TTL = 2
        private const val SSDP_MAX = 2_048
        private const val MAX_SSDP_REPLIES = 16
        private const val HTTP_TIMEOUT_MS = 4_000
        private const val HEADER_ALLOWANCE = 8 * 1024
    }
}
