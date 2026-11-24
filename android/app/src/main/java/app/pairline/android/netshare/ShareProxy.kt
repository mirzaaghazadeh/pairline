package app.pairline.android.netshare

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Internet sharing (M8, D-048): a small SOCKS5 + HTTP proxy on the bridge phone, so the iPhone can use this phone's
 * connection, including its VPN. One port speaks both: SOCKS5 (Telegram and other apps that support it) and HTTP
 * (CONNECT and plain requests, for iOS's Wi-Fi proxy settings), plus `GET /proxy.pac` for automatic configuration.
 *
 * - Listens only on [bindAddress], a local-network address of this phone, never on all interfaces.
 * - Accepts only clients with a private or link-local address, and only [allowedClient] when it's known.
 * - Requires the per-session [username]/[password] for SOCKS5 and HTTP proxying (the PAC file holds no secret).
 * - Forwards only what the iPhone asks for; logs counts, never hosts or addresses.
 */
class ShareProxy(
    private val bindAddress: InetAddress,
    private val username: String,
    private val password: String,
    private val pac: () -> String,
    private val allowedClient: () -> InetAddress? = { null },
    private val connector: (host: String, port: Int) -> Socket = ::connectOut,
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "pairline-netshare").apply { isDaemon = true } }
    private val open = AtomicInteger(0)
    private val total = AtomicInteger(0)

    @Volatile
    private var running = false

    val port: Int get() = server?.localPort ?: 0
    val connectionsServed: Int get() = total.get()

    /** Binds an ephemeral port (or [port]) and starts accepting. */
    fun start(port: Int = 0): Int {
        val s = ServerSocket()
        s.reuseAddress = true
        try {
            s.bind(InetSocketAddress(bindAddress, port), 64)
        } catch (e: java.io.IOException) {
            s.close() // a busy preferred port: the caller tries another one
            throw e
        }
        server = s
        running = true
        pool.execute { acceptLoop(s) }
        return s.localPort
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val client = try {
                s.accept()
            } catch (_: IOException) {
                break
            }
            if (!allowed(client.inetAddress) || open.get() >= MAX_CONNECTIONS) {
                runCatching { client.close() }
                continue
            }
            open.incrementAndGet()
            total.incrementAndGet()
            pool.execute {
                try {
                    handle(client)
                } catch (_: IOException) {
                    // Client or upstream went away.
                } finally {
                    runCatching { client.close() }
                    open.decrementAndGet()
                }
            }
        }
    }

    private fun allowed(address: InetAddress): Boolean {
        allowedClient()?.let { return it == address }
        return isLocal(address)
    }

    private fun handle(client: Socket) {
        client.soTimeout = HANDSHAKE_TIMEOUT_MS
        client.tcpNoDelay = true
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val first = input.read()
        if (first < 0) return
        if (first == SOCKS_VERSION) socks(client, input, output) else http(client, first, input, output)
    }

    // ---- SOCKS5 (RFC 1928, username/password RFC 1929); CONNECT only ----

    private fun socks(client: Socket, input: InputStream, output: OutputStream) {
        val methods = readExact(input, input.read())
        if (METHOD_USER_PASS.toByte() !in methods) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_ACCEPTABLE.toByte()))
            return
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_USER_PASS.toByte()))
        if (input.read() != 0x01) return
        val user = String(readExact(input, input.read()), Charsets.UTF_8)
        val pass = String(readExact(input, input.read()), Charsets.UTF_8)
        val ok = constantTimeEquals(user, username) && constantTimeEquals(pass, password)
        output.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
        if (!ok) return

        val header = readExact(input, 4) // VER CMD RSV ATYP
        if (header[0].toInt() != SOCKS_VERSION || header[1].toInt() != CMD_CONNECT) {
            output.write(socksReply(REPLY_COMMAND_NOT_SUPPORTED))
            return
        }
        val host = when (header[3].toInt()) {
            0x01 -> InetAddress.getByAddress(readExact(input, 4)).hostAddress
            0x03 -> String(readExact(input, input.read()), Charsets.US_ASCII)
            0x04 -> InetAddress.getByAddress(readExact(input, 16)).hostAddress
            else -> {
                output.write(socksReply(REPLY_ADDRESS_NOT_SUPPORTED))
                return
            }
        } ?: return
        val portBytes = readExact(input, 2)
        val port = (portBytes[0].toInt() and 0xFF shl 8) or (portBytes[1].toInt() and 0xFF)
        val upstream = try {
            connector(host, port)
        } catch (_: IOException) {
            output.write(socksReply(REPLY_HOST_UNREACHABLE))
            return
        }
        output.write(socksReply(0x00))
        relay(client, upstream)
    }

    private fun socksReply(code: Int): ByteArray =
        byteArrayOf(SOCKS_VERSION.toByte(), code.toByte(), 0, 0x01, 0, 0, 0, 0, 0, 0)

    // ---- HTTP proxy: CONNECT, absolute-form requests, and the PAC file ----

    private fun http(client: Socket, first: Int, input: InputStream, output: OutputStream) {
        val head = readHead(first, input) ?: return
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 3) return
        val (method, target) = parts[0] to parts[1]
        val headers = lines.drop(1).filter { it.isNotEmpty() }

        if (method == "GET" && (target == "/proxy.pac" || target.endsWith("/proxy.pac") && target.startsWith("/"))) {
            val body = pac().toByteArray(Charsets.UTF_8)
            output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/x-ns-proxy-autoconfig\r\n" +
                    "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            output.write(body)
            return
        }
        // iOS's automatic (PAC) proxy settings can't carry a password, so the paired iPhone's own Wi-Fi-link address
        // may proxy over HTTP without one; anyone else must authenticate. SOCKS5 always needs the password.
        val trusted = allowedClient()?.let { it == client.inetAddress } ?: false
        if (!trusted && !authorized(headers)) {
            output.write(
                ("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"Pairline\"\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            return
        }
        if (method == "CONNECT") {
            val (host, port) = splitHostPort(target, 443) ?: return badRequest(output)
            val upstream = try {
                connector(host, port)
            } catch (_: IOException) {
                return gatewayError(output)
            }
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            relay(client, upstream)
            return
        }
        // Absolute-form request ("GET http://host/path"): send it on in origin form, one request per connection.
        if (!target.startsWith("http://")) return badRequest(output)
        val rest = target.removePrefix("http://")
        val authority = rest.substringBefore('/')
        val path = "/" + rest.substringAfter('/', "")
        val (host, port) = splitHostPort(authority, 80) ?: return badRequest(output)
        val upstream = try {
            connector(host, port)
        } catch (_: IOException) {
            return gatewayError(output)
        }
        val forwarded = StringBuilder("$method $path ${parts[2]}\r\n")
        for (h in headers) {
            val name = h.substringBefore(':').trim().lowercase()
            if (name.startsWith("proxy-") || name == "connection" || name == "keep-alive") continue
            forwarded.append(h).append("\r\n")
        }
        forwarded.append("Connection: close\r\n\r\n")
        upstream.getOutputStream().write(forwarded.toString().toByteArray(Charsets.ISO_8859_1))
        relay(client, upstream)
    }

    private fun authorized(headers: List<String>): Boolean {
        val value = headers.firstOrNull { it.substringBefore(':').trim().equals("Proxy-Authorization", true) }
            ?.substringAfter(':')?.trim() ?: return false
        if (!value.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching { String(java.util.Base64.getDecoder().decode(value.substring(6).trim()), Charsets.UTF_8) }
            .getOrNull() ?: return false
        return constantTimeEquals(decoded, "$username:$password")
    }

    private fun badRequest(output: OutputStream) =
        output.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())

    private fun gatewayError(output: OutputStream) =
        output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())

    /** Reads the request head (up to the blank line), at most [MAX_HEAD] bytes. */
    private fun readHead(first: Int, input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        buffer.write(first)
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (buffer.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (matched == 4) return buffer.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
        }
        return null
    }

    /** Copies both ways until either side closes; idle connections end after [IDLE_TIMEOUT_MS]. */
    private fun relay(client: Socket, upstream: Socket) {
        client.soTimeout = IDLE_TIMEOUT_MS
        upstream.soTimeout = IDLE_TIMEOUT_MS
        val down = pool.submit { pipe(upstream.getInputStream(), client.getOutputStream(), client, upstream) }
        try {
            pipe(client.getInputStream(), upstream.getOutputStream(), upstream, client)
        } finally {
            runCatching { down.get() }
            runCatching { upstream.close() }
        }
    }

    private fun pipe(from: InputStream, to: OutputStream, toSocket: Socket, fromSocket: Socket) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
            }
        } catch (_: SocketTimeoutException) {
            // Idle too long.
        } catch (_: IOException) {
            // Closed.
        } finally {
            runCatching { toSocket.shutdownOutput() }
            runCatching { fromSocket.shutdownInput() }
        }
    }

    companion object {
        private const val SOCKS_VERSION = 0x05
        private const val METHOD_USER_PASS = 0x02
        private const val NO_ACCEPTABLE = 0xFF
        private const val CMD_CONNECT = 0x01
        private const val REPLY_HOST_UNREACHABLE = 0x04
        private const val REPLY_COMMAND_NOT_SUPPORTED = 0x07
        private const val REPLY_ADDRESS_NOT_SUPPORTED = 0x08
        private const val MAX_CONNECTIONS = 256
        private const val MAX_HEAD = 16 * 1024
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val IDLE_TIMEOUT_MS = 5 * 60_000
        private const val CONNECT_TIMEOUT_MS = 15_000

        /** Opens the outbound connection through Android's default network, which is the VPN when one is on. */
        fun connectOut(host: String, port: Int): Socket {
            if (port !in 1..65535) throw IOException("bad port")
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            return socket
        }

        fun isLocal(address: InetAddress): Boolean = when (address) {
            is Inet4Address -> address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress
            is Inet6Address -> address.isLinkLocalAddress || address.isLoopbackAddress ||
                (address.address[0].toInt() and 0xFE) == 0xFC // unique local fc00::/7
            else -> false
        }

        fun splitHostPort(authority: String, defaultPort: Int): Pair<String, Int>? {
            if (authority.startsWith("[")) {
                val host = authority.substringAfter('[').substringBefore(']')
                val port = authority.substringAfter("]:", "").toIntOrNull() ?: defaultPort
                return if (host.isEmpty()) null else host to port
            }
            val host = authority.substringBefore(':')
            val port = if (':' in authority) authority.substringAfter(':').toIntOrNull() ?: return null else defaultPort
            return if (host.isEmpty() || port !in 1..65535) null else host to port
        }

        fun constantTimeEquals(a: String, b: String): Boolean {
            val x = a.toByteArray(Charsets.UTF_8)
            val y = b.toByteArray(Charsets.UTF_8)
            var diff = x.size xor y.size
            for (i in 0 until maxOf(x.size, y.size)) diff = diff or (x.getOrElse(i) { 0 }.toInt() xor y.getOrElse(i) { 0 }.toInt())
            return diff == 0
        }

        private fun readExact(input: InputStream, count: Int): ByteArray {
            if (count < 0) throw IOException("closed")
            val out = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val n = input.read(out, filled, count - filled)
                if (n < 0) throw IOException("closed")
                filled += n
            }
            return out
        }
    }
}
