package app.pairline.android.link.remote

import app.pairline.android.link.remote.router.Addresses
import app.pairline.android.link.remote.router.LanUrl
import app.pairline.android.link.remote.router.Mapping
import app.pairline.android.link.remote.router.PortMapper
import app.pairline.android.link.remote.router.RouterIo
import app.pairline.android.link.remote.router.RouterNetwork
import app.pairline.android.link.remote.router.UpnpClient
import app.pairline.android.link.remote.router.UpnpService
import app.pairline.android.link.remote.router.V4Kind
import app.pairline.android.link.remote.router.V4Result
import app.pairline.android.log.Log
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.Messages
import app.pairline.android.protocol.RequestException
import java.net.Inet4Address
import java.net.Inet6Address
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** `remote.state`'s `direct` (PROTOCOL.md, "Remote messages"). */
enum class Direct(val wire: String) { OPEN("open"), NONE("none"), BLOCKED("blocked") }

/**
 * Why there's no confirmed direct connection. [wire] is `remote.state`'s `reason`, a fixed code the iPhone
 * translates; this phone's screen shows its own text for each. [blocks]: nothing can work on this network as it is.
 */
enum class DirectReason(val wire: String, val blocks: Boolean) {
    /** The bridge isn't on Wi-Fi or Ethernet. */
    NOT_ON_WIFI("not_wifi", true),

    /** The router's internet address is carrier-grade NAT (100.64.0.0/10). */
    CARRIER_NAT("cgnat", true),

    /** The router's internet address is private: another router sits in front of it (double NAT). */
    DOUBLE_NAT("no_public_address", true),

    /** The router's internet address is otherwise not public (0.0.0.0, reserved ranges). */
    NO_PUBLIC_ADDRESS("no_public_address", true),

    /** No NAT-PMP, PCP or UPnP mapping could be made. */
    NO_MAPPING("no_mapping", true),

    /** The local listener couldn't start. */
    LISTEN_FAILED("listener_failed", false),
}

/** What the UI shows about the direct connection. */
data class DirectStatus(
    /** Remote mode is on, so the direct connection is being worked on. */
    val active: Boolean = false,
    /** What `remote.state` last said; null while the first attempt runs. */
    val direct: Direct? = null,
    val reason: DirectReason? = null,
    /** An IPv6 address is offered although the router didn't confirm it lets connections in. */
    val ipv6Unconfirmed: Boolean = false,
    /**
     * This phone's address on the home network while it listens, and the listening port: what the user forwards in the
     * router by hand when it has no UPnP, NAT-PMP or PCP. For the screen only; never logged or sent.
     */
    val localV4: String? = null,
    val port: Int? = null,
    /** The router opened IPv4 for us (NAT-PMP, PCP or UPnP), so no forwarding by hand is needed. */
    val mappedV4: Boolean = false,
)

/**
 * Remote mode's direct connection on the bridge (D-092, PROTOCOL.md "Direct connection"). While remote mode is on and
 * the phone is on Wi-Fi it listens ([DirectServer]) on this phone's Wi-Fi IPv4 address and its global IPv6 address,
 * asks the home router to open the port ([PortMapper]: NAT-PMP, then PCP, then UPnP; a PCP pinhole for IPv6), renews
 * the mapping before it expires and checks the router's internet address every [CHECK_MS], and maps again when the
 * network changes. It tells the iPhone where to connect (`remote.addr`) and whether that can work (`remote.state`)
 * through [send] — the event session, or the quiet mailbox — whenever either changes. When remote mode ends, the
 * pairing is forgotten (both turn remote mode off) or the service stops, it closes the listener and deletes the
 * mappings.
 *
 * All router traffic runs on one coroutine on [Dispatchers.IO], one step at a time.
 */
