import Foundation

/// Finds one-time codes in SMS text, on the device only. A message needs a code-like keyword (in English,
/// Persian, Arabic and a few other languages) and a 4–8 digit candidate near it; amounts, times, dates and phone
/// numbers are skipped. Persian and Arabic-Indic digits are accepted and the code is returned in ASCII digits.
enum OTPDetector {
    private static let keywords: [String] = [
        // English
        "code", "otp", "passcode", "password", "pin", "verification", "verify", "one-time", "one time",
        "security", "login", "log in", "sign in", "sign-in", "authentication", "auth", "token", "confirm",
        // Persian
        "کد", "رمز", "تایید", "تأیید", "یکبار", "یک بار", "ورود", "پویا", "احراز",
        // Arabic
        "رمز", "كود", "الرمز", "التحقق", "تحقق", "التفعيل", "تفعيل", "كلمة المرور", "لمرة واحدة", "الدخول",
        // Turkish, Spanish, French, German, Russian
        "kod", "doğrulama", "şifre", "código", "clave", "verificación", "vérification", "bestätigung",
        "код", "пароль",
    ]

    private static let currencyWords: [String] = [
        "$", "€", "£", "¥", "₺", "usd", "eur", "irr", "irt", "aed", "sar", "rial", "riyal", "toman",
        "ریال", "تومان", "درهم", "دينار", "ريال", "ليرة", "balance", "موجودی", "مبلغ", "الرصيد",
    ]

    /// 4–8 digits, or two groups of 3–4 split by a space or dash, not part of a longer number, decimal, time or date.
    private static let candidateRegex = try! NSRegularExpression(
        pattern: #"(?<![\d+])(?<!\d[.,:/\-])(\d{3,4}[ \-]\d{3,4}|\d{4,8})(?!\d|[.,:/]\d|%|-\d)"#)

    /// Returns the most likely one-time code in `text`, in ASCII digits.
    static func code(in text: String) -> String? {
        let normalized = PhoneNumber.asciiDigits(text)
        let lower = normalized.lowercased()
        let keywordRanges = keywordPositions(in: lower)
        guard !keywordRanges.isEmpty else { return nil }

        let ns = normalized as NSString
        var best: (code: String, score: Int)?
        for match in candidateRegex.matches(in: normalized, range: NSRange(location: 0, length: ns.length)) {
            let range = match.range(at: 1)
            let raw = ns.substring(with: range)
            let digits = raw.filter(\.isNumber)
            guard (4...8).contains(digits.count) else { continue }
            if isExcluded(range: range, in: ns, lower: lower as NSString, digits: digits) { continue }
            let distance = keywordRanges.map { abs($0 - range.location) }.min() ?? 1000
            var score = 1000 - min(distance, 900)
            if digits.count == 6 { score += 60 } else if digits.count == 4 || digits.count == 5 { score += 30 }
            if raw.contains(" ") || raw.contains("-") { score -= 20 }
            if best == nil || score > best!.score { best = (digits, score) }
        }
        return best?.code
    }

    private static func keywordPositions(in lower: String) -> [Int] {
        let ns = lower as NSString
        var positions: [Int] = []
        for keyword in keywords {
            var search = NSRange(location: 0, length: ns.length)
            while true {
                let found = ns.range(of: keyword, options: [], range: search)
                if found.location == NSNotFound { break }
                if isWordBoundary(ns, found) { positions.append(found.location) }
                let next = found.location + found.length
                guard next < ns.length else { break }
                search = NSRange(location: next, length: ns.length - next)
            }
        }
        // "G-123456" style prefixes (Google and others).
        if let regex = try? NSRegularExpression(pattern: #"\b[a-z]{1,3}-\d{4,8}\b"#) {
            for match in regex.matches(in: lower, range: NSRange(location: 0, length: ns.length)) {
                positions.append(match.range.location)
            }
        }
        return positions
    }

    /// Latin keywords must stand alone ("pin" but not "spinning"); other scripts attach prefixes, so they match anywhere.
    private static func isWordBoundary(_ ns: NSString, _ range: NSRange) -> Bool {
        let word = ns.substring(with: range)
        guard word.unicodeScalars.allSatisfy({ $0.isASCII }) else { return true }
        func isLetter(_ index: Int) -> Bool {
            guard index >= 0, index < ns.length else { return false }
            let c = ns.character(at: index)
            return CharacterSet.letters.contains(UnicodeScalar(c) ?? " ")
        }
        return !isLetter(range.location - 1) && !isLetter(range.location + range.length)
    }

    private static func isExcluded(range: NSRange, in ns: NSString, lower: NSString, digits: String) -> Bool {
        // Years such as 2024 next to a date separator were already excluded by the regex; skip clock times.
        let before = ns.substring(with: NSRange(location: max(0, range.location - 12),
                                                length: min(12, range.location))).lowercased()
        let afterStart = range.location + range.length
        let after = ns.substring(with: NSRange(location: afterStart, length: min(14, ns.length - afterStart))).lowercased()
        let trimmedBefore = before.trimmingCharacters(in: .whitespaces)
        let trimmedAfter = after.trimmingCharacters(in: .whitespaces)
        for word in currencyWords {
            if trimmedBefore.hasSuffix(word) || trimmedAfter.hasPrefix(word) { return true }
        }
        // Part of a phone number written in groups ("+98 912 123 4567"), or one that starts with 00.
        if let last = trimmedBefore.last, last.isNumber || last == "+" { return true }
        if let first = trimmedAfter.first, first.isNumber { return true }
        if digits.hasPrefix("00") && digits.count >= 8 { return true }
        // Percentages and units.
        if trimmedAfter.hasPrefix("%") || trimmedAfter.hasPrefix("mb") || trimmedAfter.hasPrefix("gb")
            || trimmedAfter.hasPrefix("min") || trimmedAfter.hasPrefix("دقیقه") || trimmedAfter.hasPrefix("دقيقة") {
            return true
        }
        return false
    }
}
