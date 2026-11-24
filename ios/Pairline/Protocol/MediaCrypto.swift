import Foundation

/// The media channel's 64-record sliding replay window (PROTOCOL.md, "Records"). It keeps the highest accepted
/// counter and a mask of which of the 64 counters up to it were seen. Check with `mayAccept` before decrypting and
/// `mark` only after the record authenticated, so a forged datagram never moves the window.
struct ReplayWindow {
    static let size: UInt64 = 64

    private(set) var top: UInt64?
    /// Bit i set: counter `top - i` was accepted.
    private(set) var mask: UInt64 = 0

    func mayAccept(_ counter: UInt64) -> Bool {
        guard let top, counter <= top else { return true }
        let offset = top - counter
        return offset < Self.size && (mask >> offset) & 1 == 0
    }

    mutating func mark(_ counter: UInt64) {
        guard let current = top else {
            top = counter
            mask = 1
            return
        }
        if counter > current {
            let shift = counter - current
            mask = shift < Self.size ? (mask << shift) | 1 : 1
            top = counter
        } else {
            mask |= 1 << (current - counter)
        }
    }
}

/// Seals and opens media datagrams: one record per datagram, channel 1, counters from 0 including probes
/// (PROTOCOL.md, "Media channel (UDP)"). Not thread-safe: the media socket uses it from its own queue.
struct MediaCrypto {
    /// Largest plaintext in one datagram, so a datagram (≤ 1224 bytes) needs no IP fragmentation.
    static let maxPayload = 1200
    static let maxDatagram = maxPayload + Record.counterLength + Record.tagLength

    private var sealer: RecordSealer
    private let openKey: Data
    private let openDirection: Direction
    private(set) var window = ReplayWindow()

    /// The iPhone's side: seals R→B, opens B→R.
    init(keys: SessionKeys) {
        let media = keys.wifiKeys(.media)
        self.init(sealKey: media.remoteToBridge, sealDirection: .remoteToBridge,
                  openKey: media.bridgeToRemote, openDirection: .bridgeToRemote)
    }

    init(sealKey: Data, sealDirection: Direction, openKey: Data, openDirection: Direction) {
        sealer = RecordSealer(key: sealKey, direction: sealDirection, channel: .media)
        self.openKey = openKey
        self.openDirection = openDirection
    }

    var exhausted: Bool { sealer.nextCounter >= Record.counterLimit }

    /// One datagram. An empty payload is a probe.
    mutating func seal(_ payload: Data) throws -> Data {
        guard payload.count <= Self.maxPayload else { throw ProtocolError.frameTooLong }
        return try sealer.seal(payload)
    }

    /// The plaintext of an authentic, fresh datagram, or nil (dropped silently: UDP is easy to spoof).
    mutating func open(_ datagram: Data) -> Data? {
        guard datagram.count >= Record.counterLength + Record.tagLength, datagram.count <= Self.maxDatagram,
              let counter = datagram.readBigEndian(UInt64.self), counter < Record.counterLimit,
              window.mayAccept(counter),
              let (_, plaintext) = try? Record.open(key: openKey, direction: openDirection, channel: .media,
                                                    wire: datagram)
        else { return nil }
        window.mark(counter)
        return plaintext
    }
}
