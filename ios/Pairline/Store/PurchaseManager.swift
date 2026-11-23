import Foundation
import StoreKit

/// StoreKit 2 purchases (D-017). Two non-consumables: a free "3-day Trial" and "Pairline Full Access".
/// Entitlements are verified on the device from Apple-signed transactions; there's no receipt server.
@MainActor
final class PurchaseManager: ObservableObject {
    static let trialID = "app.pairline.ios.trial3day"
    static let fullID = "app.pairline.ios.full"
    static let fallbackPrice = "$11.99"

    @Published private(set) var access: AccessState = .loading
    @Published private(set) var fullProduct: Product?
    @Published private(set) var trialProduct: Product?
    @Published private(set) var busy = false
    @Published var message: String?
    @Published private(set) var trustedNow = Date()

    private let timeStore: TrustedTimeStore
    private let clock: () -> Date
    private var updatesTask: Task<Void, Never>?
    private var tick: Timer?
    private var trialStart: Date?
    private var purchased = false
    private var signedDates: [Date] = []

    init(timeStore: TrustedTimeStore = KeychainTimeStore(), clock: @escaping () -> Date = Date.init) {
        self.timeStore = timeStore
        self.clock = clock
    }

    func start() {
        guard updatesTask == nil else { return }
        updatesTask = Task { [weak self] in
            for await result in Transaction.updates {
                guard let self else { return }
                if case .verified(let transaction) = result {
                    await transaction.finish()
                }
                await self.refresh()
            }
        }
        tick = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.evaluate() }
        }
        Task {
            await refresh()
            await loadProducts()
        }
    }

    var priceText: String { fullProduct?.displayPrice ?? Self.fallbackPrice }

    func loadProducts() async {
        do {
            let products = try await Product.products(for: [Self.trialID, Self.fullID])
            for product in products {
                if product.id == Self.fullID { fullProduct = product }
                if product.id == Self.trialID { trialProduct = product }
            }
        } catch {
            log.warning("store", "Couldn't load products: \(error.localizedDescription)")
        }
    }

    /// Reads the current entitlements (local, Apple-signed) and recomputes access.
    func refresh() async {
        var start: Date?
        var bought = false
        var signed: [Date] = []
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result, transaction.revocationDate == nil else { continue }
            signed.append(transaction.signedDate)
            switch transaction.productID {
            case Self.fullID:
                bought = true
            case Self.trialID:
                start = min(start ?? transaction.originalPurchaseDate, transaction.originalPurchaseDate)
            default:
                break
            }
        }
        trialStart = start
        purchased = bought
        signedDates = signed
        evaluate()
    }

    /// Recomputes access from the stored facts and the trusted clock.
    func evaluate() {
        let now = TrialPolicy.trustedNow(deviceNow: clock(), highWater: timeStore.highWater, signedDates: signedDates)
        timeStore.save(now)
        trustedNow = now
        let new = TrialPolicy.state(purchased: purchased, trialStart: trialStart, now: now)
        if new != access {
            log.info("store", "Access: \(new)")
            access = new
        }
    }

    @discardableResult
    func startTrial() async -> Bool {
        if trialProduct == nil { await loadProducts() }
        guard let product = trialProduct else {
            message = String(localized: "The App Store isn't available right now. Check your connection and try again.")
            return false
        }
        return await purchase(product)
    }

    @discardableResult
    func buyFull() async -> Bool {
        if fullProduct == nil { await loadProducts() }
        guard let product = fullProduct else {
            message = String(localized: "The App Store isn't available right now. Check your connection and try again.")
            return false
        }
        return await purchase(product)
    }

    private func purchase(_ product: Product) async -> Bool {
        busy = true
        defer { busy = false }
        do {
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                guard case .verified(let transaction) = verification else {
                    message = String(localized: "The purchase couldn't be verified.")
                    return false
                }
                await transaction.finish()
                await refresh()
                return true
            case .pending:
                message = String(localized: "Your purchase is waiting for approval.")
                return false
            case .userCancelled:
                return false
            @unknown default:
                return false
            }
        } catch {
            message = error.localizedDescription
            return false
        }
    }

    /// Restore Purchases: syncs with the App Store (may ask the user to sign in).
    func restore() async {
        busy = true
        defer { busy = false }
        do {
            try await AppStore.sync()
            await refresh()
            message = access.isPurchased
                ? String(localized: "Your purchase was restored.")
                : String(localized: "No purchase was found for this Apple Account.")
        } catch {
            message = error.localizedDescription
        }
    }
}
