import CryptoKit
import XCTest
@testable import Pairline

// Remote mode (D-072) on the iPhone: the state store, the mailbox catch-up (dedupe, time window, dispatch through
// LinkManager) and the remote pairing state machine, against an in-memory mailbox instead of CloudKit.

/// An in-memory iCloud mailbox: what the iPhone sends lands in `.toBridge` (and in `sent`).
final class FakeMailbox: RemoteMailboxing {
    var boxes: [RemoteMailbox.Box: [RemoteMailbox.Record]] = [:]
    private(set) var sent: [RemoteMailbox.Record] = []
    private(set) var deleted: [RemoteMailbox.Box: [String]] = [:]
    private(set) var prepared = 0
    private(set) var tornDown = 0

    func prepare() async throws { prepared += 1 }
    func tearDown() async throws { tornDown += 1 }

    func send(_ sealed: Data, name: String) async throws {
        let record = RemoteMailbox.Record(name: name, sealed: sealed)
        sent.append(record)
        boxes[.toBridge, default: []].append(record)
    }

    func pending(in box: RemoteMailbox.Box, limit: Int) async throws -> [RemoteMailbox.Record] {
        Array(boxes[box, default: []].prefix(limit))
    }

    func delete(names: [String], in box: RemoteMailbox.Box) async throws {
        deleted[box, default: []] += names
        boxes[box]?.removeAll { names.contains($0.name) }
    }

    /// Puts a record where the bridge (or this iPhone) would, named after its record id unless `name` says otherwise.
    func put(_ sealed: Data, in box: RemoteMailbox.Box, name: String? = nil) {
        let name = name ?? (try! MailboxHeader(parsing: sealed)).recordName
        boxes[box, default: []].append(RemoteMailbox.Record(name: name, sealed: sealed))
    }
}

/// What the notification service extension would leave in the app group.
final class FakeInbox: RemoteInboxing {
    var waiting: [(recordName: String, plaintext: Data)] = []
    var handled = Set<String>()
    private(set) var cleared = 0

    func drain() -> [(recordName: String, plaintext: Data)] {
        defer { waiting = [] }
        return waiting
    }

    func isHandled(_ recordName: String) -> Bool { handled.contains(recordName) }
    func markHandled(_ recordName: String) { handled.insert(recordName) }

    func clear() {
        cleared += 1
        waiting = []
        handled = []
    }
}

private func ms(_ date: Date) -> UInt64 { UInt64(date.milliseconds) }

private func json(_ object: [String: Any]) -> Data { try! JSONSerialization.data(withJSONObject: object) }

private func object(_ data: Data) -> [String: Any]? { try? JSONSerialization.jsonObject(with: data) as? [String: Any] }

/// A kind 1 record the bridge (`sender`) seals for the iPhone (`phone`).
private func bridgeRecord(_ message: [String: Any], from sender: P256.KeyAgreement.PrivateKey,
                          to phone: P256.KeyAgreement.PublicKey, at time: Date = Date(),
                          direction: Direction = .bridgeToRemote) -> Data {
    try! MailboxCrypto.seal(json(message), identity: sender, recipient: phone, direction: direction, time: ms(time))
}

/// Opens what the iPhone wrote for the bridge.
private func bridgeOpens(_ sealed: Data, bridge: P256.KeyAgreement.PrivateKey,
                         phone: P256.KeyAgreement.PublicKey) -> [String: Any]? {
    guard let opened = try? MailboxCrypto.open(sealed, identity: bridge, sender: phone, direction: .remoteToBridge) else {
        return nil
    }
    return object(opened.plaintext)
}

@MainActor
private func waitFor(_ condition: () -> Bool, timeout: TimeInterval = 3) async {
    let end = Date().addingTimeInterval(timeout)
    while !condition(), Date() < end {
        try? await Task.sleep(nanoseconds: 10_000_000)
    }
}

// MARK: - State

final class RemoteStoreTests: XCTestCase {
    private let suite = "RemoteStoreTests"
    private var defaults: UserDefaults!

    override func setUp() {
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
    }

    func testSettingsSurviveARestart() {
        let settings = RemoteSettings(on: true, backend: .public, environment: .development, mailboxes: .random(),
                                      tokenSentAt: Date(timeIntervalSince1970: 1_764_000_000))
        RemoteStore(defaults: defaults).save(settings)
        XCTAssertEqual(RemoteStore(defaults: defaults).settings, settings)
    }

