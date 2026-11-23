import XCTest
@testable import Pairline

@MainActor
final class AppDatabaseTests: XCTestCase {
    private var db: AppDatabase!
    private let base: Int64 = 1_764_000_000_000

    override func setUp() async throws {
        db = AppDatabase.inMemory()
    }

    private func msg(_ key: String, thread: Int64?, addr: String = "+15550100", body: String = "hi", at offset: Int64,
                     box: String = "in", state: String? = nil) -> SMSMessage {
        SMSMessage(key: key, thread: thread, addr: addr, body: body, time: base + offset, box: box, sim: 0, state: state)
    }

    func testConversationsAndUnread() {
        let inserted = db.upsertMessages([msg("sms:1", thread: 1, at: 0), msg("sms:2", thread: 2, addr: "+15550200", at: 10)],
                                         countUnread: true)
        XCTAssertEqual(inserted.count, 2)
        XCTAssertEqual(db.upsertMessages([msg("sms:1", thread: 1, at: 0)], countUnread: true).count, 0, "duplicates")
        let conversations = db.conversations()
        XCTAssertEqual(conversations.map(\.thread), [2, 1], "newest first")
        XCTAssertEqual(db.unreadCount, 2)
        db.markRead(thread: 2)
        XCTAssertEqual(db.unreadCount, 1)
        XCTAssertEqual(db.messages(thread: 1).count, 1)
    }

    func testSentMessageGetsItsKeyFromState() {
        let local = db.insertPending(thread: 1, to: "+15550100", body: "On my way", sim: 0, cid: "c-1")
        XCTAssertEqual(local.state, .pending)
        XCTAssertTrue(local.key.hasPrefix("local:"))
        db.updateState(cid: "c-1", state: .sent, key: "sms:9", error: nil)
        XCTAssertEqual(db.message(key: "sms:9")?.state, .sent)
        XCTAssertNil(db.message(key: local.key))
        // The same message later arrives through history sync: no duplicate.
        db.upsertMessages([msg("sms:9", thread: 1, body: "On my way", at: 5, box: "out", state: "sent")], countUnread: false)
        db.updateState(cid: "c-1", state: .delivered, key: "sms:9", error: nil)
        XCTAssertEqual(db.messages(thread: 1).count, 1)
        XCTAssertEqual(db.message(key: "sms:9")?.state, .delivered)
    }

    func testHistoryBeforeStateReplacesTheLocalCopy() {
        let local = db.insertPending(thread: nil, to: "+15550300", body: "Hello there", sim: nil, cid: "c-2")
        XCTAssertLessThan(local.thread, 0, "new conversation starts with a synthetic id")
        let stored = SMSMessage(key: "sms:20", thread: 30, addr: "+1 555 0300", body: "Hello there",
                                time: Date().milliseconds, box: "out", sim: 0, state: "sent")
        db.upsertMessages([stored], countUnread: false)
        let messages = db.messages(thread: 30)
        XCTAssertEqual(messages.map(\.key), ["sms:20"])
        XCTAssertEqual(messages.first?.cid, "c-2")
        XCTAssertNil(db.conversation(thread: local.thread), "the synthetic thread was merged into the real one")
        db.updateState(cid: "c-2", state: .delivered, key: "sms:20", error: nil)
        XCTAssertEqual(db.message(key: "sms:20")?.state, .delivered)
    }

    func testFailedSendKeepsTheMessage() {
        _ = db.insertPending(thread: 1, to: "+15550100", body: "x", sim: 0, cid: "c-3")
        db.markFailed(cid: "c-3", error: "generic failure")
        XCTAssertEqual(db.message(cid: "c-3")?.state, .failed)
        XCTAssertEqual(db.conversation(thread: 1)?.lastState, .failed)
    }

    func testUnstoredIncomingIsMergedWithItsStoredCopy() {
        db.upsertMessages([msg("rx:1764000000500", thread: nil, addr: "+98 912 555 0100", body: "code 1234", at: 500)],
                          countUnread: true)
        XCTAssertEqual(db.messageCount, 1)
        db.upsertMessages([msg("sms:77", thread: 8, addr: "+989125550100", body: "code 1234", at: 0)], countUnread: true)
        XCTAssertEqual(db.messageCount, 1)
        XCTAssertNotNil(db.message(key: "sms:77"))
        XCTAssertEqual(db.messages(thread: 8).count, 1)
        // And the other way around: an rx copy after the stored one is ignored.
        db.upsertMessages([msg("rx:1764000000600", thread: nil, addr: "+989125550100", body: "code 1234", at: 600)],
                          countUnread: true)
        XCTAssertEqual(db.messageCount, 1)
        XCTAssertEqual(db.newestMessageTime, base)
    }

    func testThreadsFromSummaryAndOlderFlag() {
        db.upsertThreads([SMSThreadSummary(thread: 5, addrs: ["+15550500"], snippet: "last", time: base, count: 3)])
        XCTAssertEqual(db.conversation(thread: 5)?.hasOlder, true)
        db.upsertMessages((1...3).map { msg("sms:\($0)", thread: 5, addr: "+15550500", at: Int64($0)) }, countUnread: false)
        XCTAssertEqual(db.conversation(thread: 5)?.hasOlder, false, "all messages are here")
    }

