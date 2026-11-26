import Foundation

enum MessageState: String {
    case received
    case pending
    case sent
    case delivered
    case failed
    /// Given to the bridge to send at the message's time (D-084); never flushed from the outbox.
    case scheduled
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

    /// "0098 912…" as "+98 912…" (D-067): "00" is the international prefix in Iran, Turkey and most of the world, and
    /// "+" is how phones write it. The same number, dialed the same; contacts still match (they compare the last digits).
    static func international(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard trimmed.hasPrefix("00"), trimmed.count > 6,
              trimmed.dropFirst(2).first.map({ $0.isASCII && $0.isNumber && $0 != "0" }) == true else { return text }
        return "+" + trimmed.dropFirst(2)
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

    /// The ASCII digits of a number, in order (Persian and Arabic-Indic digits converted).
    static func digits(_ text: String) -> String {
        String(asciiDigits(text).filter { $0.isASCII && $0.isNumber })
    }

    /// Whether a block-list entry covers `number`, the same rule as the bridge (D-043): with seven or more digits on
    /// both sides, the last digits agree (as many as the shorter has, up to 9), so "+354 555 1234" covers "5551234".
    /// Shorter numbers and sender names such as "BANK" must match exactly.
    static func blockMatches(_ blocked: String, _ number: String) -> Bool {
        if let prefix = blockPrefix(blocked) { return prefixMatches(prefix, number) }
        let a = digits(blocked)
        let b = digits(number)
        if a.count >= 7 && b.count >= 7 {
            let n = min(9, a.count, b.count)
            return a.suffix(n) == b.suffix(n)
        }
        guard let key = matchKey(blocked) else { return false }
        return key == matchKey(number)
    }

    /// A block-list entry ending with "*" blocks every number starting with it (D-085), the same rule as the bridge:
    /// at least two digits; "+" or "00" first means an international prefix.
    struct BlockPrefix: Equatable {
        var international: Bool
        var digits: String
    }

    static func blockPrefix(_ entry: String) -> BlockPrefix? {
        let t = entry.trimmingCharacters(in: .whitespaces)
        guard t.hasSuffix("*"), t.filter({ $0 == "*" }).count == 1 else { return nil }
        let body = String(t.dropLast())
        let ascii = asciiDigits(body)
        guard ascii.allSatisfy({ ($0.isASCII && $0.isNumber) || $0 == "+" || $0 == " " || $0 == "-" }) else { return nil }
        let d = digits(body)
        guard d.count >= 2 else { return nil }
        let plus = body.trimmingCharacters(in: .whitespaces).hasPrefix("+")
        return BlockPrefix(international: plus || d.hasPrefix("00"),
                           digits: !plus && d.hasPrefix("00") ? String(d.dropFirst(2)) : d)
    }

    static func prefixMatches(_ prefix: BlockPrefix, _ number: String) -> Bool {
        let trimmed = number.trimmingCharacters(in: .whitespaces)
        let d = digits(trimmed)
        guard prefix.international else { return d.hasPrefix(prefix.digits) }
        if trimmed.hasPrefix("+") { return d.hasPrefix(prefix.digits) }
        if d.hasPrefix("00") { return d.dropFirst(2).hasPrefix(prefix.digits) }
        return false
    }

    /// Whether an address looks like a phone number (and not an alphanumeric sender such as "BANK").
    static func isPhoneNumber(_ text: String) -> Bool {
        let digits = asciiDigits(text).filter { $0.isASCII && $0.isNumber }
        return digits.count >= 3 && !text.contains(where: { $0.isLetter })
    }

    /// The number to call in [text]: the text itself when it's a number (Recents, a keypad link), else the first phone
    /// number found in it (text shared into a Shortcut, D-074). Dialable characters only; nil if there's none.
    static func firstNumber(in text: String) -> String? {
        let direct = international(dialable(text))
        if digits(direct).count >= 3, !text.contains(where: { $0.isLetter }) { return direct }
        let ascii = asciiDigits(text)
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.phoneNumber.rawValue),
              let match = detector.firstMatch(in: ascii, range: NSRange(ascii.startIndex..., in: ascii)),
              let found = match.phoneNumber else { return nil }
        let number = international(dialable(found))
        return digits(number).count >= 3 ? number : nil
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
