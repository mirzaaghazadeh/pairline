import Foundation
import Network

/// Remote mode's direct connection (D-092; PROTOCOL.md, "Direct connection"): the bridge's addresses checked strictly.
/// The only non-local addresses this iPhone ever dials pass through here, and they come only from an authenticated
/// `remote.addr` of the paired bridge.
enum DirectAddress {
    /// A public IPv4 address: not "this network" (0/8), private (RFC 1918), carrier-grade NAT (100.64/10), loopback,
    /// link-local, the IETF block (192.0.0/24), documentation, the 6to4 relay, benchmarking (198.18/15), multicast,
    /// reserved or broadcast (RFC 6890).
    static func isPublic(_ address: IPv4Address) -> Bool {
        let b = [UInt8](address.rawValue)
        guard b.count == 4 else { return false }
        switch (b[0], b[1], b[2]) {
        case (0, _, _), (10, _, _), (127, _, _), (224..., _, _): return false
        case (100, 64...127, _), (169, 254, _), (172, 16...31, _), (192, 168, _), (198, 18...19, _): return false
        case (192, 0, 0), (192, 0, 2), (192, 88, 99), (198, 51, 100), (203, 0, 113): return false
        default: return true
        }
    }

    /// A global unicast IPv6 address (2000::/3), but not the IETF block 2001::/23 (Teredo, benchmarking), 6to4
    /// (2002::/16) or documentation (2001:db8::/32, 3fff::/20). Unique-local, link-local, loopback, multicast,
    /// IPv4-mapped and NAT64 addresses all fall outside 2000::/3.
    static func isGlobal(_ address: IPv6Address) -> Bool {
        let b = [UInt8](address.rawValue)
        guard b.count == 16, b[0] & 0xe0 == 0x20 else { return false }
        switch (b[0], b[1]) {
        case (0x20, 0x01): return b[2] >= 0x02 && !(b[2] == 0x0d && b[3] == 0xb8)
        case (0x20, 0x02): return false
        case (0x3f, 0xff): return b[2] & 0xf0 != 0
        default: return true
        }
    }

    /// `remote.addr` `v4`: `"a.b.c.d:port"` with a public address, or nil.
    static func v4(_ text: String) -> WiFiEndpoint? {
        let parts = text.split(separator: ":", omittingEmptySubsequences: false)
        guard text.count <= 21, parts.count == 2, let port = port(parts[1]) else { return nil }
        let octets = parts[0].split(separator: ".", omittingEmptySubsequences: false)
        guard octets.count == 4, octets.allSatisfy({ isDecimal($0, max: 255) }),
              let address = IPv4Address(String(parts[0])), isPublic(address) else { return nil }
        return WiFiEndpoint(host: "\(address)", port: port)
    }

    /// `remote.addr` `v6`: `"[address]:port"` with a global address (no zone, no name), or nil.
    static func v6(_ text: String) -> WiFiEndpoint? {
        guard text.count <= 53, text.hasPrefix("["), let close = text.firstIndex(of: "]") else { return nil }
        let host = text[text.index(after: text.startIndex)..<close]
        let rest = text[text.index(after: close)...]
        guard !host.isEmpty, host.allSatisfy({ $0.isASCII && ($0.isHexDigit || $0 == ":" || $0 == ".") }),
              rest.hasPrefix(":"), let port = port(rest.dropFirst()),
              let address = IPv6Address(String(host)), isGlobal(address) else { return nil }
        return WiFiEndpoint(host: "\(address)", port: port)
    }

    /// The endpoint as Network.framework dials it: an IP literal (never a name to resolve), and only while it's still
    /// an address `v4` or `v6` accepts.
    static func dialable(_ endpoint: WiFiEndpoint) -> NWEndpoint? {
        guard let port = NWEndpoint.Port(rawValue: endpoint.port) else { return nil }
        if endpoint.host.contains(":") {
            guard v6("[\(endpoint.host)]:\(endpoint.port)") == endpoint, let address = IPv6Address(endpoint.host) else {
                return nil
            }
            return .hostPort(host: .ipv6(address), port: port)
        }
        guard v4("\(endpoint.host):\(endpoint.port)") == endpoint, let address = IPv4Address(endpoint.host) else {
            return nil
        }
        return .hostPort(host: .ipv4(address), port: port)
    }

    /// For the log, which never holds the bridge's addresses (AGENTS.md rule 9).
    static func family(_ endpoint: WiFiEndpoint) -> String {
        endpoint.host.contains(":") ? "IPv6" : "IPv4"
    }

    /// 1–65535 in plain decimal: no sign, no leading zero.
    private static func port(_ text: Substring) -> UInt16? {
        guard isDecimal(text, max: 65535), let value = UInt16(text), value > 0 else { return nil }
        return value
    }

