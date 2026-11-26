import XCTest
@testable import Pairline

/// JSON message catalog: decoding every inbound type, tolerance, and the shape of outbound requests.
final class MessageCodecTests: XCTestCase {
    private func decode(_ object: [String: Any]) throws -> Inbound {
        try Inbound.decode(JSONSerialization.data(withJSONObject: object))
    }

    private func json(_ outbound: Outbound, id: Int? = 5) throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: outbound.json(id: id)) as! [String: Any]
    }

    func testStatusWithSimsAndCaps() throws {
        let inbound = try decode([
            "t": "status", "id": 3, "app": "0.2.0", "time": 1_764_000_000_000, "caps": ["sms.send", "ussd", 7],
            "battery": ["level": 41, "charging": true],
            "sims": [["slot": 0, "carrier": "A", "label": "SIM 1", "number": NSNull(), "signal": 3, "net": "LTE",
                      "roaming": false, "defaultSms": true, "defaultVoice": false],
                     ["carrier": "broken: no slot"]],
            "unknownField": ["nested": true],
        ])
        XCTAssertEqual(inbound.id, 3)
        guard case .status(let status) = inbound.message else { return XCTFail() }
        XCTAssertEqual(status.battery, BatteryInfo(level: 41, charging: true))
        XCTAssertEqual(status.sims?.count, 1, "a SIM without a slot is skipped, not fatal")
        XCTAssertNil(status.sims?.first?.number)
        XCTAssertEqual(status.caps, ["sms.send", "ussd"], "non-string caps are skipped")
    }

    /// `status.net`, `sims[].defaultData` and `net.switch` (D-073).
    func testNetSwitches() throws {
        let inbound = try decode([
            "t": "status", "caps": ["net.switch"],
            "sims": [["slot": 0, "defaultData": false], ["slot": 1, "defaultData": true]],
            "net": ["hotspot": true, "bluetooth": false, "data": NSNull(), "future": 1],
        ])
        guard case .status(let status) = inbound.message else { return XCTFail() }
        XCTAssertEqual(status.net, BridgeNetState(hotspot: true, bluetooth: false, data: nil))
        XCTAssertEqual(status.net?.value(.hotspot), true)
        XCTAssertNil(status.net?.value(.data), "unknown mobile data stays unknown")
        XCTAssertEqual(status.sims?.map(\.defaultData), [false, true])

        guard case .status(let old) = try decode(["t": "status", "caps": []]).message else { return XCTFail() }
        XCTAssertNil(old.net, "an older bridge sends no net: the switches stay hidden")

        let flip = try json(.netSwitch(.bluetooth, on: true))
        XCTAssertEqual(flip["t"] as? String, "net.switch")
        XCTAssertEqual(flip["what"] as? String, "bluetooth")
        XCTAssertEqual(flip["on"] as? Bool, true)
        XCTAssertEqual(flip["id"] as? Int, 5)
        let sim = try json(.netDataSIM(slot: 1))
        XCTAssertEqual(sim["what"] as? String, "dataSim")
        XCTAssertEqual(sim["sim"] as? Int, 1)
        XCTAssertNil(sim["on"])
        XCTAssertEqual(Set(NetSwitch.allCases.map(\.rawValue)), ["hotspot", "bluetooth", "data"])
    }

    /// D-074: read sync, hold, contact edits, Find phone, the ringer, and numbers from outside Pairline.
    func testPhoneToolsMessages() throws {
        guard case .status(let status) = try decode(["t": "status", "caps": ["phone.ringer"], "ringer": "vibrate"]).message
        else { return XCTFail() }
        XCTAssertEqual(status.ringer, "vibrate")

        let read = try json(.smsRead(thread: 17))
        XCTAssertEqual(read["t"] as? String, "sms.read")
        XCTAssertEqual(read["thread"] as? Int, 17)

        let hold = try json(.callHold(.string("c1"), hold: true))
        XCTAssertEqual(hold["t"] as? String, "call.hold")
        XCTAssertEqual(hold["call"] as? String, "c1")
        XCTAssertEqual(hold["hold"] as? Bool, true)

        let star = try json(.contactsUpdate(key: "c:42", name: nil, numbers: nil, starred: true))
        XCTAssertEqual(star["key"] as? String, "c:42")
        XCTAssertEqual(star["starred"] as? Bool, true)
        XCTAssertNil(star["name"], "unchanged parts aren't sent")
        XCTAssertNil(star["numbers"])
        let edit = try json(.contactsUpdate(key: "c:42", name: "Ada", numbers: [ContactNumber(n: "+15551234", label: nil)],
                                            starred: nil))
        XCTAssertEqual(edit["name"] as? String, "Ada")
        XCTAssertEqual((edit["numbers"] as? [[String: Any]])?.first?["n"] as? String, "+15551234")

        XCTAssertEqual(try json(.phoneFind(on: false))["on"] as? Bool, false)
        XCTAssertEqual(try json(.phoneRinger("silent"))["mode"] as? String, "silent")

        XCTAssertEqual(PhoneNumber.firstNumber(in: "+98 912 123 4567"), "+989121234567")
        XCTAssertEqual(PhoneNumber.firstNumber(in: "۰۰۹۸۹۱۲۱۲۳۴۵۶۷"), "+989121234567")
        XCTAssertEqual(PhoneNumber.firstNumber(in: "Call me at (555) 123-4567 tonight"), "5551234567")
        XCTAssertNil(PhoneNumber.firstNumber(in: "no number here"))
    }

    /// D-077: notifications from Android apps, their removal, and dismissing one from here.
    func testMirroredNotifications() throws {
        guard case .notifPosted(let n) = try decode([
            "t": "notif.posted", "key": "0|com.whatsapp|1|null|10123", "app": "com.whatsapp", "appName": "WhatsApp",
            "title": "Ada", "text": "See you at 6", "time": 1_764_000_000_000,
        ]).message else { return XCTFail() }
        XCTAssertEqual(n.appName, "WhatsApp")
        XCTAssertEqual(n.text, "See you at 6")
        guard case .notifPosted(let bare) = try decode(["t": "notif.posted", "key": "k", "app": "a.b"]).message
        else { return XCTFail() }
        XCTAssertNil(bare.title, "title and text may be missing")
        guard case .notifRemoved(let key) = try decode(["t": "notif.removed", "key": "k"]).message else { return XCTFail() }
        XCTAssertEqual(key, "k")
        let dismiss = try json(.notifDismiss(key: "k"))
        XCTAssertEqual(dismiss["t"] as? String, "notif.dismiss")
        XCTAssertEqual(dismiss["key"] as? String, "k")
    }

    /// D-082: signal detail and the voicemail indicator per SIM, and dialing voicemail.
    func testCarrierTools() throws {
        guard case .status(let s) = try decode([
            "t": "status",
            "sims": [["slot": 0, "carrier": "Irancell", "signal": 3, "net": "LTE", "rat": "LTE-CA", "dbm": -97,
                      "op": "Turkcell", "vm": true],
                     ["slot": 1, "signal": 2, "net": "3G"]],
        ]).message else { return XCTFail() }
        XCTAssertEqual(s.sims?.first?.rat, "LTE-CA")
        XCTAssertEqual(s.sims?.first?.dbm, -97)
        XCTAssertEqual(s.sims?.first?.op, "Turkcell")
        XCTAssertEqual(s.sims?.first?.vm, true)
        XCTAssertNil(s.sims?.last?.vm, "older bridges leave it out")
        let vm = try json(.callVoicemail(sim: 1))
        XCTAssertEqual(vm["t"] as? String, "call.dial")
        XCTAssertEqual(vm["voicemail"] as? Bool, true)
        XCTAssertEqual(vm["sim"] as? Int, 1)
        XCTAssertNil(vm["number"])
        XCTAssertTrue(Capability.allCases.contains(.callVoicemail))
    }

    func testSMSMessagesAndHistoryPage() throws {
        let inbound = try decode([
            "t": "sms.history", "id": 9, "more": true,
            "msgs": [["key": "sms:1", "thread": 17, "addr": "+15551234567", "body": "hi", "time": 1, "box": "in",
                      "sim": 0, "state": "received"],
                     ["key": "rx:2", "thread": NSNull(), "addr": "BANK", "body": "x", "time": 2, "box": "in"],
                     ["key": "broken"]],
        ])
        guard case .smsHistory(let page) = inbound.message else { return XCTFail() }
        XCTAssertTrue(page.more)
        XCTAssertEqual(page.msgs.map(\.key), ["sms:1", "rx:2"])
        XCTAssertNil(page.msgs[1].thread)
    }

    func testCallEventsWithStringAndNumberIds() throws {
        let incoming = try decode(["t": "call.incoming", "call": "c7uekrq-3", "sim": 1, "from": "+1555", "name": "Ada"])
        guard case .callIncoming(let call) = incoming.message else { return XCTFail() }
        XCTAssertEqual(call.call, .string("c7uekrq-3"))
        XCTAssertEqual(try json(.callAnswer(call.call))["call"] as? String, "c7uekrq-3")

        let state = try decode(["t": "call.state", "call": 12, "state": "ended", "dir": "out", "reason": "busy"])
        guard case .callState(let event) = state.message else { return XCTFail() }
        XCTAssertEqual(event.call, .int(12))
        XCTAssertEqual(try json(.callEnd(event.call))["call"] as? Int, 12)
    }

    func testSyncRepliesWithCursorsAndFullFlag() throws {
        let contacts = try decode(["t": "contacts.sync", "id": 1, "cursor": "c.ab.12", "more": false, "full": true,
                                   "deleted": ["c:4"],
                                   "contacts": [["key": "c:1", "name": "Ada", "starred": true,
                                                 "numbers": [["n": "+1555", "label": "mobile"]], "emails": ["a@b.c"]]]])
        guard case .contactsSync(let page) = contacts.message else { return XCTFail() }
        XCTAssertTrue(page.full)
        XCTAssertEqual(page.cursor, .string("c.ab.12"))
        XCTAssertEqual(page.deleted, ["c:4"])
        XCTAssertEqual(page.contacts.first?.numbers?.first, ContactNumber(n: "+1555", label: "mobile"))
        XCTAssertEqual(try json(.contactsSync(since: page.cursor))["since"] as? String, "c.ab.12")
        XCTAssertTrue(try json(.contactsSync(since: nil))["since"] is NSNull)

        let calls = try decode(["t": "calllog.sync", "id": 2, "cursor": 99, "more": true,
                                "calls": [["key": "cl:1", "number": "+1", "name": NSNull(), "type": "missed", "time": 5,
                                           "dur": 0, "sim": 1]]])
        guard case .calllogSync(let log) = calls.message else { return XCTFail() }
        XCTAssertFalse(log.full)
        XCTAssertEqual(log.cursor, .int(99))
        XCTAssertEqual(log.calls.first?.type, "missed")
    }

    func testOpaqueValueStorageRoundTrip() {
        for value: OpaqueValue in [.int(42), .string("c.x.1"), .string(""), .double(1.5)] {
            XCTAssertEqual(OpaqueValue(storageString: value.storageString), value)
        }
        XCTAssertNil(OpaqueValue(storageString: "zz"))
    }

    func testSmallMessages() throws {
        if case .ok = try decode(["t": "ok", "id": 1]).message {} else { XCTFail() }
        if case .unpair = try decode(["t": "unpair"]).message {} else { XCTFail() }
        if case .ping = try decode(["t": "ping", "id": 2]).message {} else { XCTFail() }
        guard case .error(let error) = try decode(["t": "error", "id": 1, "code": "busy"]).message else { return XCTFail() }
        XCTAssertEqual(error.code, "busy")
        guard case .ussdResult(let ussd) = try decode(["t": "ussd.result", "id": 1, "ok": false, "error": "timeout"]).message
        else { return XCTFail() }
        XCTAssertFalse(ussd.ok)
        guard case .smsState(let state) = try decode(["t": "sms.state", "cid": "c-1", "state": "delivered", "key": NSNull()])
            .message else { return XCTFail() }
        XCTAssertNil(state.key)
        guard case .deviceInfo(let info) = try decode(["t": "device.info", "name": "Google Pixel 7", "platform": "android",
                                                       "os": "15", "app": "0.1.0", "proto": 0]).message
        else { return XCTFail() }
        XCTAssertEqual(info.proto, 0)
    }

    func testUnknownTypesAreIgnoredButBadJSONThrows() throws {
        let unknown = try decode(["t": "future.feature", "ssid": "x"])
        XCTAssertEqual(unknown.type, "future.feature")
        if case .unknown = unknown.message {} else { XCTFail() }
        XCTAssertThrowsError(try Inbound.decode(Data("not json".utf8)))
        XCTAssertThrowsError(try Inbound.decode(Data("{\"id\":1}".utf8)))
        XCTAssertThrowsError(try decode(["t": "sms.new", "msg": ["key": 1]]))
    }

    func testBlockListAndCallLogDelete() throws {
        let reply = try decode(["t": "block.list", "id": 4,
                                "numbers": [["n": "+15551234", "time": 1_764_000_000_000], ["time": 1], "bad"]])
        XCTAssertEqual(reply.id, 4)
        guard case .blockList(let list) = reply.message else { return XCTFail() }
        XCTAssertEqual(list, [BlockedNumber(n: "+15551234", time: 1_764_000_000_000)], "bad entries are skipped")
        let event = try decode(["t": "block.list", "numbers": []])
        XCTAssertNil(event.id, "the bridge's own changes arrive as an event")
        guard case .blockList(let empty) = event.message else { return XCTFail() }
        XCTAssertTrue(empty.isEmpty)

        let set = try json(.blockSet(add: ["+15551234"], remove: []))
        XCTAssertEqual(set["t"] as? String, "block.set")
        XCTAssertEqual(set["add"] as? [String], ["+15551234"])
        XCTAssertEqual(set["remove"] as? [String], [])
        XCTAssertEqual(try json(.blockList)["t"] as? String, "block.list")
        let delete = try json(.calllogDelete(keys: ["cl:1", "cl:2"]))
        XCTAssertEqual(delete["t"] as? String, "calllog.delete")
        XCTAssertEqual(delete["keys"] as? [String], ["cl:1", "cl:2"])
        XCTAssertNil(delete["all"])
        XCTAssertEqual(try json(.calllogDeleteAll)["all"] as? Bool, true)
        XCTAssertEqual(Capability(rawValue: "calllog.delete"), .calllogDelete)
        XCTAssertEqual(Capability(rawValue: "block"), .block)
    }

    func testOutboundShapes() throws {
        let send = try json(.smsSend(sim: nil, to: "+989121234567", body: "سلام 👋", cid: "c-1"), id: 7)
        XCTAssertEqual(send["t"] as? String, "sms.send")
        XCTAssertEqual(send["id"] as? Int, 7)
        XCTAssertTrue(send["sim"] is NSNull, "default SIM is an explicit null")
        XCTAssertEqual(send["body"] as? String, "سلام 👋")

        let history = try json(.smsHistory(thread: 4, after: 100, limit: 500))
        XCTAssertEqual(history["after"] as? Int, 100)
        XCTAssertNil(history["before"])
        XCTAssertEqual(history["limit"] as? Int, 100, "limit is capped at 100")
        let newest = try json(.smsHistory(thread: nil, limit: 50))
        XCTAssertTrue(newest["thread"] is NSNull)
        XCTAssertTrue(newest["before"] is NSNull)

        let audio = try json(.callAudio(.string("c1"), mute: true))
        XCTAssertEqual(audio["mute"] as? Bool, true)
        XCTAssertNil(audio["speaker"])

        let add = try json(.contactsAdd(name: "Ada", numbers: [ContactNumber(n: "+1", label: nil)]))
        let numbers = add["numbers"] as? [[String: Any]]
        XCTAssertEqual(numbers?.first?["n"] as? String, "+1")
        XCTAssertTrue(numbers?.first?["label"] is NSNull)

        let info = try json(.deviceInfo(name: "iPhone", os: "17.2", app: "0.1.0"), id: nil)
        XCTAssertNil(info["id"], "events carry no id")
        XCTAssertEqual(info["platform"] as? String, "ios")
        XCTAssertEqual(info["proto"] as? Int, 0)

        XCTAssertEqual(Outbound.ussdRun(sim: 0, code: "*140#").timeout, 60)
        XCTAssertEqual(Outbound.statusGet.timeout, 15)
        XCTAssertFalse(Outbound.unpair.isRequest)
    }

    func testBase64URLAndHex() {
        let data = Data([0xfb, 0xff, 0x00, 0x10])
        XCTAssertEqual(data.base64URL, "-_8AEA")
        XCTAssertEqual(Data(base64URL: "-_8AEA"), data)
        XCTAssertNil(Data(base64URL: "-_8AEA=="), "padding is not allowed")
        XCTAssertNil(Data(base64URL: "a"))
        XCTAssertEqual(Data(hex: "00ff10"), Data([0, 255, 16]))
        XCTAssertNil(Data(hex: "0g"))
        XCTAssertEqual(Data([1, 2]).readBigEndian(UInt16.self), 0x0102)
    }

    func testBLEReassemblerRejectsBadSequences() {
        var r = BLEReassembler()
        XCTAssertThrowsError(try r.feed(Data([0x00, 1, 2])), "continuation without a start")
        r.reset()
        XCTAssertNil(try r.feed(Data([0x01, 0x00, 0x04, 1, 2])))
        XCTAssertThrowsError(try r.feed(Data([0x01, 0x00, 0x02, 1])), "second start while one is in progress")
        r.reset()
        XCTAssertThrowsError(try r.feed(Data([0x03, 0x00, 0x05, 1, 2])), "length mismatch")
        r.reset()
        XCTAssertThrowsError(try r.feed(Data([0x03, 0x00, 0x00])), "zero-length frame")
        r.reset()
        XCTAssertThrowsError(try r.feed(Data([0x83, 0x00, 0x01, 1])), "unknown header bits")
    }

    func testFragmentRoundTripAtManySizes() throws {
        let frame = Data((0..<1000).map { UInt8($0 % 251) })
        for payload in [4, 5, 20, 23, 100, 185, 244, 512, 1100] {
            var r = BLEReassembler()
            var out: Data?
            for fragment in BLEFragmenter.fragments(of: frame, attPayload: payload) {
                XCTAssertLessThanOrEqual(fragment.count, payload)
                out = try r.feed(fragment)
            }
            XCTAssertEqual(out, frame, "payload \(payload)")
        }
    }
}