    func testTokenTimeIsKept() {
        let store = RemoteStore(defaults: defaults)
        store.save(RemoteSettings(on: true, backend: .public, environment: .development, mailboxes: .random()))
        let sent = Date(timeIntervalSince1970: 1_764_000_500)
        store.markTokenSent(at: sent)
        XCTAssertEqual(RemoteStore(defaults: defaults).settings?.tokenSentAt, sent)
    }

    func testRequestIDsStartAtAMillionAndAreNeverReused() {
        let store = RemoteStore(defaults: defaults)
        XCTAssertEqual(store.nextRequestID(), 1_000_000)
        XCTAssertEqual(store.nextRequestID(), 1_000_001)
        XCTAssertEqual(RemoteStore(defaults: defaults).nextRequestID(), 1_000_002)
        store.save(RemoteSettings(on: true, backend: .public, environment: .development, mailboxes: .random()))
        store.clear()
        XCTAssertEqual(store.nextRequestID(), 1_000_003)
    }

    func testClearForgetsTheSettings() {
        let store = RemoteStore(defaults: defaults)
        store.save(RemoteSettings(on: true, backend: .public, environment: .development, mailboxes: .random()))
        store.clear()
        XCTAssertNil(store.settings)
        XCTAssertNil(RemoteStore(defaults: defaults).settings)
    }

    func testBrokenStoredSettingsReadAsOff() {
        defaults.set(Data("{\"on\":true}".utf8), forKey: "remote.settings")
        XCTAssertNil(RemoteStore(defaults: defaults).settings)
    }
}

// MARK: - Catch-up

@MainActor
final class RemoteCatchUpTests: XCTestCase {
    private let suite = "RemoteCatchUpTests"
    private var defaults: UserDefaults!
    private var store: RemoteStore!
    private let phone = P256.KeyAgreement.PrivateKey()
    private let bridge = P256.KeyAgreement.PrivateKey()
    private var mailbox: FakeMailbox!
    private var inbox: FakeInbox!
    private var link: RemoteLink!
    private var delivered: [(message: [String: Any], shown: Bool)] = []
    private var now = Date()
    private var tokensFetched = 0

    override func setUp() async throws {
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
        store = RemoteStore(defaults: defaults)
        store.save(RemoteSettings(on: true, backend: .public, environment: .development, mailboxes: .random(),
                                  tokenSentAt: now))
        mailbox = FakeMailbox()
        inbox = FakeInbox()
        delivered = []
        tokensFetched = 0
        makeLink()
    }

    override func tearDown() async throws {
        defaults.removePersistentDomain(forName: suite)
    }

    private func makeLink() {
        let phone = phone, bridgeKey = bridge.publicKey.x963Representation, mailbox = mailbox!
        link = RemoteLink(store: store, inbox: inbox, identity: { phone }, bridgeKey: { bridgeKey },
                          makeMailbox: { _, _ in mailbox },
                          fetchToken: { [unowned self] _ in
                              self.tokensFetched += 1
                              return "token-\(self.tokensFetched)"
                          })
        link.now = { [unowned self] in self.now }
        link.onMessage = { [unowned self] delivery in
            self.delivered.append((object(delivery.plaintext) ?? [:], delivery.alreadyShown))
        }
    }

    private func fromBridge(_ message: [String: Any], at time: Date? = nil) -> Data {
        bridgeRecord(message, from: bridge, to: phone.publicKey, at: time ?? now)
    }

    /// A record this iPhone wrote for the bridge at `time`.
    private func own(_ message: [String: Any], at time: Date) -> Data {
        try! MailboxCrypto.seal(json(message), identity: phone, recipient: bridge.publicKey, direction: .remoteToBridge,
                                time: ms(time))
    }

    private var types: [String] { delivered.map { $0.message["t"] as? String ?? "?" } }

