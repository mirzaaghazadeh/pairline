package app.pairline.android.protocol

import org.json.JSONException
import org.json.JSONObject

/**
 * Where the iPhone can reach the bridge over Wi-Fi (M5): local addresses and the TCP port, plus the hotspot's name
 * and passphrase while the bridge's own hotspot runs. Optional parts of the pair link and `wifi.offer`.
 */
data class WifiHint(
    val addresses: List<String>,
    val port: Int,
    val ssid: String? = null,
    val password: String? = null,
)

/**
 * `pairline://pair?v=0&id=<device id>&k=<identity public key>&s=<pairing secret>` (PROTOCOL.md, "Pairing"), with the
 * optional Wi-Fi parameters `ip`, `port`, `ssid`, `pw` (M5).
 */
class PairingUri(
    val deviceId: ByteArray,
    val publicKey: ByteArray,
    val secret: ByteArray,
    val wifi: WifiHint? = null,
) {
    init {
        require(deviceId.size == Proto.DEVICE_ID_LENGTH)
        require(publicKey.size == Proto.PUBLIC_KEY_LENGTH)
        require(secret.size == Proto.PAIRING_SECRET_LENGTH)
    }

    /** Contains the pairing secret (and maybe the hotspot passphrase): show it only as a QR code and never log it. */
    fun encode(): String {
        val out = StringBuilder(
            "$SCHEME://pair?v=${Proto.VERSION}&id=${B64u.encode(deviceId)}&k=${B64u.encode(publicKey)}&s=${B64u.encode(secret)}",
        )
        val w = wifi
        if (w != null && w.addresses.isNotEmpty() && w.port in 1..65535) {
            out.append("&ip=").append(w.addresses.take(MAX_ADDRESSES).joinToString(","))
            out.append("&port=").append(w.port)
            if (w.ssid != null && w.password != null) {
                out.append("&ssid=").append(B64u.encode(w.ssid.toByteArray(Charsets.UTF_8)))
                out.append("&pw=").append(B64u.encode(w.password.toByteArray(Charsets.UTF_8)))
            }
        }
        return out.toString()
    }

    companion object {
        const val SCHEME = "pairline"
        const val MAX_ADDRESSES = 4

        fun parse(uri: String): PairingUri? {
            val prefix = "$SCHEME://pair?"
            if (!uri.startsWith(prefix)) return null
            val params = uri.removePrefix(prefix).split('&').mapNotNull {
                val eq = it.indexOf('=')
                if (eq <= 0) null else it.substring(0, eq) to it.substring(eq + 1)
            }.toMap()
            if (params["v"] != Proto.VERSION.toString()) return null
            val id = params["id"]?.let(B64u::decodeOrNull) ?: return null
            val key = params["k"]?.let(B64u::decodeOrNull) ?: return null
            val secret = params["s"]?.let(B64u::decodeOrNull) ?: return null
            if (id.size != Proto.DEVICE_ID_LENGTH || key.size != Proto.PUBLIC_KEY_LENGTH ||
                secret.size != Proto.PAIRING_SECRET_LENGTH
            ) {
                return null
            }
            return PairingUri(id, key, secret, parseWifi(params))
        }

        /** The optional Wi-Fi parameters; anything malformed is dropped, never fatal (old links stay valid). */
        private fun parseWifi(params: Map<String, String>): WifiHint? {
            val addresses = params["ip"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.take(MAX_ADDRESSES).orEmpty()
            val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 }
            if (addresses.isEmpty() || port == null) return null
            val ssid = params["ssid"]?.let(B64u::decodeOrNull)?.toString(Charsets.UTF_8)
            val password = params["pw"]?.let(B64u::decodeOrNull)?.toString(Charsets.UTF_8)
            return WifiHint(addresses, port, ssid?.takeIf { password != null }, password?.takeIf { ssid != null })
        }
    }
}

object PairingCrypto {
    /** T = "pairline-pair-v0" ‖ pubB ‖ pubR ‖ nonceR. */
    fun transcript(bridgePublic: ByteArray, remotePublic: ByteArray, remoteNonce: ByteArray): ByteArray =
        Proto.PAIR_LABEL + bridgePublic + remotePublic + remoteNonce

    /** macR = HMAC-SHA256(ps, T). */
    fun remoteMac(secret: ByteArray, transcript: ByteArray): ByteArray = HmacSha256.mac(secret, transcript)

    /** macB = HMAC-SHA256(ps, T ‖ nonceB ‖ "B"). */
    fun bridgeMac(secret: ByteArray, transcript: ByteArray, bridgeNonce: ByteArray): ByteArray =
        HmacSha256.mac(secret, transcript, bridgeNonce, byteArrayOf(Proto.ROLE_BRIDGE))
}

/** `{"t":"pair_hello","v":0,"k":<pubR>,"n":<nonceR>,"mac":<macR>}` */
class PairHello(val remotePublicKey: ByteArray, val nonce: ByteArray, val mac: ByteArray) {

    fun toJson(): JSONObject = JSONObject()
        .put("t", TYPE)
        .put("v", Proto.VERSION)
        .put("k", B64u.encode(remotePublicKey))
        .put("n", B64u.encode(nonce))
        .put("mac", B64u.encode(mac))

    companion object {
        const val TYPE = "pair_hello"

        fun parse(body: ByteArray): PairHello {
            val json = parseJsonObject(body)
            if (json.optString("t") != TYPE) throw ProtocolException("not a pair_hello")
            if (json.optInt("v", -1) != Proto.VERSION) throw ProtocolException("unsupported protocol version")
            val k = B64u.decodeOrNull(json.optString("k"))
            val n = B64u.decodeOrNull(json.optString("n"))
            val mac = B64u.decodeOrNull(json.optString("mac"))
            if (k == null || k.size != Proto.PUBLIC_KEY_LENGTH) throw ProtocolException("bad pair_hello key")
            if (n == null || n.size != Proto.PAIRING_NONCE_LENGTH) throw ProtocolException("bad pair_hello nonce")
            if (mac == null || mac.size != Proto.MAC_LENGTH) throw ProtocolException("bad pair_hello mac")
            return PairHello(k, n, mac)
        }
    }
}

/** `{"t":"pair_ok","v":0,"n":<nonceB>,"mac":<macB>}` */
class PairOk(val nonce: ByteArray, val mac: ByteArray) {
    fun toJson(): JSONObject = JSONObject()
        .put("t", "pair_ok")
        .put("v", Proto.VERSION)
        .put("n", B64u.encode(nonce))
        .put("mac", B64u.encode(mac))
}

enum class PairFailReason(val wire: String) {
    EXPIRED("expired"),
    USED("used"),
    BAD_MAC("bad_mac"),
    BUSY("busy"),
}

fun pairFailJson(reason: PairFailReason): JSONObject = JSONObject()
    .put("t", "pair_fail")
    .put("v", Proto.VERSION)
    .put("reason", reason.wire)

internal fun parseJsonObject(body: ByteArray): JSONObject = try {
    JSONObject(String(body, Charsets.UTF_8))
} catch (_: JSONException) {
    throw ProtocolException("body is not a JSON object")
}

internal fun JSONObject.toUtf8(): ByteArray = toString().toByteArray(Charsets.UTF_8)
