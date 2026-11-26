import CryptoKit
import Foundation

/// A P-256 private key that can do ECDH: a software key (Keychain path, tests, ephemeral keys)
/// or a Secure Enclave key. The result is the 32-byte X coordinate, as in docs/PROTOCOL.md.
protocol KeyAgreementPrivateKey {
    var publicKeyX963: Data { get }
    func sharedSecret(with peer: P256.KeyAgreement.PublicKey) throws -> Data
}

extension P256.KeyAgreement.PrivateKey: KeyAgreementPrivateKey {
    var publicKeyX963: Data { publicKey.x963Representation }

    func sharedSecret(with peer: P256.KeyAgreement.PublicKey) throws -> Data {
        try sharedSecretFromKeyAgreement(with: peer).withUnsafeBytes { Data($0) }
    }
}

extension SecureEnclave.P256.KeyAgreement.PrivateKey: KeyAgreementPrivateKey {
    var publicKeyX963: Data { publicKey.x963Representation }

    func sharedSecret(with peer: P256.KeyAgreement.PublicKey) throws -> Data {
        try sharedSecretFromKeyAgreement(with: peer).withUnsafeBytes { Data($0) }
    }
}

enum CryptoPrimitives {
    static func publicKey(x963 data: Data) throws -> P256.KeyAgreement.PublicKey {
        guard data.count == WireProtocol.publicKeyLength, data.first == 0x04 else { throw ProtocolError.invalidPublicKey }
        do {
            return try P256.KeyAgreement.PublicKey(x963Representation: data)
        } catch {
            throw ProtocolError.invalidPublicKey
        }
    }

    static func hkdf(ikm: Data, salt: Data, info: Data, length: Int) -> Data {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: info,
                               outputByteCount: length)
            .withUnsafeBytes { Data($0) }
    }

    static func sha256(_ data: Data) -> Data {
        Data(SHA256.hash(data: data))
    }

    static func hmac(key: Data, message: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: key)))
    }

    /// Constant-time HMAC check.
    static func verifyHMAC(_ mac: Data, key: Data, message: Data) -> Bool {
        HMAC<SHA256>.isValidAuthenticationCode(mac, authenticating: message, using: SymmetricKey(data: key))
    }
}

/// Record direction label (first 4 bytes of the nonce).
enum Direction: UInt32 {
    case bridgeToRemote = 1
    case remoteToBridge = 2
}

/// Record channel (second byte of the associated data).
enum Channel: UInt8 {
    case control = 0
    case media = 1
    case bulk = 2
    /// Call audio inside a Bluetooth or debug TCP session (`MEDIA` frames, D-072).
    case linkMedia = 3
}

/// Session keys from the handshake: `okm = HKDF(ikm, SHA-256(helloR ‖ helloB), "pairline v0 session", 64)`.
struct SessionKeys {
    let okm: Data

    static let sessionInfo = Data("pairline v0 session".utf8)
    static let mediaInfo = Data("pairline v0 media".utf8)
    static let bulkInfo = Data("pairline v0 bulk".utf8)
    static let linkMediaInfo = Data("pairline v0 link media".utf8)

    init(okm: Data) {
        self.okm = okm
    }

    static func salt(helloR: Data, helloB: Data) -> Data {
        CryptoPrimitives.sha256(helloR + helloB)
    }

    static func derive(ikm: Data, helloR: Data, helloB: Data) -> SessionKeys {
        SessionKeys(okm: CryptoPrimitives.hkdf(ikm: ikm, salt: salt(helloR: helloR, helloB: helloB),
                                               info: sessionInfo, length: 64))
    }

    /// B→R key (`kBR = okm[0..32]`).
    var bridgeToRemote: Data { okm.prefix(32) }
    /// R→B key (`kRB = okm[32..64]`).
    var remoteToBridge: Data { okm.suffix(32) }

    /// Wi-Fi channel keys (M5): `HKDF(okm, salt = empty, info, 64)`, first half B→R.
    func wifiKeys(_ channel: Channel) -> (bridgeToRemote: Data, remoteToBridge: Data) {
        let info = channel == .media ? SessionKeys.mediaInfo : SessionKeys.bulkInfo
        let out = CryptoPrimitives.hkdf(ikm: okm, salt: Data(), info: info, length: 64)
        return (out.prefix(32), out.suffix(32))
    }

    /// Link media keys (D-072): call audio inside this Bluetooth or debug TCP session, first half B→R.
    func linkMediaKeys() -> (bridgeToRemote: Data, remoteToBridge: Data) {
        let out = CryptoPrimitives.hkdf(ikm: okm, salt: Data(), info: SessionKeys.linkMediaInfo, length: 64)
        return (out.prefix(32), out.suffix(32))
    }
}