    func testOpensBothMailboxesAndDeletesWhatItHandled() async {
        mailbox.put(fromBridge(["t": "sms.new", "n": 1]), in: .alert)
        mailbox.put(fromBridge(["t": "sms.state", "n": 2]), in: .quiet)
        let got = await link.catchUp(sessionUp: false)
        XCTAssertTrue(got)
        XCTAssertEqual(types, ["sms.new", "sms.state"])
        XCTAssertEqual(delivered.map(\.shown), [false, false])
        XCTAssertTrue(mailbox.boxes[.alert, default: []].isEmpty)
        XCTAssertTrue(mailbox.boxes[.quiet, default: []].isEmpty)
        XCTAssertEqual(inbox.handled.count, 2)
        XCTAssertNil(link.problem)
    }

    func testDropsWhatTheReceivingRulesRefuseAndDeletesItToo() async throws {
        let day: TimeInterval = 24 * 3600
        let stranger = P256.KeyAgreement.PrivateKey()
        let refused: [Data] = [
            fromBridge(["t": "status", "n": "too old"], at: now.addingTimeInterval(-15 * day)),
            fromBridge(["t": "status", "n": "too far ahead"], at: now.addingTimeInterval(11 * 60)),
            // This iPhone's own direction, from the bridge's key.
            bridgeRecord(["t": "status"], from: bridge, to: phone.publicKey, at: now, direction: .remoteToBridge),
            // Not the pinned bridge.
            bridgeRecord(["t": "status"], from: stranger, to: phone.publicKey, at: now),
            // An invitation record (kind 2) outside a pairing.
            try MailboxCrypto.sealInvite(json(["t": "rpair.hello"]), secret: .random(count: 32),
                                         direction: .bridgeToRemote, time: ms(now)),
        ]
        refused.forEach { mailbox.put($0, in: .alert) }
        // A copy saved under another record name, and bytes that aren't a record.
        mailbox.put(fromBridge(["t": "status", "n": "renamed"]), in: .alert, name: Data.random(count: 16).hex)
        mailbox.put(Data([1, 2, 3]), in: .quiet, name: Data.random(count: 16).hex)
        // The edges of the window still count.
        mailbox.put(fromBridge(["t": "ok", "n": "13 days"], at: now.addingTimeInterval(-13 * day)), in: .quiet)
        mailbox.put(fromBridge(["t": "ok", "n": "9 minutes ahead"], at: now.addingTimeInterval(9 * 60)), in: .quiet)

        await link.catchUp(sessionUp: false)
        XCTAssertEqual(delivered.map { $0.message["n"] as? String }, ["13 days", "9 minutes ahead"])
        XCTAssertTrue(mailbox.boxes[.alert, default: []].isEmpty)
        XCTAssertTrue(mailbox.boxes[.quiet, default: []].isEmpty)
        XCTAssertEqual(inbox.handled.count, 9, "dropped records count as handled too")
    }

    func testHandledRecordsAreDeletedWithoutBeingHandedOnAgain() async {
        let seen = fromBridge(["t": "sms.new", "n": "seen"])
        inbox.handled.insert(try! MailboxHeader(parsing: seen).recordName)
        mailbox.put(seen, in: .alert)
        // The same record in both mailboxes counts once.
        let twice = fromBridge(["t": "status", "n": "twice"])
        mailbox.put(twice, in: .alert)
        mailbox.put(twice, in: .quiet)
        await link.catchUp(sessionUp: false)
        XCTAssertEqual(delivered.map { $0.message["n"] as? String }, ["twice"])
        XCTAssertTrue(mailbox.boxes[.alert, default: []].isEmpty)
        XCTAssertTrue(mailbox.boxes[.quiet, default: []].isEmpty)
        // And a second catch-up finds nothing new.
        mailbox.put(twice, in: .quiet)
        let got = await link.catchUp(sessionUp: false)
        XCTAssertFalse(got)
        XCTAssertEqual(delivered.count, 1)
    }

    func testWhatTheExtensionShowedComesFirstAndIsMarkedShown() async {
        let shown = fromBridge(["t": "sms.new", "n": "pushed"])
        let name = try! MailboxHeader(parsing: shown).recordName
        // As the notification service extension leaves it: the plaintext, handled, the record still in iCloud.
        inbox.waiting = [(name, json(["t": "sms.new", "n": "pushed"]))]
        inbox.handled.insert(name)
        mailbox.put(shown, in: .alert)
        mailbox.put(fromBridge(["t": "status", "n": "quiet"]), in: .quiet)
        await link.catchUp(sessionUp: false)
        XCTAssertEqual(delivered.map { $0.message["n"] as? String }, ["pushed", "quiet"])
        XCTAssertEqual(delivered.map(\.shown), [true, false])
        XCTAssertTrue(mailbox.deleted[.alert, default: []].contains(name))
        XCTAssertTrue(mailbox.boxes[.alert, default: []].isEmpty)
    }

