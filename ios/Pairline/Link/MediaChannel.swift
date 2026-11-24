import Foundation
import Network

/// The encrypted, low-latency media channel to the bridge over UDP (PROTOCOL.md, "Media channel (UDP)"), for call
/// audio. Loss and reordering are possible and nothing is retransmitted.
///
/// - `send(_:)` never blocks: it queues the packet and returns. A packet over 1200 bytes, a packet sent while the
///   channel isn't open, or one that finds too many packets already queued is dropped.
/// - `onPacket` is called on a private serial queue (not the main queue), once per authentic packet, in arrival
///   order. Keep the work short or hand it off.
/// - Thread-safe: every member can be used from any thread.
///
/// Get the current one from `LinkManager.mediaChannel` (nil unless open) and follow `onMediaChannelChange` or the
/// `LinkManager.mediaChannelDidChange` notification. It closes with the Wi-Fi session it belongs to.
protocol MediaChannel: AnyObject {
    var isOpen: Bool { get }
    func send(_ packet: Data)
    var onPacket: ((Data) -> Void)? { get set }
}

/// `MediaChannel` over one UDP `NWConnection` to the bridge's address at the `wifi.media` port.
final class MediaSocket: MediaChannel {
    static let maxPayload = MediaCrypto.maxPayload
    /// Packets queued for the socket beyond this are dropped, so a stalled network never builds up delay.
    private static let maxInFlight = 32
    private static let probeUntilOpen: TimeInterval = 1
    private static let idleProbe: TimeInterval = 5

    private let queue = DispatchQueue(label: "app.pairline.media", qos: .userInteractive)
    private let lock = NSLock()
    private let connection: NWConnection
    private var crypto: MediaCrypto
    private var timer: DispatchSourceTimer?
    private var lastSent = DispatchTime(uptimeNanoseconds: 0)
    private var inFlight = 0
    private var _open = false
    private var _closed = false
    private var _onPacket: ((Data) -> Void)?

    /// Called once on the main queue when the first authentic datagram from the bridge arrives.
    var onOpen: (() -> Void)?

    init(host: NWEndpoint.Host, port: UInt16, keys: SessionKeys, allowLoopback: Bool = false) {
        crypto = MediaCrypto(keys: keys)
        let parameters = NWParameters.udp
        parameters.includePeerToPeer = false
        if !allowLoopback { parameters.prohibitedInterfaceTypes = [.cellular] }
        connection = NWConnection(host: host, port: NWEndpoint.Port(rawValue: port) ?? .any, using: parameters)
    }

    var isOpen: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _open && !_closed
    }

    var onPacket: ((Data) -> Void)? {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _onPacket
        }
        set {
            lock.lock()
            _onPacket = newValue
            lock.unlock()
        }
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            if case .failed = state { self?.close() }
        }
        connection.start(queue: queue)
        receive()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: Self.probeUntilOpen)
        timer.setEventHandler { [weak self] in self?.tick() }
        self.timer = timer
        timer.resume()
    }

    func send(_ packet: Data) {
        guard !packet.isEmpty, packet.count <= Self.maxPayload, isOpen else { return }
        queue.async { [weak self] in self?.transmit(packet) }
    }

    func close() {
        lock.lock()
        let wasClosed = _closed
        _closed = true
        _open = false
        lock.unlock()
        guard !wasClosed else { return }
        queue.async { [weak self] in
            self?.timer?.cancel()
            self?.timer = nil
            self?.connection.cancel()
        }
    }

    // MARK: - On the media queue

    private func tick() {
        lock.lock()
        let open = _open, closed = _closed
        lock.unlock()
        guard !closed else { return }
        let idle = Double(DispatchTime.now().uptimeNanoseconds &- lastSent.uptimeNanoseconds) / 1e9
        if !open || idle >= Self.idleProbe { transmit(Data()) }
    }

    /// Seals and sends one datagram (an empty payload is a probe).
    private func transmit(_ payload: Data) {
        guard inFlight < Self.maxInFlight, !crypto.exhausted, let datagram = try? crypto.seal(payload) else { return }
        inFlight += 1
        lastSent = .now()
        connection.send(content: datagram, completion: .contentProcessed { [weak self] _ in
            self?.inFlight -= 1
        })
    }

    private func receive() {
        connection.receiveMessage { [weak self] data, _, _, error in
            guard let self else { return }
            if let data, let plaintext = self.crypto.open(data) {
                self.received(plaintext)
            }
            if error == nil || self.connection.state == .ready {
                self.lock.lock()
                let closed = self._closed
                self.lock.unlock()
                if !closed { self.receive() }
            }
        }
    }

    private func received(_ plaintext: Data) {
        lock.lock()
        let opening = !_open && !_closed
        if opening { _open = true }
        let handler = _onPacket
        lock.unlock()
        if opening {
            DispatchQueue.main.async { [weak self] in self?.onOpen?() }
        }
        if !plaintext.isEmpty { handler?(plaintext) }
    }
}
