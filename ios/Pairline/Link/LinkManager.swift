import Foundation

/// What the user sees about the link.
enum LinkPhase: Equatable {
    case notPaired
    /// Link features are locked (trial not started or ended, D-017).
    case locked
    case bluetoothOff
    case bluetoothUnauthorized
    case bluetoothUnsupported
    case searching
    case securing
    case connected
    case waiting(until: Date)
    case pairing
    /// Remote mode (D-092): no session, but messages reach the bridge through the user's iCloud ("Via iCloud").
    /// Texts, status and history work; calls don't, since there's no audio without a direct connection yet.
    case remote

    /// A live session with the bridge.
    var isConnected: Bool { self == .connected }
    /// Requests reach the bridge: a live session, or the iCloud mailbox (slower, and no calls).
    var canReachBridge: Bool { self == .connected || self == .remote }
}

enum LinkError: LocalizedError, Equatable {
    case notConnected
    case timeout
    case bridge(BridgeErrorReply)

    var errorDescription: String? {
        switch self {
        case .notConnected:
            return String(localized: "Your bridge phone isn't connected right now.")
        case .timeout:
            return String(localized: "The bridge phone didn't answer in time.")
        case .bridge(let reply):
            switch reply.code {
            case "unsupported": return String(localized: "The bridge app doesn't support this yet. Update it on your Android phone.")
            case "permission": return String(localized: "The bridge app is missing a permission for this. Open Pairline Bridge on your Android phone to allow it.")
            case "invalid": return String(localized: "The bridge phone couldn't handle that request.")
            case "busy": return String(localized: "The bridge phone is busy. Try again in a moment.")
            default: return String(localized: "The bridge phone couldn't do that.")
            }
        }
    }
}

enum PairingError: LocalizedError, Equatable {
    case invalidCode
    case expired
    case alreadyUsed
    case wrongCode
    case busy
    case bridgeNotFound
    case verificationFailed
    case connectionLost
    case bluetoothUnavailable
    case locked

    var errorDescription: String? {
        switch self {
        case .invalidCode: return String(localized: "That isn't a Pairline pairing code.")
        case .expired: return String(localized: "This pairing code has expired. Show a new code on the bridge phone and scan it again.")
        case .alreadyUsed: return String(localized: "This pairing code was already used. Show a new code on the bridge phone.")
        case .wrongCode: return String(localized: "The bridge phone didn't accept this iPhone. Show a new code and scan it again.")
        case .busy: return String(localized: "The bridge phone is busy pairing with another device. Try again in a moment.")
        case .bridgeNotFound: return String(localized: "Couldn't find the bridge phone. Keep both phones close together with Bluetooth on, and keep Pairline Bridge open.")
        case .verificationFailed: return String(localized: "The bridge phone couldn't prove it showed this code. Pairing was stopped to keep you safe.")
        case .connectionLost: return String(localized: "The connection dropped while pairing. Try again.")
        case .bluetoothUnavailable: return String(localized: "Turn on Bluetooth and allow Pairline to use it, then try again.")
        case .locked: return String(localized: "Start your free trial or unlock Pairline to pair a bridge phone.")
        }
    }
}

/// The interface the app talks to: the real link (`LinkManager`) and the demo bridge (`DemoBridge`) both implement it,
/// so everything above the link is shared.
@MainActor
protocol BridgeLink: AnyObject {
    var phase: LinkPhase { get }
    var latency: TimeInterval? { get }
    var transportKind: TransportKind { get }
    /// The transports with an established session right now, Bluetooth (or debug TCP) first.
    var activeTransports: [TransportKind] { get }
    var onPhaseChange: ((LinkPhase) -> Void)? { get set }
    /// The set of active transports (or the media channel) changed while the phase may have stayed the same.
    var onTransportsChange: (() -> Void)? { get set }
    /// Events and unsolicited messages from the bridge.
    var onEvent: ((Inbound) -> Void)? { get set }
    /// Called when the link comes up (the first session after none; a good moment to sync).
    var onEstablished: (() -> Void)? { get set }
    func request(_ message: Outbound) async throws -> Inbound
    func send(_ message: Outbound)
    func measureLatency() async
    /// Asks for a reconnect now (user action or app foregrounded).
    func reconnectNow()
}

/// Which session a message travels on (PROTOCOL.md, "Two sessions at once").
enum LinkLane: String, CaseIterable {
    /// Bluetooth (or the debug TCP link in debug builds).
    case primary
    case wifi
    /// Remote mode's direct connection (D-092): only while no nearby session is up.
    case direct
}

/// The real link: up to two sessions with the paired bridge — Bluetooth and Wi-Fi — each with its own transport,
/// handshake, reconnection and keep-alive; requests routed between them; the Wi-Fi session's media channel and file
/// transfers (PROTOCOL.md, "Wi-Fi"). In remote mode, while neither is up, a third session runs over the direct
/// connection to the bridge's public address when the bridge has sent one, and otherwise messages go through the
/// iCloud mailbox (PROTOCOL.md, "Remote mode").
@MainActor
final class LinkManager: BridgeLink {
    private struct Pending {
        let continuation: CheckedContinuation<Inbound, Error>
        let timer: Timer
        let message: Outbound
        /// nil: sent through remote mode's mailbox.
        let lane: LinkLane?
    }

    private struct PairingAttempt {
        let link: PairingLink
        let continuation: CheckedContinuation<Void, Error>
        let deadline: Timer
        /// The lane whose transport reached the bridge first; it carries `PAIR_HELLO`.
        var chosen: LinkLane?
        var trying: Set<LinkLane>
    }

    private final class Lane {
        let id: LinkLane
        var connecting = false
        /// Bumped for every connect attempt, so a stale completion is ignored.
        var token = 0
        var session: LinkSession?
        var startedAt = Date()
        var retryAttempt = 0
        var retryTimer: Timer?
        var retryAt: Date?
        var lastReceived = Date()
        var lastPing = Date.distantPast
        var connectedSince: Date?

        init(_ id: LinkLane) { self.id = id }

        var isEstablished: Bool { session?.phase == .established }
    }

    static let idleLimit: TimeInterval = 180
    /// Wi-Fi keep-alive (PROTOCOL.md): ping after 15 s without receiving anything, close after 45 s of silence.
    static let wifiPingAfter: TimeInterval = 15
    static let wifiIdleLimit: TimeInterval = 45
    static let handshakeLimit: TimeInterval = 30
    /// A request through remote mode's mailbox waits this long for its reply: the bridge checks the mailbox every
    /// 5 s for 2 minutes after hearing from this iPhone, every 30 s within 10 minutes, otherwise every 2 minutes
    /// (PROTOCOL.md).
    static let mailboxTimeout: TimeInterval = 180
    /// While such a request waits and the app is open, look for the reply this often (silent pushes can lag; the
    /// bridge checks every 5 s right after hearing from this iPhone, so a reply is usually there within seconds).
    static let mailboxPollInterval: TimeInterval = 3
    /// Remote mode's own message types from the bridge (PROTOCOL.md, "Remote messages"), handled by the link itself.
    nonisolated static let remoteModeTypes: Set<String> = ["remote.config", "remote.token", "remote.addr", "remote.state",
                                                           "remote.check"]
    /// The direct connection retries after 5 s, 15 s and 30 s, then every 2 minutes; at once when the network
    /// changes, a nearby session ends or new addresses arrive.
    nonisolated static func directRetryDelay(attempt: Int) -> TimeInterval {
        let delays: [TimeInterval] = [5, 15, 30]
        return attempt < delays.count ? delays[max(0, attempt)] : 120
    }
    /// Posted (object: the link manager) when `mediaChannel` changes.
    static let mediaChannelDidChange = Notification.Name("PairlineMediaChannelDidChange")

