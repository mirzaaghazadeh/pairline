import Foundation

/// The QR pairing link `pairline://pair?v=0&id=<device id>&k=<bridge public key>&s=<pairing secret>`.
struct PairingLink: Equatable {
    let deviceID: Data
    let bridgeKey: Data
    let secret: Data

    init(deviceID: Data, bridgeKey: Data, secret: Data) {
        self.deviceID = deviceID
        self.bridgeKey = bridgeKey
        self.secret = secret
    }

    init(string: String) throws {
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed),
              components.scheme?.lowercased() == "pairline",
              components.host?.lowercased() == "pair",
              let items = components.queryItems else { throw ProtocolError.invalidPairingLink }
        func value(_ name: String) -> String? { items.first(where: { $0.name == name })?.value }
        guard value("v") == "0",
              let id = value("id").flatMap(Data.init(base64URL:)), id.count == WireProtocol.deviceIDLength,
              let key = value("k").flatMap(Data.init(base64URL:)), key.count == WireProtocol.publicKeyLength,
              let secret = value("s").flatMap(Data.init(base64URL:)), secret.count == WireProtocol.pairingSecretLength
        else { throw ProtocolError.invalidPairingLink }
        _ = try CryptoPrimitives.publicKey(x963: key)
        self.init(deviceID: id, bridgeKey: key, secret: secret)
    }

    var uri: String {
        "pairline://pair?v=0&id=\(deviceID.base64URL)&k=\(bridgeKey.base64URL)&s=\(secret.base64URL)"
    }

    /// A pairing link must never be logged with its secret.
    var redactedDescription: String { "pairline://pair?v=0&id=\(deviceID.base64URL)&k=…&s=…" }
}

enum PairingCrypto {
    static let label = Data("pairline-pair-v0".utf8)

    /// `T = "pairline-pair-v0" ‖ pubB ‖ pubR ‖ nonceR`
    static func transcript(bridgeKey: Data, remoteKey: Data, nonceR: Data) -> Data {
        label + bridgeKey + remoteKey + nonceR
    }

    /// `macR = HMAC-SHA256(ps, T)`
    static func remoteMAC(secret: Data, transcript: Data) -> Data {
        CryptoPrimitives.hmac(key: secret, message: transcript)
    }

    /// `macB = HMAC-SHA256(ps, T ‖ nonceB ‖ "B")`
    static func bridgeMACInput(transcript: Data, nonceB: Data) -> Data {
        transcript + nonceB + Data("B".utf8)
    }

    static func bridgeMAC(secret: Data, transcript: Data, nonceB: Data) -> Data {
        CryptoPrimitives.hmac(key: secret, message: bridgeMACInput(transcript: transcript, nonceB: nonceB))
    }

    static func verifyBridgeMAC(_ mac: Data, secret: Data, transcript: Data, nonceB: Data) -> Bool {
        CryptoPrimitives.verifyHMAC(mac, key: secret, message: bridgeMACInput(transcript: transcript, nonceB: nonceB))
    }
}

/// `PAIR_HELLO` body: `{"t":"pair_hello","v":0,"k":<pubR>,"n":<nonceR>,"mac":<macR>}`.
struct PairHelloMessage: Codable, Equatable {
    var t = "pair_hello"
    var v = 0
    let k: String
    let n: String
    let mac: String
}

/// `PAIR_OK` body: `{"t":"pair_ok","v":0,"n":<nonceB>,"mac":<macB>}`.
struct PairOKMessage: Codable, Equatable {
    let t: String
    let v: Int
    let n: String
    let mac: String
}

/// `PAIR_FAIL` body: `{"t":"pair_fail","v":0,"reason":"expired"|"used"|"bad_mac"|"busy"}`.
struct PairFailMessage: Codable, Equatable {
    let t: String
    let v: Int?
    let reason: String?
}
