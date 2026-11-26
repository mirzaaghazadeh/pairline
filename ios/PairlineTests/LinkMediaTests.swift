import CryptoKit
import XCTest
@testable import Pairline

/// Call audio inside the Bluetooth session (D-072): `link_media` in protocol/test-vectors/v0.json, the session's
/// `MEDIA` frames, the channel's batching, and iOS's Opus codec.
final class LinkMediaTests: XCTestCase {
    private static let vectors: [String: Any] = {
        let url = Bundle(for: LinkMediaTests.self).url(forResource: "v0", withExtension: "json")!
        return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }()

    private var v: [String: Any] { Self.vectors["link_media"] as! [String: Any] }
    private func hex(_ value: Any?) -> Data { Data(hex: value as! String)! }
    private func sessionKey(_ name: String) -> Data { hex((Self.vectors["session"] as! [String: Any])[name]) }
    private func hexList(_ name: String) -> [Data] { (v[name] as! [String]).map { Data(hex: $0)! } }

    // MARK: - Vectors

    func testKeysComeFromTheSession() {
        let keys = SessionKeys(okm: sessionKey("okm")).linkMediaKeys()
        XCTAssertEqual(keys.bridgeToRemote, sessionKey("link_media_k_br"))
        XCTAssertEqual(keys.remoteToBridge, sessionKey("link_media_k_rb"))
    }

    func testPacksAndUnpacksTheVectorsPackets() {
        let packets = hexList("packets_b_to_r")
        XCTAssertEqual(LinkMediaPackets.pack(packets), hex(v["plaintext_b_to_r"]))
        XCTAssertEqual(LinkMediaPackets.unpack(hex(v["plaintext_b_to_r"])), packets)
        XCTAssertEqual(LinkMediaPackets.pack(hexList("packets_r_to_b")), hex(v["plaintext_r_to_b"]))
    }

    func testMalformedRecordsAreIgnored() {
        let good = hex(v["plaintext_b_to_r"])
        XCTAssertNil(LinkMediaPackets.unpack(good + Data([0])), "a trailing byte")
        XCTAssertNil(LinkMediaPackets.unpack(good.dropLast()), "cut short")
        XCTAssertNil(LinkMediaPackets.unpack(Data([0, 2, 2, 0])), "a packet below 3 bytes")
        XCTAssertNil(LinkMediaPackets.unpack(Data()), "empty")
        XCTAssertNil(LinkMediaPackets.unpack(Data(count: 1201)), "over 1200 bytes")
    }

    func testTheVectorsFramesSealAndOpen() throws {
        var sealer = RecordSealer(key: sessionKey("link_media_k_rb"), direction: .remoteToBridge, channel: .linkMedia)
        let frame = Frame(type: .media, body: try sealer.seal(hex(v["plaintext_r_to_b"])))
        XCTAssertEqual(frame.encoded, hex(v["frame_r_to_b"]))

        let fromB = try Frame.decode(hex(v["frame_b_to_r"]))
        XCTAssertEqual(fromB.type, .media)
        var opener = RecordOpener(key: sessionKey("link_media_k_br"), direction: .bridgeToRemote, channel: .linkMedia)
        let (counter, plaintext) = try opener.open(fromB.body)
        XCTAssertEqual(counter, UInt64(v["counter_b_to_r"] as! Int))
        XCTAssertEqual(plaintext, hex(v["plaintext_b_to_r"]))
        XCTAssertThrowsError(try opener.open(fromB.body), "a replay")

        let fragments = BLEFragmenter.fragments(of: fromB.encoded, attPayload: v["ble_att_payload"] as! Int)
        XCTAssertEqual(fragments, hexList("ble_fragments_b_to_r"))
        XCTAssertEqual(TCPFraming.encode(fromB.encoded), hex(v["tcp_b_to_r"]))
    }