    private(set) var phase: LinkPhase = .notPaired {
        didSet {
            guard phase != oldValue else { return }
            onPhaseChange?(phase)
            if phase == .remote { Task { await remoteCatchUp() } }
        }
    }
    private(set) var latency: TimeInterval?
    private(set) var lastSessionError: LinkSessionError?
    var connectedSince: Date? { lanes.compactMap(\.connectedSince).min() }
    var transportKind: TransportKind { activeTransports.first ?? primaryKind ?? .wifi }
    var activeTransports: [TransportKind] { lanes.filter(\.isEstablished).compactMap { $0.session?.transportKind } }
    /// The live session is remote mode's direct connection (the UI says "Direct connection").
    var isDirect: Bool { activeTransports == [.direct] }

    var onPhaseChange: ((LinkPhase) -> Void)?
    var onTransportsChange: (() -> Void)?
    var onEvent: ((Inbound) -> Void)?
    var onEstablished: (() -> Void)?
    var onPeerChange: (() -> Void)?
    /// The iPhone just joined the bridge's own hotspot, which has no internet (iOS says "No Internet Connection").
    var onJoinedBridgeHotspot: (() -> Void)?
    /// Remote pairing (D-092): the bridge's `rpair.confirm` arrived through a catch-up.
    var onRemotePairConfirm: (() -> Void)?

    /// The open media channel of the Wi-Fi session, or nil (no Wi-Fi session, or its UDP path isn't verified yet).
    private(set) var mediaChannel: MediaChannel?
    /// Called on the main queue whenever `mediaChannel` changes (opened, or closed with nil).
    var onMediaChannelChange: ((MediaChannel?) -> Void)?
    /// Call audio inside the Bluetooth (or debug TCP) session while it's established (D-072), else inside remote mode's
    /// direct connection (D-092), or nil.
    private(set) var linkMediaChannel: LinkMediaChannel?
    /// The lane whose session carries `linkMediaChannel`: `call.media.start` with `via: "link"` must go on it.
    private(set) var linkMediaLane: LinkLane?

    let files = FileTransferManager()
    let hotspot = HotspotJoiner()
    let identityStore: IdentityStore
    let peerStore: PeerStore
    /// Remote mode's mailbox in iCloud (D-092): the path while no session is up. nil where it isn't available.
    let remote: RemoteLink?
    private let makeConnector: (TransportKind) -> LinkConnector
    private var connectors: [TransportKind: LinkConnector] = [:]
    private let primary = Lane(.primary)
    private let wifi = Lane(.wifi)
    private let direct = Lane(.direct)
    private var lanes: [Lane] { [primary, wifi, direct] }
    private var pending: [Int: Pending] = [:]
    private var nextID = 1
    private var watchdog: Timer?
    private var allowed = false
    private var pairingAttempt: PairingAttempt?
    private let deviceInfo: Outbound
    private var media: MediaSocket?
    private var bridgeCaps: Set<String> = []
    private var hotspotTimer: Timer?
    private var mailboxPoll: Timer?

    /// The transport of the first lane: Bluetooth in release builds; debug builds may use TCP or nothing (Wi-Fi only).
    var primaryKind: TransportKind? {
        if let raw = peerStore.peer?.transport, let kind = TransportKind(rawValue: raw), kind == .bluetooth || kind == .tcp {
            return kind
        }
        let kind = DebugSettings.pairingTransport
        return kind == .wifi ? nil : kind
    }

    /// Whether the bridge ever told us about Wi-Fi (pair link, `wifi.offer`, or `wifi` in its capabilities). Until
    /// then the Wi-Fi lane stays idle, so an older bridge never triggers the Local Network prompt.
    private var wifiLaneEnabled: Bool {
        #if DEBUG
        if DebugSettings.wifiOverride != nil { return true }
        #endif
        return peerStore.peer?.wifi != nil || bridgeCaps.contains(Capability.wifi.rawValue)
    }

    /// The direct lane runs in remote mode, once the bridge has sent addresses, while no nearby session is up, and
    /// while the app is in the foreground or a call needs it.
    private var directWanted: Bool {
        guard let remote, remote.isOn, remote.hasDirectTargets else { return false }
        if inBackground, keepsDirectInBackground?() != true { return false }
        return !primary.isEstablished && !wifi.isEstablished
    }

    /// The app is in the background. A direct session can't wake a suspended app the way Bluetooth and iCloud's pushes
    /// do, so the bridge would send events into a socket nobody reads: it closes, and events go through iCloud.
    private var inBackground = false
    /// A call is in progress, whose audio may ride the direct connection: it stays in the background then.
    var keepsDirectInBackground: (() -> Bool)?

    func appMovedToBackground() {
        inBackground = true
        backgroundCheck()
    }

    func appBecameActive() {
        inBackground = false
        // The direct connection closed in the background (or never opened there), and the iPhone may be on another
        // network now: try it again right away instead of waiting for the next push or address.
        if direct.session == nil, !direct.connecting { kickDirect(reason: "back in the foreground") }
    }

    /// While in the background: closes the direct connection unless a call needs it (call this when a call ends).
    func backgroundCheck() {
        guard inBackground, keepsDirectInBackground?() != true else { return }
        guard direct.session != nil || direct.connecting || direct.retryTimer != nil else { return }
        log.info("direct", "In the background: the direct connection closes and iCloud carries events")
        cancelDirectAttempt()
        closeDirectSession()
    }

    init(identityStore: IdentityStore, peerStore: PeerStore, deviceInfo: Outbound, remote: RemoteLink? = nil,
         makeConnector: @escaping (TransportKind) -> LinkConnector) {
        self.identityStore = identityStore
        self.peerStore = peerStore
        self.deviceInfo = deviceInfo
        self.remote = remote
        self.makeConnector = makeConnector
        // Create the Bluetooth central right away when paired, so iOS can restore it.
        if peerStore.peer != nil, primaryKind == .bluetooth {
            _ = connector(for: .bluetooth)
        }
        files.sendControl = { [weak self] message in self?.sendOnWiFi(message) ?? false }
        files.sendBulk = { [weak self] data, done in self?.sendBulk(data, completion: done) ?? false }
        remote?.onMessage = { [weak self] delivery in
            self?.handle(plaintext: delivery.plaintext, lane: nil, alreadyShown: delivery.alreadyShown)
        }
        remote?.onChange = { [weak self] in self?.remoteChanged() }
        remote?.sendOverSession = { [weak self] message in
            guard let self, let lane = self.route(message) else { return false }
            return self.sendRaw(message, id: nil, on: lane)
        }
        updatePhase()
    }

    // MARK: - Control

    /// Link features are allowed (trial active or purchased). Called on launch and whenever access changes.
    func setAllowed(_ allowed: Bool) {
        guard allowed != self.allowed else { return }
        self.allowed = allowed
        if allowed {
            lanes.forEach(connectIfNeeded)
        } else {
            teardown()
            updatePhase()
        }
    }

    func reconnectNow() {
        guard allowed, peerStore.peer != nil, pairingAttempt == nil else { return }
        for lane in lanes where lane.session == nil {
            lane.retryTimer?.invalidate()
            lane.retryTimer = nil
            lane.retryAt = nil
            lane.retryAttempt = 0
            if !lane.connecting { connectIfNeeded(lane) }
        }
        updatePhase()
    }

    private func lane(_ id: LinkLane) -> Lane {
        switch id {
        case .primary: return primary
        case .wifi: return wifi
        case .direct: return direct
        }
    }

    private func connector(for kind: TransportKind) -> LinkConnector {
        if let existing = connectors[kind] { return existing }
        let new = makeConnector(kind)
        new.onAvailabilityChange = { [weak self] _ in self?.availabilityChanged() }
        if let wifiConnector = new as? WiFiConnector {
            wifiConnector.hints = { [weak self] in self?.peerStore.peer?.wifi }
            wifiConnector.onNetworkChange = { [weak self] in self?.kickWiFi(reason: "network changed") }
        }
        if let directConnector = new as? DirectConnector {
            directConnector.onNetworkChange = { [weak self] in self?.kickDirect(reason: "network changed") }
        }
        connectors[kind] = new
        return new
    }

