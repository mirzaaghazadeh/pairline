import CryptoKit
import XCTest
@testable import Pairline

/// The user's own server (D-106): its link, `remote.relay`, the preamble and the status bytes against `relay` in
/// protocol/test-vectors/v1-remote.json, the bridge's `remote.state` fields, and what's kept in the Keychain.
final class RelayServerTests: XCTestCase {
    private static let relay: [String: Any] = {
        let url = Bundle(for: RelayServerTests.self).url(forResource: "v1-remote", withExtension: "json")!
        let all = try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        return all["relay"] as! [String: Any]
    }()

    private var v: [String: Any] { Self.relay }

    private func hex(_ value: Any?) -> Data { Data(hex: value as! String)! }

    private var rendezvous: Data { hex(v["rendezvous_id"]) }

    func testLinksThatParse() throws {
        for link in v["links"] as! [[String: Any]] {
            let why = link["why"] as! String
            // Release rules: no local addresses.
            let server = try RelayServer.parse(link["uri"] as! String, allowLocal: false, rendezvous: rendezvous).get()
            XCTAssertEqual(server.host, link["host"] as? String, why)
            XCTAssertEqual(Int(server.port), link["port"] as? Int, why)
            XCTAssertEqual(server.fingerprint, hex(link["fp"]), why)
            XCTAssertEqual(server.key, hex(link["k"]), why)
            XCTAssertEqual(server.rendezvous, rendezvous, why)
            XCTAssertNotNil(server.dialable, why)
        }
    }

    func testLinksThatMustFail() {
        for link in v["links_must_fail"] as! [[String: Any]] {
            let result = RelayServer.parse(link["uri"] as! String, allowLocal: false, rendezvous: rendezvous)
            if case .success = result { XCTFail("accepted: \(link["why"] as! String)") }
        }
    }

    func testWhyALinkIsRefused() {
        let fp = Data(repeating: 1, count: 32).base64URL, k = Data(repeating: 2, count: 32).base64URL
        XCTAssertEqual(RelayServer.parse("pairline://pair?v=0", allowLocal: false).failure, .notARelayLink)
        XCTAssertEqual(RelayServer.parse("pairline-relay://relay.example.net:443?v=2&fp=\(fp)&k=\(k)", allowLocal: false).failure,
                       .unsupportedVersion)
        XCTAssertEqual(RelayServer.parse("pairline-relay://192.168.1.10:443?v=1&fp=\(fp)&k=\(k)", allowLocal: false).failure,
                       .insideHome)
        XCTAssertEqual(RelayServer.parse("pairline-relay://relay.local:443?v=1&fp=\(fp)&k=\(k)", allowLocal: false).failure,
                       .insideHome)
    }

    /// Debug builds reach a relay on the development Mac: loopback and private addresses, never a `.local` name.
    func testLocalAddressesOnlyWhenAllowed() throws {
        let fp = Data(repeating: 1, count: 32).base64URL, k = Data(repeating: 2, count: 32).base64URL
        for host in ["127.0.0.1", "10.0.2.2", "192.168.1.10", "[::1]", "[fd00::1]"] {
            let link = "pairline-relay://\(host):47840?v=1&fp=\(fp)&k=\(k)"
            XCTAssertNotNil(try? RelayServer.parse(link, allowLocal: true).get(), host)
            XCTAssertNil(try? RelayServer.parse(link, allowLocal: false).get(), host)
        }
        let loopback = try RelayServer.parse("pairline-relay://[::1]:47840?v=1&fp=\(fp)&k=\(k)", allowLocal: true).get()
        XCTAssertEqual(loopback.host, "::1")
        XCTAssertNil(try? RelayServer.parse("pairline-relay://relay.local:47840?v=1&fp=\(fp)&k=\(k)", allowLocal: true).get())
    }

    func testEveryServerGetsItsOwnRendezvousID() throws {
        let link = (v["links"] as! [[String: Any]])[0]["uri"] as! String
        let a = try RelayServer.parse(link, allowLocal: false).get()
        let b = try RelayServer.parse(link, allowLocal: false).get()
        XCTAssertEqual(a.rendezvous.count, 16)
        XCTAssertNotEqual(a.rendezvous, b.rendezvous)
    }

    func testRemoteRelayMessage() throws {
        let expected = v["remote_relay"] as! [String: Any]
        let link = (v["links"] as! [[String: Any]])[0]["uri"] as! String
        let server = try RelayServer.parse(link, allowLocal: false, rendezvous: rendezvous).get()
        let body = server.message
        XCTAssertEqual(body["host"] as? String, expected["host"] as? String)
        XCTAssertEqual(body["port"] as? Int, expected["port"] as? Int)
        XCTAssertEqual(body["fp"] as? String, expected["fp"] as? String)
        XCTAssertEqual(body["k"] as? String, expected["k"] as? String)
        XCTAssertEqual(body["rv"] as? String, expected["rv"] as? String)

        let sent = try JSONSerialization.jsonObject(with: Outbound.remoteRelay(server).json(id: 7)) as! [String: Any]
        XCTAssertEqual(sent["t"] as? String, "remote.relay")
        XCTAssertEqual(sent["id"] as? Int, 7)
        XCTAssertEqual((sent["relay"] as? [String: Any])?["rv"] as? String, expected["rv"] as? String)
        let off = try JSONSerialization.jsonObject(with: Outbound.remoteRelay(nil).json(id: 8)) as! [String: Any]
        XCTAssertTrue(off["relay"] is NSNull)
        XCTAssertTrue(Outbound.remoteRelay(nil).isRequest)
    }

