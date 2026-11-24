package app.pairline.android.link.wifi

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Which addresses the Wi-Fi link may use (PROTOCOL.md, "Wi-Fi"; AGENTS.md rule 2). The bridge listens only on, and
 * accepts only peers from, the local network: private IPv4 (RFC 1918), link-local IPv4 (169.254/16), unique-local
 * IPv6 (fc00::/7) and link-local IPv6 (fe80::/10). Never loopback, carrier-grade NAT (100.64/10) or public addresses.
 */
object LocalAddresses {
    fun isLocal(address: InetAddress): Boolean {
        val b = address.address
        return when (address) {
            is Inet4Address -> isLocalV4(b)
            is Inet6Address -> {
                // An IPv4-mapped IPv6 address (::ffff:a.b.c.d) is judged by its IPv4 part.
                if (isV4Mapped(b)) return isLocalV4(b.copyOfRange(12, 16))
                val first = b[0].toInt() and 0xff
                val second = b[1].toInt() and 0xff
                (first and 0xfe) == 0xfc || (first == 0xfe && (second and 0xc0) == 0x80)
            }
            else -> false
        }
    }

    /** Addresses we put in the pair link and `wifi.offer`: local ones the iPhone can use without a zone index. */
    fun isAdvertisable(address: InetAddress): Boolean =
        isLocal(address) && !(address is Inet6Address && address.isLinkLocalAddress)

    /** The address as the pair link and `wifi.offer` carry it: no brackets, no zone. */
    fun format(address: InetAddress): String {
        val text = address.hostAddress ?: return ""
        return text.substringBefore('%')
    }

    private fun isLocalV4(b: ByteArray): Boolean {
        val a0 = b[0].toInt() and 0xff
        val a1 = b[1].toInt() and 0xff
        return a0 == 10 ||
            (a0 == 172 && a1 in 16..31) ||
            (a0 == 192 && a1 == 168) ||
            (a0 == 169 && a1 == 254)
    }

    private fun isV4Mapped(b: ByteArray): Boolean {
        for (i in 0 until 10) if (b[i].toInt() != 0) return false
        return (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff
    }
}

/**
 * Interface names that belong to mobile data or VPNs. The listener never binds on them, even when they carry a
 * private address (some carriers hand out 10.x addresses).
 */
object InterfaceNames {
    private val mobileOrVpn = Regex("^(rmnet|ccmni|pdp|seth|v4-|clat|tun|ppp|ipsec|wwan|ccinet|rev_rmnet).*")

    fun looksMobileOrVpn(name: String): Boolean = mobileOrVpn.matches(name)
}

/** Debug-only switches, set from `src/debug` (D-018). Release builds never change them. */
object WifiDebug {
    /**
     * The Android emulator's NAT (`adb emu redir`) delivers to its emulated mobile-data interface. A debug build on
     * an emulator turns this on so the Simulator can reach the Wi-Fi link; never on a phone, never in release.
     */
    @Volatile
    var allowEmulatorMobileInterface: Boolean = false
}