    private func laneKind(_ lane: Lane) -> TransportKind? {
        switch lane.id {
        case .primary: return primaryKind
        case .wifi: return .wifi
        case .direct: return .direct
        }
    }

    private func availabilityChanged() {
        guard let kind = primaryKind, let connector = connectors[kind] else { return }
        let unavailable = [.poweredOff, .unauthorized, .unsupported].contains(connector.availability)
        if var attempt = pairingAttempt, attempt.chosen == nil, attempt.trying.contains(.primary), unavailable {
            attempt.trying.remove(.primary)
            pairingAttempt = attempt
            primary.token += 1
            primary.connecting = false
            connector.cancel()
            if attempt.trying.isEmpty { return finishPairing(.failure(PairingError.bluetoothUnavailable)) }
        }
        if connector.availability == .poweredOn, primary.session == nil, pairingAttempt == nil {
            primary.connecting = false
            primary.token += 1
            connectIfNeeded(primary)
        }
        updatePhase()
    }

    private func updatePhase() {
        if pairingAttempt != nil {
            phase = .pairing
            return
        }
        if lanes.contains(where: \.isEstablished) {
            phase = .connected
            return
        }
        guard peerStore.peer != nil else {
            phase = .notPaired
            return
        }
        guard allowed else {
            phase = .locked
            return
        }
        if remote?.isOn == true {
            phase = .remote
            return
        }
        if lanes.contains(where: { $0.session != nil }) {
            phase = .securing
            return
        }
        let wifiKnown = !(peerStore.peer?.wifi?.endpoints.isEmpty ?? true)
        if !wifiKnown, let kind = primaryKind {
            switch connectors[kind]?.availability {
            case .poweredOff?: phase = .bluetoothOff; return
            case .unauthorized?: phase = .bluetoothUnauthorized; return
            case .unsupported?: phase = .bluetoothUnsupported; return
            default: break
            }
        }
        if !lanes.contains(where: \.connecting), let until = lanes.compactMap(\.retryAt).min() {
            phase = .waiting(until: until)
        } else {
            phase = .searching
        }
    }

    private func connectIfNeeded(_ lane: Lane) {
        guard allowed, let peer = peerStore.peer, lane.session == nil, pairingAttempt == nil, !lane.connecting,
              let kind = laneKind(lane) else { return }
        if lane.id == .wifi, !wifiLaneEnabled { return }
        if lane.id == .direct, !directWanted { return }
        lane.retryTimer?.invalidate()
        lane.retryTimer = nil
        lane.retryAt = nil
        let connector = connector(for: kind)
        lane.connecting = true
        lane.token += 1
        let token = lane.token
        var target = ConnectTarget(deviceID: peer.deviceID, knownPeripheral: peer.peripheralID)
        // The only non-local addresses the iPhone dials: from the paired bridge's authenticated `remote.addr`, and the
        // port forwarding the user typed in (D-095).
        if lane.id == .direct, let remote {
            target.direct = remote.directCandidates
            target.directManual = remote.manualEndpoint
            target.directManualFirst = remote.manualFirst
        }
        let triedManual = target.directManual != nil
        updatePhase()
        connector.connect(target) { [weak self, weak lane] result in
            guard let self, let lane, token == lane.token else {
                if case .success(let (transport, _)) = result { transport.close() }
                return
            }
            lane.connecting = false
            switch result {
            case .success(let (transport, peripheralID)):
                guard self.allowed, self.peerStore.peer != nil, self.pairingAttempt == nil, lane.session == nil,
                      lane.id != .direct || self.directWanted else {
                    transport.close()
                    return self.updatePhase()
                }
                if let peripheralID { self.peerStore.update { $0.peripheralID = peripheralID } }
                self.startSession(transport, lane: lane, pairing: nil)
            case .failure(let error):
                if case ConnectError.cancelled = error { return self.updatePhase() }
                // Remote mode's log line for a failed attempt: never the addresses tried (rule 9).
                if lane.id == .direct { log.info("direct", "Couldn't reach the bridge's home; trying again later") }
                if lane.id == .direct, triedManual { self.remote?.noteManualAttempt(worked: false) }
                self.scheduleRetry(lane)
            }
        }
    }

