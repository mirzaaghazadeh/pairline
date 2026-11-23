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

    var isConnected: Bool { self == .connected }
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
            case "invalid": return String(localized: "The bridge phone couldn't use that request.")
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
    var onPhaseChange: ((LinkPhase) -> Void)? { get set }
    /// Events and unsolicited messages from the bridge.
    var onEvent: ((Inbound) -> Void)? { get set }
    /// Called after every successful handshake (a good moment to sync).
    var onEstablished: (() -> Void)? { get set }
    func request(_ message: Outbound) async throws -> Inbound
    func send(_ message: Outbound)
    func measureLatency() async
    /// Asks for a reconnect now (user action or app foregrounded).
    func reconnectNow()
}

/// The real link: transport + session + request/response + reconnection.
@MainActor
final class LinkManager: BridgeLink {
    private struct Pending {
        let continuation: CheckedContinuation<Inbound, Error>
        let timer: Timer
    }

    private struct PairingAttempt {
        let link: PairingLink
        let kind: TransportKind
        let continuation: CheckedContinuation<Void, Error>
        let deadline: Timer
    }

    static let idleLimit: TimeInterval = 180

    private(set) var phase: LinkPhase = .notPaired {
        didSet { if phase != oldValue { onPhaseChange?(phase) } }
    }
    private(set) var latency: TimeInterval?
    private(set) var lastSessionError: LinkSessionError?
    private(set) var connectedSince: Date?
    var transportKind: TransportKind { session?.transportKind ?? connector?.kind ?? preferredKind }

    var onPhaseChange: ((LinkPhase) -> Void)?
    var onEvent: ((Inbound) -> Void)?
    var onEstablished: (() -> Void)?
    var onPeerChange: (() -> Void)?

    let identityStore: IdentityStore
    let peerStore: PeerStore
    private let makeConnector: (TransportKind) -> LinkConnector
    private var connectors: [TransportKind: LinkConnector] = [:]
    private var connector: LinkConnector?
    private var connecting = false
    private var session: LinkSession?
    private var pending: [Int: Pending] = [:]
    private var nextID = 1
    private var retryAttempt = 0
    private var retryTimer: Timer?
    private var watchdog: Timer?
    private var lastReceived = Date()
    private var allowed = false
    private var pairingAttempt: PairingAttempt?
    private let deviceInfo: Outbound

    var preferredKind: TransportKind {
        if let raw = peerStore.peer?.transport, let kind = TransportKind(rawValue: raw) { return kind }
        return DebugSettings.pairingTransport
    }

    init(identityStore: IdentityStore, peerStore: PeerStore, deviceInfo: Outbound,
         makeConnector: @escaping (TransportKind) -> LinkConnector) {
        self.identityStore = identityStore
        self.peerStore = peerStore
        self.deviceInfo = deviceInfo
        self.makeConnector = makeConnector
        // Create the Bluetooth central right away when paired over Bluetooth, so iOS can restore it.
        if peerStore.peer?.transport == TransportKind.bluetooth.rawValue {
            _ = connector(for: .bluetooth)
        }
        updateIdlePhase()
    }

    // MARK: - Control

    /// Link features are allowed (trial active or purchased). Called on launch and whenever access changes.
    func setAllowed(_ allowed: Bool) {
        guard allowed != self.allowed else { return }
        self.allowed = allowed
        if allowed {
            connectIfNeeded()
        } else {
            teardown()
            updateIdlePhase()
        }
    }

    func reconnectNow() {
        guard allowed, peerStore.peer != nil, session == nil, pairingAttempt == nil else { return }
        retryTimer?.invalidate()
        retryTimer = nil
        retryAttempt = 0
        if !connecting { connectIfNeeded() }
    }

    private func connector(for kind: TransportKind) -> LinkConnector {
        if let existing = connectors[kind] { return existing }
        let new = makeConnector(kind)
        new.onAvailabilityChange = { [weak self] _ in self?.availabilityChanged() }
        connectors[kind] = new
        return new
    }

    private func availabilityChanged() {
        guard let connector else { return }
        if connector.availability == .poweredOn {
            if session == nil, pairingAttempt == nil {
                connecting = false
                connectIfNeeded()
            }
        } else if session == nil {
            updateIdlePhase()
        }
    }

    private func updateIdlePhase() {
        guard session == nil, pairingAttempt == nil else { return }
        if peerStore.peer == nil {
            phase = .notPaired
            return
        }
        if !allowed {
            phase = .locked
            return
        }
        switch connectors[preferredKind]?.availability {
        case .poweredOff?: phase = .bluetoothOff
        case .unauthorized?: phase = .bluetoothUnauthorized
        case .unsupported?: phase = .bluetoothUnsupported
        default: phase = .searching
        }
    }

