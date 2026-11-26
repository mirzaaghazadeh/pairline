import CryptoKit
import XCTest
@testable import Pairline

// Remote mode's direct connection (D-092) on the iPhone: which addresses it takes, when the direct lane runs (a nearby
// session beats it, it beats the iCloud mailbox), its retries, and that nothing but an authenticated `remote.addr` of
// the paired bridge makes the app dial anywhere.

private let v4 = WiFiEndpoint(host: "86.12.34.56", port: 47830)
private let v6 = WiFiEndpoint(host: "2a02:1234::5", port: 47830)
private let addr: [String: Any] = ["t": "remote.addr", "v4": "86.12.34.56:47830", "v6": "[2a02:1234::5]:47830"]

private func json(_ object: [String: Any]) -> Data { try! JSONSerialization.data(withJSONObject: object) }

// MARK: - Addresses

final class DirectAddressTests: XCTestCase {
    func testAcceptsPublicAddresses() {
        XCTAssertEqual(DirectAddress.v4("86.12.34.56:47830"), v4)
        XCTAssertEqual(DirectAddress.v6("[2a02:1234::5]:47830"), v6)
        XCTAssertEqual(DirectAddress.v6("[2A02:1234:0:0:0:0:0:5]:1"), WiFiEndpoint(host: "2a02:1234::5", port: 1))
        // Just outside the ranges that are refused.
        for text in ["1.1.1.1:65535", "100.63.255.255:80", "100.128.0.1:80", "172.15.255.255:80", "172.32.0.1:80",
                     "11.0.0.1:80", "126.255.255.255:80", "128.0.0.1:80", "169.253.0.1:80", "192.0.1.1:80",
                     "198.20.0.1:80", "223.255.255.254:80"] {
            XCTAssertNotNil(DirectAddress.v4(text), text)
        }
        for text in ["[2001:4860:4860::8888]:443", "[2600::1]:443", "[2001:200::1]:443", "[3fff:1000::1]:443"] {
            XCTAssertNotNil(DirectAddress.v6(text), text)
        }
    }

    func testRefusesIPv4ThatIsntPublic() {
        for text in ["10.0.0.1:47830", "172.16.5.4:47830", "172.31.255.255:47830", "192.168.1.20:47830",
                     "100.64.0.1:47830", "100.127.255.255:47830", "127.0.0.1:47830", "169.254.1.1:47830",
                     "224.0.0.1:47830", "239.255.255.250:47830", "240.0.0.1:47830", "255.255.255.255:47830",
                     "0.0.0.0:47830", "0.1.2.3:47830", "192.0.0.8:47830", "192.0.2.1:47830", "198.51.100.7:47830",
                     "203.0.113.5:47830", "198.18.0.1:47830", "198.19.255.255:47830", "192.88.99.1:47830"] {
            XCTAssertNil(DirectAddress.v4(text), text)
        }
    }

    func testRefusesIPv6ThatIsntGlobal() {
        for text in ["[fe80::1]:47830", "[fe80::1%en0]:47830", "[fd12::1]:47830", "[fc00::1]:47830", "[::1]:47830",
                     "[::]:47830", "[ff02::1]:47830", "[2001:db8::5]:47830", "[3fff::1]:47830", "[2001::1]:47830",
                     "[2001:2::1]:47830", "[2002:c000:204::1]:47830", "[::ffff:86.12.34.56]:47830",
                     "[64:ff9b::5602:2238]:47830", "[4000::1]:47830"] {
            XCTAssertNil(DirectAddress.v6(text), text)
        }
    }

    func testRefusesAnythingMalformed() {
        let v4Texts = ["", "86.12.34.56", "86.12.34.56:", "86.12.34.56:0", "86.12.34.56:65536", "86.12.34.56:0443",
                       "86.12.34.56:+443", "86.12.34.56:443:1", "86.12.34:443", "86.12.34.56.7:443", "086.12.34.56:443",
                       "86.12.34.256:443", "86.12.34.-1:443", "bridge.example.com:443", "localhost:443",
                       " 86.12.34.56:443", "86.12.34.56:443 ", "[86.12.34.56]:443", "86.12.34.56:4 43",
                       "８６.12.34.56:443", "86.12.34.56:٤٤٣"]
        for text in v4Texts { XCTAssertNil(DirectAddress.v4(text), text) }
        let v6Texts = ["", "2a02:1234::5:443", "[2a02:1234::5]", "[2a02:1234::5]:", "[2a02:1234::5]:0",
                       "[2a02:1234::5]:70000", "[2a02:1234::5]443", "[[2a02:1234::5]]:443", "[example.com]:443",
                       "[2a02:1234::5]:443x", "[2a02:1234::5%25en0]:443", "[ 2a02:1234::5]:443", "[]:443",
                       "86.12.34.56:443", "[2a02:1234::5]:443:1"]
        for text in v6Texts { XCTAssertNil(DirectAddress.v6(text), text) }
    }