    private func scheduleRetry(_ lane: Lane) {
        guard allowed, peerStore.peer != nil, pairingAttempt == nil else { return updatePhase() }
        if lane.id == .wifi, !wifiLaneEnabled { return updatePhase() }
        if lane.id == .direct, !directWanted { return updatePhase() }
        let delay = lane.id == .direct ? Self.directRetryDelay(attempt: lane.retryAttempt)
            : min(30, pow(2, Double(lane.retryAttempt)))
        lane.retryAttempt += 1
        lane.retryAt = Date().addingTimeInterval(delay)
        lane.retryTimer?.invalidate()
        let id = lane.id
        lane.retryTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                let lane = self.lane(id)
                lane.retryAt = nil
                self.connectIfNeeded(lane)
                self.updatePhase()
            }
        }
        updatePhase()
    }

    /// Something suggests Wi-Fi may work now (the network changed, a `wifi.offer` arrived, a hotspot was joined):
    /// restart the Wi-Fi lane's attempt without waiting for its backoff.
    private func kickWiFi(reason: String) {
        guard allowed, peerStore.peer != nil, pairingAttempt == nil, wifi.session == nil, wifiLaneEnabled else { return }
        log.info("wifi", "Trying Wi-Fi again (\(reason))")
        wifi.retryAttempt = 0
        if wifi.connecting {
            wifi.token += 1
            wifi.connecting = false
            connectors[.wifi]?.cancel()
        }
        connectIfNeeded(wifi)
    }

    // MARK: - Direct connection (D-092)

    /// Something suggests the direct connection may work now (new addresses, the network changed, the nearby link
    /// ended): restart its attempt without waiting for its backoff. A direct session already up is kept.
    private func kickDirect(reason: String) {
        guard allowed, peerStore.peer != nil, pairingAttempt == nil, direct.session == nil else { return }
        cancelDirectAttempt()
        direct.retryAttempt = 0
        guard directWanted else { return updatePhase() }
        log.info("direct", "Trying the direct connection (\(reason))")
        connectIfNeeded(direct)
    }

    private func cancelDirectAttempt() {
        direct.retryTimer?.invalidate()
        direct.retryTimer = nil
        direct.retryAt = nil
        if direct.connecting {
            direct.token += 1
            direct.connecting = false
            connectors[.direct]?.cancel()
        }
    }

    /// Remote mode went on or off: the direct lane follows it.
    private func remoteChanged() {
        if remote?.isOn == true {
            connectIfNeeded(direct)
        } else {
            cancelDirectAttempt()
            direct.retryAttempt = 0
            closeDirectSession()
        }
        updatePhase()
    }

    /// A nearby session is up, so the direct one isn't needed: stop trying, and close it once its requests are
    /// answered (the bridge answers on the session a request came on).
    private func retireDirect() {
        guard primary.isEstablished || wifi.isEstablished else { return }
        cancelDirectAttempt()
        guard let session = direct.session else { return }
        if session.phase == .established, pending.values.contains(where: { $0.lane == .direct }) { return }
        log.info("direct", "A nearby link is up; closing the direct connection")
        closeDirectSession()
    }

    /// Closes the direct session on purpose (it worked, so its address stays).
    private func closeDirectSession() {
        guard let session = direct.session else { return }
        direct.session = nil
        direct.connectedSince = nil
        // Its close callback no longer matches the lane, so the call-audio channel inside it closes here.
        if linkMediaLane == .direct { closeLinkMedia() }
        session.close()
        reroutePending(from: direct)
        if !lanes.contains(where: \.isEstablished) { latency = nil }
        onTransportsChange?()
        updatePhase()
    }

    /// The direct handshake failed or timed out at this session's address: skip it until the next `remote.addr`.
    private func dropDirectAddress(of session: LinkSession) {
        if let endpoint = session.frameTransport.dialed { remote?.dropDirect(endpoint) }
    }

    private func teardown() {
        for lane in lanes {
            lane.retryTimer?.invalidate()
            lane.retryTimer = nil
            lane.retryAt = nil
            lane.connecting = false
            lane.token += 1
        }
        connectors.values.forEach { $0.cancel() }
        watchdog?.invalidate()
        watchdog = nil
        hotspotTimer?.invalidate()
        hotspotTimer = nil
        mailboxPoll?.invalidate()
        mailboxPoll = nil
        for lane in lanes {
            let old = lane.session
            lane.session = nil
            lane.connectedSince = nil
            old?.close()
        }
        failPending(LinkError.notConnected)
        closeMedia()
        closeLinkMedia()
        updateFiles()
        latency = nil
        onTransportsChange?()
    }

    // MARK: - Pairing

    /// Pairs with the bridge in `link` (scanned QR code, or a pasted link in debug builds), then stays connected.
    /// Bluetooth and Wi-Fi (when the link has addresses) race; the first to reach the bridge carries the pairing.
    func pair(with link: PairingLink) async throws {
        guard allowed else { throw PairingError.locked }
        teardown()
        if let attempt = pairingAttempt {
            pairingAttempt = nil
            attempt.deadline.invalidate()
            attempt.continuation.resume(throwing: PairingError.connectionLost)
        }
        var trying = Set<LinkLane>()
        if primaryKind != nil { trying.insert(.primary) }
        if !wifiPairingCandidates(link).isEmpty || debugWiFiOverride { trying.insert(.wifi) }
        guard !trying.isEmpty else { throw PairingError.bluetoothUnavailable }
        hotspot.reset()
        phase = .pairing
        log.info("pairing", "Pairing over \(trying.map(\.rawValue).sorted().joined(separator: "+")): \(link.redactedDescription)")
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let deadline = Timer.scheduledTimer(withTimeInterval: 60, repeats: false) { [weak self] _ in
                Task { @MainActor in self?.finishPairing(.failure(PairingError.bridgeNotFound)) }
            }
            pairingAttempt = PairingAttempt(link: link, continuation: continuation, deadline: deadline, chosen: nil,
                                            trying: trying)
            for lane in LinkLane.allCases where trying.contains(lane) { attemptPairingConnection(lane) }
            if let ssid = link.hotspotSSID, let password = link.hotspotPassword {
                // The bridge runs its own hotspot: if nothing answers soon, offer to join it.
                hotspotTimer = Timer.scheduledTimer(withTimeInterval: 4, repeats: false) { [weak self] _ in
                    Task { @MainActor in
                        guard let self, self.pairingAttempt?.chosen == nil else { return }
                        self.hotspot.join(ssid: ssid, password: password) { [weak self] outcome in
                            if outcome == .joined { self?.onJoinedBridgeHotspot?() }
                        }
                    }
                }
            }
        }
    }

    private var debugWiFiOverride: Bool {
        #if DEBUG
        return DebugSettings.wifiOverride != nil
        #else
        return false
        #endif
    }

    private func wifiPairingCandidates(_ link: PairingLink) -> [WiFiEndpoint] {
        guard let port = link.wifiPort else { return [] }
        return link.wifiAddresses.map { WiFiEndpoint(host: $0, port: port) }
    }

    func cancelPairing() {
        finishPairing(.failure(CancellationError()))
    }

    private func attemptPairingConnection(_ id: LinkLane) {
        guard var attempt = pairingAttempt, attempt.chosen == nil, attempt.trying.contains(id) else { return }
        let lane = self.lane(id)
        guard let kind = laneKind(lane) else { return }
        let connector = connector(for: kind)
        if id == .primary, [.poweredOff, .unauthorized, .unsupported].contains(connector.availability) {
            attempt.trying.remove(.primary)
            pairingAttempt = attempt
            if attempt.trying.isEmpty { finishPairing(.failure(PairingError.bluetoothUnavailable)) }
            return
        }
        var target = ConnectTarget(deviceID: attempt.link.deviceID, knownPeripheral: nil)
        if id == .wifi {
            target.wifi = wifiPairingCandidates(attempt.link)
            target.wifiExplicitOnly = true
        }
        lane.token += 1
        let token = lane.token
        lane.connecting = true
        connector.connect(target) { [weak self, weak lane] result in
            guard let self, let lane, token == lane.token else {
                if case .success(let (transport, _)) = result { transport.close() }
                return
            }
            lane.connecting = false
            guard let attempt = self.pairingAttempt, attempt.chosen == nil else {
                if case .success(let (transport, _)) = result { transport.close() }
                return
            }
            switch result {
            case .success(let (transport, peripheralID)):
                self.pairingAttempt?.chosen = id
                for other in self.lanes where other !== lane {
                    other.token += 1
                    other.connecting = false
                    if let kind = self.laneKind(other) { self.connectors[kind]?.cancel() }
                }
                self.hotspotTimer?.invalidate()
                self.startSession(transport, lane: lane, pairing: attempt.link, peripheralID: peripheralID)
            case .failure(let error):
                if case ConnectError.cancelled = error { return }
                // TCP fails fast when nothing listens yet; keep trying until the deadline.
                Timer.scheduledTimer(withTimeInterval: 2, repeats: false) { [weak self] _ in
                    Task { @MainActor in self?.attemptPairingConnection(id) }
                }
            }
        }
    }

    private func finishPairing(_ result: Result<Void, Error>) {
        guard let attempt = pairingAttempt else { return }
        pairingAttempt = nil
        attempt.deadline.invalidate()
        switch result {
        case .success:
            log.info("pairing", "Paired")
            updatePhase()
            attempt.continuation.resume()
        case .failure(let error):
            log.warning("pairing", "Pairing failed: \(error)")
            if !lanes.contains(where: \.isEstablished) { teardown() }
            updatePhase()
            attempt.continuation.resume(throwing: error)
            if peerStore.peer != nil { lanes.forEach(connectIfNeeded) }
        }
    }

    // MARK: - Unpair

    /// Forgets the bridge. If connected, tells the bridge first so it forgets this iPhone too; in remote mode without
    /// a session, it tells it through the mailbox.
    func unpair() {
        var farewell: RemoteLink.SealedRecord?
        if let lane = lanes.first(where: \.isEstablished), let session = lane.session,
           let data = try? Outbound.unpair.json(id: nil) {
            try? session.send(data)
            log.info("link", "Sent unpair to the bridge")
            // Give the frame a moment to leave before closing.
            lane.session = nil
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 600_000_000)
                session.close()
            }
        } else if let remote, remote.isOn, let data = try? Outbound.unpair.json(id: nil) {
            // Sealed now, while the bridge's key is still pinned.
            farewell = try? remote.seal(data)
            log.info("link", "Telling the bridge to unpair through iCloud")
        }
        teardown()
        remote?.reset(farewell: farewell)
        peerStore.clear()
        (connectors[.wifi] as? WiFiConnector)?.forgetBad()
        bridgeCaps = []
        hotspot.reset()
        hotspot.forgetSaved(keeping: nil)
        lanes.forEach { $0.retryAttempt = 0 }
        updatePhase()
        onPeerChange?()
    }

    private func remoteUnpaired() {
        log.info("link", "The bridge unpaired this iPhone")
        teardown()
        remote?.reset()
        peerStore.clear()
        (connectors[.wifi] as? WiFiConnector)?.forgetBad()
        bridgeCaps = []
        updatePhase()
        onPeerChange?()
    }

    // MARK: - Sessions

    private func startSession(_ transport: FrameTransport, lane: Lane, pairing: PairingLink?, peripheralID: UUID? = nil) {
        let identity: KeyAgreementPrivateKey
        do {
            identity = try identityStore.identity()
        } catch {
            log.error("link", "No identity key: \(error)")
            transport.close()
            return pairing == nil ? scheduleRetry(lane) : finishPairing(.failure(PairingError.connectionLost))
        }
        let session = LinkSession(transport: transport, identity: identity,
                                  pinnedBridgeKey: peerStore.peer?.publicKey, pairing: pairing)
        lane.session = session
        lane.startedAt = Date()
        lane.lastReceived = Date()
        let kind = transport.kind
        session.onPaired = { [weak self] bridgeKey in
            guard let self, let pairing else { return }
            var hints: WiFiHints?
            if pairing.hasWiFi || pairing.hotspotSSID != nil {
                hints = WiFiHints(addresses: pairing.wifiAddresses, port: pairing.wifiPort, lastGood: nil,
                                  ssid: pairing.hotspotSSID, password: pairing.hotspotPassword)
            }
            // Remote mode belonged to the previous pairing; it's turned on again in Settings.
            self.remote?.reset()
            self.peerStore.save(PairedPeer(deviceID: pairing.deviceID, publicKey: bridgeKey, pairedAt: Date(), name: nil,
                                           peripheralID: peripheralID, transport: kind.rawValue, wifi: hints))
            // Addresses that failed with the previous bridge's key may be this bridge.
            (self.connectors[.wifi] as? WiFiConnector)?.forgetBad()
            self.onPeerChange?()
        }
        session.onEstablished = { [weak self, weak session, weak lane] in
            guard let self, let session, let lane, session === lane.session else { return }
            self.established(lane)
        }
        session.onMessage = { [weak self, weak session, weak lane] data in
            guard let self, let session, let lane, session === lane.session else { return }
            self.handle(plaintext: data, lane: lane)
        }
        session.onBulk = { [weak self, weak session, weak lane] data in
            guard let self, let session, let lane, session === lane.session, lane.id == .wifi else { return }
            lane.lastReceived = Date()
            self.files.handleBulk(data)
        }
        session.onLinkMedia = { [weak self, weak session, weak lane] data in
            guard let self, let session, let lane, session === lane.session, lane.id == self.linkMediaLane else { return }
            lane.lastReceived = Date()
            self.linkMediaChannel?.receive(data)
        }
        session.onClose = { [weak self, weak session, weak lane] error in
            guard let self, let session, let lane, session === lane.session else { return }
            self.sessionClosed(lane, session: session, error: error)
        }
        updatePhase()
        session.start()
        ensureWatchdog()
    }

    private func established(_ lane: Lane) {
        let wasConnected = lanes.contains { $0 !== lane && $0.isEstablished }
        log.info("link", "Session established over \(lane.session?.transportKind.rawValue ?? "?")")
        lane.retryAttempt = 0
        lane.connectedSince = Date()
        lane.lastReceived = Date()
        lastSessionError = nil
        if lane.id == .wifi, let transport = lane.session?.frameTransport as? WiFiTransport,
           let endpoint = transport.resolvedEndpoint ?? transport.dialed {
            peerStore.update { peer in
                var hints = peer.wifi ?? WiFiHints()
                hints.lastGood = endpoint
                peer.wifi = hints
            }
        }
        sendRaw(deviceInfo, id: nil, on: lane)
        // The user's port forwarding (D-095) worked: Settings says so.
        if lane.id == .direct, let dialed = lane.session?.frameTransport.dialed, dialed == remote?.manualEndpoint {
            remote?.noteManualAttempt(worked: true)
        }
        // Call audio rides inside the Bluetooth session, or inside the direct connection while no Bluetooth one is up.
        if lane.id == .primary || (lane.id == .direct && linkMediaLane != .primary),
           let session = lane.session, session.carriesLinkMedia {
            closeLinkMedia()
            let channel = LinkMediaChannel(session: session)
            linkMediaChannel = channel
            linkMediaLane = lane.id
            mediaChanged()
            #if DEBUG
            if DebugSettings.linkMediaSelfTest { LinkMediaSelfTest.run(channel) }
            #endif
        }
        finishPairing(.success(()))
        // A nearby link beats the direct connection.
        if lane.id != .direct { retireDirect() }
        updatePhase()
        updateFiles()
        onTransportsChange?()
        if !wasConnected { onEstablished?() }
        for other in lanes where other !== lane { connectIfNeeded(other) }
    }

    private func sessionClosed(_ lane: Lane, session: LinkSession, error: LinkSessionError?) {
        let transport = session.frameTransport as? WiFiTransport
        let wasEstablished = lane.connectedSince != nil
        lane.session = nil
        lane.connectedSince = nil
        if let error { lastSessionError = error }
        log.info("link", "\(lane.id.rawValue) session closed: \(error.map { "\($0)" } ?? "by us")")
        if lane.id == linkMediaLane { closeLinkMedia() }
        if lane.id == .wifi {
            closeMedia()
            if error == .keyMismatch, let endpoint = transport?.resolvedEndpoint ?? transport?.dialed {
                (connectors[.wifi] as? WiFiConnector)?.markBad(endpoint)
            }
        }
        if lane.id == .direct, !wasEstablished {
            // Someone else answered there, or not the protocol: a failed handshake, not a lost connection.
            switch error {
            case .keyMismatch?, .protocolViolation?: dropDirectAddress(of: session)
            default: break
            }
        }
        reroutePending(from: lane)
        updateFiles()
        onTransportsChange?()
        if let attempt = pairingAttempt, attempt.chosen == lane.id {
            let pairingError: PairingError
            switch error {
            case .pairingRejected("expired")?: pairingError = .expired
            case .pairingRejected("used")?: pairingError = .alreadyUsed
            case .pairingRejected("bad_mac")?: pairingError = .wrongCode
            case .pairingRejected("busy")?: pairingError = .busy
            case .pairingMACMismatch?: pairingError = .verificationFailed
            default: pairingError = .connectionLost
            }
            return finishPairing(.failure(pairingError))
        }
        if !lanes.contains(where: \.isEstablished) { latency = nil }
        if error == .keyMismatch, lane.id == .primary {
            // Back off harder: the bridge holds other keys until the user pairs again.
            lane.retryAttempt = max(lane.retryAttempt, 4)
        }
        scheduleRetry(lane)
        if lane.id != .direct, !lanes.contains(where: \.isEstablished) {
            kickDirect(reason: "the nearby link ended")
        }
    }

    private func ensureWatchdog() {
        guard watchdog == nil else { return }
        watchdog = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.checkIdle() }
        }
    }

    private func checkIdle() {
        let now = Date()
        for lane in lanes {
            guard let session = lane.session else { continue }
            guard session.phase == .established else {
                if pairingAttempt == nil, now.timeIntervalSince(lane.startedAt) > Self.handshakeLimit {
                    log.warning("link", "\(lane.id.rawValue) handshake timed out")
                    if lane.id == .direct { dropDirectAddress(of: session) }
                    session.close()
                }
                continue
            }
            let idle = now.timeIntervalSince(lane.lastReceived)
            switch lane.id {
            case .primary:
                if idle > Self.idleLimit {
                    log.warning("link", "Nothing received for \(Int(Self.idleLimit)) s; reconnecting")
                    session.close()
                }
            case .wifi, .direct:
                // The direct connection keeps alive like the Wi-Fi session it is.
                if idle > Self.wifiIdleLimit {
                    let what = lane.id == .wifi ? "Wi-Fi" : "the direct connection"
                    log.warning(lane.id.rawValue, "Nothing received over \(what) for \(Int(Self.wifiIdleLimit)) s; closing it")
                    session.close()
                } else if idle > Self.wifiPingAfter, now.timeIntervalSince(lane.lastPing) > Self.wifiPingAfter {
                    lane.lastPing = now
                    sendRaw(.ping, id: nextID, on: lane)
                    nextID += 1
                }
            }
        }
        retireDirect()
        if lanes.allSatisfy({ $0.session == nil }) {
            watchdog?.invalidate()
            watchdog = nil
        }
    }

    /// One message from the bridge: from a session's `lane`, or (lane nil) from remote mode's mailbox.
    private func handle(plaintext: Data, lane: Lane?, alreadyShown: Bool = false) {
        lane?.lastReceived = Date()
        var inbound: Inbound
        do {
            inbound = try Inbound.decode(plaintext)
        } catch {
            log.warning("link", "Ignored a message that isn't valid JSON")
            return
        }
        inbound.viaMailbox = lane == nil
        inbound.alreadyShown = alreadyShown
        // Remote mode's own events stay here; a reply to a request (`remote.check`) goes to whoever asked. Only its own
        // types: `remote.ring` (D-084, the bridge ringing this iPhone) goes to the app like any other event.
        let answersRequest = inbound.id.map { pending[$0] != nil } ?? false
        if !answersRequest, Self.remoteModeTypes.contains(inbound.type) || inbound.type.hasPrefix("rpair.") {
            return handleRemote(inbound, plaintext: plaintext)
        }
        switch inbound.message {
        case .ping:
            // Through the mailbox nobody waits for the pong.
            if let id = inbound.id, let lane { sendRaw(Outbound("pong", isRequest: false), id: id, on: lane) }
            return
        case .unpair:
            remoteUnpaired()
            return
        case .wifiOffer(let offer):
            // From far away (the mailbox or the direct connection) its addresses and hotspot are of no use.
            if let lane, lane.id != .direct { wifiOffered(offer) }
            return
        case .wifiMedia(let port):
            if let lane, lane.id == .wifi { openMedia(port: port, lane: lane) }
            return
        case .fileOffer, .fileAnswer, .fileDone, .fileCancel:
            if lane?.id == .wifi { files.handle(inbound.message) }
            return
        case .status(let status):
            let caps = Set(status.caps ?? [])
            if caps != bridgeCaps {
                bridgeCaps = caps
                updateFiles()
                if wifi.session == nil, !wifi.connecting { connectIfNeeded(wifi) }
            }
        default:
            break
        }
        if let id = inbound.id, inbound.type != "ping", let pending = pending.removeValue(forKey: id) {
            pending.timer.invalidate()
            if case .error(let reply) = inbound.message {
                pending.continuation.resume(throwing: LinkError.bridge(reply))
            } else {
                pending.continuation.resume(returning: inbound)
            }
            if case .status = inbound.message { onEvent?(inbound) }
            return
        }
        onEvent?(inbound)
    }

    /// Remote mode's own messages (D-092); none is for the app above the link.
    private func handleRemote(_ inbound: Inbound, plaintext: Data) {
        switch inbound.type {
        case "rpair.confirm":
            onRemotePairConfirm?()
        case "remote.state":
            remote?.bridgeState(plaintext)
        case "remote.addr":
            // Authenticated: it came on a session or in a mailbox record sealed with the pinned bridge's key.
            if remote?.bridgeAddresses(plaintext) == true { kickDirect(reason: "new addresses") }
        default:
            break
        }
    }

    // MARK: - Wi-Fi offers, media and files

    private func wifiOffered(_ offer: WiFiOffer) {
        let addresses = Array((offer.ip ?? []).filter { LocalAddress.isAllowed($0) }.prefix(PairingLink.maxAddresses))
        peerStore.update { peer in
            var hints = peer.wifi ?? WiFiHints()
            hints.addresses = addresses
            if let port = offer.port.flatMap({ UInt16(exactly: $0) }), port > 0 { hints.port = port }
            hints.ssid = offer.ssid
            hints.password = offer.password
            peer.wifi = hints
        }
        log.info("wifi", "The bridge offered Wi-Fi: \(addresses.count) address(es)\(offer.ssid != nil ? ", hotspot" : "")")
        hotspot.forgetSaved(keeping: offer.ssid)
        if wifi.session == nil, !addresses.isEmpty { kickWiFi(reason: "wifi.offer") }
        hotspotTimer?.invalidate()
        hotspotTimer = nil
        if let ssid = offer.ssid, let password = offer.password, !wifi.isEstablished {
            hotspotTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: false) { [weak self] _ in
                Task { @MainActor in
                    guard let self, !self.wifi.isEstablished else { return }
                    self.hotspot.join(ssid: ssid, password: password) { [weak self] outcome in
                        if outcome == .joined || outcome == .alreadyJoined { self?.kickWiFi(reason: "joined the hotspot") }
                        if outcome == .joined { self?.onJoinedBridgeHotspot?() }
                    }
                }
            }
        }
    }

    private func openMedia(port: UInt16?, lane: Lane) {
        closeMedia()
        guard let port, let session = lane.session, let keys = session.keys,
              let transport = session.frameTransport as? WiFiTransport, let host = transport.remoteHost else {
            log.info("media", "No media channel this session")
            return
        }
        var loopback = false
        #if DEBUG
        loopback = DebugSettings.wifiOverride != nil
        #endif
        let socket = MediaSocket(host: host, port: port, keys: keys, allowLoopback: loopback)
        socket.onOpen = { [weak self, weak socket] in
            guard let self, let socket, socket === self.media else { return }
            log.info("media", "Media channel open")
            self.mediaChannel = socket
            self.mediaChanged()
            #if DEBUG
            if DebugSettings.mediaSelfTest { MediaSelfTest.run(socket) }
            #endif
        }
        media = socket
        socket.start()
        log.info("media", "Opening the media channel")
    }

    private func closeMedia() {
        guard let socket = media else { return }
        media = nil
        socket.close()
        if mediaChannel != nil {
            mediaChannel = nil
            log.info("media", "Media channel closed")
            mediaChanged()
        }
    }

    private func closeLinkMedia() {
        guard let channel = linkMediaChannel else { return }
        let lane = linkMediaLane
        linkMediaChannel = nil
        linkMediaLane = nil
        channel.close()
        log.info("media", "Call-audio channel inside the \(lane == .direct ? "direct" : "Bluetooth") session closed: \(channel.framesSent) frames, \(channel.packetsSent) packets sent, \(channel.packetsDropped) dropped as late")
        mediaChanged()
    }

    private func mediaChanged() {
        onMediaChannelChange?(mediaChannel)
        NotificationCenter.default.post(name: Self.mediaChannelDidChange, object: self)
        onTransportsChange?()
    }

    private func updateFiles() {
        files.setAvailability(wifiUp: wifi.isEstablished, bridgeHasFiles: bridgeCaps.contains(Capability.files.rawValue))
    }

    /// Sends a control message on the Wi-Fi session (file transfers). Returns false when there's none.
    private func sendOnWiFi(_ message: Outbound) -> Bool {
        guard wifi.isEstablished else { return false }
        return sendRaw(message, id: nil, on: wifi)
    }

    private func sendBulk(_ data: Data, completion: @escaping () -> Void) -> Bool {
        guard let session = wifi.session, session.phase == .established else { return false }
        do {
            try session.sendBulk(data, completion: completion)
            return true
        } catch {
            return false
        }
    }

    /// Debug and diagnostics: what the Wi-Fi side is doing, without addresses.
    var wifiSummary: String {
        let state: String
        if wifi.isEstablished {
            state = "connected"
        } else if wifi.session != nil {
            state = "handshake"
        } else if wifi.connecting {
            state = "searching"
        } else if wifiLaneEnabled {
            state = wifi.retryAt != nil ? "waiting" : "idle"
        } else {
            state = "not offered by the bridge"
        }
        let denied = (connectors[.wifi] as? WiFiConnector)?.localNetworkDenied == true ? ", local network denied" : ""
        let media = mediaChannel != nil ? "open" : (self.media != nil ? "probing" : "closed")
        return "\(state)\(denied); media \(media)"
    }

    var primarySummary: String {
        guard let kind = primaryKind else { return "off" }
        let state = primary.isEstablished ? "connected" : primary.session != nil ? "handshake"
            : primary.connecting ? "searching" : primary.retryAt != nil ? "waiting" : "idle"
        return "\(kind.rawValue) \(state)"
    }

    /// Debug and diagnostics: the direct connection, with address families only.
    var directSummary: String {
        guard let remote, remote.isOn else { return "off" }
        let state = direct.isEstablished ? "connected" : direct.session != nil ? "handshake"
            : direct.connecting ? "connecting" : direct.retryAt != nil ? "waiting" : "idle"
        var families = remote.directCandidates.map(DirectAddress.family)
        if remote.manualEndpoint != nil { families.append("port forwarding") }
        return "\(state); addresses: \(families.isEmpty ? "none" : families.joined(separator: " + "))"
    }

    // MARK: - Messages

    /// The session a message goes on: bulk requests prefer Wi-Fi, everything else Bluetooth; the direct connection
    /// carries everything while it's the only one (a nearby session closes it).
    private func route(_ message: Outbound) -> Lane? {
        if let required = message.lane {
            let only = lane(required)
            return only.isEstablished ? only : nil
        }
        let order = message.isBulk ? [wifi, primary, direct] : [primary, wifi, direct]
        return order.first(where: \.isEstablished)
    }

    func request(_ message: Outbound) async throws -> Inbound {
        _ = try message.json(id: 0)
        if let lane = route(message) {
            return try await withCheckedThrowingContinuation { continuation in
                dispatch(message, continuation: continuation, on: lane)
            }
        }
        guard phase == .remote, let remote else { throw LinkError.notConnected }
        return try await requestThroughMailbox(message, remote: remote)
    }

    /// Remote mode: the request goes into the iCloud mailbox with an id from the mailbox counter (kept across
    /// restarts); the reply comes back through a catch-up, or on a session if one comes up meanwhile.
    private func requestThroughMailbox(_ message: Outbound, remote: RemoteLink) async throws -> Inbound {
        let id = remote.nextRequestID()
        let record = try remote.seal(message.json(id: id))
        log.info("link", "Sending \(message.type) through iCloud")
        return try await withCheckedThrowingContinuation { continuation in
            let timer = Timer.scheduledTimer(withTimeInterval: max(message.timeout, Self.mailboxTimeout),
                                             repeats: false) { [weak self] _ in
                Task { @MainActor in
                    guard let self, let pending = self.pending.removeValue(forKey: id) else { return }
                    log.warning("link", "Request \(message.type) through iCloud timed out")
                    pending.continuation.resume(throwing: LinkError.timeout)
                }
            }
            pending[id] = Pending(continuation: continuation, timer: timer, message: message, lane: nil)
            pollMailboxWhileWaiting()
            Task {
                do {
                    try await remote.write(record)
                } catch {
                    guard let pending = self.pending.removeValue(forKey: id) else { return }
                    pending.timer.invalidate()
                    pending.continuation.resume(throwing: LinkError.notConnected)
                }
            }
        }
    }

    /// While a request waits for its reply through iCloud and the app is open, look for it every few seconds rather
    /// than rely on the silent push alone, which iOS may hold back.
    private func pollMailboxWhileWaiting() {
        guard mailboxPoll == nil else { return }
        mailboxPoll = Timer.scheduledTimer(withTimeInterval: Self.mailboxPollInterval, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                guard self.pending.values.contains(where: { $0.lane == nil }) else {
                    self.mailboxPoll?.invalidate()
                    self.mailboxPoll = nil
                    return
                }
                await self.remoteCatchUp()
            }
        }
    }

    private func dispatch(_ message: Outbound, continuation: CheckedContinuation<Inbound, Error>, on lane: Lane) {
        let id = nextID
        nextID += 1
        guard let session = lane.session, session.phase == .established, let data = try? message.json(id: id) else {
            return continuation.resume(throwing: LinkError.notConnected)
        }
        let timer = Timer.scheduledTimer(withTimeInterval: message.timeout, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self, let pending = self.pending.removeValue(forKey: id) else { return }
                log.warning("link", "Request \(message.type) timed out")
                pending.continuation.resume(throwing: LinkError.timeout)
            }
        }
        pending[id] = Pending(continuation: continuation, timer: timer, message: message, lane: lane.id)
        do {
            try session.send(data)
        } catch {
            pending.removeValue(forKey: id)?.timer.invalidate()
            continuation.resume(throwing: LinkError.notConnected)
            session.close()
        }
    }

    /// A lane closed: its bulk requests go again on the other session (PROTOCOL.md), the rest fail.
    private func reroutePending(from lane: Lane) {
        let affected = pending.filter { $0.value.lane == lane.id }
        for (id, item) in affected {
            pending.removeValue(forKey: id)
            item.timer.invalidate()
            if item.message.isBulk, let other = lanes.first(where: { $0 !== lane && $0.isEstablished }) {
                log.info("link", "Sending \(item.message.type) again over \(other.id.rawValue)")
                dispatch(item.message, continuation: item.continuation, on: other)
            } else {
                item.continuation.resume(throwing: LinkError.notConnected)
            }
        }
    }

    func send(_ message: Outbound) {
        if let lane = route(message) {
            sendRaw(message, id: nil, on: lane)
            return
        }
        guard phase == .remote, let remote, let record = try? remote.seal(message.json(id: nil)) else { return }
        log.info("link", "Sending \(message.type) through iCloud")
        Task { try? await remote.write(record) }
    }

    @discardableResult
    private func sendRaw(_ message: Outbound, id: Int?, on lane: Lane) -> Bool {
        guard let session = lane.session, session.phase == .established, let data = try? message.json(id: id) else {
            return false
        }
        do {
            try session.send(data)
            return true
        } catch {
            session.close()
            return false
        }
    }

    func measureLatency() async {
        // A session's round trip only; never a ping through iCloud.
        guard lanes.contains(where: \.isEstablished) else { return }
        let start = Date()
        if (try? await request(.ping)) != nil {
            latency = Date().timeIntervalSince(start)
        }
    }

    // MARK: - Remote mode (D-092)

    /// Remote mode's catch-up (on launch, when the app becomes active, on a push, and while a reply is awaited):
    /// what the bridge left in iCloud, handled like a session's messages. Returns whether anything arrived.
    @discardableResult
    func remoteCatchUp() async -> Bool {
        guard let remote, remote.isOn, allowed, peerStore.peer != nil, pairingAttempt == nil else { return false }
        return await remote.catchUp(sessionUp: lanes.contains(where: \.isEstablished))
    }

    /// Turns remote mode on (Settings). The bridge gets `remote.config` over the session, so one must be up.
    func turnOnRemote() async throws {
        guard let remote, !remote.isOn else { return }
        guard lanes.contains(where: \.isEstablished) else { throw RemoteError.needsNearbyLink }
        try await remote.turnOn { message in try await self.request(message) }
    }

    /// Turns remote mode off: `remote.config` off to the bridge (over a session, or through the mailbox one last
    /// time), then everything of it on this iPhone and in iCloud goes.
    func turnOffRemote() async {
        guard let remote, remote.isOn else { return }
        let off = Outbound.remoteConfigOff
        if route(off) != nil {
            _ = try? await request(off)
            await remote.reset().value
        } else {
            let farewell = try? remote.seal(off.json(id: remote.nextRequestID()))
            await remote.reset(farewell: farewell).value
        }
    }

    /// Remote pairing finished on this side: the bridge is pinned and remote mode is on with the invitation's
    /// mailboxes. The link reaches the bridge through iCloud until the phones meet.
    func adoptRemotePairing(peer: PairedPeer, settings: RemoteSettings) {
        peerStore.save(peer)
        bridgeCaps = []
        (connectors[.wifi] as? WiFiConnector)?.forgetBad()
        remote?.adopt(settings)
        lanes.forEach(connectIfNeeded)
        updatePhase()
        onPeerChange?()
    }

    private func failPending(_ error: Error) {
        let all = pending
        pending.removeAll()
        for item in all.values {
            item.timer.invalidate()
            item.continuation.resume(throwing: error)
        }
    }
}

