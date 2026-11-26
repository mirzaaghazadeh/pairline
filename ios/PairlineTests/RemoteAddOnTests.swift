import XCTest
@testable import Pairline

/// The remote-mode add-on's rule (D-093): part of the trial, then a one-time add-on to Full Access.
final class RemoteAddOnTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    func testLoadingDecidesNothing() {
        for bought in [false, true] {
            let access = RemoteAddOnPolicy.access(.loading, remotePurchased: bought)
            XCTAssertEqual(access, .loading)
            XCTAssertFalse(access.allowsRemote)
            XCTAssertFalse(access.offersPurchase)
            XCTAssertFalse(access.endsRemote, "remote mode must not be turned off before the purchase state is known")
        }
    }

    func testTrialIncludesRemoteModeWithoutTheAddOn() {
        for bought in [false, true] {
            let access = RemoteAddOnPolicy.access(.trial(endsAt: now.addingTimeInterval(3600)), remotePurchased: bought)
            XCTAssertEqual(access, .trial)
            XCTAssertTrue(access.allowsRemote)
            XCTAssertFalse(access.offersPurchase, "the add-on is sold only after Full Access")
            XCTAssertFalse(access.endsRemote)
        }
    }

    func testFullAccessWithoutTheAddOnOffersIt() {
        let access = RemoteAddOnPolicy.access(.purchased, remotePurchased: false)
        XCTAssertEqual(access, .needsAddOn)
        XCTAssertFalse(access.allowsRemote)
        XCTAssertTrue(access.offersPurchase)
        XCTAssertTrue(access.endsRemote)
    }

    func testFullAccessAndTheAddOnAllowRemoteMode() {
        let access = RemoteAddOnPolicy.access(.purchased, remotePurchased: true)
        XCTAssertEqual(access, .purchased)
        XCTAssertTrue(access.allowsRemote)
        XCTAssertFalse(access.offersPurchase)
        XCTAssertFalse(access.endsRemote)
    }

    func testNoTrialAndNoFullAccessNeedsFullAccessFirst() {
        let states: [AccessState] = [.notStarted, .expired(endedAt: now)]
        for state in states {
            for bought in [false, true] {
                // An add-on without Full Access (say, Full Access refunded) doesn't unlock remote mode.
                let access = RemoteAddOnPolicy.access(state, remotePurchased: bought)
                XCTAssertEqual(access, .needsFullAccess, "\(state), add-on \(bought)")
                XCTAssertFalse(access.allowsRemote)
                XCTAssertFalse(access.offersPurchase)
                XCTAssertTrue(access.endsRemote)
            }
        }
    }

    func testSettingsLinesFollowTheState() {
        XCTAssertNotNil(RemoteAddOnText.blocker(.needsAddOn))
        XCTAssertNotNil(RemoteAddOnText.blocker(.needsFullAccess))
        XCTAssertNil(RemoteAddOnText.blocker(.trial))
        XCTAssertNil(RemoteAddOnText.blocker(.purchased))
        XCTAssertNil(RemoteAddOnText.blocker(.loading))

        XCTAssertNotNil(RemoteAddOnText.trialNote(.trial, price: "$9.99"))
        for access in [RemoteAccess.loading, .purchased, .needsAddOn, .needsFullAccess] {
            XCTAssertNil(RemoteAddOnText.trialNote(access, price: "$9.99"))
        }
    }

    func testProductID() {
        XCTAssertEqual(PurchaseManager.remoteID, "app.pairline.ios.remote")
    }
}
