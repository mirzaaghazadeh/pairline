import XCTest
@testable import Pairline

// Talk on iPhone's state from the bridge (`status.talk`) and `talk.setup` (D-107; PROTOCOL.md "Talk on iPhone's state").
@MainActor
final class TalkStateTests: XCTestCase {
    private func status(_ talk: String?) throws -> BridgeStatus {
        let field = talk.map { #","talk":\#($0)"# } ?? ""
        let json = #"{"t":"status","battery":{"level":80,"charging":false},"caps":["sms.send"]\#(field),"time":1}"#
        guard case .status(let status) = try Inbound.decode(Data(json.utf8)).message else {
            throw XCTSkip("not a status")
        }
        return status
    }

    func testEveryStateAndReason() throws {
        for state in TalkState.State.allCases {
            let talk = try status(#"{"state":"\#(state.rawValue)","reason":"wifi"}"#).talk
            XCTAssertEqual(talk?.state, state)
            // A reason counts only with `waiting` or `failed`.
            XCTAssertEqual(talk?.reason, state == .waiting || state == .failed ? .wifi : nil, state.rawValue)
        }
        for reason in TalkState.Reason.allCases {
            XCTAssertEqual(try status(#"{"state":"failed","reason":"\#(reason.rawValue)"}"#).talk?.reason, reason)
        }
    }

    func testUnknownValues() throws {
        // An unknown state reads as failed without a reason; an unknown reason as none.
        XCTAssertEqual(try status(#"{"state":"something_new","reason":"wifi"}"#).talk, TalkState(state: .failed))
        XCTAssertEqual(try status(#"{"state":"waiting","reason":"something_new"}"#).talk, TalkState(state: .waiting))
        XCTAssertEqual(try status(#"{"state":"waiting","reason":null}"#).talk, TalkState(state: .waiting))
        XCTAssertEqual(try status(#"{"state":"ready"}"#).talk, TalkState(state: .ready))
    }

    func testOlderBridgesAndMalformedValuesLeaveTheStatusIntact() throws {
        // No `talk`: an older bridge.
        let old = try status(nil)
        XCTAssertNil(old.talk)
        XCTAssertEqual(old.battery?.level, 80)
        // A `talk` that doesn't parse is dropped, never the whole status.
        for bad in ["null", #""ready""#, "42", #"{"reason":"wifi"}"#, #"{"state":7}"#] {
            let s = try status(bad)
            XCTAssertNil(s.talk, bad)
            XCTAssertEqual(s.caps, ["sms.send"], bad)
        }
    }

    func testStoredAndReadBack() throws {
        let talk = TalkState(state: .waiting, reason: .debugging)
        let data = try JSONEncoder().encode(BridgeStatus(caps: [], talk: talk))
        XCTAssertEqual(try JSONDecoder().decode(BridgeStatus.self, from: data).talk, talk)
    }

    func testSetupRequest() throws {
        let request = Outbound.talkSetup
        XCTAssertTrue(request.isRequest)
        let object = try JSONSerialization.jsonObject(with: request.json(id: 4)) as? [String: Any]
        XCTAssertEqual(object?["t"] as? String, "talk.setup")
        XCTAssertEqual(object?["id"] as? Int, 4)
        XCTAssertEqual(object?.count, 2)
    }

    func testWhatTheAppSays() {
        // Ready only with `call.media` listed: the capability decides.
        XCTAssertTrue(TalkPresentation(TalkState(state: .ready), callMedia: true).ready)
        XCTAssertNil(TalkPresentation(TalkState(state: .ready), callMedia: true).callLine)
        XCTAssertFalse(TalkPresentation(TalkState(state: .ready), callMedia: false).ready)
        XCTAssertNotNil(TalkPresentation(TalkState(state: .ready), callMedia: false).callLine)
        // A way to act only where a step on the Android phone is missing.
        XCTAssertEqual(TalkPresentation(TalkState(state: .setup), callMedia: false).action, .setUp)
        XCTAssertEqual(TalkPresentation(TalkState(state: .off), callMedia: false).action, .turnOn)
        XCTAssertEqual(TalkPresentation(TalkState(state: .failed, reason: .pairing), callMedia: false).action, .setUp)
        XCTAssertEqual(TalkPresentation(TalkState(state: .waiting, reason: .pairing), callMedia: false).action, .setUp)
        for state in [TalkState(state: .waiting, reason: .wifi), TalkState(state: .waiting, reason: .debugging),
                      TalkState(state: .failed, reason: .root), TalkState(state: .failed), TalkState(state: .starting),
                      TalkState(state: .unsupported)] {
            let p = TalkPresentation(state, callMedia: false)
            XCTAssertNil(p.action, "\(state)")
            XCTAssertNotNil(p.callLine, "\(state)")
            XCTAssertFalse(p.ready, "\(state)")
        }
    }

    func testDemoBridgeIsReady() async throws {
        let demo = DemoBridge(content: .english)
        let connected = expectation(description: "connected")
        demo.onEstablished = { connected.fulfill() }
        demo.start()
        await fulfillment(of: [connected], timeout: 3)
        let reply = try await demo.request(.statusGet)
        guard case .status(let s) = reply.message else { return XCTFail("not a status") }
        XCTAssertEqual(s.talk, TalkState(state: .ready))
        _ = try await demo.request(.talkSetup)
    }
}
