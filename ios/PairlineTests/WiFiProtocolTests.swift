import CryptoKit
import XCTest
@testable import Pairline

/// The M5 parts of protocol/test-vectors/v0.json (media replay window, bulk records, the Wi-Fi pair link) and the
/// Wi-Fi helpers around them.
final class WiFiProtocolTests: XCTestCase {
    private static let vectors: [String: Any] = {
        let url = Bundle(for: WiFiProtocolTests.self).url(forResource: "v0", withExtension: "json")!
        return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }()

    private var v: [String: Any] { Self.vectors }
    private func section(_ name: String) -> [String: Any] { v[name] as! [String: Any] }
    private func hex(_ value: Any?) -> Data { Data(hex: value as! String)! }
    private func sessionKey(_ name: String) -> Data { hex(section("session")[name]) }

    func testMediaReplayWindowVector() throws {
        let w = section("media_replay_window")
        XCTAssertEqual(w["size"] as? Int, Int(ReplayWindow.size))
        XCTAssertEqual(w["key"] as? String, "media_k_br")
        // The iPhone opens B→R media datagrams; its sealing key doesn't matter here.
        var crypto = MediaCrypto(sealKey: sessionKey("media_k_rb"), sealDirection: .remoteToBridge,
                                 openKey: sessionKey("media_k_br"), openDirection: .bridgeToRemote)
        for case let step as [String: Any] in w["steps"] as! [Any] {
            let counter = step["counter"] as! Int
            let plaintext = crypto.open(hex(step["wire"]))
            let accept = step["accept"] as! Bool
            XCTAssertEqual(plaintext != nil, accept, "counter \(counter)")
            if accept { XCTAssertEqual(plaintext?.hex, step["plaintext_hex"] as? String, "counter \(counter)") }
        }
    }

    func testMediaKeysFromTheSession() throws {
        let s = section("session")
        let keys = SessionKeys(okm: hex(s["okm"]))
        var r = MediaCrypto(keys: keys)
        // R's probe is the vector's media_k_rb record with counter 0 and an empty plaintext.
        let probe = (v["records"] as! [[String: Any]]).first { $0["key"] as? String == "media_k_rb" }!
        XCTAssertEqual(try r.seal(Data()).hex, probe["wire"] as? String)
        // R opens B's media record (counter 5).
        let fromB = (v["records"] as! [[String: Any]]).first { $0["key"] as? String == "media_k_br" }!
        XCTAssertEqual(r.open(hex(fromB["wire"]))?.hex, fromB["plaintext_hex"] as? String)
        XCTAssertNil(r.open(hex(fromB["wire"])), "a replayed datagram is dropped")
    }

    func testMediaCryptoLimits() throws {
        let keys = SessionKeys(okm: Data(repeating: 9, count: 64))
        var crypto = MediaCrypto(keys: keys)
        XCTAssertThrowsError(try crypto.seal(Data(count: MediaCrypto.maxPayload + 1)))
        let datagram = try crypto.seal(Data(count: MediaCrypto.maxPayload))
        XCTAssertEqual(datagram.count, 1224)
        XCTAssertNil(crypto.open(Data(count: 10)))
        XCTAssertNil(crypto.open(Data(count: 2000)))
    }

    func testReplayWindowSlides() {
        var window = ReplayWindow()
        XCTAssertTrue(window.mayAccept(100))
        window.mark(100)
        XCTAssertFalse(window.mayAccept(100))
        XCTAssertTrue(window.mayAccept(37))
        XCTAssertFalse(window.mayAccept(36))
        window.mark(500)
        XCTAssertFalse(window.mayAccept(100))
        XCTAssertTrue(window.mayAccept(499))
        window.mark(499)
        XCTAssertFalse(window.mayAccept(499))
        XCTAssertTrue(window.mayAccept(501))
    }

    func testBulkFileDataVector() throws {
        let b = section("bulk_file_data")
        let s = section("session")
        let keys = SessionKeys(okm: hex(s["okm"]))
        let bulk = keys.wifiKeys(.bulk)
        XCTAssertEqual(bulk.bridgeToRemote.hex, s["bulk_k_br"] as? String)
        let frame = try Frame.decode(hex(b["frame"]))
        XCTAssertEqual(frame.type, .bulk)
        var opener = RecordOpener(key: bulk.bridgeToRemote, direction: .bridgeToRemote, channel: .bulk)
        let (counter, plaintext) = try opener.open(frame.body)
        XCTAssertEqual(counter, 0)
        XCTAssertEqual(plaintext.hex, b["plaintext"] as? String)
        XCTAssertEqual(plaintext.first, 0x01)
        XCTAssertEqual(plaintext.readBigEndian(UInt32.self, at: 1), UInt32(b["fid"] as! Int))
        XCTAssertEqual(plaintext.readBigEndian(UInt64.self, at: 5), UInt64(b["offset"] as! Int))
        XCTAssertEqual(String(decoding: plaintext.dropFirst(13), as: UTF8.self), b["data_utf8"] as? String)
        XCTAssertEqual(TCPFraming.encode(hex(b["frame"])).hex, b["tcp"] as? String)
        var sealer = RecordSealer(key: bulk.bridgeToRemote, direction: .bridgeToRemote, channel: .bulk)
        XCTAssertEqual(Frame(type: .bulk, body: try sealer.seal(plaintext)).encoded.hex, b["frame"] as? String)
    }

