package app.pairline.android.link.remote.relay

import app.pairline.android.link.remote.router.Addresses
import app.pairline.android.link.remote.router.V4Kind
import app.pairline.android.protocol.B64u
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.Hex
import app.pairline.android.protocol.RequestException
import java.net.Inet6Address
import java.net.NetworkInterface
import org.json.JSONObject

/**
 * The user's own server (D-106; PROTOCOL.md "Your own server (relay, D-106)"), as the iPhone set it with
 * `remote.relay`: where it is ([host], [port]), the SHA-256 of its certificate ([fingerprint]) that pins it, its access
 * key ([key], a secret) and this pairing's rendezvous id there ([rendezvous]). Never logged: [toString] leaves
 * everything out.
 */
class RelayConfig(
    /** A DNS name (lowercase), an IPv4 address, or an IPv6 address without brackets. */
    val host: String,
    val port: Int,
    val fingerprint: ByteArray,
    val key: ByteArray,
    val rendezvous: ByteArray,
) {
    /** The 54-byte preamble for [role]: `PLRY`, version 1, the role, the access key and the rendezvous id. */
    fun preamble(role: Byte = ROLE_BRIDGE): ByteArray =
        MAGIC + byteArrayOf(VERSION, role) + key + rendezvous

    /** As stored on this phone (inside the Keystore-wrapped blob) and as `remote.relay` carries it. */
    fun toJson(): JSONObject = JSONObject()
        .put("host", host)
        .put("port", port)
        .put("fp", B64u.encode(fingerprint))
        .put("k", B64u.encode(key))
        .put("rv", Hex.encode(rendezvous))

    override fun equals(other: Any?): Boolean = other is RelayConfig && host == other.host && port == other.port &&
        fingerprint.contentEquals(other.fingerprint) && key.contentEquals(other.key) &&
        rendezvous.contentEquals(other.rendezvous)

    override fun hashCode(): Int = host.hashCode() * 31 + port

    override fun toString(): String = "RelayConfig(…)"

    companion object {
        val MAGIC = "PLRY".toByteArray(Charsets.US_ASCII)
        const val VERSION: Byte = 0x01
        const val ROLE_BRIDGE: Byte = 0x42
        const val ROLE_REMOTE: Byte = 0x52
        const val PREAMBLE_LENGTH = 54

        /**
         * `remote.relay`'s `relay`: null for JSON null (no relay), otherwise the checked config. Throws
         * [RequestException] (`invalid`) for a missing field or anything malformed. Release builds take only a DNS
         * name, a public IPv4 or a global IPv6 address; [debugBuild] also takes loopback and private addresses, so the
         * emulator can reach a relay on the development Mac.
         */
        fun parse(raw: Any?, debugBuild: Boolean): RelayConfig? {
            if (raw == null) invalid("relay missing")
            if (raw == JSONObject.NULL) return null
            val o = raw as? JSONObject ?: invalid("relay is not an object")
            val host = (o.opt("host") as? String)?.let { checkHost(it, debugBuild) } ?: invalid("bad host")
            // A JSON whole number only: not a string, a fraction or a boolean.
            val rawPort: Any? = o.opt("port")
            val port = (if (rawPort is Int) rawPort.toLong() else rawPort as? Long)
                ?.takeIf { it in 1L..65535L }?.toInt() ?: invalid("bad port")
            val fp = (o.opt("fp") as? String)?.let(::key32) ?: invalid("bad fp")
            val k = (o.opt("k") as? String)?.let(::key32) ?: invalid("bad k")
            val rv = (o.opt("rv") as? String)?.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
                ?.let(Hex::decode) ?: invalid("bad rv")
            return RelayConfig(host, port, fp, k, rv)
        }

        /** Read back from this phone's storage: checked again; null when no longer valid. */
        fun fromStored(json: JSONObject, debugBuild: Boolean): RelayConfig? =
            try {
                parse(json, debugBuild)
            } catch (_: RequestException) {
                null
            }

        /** Exactly 32 bytes as base64url without padding (43 characters, canonical). */
        private fun key32(text: String): ByteArray? {
            if (text.length != 43 || !text.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) return null
            val bytes = B64u.decodeOrNull(text) ?: return null
            return bytes.takeIf { it.size == 32 && B64u.encode(it) == text }
        }

        /**
         * The host as the link's rules have it (PROTOCOL.md): an IPv6 address (no brackets, no zone) that's global; a
         * strictly dotted IPv4 address that's public; or a DNS name of letters, digits and hyphens per label, at least
         * two labels, at most 253 characters, the last label not all digits and not a local name. Names are kept
         * lowercase. Never resolves anything.
         */
        fun checkHost(raw: String, debugBuild: Boolean): String? {
            if (raw.isEmpty() || raw.length > 253) return null
            if (':' in raw) {
                val address = parseV6(raw) ?: return null
                return Addresses.v6Text(address).takeIf { isGlobalV6(address) || (debugBuild && isLocal(address)) }
            }
            val labels = raw.split('.')
            if (labels.size == 4 && labels.all { l -> l.isNotEmpty() && l.all { it in '0'..'9' } }) {
                val address = Addresses.parseV4(raw) ?: return null
                val kind = Addresses.classifyV4(address)
                val ok = kind == V4Kind.PUBLIC || (debugBuild && (kind == V4Kind.PRIVATE || address.isLoopbackAddress))
                return Addresses.v4Text(address).takeIf { ok }
            }
            if (labels.size < 2) return null
            val valid = labels.all { label ->
                label.length in 1..63 && !label.startsWith('-') && !label.endsWith('-') &&
                    label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
            }
            val last = labels.last().lowercase()
            if (!valid || last.all { it in '0'..'9' } || last in LOCAL_NAMES) return null
            return raw.lowercase()
        }

        private val LOCAL_NAMES = setOf("local", "localhost", "lan", "home", "internal")

        /**
         * A global unicast IPv6 address (2000::/3), but not the IETF block 2001::/23, 6to4 (2002::/16) or
         * documentation (2001:db8::/32, 3fff::/20), as for `remote.addr` and the iPhone's checks.
         */
        fun isGlobalV6(address: Inet6Address): Boolean {
            val b = address.address
            val b0 = b[0].toInt() and 0xff
            val b1 = b[1].toInt() and 0xff
            val b2 = b[2].toInt() and 0xff
            val b3 = b[3].toInt() and 0xff
            if (b0 and 0xe0 != 0x20) return false
            return when {
                b0 == 0x20 && b1 == 0x01 -> b2 >= 0x02 && !(b2 == 0x0d && b3 == 0xb8)
                b0 == 0x20 && b1 == 0x02 -> false
                b0 == 0x3f && b1 == 0xff -> b2 and 0xf0 != 0
                else -> true
            }
        }

        private fun isLocal(address: Inet6Address): Boolean {
            val b0 = address.address[0].toInt() and 0xff
            return address.isLoopbackAddress || address.isLinkLocalAddress || b0 and 0xfe == 0xfc
        }

        /**
         * An IPv6 address in text (RFC 4291 section 2.2: groups of 1–4 hex digits, one "::", an IPv4 tail), without
         * brackets or a zone; null for anything else. Parsed here rather than by `InetAddress.getByName`, which may
         * look a name up in DNS.
         */
        fun parseV6(text: String): Inet6Address? {
            if (text.length > 45 || !text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return null
            val halves = text.split("::")
            if (halves.size > 2) return null
            fun groups(part: String, allowV4: Boolean): List<Int>? {
                if (part.isEmpty()) return emptyList()
                val items = part.split(':')
                val out = ArrayList<Int>()
                for ((i, item) in items.withIndex()) {
                    if (allowV4 && i == items.lastIndex && '.' in item) {
                        val v4 = Addresses.parseV4(item) ?: return null
                        val a = v4.address
                        out += ((a[0].toInt() and 0xff) shl 8) or (a[1].toInt() and 0xff)
                        out += ((a[2].toInt() and 0xff) shl 8) or (a[3].toInt() and 0xff)
                    } else {
                        if (item.isEmpty() || item.length > 4 || '.' in item) return null
                        out += item.toInt(16)
                    }
                }
                return out
            }
            val head = groups(halves[0], allowV4 = halves.size == 1) ?: return null
            val tail = if (halves.size == 2) groups(halves[1], allowV4 = true) ?: return null else emptyList()
            val all = if (halves.size == 2) {
                if (head.size + tail.size > 7) return null
                head + List(8 - head.size - tail.size) { 0 } + tail
            } else {
                head
            }
            if (all.size != 8) return null
            val bytes = ByteArray(16)
            all.forEachIndexed { i, g ->
                bytes[2 * i] = (g ushr 8).toByte()
                bytes[2 * i + 1] = g.toByte()
            }
            return Inet6Address.getByAddress(null, bytes, null as NetworkInterface?)
        }

        private fun invalid(msg: String): Nothing = throw RequestException(ErrorCode.INVALID, msg)
    }
}