    func testOnlyCheckedIPLiteralsAreDialable() {
        XCTAssertNotNil(DirectAddress.dialable(v4))
        XCTAssertNotNil(DirectAddress.dialable(v6))
        for endpoint in [WiFiEndpoint(host: "192.168.1.20", port: 47820), WiFiEndpoint(host: "127.0.0.1", port: 47830),
                         WiFiEndpoint(host: "bridge.example.com", port: 443), WiFiEndpoint(host: "fe80::1%en0", port: 1),
                         WiFiEndpoint(host: "86.12.34.56", port: 0)] {
            XCTAssertNil(DirectAddress.dialable(endpoint), endpoint.host)
        }
    }

    func testRemoteAddrBody() {
        XCTAssertEqual(DirectEndpoints(message: addr).candidates, [v6, v4], "IPv6 first")
        XCTAssertEqual(DirectEndpoints(message: ["v4": "86.12.34.56:47830", "v6": NSNull()]).candidates, [v4])
        XCTAssertEqual(DirectEndpoints(message: ["v4": "10.0.0.2:47830", "v6": "[2a02:1234::5]:47830"]).candidates, [v6])
        XCTAssertTrue(DirectEndpoints(message: ["v4": 1234, "v6": ["2a02:1234::5"]]).isEmpty)
        XCTAssertTrue(DirectEndpoints(message: ["v4": "[2a02:1234::5]:47830", "v6": "86.12.34.56:47830"]).isEmpty,
                      "each family in its own field")
        XCTAssertTrue(DirectEndpoints(message: [:]).isEmpty)
        XCTAssertEqual(DirectEndpoints(message: addr).summary, "IPv6 + IPv4", "the log never sees the addresses")
    }

    func testRemoteStateBody() {
        XCTAssertEqual(DirectStatus(message: ["direct": "open", "reason": "carrier-grade NAT"]),
                       DirectStatus(availability: .open, reason: nil))
        let reasons: [(String, DirectStatus.Reason?)] = [
            // What the Android bridge sends (`DirectReason` in link/remote/DirectLink.kt).
            ("not on Wi-Fi", .notOnWiFi),
            ("carrier-grade NAT", .carrierNAT),
            ("double NAT: the router's internet address is private", .noPublicAddress),
            ("the router has no public internet address", .noPublicAddress),
            ("no port mapping on this router", .router),
            ("no port mapping on this router; IPv6 without a confirmed firewall opening", .router),
            ("couldn't listen on this phone", nil),
            // Other wordings.
            ("WAN address 100.64.3.2", .carrierNAT),
            ("CGNAT", .carrierNAT),
            ("router answers none of NAT-PMP, PCP or UPnP IGD", .router),
            ("on mobile data", .notOnWiFi),
            ("something else", nil),
        ]
        for (reason, expected) in reasons {
            let status = DirectStatus(message: ["direct": "none", "reason": reason])
            XCTAssertEqual(status?.availability, .unavailable)
            XCTAssertEqual(status?.reason, expected, reason)
        }
        XCTAssertEqual(DirectStatus(message: ["direct": "blocked"]), DirectStatus(availability: .blocked, reason: nil))
        XCTAssertNil(DirectStatus(message: ["direct": "maybe"]))
        XCTAssertNil(DirectStatus(message: ["direct": 1]))
        XCTAssertNil(DirectStatus(message: [:]))
    }

    func testRetrySchedule() {
        XCTAssertEqual((0..<6).map(LinkManager.directRetryDelay(attempt:)), [5, 15, 30, 120, 120, 120])
    }

    /// The real connector: nothing but public IP literals in `direct`; Wi-Fi hints are never its business.
    func testTheConnectorDialsNothingElse() {
        let connector = DirectConnector()
        var target = ConnectTarget(deviceID: Data(count: 8), knownPeripheral: nil)
        target.wifi = [v4]
        target.direct = [WiFiEndpoint(host: "192.168.1.20", port: 47820), WiFiEndpoint(host: "127.0.0.1", port: 47830),
                         WiFiEndpoint(host: "bridge.example.com", port: 443), WiFiEndpoint(host: "fe80::1%en0", port: 1)]
        var result: Result<(FrameTransport, UUID?), Error>?
        connector.connect(target) { result = $0 }
        guard case .failure(let error)? = result, case ConnectError.failed = error else {
            return XCTFail("dialed something: \(String(describing: result))")
        }
    }
}

