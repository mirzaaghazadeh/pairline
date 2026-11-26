package app.pairline.android.link.remote.router

import app.pairline.android.protocol.BigEndian
import java.net.Inet4Address
import java.net.InetAddress

/**
 * NAT-PMP (RFC 6886) packets: the external-address request (opcode 0) and the TCP mapping request (opcode 2). A
 * mapping is deleted with lifetime 0 and suggested external port 0.
 */
object NatPmp {
    const val VERSION = 0
    const val OP_EXTERNAL_ADDRESS = 0
    const val OP_MAP_TCP = 2
    const val RESULT_SUCCESS = 0

    /** A reply to opcode 0: the result code and, on success, the router's external IPv4 address. */
    class AddressReply(val result: Int, val epoch: Long, val external: Inet4Address?)

    /** A reply to opcode 2: the internal and external port and the granted lifetime in seconds. */
    class MapReply(val result: Int, val epoch: Long, val internalPort: Int, val externalPort: Int, val lifetime: Long)

    fun externalAddressRequest(): ByteArray = byteArrayOf(VERSION.toByte(), OP_EXTERNAL_ADDRESS.toByte())

    /** 12 bytes: version, opcode, reserved (2), internal port, suggested external port, requested lifetime (s). */
    fun mapRequest(internalPort: Int, suggestedExternalPort: Int, lifetime: Long): ByteArray =
        byteArrayOf(VERSION.toByte(), OP_MAP_TCP.toByte(), 0, 0) +
            BigEndian.u16(internalPort) + BigEndian.u16(suggestedExternalPort) + BigEndian.u32(lifetime)

    /** Null if [bytes] isn't a NAT-PMP reply to opcode 0. */
    fun parseAddressReply(bytes: ByteArray): AddressReply? {
        if (bytes.size < 8 || !isReplyTo(bytes, OP_EXTERNAL_ADDRESS)) return null
        val result = BigEndian.readU16(bytes, 2)
        val epoch = BigEndian.readU32(bytes, 4)
        if (result != RESULT_SUCCESS) return AddressReply(result, epoch, null)
        if (bytes.size < 12) return null
        return AddressReply(result, epoch, InetAddress.getByAddress(bytes.copyOfRange(8, 12)) as Inet4Address)
    }

    /** Null if [bytes] isn't a NAT-PMP reply to a TCP mapping request. */
    fun parseMapReply(bytes: ByteArray): MapReply? {
        if (bytes.size < 8 || !isReplyTo(bytes, OP_MAP_TCP)) return null
        val result = BigEndian.readU16(bytes, 2)
        val epoch = BigEndian.readU32(bytes, 4)
        if (result != RESULT_SUCCESS) return MapReply(result, epoch, 0, 0, 0)
        if (bytes.size < 16) return null
        return MapReply(result, epoch, BigEndian.readU16(bytes, 8), BigEndian.readU16(bytes, 10), BigEndian.readU32(bytes, 12))
    }

    private fun isReplyTo(bytes: ByteArray, op: Int): Boolean =
        bytes[0].toInt() == VERSION && (bytes[1].toInt() and 0xff) == 128 + op
}

/** Asks one router over NAT-PMP. Every call blocks for at most about 4 s ([TRIES]). */
class NatPmpClient(private val udp: RouterUdp, private val now: () -> Long) {

    /** The router's external IPv4 address; null if it doesn't speak NAT-PMP or refuses. */
    fun externalAddress(): Inet4Address? =
        udp.ask(NatPmp.externalAddressRequest(), TRIES, now) { NatPmp.parseAddressReply(it) }
            ?.takeIf { it.result == NatPmp.RESULT_SUCCESS }?.external

    /** Maps TCP [internalPort]; null if refused or unanswered. A granted lifetime of 0 counts as refused. */
    fun map(internalPort: Int, suggestedExternalPort: Int, lifetime: Long): NatPmp.MapReply? =
        udp.ask(NatPmp.mapRequest(internalPort, suggestedExternalPort, lifetime), TRIES, now) { bytes ->
            NatPmp.parseMapReply(bytes)?.takeIf { it.result != NatPmp.RESULT_SUCCESS || it.internalPort == internalPort }
        }?.takeIf { it.result == NatPmp.RESULT_SUCCESS && it.lifetime > 0 && it.externalPort > 0 }

    /** Deletes the TCP mapping for [internalPort] (lifetime 0, suggested external port 0). True if the router agreed. */
    fun delete(internalPort: Int): Boolean =
        udp.ask(NatPmp.mapRequest(internalPort, 0, 0), TRIES, now) { bytes ->
            NatPmp.parseMapReply(bytes)?.takeIf { it.result != NatPmp.RESULT_SUCCESS || it.internalPort == internalPort }
        }?.result == NatPmp.RESULT_SUCCESS
}
