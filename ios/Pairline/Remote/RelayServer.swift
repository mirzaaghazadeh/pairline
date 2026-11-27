import CryptoKit
import Foundation
import Network
import Security

/// The user's own relay server (D-106; PROTOCOL.md, "Your own server (relay, D-106)"): a small server the user runs,
/// which both phones connect out to and which joins the two connections byte for byte. It's one more way to open
/// Cloud mode's direct connection, for a home whose router lets nothing in. The session inside is unchanged, end to end
/// between the pinned keys; the server only passes along TLS-wrapped ciphertext.
///
/// The server's link (`pairline-relay://host:port?v=1&fp=…&k=…`) is a secret: anyone holding it can use the server. It
/// lives in the Keychain with the direct connection's other settings (`RemoteDirect`), goes with Cloud mode, and is
/// never logged; neither is the host.
struct RelayServer: Codable, Equatable {
    /// A DNS name (lowercased), an IPv4 address, or an IPv6 address without brackets.
    var host: String
    var port: UInt16
    /// SHA-256 of the server's leaf certificate (its DER bytes): the pin. No CA, name or date is checked.
    var fingerprint: Data
    /// The access key from the link.
    var key: Data
    /// The rendezvous id: 16 random bytes this iPhone picks each time the user sets a server, so one server can serve
    /// several pairs of phones.
    var rendezvous: Data

    static let scheme = "pairline-relay"
    static let version = "1"
    static let fingerprintLength = 32
    static let keyLength = 32
    static let rendezvousLength = 16
    /// The bridge lists this in `status.caps` when it takes `remote.relay` (PROTOCOL.md, "Capabilities").
    static let capability = "relay"

    /// Why a link isn't taken.
    enum Problem: Error, Equatable {
        /// Not a `pairline-relay://` link of the right shape (scheme, port, user info, path, fragment, parameters).
        case notARelayLink
        /// `v` isn't 1: a server newer than this app.
        case unsupportedVersion
        /// The host is inside a home network (private, loopback, carrier-grade NAT, `.local`): out of reach from away.
        case insideHome
    }

    /// The server as Network.framework dials it, and as the direct connection's candidate.
    var endpoint: WiFiEndpoint { WiFiEndpoint(host: host, port: port) }

    /// An IP literal as such, a name for the system to resolve.
    var dialable: NWEndpoint? {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { return nil }
        if host.contains(":"), let address = IPv6Address(host) { return .hostPort(host: .ipv6(address), port: nwPort) }
        if host.split(separator: ".").count == 4, host.allSatisfy({ $0.isASCII && ($0.isNumber || $0 == ".") }),
           let address = IPv4Address(host) {
            return .hostPort(host: .ipv4(address), port: nwPort)
        }
        return .hostPort(host: .name(host, nil), port: nwPort)
    }

    // MARK: - The link

    /// Debug builds also take loopback and private addresses, so the Simulator can reach a relay on the Mac.
    static var allowsLocalHosts: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    /// Reads the server's link (PROTOCOL.md: every rule there), with a fresh rendezvous id. `allowLocal` takes loopback
    /// and private IP addresses too (debug builds, `allowsLocalHosts`).
    static func parse(_ text: String, allowLocal: Bool = allowsLocalHosts) -> Result<RelayServer, Problem> {
        parse(text, allowLocal: allowLocal, rendezvous: Data.random(count: rendezvousLength))
    }

