import Foundation
import StoreKit
import UIKit

/// StoreKit 2 purchases (D-017, D-036, D-093). Four non-consumables: a free "7-day Trial", "Pairline Full Access",
/// the Cloud mode add-on, and Full Access + Cloud mode together. Entitlements are verified on the device from
/// Apple-signed transactions; there's no receipt server.
@MainActor
final class PurchaseManager: ObservableObject {
    static let trialID = "app.pairline.ios.trial7day"
    static let fullID = "app.pairline.ios.full"
    /// Cloud mode (remote mode) added later (D-093): sold only once Full Access is bought.
    static let remoteID = "app.pairline.ios.remote"
    /// Full Access and Cloud mode together, offered at the first purchase (D-093): unlocks both.
    static let bundleID = "app.pairline.ios.fullcloud"
    static let fallbackPrice = "$9.99"
    static let remoteFallbackPrice = "$19.99"

    @Published private(set) var access: AccessState = .loading
    /// Whether remote mode may run (D-093): during the trial, or with Full Access and the add-on (or the bundle).
    @Published private(set) var remoteAccess: RemoteAccess = .loading
    @Published private(set) var fullProduct: Product?
    @Published private(set) var trialProduct: Product?
    @Published private(set) var remoteProduct: Product?
    @Published private(set) var bundleProduct: Product?
    @Published private(set) var busy = false
    @Published var message: String?
    @Published private(set) var trustedNow = Date()

    private let timeStore: TrustedTimeStore
    private let clock: () -> Date
    private var updatesTask: Task<Void, Never>?
    private var tick: Timer?
    private var trialStart: Date?
    private var purchased = false
    private var remotePurchased = false
    private var signedDates: [Date] = []

    init(timeStore: TrustedTimeStore = KeychainTimeStore(), clock: @escaping () -> Date = Date.init) {
        self.timeStore = timeStore
        self.clock = clock
    }

    func start() {
        guard updatesTask == nil else { return }
        #if DEBUG
        // Store screenshots make no StoreKit calls, so the Simulator's "Sign in to Apple Account" prompt can't cover
        // the screen; the price shown is the list price and access comes from `-PairlineAccess`.
        if DebugSettings.marketingScreenshots {
            evaluate()
            return
        }
        #endif
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

    var priceText: String {
        #if DEBUG
        // Store screenshots show the list price, not whatever the App Store sandbox still has cached.
        if DebugSettings.marketingScreenshots { return Self.fallbackPrice }
        #endif
        return fullProduct?.displayPrice ?? Self.fallbackPrice
    }

    /// The Cloud mode add-on's price in the user's storefront (D-093).
    var remotePriceText: String {
        remoteProduct?.displayPrice ?? Self.remoteFallbackPrice
    }

    /// The "Add Cloud mode" choice at the first purchase (D-093): only before Full Access is owned, and only once all
    /// three products loaded, so every price on it comes from the user's own storefront. Where it's shown also depends
    /// on `RemoteFeature` (the paywall checks that).
    var bundleOffer: BundleOffer? {
        guard !access.isPurchased, let full = fullProduct, let remote = remoteProduct, let bundle = bundleProduct else {
            return nil
        }
        return BundleOffer(bundlePrice: bundle.displayPrice,
                           separatePrice: (full.price + remote.price).formatted(full.priceFormatStyle),
                           remotePrice: remote.displayPrice,
                           savingPercent: BundleOffer.savingPercent(full: full.price, remote: remote.price,
                                                                    bundle: bundle.price))
    }

    func loadProducts() async {
        do {
            let products = try await Product.products(for: [Self.trialID, Self.fullID, Self.remoteID, Self.bundleID])
            for product in products {
                if product.id == Self.fullID { fullProduct = product }
                if product.id == Self.trialID { trialProduct = product }
                if product.id == Self.remoteID { remoteProduct = product }
                if product.id == Self.bundleID { bundleProduct = product }
            }
        } catch {
            log.warning("store", "Couldn't load products: \(error.localizedDescription)")
        }
    }

    /// Reads the current entitlements (local, Apple-signed) and recomputes access.
    func refresh() async {
        var start: Date?
        var bought = false
        var boughtRemote = false
        var signed: [Date] = []
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result, transaction.revocationDate == nil else { continue }
            signed.append(transaction.signedDate)
            switch transaction.productID {
            case Self.fullID:
                bought = true
            case Self.trialID:
                start = min(start ?? transaction.originalPurchaseDate, transaction.originalPurchaseDate)
            case Self.remoteID:
                boughtRemote = true
            case Self.bundleID:
                // Full Access and Cloud mode in one purchase (D-093).
                bought = true
                boughtRemote = true
            default:
                break
            }
        }
        trialStart = start
        purchased = bought
        remotePurchased = boughtRemote
        signedDates = signed
        evaluate()
    }

    /// Recomputes access from the stored facts and the trusted clock.
    func evaluate() {
        let now = TrialPolicy.trustedNow(deviceNow: clock(), highWater: timeStore.highWater, signedDates: signedDates)
        timeStore.save(now)
        trustedNow = now
        var new = TrialPolicy.state(purchased: purchased, trialStart: trialStart, now: now)
        var remoteBought = remotePurchased
        #if DEBUG
        let override = Self.debugAccessOverride(now: now)
        new = override ?? new
        remoteBought = Self.debugRemoteOverride(accessOverridden: override != nil) ?? remoteBought
        #endif
        if new != access {
            log.info("store", "Access: \(new)")
            access = new
        }
        let remote = RemoteAddOnPolicy.access(new, remotePurchased: remoteBought)
        if remote != remoteAccess {
            log.info("store", "Remote mode access: \(remote)")
            remoteAccess = remote
        }
    }