    func testMustFailEntries() {
        for entry in v["must_fail"] as! [[String: Any]] {
            let channel = Channel(rawValue: UInt8(entry["channel"] as! Int))!
            XCTAssertThrowsError(try Record.open(key: sessionKey(entry["key"] as! String), direction: .bridgeToRemote,
                                                 channel: channel, wire: hex(entry["wire"])), entry["why"] as! String)
        }
    }

    // MARK: - Session and channel

    private var keep: [AnyObject] = []

    /// An established session over a loopback transport of `kind`, with the in-test bridge on the other end.
    private func established(kind: TransportKind = .tcp) -> (LinkSession, TestBridge) {
        let identity = P256.KeyAgreement.PrivateKey()
        let bridgeIdentity = P256.KeyAgreement.PrivateKey()
        let (r, b) = LoopbackTransport.pair(kind: kind)
        let bridge = TestBridge(transport: b, identity: bridgeIdentity)
        bridge.pinnedRemote = identity.publicKeyX963
        let session = LinkSession(transport: r, identity: identity,
                                  pinnedBridgeKey: bridgeIdentity.publicKey.x963Representation, pairing: nil)
        keep += [session, bridge, r, b]
        let up = expectation(description: "established")
        session.onEstablished = { up.fulfill() }
        session.start()
        wait(for: [up], timeout: 2)
        return (session, bridge)
    }

    private func packet(_ sequence: UInt16, size: Int = 80) -> Data {
        AudioPackets.encodeOpus(sequence: sequence, opus: Data(repeating: UInt8(sequence & 0xFF), count: size))
    }

    func testMediaFramesBothWaysInsideTheSession() {
        let (session, bridge) = established()
        XCTAssertTrue(session.carriesLinkMedia)
        let channel = LinkMediaChannel(session: session)
        session.onLinkMedia = { channel.receive($0) }

        let got = expectation(description: "two packets from the bridge")
        got.expectedFulfillmentCount = 2
        var packets: [Data] = []
        channel.onPacket = { p in
            DispatchQueue.main.async {
                packets.append(p)
                got.fulfill()
            }
        }
        bridge.sendMedia(LinkMediaPackets.pack([packet(1), packet(2)]))
        wait(for: [got], timeout: 2)
        XCTAssertEqual(packets, [packet(1), packet(2)])

        // Three packets sent before the main queue runs go out together, in order.
        let sent = expectation(description: "the bridge got the iPhone's packets")
        bridge.onMedia = { _ in sent.fulfill() }
        for s in 10..<13 { channel.send(packet(UInt16(s))) }
        wait(for: [sent], timeout: 2)
        XCTAssertEqual(bridge.receivedMedia.count, 1)
        XCTAssertEqual(LinkMediaPackets.unpack(bridge.receivedMedia[0]), [packet(10), packet(11), packet(12)])
        XCTAssertEqual(channel.framesSent, 1)
        XCTAssertEqual(channel.packetsSent, 3)
    }

    func testAudioOlderThan200msIsDropped() {
        let (session, bridge) = established()
        let channel = LinkMediaChannel(session: session)
        let sent = expectation(description: "sent")
        bridge.onMedia = { _ in sent.fulfill() }
        for s in 0..<15 { channel.send(packet(UInt16(s))) }
        wait(for: [sent], timeout: 2)
        XCTAssertEqual(channel.packetsDropped, 5)
        XCTAssertEqual(LinkMediaPackets.unpack(bridge.receivedMedia[0]), (5..<15).map { packet(UInt16($0)) })
    }

    func testAClosedChannelSendsNothing() {
        let (session, _) = established()
        let channel = LinkMediaChannel(session: session)
        channel.close()
        XCTAssertFalse(channel.isOpen)
        channel.send(packet(1))
        XCTAssertNil(channel.nextFrame())
    }

    func testAReplayedMediaFrameClosesTheSession() {
        let (session, bridge) = established()
        let closed = expectation(description: "closed")
        session.onClose = { error in
            XCTAssertEqual(error, .protocolViolation("media"))
            closed.fulfill()
        }
        let wire = bridge.sendMedia(LinkMediaPackets.pack([packet(1)]))
        bridge.sendRawFrame(Frame(type: .media, body: wire).encoded)
        wait(for: [closed], timeout: 2)
    }