// MARK: - The direct lane

@MainActor
final class DirectLaneTests: XCTestCase {
    private let suite = "DirectLaneTests"
    private let prefix = "test.direct."
    private var defaults: UserDefaults!
    private var identity: IdentityStore!
    private var peers: PeerStore!
    private let bridgeKey = P256.KeyAgreement.PrivateKey()
    private var mailbox: FakeMailbox!
    private var remote: RemoteLink!
    private var manager: LinkManager!
    private var primaryConnector: FakeConnector!
    private var directConnector: FakeConnector!
    private var primaryBridge: TestBridge?
    private var directBridge: TestBridge?
    /// The key whatever answers at the direct address holds (another bridge's, for a wrong-key handshake).
    private var directAnswersWith: P256.KeyAgreement.PrivateKey?
    private var onMessage: ((TestBridge, [String: Any]) -> Void)?

    override func setUp() async throws {
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
        identity = IdentityStore(prefix: prefix)
        identity.reset()
        _ = try identity.identity()
        peers = PeerStore(prefix: prefix)
        peers.save(PairedPeer(deviceID: Data(repeating: 9, count: 8), publicKey: bridgeKey.publicKey.x963Representation,
                              pairedAt: Date(), name: nil, peripheralID: nil, transport: "remote", wifi: nil))
        let store = RemoteStore(defaults: defaults, prefix: prefix)
        store.clear()
        store.save(RemoteSettings(on: true, backend: .public, environment: .development, mailboxes: .random(),
                                  tokenSentAt: Date()))
        mailbox = FakeMailbox()
        let identity = identity!, peers = peers!, mailbox = mailbox!
        remote = RemoteLink(store: store, inbox: FakeInbox(), identity: { try identity.identity() },
                            bridgeKey: { peers.peer?.publicKey }, makeMailbox: { _, _ in mailbox },
                            fetchToken: { _ in "token" })
        UserDefaults.standard.set(TransportKind.tcp.rawValue, forKey: DebugSettings.transportKey)
        directAnswersWith = nil
        onMessage = nil
        // Bluetooth (here the debug TCP lane) can't reach the bridge until a test says so.
        primaryConnector = FakeConnector(kind: .tcp, enabled: false) { [unowned self] in self.makeBridge(.tcp) }
        directConnector = FakeConnector(kind: .direct) { [unowned self] in self.makeBridge(.direct) }
        let wifiConnector = FakeConnector(kind: .wifi, enabled: false) { LoopbackTransport.pair(kind: .wifi).0 }
        manager = LinkManager(identityStore: identity, peerStore: peers,
                              deviceInfo: .deviceInfo(name: "iPhone", os: "18", app: "1.0"), remote: remote) { [unowned self] kind in
            switch kind {
            case .direct: return self.directConnector
            case .wifi: return wifiConnector
            default: return self.primaryConnector
            }
        }
        manager.setAllowed(true)
    }

    override func tearDown() async throws {
        manager.setAllowed(false)
        await remote.waitForReset()
        RemoteStore(defaults: defaults, prefix: prefix).clear()
        identity.reset()
        peers.clear()
        defaults.removePersistentDomain(forName: suite)
        UserDefaults.standard.removeObject(forKey: DebugSettings.transportKey)
    }

    /// Each connection reaches a fresh bridge endpoint; a direct one "dialed" the first address it was given (the
    /// user's port forwarding first when the target says so).
    private func makeBridge(_ kind: TransportKind) -> LoopbackTransport {
        let (r, b) = LoopbackTransport.pair(kind: kind)
        let bridge = TestBridge(transport: b, identity: kind == .direct ? directAnswersWith ?? bridgeKey : bridgeKey)
        bridge.pinnedRemote = identity.publicKey
        bridge.onMessage = { [weak self, weak bridge] object in
            guard let self, let bridge else { return }
            self.onMessage?(bridge, object)
        }
        if kind == .direct {
            if let target = directConnector.targets.last {
                r.dialed = target.directManualFirst ? (target.directManual ?? target.direct.first)
                                                    : (target.direct.first ?? target.directManual)
            }
            directBridge = bridge
        } else {
            primaryBridge = bridge
        }
        return r
    }

