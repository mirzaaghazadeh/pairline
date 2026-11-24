import CryptoKit
import XCTest
@testable import Pairline

/// Two sessions at once (PROTOCOL.md, "Wi-Fi"): pairing over Wi-Fi, request routing, falling back to Bluetooth, and
/// files over the bulk channel.
@MainActor
final class LinkLaneTests: XCTestCase {
    private var identity: IdentityStore!
    private var peers: PeerStore!
    private var manager: LinkManager!
    private var bridgeIdentity: P256.KeyAgreement.PrivateKey!
    private var pinned: Data?
    private var primaryConnector: FakeConnector!
    private var wifiConnector: FakeConnector!
    private var primaryBridge: TestBridge?
    private var wifiBridge: TestBridge?
    /// What both fake bridges do with each message; arguments are the bridge and the message.
    private var onMessage: ((TestBridge, [String: Any]) -> Void)?

    override func setUp() async throws {
        identity = IdentityStore(prefix: "test.")
        identity.reset()
        peers = PeerStore(prefix: "test.")
        peers.clear()
        UserDefaults.standard.set(TransportKind.tcp.rawValue, forKey: DebugSettings.transportKey)
        bridgeIdentity = P256.KeyAgreement.PrivateKey()
        pinned = nil
        primaryConnector = FakeConnector(kind: .tcp) { [unowned self] in self.makeBridge(kind: .tcp) }
        wifiConnector = FakeConnector(kind: .wifi) { [unowned self] in self.makeBridge(kind: .wifi) }
        manager = LinkManager(identityStore: identity, peerStore: peers,
                              deviceInfo: .deviceInfo(name: "iPhone", os: "17", app: "0.1.0")) { [unowned self] kind in
            kind == .wifi ? self.wifiConnector : self.primaryConnector
        }
    }

    override func tearDown() async throws {
        manager.setAllowed(false)
        identity.reset()
        peers.clear()
        UserDefaults.standard.removeObject(forKey: DebugSettings.transportKey)
        try? FileManager.default.removeItem(at: try FileTransferManager.receivedFolder())
    }

    /// Each connection gets a fresh bridge endpoint that shares the bridge's identity and pinned iPhone key.
    private func makeBridge(kind: TransportKind) -> LoopbackTransport {
        let (r, b) = LoopbackTransport.pair(kind: kind)
        let bridge = TestBridge(transport: b, identity: bridgeIdentity)
        bridge.pinnedRemote = pinned
        bridge.onMessage = { [weak self, weak bridge] object in
            guard let self, let bridge else { return }
            if object["t"] as? String == "device.info" { self.pinned = bridge.pinnedRemote }
            self.onMessage?(bridge, object)
        }
        if kind == .wifi { wifiBridge = bridge } else { primaryBridge = bridge }
        return r
    }

    private var link: PairingLink {
        PairingLink(deviceID: Data([1, 2, 3, 4, 5, 6, 7, 8]), bridgeKey: bridgeIdentity.publicKey.x963Representation,
                    secret: Data(repeating: 7, count: 32), wifiAddresses: ["192.168.1.23"], wifiPort: 47820)
    }

