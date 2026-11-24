import CryptoKit
import Foundation
@testable import Pairline

/// Two connected in-memory transports. Frames are delivered asynchronously on the main queue, like the real ones.
final class LoopbackTransport: FrameTransport {
    var kind: TransportKind = .tcp
    weak var peer: LoopbackTransport?
    var onFrame: ((Data) -> Void)?
    var onClose: ((Error?) -> Void)?
    private(set) var closed = false
    private(set) var sent: [Data] = []

    static func pair(kind: TransportKind = .tcp) -> (LoopbackTransport, LoopbackTransport) {
        let a = LoopbackTransport()
        let b = LoopbackTransport()
        a.kind = kind
        b.kind = kind
        a.peer = b
        b.peer = a
        return (a, b)
    }

    func send(_ frame: Data) {
        guard !closed else { return }
        sent.append(frame)
        DispatchQueue.main.async { [weak peer] in
            guard let peer, !peer.closed else { return }
            peer.onFrame?(frame)
        }
    }

    func close() {
        guard !closed else { return }
        closed = true
        let callback = onClose
        onClose = nil
        callback?(nil)
        DispatchQueue.main.async { [weak peer] in peer?.remoteClosed() }
    }

    private func remoteClosed() {
        guard !closed else { return }
        closed = true
        let callback = onClose
        onClose = nil
        callback?(nil)
    }
}

/// A minimal bridge (role B) written against PROTOCOL.md, for driving the iPhone's session code in tests.
final class TestBridge {
    let identity: P256.KeyAgreement.PrivateKey
    let deviceID = Data([1, 2, 3, 4, 5, 6, 7, 8])
    var secret = Data(repeating: 7, count: 32)
    var pinnedRemote: Data?
    var tamperPairOK = false
    var failReason: String?

    let transport: LoopbackTransport
    private var sealer: RecordSealer?
    private var opener: RecordOpener?
    private var bulkSealer: RecordSealer?
    private var bulkOpener: RecordOpener?
    private(set) var received: [Data] = []
    private(set) var receivedBulk: [Data] = []
    private(set) var confirmed = false
    var onMessage: (([String: Any]) -> Void)?
    var onBulk: ((Data) -> Void)?

    init(transport: LoopbackTransport, identity: P256.KeyAgreement.PrivateKey = .init()) {
        self.transport = transport
        self.identity = identity
        transport.onFrame = { [weak self] frame in self?.handle(frame) }
    }

    var pairingLink: PairingLink {
        PairingLink(deviceID: deviceID, bridgeKey: identity.publicKey.x963Representation, secret: secret)
    }

    private func handle(_ data: Data) {
        guard let frame = try? Frame.decode(data) else { return transport.close() }
        switch frame.type {
        case .pairHello:
            handlePairHello(frame.body)
        case .hello:
            handleHello(frame.body)
        case .record:
            handleRecord(frame.body)
        case .bulk:
            guard var bulkOpener, let (_, plaintext) = try? bulkOpener.open(frame.body) else { return transport.close() }
            self.bulkOpener = bulkOpener
            receivedBulk.append(plaintext)
            onBulk?(plaintext)
        default:
            transport.close()
        }
    }

    private func handlePairHello(_ body: Data) {
        if let failReason {
            let fail = try! JSONSerialization.data(withJSONObject: ["t": "pair_fail", "v": 0, "reason": failReason])
            transport.send(Frame(type: .pairFail, body: fail).encoded)
            transport.close()
            return
        }
        let hello = try! JSONDecoder().decode(PairHelloMessage.self, from: body)
        let remoteKey = Data(base64URL: hello.k)!
        let nonceR = Data(base64URL: hello.n)!
        let transcript = PairingCrypto.transcript(bridgeKey: identity.publicKey.x963Representation, remoteKey: remoteKey,
                                                  nonceR: nonceR)
        guard CryptoPrimitives.verifyHMAC(Data(base64URL: hello.mac)!, key: secret, message: transcript) else {
            let fail = try! JSONSerialization.data(withJSONObject: ["t": "pair_fail", "v": 0, "reason": "bad_mac"])
            transport.send(Frame(type: .pairFail, body: fail).encoded)
            transport.close()
            return
        }
        pinnedRemote = remoteKey
        let nonceB = Data.random(count: 32)
        var mac = PairingCrypto.bridgeMAC(secret: secret, transcript: transcript, nonceB: nonceB)
        if tamperPairOK { mac[0] ^= 1 }
        let ok = try! JSONSerialization.data(withJSONObject: ["t": "pair_ok", "v": 0, "n": nonceB.base64URL,
                                                               "mac": mac.base64URL])
        transport.send(Frame(type: .pairOK, body: ok).encoded)
    }

    private func handleHello(_ body: Data) {
        guard let remote = pinnedRemote, let helloR = try? Hello.decode(body, expectedRole: WireProtocol.roleRemote) else {
            return transport.close()
        }
        let eB = P256.KeyAgreement.PrivateKey()
        let helloB = Hello(role: WireProtocol.roleBridge, ephemeralPublicKey: eB.publicKeyX963, random: .random(count: 32))
        transport.send(Frame(type: .hello, body: helloB.encoded).encoded)
        let ikm = try! Handshake.bridgeIKM(ephemeral: eB, identity: identity,
                                           remoteEphemeral: try! CryptoPrimitives.publicKey(x963: helloR.ephemeralPublicKey),
                                           remoteIdentity: try! CryptoPrimitives.publicKey(x963: remote))
        let keys = SessionKeys.derive(ikm: ikm, helloR: helloR.encoded, helloB: helloB.encoded)
        var sealer = RecordSealer(key: keys.bridgeToRemote, direction: .bridgeToRemote)
        opener = RecordOpener(key: keys.remoteToBridge, direction: .remoteToBridge)
        let bulk = keys.wifiKeys(.bulk)
        bulkSealer = RecordSealer(key: bulk.bridgeToRemote, direction: .bridgeToRemote, channel: .bulk)
        bulkOpener = RecordOpener(key: bulk.remoteToBridge, direction: .remoteToBridge, channel: .bulk)
        let confirm = try! sealer.seal(Data())
        self.sealer = sealer
        transport.send(Frame(type: .record, body: confirm).encoded)
    }

    private func handleRecord(_ body: Data) {
        guard var opener else { return transport.close() }
        guard let (counter, plaintext) = try? opener.open(body) else { return transport.close() }
        self.opener = opener
        if counter == 0 {
            confirmed = true
            return
        }
        received.append(plaintext)
        if let object = try? JSONSerialization.jsonObject(with: plaintext) as? [String: Any] {
            onMessage?(object)
        }
    }

    /// Sends one application message to R and returns the raw record (for replay tests).
    @discardableResult
    func send(_ object: [String: Any]) -> Data {
        let plaintext = try! JSONSerialization.data(withJSONObject: object)
        let wire = try! sealer!.seal(plaintext)
        transport.send(Frame(type: .record, body: wire).encoded)
        return wire
    }

    /// Sends one bulk-channel record (`BULK` frame).
    func sendBulk(_ plaintext: Data) {
        let wire = try! bulkSealer!.seal(plaintext)
        transport.send(Frame(type: .bulk, body: wire).encoded)
    }

    func sendRaw(record wire: Data) {
        transport.send(Frame(type: .record, body: wire).encoded)
    }

    func sendRawFrame(_ data: Data) {
        transport.send(data)
    }
}
