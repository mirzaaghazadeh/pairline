import XCTest
@testable import Pairline

/// Call-audio packets and the jitter buffer (PROTOCOL.md "Call audio", D-047). Mirrors the bridge's AudioFramesTest.
final class CallAudioTests: XCTestCase {
    private func frame(_ value: Int16) -> [Int16] { [Int16](repeating: value, count: AudioPackets.frameSamples) }

    func testPacketRoundTripAndLayout() throws {
        let packet = AudioPackets.encode(sequence: 0xBEEF, frame: frame(1234))
        XCTAssertEqual(packet.count, 3 + 640)
        XCTAssertEqual([UInt8](packet.prefix(3)), [0x01, 0xBE, 0xEF])
        // Samples are little-endian: 1234 = 0x04D2.
        XCTAssertEqual([UInt8](packet[3..<5]), [0xD2, 0x04])
        let decoded = try XCTUnwrap(AudioPackets.decode(packet))
        XCTAssertEqual(decoded.sequence, 0xBEEF)
        XCTAssertEqual(decoded.frame, frame(1234))
    }

    func testMalformedPacketsAreDropped() {
        XCTAssertNil(AudioPackets.decode(Data(count: 10)))
        var wrongKind = AudioPackets.encode(sequence: 1, frame: frame(0))
        wrongKind[0] = 0x7F
        XCTAssertNil(AudioPackets.decode(wrongKind))
    }

    func testBuffersThenPlaysInOrder() {
        let jb = JitterBuffer(target: 3)
        jb.put(11, frame(11))
        XCTAssertNil(jb.take())
        jb.put(10, frame(10))
        jb.put(12, frame(12))
        XCTAssertEqual(jb.take(), frame(10))
        XCTAssertEqual(jb.take(), frame(11))
        XCTAssertEqual(jb.take(), frame(12))
    }

    func testLostFrameIsConcealedThenSilenced() {
        let jb = JitterBuffer(target: 1)
        jb.put(0, frame(1000))
        XCTAssertEqual(jb.take(), frame(1000))
        XCTAssertEqual(jb.take(), frame(500))
        XCTAssertEqual(jb.take(), frame(250))
        XCTAssertNil(jb.take())
    }

    func testSequenceWrapsAndLateFramesAreIgnored() {
        let jb = JitterBuffer(target: 2)
        jb.put(65535, frame(1))
        jb.put(0, frame(2))
        XCTAssertEqual(jb.take(), frame(1))
        XCTAssertEqual(jb.take(), frame(2))
        jb.put(65535, frame(9))
        jb.put(1, frame(3))
        XCTAssertEqual(jb.take(), frame(3))
    }

    func testBurstAfterStallDoesNotBuildDelay() {
        let jb = JitterBuffer(target: 3, capacity: 6)
        for s in UInt16(0)..<3 { jb.put(s, frame(Int16(s))) }
        _ = jb.take()
        for s in UInt16(3)..<20 { jb.put(s, frame(Int16(s))) }
        XCTAssertEqual(jb.take(), frame(17))
    }

    func testCallMediaMessages() throws {
        let start = try JSONSerialization.jsonObject(with: Outbound.callMediaStart(.string("c1"), talk: false).json(id: 7))
            as? [String: Any]
        XCTAssertEqual(start?["t"] as? String, "call.media.start")
        XCTAssertEqual(start?["call"] as? String, "c1")
        XCTAssertEqual(start?["talk"] as? Bool, false)
        let event = try Inbound.decode(Data(#"{"t":"call.media.state","call":"c1","state":"active","listen":true,"talk":true}"#.utf8))
        guard case .callMediaState(let state) = event.message else { return XCTFail("not decoded") }
        XCTAssertEqual(state.state, "active")
        XCTAssertEqual(state.talk, true)
    }
}