    func testWiFiPairLinkVector() throws {
        let p = section("pair_uri_wifi")
        let link = try PairingLink(string: p["uri"] as! String)
        XCTAssertEqual(link.wifiAddresses, p["ip"] as? [String])
        XCTAssertEqual(link.wifiPort.map(Int.init), p["port"] as? Int)
        XCTAssertEqual(link.hotspotSSID, p["ssid"] as? String)
        XCTAssertEqual(link.hotspotPassword, p["pw"] as? String)
        XCTAssertTrue(link.hasWiFi)
        XCTAssertEqual(link.uri, p["uri"] as? String)
        XCTAssertFalse(link.redactedDescription.contains("192.168"))
        XCTAssertFalse(link.redactedDescription.contains(p["pw"] as! String))
        // Older links without the Wi-Fi parameters still parse.
        let plain = try PairingLink(string: section("pairing")["qr_uri"] as! String)
        XCTAssertFalse(plain.hasWiFi)
        XCTAssertNil(plain.hotspotSSID)
    }

    func testPairLinkDropsAddressesOutsideTheLocalNetwork() throws {
        let base = section("pairing")["qr_uri"] as! String
        let link = try PairingLink(string: base + "&ip=8.8.8.8,10.1.2.3,100.64.0.1,fe80::1,2001:db8::1,fd00::5&port=47820&future=1")
        XCTAssertEqual(link.wifiAddresses, ["10.1.2.3", "fe80::1", "fd00::5"])
    }

    func testLocalAddresses() {
        for allowed in ["10.0.0.1", "172.16.5.4", "172.31.255.255", "192.168.43.1", "169.254.1.1", "fd12::1", "fc00::",
                        "fe80::1", "fe80::1%en0", "::ffff:192.168.1.2"] {
            XCTAssertTrue(LocalAddress.isAllowed(allowed), allowed)
        }
        for refused in ["8.8.8.8", "172.32.0.1", "100.64.0.1", "127.0.0.1", "::1", "2001:db8::1", "ff02::fb", "",
                        "bridge.local", "192.168.1"] {
            XCTAssertFalse(LocalAddress.isAllowed(refused), refused)
        }
    }

    func testWiFiMessagesDecode() throws {
        let offer = try Inbound.decode(Data(#"{"t":"wifi.offer","ip":["192.168.1.5",7],"port":47820,"ssid":null,"password":null,"band":"5"}"#.utf8))
        guard case .wifiOffer(let o) = offer.message else { return XCTFail("not wifi.offer") }
        XCTAssertEqual(o.ip, ["192.168.1.5"])
        XCTAssertEqual(o.port, 47820)
        XCTAssertNil(o.ssid)
        let media = try Inbound.decode(Data(#"{"t":"wifi.media","port":47820}"#.utf8))
        guard case .wifiMedia(let port) = media.message else { return XCTFail("not wifi.media") }
        XCTAssertEqual(port, 47820)
        let none = try Inbound.decode(Data(#"{"t":"wifi.media","port":null}"#.utf8))
        guard case .wifiMedia(nil) = none.message else { return XCTFail("port should be nil") }
        let fileOffer = try Inbound.decode(Data(#"{"t":"file.offer","fid":4000000000,"name":"a.jpg","size":12,"type":"image/jpeg"}"#.utf8))
        guard case .fileOffer(let f) = fileOffer.message else { return XCTFail("not file.offer") }
        XCTAssertEqual(f.fid, 4_000_000_000)
        XCTAssertEqual(f.size, 12)
    }

    func testDeviceInfoListsFiles() throws {
        let json = try Outbound.deviceInfo(name: "iPhone", os: "17", app: "1.0").json(id: nil)
        let object = try JSONSerialization.jsonObject(with: json) as! [String: Any]
        XCTAssertEqual(object["caps"] as? [String], ["files"])
    }

    func testSafeNames() {
        XCTAssertEqual(FileTransferManager.safeName("../../etc/passwd"), "etcpasswd")
        XCTAssertEqual(FileTransferManager.safeName(".hidden"), "hidden")
        XCTAssertEqual(FileTransferManager.safeName("a\u{0}b\nc.txt"), "abc.txt")
        XCTAssertEqual(FileTransferManager.safeName(""), "file")
        XCTAssertEqual(FileTransferManager.safeName("Résumé 📄.pdf"), "Résumé 📄.pdf")
        XCTAssertLessThanOrEqual(FileTransferManager.safeName(String(repeating: "ü", count: 300)).utf8.count, 255)
    }
}
