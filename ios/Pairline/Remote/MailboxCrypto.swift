import CryptoKit
import Foundation

// Remote mode (protocol v1, D-072; PROTOCOL.md, "Remote mode"): mailbox records sealed one by one, the remote pairing
// commitment and comparison code, and the invitation link. Nothing here logs; keys, secrets and tokens must never be.

enum RemoteProtocolError: Error, Equatable {
    /// Not a well-formed v1 mailbox record: version, kind, direction, length or ephemeral key.
    case malformedRecord
    /// Well-formed, but not the kind or direction this call expects.
    case unexpectedRecord
    case decryptFailed
    case plaintextTooLong
    case invalidInvitation
}

extension Direction {
    /// The mailbox header's direction byte: `'B'` = B→R, `'R'` = R→B.
    var mailboxByte: UInt8 { self == .bridgeToRemote ? WireProtocol.roleBridge : WireProtocol.roleRemote }

    init?(mailboxByte: UInt8) {
        switch mailboxByte {
        case WireProtocol.roleBridge: self = .bridgeToRemote
        case WireProtocol.roleRemote: self = .remoteToBridge
        default: return nil
        }
    }
}

/// A mailbox record's header, which is also its associated data: version (1) ‖ kind (1) ‖ direction (1) ‖
/// record id (16) ‖ time (8, ms since the Unix epoch, BE) ‖ for kind 1 the sender's ephemeral public key (65).
struct MailboxHeader: Equatable {
    enum Kind: UInt8 {
        /// Sealed with the pinned identity keys (one-way Noise K: `e, es, ss`).
        case sealed = 0x01
        /// Invitation record (remote pairing), sealed with keys from the invitation secret.
        case invite = 0x02

        var headerLength: Int { self == .sealed ? MailboxHeader.sealedLength : MailboxHeader.baseLength }
    }

    static let version: UInt8 = 0x01
    static let recordIDLength = 16
    static let baseLength = 27
    static let sealedLength = baseLength + WireProtocol.publicKeyLength

    let kind: Kind
    let direction: Direction
    let recordID: Data
    /// Milliseconds since the Unix epoch.
    let time: UInt64
    /// Kind 1 only: the sender's ephemeral public key (65-byte X9.62 point).
    let ephemeralPublicKey: Data?

    init(kind: Kind, direction: Direction, recordID: Data, time: UInt64, ephemeralPublicKey: Data? = nil) throws {
        guard recordID.count == Self.recordIDLength,
              (kind == .sealed) == (ephemeralPublicKey != nil) else { throw RemoteProtocolError.malformedRecord }
        if let ephemeralPublicKey {
            guard (try? CryptoPrimitives.publicKey(x963: ephemeralPublicKey)) != nil else {
                throw RemoteProtocolError.malformedRecord
            }
        }
        self.kind = kind
        self.direction = direction
        self.recordID = Data(recordID)
        self.time = time
        self.ephemeralPublicKey = ephemeralPublicKey.map { Data($0) }
    }

    /// Parses the header at the start of `data` (a header alone or a whole sealed record).
    init(parsing data: Data) throws {
        let bytes = [UInt8](data)
        guard bytes.count >= Self.baseLength, bytes[0] == Self.version,
              let kind = Kind(rawValue: bytes[1]), bytes.count >= kind.headerLength,
              let direction = Direction(mailboxByte: bytes[2]) else { throw RemoteProtocolError.malformedRecord }
        try self.init(kind: kind, direction: direction, recordID: Data(bytes[3..<19]),
                      time: bytes[19..<27].reduce(0) { $0 << 8 | UInt64($1) },
                      ephemeralPublicKey: kind == .sealed ? Data(bytes[27..<Self.sealedLength]) : nil)
    }

    var length: Int { kind.headerLength }

    var encoded: Data {
        var out = Data([Self.version, kind.rawValue, direction.mailboxByte])
        out.append(recordID)
        out.append(Data.bigEndian(time))
        if let ephemeralPublicKey { out.append(ephemeralPublicKey) }
        return out
    }

    /// The CloudKit record name: the record id as 32 lowercase hex digits.
    var recordName: String { recordID.hex }
}

/// Invitation record keys: `HKDF-SHA256(is, salt = empty, "pairline v1 invite", 64)`, the first 32 bytes B→R and the
/// last 32 R→B.
struct InviteKeys {
    let bridgeToRemote: Data
    let remoteToBridge: Data

