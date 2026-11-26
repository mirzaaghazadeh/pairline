import CryptoKit
import XCTest
@testable import Pairline

/// Reproduces every value in protocol/test-vectors/v1-remote.json with CryptoKit (PROTOCOL.md, "Remote mode (v1,
/// D-072)"). The identity keys sB and sR come from v0.json.
final class RemoteVectorTests: XCTestCase {
    private static func load(_ name: String) -> [String: Any] {
        let url = Bundle(for: RemoteVectorTests.self).url(forResource: name, withExtension: "json")!
        return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }

    private static let vectors = load("v1-remote")
    private static let v0Keys = load("v0")["keys"] as! [String: Any]

    private var v: [String: Any] { Self.vectors }

    private func section(_ name: String) -> [String: Any] { v[name] as! [String: Any] }

    private func hex(_ value: Any?) -> Data { Data(hex: value as! String)! }

    private func ms(_ value: Any?) -> UInt64 { UInt64(value as! Int) }

    private func utf8(_ value: Any?) -> Data { Data((value as! String).utf8) }

    private func scalar(_ value: Any?) -> P256.KeyAgreement.PrivateKey {
        try! P256.KeyAgreement.PrivateKey(rawRepresentation: hex(value))
    }

    private var sB: P256.KeyAgreement.PrivateKey { scalar((Self.v0Keys["sB"] as! [String: Any])["private"]) }
    private var sR: P256.KeyAgreement.PrivateKey { scalar((Self.v0Keys["sR"] as! [String: Any])["private"]) }

    /// The invite records' times aren't separate fields in the vectors; gen_vectors_remote.py uses these offsets from
    /// `mailbox_b_to_r.time_ms`.
    private var baseTime: UInt64 { ms(section("mailbox_b_to_r")["time_ms"]) }

    func testVersionAndIdentityKeys() {
        XCTAssertEqual(v["version"] as? Int, Int(MailboxHeader.version))
        XCTAssertEqual(sB.publicKeyX963.hex, section("keys")["sB_public"] as? String)
        XCTAssertEqual(sR.publicKeyX963.hex, section("keys")["sR_public"] as? String)
    }

    // MARK: Kind 1

    func testMailboxBridgeToRemote() throws {
        let m = section("mailbox_b_to_r")
        let e1 = scalar(m["ephemeral_private"])
        XCTAssertEqual(e1.publicKeyX963.hex, m["ephemeral_public"] as? String)

        let header = try MailboxHeader(kind: .sealed, direction: .bridgeToRemote, recordID: hex(m["record_id"]),
                                       time: ms(m["time_ms"]), ephemeralPublicKey: e1.publicKeyX963)
        XCTAssertEqual(header.encoded.hex, m["header"] as? String)
        XCTAssertEqual(header.encoded.count, MailboxHeader.sealedLength)
        XCTAssertEqual(header.recordName, m["record_name"] as? String)
        XCTAssertEqual(try MailboxHeader(parsing: header.encoded), header)

        let ikm = try MailboxCrypto.senderIKM(ephemeral: e1, identity: sB, recipient: sR.publicKey)
        XCTAssertEqual(ikm.hex, m["ikm"] as? String)
        XCTAssertEqual(try MailboxCrypto.recipientIKM(identity: sR, ephemeral: e1.publicKey, sender: sB.publicKey), ikm)
        XCTAssertEqual(MailboxCrypto.salt(header: header.encoded).hex, m["salt"] as? String)
        XCTAssertEqual(MailboxCrypto.recordKey(ikm: ikm, header: header.encoded).hex, m["key"] as? String)

        let plaintext = utf8(m["plaintext_utf8"])
        let sealed = try MailboxCrypto.seal(plaintext, identity: sB, recipient: sR.publicKey, direction: .bridgeToRemote,
                                            time: ms(m["time_ms"]), recordID: hex(m["record_id"]), ephemeral: e1)
        XCTAssertEqual(sealed.hex, m["sealed"] as? String)
        XCTAssertEqual(sealed.base64URL, m["sealed_b64u"] as? String)
        XCTAssertEqual(try MailboxHeader(parsing: sealed), header)

        let opened = try MailboxCrypto.open(sealed, identity: sR, sender: sB.publicKey, direction: .bridgeToRemote)
        XCTAssertEqual(opened.plaintext, plaintext)
        XCTAssertEqual(opened.header, header)
        // The direction must be the peer's, and a kind 1 record is never an invitation record.
        XCTAssertThrowsError(try MailboxCrypto.open(sealed, identity: sR, sender: sB.publicKey,
                                                    direction: .remoteToBridge)) {
            XCTAssertEqual($0 as? RemoteProtocolError, .unexpectedRecord)
        }
        XCTAssertThrowsError(try MailboxCrypto.openInvite(sealed, secret: hex(section("invite")["invite_secret"]),
                                                          direction: .bridgeToRemote)) {
            XCTAssertEqual($0 as? RemoteProtocolError, .unexpectedRecord)
        }
    }

