import XCTest
@testable import Pairline

// "Check my home connection" (`remote.check`, D-092) and the log's IP-address redaction (AGENTS.md rule 9).
final class HomeCheckTests: XCTestCase {
    private func decode(_ json: String) throws -> Inbound { try Inbound.decode(Data(json.utf8)) }

    func testRequestIsBareAndWaitsLongerThanUsual() throws {
        let request = Outbound.remoteCheck
        XCTAssertTrue(request.isRequest)
        XCTAssertNil(request.lane)
        XCTAssertEqual(request.timeout, 40)
        let object = try JSONSerialization.jsonObject(with: request.json(id: 9)) as? [String: Any]
        XCTAssertEqual(object?["t"] as? String, "remote.check")
        XCTAssertEqual(object?["id"] as? Int, 9)
        XCTAssertEqual(object?.count, 2)
    }

    func testLikelyReply() throws {
        let inbound = try decode(#"{"t":"remote.check","id":3,"direct":"likely","reason":null,"router":"nat-pmp","ipv6":true}"#)
        XCTAssertEqual(inbound.id, 3)
        guard case .remoteCheck(let reply) = inbound.message else { return XCTFail("not a remote.check") }
        XCTAssertEqual(reply, HomeCheckReply(likely: true, reason: nil, router: .natPMP, ipv6: true))
        XCTAssertNil(reply.consequence)
    }

    func testUnlikelyReplies() throws {
        let cases: [(String, DirectStatus.Reason?)] = [
            ("cgnat", .carrierNAT), ("no_public_address", .noPublicAddress), ("no_mapping", .router),
            ("not_wifi", .notOnWiFi), ("something_new", nil),
        ]
        for (code, expected) in cases {
            let inbound = try decode(#"{"t":"remote.check","id":4,"direct":"unlikely","reason":"\#(code)","router":null,"ipv6":false}"#)
            guard case .remoteCheck(let reply) = inbound.message else { return XCTFail("not a remote.check") }
            XCTAssertFalse(reply.likely)
            XCTAssertEqual(reply.reason, expected, code)
            XCTAssertNil(reply.router)
            XCTAssertEqual(reply.consequence == nil, expected == .notOnWiFi, code)
        }
    }

    func testMissingOrOddFieldsStillDecode() throws {
        let inbound = try decode(#"{"t":"remote.check","id":5,"direct":"maybe","router":"carrier-pigeon"}"#)
        guard case .remoteCheck(let reply) = inbound.message else { return XCTFail("not a remote.check") }
        XCTAssertEqual(reply, HomeCheckReply(likely: false, reason: nil, router: nil, ipv6: false))
    }

    func testRedactsIPAddresses() {
        let cases = [
            "Mapped 81.2.69.160:47830 for the iPhone": "Mapped [ip] for the iPhone",
            "Router 10.0.0.1": "Router [ip]",
            "Listening on [2a02:1234:5678:9abc::23]:47830": "Listening on [ip]",
            "From 2a02:1234:5678:9abc::23 now": "From [ip] now",
            "Gateway fe80::1%en0": "Gateway [ip]",
            "Full 2001:0db8:0000:0000:0000:ff00:0042:8329": "Full [ip]",
        ]
        for (line, expected) in cases {
            XCTAssertEqual(Redactor.redact(line), expected)
        }
    }

    func testKeepsWhatTheDirectConnectionLogsOnPurpose() {
        for line in [
            "Reached the bridge's IPv6 address",
            "Trying the direct connection (new addresses)",
            "Sending sms.send through iCloud",
            "iOS 27.2 on iPhone",
        ] {
            XCTAssertEqual(Redactor.redact(line), line)
        }
        let report = "2026-11-24T18:28:03Z INFO [direct] Couldn't reach the bridge's home; trying again later"
        XCTAssertEqual(Redactor.redactLines(report), report)
    }
}
