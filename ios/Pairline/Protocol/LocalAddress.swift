import Foundation
import Network

/// The Wi-Fi link only talks to addresses on the local network (PROTOCOL.md, "Wi-Fi"): private IPv4 (RFC 1918),
/// link-local IPv4 (169.254/16), unique-local IPv6 (fc00::/7) and link-local IPv6 (fe80::/10).
enum LocalAddress {
    static func isAllowed(_ text: String) -> Bool {
        let host = text.split(separator: "%", maxSplits: 1).first.map(String.init) ?? text
        if !host.contains(":") {
            // Dotted quads only: the parser would also take shorthand like "192.168.1".
            guard host.split(separator: ".", omittingEmptySubsequences: false).count == 4,
                  let v4 = IPv4Address(host) else { return false }
            return isAllowed(v4)
        }
        if let v6 = IPv6Address(host) { return isAllowed(v6) }
        return false
    }

    static func isAllowed(_ address: IPv4Address) -> Bool {
        let b = [UInt8](address.rawValue)
        guard b.count == 4 else { return false }
        switch (b[0], b[1]) {
        case (10, _): return true
        case (172, 16...31): return true
        case (192, 168): return true
        case (169, 254): return true
        default: return false
        }
    }

    static func isAllowed(_ address: IPv6Address) -> Bool {
        let b = [UInt8](address.rawValue)
        guard b.count == 16 else { return false }
        if b[0] & 0xfe == 0xfc { return true } // fc00::/7
        if b[0] == 0xfe, b[1] & 0xc0 == 0x80 { return true } // fe80::/10
        if let v4 = address.asIPv4 { return isAllowed(v4) } // ::ffff:a.b.c.d
        return false
    }

    /// The host part of an endpoint, if it's an address the Wi-Fi link may use.
    static func isAllowed(_ host: NWEndpoint.Host) -> Bool {
        switch host {
        case .ipv4(let v4): return isAllowed(v4)
        case .ipv6(let v6): return isAllowed(v6)
        case .name(let name, _): return isAllowed(name)
        @unknown default: return false
        }
    }
}
