import Foundation
import Network

/// Opens remote mode's direct connection (D-092; PROTOCOL.md, "Direct connection"): TCP out to the bridge's public
/// address, carrying exactly the Wi-Fi session (`WiFiTransport`). It's the one connector that dials beyond the local
/// network, so it dials only `ConnectTarget.direct` — which `LinkManager` fills from the paired bridge's authenticated
/// `remote.addr` alone — as IP literals `DirectAddress` accepts, `ConnectTarget.directManual`, the port forwarding the
/// user typed in (D-095), and `ConnectTarget.relay`, the user's own server (D-106), reached over TLS pinned to the
/// certificate in its link; those two are the names it lets the system resolve. It never browses. Candidates are tried
/// in order (the user's own server first and then their port forwarding while the bridge's router opened nothing; IPv6
/// first), each with its own time limit. Runs on the main queue.
final class DirectConnector: LinkConnector {
    /// How long one candidate may take to connect over the internet.
    static let candidateTimeout: TimeInterval = 10
    /// How long the user's own server may take to join us to the bridge after the preamble (it holds us up to 10 s
    /// while the bridge reconnects).
    static let relayAnswerTimeout: TimeInterval = 15

    let kind: TransportKind = .direct
    let availability: BluetoothAvailability = .poweredOn
    var onAvailabilityChange: ((BluetoothAvailability) -> Void)?
    /// This iPhone's network changed (Wi-Fi to mobile data, for example): a good moment to try again.
    var onNetworkChange: (() -> Void)?
    /// An attempt through the user's own server ended without a session (a session's success is noted by the link).
    var onRelayResult: ((RelayAttempt.Outcome) -> Void)?

    private struct Candidate {
        let endpoint: WiFiEndpoint
        let target: NWEndpoint
        /// Set for the user's own server: TLS pinned to its certificate, then the preamble.
        var relay: RelayServer?
    }

    private var completion: ((Result<(FrameTransport, UUID?), Error>) -> Void)?
    private var queue: [Candidate] = []
    /// The user's port forwarding in this attempt, for the log (which never holds the address itself).
    private var manual: WiFiEndpoint?
    private var pending: NWConnection?
    /// Numbers the candidates tried, so a late certificate check can't mark a later one.
    private var attempt = 0
    /// The user's own server sent a certificate that doesn't match its link (in the current attempt).
    private var pinRejected = false
    private var timer: Timer?
    private var pathMonitor: NWPathMonitor?
    private var lastPathKey: String?

    init() {
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            // Moving from one Wi-Fi network to another keeps the interface ("en0"), so the gateways tell them apart.
            // The key stays in memory only; it's never logged.
            let key = path.availableInterfaces.map(\.name).sorted().joined(separator: ",")
                + "|" + path.gateways.map { "\($0)" }.sorted().joined(separator: ",")
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
    }

    func connect(_ target: ConnectTarget, completion: @escaping (Result<(FrameTransport, UUID?), Error>) -> Void) {
        cancel()
        self.completion = completion
        var candidates: [Candidate] = target.direct.compactMap { endpoint in
            DirectAddress.dialable(endpoint).map { Candidate(endpoint: endpoint, target: $0) }
        }
        manual = nil
        if let endpoint = target.directManual,
           let forward = try? ManualForward.make(host: endpoint.host, port: String(endpoint.port)).get(),
           forward.endpoint == endpoint, let dialable = forward.dialable {
            manual = endpoint
            candidates.removeAll { $0.endpoint == endpoint }
            let candidate = Candidate(endpoint: endpoint, target: dialable)
            if target.directManualFirst { candidates.insert(candidate, at: 0) } else { candidates.append(candidate) }
        }
        if let relay = target.relay?.checked, let dialable = relay.dialable {
            let candidate = Candidate(endpoint: relay.endpoint, target: dialable, relay: relay)
            if target.relayFirst { candidates.insert(candidate, at: 0) } else { candidates.append(candidate) }
        }
        queue = candidates
        next()
    }

    func cancel() {
        timer?.invalidate()
        timer = nil
        pending?.stateUpdateHandler = nil
        pending?.cancel()
        pending = nil
        queue = []
        let callback = completion
        completion = nil
        callback?(.failure(ConnectError.cancelled))
    }