    #if DEBUG
    /// Debug builds only: `-PairlineAccess purchased|trial|expired|none` fakes the access state, so the Simulator and a
    /// test iPhone can be used end to end without App Store sandbox purchases. The value is remembered for later
    /// launches (from the home screen, or by iOS in the background, where Xcode's StoreKit file doesn't apply) until
    /// the app is launched with `-PairlineAccess reset`. Unit tests ignore the remembered value.
    private static func debugAccessOverride(now: Date) -> AccessState? {
        let key = "debug.accessOverride"
        let defaults = UserDefaults.standard
        let args = ProcessInfo.processInfo.arguments
        let value: String?
        if let index = args.firstIndex(of: "-PairlineAccess"), index + 1 < args.count {
            value = args[index + 1] == "reset" ? nil : args[index + 1]
            defaults.set(value, forKey: key)
        } else if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil {
            value = defaults.string(forKey: key) ?? testerAccess
        } else {
            value = nil
        }
        switch value ?? "" {
        case "purchased": return .purchased
        case "trial": return .trial(endsAt: now.addingTimeInterval(26 * 3600))
        case "expired": return .expired(endedAt: now.addingTimeInterval(-3600))
        case "none": return .notStarted
        default: return nil
        }
    }

    /// Debug builds only: `-PairlineRemoteAccess purchased|none` fakes the remote-mode add-on (D-093), remembered like
    /// `-PairlineAccess` until `-PairlineRemoteAccess reset`. Without it, a faked access state of "purchased" counts as
    /// everything bought (so a test iPhone set up with `-PairlineAccess purchased` keeps remote mode); use
    /// `-PairlineRemoteAccess none` to see the add-on for sale. Unit tests ignore the remembered value.
    private static func debugRemoteOverride(accessOverridden: Bool) -> Bool? {
        let key = "debug.remoteAccessOverride"
        let defaults = UserDefaults.standard
        let args = ProcessInfo.processInfo.arguments
        let value: String?
        if let index = args.firstIndex(of: "-PairlineRemoteAccess"), index + 1 < args.count {
            value = args[index + 1] == "reset" ? nil : args[index + 1]
            defaults.set(value, forKey: key)
        } else if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil {
            value = defaults.string(forKey: key)
        } else {
            value = nil
        }
        switch value {
        case "purchased": return true
        case "none": return false
        default: return accessOverridden ? true : nil
        }
    }

    /// A debug build made for a remote tester's iPhone, where nobody can pass `-PairlineAccess` at launch, is built with
    /// `PAIRLINE_TESTER_UNLOCKED` among `SWIFT_ACTIVE_COMPILATION_CONDITIONS` and starts as purchased.
    private static var testerAccess: String? {
        #if PAIRLINE_TESTER_UNLOCKED
        return "purchased"
        #else
        return nil
        #endif
    }
    #endif

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

    /// Buys the Cloud mode add-on (D-093). Offered only once Full Access is bought (`RemoteAccess.offersPurchase`).
    @discardableResult
    func buyRemote() async -> Bool {
        if remoteProduct == nil { await loadProducts() }
        guard let product = remoteProduct else {
            message = String(localized: "The App Store isn't available right now. Check your connection and try again.")
            return false
        }
        return await purchase(product)
    }

    /// Buys Full Access and Cloud mode together (D-093), the paywall's "Add Cloud mode" choice at the first purchase.
    @discardableResult
    func buyBundle() async -> Bool {
        if bundleProduct == nil { await loadProducts() }
        guard let product = bundleProduct else {
            message = String(localized: "The App Store isn't available right now. Check your connection and try again.")
            return false
        }
        return await purchase(product)
    }

    /// The scene on screen, so Apple's purchase sheet attaches to it (iOS 17+). Without one, StoreKit picks a scene
    /// itself, and on a tester's iPhone the sheet stayed up after "Get" (D-079).
    private var activeScene: UIScene? {
        UIApplication.shared.connectedScenes.first { $0.activationState == .foregroundActive }
            ?? UIApplication.shared.connectedScenes.first
    }

    private func purchase(_ product: Product) async -> Bool {
        busy = true
        defer { busy = false }
        do {
            let result: Product.PurchaseResult
            if #available(iOS 17.0, *), let scene = activeScene {
                result = try await product.purchase(confirmIn: scene)
            } else {
                result = try await product.purchase()
            }
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
            message = Self.describe(error)
            return false
        }
    }

    /// StoreKit's own errors in words a user can act on; the details go to the diagnostics log (D-079). A tester saw
    /// "SKInternalErrorDomain error 14", which StoreKit gives when the App Store account can't complete it.
    static func describe(_ error: Error) -> String {
        let ns = error as NSError
        let underlying = (ns.userInfo[NSUnderlyingErrorKey] as? NSError).map { " ← \($0.domain) \($0.code)" } ?? ""
        log.warning("store", "Purchase failed: \(ns.domain) \(ns.code)\(underlying)")
        if let storeKit = error as? StoreKitError, case .networkError = storeKit {
            return String(localized: "The App Store isn't available right now. Check your connection and try again.")
        }
        let code = "\(ns.domain) \(ns.code)"
        return String(localized: "The App Store couldn't complete this (\(code)). Check that you're signed in under Settings › your name › Media & Purchases and that in-app purchases are allowed in Screen Time, then try again. If it still fails, restart the iPhone.", table: "PhoneTools")
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
            message = Self.describe(error)
        }
    }
}
