package app.pairline.android.link.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.pairline.android.link.remote.router.Addresses
import app.pairline.android.link.remote.router.AndroidRouterIo
import app.pairline.android.link.remote.router.RouterNetwork
import app.pairline.android.link.wifi.LocalAddresses
import app.pairline.android.log.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Tells [DirectLink] which Wi-Fi (or Ethernet) network the phone is on, read from Android's LinkProperties: the
 * default IPv4 gateway, this phone's private IPv4 address, the IPv6 default router and a global IPv6 address. Mobile
 * data and VPNs are never used: without Wi-Fi it reports null.
 */
class DirectNetworkWatcher(context: Context, private val onChange: (DirectLink.Network?) -> Unit) {
    private val cm: ConnectivityManager? = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** Addresses and routes come with LinkProperties; capability changes (signal strength…) don't matter here. */
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh(null)
        override fun onLost(network: Network) = refresh(network)
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = refresh(null)
    }

    fun start() {
        try {
            cm?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build(),
                callback,
            )
        } catch (e: Exception) {
            Log.w(TAG, "can't watch Wi-Fi networks", e)
        }
        refresh(null)
    }

    fun stop() {
        runCatching { cm?.unregisterNetworkCallback(callback) }
    }

    private fun refresh(lost: Network?) {
        onChange(try {
            current(lost)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't read the Wi-Fi network", e)
            null
        })
    }

    private fun current(lost: Network?): DirectLink.Network? {
        val cm = cm ?: return null
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            if (network == lost) continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!wifi || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val lp = cm.getLinkProperties(network) ?: continue
            return DirectLink.Network(read(lp), AndroidRouterIo(network))
        }
        return null
    }

    private fun read(lp: LinkProperties): RouterNetwork {
        val v4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address && LocalAddresses.isLocal(it.address) }
        val defaults = lp.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.takeUnless(InetAddress::isAnyLocalAddress) }
        return RouterNetwork(
            gatewayV4 = defaults.firstOrNull { it is Inet4Address } as Inet4Address?,
            localV4 = v4?.address as Inet4Address?,
            prefixV4 = v4?.prefixLength ?: 24,
            gatewayV6 = (defaults.firstOrNull { it is Inet6Address } as Inet6Address?)?.let { withZone(it, lp.interfaceName) },
            globalV6 = DirectNetworks.chooseGlobalV6(lp.linkAddresses.map { it.address to it.flags }),
        )
    }

    /** The IPv6 router is usually link-local (fe80::…), which can only be reached with its interface as the zone. */
    private fun withZone(gateway: Inet6Address, interfaceName: String?): Inet6Address {
        if (!gateway.isLinkLocalAddress || gateway.scopeId != 0 || interfaceName == null) return gateway
        val nif = runCatching { NetworkInterface.getByName(interfaceName) }.getOrNull() ?: return gateway
        return Inet6Address.getByAddress(null, gateway.address, nif)
    }

    private companion object {
        const val TAG = "direct"
    }
}

/** Pure helpers for reading the network, tested on the JVM. */
object DirectNetworks {
    /** Linux address flags (`IFA_F_*`), as LinkAddress reports them. */
    private const val IFA_F_TEMPORARY = 0x01
    private const val IFA_F_DEPRECATED = 0x20
    private const val IFA_F_TENTATIVE = 0x40

    /**
     * The global IPv6 address to listen on: not deprecated or still being checked, and a stable one before a temporary
     * (privacy) one, since it changes less often. Null without one.
     */
    fun chooseGlobalV6(addresses: List<Pair<InetAddress, Int>>): Inet6Address? = addresses
        .filter { (address, flags) -> Addresses.isGlobalV6(address) && flags and (IFA_F_DEPRECATED or IFA_F_TENTATIVE) == 0 }
        .sortedBy { (_, flags) -> if (flags and IFA_F_TEMPORARY != 0) 1 else 0 }
        .firstOrNull()?.first as Inet6Address?
}