    func testWiFiSessionsDontTakeMediaFrames() {
        let (session, bridge) = established(kind: .wifi)
        XCTAssertFalse(session.carriesLinkMedia)
        XCTAssertThrowsError(try session.sealLinkMedia(Data([2, 0, 0])))
        let closed = expectation(description: "closed")
        session.onClose = { _ in closed.fulfill() }
        bridge.sendMedia(LinkMediaPackets.pack([packet(1)]))
        wait(for: [closed], timeout: 2)
    }

    func testCallMediaStartAsksForTheBluetoothLaneOnlyForLink() throws {
        let link = Outbound.callMediaStart(.string("c1"), talk: true, via: .link)
        XCTAssertEqual(link.lane, .primary)
        let object = try JSONSerialization.jsonObject(with: link.json(id: 3)) as! [String: Any]
        XCTAssertEqual(object["via"] as? String, "link")
        XCTAssertNil(Outbound.callMediaStart(.string("c1"), talk: true).lane)
    }

    // MARK: - Opus (iOS's own codec)

    private func tone(frame index: Int, hertz: Double = 440) -> [Int16] {
        (0..<Opus.frameSamples).map { i in
            let t = Double(index * Opus.frameSamples + i) / Opus.sampleRate
            return Int16(8_000 * sin(2 * .pi * hertz * t))
        }
    }

    func testOpusRoundTripKeepsTheVoice() throws {
        let encoder = try OpusEncoder()
        let decoder = try OpusDecoder()
        var sizes: [Int] = []
        var decoded: [[Int16]] = []
        for i in 0..<50 {
            guard let opus = encoder.encode(tone(frame: i)) else { continue }
            sizes.append(opus.count)
            let packet = AudioPackets.encodeOpus(sequence: UInt16(i), opus: opus)
            let (sequence, back) = try XCTUnwrap(AudioPackets.decodeOpus(packet))
            XCTAssertEqual(sequence, UInt16(i))
            if let frame = decoder.decode(back) { decoded.append(frame) }
        }
        XCTAssertGreaterThanOrEqual(sizes.count, 45, "one packet per 20 ms after a short warm-up")
        XCTAssertLessThanOrEqual(sizes.max() ?? 0, AudioPackets.maxOpusBytes)
        let average = sizes.reduce(0, +) / max(sizes.count, 1)
        XCTAssertLessThan(average, 160, "about 32 kbit/s is 80 bytes a frame; got \(average)")
        XCTAssertGreaterThanOrEqual(decoded.count, 40)
        // After the codec's start the tone comes back at about its level (8000 peak → RMS ≈ 5657).
        let level = decoded.suffix(20).map(JitterBuffer.rms).reduce(0, +) / 20
        XCTAssertGreaterThan(level, 3_000, "decoded level \(level)")
        XCTAssertLessThan(level, 9_000, "decoded level \(level)")
    }

    func testOpusDecoderRefusesGarbageGracefully() throws {
        let decoder = try OpusDecoder()
        _ = decoder.decode(Data(repeating: 0xFF, count: 10)) // must not crash; may conceal or return nil
        XCTAssertNil(decoder.decode(Data()))
        XCTAssertNil(decoder.decode(Data(count: AudioPackets.maxOpusBytes + 1)))
    }

    func testOpusPacketsInTheAudioFormat() {
        let packet = AudioPackets.encodeOpus(sequence: 0xFFFF, opus: Data([1, 2, 3]))
        XCTAssertEqual(packet, Data([0x02, 0xFF, 0xFF, 1, 2, 3]))
        XCTAssertNil(AudioPackets.decode(packet), "not PCM")
        XCTAssertNil(AudioPackets.decodeOpus(Data([0x02, 0, 1])), "empty")
        XCTAssertNil(AudioPackets.decodeOpus(Data([0x01, 0, 1, 5])), "another kind")
    }
}