    func testNothingWhileRemoteModeIsOff() async {
        store.clear()
        makeLink()
        mailbox.put(fromBridge(["t": "sms.new"]), in: .alert)
        let got = await link.catchUp(sessionUp: false)
        XCTAssertFalse(got)
        XCTAssertTrue(delivered.isEmpty)
    }

    // MARK: Tokens and this iPhone's records

    func testTokenEveryTwelveHours() async throws {
        store.markTokenSent(at: now.addingTimeInterval(-13 * 3600))
        makeLink()
        await link.catchUp(sessionUp: false)
        XCTAssertEqual(mailbox.sent.count, 1)
        let message = try XCTUnwrap(bridgeOpens(mailbox.sent[0].sealed, bridge: bridge, phone: phone.publicKey))
        XCTAssertEqual(message["t"] as? String, "remote.token")
        XCTAssertEqual(message["token"] as? String, "token-1")
        XCTAssertNil(message["id"])
        XCTAssertEqual(link.settings?.tokenSentAt, now)
        await link.catchUp(sessionUp: false)
        XCTAssertEqual(mailbox.sent.count, 1)
    }

    func testTokenAtOnceWhenTheBridgeLeavesRecordsUnread() async throws {
        store.markTokenSent(at: now.addingTimeInterval(-3600))
        makeLink()
        mailbox.put(own(["t": "sms.send", "id": 1_000_000], at: now.addingTimeInterval(-11 * 60)), in: .toBridge)
        await link.catchUp(sessionUp: false)
        XCTAssertEqual(mailbox.sent.count, 1)
        XCTAssertEqual(bridgeOpens(mailbox.sent[0].sealed, bridge: bridge, phone: phone.publicKey)?["t"] as? String,
                       "remote.token")
        XCTAssertEqual(link.problem, .bridgeNotReading)
        // Once per silence: still unread later, but a token went out since.
        now = now.addingTimeInterval(15 * 60)
        await link.catchUp(sessionUp: false)
        XCTAssertEqual(mailbox.sent.count, 1)
        XCTAssertEqual(link.problem, .bridgeNotReading)
    }

    func testUnreadRecordsDontCountWhileASessionIsUp() async {
        mailbox.put(own(["t": "sms.send"], at: now.addingTimeInterval(-11 * 60)), in: .toBridge)
        await link.catchUp(sessionUp: true)
        XCTAssertTrue(mailbox.sent.isEmpty)
        XCTAssertNil(link.problem)
    }

    func testTokenGoesOverTheSessionWhenOneIsUp() async {
        store.markTokenSent(at: now.addingTimeInterval(-13 * 3600))
        makeLink()
        var overSession: [Outbound] = []
        link.sendOverSession = { message in
            overSession.append(message)
            return true
        }
        await link.catchUp(sessionUp: true)
        XCTAssertEqual(overSession.map(\.type), ["remote.token"])
        XCTAssertEqual(overSession.first?.fields["token"] as? String, "token-1")
        XCTAssertTrue(mailbox.sent.isEmpty)
    }

    func testOwnRecordsOlderThanADayAreDeleted() async {
        let old = own(["t": "sms.send"], at: now.addingTimeInterval(-25 * 3600))
        mailbox.put(old, in: .toBridge)
        await link.catchUp(sessionUp: false)
        XCTAssertTrue(mailbox.boxes[.toBridge, default: []].isEmpty)
        XCTAssertNil(link.problem)
    }

    // MARK: Off

    func testResetRemovesEverythingButTheFarewell() async throws {
        mailbox.put(fromBridge(["t": "sms.new"]), in: .alert)
        mailbox.put(fromBridge(["t": "status"]), in: .quiet)
        mailbox.put(own(["t": "sms.send"], at: now), in: .toBridge)
        let farewell = try link.seal(Outbound.unpair.json(id: nil))
        await link.reset(farewell: farewell).value
        XCTAssertFalse(link.isOn)
        XCTAssertNil(RemoteStore(defaults: defaults).settings)
        XCTAssertEqual(inbox.cleared, 1)
        XCTAssertEqual(mailbox.tornDown, 1)
        XCTAssertTrue(mailbox.boxes[.alert, default: []].isEmpty)
        XCTAssertTrue(mailbox.boxes[.quiet, default: []].isEmpty)
        XCTAssertEqual(mailbox.boxes[.toBridge]?.map(\.name), [farewell.name])
        XCTAssertEqual(bridgeOpens(farewell.data, bridge: bridge, phone: phone.publicKey)?["t"] as? String, "unpair")
    }
}

