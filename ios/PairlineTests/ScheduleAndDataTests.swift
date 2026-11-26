@testable import Pairline
import UIKit
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

    /// D-088: a page of photos, and the requests that go on the Wi-Fi session only.
    func testMediaPages() throws {
        guard case .mediaList(let page) = try decode([
            "t": "media.list", "id": 2, "more": true, "cursor": "1764000000:42",
            "items": [["mid": 42, "time": 1, "w": 4000, "h": 3000, "size": 2_000_000, "thumb": "AAAA"], ["bad": 1]],
        ]).message else { return XCTFail() }
        XCTAssertEqual(page.items.map(\.mid), [42])
        XCTAssertEqual(page.cursor, "1764000000:42")
        XCTAssertTrue(page.more)
        XCTAssertEqual(Outbound.mediaList(cursor: nil, limit: 30).lane, .wifi)
        XCTAssertEqual(Outbound.mediaGet(mid: 42).lane, .wifi)
        XCTAssertTrue(try json(.mediaList(cursor: nil, limit: 30))["before"] is NSNull)
    }

    /// D-089: an MMS in history, its attachment request, and the send messages.
    func testMMSMessages() throws {
        guard case .smsHistory(let page) = try decode([
            "t": "sms.history", "id": 1, "more": false,
            "msgs": [["key": "mms:9", "thread": 4, "addr": "+905551111111", "body": "", "time": 1, "box": "in",
                      "parts": [["pid": 3, "type": "image/jpeg", "name": "a.jpg", "thumb": "AAAA"]],
                      "addrs": ["+905551111111", "+905552222222"]]],
        ]).message else { return XCTFail() }
        XCTAssertEqual(page.msgs.first?.parts?.first?.pid, 3)
        XCTAssertEqual(page.msgs.first?.parts?.first?.isImage, true)
        XCTAssertEqual(page.msgs.first?.addrs?.count, 2)
        XCTAssertEqual(Outbound.mmsPart(key: "mms:9", pid: 3).lane, .wifi)
        let attach = try json(.mmsAttach(aid: "a1", seq: 0, data: Data([1, 2, 3]), last: true, type: "image/jpeg", name: "x.jpg"))
        XCTAssertEqual(attach["data"] as? String, "AQID")
        let send = try json(.mmsSend(cid: "m-1", sim: nil, to: ["+1", "+2"], body: nil, aid: "a1"))
        XCTAssertEqual(send["to"] as? [String], ["+1", "+2"])
        XCTAssertTrue(send["body"] is NSNull)
    }

    /// D-089: attachments and participants are kept with the message, and a group thread lists everyone.
    @MainActor
    func testMMSStored() throws {
        let db = AppDatabase.inMemory()
        let part = MMSPart(pid: 3, type: "image/jpeg", name: "a.jpg", thumb: "AAAA")
        db.upsertMessages([SMSMessage(key: "mms:9", thread: 4, addr: "+905551111111", body: "", time: 1_000, box: "in",
                                      sim: nil, state: "received", parts: [part],
                                      addrs: ["+905551111111", "+905552222222"])], countUnread: true)
        let stored = try XCTUnwrap(db.messages(thread: 4).first)
        XCTAssertEqual(stored.parts, [part])
        XCTAssertEqual(stored.addrs.count, 2)
        XCTAssertEqual(db.conversation(thread: 4)?.addresses.count, 2)
        XCTAssertEqual(db.conversation(thread: 4)?.snippet, "📷", "a picture without text")
        let local = db.insertPending(thread: 4, to: "+905551111111", body: "hi", sim: nil, cid: "m-1",
                                     group: ["+905551111111", "+905552222222"])
        XCTAssertTrue(local.isLocalMMS)
    }

    /// D-089: a picture is made small enough for an MMS.
    func testPictureFitsAnMMS() throws {
        let big = UIGraphicsImageRenderer(size: CGSize(width: 3000, height: 2000)).image { context in
            for i in 0..<60 {
                UIColor(hue: CGFloat(i) / 60, saturation: 1, brightness: 1, alpha: 1).setFill()
                context.fill(CGRect(x: i * 50, y: 0, width: 50, height: 2000))
            }
        }
        let data = try XCTUnwrap(big.pngData())
        let small = try XCTUnwrap(PicturePicker.forMMS(data))
        XCTAssertLessThanOrEqual(small.count, PicturePicker.maxBytes)
        let image = try XCTUnwrap(UIImage(data: small))
        XCTAssertLessThanOrEqual(max(image.size.width, image.size.height), 1280)
    }

    /// D-087: the captions panel keeps the last ~240 characters, cut at a word.
    func testCaptionsTail() {
        XCTAssertEqual(LiveCaptions.tail("hello there"), "hello there")
        let long = String(repeating: "word ", count: 100)
        let tail = LiveCaptions.tail(long)
        XCTAssertLessThanOrEqual(tail.count, 240)
        XCTAssertTrue(tail.hasPrefix("word"), "starts at a whole word")
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