    init(secret: Data) {
        let okm = CryptoPrimitives.hkdf(ikm: secret, salt: Data(), info: MailboxCrypto.inviteInfo, length: 64)
        bridgeToRemote = Data(okm.prefix(32))
        remoteToBridge = Data(okm.suffix(32))
    }

    func key(for direction: Direction) -> Data {
        direction == .bridgeToRemote ? bridgeToRemote : remoteToBridge
    }
}

/// Sealing and opening mailbox records; `sealed = header ‖ ciphertext ‖ tag (16)`, AES-256-GCM with the header as
/// associated data.
///
/// - Kind 1: `ikm = ECDH(e, sP) ‖ ECDH(sS, sP)`, `key = HKDF-SHA256(ikm, SHA-256(header), "pairline v1 mailbox", 32)`,
///   a zero nonce (each key seals exactly one record).
/// - Kind 2: the `InviteKeys` key for the direction, the first 12 bytes of the record id as the nonce.
enum MailboxCrypto {
    static let mailboxInfo = Data("pairline v1 mailbox".utf8)
    static let inviteInfo = Data("pairline v1 invite".utf8)
    static let tagLength = 16
    /// Plaintext limit (48 KB).
    static let maxPlaintext = 48 * 1024
    private static let zeroNonce = Data(count: 12)

    // MARK: Kind 1

    /// The sender's side of `ikm`: `ECDH(e, sP) ‖ ECDH(sS, sP)`.
    static func senderIKM(ephemeral e: KeyAgreementPrivateKey, identity sS: KeyAgreementPrivateKey,
                          recipient sP: P256.KeyAgreement.PublicKey) throws -> Data {
        try e.sharedSecret(with: sP) + sS.sharedSecret(with: sP)
    }

    /// The recipient's side of `ikm`: `ECDH(sP, e) ‖ ECDH(sP, sS)`.
    static func recipientIKM(identity sP: KeyAgreementPrivateKey, ephemeral e: P256.KeyAgreement.PublicKey,
                             sender sS: P256.KeyAgreement.PublicKey) throws -> Data {
        try sP.sharedSecret(with: e) + sP.sharedSecret(with: sS)
    }

    static func salt(header: Data) -> Data {
        CryptoPrimitives.sha256(header)
    }

    static func recordKey(ikm: Data, header: Data) -> Data {
        CryptoPrimitives.hkdf(ikm: ikm, salt: salt(header: header), info: mailboxInfo, length: 32)
    }

    /// Seals `plaintext` from `identity` (this device's key, Secure Enclave or software) to the pinned peer
    /// `recipient`, with a fresh ephemeral key. `direction` is this device's: `.remoteToBridge` on the iPhone.
    static func seal(_ plaintext: Data, identity: KeyAgreementPrivateKey, recipient: P256.KeyAgreement.PublicKey,
                     direction: Direction, time: UInt64,
                     recordID: Data = .random(count: MailboxHeader.recordIDLength)) throws -> Data {
        try seal(plaintext, identity: identity, recipient: recipient, direction: direction, time: time,
                 recordID: recordID, ephemeral: P256.KeyAgreement.PrivateKey(compactRepresentable: false))
    }

    /// Test vectors only: seals with the given ephemeral key. An ephemeral key must never seal two records.
    static func seal(_ plaintext: Data, identity: KeyAgreementPrivateKey, recipient: P256.KeyAgreement.PublicKey,
                     direction: Direction, time: UInt64, recordID: Data,
                     ephemeral: P256.KeyAgreement.PrivateKey) throws -> Data {
        guard plaintext.count <= maxPlaintext else { throw RemoteProtocolError.plaintextTooLong }
        let header = try MailboxHeader(kind: .sealed, direction: direction, recordID: recordID, time: time,
                                       ephemeralPublicKey: ephemeral.publicKeyX963).encoded
        let ikm = try senderIKM(ephemeral: ephemeral, identity: identity, recipient: recipient)
        return try aeadSeal(plaintext, key: recordKey(ikm: ikm, header: header), nonce: zeroNonce, header: header)
    }