    static func parse(_ text: String, allowLocal: Bool, rendezvous: Data) -> Result<RelayServer, Problem> {
        let link = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let prefix = scheme + "://"
        guard link.count <= 2048, link.lowercased().hasPrefix(prefix), !link.contains("#"),
              link.unicodeScalars.allSatisfy({ $0.isASCII && $0.value > 0x20 && $0.value < 0x7f }) else {
            return .failure(.notARelayLink)
        }
        let rest = link.dropFirst(prefix.count)
        let authorityEnd = rest.firstIndex(where: { $0 == "/" || $0 == "?" }) ?? rest.endIndex
        let authority = rest[..<authorityEnd]
        var tail = rest[authorityEnd...]
        if tail.hasPrefix("/") {
            tail = tail.dropFirst()
            guard tail.isEmpty || tail.hasPrefix("?") else { return .failure(.notARelayLink) }
        }
        guard !authority.contains("@"), tail.hasPrefix("?") else { return .failure(.notARelayLink) }

        // host:port, an IPv6 host in brackets.
        let hostText: Substring
        let portText: Substring
        if authority.hasPrefix("[") {
            guard let close = authority.firstIndex(of: "]") else { return .failure(.notARelayLink) }
            hostText = authority[authority.index(after: authority.startIndex)..<close]
            let afterHost = authority[authority.index(after: close)...]
            guard afterHost.hasPrefix(":"), hostText.contains(":") else { return .failure(.notARelayLink) }
            portText = afterHost.dropFirst()
        } else {
            let parts = authority.split(separator: ":", omittingEmptySubsequences: false)
            guard parts.count == 2 else { return .failure(.notARelayLink) }
            hostText = parts[0]
            portText = parts[1]
        }
        guard let port = plainPort(portText) else { return .failure(.notARelayLink) }

        // v, fp and k exactly once each; anything else is ignored.
        var values: [String: [Substring]] = [:]
        for pair in tail.dropFirst().split(separator: "&", omittingEmptySubsequences: false) {
            let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard parts.count == 2 else { continue }
            values[String(parts[0]), default: []].append(parts[1])
        }
        guard let v = values["v"], v.count == 1 else { return .failure(.notARelayLink) }
        guard v[0] == Substring(version) else { return .failure(.unsupportedVersion) }
        guard let fp = values["fp"], fp.count == 1, let k = values["k"], k.count == 1,
              let fingerprint = Data(base64URL: String(fp[0])), fingerprint.count == fingerprintLength,
              let key = Data(base64URL: String(k[0])), key.count == keyLength,
              rendezvous.count == rendezvousLength else { return .failure(.notARelayLink) }

        switch checkedHost(String(hostText), port: port, allowLocal: allowLocal) {
        case .success(let host):
            return .success(RelayServer(host: host, port: port, fingerprint: fingerprint, key: key,
                                        rendezvous: rendezvous))
        case .failure(let problem):
            return .failure(problem)
        }
    }

    /// The host as stored and dialed, checked like the user's port forwarding (D-095): a DNS name or a public IPv4 or
    /// global IPv6 address; with `allowLocal`, any IPv4 or IPv6 literal too.
    static func checkedHost(_ raw: String, port: UInt16, allowLocal: Bool) -> Result<String, Problem> {
        switch ManualForward.make(host: raw, port: String(port)) {
        case .success(let forward):
            return .success(forward.host)
        case .failure(.insideHome):
            if allowLocal, let literal = ipLiteral(raw) { return .success(literal) }
            return .failure(.insideHome)
        case .failure:
            if allowLocal, let literal = ipLiteral(raw) { return .success(literal) }
            return .failure(.notARelayLink)
        }
    }

    /// An IPv4 address in four dotted decimal parts, or an IPv6 address, in its normal form; nil otherwise.
    private static func ipLiteral(_ raw: String) -> String? {
        let text = raw.trimmingCharacters(in: .whitespaces)
        if text.contains(":") {
            guard text.allSatisfy({ $0.isASCII && ($0.isHexDigit || $0 == ":" || $0 == ".") }),
                  let address = IPv6Address(text) else { return nil }
            return "\(address)"
        }
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4, parts.allSatisfy({ !$0.isEmpty && $0.count <= 3 && $0.allSatisfy(\.isNumber) }),
              let address = IPv4Address(text) else { return nil }
        return "\(address)"
    }

    /// 1–65535 in plain ASCII decimal: no sign, no leading zero.
    private static func plainPort(_ text: Substring) -> UInt16? {
        guard !text.isEmpty, text.count <= 5, text.allSatisfy({ $0.isASCII && $0.isNumber }), !text.hasPrefix("0"),
              let value = Int(text), (1...65535).contains(value) else { return nil }
        return UInt16(value)
    }