// MARK: - Through LinkManager

@MainActor
final class RemoteLinkManagerTests: XCTestCase {
    private let suite = "RemoteLinkManagerTests"
    private var defaults: UserDefaults!
    private var identity: IdentityStore!
    private var peers: PeerStore!
    private let bridge = P256.KeyAgreement.PrivateKey()
    private var mailbox: FakeMailbox!
    private var inbox: FakeInbox!
    private var remote: RemoteLink!
    private var manager: LinkManager!

    override func setUp() async throws {
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
        identity = IdentityStore(prefix: "test.remote.")
        identity.reset()
        peers = PeerStore(prefix: "test.remote.")
        peers.save(PairedPeer(deviceID: Data(repeating: 9, count: 8), publicKey: bridge.publicKey.x963Representation,
                              pairedAt: Date(), name: nil, peripheralID: nil, transport: "remote", wifi: nil))
        let store = RemoteStore(defaults: defaults, prefix: "test.remote.")
        store.save(RemoteSettings(on: true, backend: .public, environment: .development, mailboxes: .random(),
                                  tokenSentAt: Date()))
        mailbox = FakeMailbox()
        inbox = FakeInbox()
        let identity = identity!, peers = peers!, mailbox = mailbox!
        remote = RemoteLink(store: store, inbox: inbox, identity: { try identity.identity() },
                            bridgeKey: { peers.peer?.publicKey }, makeMailbox: { _, _ in mailbox },
                            fetchToken: { _ in "token" })
        UserDefaults.standard.set(TransportKind.tcp.rawValue, forKey: DebugSettings.transportKey)
        // The nearby link never comes up: the bridge is far away.
        manager = LinkManager(identityStore: identity, peerStore: peers,
                              deviceInfo: .deviceInfo(name: "iPhone", os: "18", app: "1.0"), remote: remote) { _ in
            FakeConnector(enabled: false) { LoopbackTransport.pair().0 }
        }
        manager.setAllowed(true)
    }

    override func tearDown() async throws {
        manager.setAllowed(false)
        await remote.waitForReset()
        RemoteStore(defaults: defaults, prefix: "test.remote.").clear()
        identity.reset()
        peers.clear()
        defaults.removePersistentDomain(forName: suite)
        UserDefaults.standard.removeObject(forKey: DebugSettings.transportKey)
    }

    private var phoneKey: P256.KeyAgreement.PublicKey {
        try! CryptoPrimitives.publicKey(x963: identity.publicKey!)
    }

    private func fromBridge(_ message: [String: Any]) -> Data {
        bridgeRecord(message, from: bridge, to: phoneKey)
    }

    func testThePhaseIsViaICloud() {
        XCTAssertEqual(manager.phase, .remote)
        XCTAssertTrue(manager.phase.canReachBridge)
        XCTAssertFalse(manager.phase.isConnected)
    }

    func testARequestGoesThroughTheMailboxAndTheReplyComesBackByCatchUp() async throws {
        let request = Task { try await manager.request(.statusGet) }
        await waitFor { !mailbox.sent.isEmpty }
        let sent = try XCTUnwrap(bridgeOpens(XCTUnwrap(mailbox.sent.first).sealed, bridge: bridge, phone: phoneKey))
        XCTAssertEqual(sent["t"] as? String, "status.get")
        let id = try XCTUnwrap(sent["id"] as? Int)
        XCTAssertGreaterThanOrEqual(id, RemoteStore.firstRequestID)
        mailbox.put(fromBridge(["t": "status", "id": id, "battery": ["level": 57, "charging": false]]), in: .quiet)
        await manager.remoteCatchUp()
        let reply = try await request.value
        guard case .status(let status) = reply.message else { return XCTFail("not status") }
        XCTAssertEqual(status.battery?.level, 57)
        XCTAssertTrue(reply.viaMailbox)
    }