class DirectLink(
    private val server: DirectServer,
    private val send: (JSONObject) -> Unit,
    /** Remembers a permanent UPnP mapping until it's deleted, even across a crash. */
    private val leftovers: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** A Wi-Fi network to work on: what it is, and how to reach its router. */
    class Network(val info: RouterNetwork, val io: RouterIo)

    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> Log.e(TAG, "direct connection task failed", e) },
    )
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private val work = Any()

    @Volatile private var wantActive = false
    @Volatile private var wantNetwork: Network? = null
    @Volatile private var networkKnown = false
    @Volatile private var reannounce = false

    // Everything below: inside synchronized(work) only.
    private var mapped: Network? = null
    private var port: Int? = null
    private var listening = false
    private var v4: Mapping? = null
    private var v4Problem: DirectReason? = null
    private var v4DueAt = 0L
    private var lastMappedAt = Long.MIN_VALUE / 2
    private var v6: Mapping? = null
    private var v6DueAt = 0L
    private var lastAddr: Pair<String?, String?>? = null
    private var lastState: Pair<Direct, String?>? = null

    private val _status = MutableStateFlow(DirectStatus())
    val status: StateFlow<DirectStatus> = _status

    /** Starts with the link service. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    /** The link service stops: closes the listener and deletes the mappings (in the background). */
    fun stop() {
        job?.cancel()
        job = null
        networkKnown = false
        scope.launch {
            synchronized(work) {
                teardown(wantNetwork)
                publishOff()
            }
            wake.trySend(Unit) // in case the service already started again
        }
    }

    /** Remote mode is on ([on]) or off; off closes everything and deletes the mappings. */
    fun setActive(on: Boolean) {
        wantActive = on
        wake.trySend(Unit)
    }

    /** The phone's Wi-Fi network now; null without Wi-Fi (mobile data only). */
    fun setNetwork(network: Network?) {
        wantNetwork = network
        networkKnown = true
        wake.trySend(Unit)
    }

    /**
     * "Check my home connection" (`remote.check`): asks this phone's current Wi-Fi router, read-only, whether a direct
     * connection could work ([HomeCheck]). Works with remote mode on or off; one check at a time. Blocks for seconds.
     */
    fun check(): HomeCheck.Result {
        if (!checking.compareAndSet(false, true)) throw RequestException(ErrorCode.BUSY, "a check is already running")
        try {
            val result = reconcile(HomeCheck.run(if (networkKnown) wantNetwork else null))
            // The router answers now (the user just turned UPnP or NAT-PMP on): with Cloud mode on, map at once
            // instead of at the next half-hourly retry.
            if (result.router != null && wantActive) {
                retryNow = true
                wake.trySend(Unit)
            }
            return result
        } finally {
            checking.set(false)
        }
    }

    /**
     * The check's quick, read-only probe can miss a slow router (no UPnP answer in its time) while this phone's own
     * mapping on the same network already learned more: carrier-grade NAT, double NAT, or a port that's open. That
     * stands then, so the iPhone isn't told to turn on UPnP that's already on (seen 2026-11-27: a TP-Link TD-W9960 with
     * UPnP on, behind the provider's carrier-grade NAT, got "the router doesn't open ports").
     */
    private fun reconcile(probed: HomeCheck.Result): HomeCheck.Result {
        if (probed.router != null || probed.reason == DirectReason.NOT_ON_WIFI) return probed
        val known = _status.value
        val sameNetwork = synchronized(work) { mapped != null && mapped === wantNetwork }
        if (!known.active || !sameNetwork) return probed
        val result = when {
            known.direct == Direct.OPEN && known.mappedV4 -> HomeCheck.Result(true, null, null, probed.ipv6)
            known.reason in ADDRESS_REASONS -> HomeCheck.Result(false, known.reason, null, probed.ipv6)
            else -> return probed
        }
        Log.i(TAG, "the router didn't answer the check in time; " + HomeCheck.describe(result) + " (from this phone's mapping)")
        return result
    }

    /** Set by [check] when the router answered: the next pass asks it for the mapping again right away. */
    @Volatile private var retryNow = false

    private val checking = AtomicBoolean(false)

    /** Remote mode was just (re)configured: send `remote.addr` and `remote.state` again even if unchanged. */
    fun announce() {
        reannounce = true
        wake.trySend(Unit)
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val wait = step()
            if (wait == null) wake.receive() else withTimeoutOrNull(wait.coerceAtLeast(1)) { wake.receive() }
        }
    }

    /** One pass: does whatever is due. Returns how long until the next pass is due, or null to wait until woken. */
    internal fun step(): Long? = synchronized(work) { stepLocked() }

    private fun stepLocked(): Long? {
        val net = wantNetwork
        if (!wantActive) {
            if (listening || mapped != null) {
                teardown(net)
                Log.i(TAG, "direct connection closed (remote mode off)")
            }
            publishOff()
            return null
        }
        if (!networkKnown) return null // the network watcher reports first, so a start doesn't say "not on Wi-Fi"
        if (net == null) {
            // Off Wi-Fi the old router can't be reached; its mappings expire by themselves.
            if (listening || mapped != null) teardown(null)
            report(null, DirectReason.NOT_ON_WIFI)
            return null
        }
        if (!_status.value.active) _status.value = DirectStatus(active = true)
        val info = net.info
        val p = server.listen(listOfNotNull(info.localV4, info.globalV6))
        listening = p != null
        if (p == null) {
            dropMappings(net)
            report(info, DirectReason.LISTEN_FAILED)
            return LISTEN_RETRY_MS
        }
        val old = mapped?.info
        if (old != null && old != info) Log.i(TAG, "the network changed; mapping again")
        if (old == null || old.gatewayV4 != info.gatewayV4 || old.localV4 != info.localV4 || port != p) {
            v4?.let { m -> if (old != null && old.gatewayV4 == info.gatewayV4) delete(net, old, m) }
            v4 = null
            v4Problem = null
            v4DueAt = 0
            lastMappedAt = Long.MIN_VALUE / 2
        }
        if (old == null || old.gatewayV6 != info.gatewayV6 || old.globalV6 != info.globalV6 || port != p) {
            v6?.let { m -> if (old != null && old.gatewayV6 == info.gatewayV6) delete(net, old, m) }
            v6 = null
            v6DueAt = 0
        }
        mapped = net
        port = p
        if (retryNow) {
            retryNow = false
            if (v4 == null) v4DueAt = 0
            if (v6 == null && info.globalV6 != null) v6DueAt = 0
        }
        val mapper = PortMapper(net.io, info, random)
        val now = clock()
        if (now >= v4DueAt) {
            val previous = v4
            v4 = previous?.let { attempt { mapper.renew(it) } }
            // The log says when a mapping is renewed or lost; the quarter-hourly checks in between stay quiet.
            val renewed = v4
            when {
                previous == null -> Unit
                renewed == null -> Log.i(TAG, "the router no longer keeps the IPv4 mapping (${previous.protocol.label}); mapping again")
                now >= previous.renewAt -> Log.i(TAG, "IPv4 mapping renewed (${renewed.protocol.label}, ${renewed.lifetime} s)")
            }
            if (v4 == null) {
                if (previous == null) deleteLeftover(net)
                mapV4(mapper, info, p)
            }
            v4?.let {
                rememberIfPermanent(it)
                lastMappedAt = clock()
            }
            // A router that mapped a moment ago and now doesn't answer is asked again soon, not in half an hour.
            val retry = if (clock() - lastMappedAt < RECHECK_MS) LISTEN_RETRY_MS else RECHECK_MS
            v4DueAt = v4?.let { minOf(it.renewAt, clock() + CHECK_MS) } ?: (clock() + retry)
        }
        if (now >= v6DueAt) {
            val previousV6 = v6
            v6 = if (info.globalV6 == null) null else attempt { v6?.let(mapper::renew) ?: mapper.pinholeV6(p) }
            when {
                previousV6 == null -> Unit
                v6 == null -> Log.i(TAG, "the router no longer keeps the IPv6 pinhole (PCP)")
                now >= previousV6.renewAt -> Log.i(TAG, "IPv6 pinhole renewed (PCP)")
            }
            v6DueAt = when {
                info.globalV6 == null -> Long.MAX_VALUE
                v6 != null -> minOf(v6!!.renewAt, clock() + CHECK_MS)
                else -> clock() + RECHECK_MS
            }
        }
        report(info, v4Problem)
        return (minOf(v4DueAt, v6DueAt) - clock()).coerceAtLeast(1)
    }

    private fun mapV4(mapper: PortMapper, info: RouterNetwork, port: Int) {
        val result = (if (info.hasV4) attempt { mapper.mapV4(port) } else null) ?: V4Result.NoMapping
        when (result) {
            is V4Result.Mapped -> {
                v4 = result.mapping
                v4Problem = null
            }
            is V4Result.NotPublic -> v4Problem = when (result.kind) {
                V4Kind.CARRIER_NAT -> DirectReason.CARRIER_NAT
                V4Kind.PRIVATE -> DirectReason.DOUBLE_NAT
                else -> DirectReason.NO_PUBLIC_ADDRESS
            }
            V4Result.NoMapping -> v4Problem = DirectReason.NO_MAPPING
        }
    }

    /**
     * Works out `remote.addr` and `remote.state` and sends whichever changed. Only public addresses go into
     * `remote.addr` (the iPhone refuses anything else). Open: a confirmed IPv4 mapping or IPv6 pinhole. None: only an
     * IPv6 address the router didn't confirm (worth a try; the reason is IPv4's), or a passing problem. Blocked:
     * nothing can work on this network. The reason is a fixed code, null when open.
     */
    private fun report(info: RouterNetwork?, problem: DirectReason?) {
        val v4Text = v4?.let { m ->
            (m.external as? Inet4Address)?.takeIf { Addresses.classifyV4(it) == V4Kind.PUBLIC }
                ?.let { Addresses.formatV4(it, m.externalPort) }
        }
        val v6Address = ((v6?.external as? Inet6Address) ?: info?.globalV6?.takeIf { listening })
            ?.takeIf { Addresses.isGlobalV6(it) }
        val v6Port = v6?.externalPort ?: port?.takeIf { listening }
        val v6Text = if (v6Address != null && v6Port != null) Addresses.formatV6(v6Address, v6Port) else null
        val unconfirmedV6 = v6Text != null && v6 == null
        val direct = when {
            v4Text != null || (v6 != null && v6Text != null) -> Direct.OPEN
            unconfirmedV6 || problem?.blocks != true -> Direct.NONE
            else -> Direct.BLOCKED
        }
        val reason = if (direct == Direct.OPEN) null else problem
        val reasonText = reason?.wire
        val addr = v4Text to v6Text
        if (addr != lastAddr || reannounce) send(Messages.remoteAddr(v4Text, v6Text))
        val state = direct to reasonText
        if (state != lastState || reannounce) send(Messages.remoteState(direct.wire, reasonText))
        if (state != lastState) Log.i(TAG, describe(direct, reasonText))
        lastAddr = addr
        lastState = state
        reannounce = false
        _status.value = DirectStatus(
            active = true,
            direct = direct,
            reason = reason,
            ipv6Unconfirmed = unconfirmedV6,
            localV4 = info?.localV4?.takeIf { listening }?.hostAddress,
            port = port?.takeIf { listening },
            mappedV4 = v4Text != null,
        )
    }

    /** For the log: never an address, only how the connection is open or why not. */
    private fun describe(direct: Direct, reason: String?): String = when (direct) {
        Direct.OPEN -> buildString {
            append("direct connection open")
            v4?.let { append(" (IPv4 through ").append(it.protocol.label).append(')') }
            if (v6 != null) append(" (IPv6 opened through PCP)")
        }
        Direct.NONE -> "no direct connection for now: $reason"
        Direct.BLOCKED -> "no direct connection: $reason"
    }

    private fun publishOff() {
        lastAddr = null
        lastState = null
        _status.value = DirectStatus()
    }

    /** Closes the listener and deletes the mappings on [net]'s router, if that's still the one they were made on. */
    private fun teardown(net: Network?) {
        server.close()
        listening = false
        if (net != null) dropMappings(net) else forgetMappings()
    }

    private fun dropMappings(net: Network) {
        val old = mapped?.info
        if (old != null) {
            v4?.let { m -> if (old.gatewayV4 == net.info.gatewayV4) delete(net, old, m) }
            v6?.let { m -> if (old.gatewayV6 == net.info.gatewayV6) delete(net, old, m) }
        }
        forgetMappings()
    }

    /** Deletes [m], made on [madeOn], through [net]'s sockets; a deleted permanent mapping is forgotten. */
    private fun delete(net: Network, madeOn: RouterNetwork, m: Mapping) {
        val deleted = attempt { PortMapper(net.io, madeOn, random).delete(m) } == true
        if (deleted && m.lifetime == 0L) leftovers.clear()
    }

    private fun forgetMappings() {
        mapped = null
        port = null
        v4 = null
        v6 = null
        v4Problem = null
        v4DueAt = 0
        v6DueAt = 0
        lastMappedAt = Long.MIN_VALUE / 2
    }

    /** A permanent UPnP mapping stays on the router until deleted, so it's remembered until then. */
    private fun rememberIfPermanent(m: Mapping) {
        val service = m.upnp
        val gateway = mapped?.info?.gatewayV4
        if (m.lifetime != 0L || service == null || gateway == null) return
        leftovers.put(
            mapOf(
                KEY_GATEWAY to Addresses.v4Text(gateway),
                KEY_HOST to Addresses.v4Text(service.control.host),
                KEY_PORT to service.control.port.toString(),
                KEY_PATH to service.control.path,
                KEY_TYPE to service.type,
                KEY_EXTERNAL_PORT to m.externalPort.toString(),
            ),
        )
    }

    /** Deletes a permanent mapping a previous run left on this router (for example after a crash). */
    private fun deleteLeftover(net: Network) {
        val gateway = leftovers.get(KEY_GATEWAY)?.let(Addresses::parseV4) ?: return
        if (gateway != net.info.gatewayV4) return
        val host = leftovers.get(KEY_HOST)?.let(Addresses::parseV4)
        val port = leftovers.get(KEY_PORT)?.toIntOrNull()
        val path = leftovers.get(KEY_PATH)
        val type = leftovers.get(KEY_TYPE)
        val external = leftovers.get(KEY_EXTERNAL_PORT)?.toIntOrNull()
        if (host == null || port == null || path == null || type == null || external == null) {
            leftovers.clear()
            return
        }
        val service = UpnpService(type, LanUrl(host, port, path))
        if (attempt { UpnpClient(net.io, net.info).deletePortMapping(service, external) } == true) {
            Log.i(TAG, "deleted a permanent mapping a previous run left on router ${Addresses.lanHint(gateway)}")
            leftovers.clear()
        }
    }

    /** Router I/O can fail in many ways (a socket that can't bind, a network that just went away): log and go on. */
    private fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "router request failed (${e.javaClass.simpleName})")
        null
    }

    companion object {
        private const val TAG = "direct"

        /** What this phone's mapping learned about the router's internet address, which a check's quick probe keeps. */
        private val ADDRESS_REASONS =
            setOf(DirectReason.CARRIER_NAT, DirectReason.DOUBLE_NAT, DirectReason.NO_PUBLIC_ADDRESS)

        /** Check the mapping and the router's internet address at least this often (a new address is noticed then). */
        const val CHECK_MS = 15 * 60_000L

        /** Try again this long after no mapping was possible (the user may change the router, or move it). */
        const val RECHECK_MS = 30 * 60_000L
        const val LISTEN_RETRY_MS = 60_000L

        private const val KEY_GATEWAY = "upnp.gateway"
        private const val KEY_HOST = "upnp.host"
        private const val KEY_PORT = "upnp.port"
        private const val KEY_PATH = "upnp.path"
        private const val KEY_TYPE = "upnp.type"
        private const val KEY_EXTERNAL_PORT = "upnp.external"
    }
}
