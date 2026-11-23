import CryptoKit
import Foundation

/// A transport that carries complete frames (after BLE reassembly or TCP deframing).
/// All callbacks arrive on the main queue.
protocol FrameTransport: AnyObject {
    var onFrame: ((Data) -> Void)? { get set }
    var onClose: ((Error?) -> Void)? { get set }
    var kind: TransportKind { get }
    func send(_ frame: Data)
    func close()
}

enum TransportKind: String, Codable {
    case bluetooth = "ble"
    case tcp
    case demo
}

enum LinkSessionError: Error, Equatable {
    case pairingRejected(String)
    case pairingMACMismatch
    /// The bridge's confirm didn't decrypt: it holds different keys (it was reset or paired with another iPhone).
    case keyMismatch
    case protocolViolation(String)
    case transportClosed
}

/// One connection's crypto state machine (R side): optional pairing, hello exchange, confirms, then records.
final class LinkSession {
    enum Phase: Equatable {
        case idle
        case pairing
        case awaitingHello
        case awaitingConfirm
        case established
        case closed
    }

    private let transport: FrameTransport
    private let identity: KeyAgreementPrivateKey
    private var bridgeKey: Data?
    private let pairing: PairingLink?
    private let ephemeral: KeyAgreementPrivateKey
    private let random: Data
    private let pairingNonce: Data
    private var pairingTranscript: Data?
    private var helloR: Data?
    private var sealer: RecordSealer?
    private var opener: RecordOpener?

    private(set) var phase: Phase = .idle
    private(set) var keys: SessionKeys?

    /// Called once after `PAIR_OK` verified, with the bridge identity key to pin.
    var onPaired: ((Data) -> Void)?
    var onEstablished: (() -> Void)?
    /// Decrypted record plaintext (application messages, counter ≥ 1).
    var onMessage: ((Data) -> Void)?
    var onClose: ((LinkSessionError?) -> Void)?

    var transportKind: TransportKind { transport.kind }

    init(transport: FrameTransport, identity: KeyAgreementPrivateKey, pinnedBridgeKey: Data?, pairing: PairingLink?,
         ephemeral: KeyAgreementPrivateKey = P256.KeyAgreement.PrivateKey(),
         random: Data = .random(count: WireProtocol.randomLength),
         pairingNonce: Data = .random(count: 32)) {
        self.transport = transport
        self.identity = identity
        self.bridgeKey = pairing?.bridgeKey ?? pinnedBridgeKey
        self.pairing = pairing
        self.ephemeral = ephemeral
        self.random = random
        self.pairingNonce = pairingNonce
    }

    func start() {
        guard phase == .idle else { return }
        transport.onFrame = { [weak self] frame in self?.handle(frame: frame) }
        transport.onClose = { [weak self] _ in self?.finish(.transportClosed) }
        if let pairing {
            sendPairHello(pairing)
        } else {
            sendHello()
        }
    }

    /// Encrypts and sends one application message.
    func send(_ plaintext: Data) throws {
        guard phase == .established, var sealer else { throw LinkSessionError.transportClosed }
        let wire = try sealer.seal(plaintext)
        self.sealer = sealer
        transport.send(Frame(type: .record, body: wire).encoded)
    }

    func close() {
        finish(nil)
    }

    // MARK: - Steps

    private func sendPairHello(_ link: PairingLink) {
        phase = .pairing
        let remoteKey = identity.publicKeyX963
        let transcript = PairingCrypto.transcript(bridgeKey: link.bridgeKey, remoteKey: remoteKey, nonceR: pairingNonce)
        pairingTranscript = transcript
        let mac = PairingCrypto.remoteMAC(secret: link.secret, transcript: transcript)
        let hello = PairHelloMessage(k: remoteKey.base64URL, n: pairingNonce.base64URL, mac: mac.base64URL)
        guard let body = try? JSONEncoder.wire.encode(hello) else { return finish(.protocolViolation("encode")) }
        transport.send(Frame(type: .pairHello, body: body).encoded)
    }

    private func sendHello() {
        phase = .awaitingHello
        let hello = Hello(role: WireProtocol.roleRemote, ephemeralPublicKey: ephemeral.publicKeyX963, random: random)
        helloR = hello.encoded
        transport.send(Frame(type: .hello, body: hello.encoded).encoded)
    }

