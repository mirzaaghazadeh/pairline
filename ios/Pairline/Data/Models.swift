import Foundation

enum MessageState: String {
    case received
    case pending
    case sent
    case delivered
    case failed
}

struct StoredMessage: Identifiable, Equatable {
    var key: String
    var thread: Int64
    var addr: String
    var body: String
    var time: Date
    var isOutgoing: Bool
    var sim: Int?
    var state: MessageState
    var cid: String?
    var error: String?

    var id: String { key }
}

struct Conversation: Identifiable, Equatable {
    var thread: Int64
    var addresses: [String]
    var snippet: String
    var time: Date
    var unread: Int
    var hasOlder: Bool
    var lastIsOutgoing: Bool
    var lastState: MessageState?

    var id: Int64 { thread }
    var primaryAddress: String { addresses.first ?? "" }
}

struct Contact: Identifiable, Equatable, Hashable {
    var key: String
    var name: String
    var starred: Bool
    var numbers: [ContactNumber]
    var emails: [String]

    var id: String { key }
    var primaryNumber: String? { numbers.first?.n }
}

enum CallLogType: String {
    case incoming = "in"
    case outgoing = "out"
    case missed
    case rejected
    case blocked
    case voicemail
}

struct CallLogItem: Identifiable, Equatable {
    var key: String
    var number: String
    var name: String?
    var type: CallLogType
    var time: Date
    var duration: Int
    var sim: Int?

    var id: String { key }
}

extension Date {
    init(milliseconds: Int64) {
        self.init(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
    }

    var milliseconds: Int64 { Int64((timeIntervalSince1970 * 1000).rounded()) }
}

/// Phone-number helpers. Numbers are shown as the bridge sends them, always left-to-right.
enum PhoneNumber {
    /// Converts Persian and Arabic-Indic digits to ASCII.
    static func asciiDigits(_ text: String) -> String {
        String(text.unicodeScalars.map { scalar -> Character in
            switch scalar.value {
            case 0x06F0...0x06F9: return Character(UnicodeScalar(scalar.value - 0x06F0 + 0x30)!)
            case 0x0660...0x0669: return Character(UnicodeScalar(scalar.value - 0x0660 + 0x30)!)
            default: return Character(scalar)
            }
        })
    }

    /// The characters a dialer accepts: digits, +, *, #.
    static func dialable(_ text: String) -> String {
        String(asciiDigits(text).filter { $0.isASCII && ($0.isNumber || "+*#".contains($0)) })
    }

    /// A key for matching numbers written in different formats: the last 9 digits.
    static func matchKey(_ text: String) -> String? {
        let digits = asciiDigits(text).filter { $0.isASCII && $0.isNumber }
        guard digits.count >= 5 else {
            let trimmed = text.trimmingCharacters(in: .whitespaces).lowercased()
            return trimmed.isEmpty ? nil : "t:" + trimmed
        }
        return String(digits.suffix(9))
    }

    /// Whether an address looks like a phone number (and not an alphanumeric sender such as "BANK").
    static func isPhoneNumber(_ text: String) -> Bool {
        let digits = asciiDigits(text).filter { $0.isASCII && $0.isNumber }
        return digits.count >= 3 && !text.contains(where: { $0.isLetter })
    }

    /// Wraps text in Unicode left-to-right isolates so numbers keep their order inside right-to-left text.
    static func ltr(_ text: String) -> String { "\u{2066}\(text)\u{2069}" }

    /// USSD-looking input such as `*140#` or `*#06#`.
    static func isUSSD(_ text: String) -> Bool {
        let t = dialable(text)
        return (t.hasPrefix("*") || t.hasPrefix("#")) && t.hasSuffix("#") && t.count >= 3
    }

    /// A stable negative thread id for a conversation the bridge hasn't stored yet.
    static func syntheticThreadID(for address: String) -> Int64 {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in (matchKey(address) ?? address).utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01b3
        }
        return -Int64(hash % 1_000_000_000_000) - 1
    }
}
