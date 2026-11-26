package app.pairline.android.link.remote.router

import app.pairline.android.protocol.BigEndian
import app.pairline.android.protocol.constantTimeEquals
import java.net.Inet4Address
import java.net.InetAddress

/**
 * PCP (RFC 6887) MAP requests for one TCP port: a 24-byte common header and the 36-byte MAP payload. IPv4
 * addresses travel as IPv4-mapped IPv6 (`::ffff:a.b.c.d`). The same nonce renews and deletes (lifetime 0) a mapping.
 * For IPv6 the same request opens a pinhole in the router's firewall for this phone's own address.
 */
object Pcp {
    const val VERSION = 2
    const val OP_MAP = 1
    const val PROTOCOL_TCP = 6
    const val RESULT_SUCCESS = 0
    const val NONCE_LENGTH = 12
    const val REQUEST_LENGTH = 60

    class MapReply(
        val result: Int,
        val lifetime: Long,
        val epoch: Long,
        val internalPort: Int,
        val externalPort: Int,
        val external: InetAddress,
    )

    /**
     * [client] is this phone's address the request is sent from (the router checks it against the packet's source).
     * The suggested external address is the all-zeros address of the same family: no preference.
     */
    fun mapRequest(client: InetAddress, nonce: ByteArray, internalPort: Int, suggestedExternalPort: Int, lifetime: Long): ByteArray {
        require(nonce.size == NONCE_LENGTH)
        val out = ByteArray(REQUEST_LENGTH)
        out[0] = VERSION.toByte()
        out[1] = OP_MAP.toByte() // R bit 0: a request
        BigEndian.u32(lifetime).copyInto(out, 4)
        as16(client).copyInto(out, 8)
        nonce.copyInto(out, 24)
        out[36] = PROTOCOL_TCP.toByte()
        BigEndian.u16(internalPort).copyInto(out, 40)
        BigEndian.u16(suggestedExternalPort).copyInto(out, 42)
        (if (client is Inet4Address) V4_ALL_ZEROS else ByteArray(16)).copyInto(out, 44)
        return out
    }

    /**
     * The reply to our MAP request with [nonce] for [internalPort], or null for anything else: too short, another
     * version (a NAT-PMP-only router answers "unsupported version" in NAT-PMP's format), another opcode or protocol,
     * or another nonce. An error reply has the result code and nothing else useful.
     */
    fun parseMapReply(bytes: ByteArray, nonce: ByteArray, internalPort: Int): MapReply? {
        if (bytes.size < 24 || bytes[0].toInt() != VERSION || (bytes[1].toInt() and 0xff) != 0x80 + OP_MAP) return null
        val result = bytes[3].toInt() and 0xff
        val lifetime = BigEndian.readU32(bytes, 4)
        val epoch = BigEndian.readU32(bytes, 8)
        if (bytes.size < REQUEST_LENGTH) {
            return if (result != RESULT_SUCCESS) MapReply(result, lifetime, epoch, 0, 0, InetAddress.getByAddress(ByteArray(16))) else null
        }
        if (!constantTimeEquals(bytes.copyOfRange(24, 36), nonce)) return null
        if ((bytes[36].toInt() and 0xff) != PROTOCOL_TCP || BigEndian.readU16(bytes, 40) != internalPort) return null
        return MapReply(
            result,
            lifetime,
            epoch,
            internalPort,
            BigEndian.readU16(bytes, 42),
            from16(bytes.copyOfRange(44, 60)),
        )
    }

    /** True if [bytes] is a NAT-PMP "unsupported version" answer: the router speaks NAT-PMP only. */
    fun isNatPmpOnlyAnswer(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes[0].toInt() == 0 && BigEndian.readU16(bytes, 2) == 1

    /** 16 bytes: IPv6 as is, IPv4 as `::ffff:a.b.c.d`. */
    fun as16(address: InetAddress): ByteArray = when (address) {
        is Inet4Address -> ByteArray(10) + byteArrayOf(-1, -1) + address.address
        else -> address.address.copyOf()
    }

    /** IPv4-mapped addresses (`::ffff:0:0/96`) come back as [Inet4Address]; Java does that conversion itself. */
    private fun from16(bytes: ByteArray): InetAddress = InetAddress.getByAddress(bytes)

    private val V4_ALL_ZEROS = ByteArray(10) + byteArrayOf(-1, -1, 0, 0, 0, 0)
}

/** Asks one router over PCP. Every call blocks for at most about 4 s ([TRIES]). */
class PcpClient(private val udp: RouterUdp, private val client: InetAddress, private val now: () -> Long) {

    /** How the last request ended, for the log. */
    var lastResult: Int? = null
        private set

    /** Maps TCP [internalPort] with [nonce]; null if refused or unanswered. */
    fun map(nonce: ByteArray, internalPort: Int, suggestedExternalPort: Int, lifetime: Long): Pcp.MapReply? {
        val reply = exchange(Pcp.mapRequest(client, nonce, internalPort, suggestedExternalPort, lifetime), nonce, internalPort)
        return reply?.takeIf { it.result == Pcp.RESULT_SUCCESS && it.lifetime > 0 && it.externalPort > 0 }
    }

    /** Deletes the mapping made with [nonce]. True if the router agreed. */
    fun delete(nonce: ByteArray, internalPort: Int): Boolean =
        exchange(Pcp.mapRequest(client, nonce, internalPort, 0, 0), nonce, internalPort)?.result == Pcp.RESULT_SUCCESS

    private fun exchange(request: ByteArray, nonce: ByteArray, internalPort: Int): Pcp.MapReply? {
        var natPmpOnly = false
        val reply = udp.ask(request, TRIES, now) { bytes ->
            if (Pcp.isNatPmpOnlyAnswer(bytes)) {
                natPmpOnly = true
                NO_PCP
            } else {
                Pcp.parseMapReply(bytes, nonce, internalPort)
            }
        }
        lastResult = if (natPmpOnly) null else reply?.result
        return reply?.takeIf { it !== NO_PCP }
    }

    private companion object {
        /** Stops the retries at once when a NAT-PMP-only router says it doesn't know version 2. */
        val NO_PCP = Pcp.MapReply(-1, 0, 0, 0, 0, InetAddress.getByAddress(ByteArray(4)))
    }
}
