import XCTest
@testable import Pairline

/// Trial clock (D-017), redaction (rule 9), SMS segments, phone numbers and versions.
final class TrialPolicyTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_764_000_000)

    func testStates() {
        XCTAssertEqual(TrialPolicy.state(purchased: false, trialStart: nil, now: start), .notStarted)
        XCTAssertEqual(TrialPolicy.state(purchased: false, trialStart: start, now: start.addingTimeInterval(3600)),
                       .trial(endsAt: start.addingTimeInterval(TrialPolicy.duration)))
        XCTAssertEqual(TrialPolicy.state(purchased: false, trialStart: start, now: start.addingTimeInterval(TrialPolicy.duration)),
                       .expired(endedAt: start.addingTimeInterval(TrialPolicy.duration)))
        XCTAssertEqual(TrialPolicy.state(purchased: true, trialStart: start, now: start.addingTimeInterval(99 * 86400)),
                       .purchased)
        XCTAssertEqual(TrialPolicy.state(purchased: true, trialStart: nil, now: start), .purchased)
    }

    /// D-069: before a trial, the link runs for one free first sync only; a trial or purchase always opens it, and an
    /// ended trial never does.
    func testFirstSyncPolicy() {
        let trial = AccessState.trial(endsAt: start)
        let expired = AccessState.expired(endedAt: start)
        XCTAssertTrue(FirstSyncPolicy.linkAllowed(access: .notStarted, firstSyncUsed: false))
        XCTAssertTrue(FirstSyncPolicy.firstSyncOnly(access: .notStarted, firstSyncUsed: false))
        XCTAssertFalse(FirstSyncPolicy.linkAllowed(access: .notStarted, firstSyncUsed: true))
        for used in [false, true] {
            XCTAssertTrue(FirstSyncPolicy.linkAllowed(access: trial, firstSyncUsed: used))
            XCTAssertTrue(FirstSyncPolicy.linkAllowed(access: .purchased, firstSyncUsed: used))
            XCTAssertFalse(FirstSyncPolicy.firstSyncOnly(access: trial, firstSyncUsed: used))
            XCTAssertFalse(FirstSyncPolicy.firstSyncOnly(access: .purchased, firstSyncUsed: used))
            XCTAssertFalse(FirstSyncPolicy.linkAllowed(access: expired, firstSyncUsed: used))
            XCTAssertFalse(FirstSyncPolicy.linkAllowed(access: .loading, firstSyncUsed: used))
        }
    }

    func testAccessGates() {
        XCTAssertTrue(AccessState.purchased.allowsLink)
        XCTAssertTrue(AccessState.trial(endsAt: start).allowsLink)
        XCTAssertFalse(AccessState.expired(endedAt: start).allowsLink)
        XCTAssertFalse(AccessState.notStarted.allowsLink)
        XCTAssertFalse(AccessState.loading.allowsLink)
    }

    func testTurningTheClockBackDoesNotExtendTheTrial() {
        let store = MemoryTimeStore()
        // Day 8: the trial has ended, and the app saw that time.
        let day8 = start.addingTimeInterval(8 * 86400)
        let seen = TrialPolicy.trustedNow(deviceNow: day8, highWater: store.highWater, signedDates: [start])
        store.save(seen)
        // The user sets the clock back to day 1.
        let rewound = start.addingTimeInterval(86400)
        let now = TrialPolicy.trustedNow(deviceNow: rewound, highWater: store.highWater, signedDates: [start])
        XCTAssertEqual(now, day8)
        XCTAssertEqual(TrialPolicy.state(purchased: false, trialStart: start, now: now),
                       .expired(endedAt: start.addingTimeInterval(TrialPolicy.duration)))
    }

    func testAppleSignedDateBeatsAnEarlyDeviceClock() {
        let signed = start.addingTimeInterval(5 * 86400)
        let now = TrialPolicy.trustedNow(deviceNow: start, highWater: nil, signedDates: [start, signed])
        XCTAssertEqual(now, signed)
    }

    func testHighWaterNeverDecreases() {
        let store = MemoryTimeStore()
        store.save(start.addingTimeInterval(100))
        store.save(start)
        XCTAssertEqual(store.highWater, start.addingTimeInterval(100))
    }

    func testRemainingAndReminder() {
        let end = start.addingTimeInterval(TrialPolicy.duration)
        XCTAssertEqual(TrialPolicy.remaining(until: end, now: start).days, 7)
        let left = TrialPolicy.remaining(until: end, now: end.addingTimeInterval(-(26 * 3600 + 60)))
        XCTAssertEqual(left.days, 1)
        XCTAssertEqual(left.hours, 3)
        XCTAssertEqual(TrialPolicy.remaining(until: end, now: end.addingTimeInterval(10)).hours, 0)
        XCTAssertEqual(TrialPolicy.reminderDate(trialEnd: end), end.addingTimeInterval(-86400))
    }
}

