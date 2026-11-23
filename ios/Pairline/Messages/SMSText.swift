import Foundation

/// How a text will be split into SMS parts: 160/153 characters in the GSM 7-bit alphabet, 70/67 otherwise
/// (Persian, Arabic and emoji need UCS-2). The bridge splits long messages itself; this only informs the user.
enum SMSSegments {
    private static let gsmBasic: Set<Character> = Set(
        "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà")
    private static let gsmExtended: Set<Character> = Set("^{}\\[~]|€\u{0C}")

    struct Info: Equatable {
        let parts: Int
        let remaining: Int
        let isUnicode: Bool
    }

    static func info(for text: String) -> Info {
        guard !text.isEmpty else { return Info(parts: 0, remaining: 160, isUnicode: false) }
        var septets = 0
        var unicode = false
        for character in text {
            if gsmBasic.contains(character) {
                septets += 1
            } else if gsmExtended.contains(character) {
                septets += 2
            } else {
                unicode = true
                break
            }
        }
        if unicode {
            let units = text.utf16.count
            if units <= 70 { return Info(parts: 1, remaining: 70 - units, isUnicode: true) }
            let parts = Int((Double(units) / 67).rounded(.up))
            return Info(parts: parts, remaining: parts * 67 - units, isUnicode: true)
        }
        if septets <= 160 { return Info(parts: 1, remaining: 160 - septets, isUnicode: false) }
        let parts = Int((Double(septets) / 153).rounded(.up))
        return Info(parts: parts, remaining: parts * 153 - septets, isUnicode: false)
    }
}

enum TextDirection {
    /// Whether the first strong letter is from a right-to-left script (Arabic, Persian, Hebrew).
    static func isRightToLeft(_ text: String) -> Bool {
        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 0x0590...0x08FF, 0xFB1D...0xFDFF, 0xFE70...0xFEFF:
                return true
            default:
                if CharacterSet.letters.contains(scalar) { return false }
            }
        }
        return false
    }
}

enum LinkDetector {
    private static let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)

    /// The text with web links made tappable.
    static func attributed(_ text: String) -> AttributedString {
        var attributed = AttributedString(text)
        guard let detector else { return attributed }
        let ns = text as NSString
        for match in detector.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            guard let url = match.url, let range = Range(match.range, in: text),
                  let lower = AttributedString.Index(range.lowerBound, within: attributed),
                  let upper = AttributedString.Index(range.upperBound, within: attributed) else { continue }
            attributed[lower..<upper].link = url
            attributed[lower..<upper].underlineStyle = .single
        }
        return attributed
    }
}