    func testEventsReachTheAppMarkedAsMailbox() async {
        var events: [Inbound] = []
        manager.onEvent = { events.append($0) }
        mailbox.put(fromBridge(["t": "sms.new", "msg": ["key": "sms:1", "thread": 3, "addr": "+15550100", "body": "hi",
                                                        "time": 1_764_000_000_000, "box": "in"]]), in: .alert)
        await manager.remoteCatchUp()
        XCTAssertEqual(events.map(\.type), ["sms.new"])
        XCTAssertEqual(events.first?.viaMailbox, true)
        XCTAssertEqual(events.first?.alreadyShown, false)
    }

    func testRemoteMessagesStayInTheLink() async {
        var events: [Inbound] = []
        var confirmed = 0
        manager.onEvent = { events.append($0) }
        manager.onRemotePairConfirm = { confirmed += 1 }
        mailbox.put(fromBridge(["t": "remote.state", "direct": "none", "reason": "no router support"]), in: .quiet)
        mailbox.put(fromBridge(["t": "rpair.confirm"]), in: .alert)
        await manager.remoteCatchUp()
        XCTAssertTrue(events.isEmpty)
        XCTAssertEqual(confirmed, 1)
        XCTAssertEqual(remote.direct.status, DirectStatus(availability: .unavailable, reason: .router))
    }

    func testUnpairFromTheBridgeThroughTheMailbox() async {
        mailbox.put(fromBridge(["t": "unpair"]), in: .quiet)
        await manager.remoteCatchUp()
        XCTAssertNil(peers.peer)
        XCTAssertFalse(remote.isOn)
        XCTAssertEqual(manager.phase, .notPaired)
        await remote.waitForReset()
        XCTAssertEqual(mailbox.tornDown, 1)
        XCTAssertGreaterThanOrEqual(inbox.cleared, 1)
    }

    func testUnpairHereTellsTheBridgeThroughTheMailbox() async {
        manager.unpair()
        await remote.waitForReset()
        XCTAssertEqual(mailbox.sent.count, 1)
        XCTAssertEqual(bridgeOpens(mailbox.sent[0].sealed, bridge: bridge, phone: phoneKey)?["t"] as? String, "unpair")
        XCTAssertEqual(mailbox.boxes[.toBridge]?.count, 1)
        XCTAssertNil(peers.peer)
        XCTAssertEqual(manager.phase, .notPaired)
    }

    func testCallsThroughTheMailboxDontRing() async {
        let controller = BridgeController(mode: .demo, link: manager, db: AppDatabase.inMemory())
        mailbox.put(fromBridge(["t": "call.incoming", "call": 7, "from": "+15550100"]), in: .alert)
        await manager.remoteCatchUp()
        XCTAssertNil(controller.calls.current)
        XCTAssertFalse(controller.calls.showCallScreen)
    }

    func testTurningOffTellsTheBridgeThroughTheMailbox() async {
        await manager.turnOffRemote()
        XCTAssertFalse(remote.isOn)
        let message = bridgeOpens(mailbox.sent[0].sealed, bridge: bridge, phone: phoneKey)
        XCTAssertEqual(message?["t"] as? String, "remote.config")
        XCTAssertEqual(message?["on"] as? Bool, false)
        XCTAssertEqual(mailbox.tornDown, 1)
        XCTAssertNotEqual(manager.phase, .remote)
    }
}

// MARK: - Remote pairing

@MainActor
final class RemotePairingTests: XCTestCase {
    private let phone = P256.KeyAgreement.PrivateKey()
    private let bridge = P256.KeyAgreement.PrivateKey()
    private let bridgeID = Data(repeating: 9, count: 8)
    private var mailbox: FakeMailbox!
    private var now = Date()
    private var adopted: (peer: PairedPeer, settings: RemoteSettings)?
    private var rolledBack = false
    private var restored = false
    private var pairing: RemotePairing!

    override func setUp() async throws {
        mailbox = FakeMailbox()
        now = Date()
        adopted = nil
        rolledBack = false
        restored = false
        let phone = phone, mailbox = mailbox!
        pairing = RemotePairing(environment: .init(
            identity: { phone },
            makeMailbox: { _ in mailbox },
            fetchToken: { "web-auth-token" },
            now: { [unowned self] in self.now },
            adopt: { [unowned self] peer, settings in self.adopted = (peer, settings) },
            rollBack: { [unowned self] in self.rolledBack = true },
            restore: { [unowned self] in self.restored = true }))
    }

