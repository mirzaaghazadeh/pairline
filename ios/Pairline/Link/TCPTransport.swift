#if DEBUG
import Foundation
import Network

/// Debug-only TCP transport to a bridge on 127.0.0.1:47800 (PROTOCOL.md, "TCP"; D-018). Release builds contain
/// no TCP code. Frames are preceded by their 4-byte big-endian length.
final class TCPTransport: FrameTransport {
    let kind: TransportKind = .tcp
    private let connection: NWConnection
    private var deframer = TCPDeframer()
    private var closed = false

    var onFrame: ((Data) -> Void)?
    var onClose: ((Error?) -> Void)?

    fileprivate init(connection: NWConnection) {
        self.connection = connection
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
        guard !closed else { return }
        connection.send(content: TCPFraming.encode(frame), completion: .contentProcessed { [weak self] error in
            if error != nil { self?.close() }
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

/// Connects to the debug bridge. Every attempt either succeeds or fails within a few seconds; the link manager
/// retries with backoff.
final class TCPConnector: LinkConnector {
    static let defaultHost = "127.0.0.1"
    static let defaultPort: UInt16 = 47800

    let kind: TransportKind = .tcp
    let availability: BluetoothAvailability = .poweredOn
    var onAvailabilityChange: ((BluetoothAvailability) -> Void)?

    private let host: String
    private let port: UInt16
    private var pending: NWConnection?
    private var timer: Timer?
    private var completion: ((Result<(FrameTransport, UUID?), Error>) -> Void)?

    /// `-PairlineTCPPort <port>` (or the `debug.tcpPort` default) picks another port, e.g. to run a fake bridge next
    /// to an Android emulator's `adb forward`.
    init(host: String = TCPConnector.defaultHost, port: UInt16? = nil) {
        self.host = host
        let args = ProcessInfo.processInfo.arguments
        if let port {
            self.port = port
        } else if let index = args.firstIndex(of: "-PairlineTCPPort"), index + 1 < args.count,
                  let value = UInt16(args[index + 1]) {
            self.port = value
        } else if let value = UInt16(exactly: UserDefaults.standard.integer(forKey: "debug.tcpPort")), value > 0 {
            self.port = value
        } else {
            self.port = TCPConnector.defaultPort
        }
    }

    func connect(_ target: ConnectTarget, completion: @escaping (Result<(FrameTransport, UUID?), Error>) -> Void) {
        cancel()
        self.completion = completion
        let options = NWProtocolTCP.Options()
        options.noDelay = true
        let connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!,
                                      using: NWParameters(tls: nil, tcp: options))
        pending = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection, connection === self.pending else { return }
            switch state {
            case .ready:
                self.succeed(connection)
            case .failed, .waiting:
                self.fail()
            case .cancelled:
                break
            default:
                break
            }
        }
        connection.start(queue: .main)
        timer = Timer.scheduledTimer(withTimeInterval: 5, repeats: false) { [weak self] _ in self?.fail() }
    }

    private func succeed(_ connection: NWConnection) {
        timer?.invalidate()
        connection.stateUpdateHandler = nil
        pending = nil
        let transport = TCPTransport(connection: connection)
        connection.stateUpdateHandler = { [weak transport] state in
            if case .failed = state { transport?.close() }
        }
        transport.startReceiving()
        log.info("tcp", "Connected to the debug bridge")
        let callback = completion
        completion = nil
        callback?(.success((transport, nil)))
    }

    private func fail() {
        timer?.invalidate()
        pending?.stateUpdateHandler = nil
        pending?.cancel()
        pending = nil
        let callback = completion
        completion = nil
        callback?(.failure(ConnectError.failed))
    }

    func cancel() {
        timer?.invalidate()
        pending?.stateUpdateHandler = nil
        pending?.cancel()
        pending = nil
        let callback = completion
        completion = nil
        callback?(.failure(ConnectError.cancelled))
    }
}
#endif
