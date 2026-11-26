@testable import Pairline
import XCTest

/// D-083: the inbox's on-device sorting.
final class MessageCategoryTests: XCTestCase {
    private func category(_ address: String, _ text: String, contact: Bool = false) -> MessageCategory {
        MessageCategory.of(address: address, text: text, isContact: contact)
    }

    func testContactsAreAlwaysPeople() {
        XCTAssertEqual(category("+989121234567", "Your code is 482193", contact: true), .people)
        XCTAssertEqual(category("+905551234567", "50% off today only", contact: true), .people)
    }

    func testCodesComeFirst() {
        XCTAssertEqual(category("BANK", "Your verification code is 482193. Don't share it."), .codes)
        XCTAssertEqual(category("+98200041234", "کد تایید شما: ۵۸۲۹۱۳"), .codes)
    }

    func testTransactions() {
        XCTAssertEqual(category("MELLAT", "برداشت از حساب 1234: 500,000 ریال. مانده: 2,000,000"), .transactions)
        XCTAssertEqual(category("GARANTI", "Kartınızdan 250 TL harcama yapıldı."), .transactions)
        XCTAssertEqual(category("ALRAJHI", "تمت عملية شراء ببطاقة مدى"), .transactions)
    }

    func testPromotions() {
        XCTAssertEqual(category("30001234", "جشنواره تخفیف ویژه! لغو11"), .promotions)
        XCTAssertEqual(category("SHOP", "Big sale: 30% off everything, reply STOP to opt out"), .promotions)
        XCTAssertEqual(category("TURKCELL", "Size özel kampanya: 10 GB hediye!"), .promotions)
    }

    func testSenders() {
        XCTAssertEqual(category("+905551234567", "See you at six"), .people)
        XCTAssertEqual(category("Irancell", "Your package is now active."), .services)
        XCTAssertEqual(category("30001234567", "Your parcel arrives tomorrow."), .services, "an Iranian bulk sender")
        XCTAssertEqual(category("1234", "Welcome"), .services, "a short code")
    }
}
