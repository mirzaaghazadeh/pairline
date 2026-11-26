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

        XCTAssertNotNil(RemoteAddOnText.trialNote(.trial, price: "$19.99"))
        XCTAssertNotNil(RemoteAddOnText.trialNote(.trial, price: "$19.99", bundlePrice: "$19.99"))
        for access in [RemoteAccess.loading, .purchased, .needsAddOn, .needsFullAccess] {
            XCTAssertNil(RemoteAddOnText.trialNote(access, price: "$19.99"))
            XCTAssertNil(RemoteAddOnText.trialNote(access, price: "$19.99", bundlePrice: "$19.99"))
        }
    }

    func testProductIDs() {
        XCTAssertEqual(PurchaseManager.remoteID, "app.pairline.ios.remote")
        XCTAssertEqual(PurchaseManager.bundleID, "app.pairline.ios.fullcloud")
    }

    /// The bundle's saving comes from the store's prices (D-093, D-098): $9.99 + $19.99 against $19.99 is 33 %
    /// (33.3 %, rounded to the nearest whole percent), and nothing is claimed when the bundle doesn't cost less.
    func testBundleSavingIsWorkedOutFromThePrices() {
        XCTAssertEqual(BundleOffer.savingPercent(full: dec("9.99"), remote: dec("19.99"), bundle: dec("19.99")), 33)
        XCTAssertEqual(BundleOffer.savingPercent(full: dec("9.99"), remote: dec("19.99"), bundle: dec("20.99")), 30)
        XCTAssertEqual(BundleOffer.savingPercent(full: 10, remote: 10, bundle: 15), 25)
        // Another storefront's prices give their own percentage.
        XCTAssertEqual(BundleOffer.savingPercent(full: dec("349"), remote: dec("699"), bundle: dec("749")), 29)
        XCTAssertNil(BundleOffer.savingPercent(full: dec("9.99"), remote: dec("19.99"), bundle: dec("29.98")))
        XCTAssertNil(BundleOffer.savingPercent(full: dec("9.99"), remote: dec("19.99"), bundle: dec("31")))
        XCTAssertNil(BundleOffer.savingPercent(full: 0, remote: dec("19.99"), bundle: dec("9.99")))
        XCTAssertNil(BundleOffer.savingPercent(full: dec("9.99"), remote: dec("19.99"), bundle: 0))
        // Less than half a percent saved rounds to nothing to claim.
        XCTAssertNil(BundleOffer.savingPercent(full: 100, remote: 100, bundle: dec("199.5")))
    }

    private func dec(_ text: String) -> Decimal { Decimal(string: text)! }
}
