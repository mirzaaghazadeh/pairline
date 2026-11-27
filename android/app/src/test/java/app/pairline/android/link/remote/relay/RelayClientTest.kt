package app.pairline.android.link.remote.relay

import app.pairline.android.link.ConnectionListener
import app.pairline.android.link.LinkConnection
import app.pairline.android.link.TransportKind
import app.pairline.android.protocol.BigEndian
import app.pairline.android.protocol.FrameType
import app.pairline.android.protocol.Proto
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The bridge's waiting connection at the user's own server (D-106), against a TLS relay in this process: the pin, the
 * preamble, the status bytes, the hand-over to a gated direct session, and when it retries.
 */
class RelayClientTest {

    /** Accepts TLS connections and queues them for the test to script; [accepted] counts them. */
    private class FakeRelay(context: SSLContext) {
        val server = (context.serverSocketFactory.createServerSocket(0, 8, InetAddress.getLoopbackAddress()) as SSLServerSocket)
        val connections = LinkedBlockingQueue<SSLSocket>()

        @Volatile
        var accepted = 0

        init {
            Thread({
                while (!server.isClosed) {
                    val s = runCatching { server.accept() as SSLSocket }.getOrNull() ?: break
                    accepted++
                    s.soTimeout = 5_000
                    // Handshake at once, as a real server does (a client refusing the certificate gives up here).
                    Thread({
                        runCatching { s.startHandshake() }
                        connections += s
                    }, "fake-relay-conn").apply { isDaemon = true }.start()
                }
            }, "fake-relay").apply { isDaemon = true }.start()
        }

        /** The next connection whose TLS handshake worked, with its 54-byte preamble read. */
        fun next(): Pair<SSLSocket, ByteArray> {
            while (true) {
                val s = connections.poll(5, TimeUnit.SECONDS) ?: error("no connection")
                val preamble = ByteArray(RelayConfig.PREAMBLE_LENGTH)
                try {
                    DataInputStream(s.inputStream).readFully(preamble)
                } catch (_: Exception) {
                    s.close()
                    continue // a client that refused the certificate
                }
                return s to preamble
            }
        }

        fun close() = server.close()
    }

    private class Recorder : ConnectionListener {
        val opened = LinkedBlockingQueue<LinkConnection>()
        val frames = LinkedBlockingQueue<ByteArray>()
        val closed = LinkedBlockingQueue<String>()

        override fun onOpened(connection: LinkConnection) {
            opened += connection
        }

        override fun onFrame(connection: LinkConnection, frame: ByteArray) {
            frames += frame
            connection.frameProcessed()
        }

        override fun onClosed(connection: LinkConnection, reason: String) {
            closed += reason
        }
    }

    private val relay = FakeRelay(serverContext)
    private val recorder = Recorder()
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val rendezvous = ByteArray(16) { (0xa0 + it).toByte() }
    private var config: RelayConfig? = config(fingerprint)
    private val statuses = LinkedBlockingQueue<RelayStatus>()
    private val client = RelayClient(
        recorder,
        config = { config },
        keepAliveTimeoutMs = 2_000,
        handshakeTimeoutMs = 2_000,
        firstRetryMs = 50,
    ).also { c -> c.onChange = { statuses += it } }

    private fun config(fp: ByteArray) = RelayConfig("127.0.0.1", relay.server.localPort, fp, key, rendezvous)

    @After
    fun tearDown() {
        client.stop()
        relay.close()
    }