    private func handle(frame data: Data) {
        guard phase != .closed else { return }
        let frame: Frame
        do {
            frame = try Frame.decode(data)
        } catch {
            return finish(.protocolViolation("frame"))
        }
        switch (phase, frame.type) {
        case (.pairing, .pairOK):
            handlePairOK(frame.body)
        case (.pairing, .pairFail):
            let reason = (try? JSONDecoder().decode(PairFailMessage.self, from: frame.body))?.reason ?? "unknown"
            finish(.pairingRejected(reason))
        case (.awaitingHello, .hello):
            handleHello(frame.body)
        case (.awaitingConfirm, .record):
            handleConfirm(frame.body)
        case (.established, .record):
            handleRecord(frame.body)
        default:
            finish(.protocolViolation("unexpected frame \(frame.type)"))
        }
    }

    private func handlePairOK(_ body: Data) {
        guard let pairing, let transcript = pairingTranscript,
              let ok = try? JSONDecoder().decode(PairOKMessage.self, from: body),
              ok.t == "pair_ok", ok.v == 0,
              let nonceB = Data(base64URL: ok.n), nonceB.count == 32,
              let mac = Data(base64URL: ok.mac) else {
            return finish(.protocolViolation("pair_ok"))
        }
        guard PairingCrypto.verifyBridgeMAC(mac, secret: pairing.secret, transcript: transcript, nonceB: nonceB) else {
            return finish(.pairingMACMismatch)
        }
        bridgeKey = pairing.bridgeKey
        onPaired?(pairing.bridgeKey)
        sendHello()
    }

    private func handleHello(_ body: Data) {
        guard let helloR, let bridgeKeyData = bridgeKey else { return finish(.protocolViolation("state")) }
        do {
            let hello = try Hello.decode(body, expectedRole: WireProtocol.roleBridge)
            let eB = try CryptoPrimitives.publicKey(x963: hello.ephemeralPublicKey)
            let sB = try CryptoPrimitives.publicKey(x963: bridgeKeyData)
            let ikm = try Handshake.remoteIKM(ephemeral: ephemeral, identity: identity, bridgeEphemeral: eB,
                                              bridgeIdentity: sB)
            let keys = SessionKeys.derive(ikm: ikm, helloR: helloR, helloB: hello.encoded)
            self.keys = keys
            var sealer = RecordSealer(key: keys.remoteToBridge, direction: .remoteToBridge)
            opener = RecordOpener(key: keys.bridgeToRemote, direction: .bridgeToRemote)
            let confirm = try sealer.seal(Data())
            self.sealer = sealer
            phase = .awaitingConfirm
            transport.send(Frame(type: .record, body: confirm).encoded)
        } catch {
            finish(.protocolViolation("hello"))
        }
    }

    private func handleConfirm(_ body: Data) {
        guard var opener else { return finish(.protocolViolation("state")) }
        do {
            let (counter, plaintext) = try opener.open(body)
            guard counter == 0, plaintext.isEmpty else { return finish(.protocolViolation("confirm")) }
            self.opener = opener
            phase = .established
            onEstablished?()
        } catch {
            finish(.keyMismatch)
        }
    }

    private func handleRecord(_ body: Data) {
        guard var opener else { return finish(.protocolViolation("state")) }
        do {
            let (_, plaintext) = try opener.open(body)
            self.opener = opener
            onMessage?(plaintext)
        } catch ProtocolError.replayedCounter {
            finish(.protocolViolation("replay"))
        } catch {
            finish(.protocolViolation("record"))
        }
    }

    private func finish(_ error: LinkSessionError?) {
        guard phase != .closed else { return }
        phase = .closed
        transport.onFrame = nil
        transport.onClose = nil
        transport.close()
        sealer = nil
        opener = nil
        let callback = onClose
        onClose = nil
        onPaired = nil
        onEstablished = nil
        onMessage = nil
        callback?(error)
    }
}

extension JSONEncoder {
    /// Compact JSON without escaped slashes, for wire bodies.
    static var wire: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        return encoder
    }
}