    private func next() {
        guard completion != nil, pending == nil else { return }
        guard !queue.isEmpty else {
            let callback = completion
            completion = nil
            callback?(.failure(ConnectError.failed))
            return
        }
        let candidate = queue.removeFirst()
        attempt += 1
        pinRejected = false
        let connection = NWConnection(to: candidate.target, using: parameters(for: candidate.relay, attempt: attempt))
        pending = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection, connection === self.pending else { return }
            switch state {
            case .ready:
                if candidate.relay != nil {
                    self.askRelay(connection, candidate: candidate)
                } else {
                    self.ready(connection, candidate: candidate)
                }
            case .failed, .waiting:
                if candidate.relay != nil { self.onRelayResult?(self.pinRejected ? .pinMismatch : .unreachable) }
                self.dropCandidate()
            default:
                break
            }
        }
        connection.start(queue: .main)
        timer = Timer.scheduledTimer(withTimeInterval: Self.candidateTimeout + 1, repeats: false) { [weak self] _ in
            guard let self else { return }
            if candidate.relay != nil { self.onRelayResult?(self.pinRejected ? .pinMismatch : .unreachable) }
            self.dropCandidate()
        }
    }

    /// TCP for the bridge's own addresses and the port forwarding; TCP and TLS (1.2 or later) for the user's own
    /// server, accepted only when the SHA-256 of its leaf certificate is the one in its link. Nothing else about the
    /// certificate counts: the pin is the trust (PROTOCOL.md, "Connecting").
    private func parameters(for relay: RelayServer?, attempt: Int) -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = Int(Self.candidateTimeout)
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 10
        tcp.keepaliveInterval = 5
        tcp.keepaliveCount = 3
        var tls: NWProtocolTLS.Options?
        if let relay {
            let options = NWProtocolTLS.Options()
            let security = options.securityProtocolOptions
            sec_protocol_options_set_min_tls_protocol_version(security, .TLSv12)
            let fingerprint = relay.fingerprint
            sec_protocol_options_set_verify_block(security, { [weak self] _, trust, complete in
                let matches = RelayServer.pinMatches(sec_trust_copy_ref(trust).takeRetainedValue(), fingerprint: fingerprint)
                if !matches, let self, self.attempt == attempt { self.pinRejected = true }
                complete(matches)
            }, .main)
            tls = options
        }
        let parameters = NWParameters(tls: tls, tcp: tcp)
        parameters.includePeerToPeer = false
        parameters.preferNoProxies = true
        return parameters
    }

    /// The user's own server is connected and pinned: the preamble, then one status byte. `joined` hands over the same
    /// connection, which from then on carries the bridge's bytes; anything else drops the candidate.
    private func askRelay(_ connection: NWConnection, candidate: Candidate) {
        guard let relay = candidate.relay else { return dropCandidate() }
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: Self.relayAnswerTimeout, repeats: false) { [weak self] _ in
            guard let self, connection === self.pending else { return }
            self.onRelayResult?(.timeout)
            self.dropCandidate()
        }
        connection.send(content: relay.preamble(role: .remote), completion: .contentProcessed { [weak self] error in
            guard let self, error != nil, connection === self.pending else { return }
            self.onRelayResult?(.unreachable)
            self.dropCandidate()
        })
        // Exactly one byte: whatever follows `joined` belongs to the session.
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1) { [weak self] data, _, _, _ in
            guard let self, connection === self.pending else { return }
            guard let byte = data?.first else {
                self.onRelayResult?(.unreachable)
                return self.dropCandidate()
            }
            switch RelayServer.Status(rawValue: byte) {
            case .joined?:
                self.ready(connection, candidate: candidate)
            case .noBridge?:
                self.relayRefused(.noBridge)
            case .wrongKey?:
                self.relayRefused(.wrongKey)
            case .badVersion?:
                self.relayRefused(.badVersion)
            case .full?:
                self.relayRefused(.full)
            case .keepAlive?, nil:
                // Keep-alives go only to a waiting bridge; anything else isn't the relay protocol.
                self.relayRefused(.unreachable)
            }
        }
    }

    private func relayRefused(_ outcome: RelayAttempt.Outcome) {
        onRelayResult?(outcome)
        dropCandidate()
    }

    private func dropCandidate() {
        timer?.invalidate()
        timer = nil
        pending?.stateUpdateHandler = nil
        pending?.cancel()
        pending = nil
        next()
    }

    private func ready(_ connection: NWConnection, candidate: Candidate) {
        timer?.invalidate()
        timer = nil
        pending = nil
        connection.stateUpdateHandler = nil
        let viaRelay = candidate.relay != nil
        let transport = WiFiTransport(connection: connection, dialed: candidate.endpoint,
                                      label: viaRelay ? "relay" : "direct", kind: .direct)
        transport.viaRelay = viaRelay
        connection.stateUpdateHandler = { [weak transport] state in
            if case .failed = state { transport?.close() }
        }
        transport.startReceiving()
        if viaRelay {
            log.info("direct", "Reached the bridge through your own server")
        } else {
            log.info("direct", candidate.endpoint == manual ? "Reached the address you entered (port forwarding)"
                                                            : "Reached the bridge's \(DirectAddress.family(candidate.endpoint)) address")
        }
        queue = []
        let callback = completion
        completion = nil
        callback?(.success((transport, nil)))
    }
}
