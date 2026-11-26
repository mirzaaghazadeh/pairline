package app.pairline.android.link.remote

import app.pairline.android.link.ConnectionListener
import app.pairline.android.link.LinkConnection
import app.pairline.android.link.TransportKind
import app.pairline.android.protocol.BigEndian
import app.pairline.android.protocol.FrameType
import app.pairline.android.protocol.TcpFraming
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The public listener on loopback: what it lets through before the handshake, and what it drops. */
class DirectListenerTest {

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

    private val recorder = Recorder()
    private val clients = ArrayList<Socket>()
    private var listener = DirectListener(recorder, preferredPort = 0, handshakeTimeoutMs = 800)
    private var port = listen()

    private fun listen(): Int = listener.listen(listOf(InetAddress.getLoopbackAddress()))!!

    @After
    fun tearDown() {
        listener.close()
        clients.forEach { runCatching { it.close() } }
    }

    private fun connect(): Socket = Socket(InetAddress.getLoopbackAddress(), port).also {
        it.soTimeout = 3_000
        clients += it
    }

    private fun Socket.write(bytes: ByteArray) = getOutputStream().apply { write(bytes); flush() }

    private fun frame(type: FrameType, bodyLength: Int) = TcpFraming.encode(byteArrayOf(type.code.toByte()) + ByteArray(bodyLength) { 7 })

    private val hello get() = frame(FrameType.HELLO, 99)
    private val confirm get() = frame(FrameType.RECORD, 24)

    /** True once the server has closed [this] (end of stream or a reset); false if it's still open after 3 s. */
    private fun Socket.closedByServer(): Boolean = try {
        getInputStream().read() < 0
    } catch (_: SocketTimeoutException) {
        false
    } catch (_: IOException) {
        true
    }

    @Test
    fun helloAndConfirmPassThenNothingUntilAuthenticated() {
        val c = connect()
        c.write(hello + confirm + frame(FrameType.RECORD, 40))
        val conn = recorder.opened.poll(2, TimeUnit.SECONDS)!!
        assertEquals(TransportKind.DIRECT, conn.kind)
        assertEquals(100, recorder.frames.poll(2, TimeUnit.SECONDS)!!.size)
        assertEquals(25, recorder.frames.poll(2, TimeUnit.SECONDS)!!.size)
        // The third frame waits in the socket until the handshake has authenticated the iPhone.
        assertNull(recorder.frames.poll(300, TimeUnit.MILLISECONDS))
        conn.onAuthenticated()
        assertEquals(41, recorder.frames.poll(2, TimeUnit.SECONDS)!!.size)
        // After that: ordinary frames, both ways, and no handshake deadline any more.
        conn.send(byteArrayOf(0x20, 1, 2))
        val header = ByteArray(4).also { c.getInputStream().read(it) }
        assertEquals(3L, BigEndian.readU32(header, 0))
        Thread.sleep(1_000)
        assertTrue(recorder.closed.isEmpty())
    }

    @Test
    fun pairingFramesAreRefused() {
        // A PAIR_HELLO of the hello's exact length, and one of a typical length.
        for (pair in listOf(frame(FrameType.PAIR_HELLO, 99), frame(FrameType.PAIR_HELLO, 180))) {
            val c = connect()
            c.write(pair)
            assertEquals("refused before the handshake", recorder.closed.poll(2, TimeUnit.SECONDS))
            assertTrue(c.closedByServer())
        }
        assertTrue(recorder.frames.isEmpty())
    }

    @Test
    fun garbageIsDroppedAfterTheLength() {
        val c = connect()
        c.write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        assertEquals("refused before the handshake", recorder.closed.poll(2, TimeUnit.SECONDS))
        assertTrue(c.closedByServer())
        assertTrue(recorder.frames.isEmpty())
    }

    @Test
    fun aSecondHelloInsteadOfTheConfirmIsDropped() {
        val c = connect()
        c.write(hello + hello)
        assertEquals("refused before the handshake", recorder.closed.poll(2, TimeUnit.SECONDS))
        assertEquals(1, recorder.frames.size)
        assertTrue(c.closedByServer())
    }

    @Test
    fun silenceTimesOut() {
        val c = connect()
        assertEquals("handshake timed out", recorder.closed.poll(3, TimeUnit.SECONDS))
        assertTrue(c.closedByServer())
    }