    private static func isDecimal(_ text: Substring, max: Int) -> Bool {
        guard !text.isEmpty, text.count <= 5, text.allSatisfy({ $0.isASCII && $0.isNumber }),
              text == "0" || !text.hasPrefix("0"), let value = Int(text) else { return false }
        return value <= max
    }
}

/// Where the bridge's direct connection listens, from its last `remote.addr`: a public IPv4 and a global IPv6
/// address with their ports, either missing.
struct DirectEndpoints: Codable, Equatable {
    var v4: WiFiEndpoint?
    var v6: WiFiEndpoint?

    /// In the order they're tried: IPv6 first (no NAT on the way), then IPv4.
    var candidates: [WiFiEndpoint] { [v6, v4].compactMap { $0 } }

    var isEmpty: Bool { v4 == nil && v6 == nil }

    /// For the log: which families, never the addresses.
    var summary: String {
        isEmpty ? "none" : candidates.map(DirectAddress.family).joined(separator: " + ")
    }
}

extension DirectEndpoints {
    /// `remote.addr`'s body. A value that isn't a valid address of its family counts as missing.
    init(message object: [String: Any]) {
        v4 = (object["v4"] as? String).flatMap(DirectAddress.v4)
        v6 = (object["v6"] as? String).flatMap(DirectAddress.v6)
    }

    /// Stored endpoints are checked again when read back.
    var checked: DirectEndpoints {
        DirectEndpoints(v4: v4.flatMap { DirectAddress.v4("\($0.host):\($0.port)") },
                        v6: v6.flatMap { DirectAddress.v6("[\($0.host)]:\($0.port)") })
    }
}

/// The bridge's last `remote.state`: whether its direct connection can be reached, and roughly why not.
struct DirectStatus: Codable, Equatable {
    enum Availability: String, Codable {
        case open
        case unavailable = "none"
        case blocked
    }

    /// `reason` is one of PROTOCOL.md's codes (`cgnat`, `no_public_address`, `no_mapping`, `not_wifi`,
    /// `listener_failed`); anything else is matched by its words, and never shown or logged (it may hold addresses).
    enum Reason: String, Codable {
        case carrierNAT
        case noPublicAddress
        case router
        case notOnWiFi

        var text: String {
            switch self {
            case .carrierNAT: return String(localized: "carrier-grade NAT")
            case .noPublicAddress: return String(localized: "no public address")
            case .router: return String(localized: "the router doesn't open a port")
            case .notOnWiFi: return String(localized: "the bridge phone isn't on Wi-Fi")
            }
        }
    }

    var availability: Availability
    var reason: Reason?
    /// The user's own server (D-106): nil when the bridge has none set (or is older), `up` while it waits there.
    var relay: RelayBridgeState? = nil
    /// Why not, when `relay` is `down`; nil for a reason this app doesn't know.
    var relayReason: RelayBridgeReason? = nil
}

extension DirectStatus {
    /// `remote.state`'s body; nil unless `direct` is one of the three values. An unknown `relay` counts as `down`.
    init?(message object: [String: Any]) {
        guard let raw = object["direct"] as? String, let availability = Availability(rawValue: raw) else { return nil }
        self.availability = availability
        reason = availability == .open ? nil : (object["reason"] as? String).flatMap(Reason.init(text:))
        if let relay = object["relay"] as? String {
            let state = RelayBridgeState(rawValue: relay) ?? .down
            self.relay = state
            relayReason = state == .down ? (object["relayReason"] as? String).flatMap(RelayBridgeReason.init(rawValue:)) : nil
        }
    }
}

extension DirectStatus.Reason {
    init?(text: String) {
        switch text {
        case "cgnat": self = .carrierNAT; return
        case "no_public_address": self = .noPublicAddress; return
        case "no_mapping": self = .router; return
        case "not_wifi": self = .notOnWiFi; return
        case "listener_failed": return nil
        default: break
        }
        let words = text.prefix(300).lowercased()
        func has(_ any: String...) -> Bool { any.contains { words.contains($0) } }
        if has("cgnat", "carrier", "100.64") {
            self = .carrierNAT
        } else if has("public", "private", "double nat") {
            self = .noPublicAddress
        } else if has("router", "upnp", "nat-pmp", "natpmp", "pcp", "igd", "mapping", "port forward") {
            self = .router
        } else if has("wi-fi", "wifi", "mobile data", "cellular") {
            self = .notOnWiFi
        } else {
            return nil
        }
    }
}

/// What the bridge last said about its direct connection (`remote.addr`, `remote.state`), the port forwarding the
/// user set up by hand and the user's own server, kept until remote mode goes off.
struct RemoteDirect: Codable, Equatable {
    var endpoints = DirectEndpoints()
    var status: DirectStatus?
    /// Manual port forwarding (D-095): where the user says the bridge's home can be reached, for a router without
    /// UPnP, NAT-PMP or PCP.
    var manual: ManualForward?
    /// The user's own relay server (D-106), with this pairing's rendezvous id.
    var relay: RelayServer?
}

