import XCTest
@testable import Pairline

final class OTPDetectorTests: XCTestCase {
    func testFindsCodes() {
        let cases: [(String, String)] = [
            ("Your verification code is 482913. It expires in 10 minutes.", "482913"),
            ("482913 is your login code", "482913"),
            ("G-551203 is your Google verification code.", "551203"),
            ("Use 7391 as your one-time password (OTP) for Acme.", "7391"),
            ("Your code: 123-456", "123456"),
            ("Your security code is 123 456. Don't share it.", "123456"),
            ("PIN: 90817", "90817"),
            ("Sign in to Contoso with code 30184952", "30184952"),
            ("Ihr Bestätigung Code lautet 662211", "662211"),
            ("Tu código de verificación es 551177", "551177"),
            ("Votre code de vérification est 908070", "908070"),
            ("Ваш код: 445566", "445566"),
            ("Doğrulama kodunuz 774411", "774411"),
            // Persian, with Persian and ASCII digits
            ("کد تایید شما: ۷۳۸۲۹۱", "738291"),
            ("رمز پویا: 556677\nبانک نمونه", "556677"),
            ("کد ورود شما به حساب: 551203", "551203"),
            // Arabic, with Arabic-Indic and ASCII digits
            ("رمز التحقق الخاص بك هو ٤٨٢٩١٣", "482913"),
            ("رمزك لمرة واحدة هو 739204. لا تشاركه.", "739204"),
            ("كود التفعيل 1234", "1234"),
        ]
        for (text, expected) in cases {
            XCTAssertEqual(OTPDetector.code(in: text), expected, text)
        }
    }

    func testPrefersTheCodeOverOtherNumbers() {
        XCTAssertEqual(OTPDetector.code(in: "Payment of $1250 to SHOP. Your confirmation code is 662914."), "662914")
        XCTAssertEqual(OTPDetector.code(in: "Your code is 4417. Call +1 800 555 0100 if this wasn't you."), "4417")
        XCTAssertEqual(OTPDetector.code(in: "On 2026-11-24 at 10:30 your login code is 902211"), "902211")
    }

    func testIgnoresMessagesWithoutCodes() {
        let texts = [
            "Are we still on for dinner at 7:30?",
            "Your package will arrive between 2 and 5 PM.",
            "Call me back at +98 912 123 4567",
            "Your balance is 125000 IRR.",
            "Meeting moved to room 4021",
            "Your bill of $1,250.00 is due on 12/03/2026",
            "Spinning class at 1830 tomorrow",
            "سلام، شام امشب ساعت ۸ خوبه؟",
            "مبلغ 250000 ریال از حساب شما برداشت شد",
            "",
        ]
        for text in texts {
            XCTAssertNil(OTPDetector.code(in: text), text)
        }
    }
}