    /// Opens a kind 1 record from the pinned peer `sender`, sent in `direction` (the peer's: `.bridgeToRemote` on
    /// the iPhone). A record that decrypts comes from `sender`, since only it can produce the `ss` term.
    static func open(_ sealed: Data, identity: KeyAgreementPrivateKey, sender: P256.KeyAgreement.PublicKey,
                     direction: Direction) throws -> (header: MailboxHeader, plaintext: Data) {
        let header = try MailboxHeader(parsing: sealed)
        guard header.kind == .sealed, header.direction == direction,
              let ephemeralKey = header.ephemeralPublicKey else { throw RemoteProtocolError.unexpectedRecord }
        let parts = try split(sealed, headerLength: header.length)
        let ikm = try recipientIKM(identity: identity, ephemeral: CryptoPrimitives.publicKey(x963: ephemeralKey),
                                   sender: sender)
        return (header, try aeadOpen(parts, key: recordKey(ikm: ikm, header: parts.header), nonce: zeroNonce))
    }

    // MARK: Kind 2

    /// Seals an invitation record (`rpair.hello`, `rpair.nonce`, `rpair.reveal`) with the invitation secret.
    static func sealInvite(_ plaintext: Data, secret: Data, direction: Direction, time: UInt64,
                           recordID: Data = .random(count: MailboxHeader.recordIDLength)) throws -> Data {
        guard plaintext.count <= maxPlaintext else { throw RemoteProtocolError.plaintextTooLong }
        let header = try MailboxHeader(kind: .invite, direction: direction, recordID: recordID, time: time)
        return try aeadSeal(plaintext, key: InviteKeys(secret: secret).key(for: direction),
                            nonce: Data(header.recordID.prefix(12)), header: header.encoded)
    }

    /// Opens an invitation record sent in `direction` (the peer's).
    static func openInvite(_ sealed: Data, secret: Data,
                           direction: Direction) throws -> (header: MailboxHeader, plaintext: Data) {
        let header = try MailboxHeader(parsing: sealed)
        guard header.kind == .invite, header.direction == direction else { throw RemoteProtocolError.unexpectedRecord }
        let parts = try split(sealed, headerLength: header.length)
        return (header, try aeadOpen(parts, key: InviteKeys(secret: secret).key(for: direction),
                                     nonce: Data(header.recordID.prefix(12))))
    }

    // MARK: AEAD

    private static func aeadSeal(_ plaintext: Data, key: Data, nonce: Data, header: Data) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key), nonce: AES.GCM.Nonce(data: nonce),
                                   authenticating: header)
        return header + box.ciphertext + box.tag
    }

    private static func split(_ sealed: Data,
                              headerLength: Int) throws -> (header: Data, ciphertext: Data, tag: Data) {
        let bytes = [UInt8](sealed)
        guard bytes.count >= headerLength + tagLength,
              bytes.count - headerLength - tagLength <= maxPlaintext else { throw RemoteProtocolError.malformedRecord }
        return (Data(bytes[..<headerLength]), Data(bytes[headerLength..<(bytes.count - tagLength)]),
                Data(bytes[(bytes.count - tagLength)...]))
    }

    private static func aeadOpen(_ parts: (header: Data, ciphertext: Data, tag: Data), key: Data,
                                 nonce: Data) throws -> Data {
        do {
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: parts.ciphertext,
                                            tag: parts.tag)
            return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: parts.header)
        } catch {
            throw RemoteProtocolError.decryptFailed
        }
    }
}

/// Remote pairing values (PROTOCOL.md, "Remote pairing"). Keys are 65-byte X9.62 points, nonces 32 bytes.
enum RemotePairingCrypto {
    static let commitLabel = Data("pairline v1 commit".utf8)
    static let codeLabel = Data("pairline v1 code".utf8)

    /// `commit = SHA-256("pairline v1 commit" ‖ pubR ‖ pubB ‖ nonceB)`
    static func commit(remoteKey: Data, bridgeKey: Data, nonceB: Data) -> Data {
        CryptoPrimitives.sha256(commitLabel + remoteKey + bridgeKey + nonceB)
    }

    /// The comparison code: the first 4 bytes of `HMAC-SHA256(is, "pairline v1 code" ‖ pubR ‖ pubB ‖ nonceR ‖ nonceB)`
    /// as a big-endian number, modulo 1,000,000, as 6 digits.
    static func code(secret: Data, remoteKey: Data, bridgeKey: Data, nonceR: Data, nonceB: Data) -> String {
        let mac = CryptoPrimitives.hmac(key: secret, message: codeLabel + remoteKey + bridgeKey + nonceR + nonceB)
        let digits = String(mac.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) } % 1_000_000)
        return String(repeating: "0", count: 6 - digits.count) + digits
    }
}

/// The three mailbox ids R chooses: 16 random bytes each, as 32 lowercase hex digits.
struct RemoteMailboxes: Equatable {
    /// R→B (`m` in the invitation, `b` in `remote.config`).
    let toBridge: String
    /// B→R alert (`a`).
    let alert: String
    /// B→R quiet (`q`).
    let quiet: String

