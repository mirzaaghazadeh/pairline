import Foundation
import Network

/// A bridge address on the local network and its Wi-Fi TCP port.
struct WiFiEndpoint: Codable, Hashable {
    var host: String
    var port: UInt16

    var key: String { "\(host)|\(port)" }
}

/// What the iPhone remembers about reaching the bridge over Wi-Fi (stored with the paired peer, in the Keychain).
struct WiFiHints: Codable, Equatable {
    /// From the last `wifi.offer` or the pair link.
    var addresses: [String] = []
    var port: UInt16?
    /// The endpoint the last Wi-Fi session used.
    var lastGood: WiFiEndpoint?
    /// The bridge's hotspot, while it was on.
    var ssid: String?
    var password: String?

    /// Candidates in the order PROTOCOL.md gives: the one that worked last, then the offered addresses.
    var endpoints: [WiFiEndpoint] {
        var out: [WiFiEndpoint] = []
        if let lastGood { out.append(lastGood) }
        if let port {
            for address in addresses where LocalAddress.isAllowed(address) {
                let endpoint = WiFiEndpoint(host: address, port: port)
                if !out.contains(endpoint) { out.append(endpoint) }
            }
        }
        return out
    }
}

/// The production Wi-Fi link (PROTOCOL.md, "Wi-Fi"): frames over TCP, each preceded by its 4-byte big-endian length,
/// carrying the same pairing, handshake and records as Bluetooth, plus `BULK` frames. Callbacks run on the main queue.
final class WiFiTransport: FrameTransport {
    let kind: TransportKind = .wifi
    /// What was dialed (an address from a hint, or a DNS-SD service resolved to `remoteHost`).
    let label: String
    let dialed: WiFiEndpoint?
    private let connection: NWConnection
    private var deframer = TCPDeframer()
    private var closed = false

    var onFrame: ((Data) -> Void)?
    var onClose: ((Error?) -> Void)?

    fileprivate init(connection: NWConnection, dialed: WiFiEndpoint?, label: String) {
        self.connection = connection
        self.dialed = dialed
        self.label = label
    }

    /// The bridge's address as connected; the media channel sends its datagrams there.
    var remoteHost: NWEndpoint.Host? {
        if case .hostPort(let host, _)? = connection.currentPath?.remoteEndpoint { return host }
        if case .hostPort(let host, _) = connection.endpoint { return host }
        return nil
    }

    /// The endpoint the session ended up on, to remember as "last good".
    var resolvedEndpoint: WiFiEndpoint? {
        if case .hostPort(let host, let port)? = connection.currentPath?.remoteEndpoint {
            return WiFiEndpoint(host: WiFiTransport.text(host), port: port.rawValue)
        }
        return dialed
    }

    static func text(_ host: NWEndpoint.Host) -> String {
        switch host {
        case .ipv4(let v4): return "\(v4)"
        case .ipv6(let v6): return "\(v6)" // keeps a link-local address's zone ("fe80::1%en0"), which it needs
        case .name(let name, _): return name
        @unknown default: return "\(host)"
        }
    }

    fileprivate func startReceiving() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            guard let self, !self.closed else { return }
            if let data, !data.isEmpty {
                do {
                    for frame in try self.deframer.feed(data) {
                        self.onFrame?(frame)
                        if self.closed { return }
                    }
                } catch {
                    self.close()
                    return
                }
            }
            if isComplete || error != nil {
                self.finish(error)
            } else {
                self.startReceiving()
            }
        }
    }

    func send(_ frame: Data) {
        send(frame, completion: nil)
    }

    func send(_ frame: Data, completion: (() -> Void)?) {
        guard !closed else { return }
        connection.send(content: TCPFraming.encode(frame), completion: .contentProcessed { [weak self] error in
            if error != nil { self?.close() }
            completion?()
        })
    }

    func close() {
        guard !closed else { return }
        connection.cancel()
        finish(nil)
    }

    fileprivate func finish(_ error: Error?) {
        guard !closed else { return }
        closed = true
        connection.cancel()
        let callback = onClose
        onClose = nil
        onFrame = nil
        callback?(error)
    }
}

/// Finds the bridge on the local network and opens a `WiFiTransport` to it. Candidates, in order: an explicit list
/// (the pair link while pairing), the remembered endpoints, then every `_pairline._tcp` service found by browsing
/// (PROTOCOL.md, "Discovery"). Only private, link-local and unique-local addresses are dialed. Runs on the main queue.
final class WiFiConnector: LinkConnector {
    static let serviceType = "_pairline._tcp"
    /// How long one candidate may take to connect.
    private static let candidateTimeout: TimeInterval = 4
    /// How long an attempt waits for browse results when it has run out of candidates.
    private static let browseWait: TimeInterval = 6
    /// A candidate that turned out to be someone else's bridge is skipped this long.
    static let badCandidateTime: TimeInterval = 600

