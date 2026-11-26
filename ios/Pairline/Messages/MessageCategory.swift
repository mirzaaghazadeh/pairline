import SwiftUI

/// The inbox's filters (D-083): conversations sorted on this iPhone from the sender and the latest message's text.
/// Nothing leaves the iPhone and nothing is stored; the sorting runs again whenever the list does.
enum MessageCategory: String, CaseIterable, Identifiable {
    case all
    /// Contacts, and unknown senders whose message isn't a code, a transaction or an offer.
    case people
    /// One-time codes.
    case codes
    /// Banks, cards and payments.
    case transactions
    /// Offers and advertising.
    case promotions
    /// Everything else from a short code or a named sender: carriers, services, deliveries.
    case services

    var id: String { rawValue }

    var title: Text {
        switch self {
        case .all: return Text("All", tableName: "Inbox")
        case .people: return Text("People", tableName: "Inbox")
        case .codes: return Text("Codes", tableName: "Inbox")
        case .transactions: return Text("Transactions", tableName: "Inbox")
        case .promotions: return Text("Offers", tableName: "Inbox")
        case .services: return Text("Services", tableName: "Inbox")
        }
    }

    var symbol: String {
        switch self {
        case .all: return "tray.full"
        case .people: return "person.2"
        case .codes: return "key"
        case .transactions: return "creditcard"
        case .promotions: return "tag"
        case .services: return "building.2"
        }
    }

    /// The category of a conversation: a sender in the bridge's contacts is always a person; otherwise the latest
    /// message decides (a code, a transaction, an offer), then the sender (a full phone number is a person).
    static func of(address: String, text: String, isContact: Bool) -> MessageCategory {
        if isContact { return .people }
        let lower = PhoneNumber.asciiDigits(text).lowercased()
        if OTPDetector.code(in: text) != nil { return .codes }
        if transactionWords.contains(where: lower.contains) { return .transactions }
        if promotionWords.contains(where: lower.contains) { return .promotions }
        if PhoneNumber.digits(address).count >= 7, !address.contains(where: \.isLetter), !isServiceNumber(address) {
            return .people
        }
        return .services
    }

    /// Bulk senders in Iran use service numbers such as 3000…, 1000…, 2000…, 5000… and 9820004…: not people.
    private static func isServiceNumber(_ address: String) -> Bool {
        var digits = PhoneNumber.digits(address)
        if digits.hasPrefix("98") { digits.removeFirst(2) }
        return ["1000", "2000", "3000", "5000", "20004", "210000"].contains(where: digits.hasPrefix)
    }

    // Lowercased; matched anywhere in the text. Specific words only, so a friend's message rarely lands here.
    private static let transactionWords: [String] = [
        // English
        "bank", "debit", "credit card", "withdraw", "deposit", "transaction", "purchase", "payment", "account",
        "a/c", "transfer", "balance:", "avail bal",
        // Persian
        "بانک", "حساب", "برداشت", "واریز", "مانده", "تراکنش", "خرید", "کارت",
        // Arabic
        "بنك", "مصرف", "حساب", "سحب", "إيداع", "ايداع", "بطاقة", "عملية شراء", "تحويل", "الرصيد المتاح",
        // Turkish
        "banka", "hesab", "hesap", "kartınız", "kredi kart", "bakiye", "ödeme", "harcama", "havale", "eft ",
    ]

    private static let promotionWords: [String] = [
        // English
        "% off", "discount", "offer", "promo", "coupon", "unsubscribe", "opt out", "opt-out", "reply stop", "stop to",
        // Persian
        "تخفیف", "جشنواره", "هدیه", "رایگان", "لغو11", "لغو 11", "لغو ۱۱", "قرعه", "جایزه", "ویژه",
        // Arabic
        "خصم", "تخفيض", "عرض خاص", "عروض", "مجانا", "مجاناً", "هدية", "اربح", "لإلغاء الاشتراك", "الغاء الاشتراك",
        // Turkish
        "indirim", "kampanya", "fırsat", "hediye", "ücretsiz", "çekiliş", "kazan", "iptal için", "ret yaz",
    ]
}
