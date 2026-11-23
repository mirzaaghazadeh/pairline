import CryptoKit
import XCTest
@testable import Pairline

/// Reproduces every value in protocol/test-vectors/v0.json with CryptoKit (PROTOCOL.md, "Test vectors").
final class ProtocolVectorTests: XCTestCase {
    private static let vectors: [String: Any] = {
        let bundle = Bundle(for: ProtocolVectorTests.self)
        let url = bundle.url(forResource: "v0", withExtension: "json")!
        return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }()

    private var v: [String: Any] { Self.vectors }

    private func section(_ name: String) -> [String: Any] { v[name] as! [String: Any] }

    private func hex(_ value: Any?) -> Data { Data(hex: value as! String)! }

    private func key(_ name: String) -> P256.KeyAgreement.PrivateKey {
        let keys = section("keys")[name] as! [String: Any]
        return try! P256.KeyAgreement.PrivateKey(rawRepresentation: hex(keys["private"]))
    }

    private func sessionKey(_ name: String) -> Data { hex(section("session")[name]) }

    func testVersion() {
        XCTAssertEqual(v["version"] as? Int, Int(WireProtocol.version))
    }

    func testHKDFRFC5869Case1() {
        let c = section("hkdf_rfc5869_case1")
        let okm = CryptoPrimitives.hkdf(ikm: hex(c["ikm"]), salt: hex(c["salt"]), info: hex(c["info"]),
                                        length: c["length"] as! Int)
        XCTAssertEqual(okm.hex, c["okm"] as? String)
    }

    func testPublicKeysFromPrivateScalars() {
        for name in ["sB", "sR", "eB", "eR"] {
            let expected = (section("keys")[name] as! [String: Any])["public"] as! String
            XCTAssertEqual(key(name).publicKeyX963.hex, expected, name)
            XCTAssertEqual(key(name).publicKeyX963.count, 65)
        }
    }

    func testECDH() throws {
        let ecdh = section("ecdh")
        XCTAssertEqual(try key("eB").sharedSecret(with: key("eR").publicKey).hex, ecdh["eB_eR"] as? String)
        XCTAssertEqual(try key("sB").sharedSecret(with: key("eR").publicKey).hex, ecdh["sB_eR"] as? String)
        XCTAssertEqual(try key("eB").sharedSecret(with: key("sR").publicKey).hex, ecdh["eB_sR"] as? String)
        // R's side computes the same values.
        XCTAssertEqual(try key("eR").sharedSecret(with: key("eB").publicKey).hex, ecdh["eB_eR"] as? String)
        XCTAssertEqual(try key("eR").sharedSecret(with: key("sB").publicKey).hex, ecdh["sB_eR"] as? String)
        XCTAssertEqual(try key("sR").sharedSecret(with: key("eB").publicKey).hex, ecdh["eB_sR"] as? String)
    }

    func testPairingLinkParsesAndRoundTrips() throws {
        let p = section("pairing")
        let link = try PairingLink(string: p["qr_uri"] as! String)
        XCTAssertEqual(link.deviceID.hex, p["device_id"] as? String)
        XCTAssertEqual(link.bridgeKey, key("sB").publicKeyX963)
        XCTAssertEqual(link.secret.hex, p["pairing_secret"] as? String)
        XCTAssertEqual(link.uri, p["qr_uri"] as? String)
        XCTAssertFalse(link.redactedDescription.contains(link.secret.base64URL))
    }

    func testPairingLinkRejectsBadInput() {
        let good = section("pairing")["qr_uri"] as! String
        let bad = [
            "", "https://example.com", good.replacingOccurrences(of: "v=0", with: "v=1"),
            good.replacingOccurrences(of: "pairline://pair", with: "pairline://other"),
            good.replacingOccurrences(of: "&s=", with: "&x="),
            "pairline://pair?v=0&id=AAAA&k=AAAA&s=AAAA",
            // A point that isn't on the curve.
            "pairline://pair?v=0&id=obLD1OX2Bxg&k=" + Data([0x04] + [UInt8](repeating: 1, count: 64)).base64URL
                + "&s=RckDaSOwfAzcD1hyQhuXOt7zUoCYIJD-JzmMNjaFz9s",
        ]
        for input in bad {
            XCTAssertThrowsError(try PairingLink(string: input), input)
        }
    }

