import CryptoKit
import XCTest
@testable import Pairline

/// Connects the link manager to an in-test bridge.
final class FakeConnector: LinkConnector {
    let kind: TransportKind = .tcp
    let availability: BluetoothAvailability = .poweredOn
    var onAvailabilityChange: ((BluetoothAvailability) -> Void)?
    var makeTransport: () -> LoopbackTransport

    init(makeTransport: @escaping () -> LoopbackTransport) {
        self.makeTransport = makeTransport
    }

    func connect(_ target: ConnectTarget, completion: @escaping (Result<(FrameTransport, UUID?), Error>) -> Void) {
        let transport = makeTransport()
        DispatchQueue.main.async { completion(.success((transport, nil))) }
    }

    func cancel() {}
}

/// Request/response, events, pairing, unpairing and reconnection through `LinkManager`.
@MainActor
final class LinkManagerTests: XCTestCase {
    private var bridge: TestBridge?
    private var identity: IdentityStore!
    private var peers: PeerStore!
    private var manager: LinkManager!
    private var pairingLink: PairingLink!

    override func setUp() async throws {
        identity = IdentityStore(prefix: "test.")
        identity.reset()
        peers = PeerStore(prefix: "test.")
        peers.clear()
        UserDefaults.standard.set(TransportKind.tcp.rawValue, forKey: DebugSettings.transportKey)
        let bridgeIdentity = P256.KeyAgreement.PrivateKey()
        bridge = nil
        manager = LinkManager(identityStore: identity, peerStore: peers,
                              deviceInfo: .deviceInfo(name: "iPhone", os: "17", app: "0.1.0")) { [unowned self] _ in
            FakeConnector {
                // Each connection gets a fresh bridge endpoint that remembers the pinned iPhone key.
                let (r, b) = LoopbackTransport.pair()
                let next = TestBridge(transport: b, identity: bridgeIdentity)
                next.pinnedRemote = self.bridge?.pinnedRemote
                next.onMessage = self.bridge?.onMessage
                self.bridge = next
                return r
            }
        }
        pairingLink = PairingLink(deviceID: Data([1, 2, 3, 4, 5, 6, 7, 8]),
                                  bridgeKey: bridgeIdentity.publicKey.x963Representation,
                                  secret: Data(repeating: 7, count: 32))
    }

    override func tearDown() async throws {
        manager.setAllowed(false)
        identity.reset()
        peers.clear()
        UserDefaults.standard.removeObject(forKey: DebugSettings.transportKey)
    }

    private func pair() async throws {
        manager.setAllowed(true)
        try await manager.pair(with: pairingLink)
    }

    func testLockedLinkRefusesToPair() async {
        do {
            try await manager.pair(with: pairingLink)
            XCTFail("paired while locked")
        } catch {
            XCTAssertEqual(error as? PairingError, .locked)
        }
    }

    func testPairStoresPeerAndConnects() async throws {
        try await pair()
        XCTAssertEqual(manager.phase, .connected)
        XCTAssertEqual(peers.peer?.publicKey, pairingLink.bridgeKey)
        XCTAssertEqual(peers.peer?.deviceID, pairingLink.deviceID)
        XCTAssertEqual(peers.peer?.transport, "tcp")
    }

    func testWrongSecretFailsWithWrongCode() async {
        manager.setAllowed(true)
        let wrong = PairingLink(deviceID: pairingLink.deviceID, bridgeKey: pairingLink.bridgeKey,
                                secret: Data(repeating: 1, count: 32))
        do {
            try await manager.pair(with: wrong)
            XCTFail("paired with the wrong secret")
        } catch {
            XCTAssertEqual(error as? PairingError, .wrongCode)
        }
        XCTAssertNil(peers.peer)
    }

    func testRequestGetsTheMatchingReply() async throws {
        try await pair()
        bridge?.onMessage = { [weak self] object in
            guard object["t"] as? String == "status.get", let id = object["id"] as? Int else { return }
            self?.bridge?.send(["t": "status", "id": id, "battery": ["level": 64, "charging": true], "caps": ["sms.send"]])
        }
        let reply = try await manager.request(.statusGet)
        guard case .status(let status) = reply.message else { return XCTFail("not status") }
        XCTAssertEqual(status.battery?.level, 64)
        XCTAssertEqual(status.caps, ["sms.send"])
    }

    func testErrorReplyThrows() async throws {
        try await pair()
        bridge?.onMessage = { [weak self] object in
            guard let id = object["id"] as? Int, object["t"] as? String == "ussd.run" else { return }
            self?.bridge?.send(["t": "error", "id": id, "code": "permission", "msg": "no CALL_PHONE"])
        }
        do {
            _ = try await manager.request(.ussdRun(sim: 0, code: "*140#"))
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? LinkError, .bridge(BridgeErrorReply(code: "permission", msg: "no CALL_PHONE")))
        }
    }

    func testPingFromBridgeGetsPong() async throws {
        try await pair()
        let pong = expectation(description: "pong")
        bridge?.onMessage = { object in
            if object["t"] as? String == "pong", object["id"] as? Int == 4242 { pong.fulfill() }
        }
        bridge?.send(["t": "ping", "id": 4242])
        await fulfillment(of: [pong], timeout: 2)
    }

    func testEventsReachTheApp() async throws {
        try await pair()
        let event = expectation(description: "sms.new")
        manager.onEvent = { inbound in
            if case .smsNew(let message) = inbound.message, message.body == "hi" { event.fulfill() }
        }
        bridge?.send(["t": "sms.new", "msg": ["key": "sms:1", "thread": 3, "addr": "+15550100", "body": "hi",
                                              "time": 1_764_000_000_000, "box": "in", "sim": 0, "state": "received"]])
        await fulfillment(of: [event], timeout: 2)
    }

    func testUnpairFromBridgeForgetsPeer() async throws {
        try await pair()
        let changed = expectation(description: "peer change")
        manager.onPeerChange = { changed.fulfill() }
        bridge?.send(["t": "unpair"])
        await fulfillment(of: [changed], timeout: 2)
        XCTAssertNil(peers.peer)
        XCTAssertEqual(manager.phase, .notPaired)
    }

    func testUnpairFromIPhoneTellsTheBridge() async throws {
        try await pair()
        let told = expectation(description: "bridge told")
        bridge?.onMessage = { object in
            if object["t"] as? String == "unpair" { told.fulfill() }
        }
        manager.unpair()
        await fulfillment(of: [told], timeout: 2)
        XCTAssertNil(peers.peer)
        XCTAssertEqual(manager.phase, .notPaired)
    }

    func testReconnectsAfterTheLinkDrops() async throws {
        try await pair()
        let reconnected = expectation(description: "reconnected")
        manager.onEstablished = { reconnected.fulfill() }
        bridge?.sendRawFrame(Data([0x7e]))
        await fulfillment(of: [reconnected], timeout: 5)
        XCTAssertEqual(manager.phase, .connected)
    }

    func testRequestWhileDisconnectedFailsFast() async {
        do {
            _ = try await manager.request(.statusGet)
            XCTFail("expected notConnected")
        } catch {
            XCTAssertEqual(error as? LinkError, .notConnected)
        }
    }
}