final class RedactorTests: XCTestCase {
    func testRemovesPersonalData() {
        let text = "Sent to +98 912 123 4567 and 0912-123-4567; mail ada@example.com; MAC A1:B2:C3:D4:E5:F6; " +
            "peripheral 8CC09D09-4084-4E91-A358-A153EB79D422; code ۴۸۲۹۱۳"
        let out = Redactor.redact(text)
        for leaked in ["912", "4567", "ada@", "A1:B2", "8CC09D09", "۴۸۲"] {
            XCTAssertFalse(out.contains(leaked), "\(leaked) leaked: \(out)")
        }
        XCTAssertTrue(out.contains("[number]"))
        XCTAssertTrue(out.contains("[email]"))
        XCTAssertTrue(out.contains("[mac]"))
        XCTAssertTrue(out.contains("[id]"))
    }

    func testReportKeepsTimeStampsAndErrorCodes() {
        let report = "2026-11-24T18:27:55Z WARN [calls] CallKit transaction failed: (com.apple.CallKit.error.requesttransaction error 1.)\n" +
            "2026-11-24T18:28:03Z INFO [calls] Call ended: local\n" +
            "2026-11-24T18:28:04Z INFO [sms] To +98 912 123 4567"
        let out = Redactor.redactLines(report)
        XCTAssertTrue(out.contains("requesttransaction error 1.)\n2026-11-24T18:28:03Z INFO [calls] Call ended"), out)
        XCTAssertTrue(out.hasPrefix("2026-11-24T18:27:55Z WARN"), out)
        XCTAssertFalse(out.contains("4567"), out)
    }

    func testKeepsUsefulText() {
        let line = "2026-11-24T01:00:00Z INFO [link] Session established over ble (v0, 3 caps)"
        XCTAssertEqual(Redactor.redact("Session established over ble (v0, 3 caps)"), "Session established over ble (v0, 3 caps)")
        XCTAssertTrue(Redactor.redact(line).contains("[link] Session established"))
    }

    func testLogWritesRedactedLinesAndClears() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let log = AppLog(directory: dir)
        log.info("test", "Called +1 415 555 0182")
        let text = log.text()
        XCTAssertTrue(text.contains("[test] Called [number]"), text)
        XCTAssertFalse(text.contains("555"))
        log.clear()
        XCTAssertEqual(log.text(), "")
    }
}

final class TextHelpersTests: XCTestCase {
    func testSMSSegments() {
        XCTAssertEqual(SMSSegments.info(for: "").parts, 0)
        XCTAssertEqual(SMSSegments.info(for: String(repeating: "a", count: 160)), .init(parts: 1, remaining: 0, isUnicode: false))
        XCTAssertEqual(SMSSegments.info(for: String(repeating: "a", count: 161)).parts, 2)
        XCTAssertEqual(SMSSegments.info(for: String(repeating: "{", count: 80)).parts, 1, "extended chars count twice")
        XCTAssertEqual(SMSSegments.info(for: String(repeating: "س", count: 70)), .init(parts: 1, remaining: 0, isUnicode: true))
        XCTAssertEqual(SMSSegments.info(for: String(repeating: "س", count: 71)).parts, 2)
        XCTAssertTrue(SMSSegments.info(for: "hi 👋").isUnicode)
    }

