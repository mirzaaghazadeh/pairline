import CryptoKit
import XCTest
@testable import Pairline

/// The iPhone's session state machine against an in-test bridge over a loopback transport.
final class LinkSessionTests: XCTestCase {
    private var sessions: [LinkSession] = []

    private func makeSession(bridge: TestBridge, transport: LoopbackTransport, identity: P256.KeyAgreement.PrivateKey,
                             pairing: PairingLink?, pinned: Data? = nil) -> LinkSession {
        let session = LinkSession(transport: transport, identity: identity, pinnedBridgeKey: pinned, pairing: pairing)
        sessions.append(session)
        return session
    }

    func testPairingThenHandshakeThenMessagesBothWays() {
        let (r, b) = LoopbackTransport.pair()
        let bridge = TestBridge(transport: b)
        let identity = P256.KeyAgreement.PrivateKey()
        let session = makeSession(bridge: bridge, transport: r, identity: identity, pairing: bridge.pairingLink)

        let paired = expectation(description: "paired")
        let established = expectation(description: "established")
        let received = expectation(description: "received status")
        var pinnedKey: Data?
        session.onPaired = { key in
            pinnedKey = key
            paired.fulfill()
        }
        session.onEstablished = { established.fulfill() }
        session.onMessage = { data in
            let inbound = try? Inbound.decode(data)
            if case .status(let status)? = inbound?.message, status.battery?.level == 55 { received.fulfill() }
        }
        let bridgeGot = expectation(description: "bridge got device.info")
        bridge.onMessage = { object in
            if object["t"] as? String == "device.info" { bridgeGot.fulfill() }
        }
        session.start()
        wait(for: [paired, established], timeout: 2)
        XCTAssertEqual(pinnedKey, bridge.identity.publicKey.x963Representation)
        XCTAssertEqual(bridge.pinnedRemote, identity.publicKeyX963)
        XCTAssertEqual(session.phase, .established)

        try? session.send(Outbound.deviceInfo(name: "iPhone", os: "17", app: "0.1.0").json(id: nil))
        bridge.send(["t": "status", "battery": ["level": 55, "charging": false]])
        wait(for: [received, bridgeGot], timeout: 2)
        XCTAssertTrue(bridge.confirmed)
    }

    func testReconnectWithPinnedKeysOnly() {
        let identity = P256.KeyAgreement.PrivateKey()
        let bridgeIdentity = P256.KeyAgreement.PrivateKey()
        let (r, b) = LoopbackTransport.pair()
        let bridge = TestBridge(transport: b, identity: bridgeIdentity)
        bridge.pinnedRemote = identity.publicKeyX963
        let session = makeSession(bridge: bridge, transport: r, identity: identity, pairing: nil,
                                  pinned: bridgeIdentity.publicKey.x963Representation)
        let established = expectation(description: "established")
        session.onEstablished = { established.fulfill() }
        session.start()
        wait(for: [established], timeout: 2)
        // The first frame R sent is its 99-byte hello.
        XCTAssertEqual(r.sent.first?.first, FrameType.hello.rawValue)
        XCTAssertEqual(r.sent.first?.count, 100)
    }

    func testBridgeRejectsWrongPairingSecret() {
        let (r, b) = LoopbackTransport.pair()
        let bridge = TestBridge(transport: b)
        var link = bridge.pairingLink
        link = PairingLink(deviceID: link.deviceID, bridgeKey: link.bridgeKey, secret: Data(repeating: 9, count: 32))
        let session = makeSession(bridge: bridge, transport: r, identity: .init(), pairing: link)
        let closed = expectation(description: "closed")
        session.onClose = { error in
            XCTAssertEqual(error, .pairingRejected("bad_mac"))
            closed.fulfill()
        }
        session.start()
        wait(for: [closed], timeout: 2)
    }

    func testPairFailReasonIsReported() {
        let (r, b) = LoopbackTransport.pair()
        let bridge = TestBridge(transport: b)
        bridge.failReason = "expired"
        let session = makeSession(bridge: bridge, transport: r, identity: .init(), pairing: bridge.pairingLink)
        let closed = expectation(description: "closed")
        session.onClose = { error in
            XCTAssertEqual(error, .pairingRejected("expired"))
            closed.fulfill()
        }
        session.start()
        wait(for: [closed], timeout: 2)
    }