    private func connectIfNeeded() {
        guard allowed, let peer = peerStore.peer, session == nil, pairingAttempt == nil, !connecting else { return }
        retryTimer?.invalidate()
        retryTimer = nil
        let connector = connector(for: preferredKind)
        self.connector = connector
        connecting = true
        updateIdlePhase()
        connector.connect(ConnectTarget(deviceID: peer.deviceID, knownPeripheral: peer.peripheralID)) { [weak self] result in
            guard let self else { return }
            self.connecting = false
            switch result {
            case .success(let (transport, peripheralID)):
                if let peripheralID { self.peerStore.update { $0.peripheralID = peripheralID } }
                self.startSession(transport, pairing: nil)
            case .failure(let error):
                if case ConnectError.cancelled = error { return }
                self.scheduleRetry()
            }
        }
    }

    private func scheduleRetry() {
        guard allowed, peerStore.peer != nil, pairingAttempt == nil else { return updateIdlePhase() }
        let delay = min(30, pow(2, Double(retryAttempt)))
        retryAttempt += 1
        phase = .waiting(until: Date().addingTimeInterval(delay))
        retryTimer?.invalidate()
        retryTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.connectIfNeeded() }
        }
    }

    private func teardown() {
        retryTimer?.invalidate()
        retryTimer = nil
        watchdog?.invalidate()
        watchdog = nil
        connectors.values.forEach { $0.cancel() }
        connecting = false
        let old = session
        session = nil
        old?.close()
        failPending(LinkError.notConnected)
        connectedSince = nil
        latency = nil
    }

    // MARK: - Pairing

    /// Pairs with the bridge in `link` (scanned QR code, or a pasted link in debug builds), then stays connected.
    func pair(with link: PairingLink) async throws {
        guard allowed else { throw PairingError.locked }
        teardown()
        if let attempt = pairingAttempt {
            pairingAttempt = nil
            attempt.deadline.invalidate()
            attempt.continuation.resume(throwing: PairingError.connectionLost)
        }
        let kind = DebugSettings.pairingTransport
        phase = .pairing
        log.info("pairing", "Pairing over \(kind.rawValue): \(link.redactedDescription)")
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let deadline = Timer.scheduledTimer(withTimeInterval: 60, repeats: false) { [weak self] _ in
                Task { @MainActor in self?.finishPairing(.failure(PairingError.bridgeNotFound)) }
            }
            pairingAttempt = PairingAttempt(link: link, kind: kind, continuation: continuation, deadline: deadline)
            attemptPairingConnection()
        }
    }

    func cancelPairing() {
        finishPairing(.failure(CancellationError()))
    }

    private func attemptPairingConnection() {
        guard let attempt = pairingAttempt else { return }
        let connector = connector(for: attempt.kind)
        self.connector = connector
        if connector.availability == .poweredOff || connector.availability == .unauthorized
            || connector.availability == .unsupported {
            return finishPairing(.failure(PairingError.bluetoothUnavailable))
        }
        connector.connect(ConnectTarget(deviceID: attempt.link.deviceID, knownPeripheral: nil)) { [weak self] result in
            guard let self, self.pairingAttempt != nil else { return }
            switch result {
            case .success(let (transport, peripheralID)):
                self.startSession(transport, pairing: attempt.link, peripheralID: peripheralID)
            case .failure(let error):
                if case ConnectError.cancelled = error { return }
                // TCP fails fast when nothing listens yet; keep trying until the deadline.
                Timer.scheduledTimer(withTimeInterval: 2, repeats: false) { [weak self] _ in
                    Task { @MainActor in self?.attemptPairingConnection() }
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
            attempt.continuation.resume()
        case .failure(let error):
            log.warning("pairing", "Pairing failed: \(error)")
            if session?.phase != .established { teardown() }
            updateIdlePhase()
            attempt.continuation.resume(throwing: error)
            if peerStore.peer != nil { connectIfNeeded() }
        }
    }

    // MARK: - Unpair

    /// Forgets the bridge. If connected, tells the bridge first so it forgets this iPhone too.
    func unpair() {
        if let session, session.phase == .established, let data = try? Outbound.unpair.json(id: nil) {
            try? session.send(data)
            log.info("link", "Sent unpair to the bridge")
            // Give the frame a moment to leave before closing.
            let closing = session
            self.session = nil
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 600_000_000)
                closing.close()
            }
        }
        teardown()
        peerStore.clear()
        retryAttempt = 0
        updateIdlePhase()
        onPeerChange?()
    }

    private func remoteUnpaired() {
        log.info("link", "The bridge unpaired this iPhone")
        teardown()
        peerStore.clear()
        updateIdlePhase()
        onPeerChange?()
    }

    // MARK: - Session

    private func startSession(_ transport: FrameTransport, pairing: PairingLink?, peripheralID: UUID? = nil) {
        let identity: KeyAgreementPrivateKey
        do {
            identity = try identityStore.identity()
        } catch {
            log.error("link", "No identity key: \(error)")
            transport.close()
            return pairing == nil ? scheduleRetry() : finishPairing(.failure(PairingError.connectionLost))
        }
        let session = LinkSession(transport: transport, identity: identity,
                                  pinnedBridgeKey: peerStore.peer?.publicKey, pairing: pairing)
        self.session = session
        phase = pairing == nil ? .securing : .pairing
        let kind = transport.kind
        session.onPaired = { [weak self] bridgeKey in
            guard let self, let pairing else { return }
            self.peerStore.save(PairedPeer(deviceID: pairing.deviceID, publicKey: bridgeKey, pairedAt: Date(), name: nil,
                                           peripheralID: peripheralID, transport: kind.rawValue))
            self.onPeerChange?()
        }
        session.onEstablished = { [weak self, weak session] in
            guard let self, let session, session === self.session else { return }
            self.established()
        }
        session.onMessage = { [weak self, weak session] data in
            guard let self, let session, session === self.session else { return }
            self.handle(plaintext: data)
        }
        session.onClose = { [weak self, weak session] error in
            guard let self, session === self.session || session == nil else { return }
            self.sessionClosed(error)
        }
        session.start()
    }

    private func established() {
        log.info("link", "Session established over \(transportKind.rawValue)")
        retryAttempt = 0
        lastSessionError = nil
        connectedSince = Date()
        lastReceived = Date()
        nextID = 1
        phase = .connected
        send(deviceInfo)
        watchdog?.invalidate()
        watchdog = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.checkIdle() }
        }
        finishPairing(.success(()))
        onEstablished?()
    }

    private func checkIdle() {
        guard let session, session.phase == .established else { return }
        if Date().timeIntervalSince(lastReceived) > Self.idleLimit {
            log.warning("link", "Nothing received for \(Int(Self.idleLimit)) s; reconnecting")
            session.close()
        }
    }

    private func sessionClosed(_ error: LinkSessionError?) {
        session = nil
        watchdog?.invalidate()
        watchdog = nil
        connectedSince = nil
        failPending(LinkError.notConnected)
        if let error { lastSessionError = error }
        log.info("link", "Session closed: \(error.map { "\($0)" } ?? "by us")")
        if pairingAttempt != nil {
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
        if error == .keyMismatch {
            // Back off harder: the bridge holds other keys until the user pairs again.
            retryAttempt = max(retryAttempt, 4)
        }
        scheduleRetry()
    }

    private func handle(plaintext: Data) {
        lastReceived = Date()
        let inbound: Inbound
        do {
            inbound = try Inbound.decode(plaintext)
        } catch {
            log.warning("link", "Ignored a message that isn't valid JSON")
            return
        }
        switch inbound.message {
        case .ping:
            if let id = inbound.id { sendRaw(Outbound("pong", isRequest: false), id: id) }
            return
        case .unpair:
            remoteUnpaired()
            return
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

    // MARK: - Messages

    func request(_ message: Outbound) async throws -> Inbound {
        guard let session, session.phase == .established else { throw LinkError.notConnected }
        let id = nextID
        nextID += 1
        let data = try message.json(id: id)
        return try await withCheckedThrowingContinuation { continuation in
            let timer = Timer.scheduledTimer(withTimeInterval: message.timeout, repeats: false) { [weak self] _ in
                Task { @MainActor in
                    guard let self, let pending = self.pending.removeValue(forKey: id) else { return }
                    log.warning("link", "Request \(message.type) timed out")
                    pending.continuation.resume(throwing: LinkError.timeout)
                }
            }
            pending[id] = Pending(continuation: continuation, timer: timer)
            do {
                try session.send(data)
            } catch {
                pending.removeValue(forKey: id)?.timer.invalidate()
                continuation.resume(throwing: LinkError.notConnected)
                session.close()
            }
        }
    }

    func send(_ message: Outbound) {
        sendRaw(message, id: nil)
    }

    private func sendRaw(_ message: Outbound, id: Int?) {
        guard let session, session.phase == .established, let data = try? message.json(id: id) else { return }
        do {
            try session.send(data)
        } catch {
            session.close()
        }
    }

    func measureLatency() async {
        let start = Date()
        if (try? await request(.ping)) != nil {
            latency = Date().timeIntervalSince(start)
        }
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

/// Developer settings that exist only in debug builds (D-018). Release builds always use Bluetooth.
enum DebugSettings {
    static let transportKey = "debug.transport"

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
}