    private var bridgeKey: Data { bridge.publicKey.x963Representation }

    private func invitation() throws -> RemoteInvitation {
        guard case .inviting(let uri, _, _) = pairing.phase else { throw XCTSkip("no invitation: \(pairing.phase)") }
        return try RemoteInvitation(string: uri)
    }

    private func bridgeSends(_ message: [String: Any], _ invitation: RemoteInvitation) {
        mailbox.put(try! MailboxCrypto.sealInvite(json(message), secret: invitation.secret,
                                                  direction: .bridgeToRemote, time: ms(now)), in: .alert)
    }

    /// `rpair.hello` committing to `nonceB`.
    private func hello(_ invitation: RemoteInvitation, key: Data? = nil, nonceB: Data) {
        let key = key ?? bridgeKey
        let commit = RemotePairingCrypto.commit(remoteKey: invitation.remoteKey, bridgeKey: key, nonceB: nonceB)
        bridgeSends(["t": "rpair.hello", "k": key.base64URL, "id": bridgeID.base64URL, "c": commit.base64URL],
                    invitation)
    }

    /// The last invitation record this iPhone sent.
    private func lastSent(_ invitation: RemoteInvitation) -> [String: Any]? {
        guard let record = mailbox.sent.last,
              let opened = try? MailboxCrypto.openInvite(record.sealed, secret: invitation.secret,
                                                         direction: .remoteToBridge) else { return nil }
        return object(opened.plaintext)
    }

    /// Runs the pairing up to the code; returns the invitation and the expected code.
    private func reachTheCode() async throws -> (RemoteInvitation, String) {
        await pairing.start()
        let invitation = try invitation()
        let nonceB = Data.random(count: 32)
        hello(invitation, nonceB: nonceB)
        await pairing.poll()
        let nonce = try XCTUnwrap(lastSent(invitation))
        XCTAssertEqual(nonce["t"] as? String, "rpair.nonce")
        let nonceR = try XCTUnwrap(Data(base64URL: XCTUnwrap(nonce["n"] as? String)))
        XCTAssertEqual(nonceR.count, 32)
        bridgeSends(["t": "rpair.reveal", "n": nonceB.base64URL], invitation)
        await pairing.poll()
        let code = RemotePairingCrypto.code(secret: invitation.secret, remoteKey: invitation.remoteKey,
                                            bridgeKey: bridgeKey, nonceR: nonceR, nonceB: nonceB)
        return (invitation, code)
    }

    private func bridgeConfirmRecord() -> Data {
        bridgeRecord(["t": "rpair.confirm"], from: bridge, to: phone.publicKey, at: now)
    }

    func testTheInvitation() async throws {
        await pairing.start()
        let invitation = try invitation()
        XCTAssertEqual(invitation.remoteKey, phone.publicKey.x963Representation)
        XCTAssertEqual(invitation.backend, .public)
        XCTAssertEqual(invitation.environment, .development)
        XCTAssertEqual(invitation.token, "web-auth-token")
        XCTAssertEqual(invitation.expiry, UInt64(now.timeIntervalSince1970 + 600))
        XCTAssertTrue(invitation.mailboxes.isValid)
        XCTAssertEqual(mailbox.prepared, 1)
    }

    func testAFullPairing() async throws {
        let (invitation, code) = try await reachTheCode()
        XCTAssertEqual(pairing.phase, .comparing(code: code))
        XCTAssertTrue(mailbox.boxes[.alert, default: []].isEmpty, "handled invitation records are deleted")

        await pairing.confirm()
        XCTAssertEqual(pairing.phase, .confirming)
        let adopted = try XCTUnwrap(adopted)
        XCTAssertEqual(adopted.peer.deviceID, bridgeID)
        XCTAssertEqual(adopted.peer.publicKey, bridgeKey)
        XCTAssertEqual(adopted.peer.transport, "remote")
        XCTAssertEqual(adopted.settings.mailboxes, invitation.mailboxes)
        XCTAssertTrue(adopted.settings.on)
        XCTAssertEqual(adopted.settings.backend, .public)
        let confirm = try XCTUnwrap(mailbox.sent.last)
        XCTAssertEqual(bridgeOpens(confirm.sealed, bridge: bridge, phone: phone.publicKey)?["t"] as? String,
                       "rpair.confirm")

        mailbox.put(bridgeConfirmRecord(), in: .alert)
        await pairing.poll()
        XCTAssertEqual(pairing.phase, .paired)
        XCTAssertTrue(mailbox.boxes[.alert, default: []].isEmpty)
        XCTAssertFalse(rolledBack)
    }

