package app.pairline.android.link.remote

import app.pairline.android.link.remote.router.MapProtocol
import app.pairline.android.link.remote.router.RouterProbe
import app.pairline.android.link.remote.router.V4Kind
import app.pairline.android.log.Log
import app.pairline.android.protocol.Messages
import org.json.JSONObject

/**
 * "Check my home connection" (`remote.check`, PROTOCOL.md "Remote messages", D-092): whether remote mode's direct
 * connection could work on this phone's network, found by asking the router read-only ([RouterProbe]). Nothing is
 * opened, and it works whether remote mode is on or off. Never logs or sends an address.
 */
object HomeCheck {
    /**
     * [likely]: a router protocol answered and the internet address it reported (if any) is public. [reason]: why
     * not, as `remote.state`'s codes. [router]: the protocol that answered. [ipv6]: this phone has a global IPv6
     * address, which can work when the router lets connections in.
     */
    class Result(val likely: Boolean, val reason: DirectReason?, val router: MapProtocol?, val ipv6: Boolean)

    /** [network]: this phone's Wi-Fi network now, or null without one. Blocks for seconds. */
    fun run(network: DirectLink.Network?): Result {
        val result = if (network == null) {
            Result(false, DirectReason.NOT_ON_WIFI, null, false)
        } else {
            val probe = RouterProbe(network.io, network.info).probe()
            val reason = when {
                probe.protocol == null -> DirectReason.NO_MAPPING
                probe.external == null || probe.external == V4Kind.PUBLIC -> null
                probe.external == V4Kind.CARRIER_NAT -> DirectReason.CARRIER_NAT
                probe.external == V4Kind.PRIVATE -> DirectReason.DOUBLE_NAT
                else -> DirectReason.NO_PUBLIC_ADDRESS
            }
            Result(reason == null, reason, probe.protocol, network.info.globalV6 != null)
        }
        Log.i(TAG, describe(result))
        return result
    }

    /** The `remote.check` reply. */
    fun reply(id: Long, result: Result): JSONObject = Messages.remoteCheck(
        id,
        direct = if (result.likely) "likely" else "unlikely",
        reason = result.reason?.wire,
        router = result.router?.wire,
        ipv6 = result.ipv6,
    )

    /** For the log: what answered and the verdict, never an address. */
    fun describe(result: Result): String = buildString {
        append("home connection check: ")
        append(if (result.likely) "a direct connection is likely" else "a direct connection is unlikely")
        result.router?.let { append(" (router answers ").append(it.label).append(')') }
        result.reason?.let { append("; ").append(it.wire) }
        if (result.ipv6) append("; this phone has IPv6")
    }

    private const val TAG = "direct"
}
