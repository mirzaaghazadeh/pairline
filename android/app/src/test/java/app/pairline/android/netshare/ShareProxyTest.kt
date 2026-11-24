package app.pairline.android.netshare

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs the internet-sharing proxy on loopback against a local echo server (M8, D-048). */
class ShareProxyTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private lateinit var echo: ServerSocket
    private lateinit var proxy: ShareProxy
    private var echoPort = 0
    private var proxyPort = 0
    private val asked = mutableListOf<Pair<String, Int>>()

    @Before
    fun setUp() {
        echo = ServerSocket(0, 50, loopback)
        echoPort = echo.localPort
        thread(isDaemon = true) {
            while (!echo.isClosed) {
                val s = runCatching { echo.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { s.use { it.getInputStream().copyTo(it.getOutputStream()) } }
            }
        }
        proxy = ShareProxy(
            bindAddress = loopback,
            username = "user",
            password = "secret",
            pac = { Pac.build("127.0.0.1", proxyPort, listOf("ir")) },
            connector = { host, port ->
                synchronized(asked) { asked += host to port }
                Socket(loopback, echoPort)
            },
        )
        proxyPort = proxy.start()
    }

    @After
    fun tearDown() {
        proxy.stop()
        echo.close()
    }

    private fun connect() = Socket(loopback, proxyPort).apply { soTimeout = 5_000 }

    @Test
    fun socks5WithPasswordRelaysBothWays() {
        connect().use { s ->
            val out = s.getOutputStream()
            val input = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(5, 1, 2))
            assertEquals(5, input.read()); assertEquals(2, input.read())
            out.write(byteArrayOf(1, 4) + "user".toByteArray() + byteArrayOf(6) + "secret".toByteArray())
            assertEquals(1, input.read()); assertEquals(0, input.read())
            val host = "example.org".toByteArray()
            out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf(0x01, 0xBB.toByte()))
            val reply = ByteArray(10).also { input.readFully(it) }
            assertEquals(0, reply[1].toInt())
            out.write("ping".toByteArray())
            val back = ByteArray(4).also { input.readFully(it) }
            assertEquals("ping", String(back))
        }
        assertEquals("example.org" to 443, asked.single())
    }

    @Test
    fun socks5WrongPasswordIsRefused() {
        connect().use { s ->
            val out = s.getOutputStream()
            val input = s.getInputStream()
            out.write(byteArrayOf(5, 1, 2))
            input.read(); input.read()
            out.write(byteArrayOf(1, 4) + "user".toByteArray() + byteArrayOf(5) + "wrong".toByteArray())
            assertEquals(1, input.read())
            assertEquals(1, input.read())
        }
        assertTrue(asked.isEmpty())
    }

    @Test
    fun socks5WithoutPasswordMethodIsRefused() {
        connect().use { s ->
            s.getOutputStream().write(byteArrayOf(5, 1, 0))
            assertEquals(5, s.getInputStream().read())
            assertEquals(0xFF, s.getInputStream().read())
        }
    }

    @Test
    fun httpConnectNeedsBasicAuth() {
        connect().use { s ->
            s.getOutputStream().write("CONNECT example.org:443 HTTP/1.1\r\nHost: example.org:443\r\n\r\n".toByteArray())
            val head = s.getInputStream().bufferedReader().readLine()
            assertTrue(head.startsWith("HTTP/1.1 407"))
        }
        val auth = java.util.Base64.getEncoder().encodeToString("user:secret".toByteArray())
        connect().use { s ->
            s.getOutputStream().write(
                "CONNECT example.org:443 HTTP/1.1\r\nProxy-Authorization: Basic $auth\r\n\r\n".toByteArray(),
            )
            val input = DataInputStream(s.getInputStream())
            val expected = "HTTP/1.1 200 Connection established\r\n\r\n"
            val head = ByteArray(expected.length).also { input.readFully(it) }
            assertEquals(expected, String(head))
            s.getOutputStream().write("tls".toByteArray())
            assertEquals("tls", String(ByteArray(3).also { input.readFully(it) }))
        }
    }

    @Test
    fun pacFileIsServedWithoutAuthAndHoldsNoSecret() {
        connect().use { s ->
            s.getOutputStream().write("GET /proxy.pac HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val response = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertTrue(response.contains("PROXY 127.0.0.1:$proxyPort"))
            assertTrue(response.contains("dnsDomainIs(host, \".ir\")"))
            assertFalse(response.contains("secret"))
        }
    }

    @Test
    fun pacRejectsOddSuffixesAndKeepsLocalDirect() {
        val pac = Pac.build("192.168.43.1", 8080, listOf("ir", ".example.com", "bad\"js"))
        assertTrue(pac.contains("dnsDomainIs(host, \".example.com\")"))
        assertFalse(pac.contains("bad"))
        assertTrue(pac.contains("isInNet(host, \"192.168.0.0\""))
        assertTrue(pac.trimEnd().endsWith("}"))
        assertFalse(pac.contains("; DIRECT")) // no silent fallback around the VPN
    }

    @Test
    fun hostPortParsing() {
        assertEquals("a.b" to 443, ShareProxy.splitHostPort("a.b", 443))
        assertEquals("a.b" to 8443, ShareProxy.splitHostPort("a.b:8443", 443))
        assertEquals("::1" to 80, ShareProxy.splitHostPort("[::1]:80", 443))
        assertEquals(null, ShareProxy.splitHostPort("a.b:99999", 443))
        assertTrue(ShareProxy.isLocal(InetAddress.getByName("192.168.43.10")))
        assertFalse(ShareProxy.isLocal(InetAddress.getByName("8.8.8.8")))
    }
}