    private func waitFor(_ what: String, timeout: TimeInterval = 3, _ condition: () -> Bool) async {
        let end = Date().addingTimeInterval(timeout)
        while !condition(), Date() < end {
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertTrue(condition(), what)
    }

    /// Pairs over the first lane, then lets the Wi-Fi lane connect too.
    private func pairBoth() async throws {
        wifiConnector.enabled = false
        manager.setAllowed(true)
        try await manager.pair(with: link)
        pinned = primaryBridge?.pinnedRemote
        XCTAssertEqual(peers.peer?.transport, "tcp")
        wifiConnector.enable()
        await waitFor("both lanes up") { manager.activeTransports == [.tcp, .wifi] }
    }

    func testPairsOverWiFiWhenBluetoothCantReachTheBridge() async throws {
        primaryConnector.enabled = false
        manager.setAllowed(true)
        try await manager.pair(with: link)
        XCTAssertEqual(peers.peer?.transport, "wifi")
        XCTAssertEqual(manager.phase, .connected)
        XCTAssertEqual(manager.activeTransports, [.wifi])
        XCTAssertEqual(peers.peer?.wifi?.addresses, ["192.168.1.23"])
        XCTAssertEqual(peers.peer?.wifi?.port, 47820)
        // Pairing over Wi-Fi only dials the link's own addresses.
        let target = try XCTUnwrap(wifiConnector.targets.first)
        XCTAssertTrue(target.wifiExplicitOnly)
        XCTAssertEqual(target.wifi, [WiFiEndpoint(host: "192.168.1.23", port: 47820)])
    }

    func testBulkRequestsUseWiFiAndTheRestBluetooth() async throws {
        try await pairBoth()
        var seen: [String: TransportKind] = [:]
        onMessage = { bridge, object in
            guard let type = object["t"] as? String, let id = object["id"] as? Int else { return }
            seen[type] = bridge.transport.kind
            switch type {
            case "sms.history": bridge.send(["t": "sms.history", "id": id, "msgs": [], "more": false])
            case "status.get": bridge.send(["t": "status", "id": id, "caps": ["files"]])
            default: break
            }
        }
        _ = try await manager.request(.smsHistory(thread: nil, limit: 10))
        _ = try await manager.request(.statusGet)
        XCTAssertEqual(seen["sms.history"], .wifi)
        XCTAssertEqual(seen["status.get"], .tcp)
    }

    func testBulkRequestMovesToBluetoothWhenWiFiDrops() async throws {
        try await pairBoth()
        var answered: TransportKind?
        onMessage = { bridge, object in
            guard object["t"] as? String == "contacts.sync", let id = object["id"] as? Int else { return }
            if bridge.transport.kind == .wifi {
                bridge.transport.close() // Wi-Fi drops before the reply
            } else {
                answered = bridge.transport.kind
                bridge.send(["t": "contacts.sync", "id": id, "contacts": [], "deleted": [], "cursor": "c1", "more": false])
            }
        }
        wifiConnector.enabled = false
        let reply = try await manager.request(.contactsSync(since: nil))
        guard case .contactsSync(let page) = reply.message else { return XCTFail("not contacts.sync") }
        XCTAssertEqual(page.cursor, .string("c1"))
        XCTAssertEqual(answered, .tcp)
        XCTAssertEqual(manager.activeTransports, [.tcp])
        XCTAssertEqual(manager.phase, .connected)
    }

    func testWiFiOfferIsStoredForReconnecting() async throws {
        try await pairBoth()
        primaryBridge?.send(["t": "wifi.offer", "ip": ["10.0.0.7", "8.8.8.8"], "port": 47821, "ssid": NSNull(),
                             "password": NSNull(), "band": NSNull()])
        await waitFor("offer stored") { peers.peer?.wifi?.addresses == ["10.0.0.7"] }
        XCTAssertEqual(peers.peer?.wifi?.port, 47821)
    }

    func testEventsFromEitherLaneReachTheApp() async throws {
        try await pairBoth()
        var bodies: [String] = []
        manager.onEvent = { inbound in
            if case .smsNew(let message) = inbound.message { bodies.append(message.body) }
        }
        let sms: (String) -> [String: Any] = { body in
            ["t": "sms.new", "msg": ["key": "sms:\(body)", "thread": 1, "addr": "+15550100", "body": body,
                                     "time": 1_764_000_000_000, "box": "in"]]
        }
        primaryBridge?.send(sms("ble"))
        wifiBridge?.send(sms("wifi"))
        await waitFor("both events") { bodies.sorted() == ["ble", "wifi"] }
    }

    func testFileFromTheBridgeIsSaved() async throws {
        try await pairBoth()
        primaryBridge?.send(["t": "status", "caps": ["files", "wifi"]])
        await waitFor("files available") { manager.files.canSend }
        var answer: [String: Any]?
        var done: [String: Any]?
        onMessage = { _, object in
            if object["t"] as? String == "file.answer" { answer = object }
            if object["t"] as? String == "file.done" { done = object }
        }
        let content = Data((0..<120_000).map { UInt8($0 % 251) })
        wifiBridge?.send(["t": "file.offer", "fid": 9, "name": "../photo.jpg", "size": content.count, "type": "image/jpeg"])
        await waitFor("accepted") { answer != nil }
        XCTAssertEqual(answer?["accept"] as? Bool, true)
        var offset = 0
        while offset < content.count {
            let chunk = content[offset..<min(offset + 49_152, content.count)]
            var record = Data([0x01])
            record.append(Data.bigEndian(UInt32(9)))
            record.append(Data.bigEndian(UInt64(offset)))
            record.append(chunk)
            wifiBridge?.sendBulk(record)
            offset += chunk.count
        }
        await waitFor("done") { done != nil }
        XCTAssertEqual(done?["ok"] as? Bool, true)
        let transfer = try XCTUnwrap(manager.files.transfers.first)
        XCTAssertEqual(transfer.state, .done)
        XCTAssertEqual(transfer.name, "photo.jpg")
        let saved = try XCTUnwrap(transfer.fileURL)
        XCTAssertEqual(saved.deletingLastPathComponent().lastPathComponent, "Received")
        XCTAssertEqual(try Data(contentsOf: saved), content)
    }

    func testFileToTheBridgeIsSentInOrder() async throws {
        try await pairBoth()
        primaryBridge?.send(["t": "status", "caps": ["files"]])
        await waitFor("files available") { manager.files.canSend }
        let content = Data((0..<150_000).map { UInt8($0 % 239) })
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("pairline-test-\(UUID().uuidString).bin")
        try content.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        var received = Data()
        var fid: UInt32?
        onMessage = { bridge, object in
            guard object["t"] as? String == "file.offer", let value = object["fid"] as? Int else { return }
            XCTAssertEqual(object["size"] as? Int, content.count)
            fid = UInt32(value)
            bridge.send(["t": "file.answer", "fid": value, "accept": true])
        }
        wifiBridge?.onBulk = { [weak self] plaintext in
            guard plaintext.first == 0x01, plaintext.readBigEndian(UInt32.self, at: 1) == fid,
                  plaintext.readBigEndian(UInt64.self, at: 5) == UInt64(received.count) else {
                return XCTFail("bad data record")
            }
            received.append(plaintext.dropFirst(13))
            if received.count == content.count, let fid {
                self?.wifiBridge?.send(["t": "file.done", "fid": fid, "ok": true])
            }
        }
        manager.files.send(url: url)
        await waitFor("sent", timeout: 5) { manager.files.transfers.first?.state == .done }
        XCTAssertEqual(received, content)
    }

    func testTransfersFailWhenWiFiDrops() async throws {
        try await pairBoth()
        primaryBridge?.send(["t": "status", "caps": ["files"]])
        await waitFor("files available") { manager.files.canSend }
        wifiBridge?.send(["t": "file.offer", "fid": 3, "name": "big.bin", "size": 1_000_000, "type": NSNull()])
        await waitFor("receiving") { manager.files.transfers.first?.state == .active }
        wifiConnector.enabled = false
        wifiBridge?.transport.close()
        await waitFor("failed") {
            if case .failed = manager.files.transfers.first?.state { return true }
            return false
        }
        XCTAssertFalse(manager.files.canSend)
        XCTAssertEqual(manager.activeTransports, [.tcp])
    }

    func testOffersOverTheLimitAreDeclined() async throws {
        try await pairBoth()
        var answer: [String: Any]?
        onMessage = { _, object in if object["t"] as? String == "file.answer" { answer = object } }
        wifiBridge?.send(["t": "file.offer", "fid": 4, "name": "huge.iso", "size": FileTransferManager.maxSize + 1])
        await waitFor("declined") { answer != nil }
        XCTAssertEqual(answer?["accept"] as? Bool, false)
        XCTAssertEqual(answer?["reason"] as? String, "too_large")
    }
}
