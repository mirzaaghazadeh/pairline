import CryptoKit
import Foundation

/// A transport that carries complete frames (after BLE reassembly or TCP deframing).
/// All callbacks arrive on the main queue.
protocol FrameTransport: AnyObject {
    var onFrame: ((Data) -> Void)? { get set }
    var onClose: ((Error?) -> Void)? { get set }
    var kind: TransportKind { get }
    /// The address this transport dialed (Wi-Fi and the direct connection); nil for Bluetooth.
    var dialed: WiFiEndpoint? { get }
    func send(_ frame: Data)
    /// Sends and calls `completion` (on the main queue) once the transport took the frame, for flow control.
    func send(_ frame: Data, completion: (() -> Void)?)
    /// Call audio is waiting (D-072): `source` returns the next `MEDIA` frame (every packet waiting in it) or nil.
    /// Bluetooth pulls it at each frame boundary, before frames still waiting; others send what it has at once.
    func mediaReady(_ source: @escaping () -> Data?)
    func close()
}

extension FrameTransport {
    var dialed: WiFiEndpoint? { nil }

    func send(_ frame: Data, completion: (() -> Void)?) {
        send(frame)
        if let completion { DispatchQueue.main.async(execute: completion) }
    }

    func mediaReady(_ source: @escaping () -> Data?) {
        while let frame = source() { send(frame) }
    }
}

enum TransportKind: String, Codable {
    case bluetooth = "ble"
    case tcp
    /// The production Wi-Fi link (M5): a full session over TCP on the local network, plus media and bulk channels.
    case wifi
    /// Remote mode's direct connection (D-092): the Wi-Fi session over TCP to the bridge's public address. Call audio
    /// rides inside it as `MEDIA` frames, like Bluetooth (no UDP media channel); no bulk channel for now.
    case direct
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
    private var bulkSealer: RecordSealer?
    private var bulkOpener: RecordOpener?
    private var linkMediaSealer: RecordSealer?
    private var linkMediaOpener: RecordOpener?

    private(set) var phase: Phase = .idle
    private(set) var keys: SessionKeys?

    /// Called once after `PAIR_OK` verified, with the bridge identity key to pin.
    var onPaired: ((Data) -> Void)?
    var onEstablished: (() -> Void)?
    /// Decrypted record plaintext (application messages, counter ≥ 1).
    var onMessage: ((Data) -> Void)?
    /// Decrypted `BULK` record plaintext (Wi-Fi sessions only).
    var onBulk: ((Data) -> Void)?
    /// Decrypted `MEDIA` record plaintext: call audio (Bluetooth and debug TCP sessions only, D-072).
    var onLinkMedia: ((Data) -> Void)?
    var onClose: ((LinkSessionError?) -> Void)?

    var transportKind: TransportKind { transport.kind }
    /// The transport under this session (the Wi-Fi link reads the peer's address from it for the media channel).
    var frameTransport: FrameTransport { transport }
    /// `BULK` frames exist only on the Wi-Fi link (PROTOCOL.md, "Frames"); not on the direct connection yet.
    var carriesBulk: Bool { transport.kind == .wifi }
    /// `MEDIA` frames (call audio, D-072) exist on Bluetooth, the debug TCP link and remote mode's direct connection
    /// (D-092): Wi-Fi has its UDP channel.
    var carriesLinkMedia: Bool { transport.kind == .bluetooth || transport.kind == .tcp || transport.kind == .direct }

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

    /// Encrypts one bulk-channel record and sends it as a `BULK` frame. `completion` runs once the transport took it.
    func sendBulk(_ plaintext: Data, completion: (() -> Void)? = nil) throws {
        guard phase == .established, carriesBulk, var bulkSealer else { throw LinkSessionError.transportClosed }
        let wire = try bulkSealer.seal(plaintext)
        self.bulkSealer = bulkSealer
        transport.send(Frame(type: .bulk, body: wire).encoded, completion: completion)
    }

    /// Seals one link media record and returns its `MEDIA` frame for the caller to hand to the transport (D-072).
    func sealLinkMedia(_ plaintext: Data) throws -> Data {
        guard phase == .established, carriesLinkMedia, var linkMediaSealer else { throw LinkSessionError.transportClosed }
        let wire = try linkMediaSealer.seal(plaintext)
        self.linkMediaSealer = linkMediaSealer
        return Frame(type: .media, body: wire).encoded
    }

    /// Call audio is waiting: the transport pulls `MEDIA` frames from `source`, ahead of frames that haven't started.
    func mediaReady(_ source: @escaping () -> Data?) {
        guard phase == .established, carriesLinkMedia else { return }
        transport.mediaReady(source)
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
        case (.established, .bulk) where carriesBulk:
            handleBulk(frame.body)
        case (.established, .media) where carriesLinkMedia:
            handleLinkMedia(frame.body)
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
            if carriesBulk, let keys {
                let bulk = keys.wifiKeys(.bulk)
                bulkSealer = RecordSealer(key: bulk.remoteToBridge, direction: .remoteToBridge, channel: .bulk)
                bulkOpener = RecordOpener(key: bulk.bridgeToRemote, direction: .bridgeToRemote, channel: .bulk)
            }
            if carriesLinkMedia, let keys {
                let media = keys.linkMediaKeys()
                linkMediaSealer = RecordSealer(key: media.remoteToBridge, direction: .remoteToBridge, channel: .linkMedia)
                linkMediaOpener = RecordOpener(key: media.bridgeToRemote, direction: .bridgeToRemote, channel: .linkMedia)
            }
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

    /// Bulk records are ordered like control records: a replayed or forged one closes the connection.
    private func handleBulk(_ body: Data) {
        guard var bulkOpener else { return finish(.protocolViolation("state")) }
        do {
            let (_, plaintext) = try bulkOpener.open(body)
            self.bulkOpener = bulkOpener
            onBulk?(plaintext)
        } catch {
            finish(.protocolViolation("bulk"))
        }
    }

    /// Link media records are ordered like control records: a replayed or forged one closes the connection (D-072).
    private func handleLinkMedia(_ body: Data) {
        guard var linkMediaOpener else { return finish(.protocolViolation("state")) }
        do {
            let (_, plaintext) = try linkMediaOpener.open(body)
            self.linkMediaOpener = linkMediaOpener
            onLinkMedia?(plaintext)
        } catch {
            finish(.protocolViolation("media"))
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
        bulkSealer = nil
        bulkOpener = nil
        linkMediaSealer = nil
        linkMediaOpener = nil
        let callback = onClose
        onClose = nil
        onPaired = nil
        onEstablished = nil
        onMessage = nil
        onBulk = nil
        onLinkMedia = nil
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