enum Handshake {
    /// R's side of `ikm = ECDH(eB, eR) ‖ ECDH(sB, eR) ‖ ECDH(eB, sR)`, computed as
    /// `ECDH(eR, eB) ‖ ECDH(eR, sB) ‖ ECDH(sR, eB)`.
    static func remoteIKM(ephemeral eR: KeyAgreementPrivateKey, identity sR: KeyAgreementPrivateKey,
                          bridgeEphemeral eB: P256.KeyAgreement.PublicKey,
                          bridgeIdentity sB: P256.KeyAgreement.PublicKey) throws -> Data {
        try eR.sharedSecret(with: eB) + eR.sharedSecret(with: sB) + sR.sharedSecret(with: eB)
    }

    /// B's side, used by tests and the in-app loopback bridge.
    static func bridgeIKM(ephemeral eB: KeyAgreementPrivateKey, identity sB: KeyAgreementPrivateKey,
                          remoteEphemeral eR: P256.KeyAgreement.PublicKey,
                          remoteIdentity sR: P256.KeyAgreement.PublicKey) throws -> Data {
        try eB.sharedSecret(with: eR) + sB.sharedSecret(with: eR) + eB.sharedSecret(with: sR)
    }
}

/// AES-256-GCM records: `counter (8, BE) ‖ ciphertext ‖ tag (16)`, nonce = direction (4, BE) ‖ counter (8, BE),
/// associated data = version ‖ channel.
enum Record {
    static let counterLength = 8
    static let tagLength = 16
    /// Close and reconnect before any counter reaches 2³² (PROTOCOL.md, "Rekeying").
    static let counterLimit: UInt64 = 1 << 32

    static func nonce(direction: Direction, counter: UInt64) -> Data {
        Data.bigEndian(direction.rawValue) + Data.bigEndian(counter)
    }

    static func associatedData(channel: Channel) -> Data {
        Data([WireProtocol.version, channel.rawValue])
    }

    static func seal(key: Data, direction: Direction, counter: UInt64, channel: Channel, plaintext: Data) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key),
                                   nonce: try AES.GCM.Nonce(data: nonce(direction: direction, counter: counter)),
                                   authenticating: associatedData(channel: channel))
        return Data.bigEndian(counter) + box.ciphertext + box.tag
    }

    static func open(key: Data, direction: Direction, channel: Channel, wire: Data) throws -> (counter: UInt64, plaintext: Data) {
        guard wire.count >= counterLength + tagLength,
              let counter = wire.readBigEndian(UInt64.self) else { throw ProtocolError.decryptFailed }
        let bytes = [UInt8](wire)
        let ciphertext = Data(bytes[counterLength..<(bytes.count - tagLength)])
        let tag = Data(bytes[(bytes.count - tagLength)...])
        do {
            let box = try AES.GCM.SealedBox(nonce: try AES.GCM.Nonce(data: nonce(direction: direction, counter: counter)),
                                            ciphertext: ciphertext, tag: tag)
            let plaintext = try AES.GCM.open(box, using: SymmetricKey(data: key),
                                             authenticating: associatedData(channel: channel))
            return (counter, plaintext)
        } catch {
            throw ProtocolError.decryptFailed
        }
    }
}

/// Outgoing record stream for one direction and channel. Counter 0 is the confirm.
struct RecordSealer {
    let key: Data
    let direction: Direction
    let channel: Channel
    private(set) var nextCounter: UInt64 = 0

    init(key: Data, direction: Direction, channel: Channel = .control) {
        self.key = key
        self.direction = direction
        self.channel = channel
    }

    mutating func seal(_ plaintext: Data) throws -> Data {
        guard nextCounter < Record.counterLimit else { throw ProtocolError.counterExhausted }
        let wire = try Record.seal(key: key, direction: direction, counter: nextCounter, channel: channel,
                                   plaintext: plaintext)
        nextCounter += 1
        return wire
    }
}

/// Incoming record stream for the ordered control channel: rejects any counter ≤ the last accepted one.
struct RecordOpener {
    let key: Data
    let direction: Direction
    let channel: Channel
    private(set) var lastAccepted: UInt64?

    init(key: Data, direction: Direction, channel: Channel = .control) {
        self.key = key
        self.direction = direction
        self.channel = channel
    }

    mutating func open(_ wire: Data) throws -> (counter: UInt64, plaintext: Data) {
        guard let counter = wire.readBigEndian(UInt64.self) else { throw ProtocolError.decryptFailed }
        if let last = lastAccepted, counter <= last { throw ProtocolError.replayedCounter }
        guard counter < Record.counterLimit else { throw ProtocolError.counterExhausted }
        let result = try Record.open(key: key, direction: direction, channel: channel, wire: wire)
        lastAccepted = result.counter
        return result
    }
}