    func testPreambles() throws {
        let link = (v["links"] as! [[String: Any]])[0]["uri"] as! String
        let server = try RelayServer.parse(link, allowLocal: false, rendezvous: rendezvous).get()
        XCTAssertEqual(server.key, hex(v["access_key"]))
        XCTAssertEqual(server.preamble(role: .remote).hex, v["preamble_remote"] as? String)
        XCTAssertEqual(server.preamble(role: .bridge).hex, v["preamble_bridge"] as? String)
        XCTAssertEqual(server.preamble().count, RelayServer.preambleLength)
    }

    func testStatusBytes() {
        let status = v["status"] as! [String: Int]
        XCTAssertEqual(RelayServer.Status.keepAlive.rawValue, UInt8(status["keepalive"]!))
        XCTAssertEqual(RelayServer.Status.joined.rawValue, UInt8(status["joined"]!))
        XCTAssertEqual(RelayServer.Status.noBridge.rawValue, UInt8(status["no_bridge"]!))
        XCTAssertEqual(RelayServer.Status.wrongKey.rawValue, UInt8(status["wrong_key"]!))
        XCTAssertEqual(RelayServer.Status.badVersion.rawValue, UInt8(status["bad_version"]!))
        XCTAssertEqual(RelayServer.Status.full.rawValue, UInt8(status["full"]!))
    }

    func testThePinIsTheCertificatesSHA256() {
        let der = Data("a certificate".utf8)
        let pin = Data(SHA256.hash(data: der))
        XCTAssertTrue(RelayServer.pinMatches(certificate: der, fingerprint: pin))
        XCTAssertFalse(RelayServer.pinMatches(certificate: der + Data([0]), fingerprint: pin))
        XCTAssertFalse(RelayServer.pinMatches(certificate: der, fingerprint: pin.prefix(31)))
    }

    /// `remote.state`'s `relay` and `relayReason` (PROTOCOL.md): none, up, down with a reason; unknown values.
    func testTheBridgesWordOnTheServer() {
        let none = DirectStatus(message: ["direct": "blocked", "reason": "cgnat"])
        XCTAssertNil(none?.relay)
        XCTAssertNil(DirectStatus(message: ["direct": "blocked", "relay": NSNull()])?.relay)
        let up = DirectStatus(message: ["direct": "blocked", "reason": "cgnat", "relay": "up", "relayReason": "key"])
        XCTAssertEqual(up?.relay, .up)
        XCTAssertNil(up?.relayReason, "a reason only goes with down")
        for (code, reason) in [("unreachable", RelayBridgeReason.unreachable), ("pin", .pin), ("key", .key),
                               ("version", .version), ("full", .full)] {
            let down = DirectStatus(message: ["direct": "open", "relay": "down", "relayReason": code])
            XCTAssertEqual(down?.relay, .down)
            XCTAssertEqual(down?.relayReason, reason, code)
        }
        let odd = DirectStatus(message: ["direct": "open", "relay": "sideways", "relayReason": "gremlins"])
        XCTAssertEqual(odd?.relay, .down, "an unknown state counts as down")
        XCTAssertNil(odd?.relayReason)
    }

    /// Kept in the Keychain with the direct connection; an older stored value without it still reads.
    func testStoredWithTheDirectConnection() throws {
        let link = (v["links"] as! [[String: Any]])[0]["uri"] as! String
        let server = try RelayServer.parse(link, allowLocal: false, rendezvous: rendezvous).get()
        var direct = RemoteDirect()
        direct.relay = server
        direct.status = DirectStatus(availability: .blocked, reason: .carrierNAT, relay: .down, relayReason: .pin)
        let decoded = try JSONDecoder().decode(RemoteDirect.self, from: JSONEncoder().encode(direct))
        XCTAssertEqual(decoded, direct)
        let old = Data(#"{"endpoints":{},"status":{"availability":"blocked","reason":"carrierNAT"}}"#.utf8)
        let older = try JSONDecoder().decode(RemoteDirect.self, from: old)
        XCTAssertNil(older.relay)
        XCTAssertNil(older.status?.relay)
        XCTAssertEqual(server.checked, server)
        var broken = server
        broken.rendezvous = Data(count: 15)
        XCTAssertNil(broken.checked)
    }
}

/// The real connector with nothing listening at the server's port: it tries the server, says why it failed, and fails.
@MainActor
final class RelayConnectorTests: XCTestCase {
    func testAServerThatIsntThereIsReportedUnreachable() async throws {
        let connector = DirectConnector()
        var outcomes: [RelayAttempt.Outcome] = []
        connector.onRelayResult = { outcomes.append($0) }
        var target = ConnectTarget(deviceID: Data(count: 8), knownPeripheral: nil)
        let fp = Data(repeating: 1, count: 32).base64URL, k = Data(repeating: 2, count: 32).base64URL
        target.relay = try RelayServer.parse("pairline-relay://127.0.0.1:9?v=1&fp=\(fp)&k=\(k)", allowLocal: true).get()
        target.relayFirst = true
        let result: Result<(FrameTransport, UUID?), Error> = await withCheckedContinuation { continuation in
            connector.connect(target) { continuation.resume(returning: $0) }
        }
        guard case .failure(let error) = result, case ConnectError.failed = error else {
            return XCTFail("connected to nothing: \(result)")
        }
        XCTAssertEqual(outcomes, [.unreachable])
    }
}

private extension Result {
    var failure: Failure? {
        if case .failure(let error) = self { return error }
        return nil
    }
}
