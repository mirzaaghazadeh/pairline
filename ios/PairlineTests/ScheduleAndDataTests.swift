@testable import Pairline
import XCTest

/// D-084: scheduled messages, the data meter and ringing this iPhone.
final class ScheduleAndDataTests: XCTestCase {
    private func decode(_ object: [String: Any]) throws -> Inbound {
        try Inbound.decode(JSONSerialization.data(withJSONObject: object))
    }

    private func json(_ message: Outbound) throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: message.json(id: 1)) as? [String: Any] ?? [:]
    }

    func testScheduledListDecodes() throws {
        guard case .smsScheduled(let list) = try decode([
            "t": "sms.scheduled", "id": 3, "exact": false,
            "items": [["cid": "s-1", "sim": 0, "to": "+905551234567", "body": "Happy birthday", "at": 1_764_000_000_000],
                      ["cid": "broken"]],
            "done": [["cid": "s-0", "state": "sent", "key": "sms:9", "error": NSNull()]],
        ]).message else { return XCTFail() }
        XCTAssertEqual(list.items.map(\.cid), ["s-1"], "a broken item is skipped")
        XCTAssertEqual(list.done.first?.key, "sms:9")
        XCTAssertFalse(list.exact)
        guard case .smsScheduled(let bare) = try decode(["t": "sms.scheduled", "id": 4]).message else { return XCTFail() }
        XCTAssertTrue(bare.items.isEmpty && bare.done.isEmpty && bare.exact)
    }

    func testOutboundShapes() throws {
        let schedule = try json(.smsSchedule(cid: "s-1", sim: nil, to: "+1555", body: "hi", at: 42))
        XCTAssertEqual(schedule["t"] as? String, "sms.schedule")
        XCTAssertEqual(schedule["at"] as? Int, 42)
        XCTAssertTrue(schedule["sim"] is NSNull)
        XCTAssertEqual(try json(.smsUnschedule(cid: "s-1"))["cid"] as? String, "s-1")
        XCTAssertEqual(try json(.dataUsage(since: 7))["since"] as? Int, 7)
        XCTAssertTrue(Outbound.remoteCaps.contains("ring"))
    }

    func testDataUsageAndRing() throws {
        guard case .dataUsage(let usage) = try decode(["t": "data.usage", "id": 1, "since": 1, "until": 2,
                                                        "bytes": 3_400_000_000]).message else { return XCTFail() }
        XCTAssertEqual(usage.bytes, 3_400_000_000)
        guard case .remoteRing(let on) = try decode(["t": "remote.ring", "on": false]).message else { return XCTFail() }
        XCTAssertFalse(on)
        guard case .remoteRing(let defaultOn) = try decode(["t": "remote.ring"]).message else { return XCTFail() }
        XCTAssertTrue(defaultOn)
    }

    func testDataMonthStart() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        func date(_ y: Int, _ m: Int, _ d: Int) -> Date {
            calendar.date(from: DateComponents(year: y, month: m, day: d))!
        }
        XCTAssertEqual(BridgeController.dataCycleStart(day: 1, now: date(2026, 11, 26), calendar: calendar), date(2026, 11, 1))
        XCTAssertEqual(BridgeController.dataCycleStart(day: 27, now: date(2026, 11, 26), calendar: calendar), date(2026, 10, 27))
        XCTAssertEqual(BridgeController.dataCycleStart(day: 15, now: date(2026, 1, 3), calendar: calendar), date(2025, 12, 15))
        XCTAssertEqual(BridgeController.dataCycleStart(day: 31, now: date(2026, 3, 30), calendar: calendar), date(2026, 3, 28),
                       "days after the 28th count as the 28th")
    }
}
