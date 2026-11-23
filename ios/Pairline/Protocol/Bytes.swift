import Foundation

/// Byte helpers used by the link protocol (docs/PROTOCOL.md): hex, base64url without padding,
/// and big-endian integers.
extension Data {
    init?(hex: String) {
        let chars = Array(hex.utf8)
        guard chars.count % 2 == 0 else { return nil }
        var out = Data(capacity: chars.count / 2)
        var index = 0
        while index < chars.count {
            guard let hi = Data.nibble(chars[index]), let lo = Data.nibble(chars[index + 1]) else { return nil }
            out.append(hi << 4 | lo)
            index += 2
        }
        self = out
    }

    private static func nibble(_ c: UInt8) -> UInt8? {
        switch c {
        case UInt8(ascii: "0")...UInt8(ascii: "9"): return c - UInt8(ascii: "0")
        case UInt8(ascii: "a")...UInt8(ascii: "f"): return c - UInt8(ascii: "a") + 10
        case UInt8(ascii: "A")...UInt8(ascii: "F"): return c - UInt8(ascii: "A") + 10
        default: return nil
        }
    }

    var hex: String {
        let digits = Array("0123456789abcdef".utf8)
        var out = [UInt8]()
        out.reserveCapacity(count * 2)
        for byte in self {
            out.append(digits[Int(byte >> 4)])
            out.append(digits[Int(byte & 0x0f)])
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// base64url without padding (RFC 4648 §5), as used in JSON bodies and pairing links.
    init?(base64URL: String) {
        guard !base64URL.contains("="), !base64URL.contains("+"), !base64URL.contains("/") else { return nil }
        var s = base64URL.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let remainder = s.count % 4
        if remainder == 1 { return nil }
        if remainder > 0 { s += String(repeating: "=", count: 4 - remainder) }
        guard let data = Data(base64Encoded: s) else { return nil }
        self = data
    }

    var base64URL: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func bigEndian<T: FixedWidthInteger>(_ value: T) -> Data {
        var be = value.bigEndian
        return Swift.withUnsafeBytes(of: &be) { Data($0) }
    }

    /// Reads a big-endian unsigned integer of `T`'s width at `offset` (relative to `startIndex`).
    func readBigEndian<T: FixedWidthInteger>(_ type: T.Type, at offset: Int = 0) -> T? {
        let size = MemoryLayout<T>.size
        guard offset >= 0, count >= offset + size else { return nil }
        var value: T = 0
        for byte in self[(startIndex + offset)..<(startIndex + offset + size)] {
            value = value << 8 | T(byte)
        }
        return value
    }

    static func random(count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        return Data(bytes)
    }
}