    /// Read back from the Keychain: checked again, nil when no longer valid.
    var checked: RelayServer? {
        guard fingerprint.count == Self.fingerprintLength, key.count == Self.keyLength,
              rendezvous.count == Self.rendezvousLength,
              case .success(let host) = Self.checkedHost(host, port: port, allowLocal: Self.allowsLocalHosts),
              host == self.host else { return nil }
        return self
    }

    // MARK: - The wire

    /// `remote.relay`'s `relay` object (PROTOCOL.md, "Remote messages").
    var message: [String: Any] {
        ["host": host, "port": Int(port), "fp": fingerprint.base64URL, "k": key.base64URL, "rv": rendezvous.hex]
    }

    enum Role: UInt8 {
        case bridge = 0x42
        case remote = 0x52
    }

    /// The 54-byte preamble sent inside TLS: `PLRY`, version 1, the role, the access key, the rendezvous id.
    func preamble(role: Role = .remote) -> Data {
        var out = Data("PLRY".utf8)
        out.append(0x01)
        out.append(role.rawValue)
        out.append(key)
        out.append(rendezvous)
        return out
    }

    static let preambleLength = 54

    /// The relay's one-byte answers.
    enum Status: UInt8 {
        case keepAlive = 0x00
        case joined = 0x01
        case noBridge = 0x02
        case wrongKey = 0x03
        case badVersion = 0x04
        case full = 0x05
    }

    /// Whether the server's certificate chain starts with the pinned certificate: SHA-256 of the leaf's DER bytes.
    static func pinMatches(_ trust: SecTrust, fingerprint: Data) -> Bool {
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let leaf = chain.first else {
            return false
        }
        return pinMatches(certificate: SecCertificateCopyData(leaf) as Data, fingerprint: fingerprint)
    }

    static func pinMatches(certificate der: Data, fingerprint: Data) -> Bool {
        let digest = Data(SHA256.hash(data: der))
        guard digest.count == fingerprint.count else { return false }
        // Constant time, though the pin isn't secret.
        return zip(digest, fingerprint).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }
}

/// What the bridge says about the user's own server in `remote.state` (`relay`, `relayReason`).
enum RelayBridgeState: String, Codable, Equatable {
    /// The bridge waits at the server, or a session runs through it.
    case up
    case down
}

enum RelayBridgeReason: String, Codable, Equatable {
    /// No TCP or TLS connection to the server.
    case unreachable
    /// The server's certificate doesn't match the link: a different server, or one that made new keys.
    case pin
    /// The server refused the access key.
    case key
    /// The server doesn't speak this version.
    case version
    /// The server is full.
    case full
}

/// The last time this iPhone tried its own server (in memory only): when, and how it went.
struct RelayAttempt: Equatable {
    enum Outcome: Equatable {
        /// A session with the bridge runs through the server.
        case connected
        /// The server answered, but the Android phone isn't waiting there right now.
        case noBridge
        case wrongKey
        case badVersion
        case full
        /// The certificate doesn't match the link.
        case pinMismatch
        /// No connection to the server, or it closed before answering.
        case unreachable
        /// The server didn't answer in time.
        case timeout
        /// The server joined us to something that isn't the paired bridge.
        case handshakeFailed

        /// For the log: never the host.
        var logText: String {
            switch self {
            case .connected: return "connected"
            case .noBridge: return "the bridge isn't waiting there"
            case .wrongKey: return "the server refused the access key"
            case .badVersion: return "the server doesn't speak this version"
            case .full: return "the server is full"
            case .pinMismatch: return "the server's certificate doesn't match the link"
            case .unreachable: return "couldn't reach the server"
            case .timeout: return "the server didn't answer in time"
            case .handshakeFailed: return "the handshake through the server failed"
            }
        }
    }

    var at: Date
    var outcome: Outcome
}
