package app.pairline.android.link.remote.router

import app.pairline.android.log.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom

enum class MapProtocol(val label: String) { NAT_PMP("NAT-PMP"), PCP("PCP"), UPNP("UPnP") }

/**
 * One TCP port opened on the router: [external]:[externalPort] reaches this phone's [internal]:[internalPort]. For an
 * IPv6 pinhole both addresses are usually this phone's own. [lifetime] is what the router granted, in seconds; 0 means
 * permanent (a UPnP router that allows nothing else), which is deleted all the same when remote mode ends.
 */
class Mapping(
    val protocol: MapProtocol,
    val internal: InetAddress,
    val internalPort: Int,
    val external: InetAddress,
    val externalPort: Int,
    val lifetime: Long,
    val grantedAt: Long,
    /** PCP: the nonce that renews and deletes this mapping. */
    val nonce: ByteArray? = null,
    /** UPnP: the service the mapping was added through. */
    val upnp: UpnpService? = null,
) {
    /** Renew at half the granted lifetime (RFC 6886); look at a permanent one again every hour. */
    val renewAt: Long get() = grantedAt + if (lifetime > 0) lifetime * 1000 / 2 else PortMapper.PERMANENT_CHECK_MS

    val isV6: Boolean get() = internal is Inet6Address
}

/** How mapping the IPv4 port went. */
sealed interface V4Result {
    class Mapped(val mapping: Mapping) : V4Result

    /** The router's internet address isn't public ([kind]): no mapping on it can help. Nothing was left open. */
    class NotPublic(val kind: V4Kind) : V4Result

    /** No protocol answered, or every one refused. */
    data object NoMapping : V4Result
}

/**
 * Opens one TCP port on the home router for remote mode's direct connection (D-072): NAT-PMP, then PCP, then UPnP IGD;
 * the first that maps wins. When the router's own internet address isn't public (carrier-grade NAT, double NAT), no
 * mapping is made and that's reported instead. For IPv6, PCP opens a pinhole for this phone's global address.
 * Talks only to [network]'s gateway and, for UPnP, to LAN addresses next to it. Every call blocks (seconds).
 */