/// Developer settings that exist only in debug builds (D-018). Release builds always use Bluetooth (and Wi-Fi when
/// the bridge offers it).
enum DebugSettings {
    static let transportKey = "debug.transport"

    /// Debug builds only: `-PairlineMarketing` hides demo labels for website and App Store screenshots.
    static var marketingScreenshots: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("-PairlineMarketing")
        #else
        return false
        #endif
    }

    /// The first lane's transport: Bluetooth, or in debug builds the TCP link (Simulator default) or `.wifi`, which
    /// means "no first lane" (Wi-Fi only, like a bridge that can't hold a Bluetooth link).
    static var pairingTransport: TransportKind {
        #if DEBUG
        if let raw = UserDefaults.standard.string(forKey: transportKey), let kind = TransportKind(rawValue: raw) {
            return kind
        }
        #if targetEnvironment(simulator)
        return .tcp
        #else
        return .bluetooth
        #endif
        #else
        return .bluetooth
        #endif
    }

    #if DEBUG
    /// `-PairlineWiFiHost <host> -PairlineWiFiPort <port>`: a fixed Wi-Fi candidate tried first (loopback allowed),
    /// e.g. the Android emulator through `adb emu redir`, or protocol/tools/fake_bridge.py.
    static var wifiOverride: WiFiEndpoint? {
        let args = ProcessInfo.processInfo.arguments
        func value(_ name: String) -> String? {
            guard let index = args.firstIndex(of: name), index + 1 < args.count else { return nil }
            return args[index + 1]
        }
        guard let host = value("-PairlineWiFiHost") else { return nil }
        let port = value("-PairlineWiFiPort").flatMap(UInt16.init) ?? 47820
        return WiFiEndpoint(host: host, port: port)
    }

    /// `-PairlineMediaTest`: when the media channel opens, send test packets and log how many came back.
    static var mediaSelfTest: Bool { ProcessInfo.processInfo.arguments.contains("-PairlineMediaTest") }
    /// `-PairlineLinkMediaTest`: when the Bluetooth (or debug TCP) session's call-audio channel appears, send a second
    /// of an Opus-encoded tone and log what came back (protocol/tools/fake_bridge.py echoes `MEDIA` frames; D-072).
    static var linkMediaSelfTest: Bool { ProcessInfo.processInfo.arguments.contains("-PairlineLinkMediaTest") }
    #endif
}

