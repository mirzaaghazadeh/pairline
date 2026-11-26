import Foundation

/// Link media records (PROTOCOL.md, "Call audio", D-072): one or more media packets, each preceded by its 2-byte
/// big-endian length, so a sender that fell behind sends everything waiting in one `MEDIA` frame.
enum LinkMediaPackets {
    /// A media packet is at least kind ‖ sequence.
    static let minPacket = 3
    /// Everything in one record, length prefixes included (the same limit as a Wi-Fi media datagram).
    static let maxPlaintext = MediaCrypto.maxPayload

    static func cost(_ packet: Data) -> Int { 2 + packet.count }

    static func pack(_ packets: [Data]) -> Data {
        var out = Data(capacity: packets.reduce(0) { $0 + cost($1) })
        for packet in packets {
            out.append(UInt8(packet.count >> 8))
            out.append(UInt8(packet.count & 0xFF))
            out.append(packet)
        }
        return out
    }

    /// The packets in a record, or nil if it doesn't split exactly into length-prefixed packets (then it's ignored).
    static func unpack(_ plaintext: Data) -> [Data]? {
        let bytes = [UInt8](plaintext)
        guard !bytes.isEmpty, bytes.count <= maxPlaintext else { return nil }
        var packets: [Data] = []
        var at = 0
        while at < bytes.count {
            guard bytes.count - at >= 2 else { return nil }
            let length = Int(bytes[at]) << 8 | Int(bytes[at + 1])
            at += 2
            guard length >= minPacket, length <= bytes.count - at else { return nil }
            packets.append(Data(bytes[at..<(at + length)]))
            at += length
        }
        return packets
    }
}

/// Call audio inside the Bluetooth (or debug TCP) session (D-072): the same `MediaChannel` as the Wi-Fi UDP channel,
/// carried as `MEDIA` frames on the session.
///
/// - `send(_:)` only queues (from any thread); the transport takes the next frame when it can, and that frame holds
///   every packet waiting, so a Bluetooth hold-up becomes one bigger frame instead of a backlog. Packets that waited
///   longer than `maxWaiting` frames (200 ms) are dropped, oldest first: late audio is worse than a short gap.
/// - `onPacket` is called on a private serial queue, in order, once per packet.
/// - It closes with its session.
final class LinkMediaChannel: MediaChannel, @unchecked Sendable {
    static let maxWaiting = 10

    private let lock = NSLock()
    private var waiting: [Data] = []
    private var closed = false
    private var kickPending = false
    private var _onPacket: ((Data) -> Void)?
    private let deliver = DispatchQueue(label: "app.pairline.linkmedia", qos: .userInteractive)
    /// Used on the main queue only, like the session itself.
    private weak var session: LinkSession?

    private(set) var framesSent = 0
    private(set) var packetsSent = 0
    private(set) var packetsDropped = 0

    init(session: LinkSession) {
        self.session = session
    }

    var isOpen: Bool {
        lock.lock()
        defer { lock.unlock() }
        return !closed
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

    func send(_ packet: Data) {
        guard packet.count >= LinkMediaPackets.minPacket,
              LinkMediaPackets.cost(packet) <= LinkMediaPackets.maxPlaintext else { return }
        lock.lock()
        guard !closed else {
            lock.unlock()
            return
        }
        waiting.append(packet)
        if waiting.count > Self.maxWaiting {
            let excess = waiting.count - Self.maxWaiting
            waiting.removeFirst(excess)
            packetsDropped += excess
        }
        let kick = !kickPending
        kickPending = true
        lock.unlock()
        if kick { DispatchQueue.main.async { [weak self] in self?.kick() } }
    }

    /// Main queue: tells the transport audio is waiting.
    private func kick() {
        lock.lock()
        kickPending = false
        lock.unlock()
        session?.mediaReady { [weak self] in self?.nextFrame() }
    }

    /// Main queue (the transport pulls it): the next `MEDIA` frame with every packet waiting, or nil.
    func nextFrame() -> Data? {
        lock.lock()
        guard !closed, !waiting.isEmpty else {
            lock.unlock()
            return nil
        }
        var batch: [Data] = []
        var size = 0
        while let first = waiting.first, size + LinkMediaPackets.cost(first) <= LinkMediaPackets.maxPlaintext {
            batch.append(waiting.removeFirst())
            size += LinkMediaPackets.cost(first)
        }
        lock.unlock()
        guard let session, let frame = try? session.sealLinkMedia(LinkMediaPackets.pack(batch)) else { return nil }
        lock.lock()
        framesSent += 1
        packetsSent += batch.count
        lock.unlock()
        return frame
    }

    /// Main queue: an authentic `MEDIA` record from the bridge. A record that doesn't split into packets is ignored.
    func receive(_ plaintext: Data) {
        guard let packets = LinkMediaPackets.unpack(plaintext) else { return }
        deliver.async { [weak self] in
            guard let receiver = self?.onPacket else { return }
            for packet in packets { receiver(packet) }
        }
    }

    func close() {
        lock.lock()
        closed = true
        waiting.removeAll()
        _onPacket = nil
        lock.unlock()
    }
}

extension LinkMediaChannel: AudioPacketTransport {}