    @Test
    fun aTrickledHelloTimesOut() {
        val c = connect()
        val bytes = hello
        Thread {
            runCatching {
                for (b in bytes) {
                    c.write(byteArrayOf(b))
                    Thread.sleep(50)
                }
            }
        }.start()
        assertEquals("handshake timed out", recorder.closed.poll(3, TimeUnit.SECONDS))
    }

    @Test
    fun anUnauthenticatedHandshakeTimesOut() {
        val c = connect()
        c.write(hello + confirm)
        assertEquals("handshake timed out", recorder.closed.poll(3, TimeUnit.SECONDS))
        assertTrue(c.closedByServer())
    }

    @Test
    fun closingAConnectionBeforeTheVerdictEndsIt() {
        val c = connect()
        c.write(hello + confirm)
        val conn = recorder.opened.poll(2, TimeUnit.SECONDS)!!
        assertNotNull(recorder.frames.poll(2, TimeUnit.SECONDS))
        assertNotNull(recorder.frames.poll(2, TimeUnit.SECONDS))
        conn.close() // the session's confirm didn't decrypt
        assertEquals("closed", recorder.closed.poll(2, TimeUnit.SECONDS))
        assertTrue(c.closedByServer())
    }

    @Test
    fun aFifthWaitingConnectionPushesOutTheOldest() {
        listener.close()
        listener = DirectListener(recorder, preferredPort = 0, handshakeTimeoutMs = 10_000)
        port = listen()
        val first = connect()
        assertNotNull(recorder.opened.poll(2, TimeUnit.SECONDS))
        repeat(DirectListener.MAX_PENDING) {
            connect()
            assertNotNull(recorder.opened.poll(2, TimeUnit.SECONDS))
        }
        assertEquals("closed", recorder.closed.poll(2, TimeUnit.SECONDS))
        assertTrue(first.closedByServer())
        assertTrue(recorder.closed.isEmpty())
    }

    @Test
    fun atMostTenNewConnectionsAMinuteFromOneAddress() {
        listener.close()
        listener = DirectListener(recorder, preferredPort = 0, handshakeTimeoutMs = 10_000)
        port = listen()
        val sockets = (1..AdmissionGate.PER_MINUTE + 1).map { connect() }
        // The eleventh is closed at once, without ever reaching LinkManager.
        assertTrue(sockets.last().closedByServer())
        Thread.sleep(200)
        assertEquals(AdmissionGate.PER_MINUTE, recorder.opened.size)
    }

    @Test
    fun closingTheListenerDropsWaitingConnectionsAndStopsAccepting() {
        val c = connect()
        assertNotNull(recorder.opened.poll(2, TimeUnit.SECONDS))
        listener.close()
        assertEquals("closed", recorder.closed.poll(2, TimeUnit.SECONDS))
        assertTrue(c.closedByServer())
        assertFalse(runCatching { Socket(InetAddress.getLoopbackAddress(), port).close() }.isSuccess)
    }

    @Test
    fun listensOnEveryAddressWithOnePort() {
        assertEquals(port, listener.listen(listOf(InetAddress.getLoopbackAddress())))
        assertNull(listener.listen(emptyList()))
        assertFalse(runCatching { Socket(InetAddress.getLoopbackAddress(), port).close() }.isSuccess)
    }

    // --- Admission ---------------------------------------------------------------------------------------------

    @Test
    fun gateCountsPerAddressAndSlides() {
        val gate = AdmissionGate()
        val a = InetAddress.getByName("81.2.69.160")
        val b = InetAddress.getByName("81.2.69.161")
        repeat(10) { assertTrue(gate.admit(a, 1_000L + it)) }
        assertFalse(gate.admit(a, 2_000))
        assertTrue(gate.admit(b, 2_000)) // another address has its own budget
        assertFalse(gate.admit(a, 60_999))
        assertTrue(gate.admit(a, 61_001)) // the first one is a minute old now
    }

    @Test
    fun anIPv6Slash64CountsAsOneAddress() {
        val gate = AdmissionGate()
        repeat(10) { assertTrue(gate.admit(InetAddress.getByName("2a02:1234:5678:9abc::${it + 1}"), 0)) }
        assertFalse(gate.admit(InetAddress.getByName("2a02:1234:5678:9abc:ffff::1"), 0))
        assertTrue(gate.admit(InetAddress.getByName("2a02:1234:5678:9abd::1"), 0))
        assertEquals(AdmissionGate.key(InetAddress.getByName("81.2.69.160")), AdmissionGate.key(InetAddress.getByName("::ffff:81.2.69.160")))
    }
}