#if DEBUG
/// Debug builds only: sends 50 numbered packets 20 ms apart over a new media channel and logs how many echoes came
/// back (protocol/tools/fake_bridge.py echoes media packets).
enum MediaSelfTest {
    private final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        private var matching = 0

        func add(_ packet: Data) {
            lock.lock()
            count += 1
            if packet.starts(with: Data("pairline-media-test".utf8)) { matching += 1 }
            lock.unlock()
        }

        var values: (Int, Int) {
            lock.lock()
            defer { lock.unlock() }
            return (count, matching)
        }
    }

    static func run(_ channel: MediaChannel) {
        let counter = Counter()
        let started = Date()
        channel.onPacket = { packet in counter.add(packet) }
        let box = UncheckedChannel(channel: channel)
        DispatchQueue.global(qos: .userInitiated).async {
            for n in 0..<50 {
                var packet = Data("pairline-media-test".utf8)
                packet.append(Data.bigEndian(UInt32(n)))
                packet.append(Data(repeating: UInt8(n & 0xff), count: 160))
                box.channel.send(packet)
                Thread.sleep(forTimeInterval: 0.02)
            }
            Thread.sleep(forTimeInterval: 1)
            let (count, matching) = counter.values
            let ms = Int(Date().timeIntervalSince(started) * 1000)
            log.info("media", "Media self-test: sent 50 packets, got \(count) back (\(matching) echoes) in \(ms) ms")
        }
    }

    private struct UncheckedChannel: @unchecked Sendable {
        let channel: MediaChannel
    }
}