    func testMailboxRemoteToBridge() throws {
        let m = section("mailbox_r_to_b")
        let plaintext = utf8(m["plaintext_utf8"])
        let sealed = try MailboxCrypto.seal(plaintext, identity: sR, recipient: sB.publicKey, direction: .remoteToBridge,
                                            time: ms(m["time_ms"]), recordID: hex(m["record_id"]),
                                            ephemeral: scalar(m["ephemeral_private"]))
        XCTAssertEqual(sealed.hex, m["sealed"] as? String)

        let opened = try MailboxCrypto.open(hex(m["sealed"]), identity: sB, sender: sR.publicKey,
                                            direction: .remoteToBridge)
        XCTAssertEqual(String(data: opened.plaintext, encoding: .utf8), m["plaintext_utf8"] as? String)
        XCTAssertEqual(opened.header.kind, .sealed)
        XCTAssertEqual(opened.header.direction, .remoteToBridge)
        XCTAssertEqual(opened.header.recordID, hex(m["record_id"]))
        XCTAssertEqual(opened.header.time, ms(m["time_ms"]))
        XCTAssertEqual(opened.header.ephemeralPublicKey, scalar(m["ephemeral_private"]).publicKeyX963)
    }

    func testMailboxMustFail() {
        for case let c as [String: Any] in v["mailbox_must_fail"] as! [Any] {
            let sender = c["use_sender_public"].map { try! CryptoPrimitives.publicKey(x963: hex($0)) } ?? sB.publicKey
            XCTAssertThrowsError(try MailboxCrypto.open(hex(c["sealed"]), identity: sR, sender: sender,
                                                        direction: .bridgeToRemote), c["why"] as! String) {
                XCTAssertEqual($0 as? RemoteProtocolError, .decryptFailed)
            }
        }
    }

    func testMalformedRecords() {
        let good = hex(section("mailbox_b_to_r")["sealed"])
        func changed(_ index: Int, _ value: UInt8) -> Data {
            var data = good
            data[index] = value
            return data
        }
        let malformed: [(String, Data)] = [
            ("empty", Data()),
            ("version 0", changed(0, 0x00)),
            ("version 2", changed(0, 0x02)),
            ("unknown kind", changed(1, 0x03)),
            ("unknown direction", changed(2, 0x41)),
            ("compressed ephemeral key", changed(27, 0x02)),
            ("ephemeral key off the curve", changed(91, good[91] ^ 0x01)),
            ("header only", good.prefix(MailboxHeader.sealedLength)),
            ("shorter than a tag", good.prefix(MailboxHeader.sealedLength + MailboxCrypto.tagLength - 1)),
        ]
        for (why, data) in malformed {
            XCTAssertThrowsError(try MailboxCrypto.open(data, identity: sR, sender: sB.publicKey,
                                                        direction: .bridgeToRemote), why) {
                XCTAssertEqual($0 as? RemoteProtocolError, .malformedRecord, why)
            }
        }
        // A well-formed header of the other kind.
        XCTAssertThrowsError(try MailboxCrypto.open(changed(1, 0x02), identity: sR, sender: sB.publicKey,
                                                    direction: .bridgeToRemote)) {
            XCTAssertEqual($0 as? RemoteProtocolError, .unexpectedRecord)
        }
        // Headers that can't be built.
        let id = Data(count: MailboxHeader.recordIDLength)
        XCTAssertThrowsError(try MailboxHeader(kind: .invite, direction: .bridgeToRemote, recordID: id.dropLast(), time: 0))
        XCTAssertThrowsError(try MailboxHeader(kind: .sealed, direction: .bridgeToRemote, recordID: id, time: 0))
        XCTAssertThrowsError(try MailboxHeader(kind: .invite, direction: .bridgeToRemote, recordID: id, time: 0,
                                               ephemeralPublicKey: sB.publicKeyX963))
    }

