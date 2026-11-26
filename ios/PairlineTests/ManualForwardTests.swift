import Network
import XCTest
@testable import Pairline

/// Manual port forwarding (D-095): what the user may type as the home's address and port.
final class ManualForwardTests: XCTestCase {
    private func make(_ host: String, _ port: String = "47830") -> Result<ManualForward, ManualForward.Problem> {
        ManualForward.make(host: host, port: port)
    }

    func testNamesAndPublicAddressesAreTaken() throws {
        XCTAssertEqual(try make(" MyHome.DDNS.net. ").get(), ManualForward(host: "myhome.ddns.net", port: 47830))
        XCTAssertEqual(try make("my-home.duckdns.org").get().host, "my-home.duckdns.org")
        XCTAssertEqual(try make("86.12.34.56", "50000").get(), ManualForward(host: "86.12.34.56", port: 50000))
        XCTAssertEqual(try make("[2a02:1234::5]").get(), ManualForward(host: "2a02:1234::5", port: 47830))
    }

    func testAddressesInsideAHomeAreRefused() {
        for host in ["192.168.1.50", "10.0.0.2", "172.16.4.1", "100.64.1.1", "127.0.0.1", "169.254.1.1", "fe80::1",
                     "fd00::1", "::1", "printer.local", "router.lan"] {
            XCTAssertEqual(make(host), .failure(.insideHome), host)
        }
    }

    func testWhatIsNotAnAddressIsRefused() {
        for host in ["myhome", "my home.net", "-bad.example.com", "bad-.example.com", "1.2.3", "300.1.2.3",
                     "a..b.com", "exa_mple.com", "http://myhome.net", String(repeating: "a", count: 64) + ".com"] {
            XCTAssertEqual(make(host), .failure(.notAnAddress), host)
        }
        XCTAssertEqual(make("   "), .failure(.empty))
    }

    func testPorts() throws {
        for port in ["0", "65536", "4a", "", "123456"] {
            XCTAssertEqual(make("myhome.net", port), .failure(.badPort), port)
        }
        XCTAssertEqual(try make("myhome.net", "65535").get().port, 65535)
        // A Persian or Arabic keypad's digits.
        XCTAssertEqual(try make("myhome.net", "۴۷۸۳۰").get().port, 47830)
        XCTAssertEqual(try make("myhome.net", "٤٧٨٣٠").get().port, 47830)
    }

    func testANameIsResolvedByTheSystemAndAnAddressIsDialedAsIs() throws {
        XCTAssertEqual(try make("myhome.net").get().dialable, .hostPort(host: .name("myhome.net", nil), port: 47830))
        XCTAssertEqual(try make("86.12.34.56").get().dialable,
                       .hostPort(host: .ipv4(IPv4Address("86.12.34.56")!), port: 47830))
        XCTAssertEqual(try make("2a02:1234::5").get().dialable,
                       .hostPort(host: .ipv6(IPv6Address("2a02:1234::5")!), port: 47830))
    }

    func testStoredEntriesAreCheckedAgain() {
        XCTAssertNil(ManualForward(host: "192.168.1.5", port: 47830).checked)
        XCTAssertEqual(ManualForward(host: "myhome.net", port: 47830).checked, ManualForward(host: "myhome.net", port: 47830))
    }
}
