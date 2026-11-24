import Foundation

/// The QR pairing link `pairline://pair?v=0&id=<device id>&k=<bridge public key>&s=<pairing secret>`, optionally with
/// the bridge's Wi-Fi addresses (`ip`, `port`) and its hotspot (`ssid`, `pw`, base64url UTF-8) (PROTOCOL.md, "Pairing
/// over Wi-Fi"). Unknown parameters are ignored, so links from older and newer bridges both parse.
struct PairingLink: Equatable {
    static let maxAddresses = 4

    let deviceID: Data
    let bridgeKey: Data
    let secret: Data
    /// Private, link-local or unique-local addresses only; anything else in the link is dropped.
    var wifiAddresses: [String] = []
    var wifiPort: UInt16?
    var hotspotSSID: String?
    var hotspotPassword: String?

    init(deviceID: Data, bridgeKey: Data, secret: Data, wifiAddresses: [String] = [], wifiPort: UInt16? = nil,
         hotspotSSID: String? = nil, hotspotPassword: String? = nil) {
        self.deviceID = deviceID
        self.bridgeKey = bridgeKey
        self.secret = secret
        self.wifiAddresses = wifiAddresses
        self.wifiPort = wifiPort
        self.hotspotSSID = hotspotSSID
        self.hotspotPassword = hotspotPassword
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
        let addresses = (value("ip") ?? "").split(separator: ",").map(String.init)
            .filter { LocalAddress.isAllowed($0) }
        func text(_ name: String) -> String? {
            value(name).flatMap(Data.init(base64URL:)).flatMap { String(data: $0, encoding: .utf8) }
                .flatMap { $0.isEmpty ? nil : $0 }
        }
        self.init(deviceID: id, bridgeKey: key, secret: secret,
                  wifiAddresses: Array(addresses.prefix(PairingLink.maxAddresses)),
                  wifiPort: value("port").flatMap(UInt16.init).flatMap { $0 == 0 ? nil : $0 },
                  hotspotSSID: text("ssid"), hotspotPassword: text("pw"))
    }

    /// Whether R can try to reach B over Wi-Fi with this link alone.
    var hasWiFi: Bool { wifiPort != nil && !wifiAddresses.isEmpty }

    var uri: String {
        var out = "pairline://pair?v=0&id=\(deviceID.base64URL)&k=\(bridgeKey.base64URL)&s=\(secret.base64URL)"
        if !wifiAddresses.isEmpty { out += "&ip=" + wifiAddresses.joined(separator: ",") }
        if let wifiPort { out += "&port=\(wifiPort)" }
        if let hotspotSSID { out += "&ssid=" + Data(hotspotSSID.utf8).base64URL }
        if let hotspotPassword { out += "&pw=" + Data(hotspotPassword.utf8).base64URL }
        return out
    }

    /// A pairing link must never be logged with its secret (or the hotspot passphrase, or addresses).
    var redactedDescription: String {
        "pairline://pair?v=0&id=\(deviceID.base64URL)&k=…&s=…" + (hasWiFi ? " (+Wi-Fi)" : "")
            + (hotspotSSID != nil ? " (+hotspot)" : "")
    }
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