    func testPairingTranscriptAndMACs() {
        let p = section("pairing")
        let transcript = PairingCrypto.transcript(bridgeKey: key("sB").publicKeyX963, remoteKey: key("sR").publicKeyX963,
                                                  nonceR: hex(p["nonce_r"]))
        XCTAssertEqual(transcript.hex, p["transcript_t"] as? String)
        let secret = hex(p["pairing_secret"])
        XCTAssertEqual(PairingCrypto.remoteMAC(secret: secret, transcript: transcript).hex, p["mac_r"] as? String)
        XCTAssertEqual(PairingCrypto.bridgeMAC(secret: secret, transcript: transcript, nonceB: hex(p["nonce_b"])).hex,
                       p["mac_b"] as? String)
        XCTAssertTrue(PairingCrypto.verifyBridgeMAC(hex(p["mac_b"]), secret: secret, transcript: transcript,
                                                    nonceB: hex(p["nonce_b"])))
        var tampered = hex(p["mac_b"])
        tampered[0] ^= 1
        XCTAssertFalse(PairingCrypto.verifyBridgeMAC(tampered, secret: secret, transcript: transcript,
                                                     nonceB: hex(p["nonce_b"])))
    }

    func testPairingJSONBodies() throws {
        let p = section("pairing")
        let expectedHello = p["pair_hello_json"] as! [String: Any]
        let hello = PairHelloMessage(k: key("sR").publicKeyX963.base64URL, n: hex(p["nonce_r"]).base64URL,
                                     mac: hex(p["mac_r"]).base64URL)
        let encoded = try JSONSerialization.jsonObject(with: JSONEncoder.wire.encode(hello)) as! [String: Any]
        XCTAssertEqual(encoded as NSDictionary, expectedHello as NSDictionary)

        let okData = try JSONSerialization.data(withJSONObject: p["pair_ok_json"]!)
        let ok = try JSONDecoder().decode(PairOKMessage.self, from: okData)
        XCTAssertEqual(Data(base64URL: ok.n)?.hex, p["nonce_b"] as? String)
        XCTAssertEqual(Data(base64URL: ok.mac)?.hex, p["mac_b"] as? String)
    }

    func testHelloBodies() throws {
        let s = section("session")
        let helloR = Hello(role: WireProtocol.roleRemote, ephemeralPublicKey: key("eR").publicKeyX963,
                           random: hex(s["rand_r"]))
        let helloB = Hello(role: WireProtocol.roleBridge, ephemeralPublicKey: key("eB").publicKeyX963,
                           random: hex(s["rand_b"]))
        XCTAssertEqual(helloR.encoded.hex, s["hello_r"] as? String)
        XCTAssertEqual(helloB.encoded.hex, s["hello_b"] as? String)
        XCTAssertEqual(helloR.encoded.count, 99)
        XCTAssertEqual(Frame(type: .hello, body: helloR.encoded).encoded.hex, s["hello_r_frame"] as? String)
        XCTAssertEqual(try Hello.decode(helloB.encoded, expectedRole: WireProtocol.roleBridge), helloB)
        XCTAssertThrowsError(try Hello.decode(helloB.encoded, expectedRole: WireProtocol.roleRemote))
        XCTAssertThrowsError(try Hello.decode(helloB.encoded.dropLast(), expectedRole: WireProtocol.roleBridge))
        var wrongVersion = helloB.encoded
        wrongVersion[0] = 1
        XCTAssertThrowsError(try Hello.decode(wrongVersion, expectedRole: WireProtocol.roleBridge))
    }

    func testSessionKeys() throws {
        let s = section("session")
        let helloR = hex(s["hello_r"])
        let helloB = hex(s["hello_b"])
        XCTAssertEqual(SessionKeys.salt(helloR: helloR, helloB: helloB).hex, s["salt"] as? String)

        let ikmR = try Handshake.remoteIKM(ephemeral: key("eR"), identity: key("sR"), bridgeEphemeral: key("eB").publicKey,
                                           bridgeIdentity: key("sB").publicKey)
        let ikmB = try Handshake.bridgeIKM(ephemeral: key("eB"), identity: key("sB"), remoteEphemeral: key("eR").publicKey,
                                           remoteIdentity: key("sR").publicKey)
        XCTAssertEqual(ikmR.hex, s["ikm"] as? String)
        XCTAssertEqual(ikmB, ikmR)

        let keys = SessionKeys.derive(ikm: ikmR, helloR: helloR, helloB: helloB)
        XCTAssertEqual(keys.okm.hex, s["okm"] as? String)
        XCTAssertEqual(keys.bridgeToRemote.hex, s["k_br"] as? String)
        XCTAssertEqual(keys.remoteToBridge.hex, s["k_rb"] as? String)

        let media = keys.wifiKeys(.media)
        XCTAssertEqual(media.bridgeToRemote.hex, s["media_k_br"] as? String)
        XCTAssertEqual(media.remoteToBridge.hex, s["media_k_rb"] as? String)
        let bulk = keys.wifiKeys(.bulk)
        XCTAssertEqual(bulk.bridgeToRemote.hex, s["bulk_k_br"] as? String)
        XCTAssertEqual(bulk.remoteToBridge.hex, s["bulk_k_rb"] as? String)
    }

