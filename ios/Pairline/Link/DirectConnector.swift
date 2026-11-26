import Foundation
import Network

/// Opens remote mode's direct connection (D-080; PROTOCOL.md, "Direct connection"): TCP out to the bridge's public
/// address, carrying exactly the Wi-Fi session (`WiFiTransport`). It's the one connector that dials beyond the local
/// network, so it dials only `ConnectTarget.direct` — which `LinkManager` fills from the paired bridge's authenticated
/// `remote.addr` alone — and only IP literals `DirectAddress` accepts. It never resolves a name or browses. Candidates
/// are tried in order (IPv6 first), each with its own time limit. Runs on the main queue.
final class DirectConnector: LinkConnector {
    /// How long one candidate may take to connect over the internet.
    static let candidateTimeout: TimeInterval = 10

    let kind: TransportKind = .direct
    let availability: BluetoothAvailability = .poweredOn
    var onAvailabilityChange: ((BluetoothAvailability) -> Void)?
    /// This iPhone's network changed (Wi-Fi to mobile data, for example): a good moment to try again.
    var onNetworkChange: (() -> Void)?

    private var completion: ((Result<(FrameTransport, UUID?), Error>) -> Void)?
    private var queue: [(endpoint: WiFiEndpoint, target: NWEndpoint)] = []
    private var pending: NWConnection?
    private var timer: Timer?
    private var pathMonitor: NWPathMonitor?
    private var lastPathKey: String?

    init() {
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let key = path.availableInterfaces.map(\.name).sorted().joined(separator: ",")
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
        queue = target.direct.compactMap { endpoint in DirectAddress.dialable(endpoint).map { (endpoint, $0) } }
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
        let options = NWProtocolTCP.Options()
        options.noDelay = true
        options.connectionTimeout = Int(Self.candidateTimeout)
        options.enableKeepalive = true
        options.keepaliveIdle = 10
        options.keepaliveInterval = 5
        options.keepaliveCount = 3
        let parameters = NWParameters(tls: nil, tcp: options)
        parameters.includePeerToPeer = false
        parameters.preferNoProxies = true
        let connection = NWConnection(to: candidate.target, using: parameters)
        pending = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection, connection === self.pending else { return }
            switch state {
            case .ready:
                self.ready(connection, endpoint: candidate.endpoint)
            case .failed, .waiting:
                self.dropCandidate()
            default:
                break
            }
        }
        connection.start(queue: .main)
        timer = Timer.scheduledTimer(withTimeInterval: Self.candidateTimeout + 1, repeats: false) { [weak self] _ in
            self?.dropCandidate()
        }
    }

    private func dropCandidate() {
        timer?.invalidate()
        timer = nil
        pending?.stateUpdateHandler = nil
        pending?.cancel()
        pending = nil
        next()
    }

    private func ready(_ connection: NWConnection, endpoint: WiFiEndpoint) {
        timer?.invalidate()
        timer = nil
        pending = nil
        connection.stateUpdateHandler = nil
        let transport = WiFiTransport(connection: connection, dialed: endpoint, label: "direct", kind: .direct)
        connection.stateUpdateHandler = { [weak transport] state in
            if case .failed = state { transport?.close() }
        }
        transport.startReceiving()
        log.info("direct", "Reached the bridge's \(DirectAddress.family(endpoint)) address")
        queue = []
        let callback = completion
        completion = nil
        callback?(.success((transport, nil)))
    }
}