    func testContactsAndNames() {
        db.upsertContacts([ContactRecord(key: "c:1", name: "Ada Lovelace", starred: true,
                                         numbers: [ContactNumber(n: "+44 20 7946 0001", label: "mobile")], emails: [])],
                          deleted: [])
        XCTAssertEqual(db.displayName(for: "00442079460001"), "Ada Lovelace")
        XCTAssertEqual(db.contact(forNumber: "+442079460001")?.key, "c:1")
        db.upsertMessages([msg("sms:1", thread: 1, addr: "+442079460001", body: "Analytical", at: 0)], countUnread: false)
        XCTAssertEqual(db.searchConversations("ada").count, 1, "search by contact name")
        XCTAssertEqual(db.searchConversations("analyt").first?.1?.key, "sms:1", "search by text")
        XCTAssertEqual(db.searchConversations("7946").count, 1, "search by number")
        XCTAssertTrue(db.searchConversations("zzz").isEmpty)
        db.upsertContacts([], deleted: ["c:1"])
        XCTAssertEqual(db.displayName(for: "+442079460001"), "+442079460001")
    }

    func testCallsAndWipe() {
        db.upsertCalls([CallLogRecord(key: "cl:1", number: "+15550100", name: nil, type: "missed", time: base, dur: 0, sim: 1),
                        CallLogRecord(key: "cl:2", number: "+15550100", name: "Bo", type: "in", time: base + 9, dur: 61, sim: 0)])
        XCTAssertEqual(db.calls().map(\.key), ["cl:2", "cl:1"])
        XCTAssertEqual(db.calls(matching: "15550100").count, 2)
        db.setValue("i:4", for: "calllog.cursor")
        db.wipe()
        XCTAssertTrue(db.calls().isEmpty)
        XCTAssertNil(db.value("calllog.cursor"))
    }

    func testFileDatabaseIsProtected() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).sqlite")
        _ = try AppDatabase(url: url)
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        try? FileManager.default.removeItem(at: url)
    }
}

/// The demo bridge drives the real sync engine end to end (D-020).
@MainActor
final class DemoModeTests: XCTestCase {
    func testDemoSyncsLocalizedSampleData() async throws {
        let bridge = DemoBridge(content: .english)
        let controller = BridgeController(mode: .demo, link: bridge, db: .inMemory())
        let connected = expectation(description: "connected")
        let previous = bridge.onEstablished
        bridge.onEstablished = {
            previous?()
            connected.fulfill()
        }
        bridge.start()
        await fulfillment(of: [connected], timeout: 3)
        // The controller syncs on its own after the handshake; wait for it.
        for _ in 0..<60 where controller.db.calls().count < DemoContent.english.calls.count || controller.syncing {
            try await Task.sleep(nanoseconds: 100_000_000)
        }

        XCTAssertEqual(controller.db.conversations().count, DemoContent.english.conversations.count)
        XCTAssertEqual(controller.db.contacts().count, DemoContent.english.people.count)
        XCTAssertEqual(controller.db.calls().count, DemoContent.english.calls.count)
        XCTAssertTrue(controller.isDualSIM)
        XCTAssertFalse(controller.has(.callAudio), "the demo shows how a missing capability is explained")
        let otp = controller.db.conversations().first { $0.primaryAddress == "NWBANK" }
        XCTAssertEqual(otp.flatMap { OTPDetector.code(in: $0.snippet) }, "482913")

        // Sending goes pending → delivered.
        let sent = try controller.sendSMS(to: DemoContent.english.people[1].number, body: "Test", sim: 0, thread: nil)
        try await Task.sleep(nanoseconds: 2_800_000_000)
        let stored = controller.db.message(cid: sent.cid!)
        XCTAssertEqual(stored?.state, .delivered)
        XCTAssertFalse(stored?.key.hasPrefix("local:") ?? true)

        // USSD answers in the demo's language.
        let ussd = try await controller.runUSSD(code: "*140#", sim: 0)
        XCTAssertEqual(ussd.text, DemoContent.english.ussdBalance)
        bridge.stop()
    }

    func testEveryLanguageHasCompleteContent() {
        for content in [DemoContent.english, DemoContent.persian, DemoContent.arabic] {
            XCTAssertEqual(content.people.count, 10)
            XCTAssertEqual(content.conversations.count, 8)
            XCTAssertNotNil(OTPDetector.code(in: content.incomingOTP.text), content.incomingOTP.text)
            for conversation in content.conversations {
                if let person = conversation.person { XCTAssertLessThan(person, content.people.count) }
                XCTAssertFalse(conversation.lines.isEmpty)
            }
            for call in content.calls {
                if let person = call.person { XCTAssertLessThan(person, content.people.count) }
            }
        }
    }
}