    let kind: TransportKind = .wifi
    let availability: BluetoothAvailability = .poweredOn
    var onAvailabilityChange: ((BluetoothAvailability) -> Void)?
    /// The local network changed (joined a Wi-Fi network, for example): a good moment to try again.
    var onNetworkChange: (() -> Void)?
    /// The remembered endpoints, asked for at the start of each attempt.
    var hints: (() -> WiFiHints?)?
    /// The user turned off Local Network access for Pairline (shown in diagnostics).
    private(set) var localNetworkDenied = false

    private var browser: NWBrowser?
    private var services: [NWEndpoint] = []
    private var bad: [String: Date] = [:]
    private var pathMonitor: NWPathMonitor?
    private var lastPathKey: String?

    private var completion: ((Result<(FrameTransport, UUID?), Error>) -> Void)?
    private var queue: [(endpoint: NWEndpoint, dialed: WiFiEndpoint?, label: String)] = []
    private var tried = Set<String>()
    private var explicitOnly = false
    private var pending: NWConnection?
    private var candidateTimer: Timer?
    private var waitTimer: Timer?

    init() {
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let key = path.availableInterfaces.filter { $0.type != .cellular }.map(\.name).sorted().joined(separator: ",")
                + (path.status == .satisfied ? "+" : "-")
            guard key != self.lastPathKey else { return }
            let first = self.lastPathKey == nil
            self.lastPathKey = key
            if !first { self.onNetworkChange?() }
        }
        monitor.start(queue: .main)
        pathMonitor = monitor
    }

    deinit {
        pathMonitor?.cancel()
        browser?.cancel()
    }

    /// Skips this endpoint for 10 minutes: its handshake showed it's another person's bridge.
    func markBad(_ endpoint: WiFiEndpoint) {
        bad[endpoint.key] = Date().addingTimeInterval(Self.badCandidateTime)
    }

    /// The pinned bridge changed (paired or unpaired): an address that held "someone else's bridge" may be ours now.
    func forgetBad() {
        bad = [:]
    }

    func connect(_ target: ConnectTarget, completion: @escaping (Result<(FrameTransport, UUID?), Error>) -> Void) {
        cancel()
        self.completion = completion
        explicitOnly = target.wifiExplicitOnly
        tried = []
        queue = []
        let now = Date()
        bad = bad.filter { $0.value > now }
        var endpoints = target.wifi
        #if DEBUG
        if let fixed = DebugSettings.wifiOverride { endpoints.insert(fixed, at: 0) }
        #endif
        if !explicitOnly { endpoints += hints?()?.endpoints ?? [] }
        for endpoint in endpoints { enqueue(endpoint) }
        if !explicitOnly {
            startBrowsing()
            for service in services { enqueue(service: service) }
        }
        next()
    }

    func cancel() {
        stopBrowsing()
        candidateTimer?.invalidate()
        candidateTimer = nil
        waitTimer?.invalidate()
        waitTimer = nil
        pending?.stateUpdateHandler = nil
        pending?.cancel()
        pending = nil
        queue = []
        let callback = completion
        completion = nil
        callback?(.failure(ConnectError.cancelled))
    }

    // MARK: - Candidates

    private func allowed(_ endpoint: WiFiEndpoint) -> Bool {
        #if DEBUG
        if endpoint == DebugSettings.wifiOverride { return true }
        #endif
        return LocalAddress.isAllowed(endpoint.host)
    }

    private func enqueue(_ endpoint: WiFiEndpoint) {
        guard allowed(endpoint), bad[endpoint.key] == nil, !tried.contains(endpoint.key),
              let port = NWEndpoint.Port(rawValue: endpoint.port) else { return }
        tried.insert(endpoint.key)
        queue.append((.hostPort(host: NWEndpoint.Host(endpoint.host), port: port), endpoint, "address"))
    }

    private func enqueue(service: NWEndpoint) {
        let key = "\(service)"
        guard !tried.contains(key) else { return }
        tried.insert(key)
        queue.append((service, nil, "service"))
    }

    /// Browsing runs only while an attempt is looking for the bridge (mDNS traffic costs battery).
    private func stopBrowsing() {
        browser?.cancel()
        browser = nil
    }

    private func startBrowsing() {
        guard browser == nil else { return }
        let parameters = NWParameters()
        parameters.includePeerToPeer = false
        let browser = NWBrowser(for: .bonjour(type: Self.serviceType, domain: nil), using: parameters)
        browser.stateUpdateHandler = { [weak self, weak browser] state in
            guard let self, let browser, browser === self.browser else { return }
            switch state {
            case .failed(let error):
                if case .dns(let code) = error, code == -65570 /* kDNSServiceErr_PolicyDenied */ {
                    self.localNetworkDenied = true
                    log.warning("wifi", "Local Network access is off for Pairline")
                }
                browser.cancel()
                self.browser = nil
            case .waiting(let error):
                if case .dns(let code) = error, code == -65570 { self.localNetworkDenied = true }
            case .ready:
                self.localNetworkDenied = false
            default:
                break
            }
        }
        browser.browseResultsChangedHandler = { [weak self, weak browser] results, _ in
            guard let self, let browser, browser === self.browser else { return }
            self.services = results.map(\.endpoint)
            guard self.completion != nil, !self.explicitOnly else { return }
            let before = self.queue.count
            for service in self.services { self.enqueue(service: service) }
            if self.queue.count > before, self.pending == nil { self.next() }
        }
        self.browser = browser
        browser.start(queue: .main)
    }

    // MARK: - Connecting

    private func next() {
        guard completion != nil, pending == nil else { return }
        waitTimer?.invalidate()
        waitTimer = nil
        guard !queue.isEmpty else {
            if explicitOnly { return fail() }
            // Nothing (more) to try: give browsing a moment, then give up for this attempt.
            waitTimer = Timer.scheduledTimer(withTimeInterval: Self.browseWait, repeats: false) { [weak self] _ in
                self?.fail()
            }
            return
        }
        let candidate = queue.removeFirst()
        let options = NWProtocolTCP.Options()
        options.noDelay = true
        options.connectionTimeout = Int(Self.candidateTimeout)
        options.enableKeepalive = true
        options.keepaliveIdle = 10
        options.keepaliveInterval = 5
        options.keepaliveCount = 3
        let parameters = NWParameters(tls: nil, tcp: options)
        parameters.includePeerToPeer = false
        #if DEBUG
        let isOverride = candidate.dialed != nil && candidate.dialed == DebugSettings.wifiOverride
        #else
        let isOverride = false
        #endif
        if !isOverride { parameters.prohibitedInterfaceTypes = [.cellular] }
        let connection = NWConnection(to: candidate.endpoint, using: parameters)
        pending = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection, connection === self.pending else { return }
            switch state {
            case .ready:
                self.ready(connection, dialed: candidate.dialed, label: candidate.label, isOverride: isOverride)
            case .failed, .waiting:
                self.dropCandidate()
            default:
                break
            }
        }
        connection.start(queue: .main)
        candidateTimer = Timer.scheduledTimer(withTimeInterval: Self.candidateTimeout + 1, repeats: false) { [weak self] _ in
            self?.dropCandidate()
        }
    }

    private func dropCandidate() {
        candidateTimer?.invalidate()
        candidateTimer = nil
        pending?.stateUpdateHandler = nil
        pending?.cancel()
        pending = nil
        next()
    }

    private func ready(_ connection: NWConnection, dialed: WiFiEndpoint?, label: String, isOverride: Bool) {
        candidateTimer?.invalidate()
        candidateTimer = nil
        // A DNS-SD service can resolve to any address: check the one we actually reached.
        if !isOverride, case .hostPort(let host, _)? = connection.currentPath?.remoteEndpoint,
           !LocalAddress.isAllowed(host) {
            log.warning("wifi", "Skipped a bridge candidate outside the local network")
            return dropCandidate()
        }
        pending = nil
        connection.stateUpdateHandler = nil
        let transport = WiFiTransport(connection: connection, dialed: dialed, label: label)
        if let resolved = transport.resolvedEndpoint, bad[resolved.key] != nil {
            transport.close()
            return next()
        }
        connection.stateUpdateHandler = { [weak transport] state in
            if case .failed = state { transport?.close() }
        }
        transport.startReceiving()
        log.info("wifi", "Connected to a bridge candidate (\(label))")
        queue = []
        stopBrowsing()
        let callback = completion
        completion = nil
        callback?(.success((transport, nil)))
    }

    private func fail() {
        waitTimer?.invalidate()
        waitTimer = nil
        stopBrowsing()
        let callback = completion
        completion = nil
        callback?(.failure(ConnectError.failed))
    }
}
