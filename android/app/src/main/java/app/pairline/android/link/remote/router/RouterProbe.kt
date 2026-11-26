package app.pairline.android.link.remote.router

/**
 * What the router said when asked read-only ("Check my home connection", `remote.check`): [protocol], the one a
 * mapping would go through (NAT-PMP, then PCP, then UPnP IGD, as [PortMapper] tries them), or null when none answered;
 * and [external], the kind of internet address the router reported, or null when no answer carried one (PCP's
 * ANNOUNCE doesn't).
 */
class ProbeResult(val protocol: MapProtocol?, val external: V4Kind?)

/**
 * Asks the home router, without changing anything on it, whether it could open a port for the direct connection:
 * NAT-PMP's external-address request (opcode 0), PCP's ANNOUNCE, and UPnP IGD's discovery and GetExternalIPAddress.
 * It never creates, renews or deletes a mapping (like `protocol/tools/router_check.py`). Talks only to [network]'s
 * gateway and, for UPnP, to LAN addresses next to it. Blocks for up to about 15 s when nothing answers.
 */
class RouterProbe(private val io: RouterIo, private val network: RouterNetwork) {
    fun probe(): ProbeResult {
        val gateway = network.gatewayV4
        val local = network.localV4
        if (gateway == null || local == null) return ProbeResult(null, null)
        var pcp = false
        io.udp(local, gateway).use { udp ->
            NatPmpClient(udp, io::now).externalAddress()?.let { return ProbeResult(MapProtocol.NAT_PMP, Addresses.classifyV4(it)) }
            pcp = PcpClient(udp, local, io::now).announce()
        }
        // UPnP also tells the address when only PCP answered, which shows carrier-grade NAT before a mapping is tried.
        val found = UpnpClient(io, network).discover()
        return when {
            pcp -> ProbeResult(MapProtocol.PCP, found?.external?.let(Addresses::classifyV4))
            found != null -> ProbeResult(MapProtocol.UPNP, Addresses.classifyV4(found.external))
            else -> ProbeResult(null, null)
        }
    }
}
