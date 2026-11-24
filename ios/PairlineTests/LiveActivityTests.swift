import XCTest
@testable import Pairline

/// The bridge-status Live Activity (D-053).
final class LiveActivityTests: XCTestCase {
    func testForegroundVisitRenewsOnlyAnOlderActivity() {
        let now = Date()
        XCTAssertTrue(BridgeLiveActivity.shouldRenew(startedAt: nil, now: now), "one from an earlier launch")
        XCTAssertFalse(BridgeLiveActivity.shouldRenew(startedAt: now.addingTimeInterval(-60), now: now))
        XCTAssertTrue(BridgeLiveActivity.shouldRenew(startedAt: now.addingTimeInterval(-31 * 60), now: now))
    }

    func testContentCarriesStatusOnlyAndNeedsABridge() throws {
        guard #available(iOS 16.2, *) else { return }
        let snapshot = WidgetSnapshot(link: .connected, batteryLevel: 64, charging: true,
                                      sims: [.init(slot: 1, carrier: "Harbor Tel", signal: 2, net: "LTE"),
                                             .init(slot: 0, carrier: "Lumen Mobile", signal: 4, net: "5G")],
                                      updated: Date(), isDemo: false)
        let state = try XCTUnwrap(BridgeActivityAttributes.ContentState(snapshot: snapshot, name: "Pixel 8"))
        XCTAssertEqual(state.sims.map(\.slot), [0, 1], "SIMs in slot order")
        XCTAssertEqual(state.primarySIM?.net, "5G")
        XCTAssertEqual(state.batteryLevel, 64)
        XCTAssertEqual(state.link, .connected)

        var unpaired = snapshot
        unpaired.link = .notPaired
        XCTAssertNil(BridgeActivityAttributes.ContentState(snapshot: unpaired, name: nil), "nothing to show")
    }
}