    func testTheBridgeMayConfirmFirst() async throws {
        let (_, code) = try await reachTheCode()
        mailbox.put(bridgeConfirmRecord(), in: .alert)
        await pairing.poll()
        XCTAssertEqual(pairing.phase, .comparing(code: code))
        XCTAssertEqual(mailbox.boxes[.alert]?.count, 1, "kept until this user has compared the codes")
        await pairing.confirm()
        XCTAssertEqual(pairing.phase, .paired)
    }

    func testTheConfirmMayArriveThroughTheLinksCatchUp() async throws {
        _ = try await reachTheCode()
        await pairing.confirm()
        XCTAssertEqual(pairing.phase, .confirming)
        pairing.bridgeConfirmed()
        XCTAssertEqual(pairing.phase, .paired)
    }

    func testTheSameHelloTwiceIsHarmless() async throws {
        await pairing.start()
        let invitation = try invitation()
        let nonceB = Data.random(count: 32)
        hello(invitation, nonceB: nonceB)
        hello(invitation, nonceB: nonceB)
        await pairing.poll()
        XCTAssertEqual(mailbox.sent.count, 1)
        guard case .inviting(_, _, true) = pairing.phase else { return XCTFail("\(pairing.phase)") }
    }

    func testASecondDifferentHelloStopsThePairing() async throws {
        await pairing.start()
        let invitation = try invitation()
        hello(invitation, nonceB: .random(count: 32))
        await pairing.poll()
        hello(invitation, key: P256.KeyAgreement.PrivateKey().publicKey.x963Representation, nonceB: .random(count: 32))
        await pairing.poll()
        XCTAssertEqual(pairing.phase, .failed(.someoneElse))
        await waitFor { restored }
        XCTAssertTrue(restored)
        XCTAssertEqual(mailbox.tornDown, 1)
        XCTAssertNil(adopted)
    }

    func testARevealThatBreaksTheCommitmentStopsThePairing() async throws {
        await pairing.start()
        let invitation = try invitation()
        hello(invitation, nonceB: .random(count: 32))
        await pairing.poll()
        bridgeSends(["t": "rpair.reveal", "n": Data.random(count: 32).base64URL], invitation)
        await pairing.poll()
        XCTAssertEqual(pairing.phase, .failed(.notVerified))
    }

    func testAMalformedHelloStopsThePairing() async throws {
        await pairing.start()
        let invitation = try invitation()
        let commit = RemotePairingCrypto.commit(remoteKey: invitation.remoteKey, bridgeKey: bridgeKey,
                                                nonceB: .random(count: 32))
        bridgeSends(["t": "rpair.hello", "k": bridgeKey.base64URL, "id": Data(count: 7).base64URL,
                     "c": commit.base64URL], invitation)
        await pairing.poll()
        XCTAssertEqual(pairing.phase, .failed(.notVerified))
    }

    func testTheInvitationExpires() async throws {
        await pairing.start()
        now = now.addingTimeInterval(601)
        await pairing.poll()
        XCTAssertEqual(pairing.phase, .failed(.expired))
        await waitFor { restored }
        XCTAssertTrue(restored)
    }

    func testNoConfirmFromTheBridgeInTenMinutesUndoesThePairing() async throws {
        _ = try await reachTheCode()
        await pairing.confirm()
        now = now.addingTimeInterval(601)
        await pairing.poll()
        XCTAssertEqual(pairing.phase, .failed(.timedOut))
        XCTAssertTrue(rolledBack)
    }

    func testCancelBeforeTheMatchCleansUp() async throws {
        await pairing.start()
        pairing.cancel()
        XCTAssertEqual(pairing.phase, .cancelled)
        await waitFor { restored }
        XCTAssertEqual(mailbox.tornDown, 1)
        XCTAssertFalse(rolledBack)
    }
}