    func testFreshEphemeralKeyAndRecordIDPerRecord() throws {
        let plaintext = Data(#"{"t":"remote.token","token":"x"}"#.utf8)
        let a = try MailboxCrypto.seal(plaintext, identity: sR, recipient: sB.publicKey, direction: .remoteToBridge,
                                       time: baseTime)
        let b = try MailboxCrypto.seal(plaintext, identity: sR, recipient: sB.publicKey, direction: .remoteToBridge,
                                       time: baseTime)
        let headerA = try MailboxHeader(parsing: a)
        let headerB = try MailboxHeader(parsing: b)
        XCTAssertNotEqual(headerA.ephemeralPublicKey, headerB.ephemeralPublicKey)
        XCTAssertNotEqual(headerA.recordID, headerB.recordID)
        for sealed in [a, b] {
            XCTAssertEqual(try MailboxCrypto.open(sealed, identity: sB, sender: sR.publicKey,
                                                  direction: .remoteToBridge).plaintext, plaintext)
        }
    }

    func testPlaintextLimit() throws {
        let limit = MailboxCrypto.maxPlaintext
        let sealed = try MailboxCrypto.seal(Data(count: limit), identity: sR, recipient: sB.publicKey,
                                            direction: .remoteToBridge, time: baseTime)
        XCTAssertEqual(try MailboxCrypto.open(sealed, identity: sB, sender: sR.publicKey,
                                              direction: .remoteToBridge).plaintext.count, limit)
        XCTAssertThrowsError(try MailboxCrypto.seal(Data(count: limit + 1), identity: sR, recipient: sB.publicKey,
                                                    direction: .remoteToBridge, time: baseTime)) {
            XCTAssertEqual($0 as? RemoteProtocolError, .plaintextTooLong)
        }
        XCTAssertThrowsError(try MailboxCrypto.sealInvite(Data(count: limit + 1), secret: Data(count: 32),
                                                          direction: .bridgeToRemote, time: baseTime))
    }

    // MARK: Remote pairing

    func testInviteKeysCommitAndCode() {
        let i = section("invite")
        let keys = InviteKeys(secret: hex(i["invite_secret"]))
        XCTAssertEqual(keys.bridgeToRemote.hex, i["k_br"] as? String)
        XCTAssertEqual(keys.remoteToBridge.hex, i["k_rb"] as? String)
        XCTAssertEqual(RemotePairingCrypto.commit(remoteKey: sR.publicKeyX963, bridgeKey: sB.publicKeyX963,
                                                  nonceB: hex(i["nonce_b"])).hex, i["commit"] as? String)
        XCTAssertEqual(RemotePairingCrypto.code(secret: hex(i["invite_secret"]), remoteKey: sR.publicKeyX963,
                                                bridgeKey: sB.publicKeyX963, nonceR: hex(i["nonce_r"]),
                                                nonceB: hex(i["nonce_b"])), i["code"] as? String)
    }

    func testCodeIsAlwaysSixDigits() {
        var sawLeadingZero = false
        for n in 0..<64 {
            let code = RemotePairingCrypto.code(secret: Data(count: 32), remoteKey: sR.publicKeyX963,
                                                bridgeKey: sB.publicKeyX963, nonceR: Data(repeating: UInt8(n), count: 32),
                                                nonceB: Data(count: 32))
            XCTAssertEqual(code.count, 6)
            XCTAssertTrue(code.allSatisfy { ("0"..."9").contains($0) }, code)
            sawLeadingZero = sawLeadingZero || code.hasPrefix("0")
        }
        XCTAssertTrue(sawLeadingZero)
    }

    func testInviteRecords() throws {
        let i = section("invite")
        let secret = hex(i["invite_secret"])
        let records: [(name: String, direction: Direction, time: UInt64)] = [
            ("hello", .bridgeToRemote, baseTime), ("nonce", .remoteToBridge, baseTime + 2000),
            ("reveal", .bridgeToRemote, baseTime + 4000),
        ]
        var bodies: [String: [String: String]] = [:]
        for record in records {
            let json = utf8(i["\(record.name)_json"])
            let recordID = hex(i["\(record.name)_record_id"])
            let sealed = try MailboxCrypto.sealInvite(json, secret: secret, direction: record.direction,
                                                      time: record.time, recordID: recordID)
            XCTAssertEqual(sealed.hex, i["\(record.name)_sealed"] as? String, record.name)

            let opened = try MailboxCrypto.openInvite(hex(i["\(record.name)_sealed"]), secret: secret,
                                                      direction: record.direction)
            XCTAssertEqual(opened.plaintext, json, record.name)
            XCTAssertEqual(opened.header.kind, .invite)
            XCTAssertEqual(opened.header.encoded.count, MailboxHeader.baseLength)
            XCTAssertEqual(opened.header.recordID, recordID)
            XCTAssertEqual(opened.header.time, record.time)
            bodies[record.name] = try JSONSerialization.jsonObject(with: opened.plaintext) as? [String: String]

            let other: Direction = record.direction == .bridgeToRemote ? .remoteToBridge : .bridgeToRemote
            XCTAssertThrowsError(try MailboxCrypto.openInvite(sealed, secret: secret, direction: other)) {
                XCTAssertEqual($0 as? RemoteProtocolError, .unexpectedRecord)
            }
            var wrongSecret = secret
            wrongSecret[0] ^= 0x01
            XCTAssertThrowsError(try MailboxCrypto.openInvite(sealed, secret: wrongSecret, direction: record.direction)) {
                XCTAssertEqual($0 as? RemoteProtocolError, .decryptFailed)
            }
            XCTAssertThrowsError(try MailboxCrypto.open(sealed, identity: sR, sender: sB.publicKey,
                                                        direction: record.direction))
        }

        // The bodies carry what R needs: B's key and device id, the commitment, then the nonces that open it.
        let hello = try XCTUnwrap(bodies["hello"])
        XCTAssertEqual(hello["t"], "rpair.hello")
        XCTAssertEqual(hello["k"].flatMap(Data.init(base64URL:)), sB.publicKeyX963)
        XCTAssertEqual(hello["id"].flatMap(Data.init(base64URL:))?.hex, i["device_id"] as? String)
        XCTAssertEqual(bodies["nonce"]?["n"].flatMap(Data.init(base64URL:))?.hex, i["nonce_r"] as? String)
        let nonceB = try XCTUnwrap(bodies["reveal"]?["n"].flatMap(Data.init(base64URL:)))
        XCTAssertEqual(hello["c"].flatMap(Data.init(base64URL:)),
                       RemotePairingCrypto.commit(remoteKey: sR.publicKeyX963, bridgeKey: sB.publicKeyX963, nonceB: nonceB))
    }

    func testConfirmRecord() throws {
        let i = section("invite")
        let confirm = Data(#"{"t":"rpair.confirm"}"#.utf8)
        let sealed = try MailboxCrypto.seal(confirm, identity: sB, recipient: sR.publicKey, direction: .bridgeToRemote,
                                            time: ms(i["confirm_time_ms"]), recordID: hex(i["confirm_record_id"]),
                                            ephemeral: scalar(i["confirm_ephemeral_private"]))
        XCTAssertEqual(sealed.hex, i["confirm_sealed"] as? String)
        let opened = try MailboxCrypto.open(hex(i["confirm_sealed"]), identity: sR, sender: sB.publicKey,
                                            direction: .bridgeToRemote)
        XCTAssertEqual(opened.plaintext, confirm)
        XCTAssertEqual(opened.header.time, ms(i["confirm_time_ms"]))
    }

    // MARK: Invitation link

    private func vectorInvitation() throws -> RemoteInvitation {
        let i = section("invite")
        let mb = i["mailboxes"] as! [String: String]
        // The backend and environment are only in the vector's "uri" (b=public, e=development).
        return try RemoteInvitation(remoteKey: sR.publicKeyX963, secret: hex(i["invite_secret"]), backend: .public,
                                    environment: .development, token: i["token"] as! String,
                                    mailboxes: RemoteMailboxes(toBridge: mb["b"]!, alert: mb["a"]!, quiet: mb["q"]!),
                                    expiry: UInt64(i["expiry"] as! Int))
    }

    func testInvitationLinkBuildsAndParses() throws {
        let i = section("invite")
        let uri = i["uri"] as! String
        let invitation = try vectorInvitation()
        XCTAssertEqual(invitation.uri, uri)

        let parsed = try RemoteInvitation(string: uri)
        XCTAssertEqual(parsed, invitation)
        XCTAssertEqual(parsed.remoteKey, sR.publicKeyX963)
        XCTAssertEqual(parsed.secret.hex, i["invite_secret"] as? String)
        XCTAssertEqual(parsed.backend, .public)
        XCTAssertEqual(parsed.environment, .development)
        XCTAssertEqual(parsed.token, i["token"] as? String)
        XCTAssertEqual(parsed.mailboxes.toBridge, (i["mailboxes"] as! [String: String])["b"])
        XCTAssertEqual(parsed.mailboxes.alert, (i["mailboxes"] as! [String: String])["a"])
        XCTAssertEqual(parsed.mailboxes.quiet, (i["mailboxes"] as! [String: String])["q"])
        XCTAssertEqual(parsed.expiry, UInt64(i["expiry"] as! Int))

        // Unknown parameters are ignored; the other backend and environment round-trip.
        XCTAssertEqual(try RemoteInvitation(string: uri + "&z=1"), invitation)
        let other = try RemoteInvitation(remoteKey: invitation.remoteKey, secret: invitation.secret, backend: .private,
                                         environment: .production, token: "167__54__xyz~.-_+/=",
                                         mailboxes: invitation.mailboxes, expiry: 1)
        XCTAssertEqual(try RemoteInvitation(string: other.uri), other)

        // Never logged with its secret or token.
        for text in [parsed.redactedDescription, "\(parsed)", String(reflecting: parsed)] {
            XCTAssertFalse(text.contains(parsed.secret.base64URL), text)
            XCTAssertFalse(text.contains("ABCdef"), text)
        }
    }

    func testInvitationLinkRejectsBadInput() throws {
        let i = section("invite")
        let good = i["uri"] as! String
        let mb = i["mailboxes"] as! [String: String]
        let key = sR.publicKeyX963.base64URL
        let secret = hex(i["invite_secret"]).base64URL
        func replacing(_ old: String, _ new: String) -> String {
            XCTAssertTrue(good.contains(old), old)
            return good.replacingOccurrences(of: old, with: new)
        }
        func without(_ name: String) -> String {
            let parts = good.components(separatedBy: "?")
            return parts[0] + "?" + parts[1].components(separatedBy: "&").filter { !$0.hasPrefix(name + "=") }
                .joined(separator: "&")
        }
        let offCurve = Data([0x04] + [UInt8](repeating: 1, count: 64)).base64URL
        var bad: [String] = [
            "", "https://example.com/invite?v=1", replacing("pairline://invite?", "pairline://pair?"),
            replacing("pairline://invite?", "pairline://invite/x?"), good.components(separatedBy: "?")[0],
            replacing("?v=1&", "?v=0&"), replacing("?v=1&", "?v=2&"),
            replacing("&k=\(key)&", "&k=\(Data(sR.publicKeyX963.dropLast()).base64URL)&"),
            replacing("&k=\(key)&", "&k=\(offCurve)&"),
            replacing("&k=\(key)&", "&k=\(sR.publicKeyX963.base64EncodedString())&"),
            replacing("&s=\(secret)&", "&s=\(Data(hex(i["invite_secret"]).dropLast()).base64URL)&"),
            replacing("&s=\(secret)&", "&s=\(secret)=&"),
            replacing("&b=public&", "&b=Public&"), replacing("&b=public&", "&b=shared&"),
            replacing("&e=development&", "&e=Production&"), replacing("&e=development&", "&e=sandbox&"),
            replacing("&t=167__54__", "&t=%20167__54__"),
            replacing("&a=\(mb["a"]!)&", "&a=\(mb["a"]!.uppercased())&"),
            replacing("&a=\(mb["a"]!)&", "&a=zz\(mb["a"]!.dropFirst(2))&"),
            replacing("&a=\(mb["a"]!)&", "&a=\(mb["a"]!.dropLast())&"),
            replacing("&q=\(mb["q"]!)&", "&q=\(mb["a"]!)&"),
            replacing("&x=1795700600", "&x=-1795700600"), replacing("&x=1795700600", "&x=+1795700600"),
            replacing("&x=1795700600", "&x=1795700600.5"), replacing("&x=1795700600", "&x=99999999999999999999"),
            replacing("&x=1795700600", "&x="),
            good + "&k=\(key)", good + "&v=1",
        ]
        bad += ["v", "k", "s", "b", "e", "t", "m", "a", "q", "x"].map(without)
        for input in bad {
            XCTAssertThrowsError(try RemoteInvitation(string: input), input) {
                XCTAssertEqual($0 as? RemoteProtocolError, .invalidInvitation, input)
            }
        }

        let invitation = try vectorInvitation()
        XCTAssertThrowsError(try RemoteInvitation(remoteKey: invitation.remoteKey, secret: invitation.secret,
                                                  backend: .public, environment: .development, token: "",
                                                  mailboxes: invitation.mailboxes, expiry: 1))
    }
}