    static func isValidID(_ id: String) -> Bool {
        id.utf8.count == 32 && id.utf8.allSatisfy { (0x30...0x39).contains($0) || (0x61...0x66).contains($0) }
    }

    /// Three well-formed, distinct ids.
    var isValid: Bool {
        [toBridge, alert, quiet].allSatisfy(Self.isValidID) && Set([toBridge, alert, quiet]).count == 3
    }
}

/// The remote pairing invitation:
/// `pairline://invite?v=1&k=<pubR>&s=<is>&b=<backend>&e=<env>&t=<token>&m=<R→B id>&a=<alert id>&q=<quiet id>&x=<expiry>`
/// (`k`, `s` base64url; `t` percent-encoded; `x` Unix seconds). It holds the invitation secret and a web auth token:
/// only `redactedDescription` (which is also `description`) may be logged.
struct RemoteInvitation: Equatable, CustomStringConvertible {
    enum Backend: String {
        case `public`
        case `private`
    }

    enum Environment: String {
        case production
        case development
    }

    static let secretLength = 32

    let remoteKey: Data
    let secret: Data
    let backend: Backend
    let environment: Environment
    /// CloudKit web auth token for B.
    let token: String
    let mailboxes: RemoteMailboxes
    /// Unix seconds.
    let expiry: UInt64

    /// Validates every field, so any invitation value builds a link that parses.
    init(remoteKey: Data, secret: Data, backend: Backend, environment: Environment, token: String,
         mailboxes: RemoteMailboxes, expiry: UInt64) throws {
        guard (try? CryptoPrimitives.publicKey(x963: remoteKey)) != nil, secret.count == Self.secretLength,
              !token.isEmpty, token.utf8.allSatisfy({ (0x21...0x7e).contains($0) }),
              mailboxes.isValid else { throw RemoteProtocolError.invalidInvitation }
        self.remoteKey = Data(remoteKey)
        self.secret = Data(secret)
        self.backend = backend
        self.environment = environment
        self.token = token
        self.mailboxes = mailboxes
        self.expiry = expiry
    }

    /// Parses a link. Every parameter must appear exactly once; unknown ones are ignored.
    init(string: String) throws {
        guard let components = URLComponents(string: string.trimmingCharacters(in: .whitespacesAndNewlines)),
              components.scheme?.lowercased() == "pairline", components.host?.lowercased() == "invite",
              components.path.isEmpty, let items = components.queryItems else {
            throw RemoteProtocolError.invalidInvitation
        }
        func value(_ name: String) throws -> String {
            let matches = items.filter { $0.name == name }
            guard matches.count == 1, let value = matches[0].value, !value.isEmpty else {
                throw RemoteProtocolError.invalidInvitation
            }
            return value
        }
        let expiry = try value("x")
        guard try value("v") == "1",
              let key = try Data(base64URL: value("k")),
              let secret = try Data(base64URL: value("s")),
              let backend = try Backend(rawValue: value("b")),
              let environment = try Environment(rawValue: value("e")),
              expiry.utf8.allSatisfy({ (0x30...0x39).contains($0) }), let seconds = UInt64(expiry) else {
            throw RemoteProtocolError.invalidInvitation
        }
        try self.init(remoteKey: key, secret: secret, backend: backend, environment: environment, token: value("t"),
                      mailboxes: RemoteMailboxes(toBridge: value("m"), alert: value("a"), quiet: value("q")),
                      expiry: seconds)
    }

    var uri: String {
        "pairline://invite?v=1&k=\(remoteKey.base64URL)&s=\(secret.base64URL)&b=\(backend.rawValue)"
            + "&e=\(environment.rawValue)&t=\(Self.percentEncoded(token))&m=\(mailboxes.toBridge)"
            + "&a=\(mailboxes.alert)&q=\(mailboxes.quiet)&x=\(expiry)"
    }

    var redactedDescription: String {
        "pairline://invite?v=1&k=…&s=…&b=\(backend.rawValue)&e=\(environment.rawValue)&t=…&x=\(expiry)"
    }

    var description: String { redactedDescription }

    /// RFC 3986 unreserved characters; everything else is percent-encoded (as Python's `quote(safe="")`).
    private static let unreserved =
        CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    private static func percentEncoded(_ text: String) -> String {
        text.addingPercentEncoding(withAllowedCharacters: unreserved) ?? ""
    }
}