class PortMapper(
    private val io: RouterIo,
    private val network: RouterNetwork,
    private val random: SecureRandom = SecureRandom(),
) {
    fun mapV4(port: Int, lifetime: Long = LIFETIME_S): V4Result {
        val gateway = network.gatewayV4 ?: return V4Result.NoMapping
        val local = network.localV4 ?: return V4Result.NoMapping
        io.udp(local, gateway).use { udp ->
            val natPmp = NatPmpClient(udp, io::now)
            val external = natPmp.externalAddress()
            if (external != null) {
                val kind = Addresses.classifyV4(external)
                if (kind != V4Kind.PUBLIC) return notPublic(MapProtocol.NAT_PMP, kind)
                natPmp.map(port, port, lifetime)?.let { reply ->
                    return mapped(Mapping(MapProtocol.NAT_PMP, local, port, external, reply.externalPort, reply.lifetime, io.now()))
                }
                Log.i(TAG, "NAT-PMP: the router refused the mapping")
            }
            val nonce = ByteArray(Pcp.NONCE_LENGTH).also(random::nextBytes)
            val pcp = PcpClient(udp, local, io::now)
            pcp.map(nonce, port, port, lifetime)?.let { reply ->
                val kind = (reply.external as? Inet4Address)?.let(Addresses::classifyV4) ?: V4Kind.UNSPECIFIED
                if (kind != V4Kind.PUBLIC) {
                    pcp.delete(nonce, port)
                    return notPublic(MapProtocol.PCP, kind)
                }
                return mapped(Mapping(MapProtocol.PCP, local, port, reply.external, reply.externalPort, reply.lifetime, io.now(), nonce = nonce))
            }
            pcp.lastResult?.let { Log.i(TAG, "PCP: the router refused the mapping (result $it)") }
        }
        val upnp = UpnpClient(io, network)
        val found = upnp.discover() ?: return V4Result.NoMapping
        val kind = Addresses.classifyV4(found.external)
        if (kind != V4Kind.PUBLIC) return notPublic(MapProtocol.UPNP, kind)
        return addUpnp(upnp, found.service, found.external, local, port, port, lifetime)
    }

    /**
     * Renews [mapping] for another [LIFETIME_S] (UPnP: adds it again with the same lease) and checks the external
     * address again. Null if the router no longer agrees or its address is no longer public: the caller maps afresh.
     */
    fun renew(mapping: Mapping): Mapping? {
        if (mapping.isV6) return pinholeV6(mapping.internalPort, mapping.nonce, mapping.externalPort)
        val gateway = network.gatewayV4 ?: return null
        val local = network.localV4 ?: return null
        return when (mapping.protocol) {
            MapProtocol.NAT_PMP -> io.udp(local, gateway).use { udp ->
                val client = NatPmpClient(udp, io::now)
                val external = client.externalAddress()?.takeIf { Addresses.classifyV4(it) == V4Kind.PUBLIC } ?: return null
                val reply = client.map(mapping.internalPort, mapping.externalPort, LIFETIME_S) ?: return null
                Mapping(MapProtocol.NAT_PMP, local, mapping.internalPort, external, reply.externalPort, reply.lifetime, io.now())
            }
            MapProtocol.PCP -> io.udp(local, gateway).use { udp ->
                val nonce = mapping.nonce ?: return null
                val reply = PcpClient(udp, local, io::now).map(nonce, mapping.internalPort, mapping.externalPort, LIFETIME_S)
                    ?: return null
                val external = (reply.external as? Inet4Address)?.takeIf { Addresses.classifyV4(it) == V4Kind.PUBLIC } ?: return null
                Mapping(MapProtocol.PCP, local, mapping.internalPort, external, reply.externalPort, reply.lifetime, io.now(), nonce = nonce)
            }
            MapProtocol.UPNP -> {
                val service = mapping.upnp ?: return null
                val upnp = UpnpClient(io, network)
                val external = upnp.externalAddress(service)?.takeIf { Addresses.classifyV4(it) == V4Kind.PUBLIC } ?: return null
                (addUpnp(upnp, service, external, local, mapping.internalPort, mapping.externalPort, mapping.lifetime, quiet = true)
                    as? V4Result.Mapped)?.mapping
            }
        }
    }

    /** Deletes [mapping] on the router (lifetime 0, or DeletePortMapping). Best effort; true if the router agreed. */
    fun delete(mapping: Mapping): Boolean {
        val ok = when (mapping.protocol) {
            MapProtocol.NAT_PMP -> {
                val gateway = network.gatewayV4 ?: return false
                io.udp(mapping.internal, gateway).use { NatPmpClient(it, io::now).delete(mapping.internalPort) }
            }
            MapProtocol.PCP -> {
                val gateway = (if (mapping.isV6) network.gatewayV6 else network.gatewayV4) ?: return false
                val nonce = mapping.nonce ?: return false
                io.udp(mapping.internal, gateway).use { PcpClient(it, mapping.internal, io::now).delete(nonce, mapping.internalPort) }
            }
            MapProtocol.UPNP -> {
                val service = mapping.upnp ?: return false
                UpnpClient(io, network).deletePortMapping(service, mapping.externalPort)
            }
        }
        Log.i(TAG, "${mapping.protocol.label}: ${if (ok) "deleted the mapping" else "couldn't delete the mapping; it expires by itself"}")
        return ok
    }

    /**
     * Asks the router's firewall, over PCP, to let connections in to this phone's global IPv6 address on [port]. Null
     * if there's no IPv6 router or it doesn't speak PCP; connections may still get in if it has no firewall.
     */
    fun pinholeV6(port: Int, previousNonce: ByteArray? = null, suggestedPort: Int = port): Mapping? {
        val local = network.globalV6 ?: return null
        val gateway = network.gatewayV6 ?: return null
        val nonce = previousNonce ?: ByteArray(Pcp.NONCE_LENGTH).also(random::nextBytes)
        val reply = io.udp(local, gateway).use { PcpClient(it, local, io::now).map(nonce, port, suggestedPort, LIFETIME_S) } ?: return null
        val external = reply.external.takeIf { Addresses.isGlobalV6(it) } ?: local
        if (previousNonce == null) Log.i(TAG, "PCP: IPv6 firewall opened for TCP $port for ${reply.lifetime} s")
        return Mapping(MapProtocol.PCP, local, port, external, reply.externalPort, reply.lifetime, io.now(), nonce = nonce)
    }

    /** AddPortMapping; a router that only takes permanent leases (725) gets lease 0, one with a clash (718) one other port. */
    private fun addUpnp(
        upnp: UpnpClient,
        service: UpnpService,
        external: Inet4Address,
        local: Inet4Address,
        internalPort: Int,
        externalPort: Int,
        lifetime: Long,
        quiet: Boolean = false,
    ): V4Result {
        var lease = lifetime
        var port = externalPort
        var code = upnp.addPortMapping(service, port, internalPort, local, lease)
        if (code == Upnp.ERROR_ONLY_PERMANENT_LEASES && lease != 0L) {
            lease = 0
            code = upnp.addPortMapping(service, port, internalPort, local, lease)
        }
        if (code == Upnp.ERROR_CONFLICT) {
            port = EPHEMERAL_FIRST + random.nextInt(EPHEMERAL_COUNT)
            code = upnp.addPortMapping(service, port, internalPort, local, lease)
        }
        if (code != 0) {
            if (!quiet) Log.i(TAG, "UPnP: the router refused the mapping (${code ?: "no answer"})")
            return V4Result.NoMapping
        }
        val mapping = Mapping(MapProtocol.UPNP, local, internalPort, external, port, lease, io.now(), upnp = service)
        return if (quiet) V4Result.Mapped(mapping) else mapped(mapping)
    }

    private fun mapped(mapping: Mapping): V4Result.Mapped {
        val lifetime = if (mapping.lifetime == 0L) "permanently (deleted when remote mode ends)" else "for ${mapping.lifetime} s"
        Log.i(TAG, "${mapping.protocol.label}: router ${network.gatewayV4?.hint()} mapped TCP ${mapping.externalPort} to port ${mapping.internalPort} $lifetime")
        return V4Result.Mapped(mapping)
    }

    private fun notPublic(protocol: MapProtocol, kind: V4Kind): V4Result.NotPublic {
        Log.i(TAG, "${protocol.label}: the router's internet address isn't public (${kind.name.lowercase()}); no mapping")
        return V4Result.NotPublic(kind)
    }

    companion object {
        private const val TAG = "direct"

        /** What every mapping asks for, in seconds (2 hours). */
        const val LIFETIME_S = 7_200L

        const val PERMANENT_CHECK_MS = 3_600_000L
        private const val EPHEMERAL_FIRST = 49_152
        private const val EPHEMERAL_COUNT = 16_384
    }
}
