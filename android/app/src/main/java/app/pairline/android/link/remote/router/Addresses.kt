package app.pairline.android.link.remote.router

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** What kind of IPv4 address the router reports as its internet address (D-080). */
enum class V4Kind {
    /** Reachable from the internet: a mapping on this router helps. */
    PUBLIC,

    /** RFC 1918: another NAT sits in front of this router ("double NAT"). */
    PRIVATE,

    /** 100.64.0.0/10 (RFC 6598): the internet provider's own NAT, which nobody at home can open. */
    CARRIER_NAT,

    /** 0.0.0.0/8: the router has no internet address right now. */
    UNSPECIFIED,

    /** Loopback, link-local, multicast, documentation, benchmarking and other reserved ranges. */
    RESERVED,
}

/** Address rules and formats for remote mode's direct connection. Pure functions; no lookups. */
object Addresses {

    fun classifyV4(address: Inet4Address): V4Kind {
        val b = address.address
        val a0 = b[0].toInt() and 0xff
        val a1 = b[1].toInt() and 0xff
        val a2 = b[2].toInt() and 0xff
        return when {
            a0 == 0 -> V4Kind.UNSPECIFIED
            a0 == 10 || (a0 == 172 && a1 in 16..31) || (a0 == 192 && a1 == 168) -> V4Kind.PRIVATE
            a0 == 100 && a1 in 64..127 -> V4Kind.CARRIER_NAT
            a0 == 127 || (a0 == 169 && a1 == 254) || a0 >= 224 -> V4Kind.RESERVED
            a0 == 192 && a1 == 0 && (a2 == 0 || a2 == 2) -> V4Kind.RESERVED // IETF assignments, TEST-NET-1
            a0 == 198 && (a1 == 18 || a1 == 19) -> V4Kind.RESERVED // benchmarking
            a0 == 198 && a1 == 51 && a2 == 100 -> V4Kind.RESERVED // TEST-NET-2
            a0 == 203 && a1 == 0 && a2 == 113 -> V4Kind.RESERVED // TEST-NET-3
            else -> V4Kind.PUBLIC
        }
    }

    /** A global unicast IPv6 address (2000::/3), not documentation (2001:db8::/32) or Teredo (2001::/32). */
    fun isGlobalV6(address: InetAddress): Boolean {
        if (address !is Inet6Address) return false
        val b = address.address
        val first = b[0].toInt() and 0xff
        if (first and 0xe0 != 0x20) return false
        val prefix32 = (first shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
        return prefix32 != 0x20010db8 && prefix32 != 0x20010000
    }

    /** Whether [a] and [b] share the first [prefix] bits (both IPv4 or both IPv6). */
    fun sameSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
        val x = a.address
        val y = b.address
        if (x.size != y.size || prefix < 0 || prefix > x.size * 8) return false
        for (bit in 0 until prefix) {
            val mask = 0x80 ushr (bit % 8)
            if ((x[bit / 8].toInt() and mask) != (y[bit / 8].toInt() and mask)) return false
        }
        return true
    }

    /**
     * Strictly four dotted decimal parts, 0–255, without leading zeros (so "010" can't mean octal somewhere else);
     * null for anything else. Never resolves a name.
     */
    fun parseV4(text: String): Inet4Address? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toInt()
            if (value > 255) return null
            bytes[i] = value.toByte()
        }
        return InetAddress.getByAddress(bytes) as Inet4Address
    }

    /** "a.b.c.d:port" as in `remote.addr`. */
    fun formatV4(address: Inet4Address, port: Int): String = "${v4Text(address)}:$port"

    /** "[2001:db8::5]:port" as in `remote.addr`: RFC 5952 text, no zone. */
    fun formatV6(address: Inet6Address, port: Int): String = "[${v6Text(address)}]:$port"

    fun v4Text(address: Inet4Address): String = address.address.joinToString(".") { (it.toInt() and 0xff).toString() }

    /** RFC 5952: lowercase, no leading zeros, the longest run of two or more zero groups (the first if tied) as "::". */
    fun v6Text(address: Inet6Address): String {
        val b = address.address
        val groups = IntArray(8) { ((b[2 * it].toInt() and 0xff) shl 8) or (b[2 * it + 1].toInt() and 0xff) }
        var bestStart = -1
        var bestLength = 1
        var i = 0
        while (i < 8) {
            if (groups[i] != 0) {
                i++
                continue
            }
            val start = i
            while (i < 8 && groups[i] == 0) i++
            if (i - start > bestLength) {
                bestStart = start
                bestLength = i - start
            }
        }
        if (bestStart < 0) return groups.joinToString(":") { Integer.toHexString(it) }
        val head = (0 until bestStart).joinToString(":") { Integer.toHexString(groups[it]) }
        val tail = (bestStart + bestLength until 8).joinToString(":") { Integer.toHexString(groups[it]) }
        return "$head::$tail"
    }

    /**
     * For the log (AGENTS.md rule 9): only the last part of a LAN address ("….1"), never a whole one. Internet
     * addresses (this home's or the iPhone's) are never logged at all.
     */
    fun lanHint(address: InetAddress): String = when (address) {
        is Inet4Address -> "…." + (address.address[3].toInt() and 0xff)
        else -> "…:" + Integer.toHexString(
            ((address.address[14].toInt() and 0xff) shl 8) or (address.address[15].toInt() and 0xff),
        )
    }
}
