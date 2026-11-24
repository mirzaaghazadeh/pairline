import StoreKit
import StoreKitTest
import XCTest
@testable import Pairline

/// Purchases against the local StoreKit configuration (Pairline.storekit), no Apple servers.
@MainActor
final class StoreKitTests: XCTestCase {
    private var session: SKTestSession!

    override func setUp() async throws {
        session = try SKTestSession(configurationFileNamed: "Pairline")
        session.resetToDefaultState()
        session.disableDialogs = true
        session.clearTransactions()
        // The test environment takes a moment to serve the configuration's products; on a cold simulator or a CI
        // runner that can be well over 4 s.
        for _ in 0..<75 {
            let products = try await Product.products(for: [PurchaseManager.trialID, PurchaseManager.fullID])
            if products.count == 2 { return }
            try await Task.sleep(nanoseconds: 200_000_000)
        }
        // GitHub's macOS runners often never serve a local StoreKit test session (no StoreKit daemon session on a
        // headless runner). Skip there, with the reason in the log; on a developer Mac this still fails loudly.
        // ios.yml sets TEST_RUNNER_CI, which xcodebuild passes to the tests as CI.
        if ProcessInfo.processInfo.environment["CI"] != nil {
            throw XCTSkip("The StoreKit test session served no products on this CI runner; run StoreKitTests locally.")
        }
    }

    override func tearDown() async throws {
        session.clearTransactions()
    }

    func testProductsMatchD017() async throws {
        let products = try await Product.products(for: [PurchaseManager.trialID, PurchaseManager.fullID])
        let trial = products.first { $0.id == PurchaseManager.trialID }
        let full = products.first { $0.id == PurchaseManager.fullID }
        XCTAssertEqual(trial?.type, .nonConsumable)
        XCTAssertEqual(trial?.price, 0)
        XCTAssertEqual(full?.type, .nonConsumable)
        XCTAssertEqual(full?.price, Decimal(string: "9.99"))
        XCTAssertEqual(trial?.displayName, "7-day Trial")
    }

    func testTrialThenExpiryThenPurchase() async throws {
        var now = Date()
        let manager = PurchaseManager(timeStore: MemoryTimeStore(), clock: { now })
        await manager.refresh()
        XCTAssertEqual(manager.access, .notStarted)

        let started = await manager.startTrial()
        XCTAssertTrue(started)
        guard case .trial(let end) = manager.access else { return XCTFail("expected trial, got \(manager.access)") }
        XCTAssertEqual(end.timeIntervalSince(now), TrialPolicy.duration, accuracy: 120)
        XCTAssertTrue(manager.access.allowsLink)

        // Eight days later the link features lock.
        now = now.addingTimeInterval(8 * 86400)
        manager.evaluate()
        guard case .expired = manager.access else { return XCTFail("expected expired, got \(manager.access)") }
        XCTAssertFalse(manager.access.allowsLink)

        // Turning the clock back doesn't help.
        now = now.addingTimeInterval(-3 * 86400)
        manager.evaluate()
        guard case .expired = manager.access else { return XCTFail("clock rollback reopened the trial") }

        let bought = await manager.buyFull()
        XCTAssertTrue(bought)
        XCTAssertEqual(manager.access, .purchased)
    }

    func testRefundedPurchaseLocksAgain() async throws {
        let manager = PurchaseManager(timeStore: MemoryTimeStore())
        let bought = await manager.buyFull()
        XCTAssertTrue(bought)
        XCTAssertEqual(manager.access, .purchased)
        let transaction = session.allTransactions().first { $0.productIdentifier == PurchaseManager.fullID }
        XCTAssertNotNil(transaction)
        if let transaction {
            try session.refundTransaction(identifier: transaction.identifier)
        }
        for _ in 0..<20 where manager.access == .purchased {
            try await Task.sleep(nanoseconds: 200_000_000)
            await manager.refresh()
        }
        XCTAssertNotEqual(manager.access, .purchased)
    }
}