/// Debug builds only: call audio inside the session end to end (D-072). Encodes 50 frames of a 440 Hz tone with iOS's
/// Opus encoder, sends them 20 ms apart over the link media channel, decodes what fake_bridge.py echoes, and logs the
/// counts, the packet size and the decoded level (a 440 Hz tone at 8000 peak is about 5600 RMS).
enum LinkMediaSelfTest {
    private final class Results: @unchecked Sendable {
        private let lock = NSLock()
        private var echoes = 0
        private var decoded = 0
        private var levels: [Double] = []
        private var firstEcho: Date?
        let decoder = try? OpusDecoder()

        /// On the channel's delivery queue (the decoder's only thread).
        func add(_ packet: Data) {
            guard let (_, opus) = AudioPackets.decodeOpus(packet) else { return }
            let frame = decoder?.decode(opus)
            lock.lock()
            echoes += 1
            if firstEcho == nil { firstEcho = Date() }
            if let frame {
                decoded += 1
                levels.append(JitterBuffer.rms(frame))
            }
            lock.unlock()
        }

        var summary: (echoes: Int, decoded: Int, level: Int, first: Date?) {
            lock.lock()
            defer { lock.unlock() }
            let tail = levels.suffix(20)
            let level = tail.isEmpty ? 0 : Int(tail.reduce(0, +) / Double(tail.count))
            return (echoes, decoded, level, firstEcho)
        }
    }