    /** Waits until the status matches [want]; fails after 5 s. */
    private fun awaitStatus(want: (RelayStatus) -> Boolean): RelayStatus {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            client.status.value.takeIf(want)?.let { return it }
            statuses.poll(50, TimeUnit.MILLISECONDS)
        }
        error("status never matched; last ${client.status.value}")
    }

    @Test
    fun keepAliveThenJoinedHandsOverAGatedDirectSession() {
        client.start()
        val (socket, preamble) = relay.next()
        assertArrayEquals(config!!.preamble(RelayConfig.ROLE_BRIDGE), preamble)
        awaitStatus { it.set && it.up }
        socket.outputStream.apply { write(RelayClient.STATUS_KEEPALIVE); flush() }
        socket.outputStream.apply { write(RelayClient.STATUS_JOINED); flush() }
        // The iPhone's hello, through the relay: the connection is now a direct one, gated like the listener's.
        val hello = ByteArray(1 + Proto.HELLO_LENGTH).also { it[0] = FrameType.HELLO.code.toByte() }
        socket.outputStream.apply { write(BigEndian.u32(hello.size.toLong()) + hello); flush() }
        val connection = recorder.opened.poll(5, TimeUnit.SECONDS)
        assertNotNull(connection)
        assertEquals(TransportKind.DIRECT, connection!!.kind)
        assertNull(connection.peerAddress)
        assertTrue(connection.label.startsWith("relay#"))
        assertArrayEquals(hello, recorder.frames.poll(5, TimeUnit.SECONDS))
        // A new waiting connection opens at once.
        val (_, again) = relay.next()
        assertArrayEquals(preamble, again)
        assertTrue(client.status.value.up)
        // Nothing but a confirm may follow before the handshake: anything else closes it.
        socket.outputStream.apply { write(BigEndian.u32(5) + byteArrayOf(1, 0, 0, 0, 0)); flush() }
        assertEquals("refused before the handshake", recorder.closed.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun aCertificateThatDoesntMatchStopsUntilTheNetworkChanges() {
        config = config(ByteArray(32) { 7 })
        client.start()
        val status = awaitStatus { it.reason == RelayReason.PIN }
        assertFalse(status.up)
        val tried = relay.accepted
        Thread.sleep(400)
        assertEquals("no retry after a pin mismatch", tried, relay.accepted)
        // The right relay again (the user scanned its link once more): it connects.
        config = config(fingerprint)
        client.reconfigure()
        relay.next()
        awaitStatus { it.up && it.reason == null }
    }

    @Test
    fun aWrongKeyStops() {
        client.start()
        val (socket, _) = relay.next()
        socket.outputStream.apply { write(RelayClient.STATUS_WRONG_KEY); flush() }
        socket.close()
        awaitStatus { it.reason == RelayReason.KEY && !it.up }
        val tried = relay.accepted
        Thread.sleep(400)
        assertEquals("no retry after a refused key", tried, relay.accepted)
        // A new network gets one more try.
        client.networkChanged()
        relay.next()
    }

    @Test
    fun aClosedConnectionIsOpenedAgain() {
        client.start()
        val (first, _) = relay.next()
        first.outputStream.apply { write(RelayClient.STATUS_KEEPALIVE); flush() }
        awaitStatus { it.up }
        first.close()
        // After 50 ms (this test's first retry), a new one.
        val (_, preamble) = relay.next()
        assertArrayEquals(config!!.preamble(RelayConfig.ROLE_BRIDGE), preamble)
        awaitStatus { it.up }
    }

    @Test
    fun theFullAndVersionBytesAreReportedAndRetried() {
        client.start()
        relay.next().first.apply { outputStream.write(RelayClient.STATUS_FULL); outputStream.flush(); close() }
        awaitStatus { it.reason == RelayReason.FULL }
        relay.next().first.apply { outputStream.write(RelayClient.STATUS_BAD_VERSION); outputStream.flush(); close() }
        awaitStatus { it.reason == RelayReason.VERSION }
        relay.next()
    }

    @Test
    fun silenceReplacesTheWaitingConnection() {
        client.start()
        relay.next()
        // No keep-alive for this test's 2 s: a new connection.
        val start = System.currentTimeMillis()
        relay.next()
        assertTrue(System.currentTimeMillis() - start >= 1_500)
    }

    @Test
    fun removingTheRelayClosesTheWaitingConnection() {
        client.start()
        val (socket, _) = relay.next()
        awaitStatus { it.up }
        config = null
        client.reconfigure()
        awaitStatus { !it.set }
        socket.soTimeout = 3_000
        val read = try {
            socket.inputStream.read()
        } catch (_: SocketTimeoutException) {
            -2
        } catch (_: Exception) {
            -1
        }
        assertEquals(-1, read)
    }

    companion object {
        private lateinit var dir: File
        private lateinit var serverContext: SSLContext
        private lateinit var fingerprint: ByteArray

        /** A P-256 key and self-signed certificate from the JDK's keytool, like the relay's own, in a temp dir. */
        @BeforeClass
        @JvmStatic
        fun makeCertificate() {
            dir = java.nio.file.Files.createTempDirectory("relay-test").toFile()
            val store = File(dir, "relay.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val process = ProcessBuilder(
                keytool, "-genkeypair", "-alias", "relay", "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", "CN=Pairline relay test", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", store.path, "-storepass", "testpass", "-keypass", "testpass",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            check(process.waitFor() == 0) { "keytool failed: $output" }
            val ks = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "testpass".toCharArray()) } }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "testpass".toCharArray()) }
            serverContext = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
            val cert = ks.getCertificate("relay") as X509Certificate
            fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            dir.deleteRecursively()
        }
    }
}