    func testBridgeWithoutTheSecretIsRejected() {
        let (r, b) = LoopbackTransport.pair()
        let bridge = TestBridge(transport: b)
        bridge.tamperPairOK = true
        let session = makeSession(bridge: bridge, transport: r, identity: .init(), pairing: bridge.pairingLink)
        let closed = expectation(description: "closed")
        var paired = false
        session.onPaired = { _ in paired = true }
        session.onClose = { error in
            XCTAssertEqual(error, .pairingMACMismatch)
            closed.fulfill()
        }
        session.start()
        wait(for: [closed], timeout: 2)
        XCTAssertFalse(paired)
    }

    func testDifferentBridgeKeyFailsTheConfirm() {
        let identity = P256.KeyAgreement.PrivateKey()
        let (r, b) = LoopbackTransport.pair()
        let bridge = TestBridge(transport: b)
        bridge.pinnedRemote = identity.publicKeyX963
        // R pinned some other bridge's key.
        let session = makeSession(bridge: bridge, transport: r, identity: identity, pairing: nil,
                                  pinned: P256.KeyAgreement.PrivateKey().publicKey.x963Representation)
        let closed = expectation(description: "closed")
        session.onClose = { error in
            XCTAssertEqual(error, .keyMismatch)
            closed.fulfill()
        }
        session.start()
        wait(for: [closed], timeout: 2)
    }

    func testReplayedRecordClosesTheConnection() {
        let identity = P256.KeyAgreement.PrivateKey()
        let (r, b) = LoopbackTransport.pair()
        let bridge = TestBridge(transport: b)
        bridge.pinnedRemote = identity.publicKeyX963
        let session = makeSession(bridge: bridge, transport: r, identity: identity, pairing: nil,
                                  pinned: bridge.identity.publicKey.x963Representation)
        let established = expectation(description: "established")
        session.onEstablished = { established.fulfill() }
        session.start()
        wait(for: [established], timeout: 2)

        var messages = 0
        session.onMessage = { _ in messages += 1 }
        let closed = expectation(description: "closed")
        session.onClose = { error in
            XCTAssertEqual(error, .protocolViolation("replay"))
            closed.fulfill()
        }
        let wire = bridge.send(["t": "ping", "id": 1])
        bridge.sendRaw(record: wire)
        wait(for: [closed], timeout: 2)
        XCTAssertEqual(messages, 1)
        XCTAssertEqual(session.phase, .closed)
    }

    func testUnknownFrameTypeClosesTheConnection() {
        let identity = P256.KeyAgreement.PrivateKey()
        let (r, b) = LoopbackTransport.pair()
        let bridge = TestBridge(transport: b)
        bridge.pinnedRemote = identity.publicKeyX963
        let session = makeSession(bridge: bridge, transport: r, identity: identity, pairing: nil,
                                  pinned: bridge.identity.publicKey.x963Representation)
        let established = expectation(description: "established")
        session.onEstablished = { established.fulfill() }
        session.start()
        wait(for: [established], timeout: 2)
        let closed = expectation(description: "closed")
        session.onClose = { _ in closed.fulfill() }
        bridge.sendRawFrame(Data([0x7e, 0, 1]))
        wait(for: [closed], timeout: 2)
    }

    func testSendingBeforeEstablishedFails() {
        let (r, _) = LoopbackTransport.pair()
        let session = LinkSession(transport: r, identity: P256.KeyAgreement.PrivateKey(), pinnedBridgeKey: nil, pairing: nil)
        XCTAssertThrowsError(try session.send(Data("{}".utf8)))
    }

    func testSealerRefusesCounterLimit() throws {
        var opener = RecordOpener(key: Data(repeating: 1, count: 32), direction: .bridgeToRemote)
        var sealer = RecordSealer(key: Data(repeating: 1, count: 32), direction: .bridgeToRemote)
        let first = try sealer.seal(Data("a".utf8))
        let second = try sealer.seal(Data("b".utf8))
        XCTAssertEqual(try opener.open(first).counter, 0)
        XCTAssertEqual(try opener.open(second).counter, 1)
        XCTAssertThrowsError(try opener.open(first)) { XCTAssertEqual($0 as? ProtocolError, .replayedCounter) }
        // A record claiming a counter at the limit is refused before decryption.
        let forged = Data.bigEndian(Record.counterLimit) + Data(repeating: 0, count: 16)
        XCTAssertThrowsError(try opener.open(forged))
    }
}