    func testPhoneNumbers() {
        XCTAssertEqual(PhoneNumber.asciiDigits("۰۹۱۲ ٤٥٦"), "0912 456")
        XCTAssertEqual(PhoneNumber.matchKey("+98 912 123 4567"), PhoneNumber.matchKey("09121234567"))
        XCTAssertNotEqual(PhoneNumber.matchKey("+98 912 123 4567"), PhoneNumber.matchKey("+98 912 123 4568"))
        XCTAssertEqual(PhoneNumber.matchKey("BANK"), PhoneNumber.matchKey("bank"))
        XCTAssertTrue(PhoneNumber.isUSSD("*140#"))
        XCTAssertTrue(PhoneNumber.isUSSD("*#06#"))
        XCTAssertFalse(PhoneNumber.isUSSD("+15551234"))
        XCTAssertEqual(PhoneNumber.dialable("+1 (415) 555-0182"), "+14155550182")
        // D-067: the international prefix "00" is shown as "+"; anything else stays as it is.
        XCTAssertEqual(PhoneNumber.international("00989121234567"), "+989121234567")
        XCTAssertEqual(PhoneNumber.international("09121234567"), "09121234567")
        XCTAssertEqual(PhoneNumber.international("+989121234567"), "+989121234567")
        XCTAssertEqual(PhoneNumber.international("00123"), "00123")
        XCTAssertEqual(PhoneNumber.international("0001234567"), "0001234567")
        XCTAssertEqual(PhoneNumber.international("*140#"), "*140#")
        XCTAssertEqual(PhoneNumber.matchKey(PhoneNumber.international("00989121234567")), PhoneNumber.matchKey("09121234567"))
        XCTAssertTrue(PhoneNumber.isPhoneNumber("+1 415 555 0182"))
        XCTAssertFalse(PhoneNumber.isPhoneNumber("NWBANK"))
        XCTAssertLessThan(PhoneNumber.syntheticThreadID(for: "+1 415 555 0182"), 0)
        XCTAssertEqual(PhoneNumber.syntheticThreadID(for: "+1 415 555 0182"), PhoneNumber.syntheticThreadID(for: "4155550182"))
        XCTAssertEqual(PhoneNumber.ltr("+1"), "\u{2066}+1\u{2069}")
    }

    /// The same cases as the bridge's TelephonyLogicTest, so both phones agree on what a block covers (D-043).
    func testBlockMatching() {
        let cases: [(String, String, Bool)] = [
            ("+98 912 123 4567", "09121234567", true),
            ("+1 555 1234", "5551234", true),
            ("+354 555 1234", "555 1234", true),
            ("+1 (555) 010-0200", "+15550100201", false),
            ("5551234", "5551235", false),
            ("12345", "12345", true),
            ("12345", "912345", false),
            ("BANK", "bank", true),
            ("BANK", "BANKX", false),
            ("۰۹۱۲۱۲۳۴۵۶۷", "+989121234567", true),
        ]
        for (blocked, number, expected) in cases {
            XCTAssertEqual(PhoneNumber.blockMatches(blocked, number), expected, "\(blocked) vs \(number)")
        }
    }

    func testVersionsAndDirection() {
        XCTAssertLessThan(Version("0.1.9"), Version("0.1.10"))
        XCTAssertLessThan(Version("0.9"), Version("1.0.0"))
        XCTAssertEqual(Version("1.0"), Version("1.0.0"))
        XCTAssertFalse(Version("0.2.0-debug") < Version("0.1.0"))
        XCTAssertTrue(TextDirection.isRightToLeft("سلام world"))
        XCTAssertTrue(TextDirection.isRightToLeft("123 مرحبا"))
        XCTAssertFalse(TextDirection.isRightToLeft("Hello سلام"))
    }

    func testLinkDetection() {
        let attributed = LinkDetector.attributed("Track: https://example.com/t/8K2Q today")
        XCTAssertTrue(attributed.runs.contains { $0.link?.host == "example.com" })
    }
}