    private var phoneKey: P256.KeyAgreement.PublicKey {
        try! CryptoPrimitives.publicKey(x963: identity.publicKey!)
    }

    /// A message from the bridge through the iCloud mailbox, sealed with `sender`'s key (the paired bridge's by
    /// default), handled by a catch-up.
    private func mail(_ message: [String: Any], from sender: P256.KeyAgreement.PrivateKey? = nil) async {
        let sealed = try! MailboxCrypto.seal(json(message), identity: sender ?? bridgeKey, recipient: phoneKey,
                                             direction: .bridgeToRemote, time: UInt64(Date().milliseconds))
        mailbox.put(sealed, in: .quiet)
        await manager.remoteCatchUp()
    }

    private func waitFor(_ what: String, timeout: TimeInterval = 3, _ condition: () -> Bool) async {
        let end = Date().addingTimeInterval(timeout)
        while !condition(), Date() < end {
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertTrue(condition(), what)
    }

    func testWithoutAddressesTheMailboxIsTheWay() async {
        await mail(["t": "remote.state", "direct": "none", "reason": "carrier-grade NAT"])
        XCTAssertEqual(manager.phase, .remote)
        XCTAssertFalse(manager.isDirect)
        XCTAssertTrue(directConnector.targets.isEmpty)
        XCTAssertEqual(remote.direct.status, DirectStatus(availability: .unavailable, reason: .carrierNAT))
    }

    func testRemoteAddrBringsUpTheDirectSession() async throws {
        await mail(addr)
        await waitFor("direct session") { manager.isDirect }
        XCTAssertEqual(directConnector.targets.first?.direct, [v6, v4])
        XCTAssertEqual(manager.phase, .connected)
        XCTAssertEqual(manager.activeTransports, [.direct])
        XCTAssertEqual(remote.direct.endpoints, DirectEndpoints(v4: v4, v6: v6))
        // Requests go over it like over Wi-Fi, not through the mailbox.
        onMessage = { bridge, object in
            guard object["t"] as? String == "status.get", let id = object["id"] as? Int else { return }
            bridge.send(["t": "status", "id": id, "battery": ["level": 64, "charging": true]])
        }
        let written = mailbox.sent.count
        let reply = try await manager.request(.statusGet)
        guard case .status(let status) = reply.message else { return XCTFail("not status") }
        XCTAssertEqual(status.battery?.level, 64)
        XCTAssertFalse(reply.viaMailbox)
        XCTAssertEqual(mailbox.sent.count, written)
        // The addresses outlive a restart (in the Keychain).
        XCTAssertEqual(RemoteStore(defaults: defaults, prefix: prefix).direct.endpoints, DirectEndpoints(v4: v4, v6: v6))
    }

    func testANearbyLinkBeatsTheDirectOneWhichComesBackWhenItEnds() async {
        await mail(addr)
        await waitFor("direct") { manager.isDirect }
        primaryConnector.enable()
        await waitFor("the nearby link replaces it") { manager.activeTransports == [.tcp] }
        XCTAssertEqual(manager.phase, .connected)
        XCTAssertFalse(manager.isDirect)
        XCTAssertEqual(remote.directCandidates, [v6, v4], "closed on purpose: its addresses stay")
        let attempts = directConnector.targets.count
        // The nearby link ends: the direct connection is tried again at once, without its backoff.
        primaryConnector.enabled = false
        primaryBridge?.transport.close()
        await waitFor("direct again") { manager.isDirect }
        XCTAssertEqual(directConnector.targets.count, attempts + 1)
    }

    /// Away from home, call audio rides inside the direct session as `MEDIA` frames (D-092), both ways, and
    /// `call.media.start` with `via: "link"` goes on that session; a nearby link takes the call audio over.
    func testCallAudioRidesTheDirectSessionUntilANearbyLinkTakesOver() async throws {
        await mail(addr)
        await waitFor("direct") { manager.isDirect }
        XCTAssertEqual(manager.linkMediaLane, .direct)
        let channel = try XCTUnwrap(manager.linkMediaChannel)
        let fromIPhone = Data(repeating: 0x11, count: 40)
        channel.send(fromIPhone)
        await waitFor("the bridge got the iPhone's audio") { directBridge?.receivedMedia.isEmpty == false }
        XCTAssertEqual(directBridge?.receivedMedia.first.flatMap(LinkMediaPackets.unpack), [fromIPhone])
        let fromBridge = Data(repeating: 0x22, count: 40)
        let heard = expectation(description: "the iPhone got the bridge's audio")
        channel.onPacket = { if $0 == fromBridge { heard.fulfill() } }
        directBridge?.sendMedia(LinkMediaPackets.pack([fromBridge]))
        await fulfillment(of: [heard], timeout: 3)
        var startedOnDirect: Bool?
        onMessage = { [unowned self] bridge, object in
            guard object["t"] as? String == "call.media.start", let id = object["id"] as? Int else { return }
            startedOnDirect = bridge === self.directBridge
            bridge.send(["t": "ok", "id": id])
        }
        _ = try await manager.request(.callMediaStart(.string("c1"), talk: true, via: .link, linkLane: .direct))
        XCTAssertEqual(startedOnDirect, true)
        primaryConnector.enable()
        await waitFor("the nearby link carries the call audio") { manager.linkMediaLane == .primary }
        XCTAssertFalse(channel.isOpen)
        XCTAssertNotNil(manager.linkMediaChannel)
    }

    /// In the background the direct session closes, since it can't wake the app and iCloud's pushes can, unless a
    /// call needs it; back in the foreground it comes back.
    func testTheDirectSessionClosesInTheBackgroundUnlessACallNeedsIt() async {
        await mail(addr)
        await waitFor("direct") { manager.isDirect }
        var inCall = true
        manager.keepsDirectInBackground = { inCall }
        manager.appMovedToBackground()
        XCTAssertTrue(manager.isDirect, "a call keeps it")
        inCall = false
        manager.backgroundCheck()
        XCTAssertFalse(manager.isDirect)
        XCTAssertEqual(manager.phase, .remote)
        XCTAssertNil(manager.linkMediaChannel)
        manager.reconnectNow()
        XCTAssertFalse(manager.isDirect, "no new attempt while in the background")
        // Coming back to the foreground alone brings it back: nothing else kicks it (2026-11-27).
        manager.appBecameActive()
        await waitFor("direct again") { manager.isDirect }
    }

    /// The app's own connectors: the direct lane dials TCP to the bridge's home. Until 2026-11-27 it fell through to
    /// Bluetooth, which searched forever and never reported a failure, while every test used a fake connector.
    func testTheAppDialsTheDirectLaneWithTheDirectConnector() {
        XCTAssertTrue(AppModel.makeConnector(.direct) is DirectConnector)
    }

    /// Manual port forwarding (D-095): with no `remote.addr` at all (the router opened nothing), the address the user
    /// entered brings the direct session up, and it's kept with the rest of the direct connection.
    func testManualPortForwardingAloneBringsUpTheDirectSession() async throws {
        await mail(["t": "remote.state", "direct": "none", "reason": "no_mapping"])
        XCTAssertTrue(directConnector.targets.isEmpty)
        let forward = try ManualForward.make(host: "myhome.example.net", port: "47830").get()
        remote.setManualForward(forward)
        await waitFor("direct through the user's entry") { manager.isDirect }
        let target = try XCTUnwrap(directConnector.targets.last)
        XCTAssertEqual(target.directManual, forward.endpoint)
        XCTAssertTrue(target.directManualFirst)
        XCTAssertTrue(target.direct.isEmpty)
        await waitFor("noted as working") { remote.manualAttempt?.worked == true }
        XCTAssertEqual(RemoteStore(defaults: defaults, prefix: prefix).direct.manual, forward)
        XCTAssertTrue(manager.directSummary.contains("port forwarding"), manager.directSummary)
    }

    /// Another phone (or nothing of ours) answers at the user's address: the attempt fails, the entry stays.
    func testAWrongPhoneAtTheUsersAddressKeepsTheEntry() async throws {
        directAnswersWith = P256.KeyAgreement.PrivateKey()
        let forward = try ManualForward.make(host: "myhome.example.net", port: "47830").get()
        remote.setManualForward(forward)
        await waitFor("noted as not reached") { remote.manualAttempt?.worked == false }
        XCTAssertFalse(manager.isDirect)
        XCTAssertEqual(remote.direct.manual, forward)
        XCTAssertEqual(remote.manualEndpoint, forward.endpoint)
        XCTAssertEqual(RemoteStore(defaults: defaults, prefix: prefix).direct.manual, forward)
    }

    func testNoDirectAttemptWhileANearbyLinkIsUp() async {
        primaryConnector.enable()
        await waitFor("nearby") { manager.activeTransports == [.tcp] }
        await mail(addr)
        XCTAssertEqual(remote.directCandidates, [v6, v4])
        XCTAssertTrue(directConnector.targets.isEmpty)
        XCTAssertEqual(manager.directSummary, "idle; addresses: IPv6 + IPv4")
    }

    func testAWrongKeyDropsTheAddressUntilTheNextRemoteAddr() async {
        directAnswersWith = P256.KeyAgreement.PrivateKey() // someone else listens at the IPv6 address
        await mail(addr)
        await waitFor("IPv6 dropped") { remote.directCandidates == [v4] }
        XCTAssertFalse(manager.isDirect)
        XCTAssertEqual(manager.lastSessionError, .keyMismatch)
        XCTAssertTrue(manager.directSummary.hasPrefix("waiting"), manager.directSummary)
        XCTAssertEqual(directConnector.targets.count, 1)
        // New addresses from the bridge: both are tried again, at once.
        directAnswersWith = nil
        await mail(addr)
        await waitFor("direct") { manager.isDirect }
        XCTAssertEqual(directConnector.targets.count, 2)
        XCTAssertEqual(directConnector.targets.last?.direct, [v6, v4])
    }

    func testAConnectionThatDoesntOpenWaitsAndNewAddressesTryAtOnce() async {
        directConnector.failing = true
        await mail(addr)
        await waitFor("waiting") { manager.directSummary.hasPrefix("waiting") }
        XCTAssertEqual(directConnector.targets.count, 1)
        XCTAssertEqual(remote.directCandidates, [v6, v4], "no handshake failed, so nothing is dropped")
        XCTAssertEqual(manager.phase, .remote)
        directConnector.failing = false
        await mail(addr)
        await waitFor("direct") { manager.isDirect }
        XCTAssertEqual(directConnector.targets.count, 2)
    }

    func testOnlyTheAuthenticatedBridgeGivesAddresses() async {
        // Sealed with someone else's key: dropped before anything reads it.
        await mail(addr, from: P256.KeyAgreement.PrivateKey())
        // From the paired bridge, but nothing public.
        await mail(["t": "remote.addr", "v4": "192.168.1.20:47830", "v6": "[fe80::1%en0]:47830"])
        await mail(["t": "remote.addr", "v4": "127.0.0.1:47830", "v6": "[fd12::1]:47830"])
        await mail(["t": "remote.addr", "v4": "100.64.0.9:47830", "v6": "[::ffff:86.12.34.56]:47830"])
        await mail(["t": "remote.addr", "v4": "bridge.example.com:47830", "v6": NSNull()])
        XCTAssertTrue(remote.direct.endpoints.isEmpty)
        XCTAssertTrue(directConnector.targets.isEmpty)
        XCTAssertEqual(manager.phase, .remote)
    }

    func testAddressesWhileRemoteModeIsOffAreIgnored() async {
        await manager.turnOffRemote()
        XCTAssertFalse(remote.bridgeAddresses(json(addr)))
        XCTAssertTrue(remote.directCandidates.isEmpty)
        XCTAssertTrue(directConnector.targets.isEmpty)
    }

    func testStoredAddressesAreCheckedAgain() {
        let store = RemoteStore(defaults: defaults, prefix: prefix)
        store.saveDirect(RemoteDirect(endpoints: DirectEndpoints(v4: WiFiEndpoint(host: "10.0.0.2", port: 47830), v6: v6)))
        XCTAssertEqual(RemoteStore(defaults: defaults, prefix: prefix).direct.endpoints, DirectEndpoints(v4: nil, v6: v6))
    }

    func testTurningRemoteModeOffClosesTheDirectSessionAndForgetsTheAddresses() async {
        await mail(addr)
        await waitFor("direct") { manager.isDirect }
        var config: [String: Any]?
        onMessage = { bridge, object in
            guard object["t"] as? String == "remote.config", let id = object["id"] as? Int else { return }
            config = object
            bridge.send(["t": "ok", "id": id])
        }
        let attempts = directConnector.targets.count
        await manager.turnOffRemote()
        XCTAssertEqual(config?["on"] as? Bool, false, "told over the direct session")
        XCTAssertFalse(remote.isOn)
        XCTAssertTrue(manager.activeTransports.isEmpty)
        XCTAssertNotEqual(manager.phase, .connected)
        XCTAssertTrue(remote.direct.endpoints.isEmpty)
        XCTAssertTrue(RemoteStore(defaults: defaults, prefix: prefix).direct.endpoints.isEmpty)
        XCTAssertEqual(directConnector.targets.count, attempts)
    }
}