    func testConfirmRecords() throws {
        let s = section("session")
        var r = RecordSealer(key: sessionKey("k_rb"), direction: .remoteToBridge)
        XCTAssertEqual(try r.seal(Data()).hex, s["confirm_r_to_b"] as? String)
        var b = RecordSealer(key: sessionKey("k_br"), direction: .bridgeToRemote)
        XCTAssertEqual(try b.seal(Data()).hex, s["confirm_b_to_r"] as? String)

        var opener = RecordOpener(key: sessionKey("k_br"), direction: .bridgeToRemote)
        let (counter, plaintext) = try opener.open(hex(s["confirm_b_to_r"]))
        XCTAssertEqual(counter, 0)
        XCTAssertTrue(plaintext.isEmpty)
    }

    func testRecords() throws {
        for case let record as [String: Any] in v["records"] as! [Any] {
            let keyData = sessionKey(record["key"] as! String)
            let direction = Direction(rawValue: UInt32(record["direction"] as! Int))!
            let channel = Channel(rawValue: UInt8(record["channel"] as! Int))!
            let counter = UInt64(record["counter"] as! Int)
            let plaintext: Data
            if let utf8 = record["plaintext_utf8"] as? String {
                plaintext = Data(utf8.utf8)
            } else {
                plaintext = hex(record["plaintext_hex"])
            }
            XCTAssertEqual(Record.nonce(direction: direction, counter: counter).hex, record["nonce"] as? String)
            XCTAssertEqual(Record.associatedData(channel: channel).hex, record["ad"] as? String)
            let wire = try Record.seal(key: keyData, direction: direction, counter: counter, channel: channel,
                                       plaintext: plaintext)
            XCTAssertEqual(wire.hex, record["wire"] as? String)
            let opened = try Record.open(key: keyData, direction: direction, channel: channel, wire: wire)
            XCTAssertEqual(opened.counter, counter)
            XCTAssertEqual(opened.plaintext, plaintext)
        }
    }

    func testRecordsThatMustFail() {
        for case let record as [String: Any] in v["must_fail"] as! [Any] {
            let direction = Direction(rawValue: UInt32(record["direction"] as! Int))!
            let channel = Channel(rawValue: UInt8(record["channel"] as! Int))!
            XCTAssertThrowsError(try Record.open(key: sessionKey(record["key"] as! String), direction: direction,
                                                 channel: channel, wire: hex(record["wire"])),
                                 record["why"] as! String)
        }
    }

    func testStatusRecordDecodesAsMessage() throws {
        let record = (v["records"] as! [[String: Any]])[0]
        let opened = try Record.open(key: sessionKey("k_br"), direction: .bridgeToRemote, channel: .control,
                                     wire: hex(record["wire"]))
        let inbound = try Inbound.decode(opened.plaintext)
        guard case .status(let status) = inbound.message else { return XCTFail("not a status") }
        XCTAssertEqual(status.battery?.level, 82)
        XCTAssertEqual(status.battery?.charging, false)
        XCTAssertEqual(status.app, "0.1.0")
    }

    func testBLEFragments() throws {
        let f = section("framing")
        let frame = hex(f["frame"])
        let fragments = BLEFragmenter.fragments(of: frame, attPayload: f["ble_att_payload"] as! Int)
        XCTAssertEqual(fragments.map(\.hex), f["ble_fragments"] as? [String])
        let single = BLEFragmenter.fragments(of: frame, attPayload: f["ble_single_fragment_att_payload"] as! Int)
        XCTAssertEqual(single.map(\.hex), f["ble_single_fragment"] as? [String])

        var reassembler = BLEReassembler()
        var out: Data?
        for (index, fragment) in fragments.enumerated() {
            out = try reassembler.feed(fragment)
            XCTAssertEqual(out == nil, index < fragments.count - 1)
        }
        XCTAssertEqual(out, frame)
        XCTAssertEqual(try reassembler.feed(single[0]), frame)
    }

    func testTCPFraming() throws {
        let f = section("framing")
        let frame = hex(f["frame"])
        XCTAssertEqual(TCPFraming.encode(frame).hex, f["tcp"] as? String)
        var deframer = TCPDeframer()
        let wire = hex(f["tcp"])
        // Byte by byte, then two frames at once.
        var frames: [Data] = []
        for byte in wire { frames += try deframer.feed(Data([byte])) }
        XCTAssertEqual(frames, [frame])
        XCTAssertEqual(try deframer.feed(wire + wire), [frame, frame])
        var bad = TCPDeframer()
        XCTAssertThrowsError(try bad.feed(Data([0, 1, 0, 0, 0])))
    }

    func testFrameDecode() throws {
        let f = section("framing")
        let frame = try Frame.decode(hex(f["frame"]))
        XCTAssertEqual(frame.type, .record)
        XCTAssertThrowsError(try Frame.decode(Data([0x7f, 1, 2])))
        XCTAssertThrowsError(try Frame.decode(Data()))
    }
}