/// Manual port forwarding (D-095): the home's internet address or DDNS name and the port the user forwarded to the
/// bridge, typed on this iPhone. Without UPnP, NAT-PMP or PCP the bridge can't learn the home's public address, and
/// Pairline never asks an outside "what's my IP" service, so the user enters it. This is the one place a host name is
/// dialed (the system resolves it); the handshake still pins the bridge's key, so a wrong host only fails.
struct ManualForward: Codable, Equatable {
    var host: String
    var port: UInt16

    static let defaultPort: UInt16 = 47830

    enum Problem: Error, Equatable {
        /// Nothing typed.
        case empty
        /// Neither a DNS name nor an IP address.
        case notAnAddress
        /// An address inside a home network (private, loopback, link-local, carrier-grade NAT) or a `.local` name.
        case insideHome
        /// Not 1–65535.
        case badPort
    }

    var endpoint: WiFiEndpoint { WiFiEndpoint(host: host, port: port) }

    /// Checks what the user typed: a DNS name (letters, digits and hyphens per label, at least two labels, up to 253
    /// characters) or a public IPv4 or global IPv6 address, and a port 1–65535. The name is kept lowercased, without a
    /// trailing dot; an IPv6 address without brackets.
    static func make(host rawHost: String, port rawPort: String) -> Result<ManualForward, Problem> {
        var host = rawHost.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if host.hasPrefix("["), host.hasSuffix("]") { host = String(host.dropFirst().dropLast()) }
        if host.hasSuffix(".") { host = String(host.dropLast()) }
        guard !host.isEmpty else { return .failure(.empty) }
        // Any script's decimal digits (a Persian or Arabic keypad types ۴۷۸۳۰ or ٤٧٨٣٠).
        let digits = rawPort.trimmingCharacters(in: .whitespacesAndNewlines).map { ch -> Int? in
            guard let value = ch.wholeNumberValue, (0...9).contains(value), ch.isNumber else { return nil }
            return value
        }
        guard !digits.isEmpty, digits.count <= 5, !digits.contains(nil) else { return .failure(.badPort) }
        let portValue = digits.reduce(0) { $0 * 10 + ($1 ?? 0) }
        guard (1...65535).contains(portValue) else { return .failure(.badPort) }
        let port = UInt16(portValue)
        if host.contains(":") {
            guard host.allSatisfy({ $0.isASCII && ($0.isHexDigit || $0 == ":" || $0 == ".") }),
                  let address = IPv6Address(host) else { return .failure(.notAnAddress) }
            guard DirectAddress.isGlobal(address) else { return .failure(.insideHome) }
            return .success(ManualForward(host: "\(address)", port: port))
        }
        let labels = host.split(separator: ".", omittingEmptySubsequences: false)
        if labels.count == 4, labels.allSatisfy({ !$0.isEmpty && $0.count <= 3 && $0.allSatisfy(\.isNumber) }) {
            guard let address = IPv4Address(host) else { return .failure(.notAnAddress) }
            guard DirectAddress.isPublic(address) else { return .failure(.insideHome) }
            return .success(ManualForward(host: "\(address)", port: port))
        }
        guard host.count <= 253, labels.count >= 2,
              labels.allSatisfy({ label in
                  (1...63).contains(label.count) && !label.hasPrefix("-") && !label.hasSuffix("-")
                      && label.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }
              }),
              let last = labels.last, !last.allSatisfy(\.isNumber) else { return .failure(.notAnAddress) }
        if last == "local" || last == "localhost" || last == "lan" || last == "home" || last == "internal" {
            return .failure(.insideHome)
        }
        return .success(ManualForward(host: host, port: port))
    }

    /// Read back from storage: checked again, nil when no longer valid.
    var checked: ManualForward? {
        try? Self.make(host: host, port: String(port)).get()
    }

    /// How Network.framework dials it: an IP literal as such, a name for the system to resolve.
    var dialable: NWEndpoint? {
        guard let port = NWEndpoint.Port(rawValue: port) else { return nil }
        if let address = IPv6Address(host), host.contains(":") { return .hostPort(host: .ipv6(address), port: port) }
        if let address = IPv4Address(host), host.split(separator: ".").count == 4 {
            return .hostPort(host: .ipv4(address), port: port)
        }
        return .hostPort(host: .name(host, nil), port: port)
    }
}

/// The last time the direct connection tried the user's port forwarding (in memory only): when, and whether the
/// bridge answered there.
struct ManualAttempt: Equatable {
    var at: Date
    var worked: Bool
}