    static func run(_ channel: LinkMediaChannel) {
        let results = Results()
        channel.onPacket = { results.add($0) }
        let box = UncheckedChannel(channel: channel)
        DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + 1) {
            guard let encoder = try? OpusEncoder() else {
                return log.warning("media", "Link media self-test: no Opus encoder")
            }
            let started = Date()
            var sizes: [Int] = []
            for n in 0..<50 {
                let tone = (0..<Opus.frameSamples).map { i -> Int16 in
                    Int16(8_000 * sin(2 * .pi * 440 * Double(n * Opus.frameSamples + i) / Opus.sampleRate))
                }
                if let opus = encoder.encode(tone) {
                    sizes.append(opus.count)
                    box.channel.send(AudioPackets.encodeOpus(sequence: UInt16(n), opus: opus))
                }
                Thread.sleep(forTimeInterval: 0.02)
            }
            Thread.sleep(forTimeInterval: 1)
            let s = results.summary
            let average = sizes.isEmpty ? 0 : sizes.reduce(0, +) / sizes.count
            let firstMs = s.first.map { Int($0.timeIntervalSince(started) * 1000) } ?? -1
            log.info("media", "Link media self-test: sent \(sizes.count) Opus packets (\(average) B average, \(encoder.bitRate / 1000) kbit/s), got \(s.echoes) back, decoded \(s.decoded), level \(s.level), first echo after \(firstMs) ms; channel sent \(box.channel.framesSent) frames, dropped \(box.channel.packetsDropped)")
        }
    }

    private struct UncheckedChannel: @unchecked Sendable {
        let channel: LinkMediaChannel
    }
}
#endif
