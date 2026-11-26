import SwiftUI

/// Whether remote mode ("Cloud mode" on screen) may run, under D-093: it's part of the free trial, and after the trial
/// it's a one-time add-on (`app.pairline.ios.remote`) to Full Access, or bought with it (`app.pairline.ios.fullcloud`).
/// Remote pairing ("Pair over the internet") follows the same rule.
enum RemoteAccess: Equatable {
    /// The purchase state isn't known yet: nothing is decided (remote mode isn't turned off on launch).
    case loading
    /// The free trial is running: remote mode works, and becomes an add-on once Full Access is bought.
    case trial
    /// Full Access and the add-on are bought.
    case purchased
    /// Full Access is bought, the add-on isn't: the only state that offers the add-on for sale.
    case needsAddOn
    /// No trial running and no Full Access (not started, or the trial ended): the app's own paywall comes first.
    case needsFullAccess

    var allowsRemote: Bool { self == .trial || self == .purchased }

    /// The "Buy Cloud mode" button shows only here, and only where remote mode exists (`RemoteFeature`).
    var offersPurchase: Bool { self == .needsAddOn }

    /// Remote mode, if on, turns off: the access is known and doesn't cover it.
    var endsRemote: Bool { self == .needsAddOn || self == .needsFullAccess }
}

/// The "Add Cloud mode" choice at the first purchase (D-093): Full Access and Cloud mode together for less than the two
/// apart. Every price on it is the user's own storefront's; the saving is worked out from them, never written in.
struct BundleOffer: Equatable {
    /// Full Access + Cloud mode, e.g. "$20.99".
    let bundlePrice: String
    /// Full Access and the add-on bought apart, e.g. "$29.98".
    let separatePrice: String
    /// The add-on alone, bought later, e.g. "$19.99".
    let remotePrice: String
    /// The whole percent saved, e.g. 30; nil when the bundle doesn't cost less.
    let savingPercent: Int?

    /// 1 − bundle ÷ (full + add-on), as a whole percent rounded to the nearest; nil unless the bundle costs less.
    static func savingPercent(full: Decimal, remote: Decimal, bundle: Decimal) -> Int? {
        let separate = full + remote
        guard full > 0, remote > 0, bundle > 0, bundle < separate else { return nil }
        var percent = (1 - bundle / separate) * 100
        var rounded = Decimal()
        NSDecimalRound(&rounded, &percent, 0, .plain)
        let whole = NSDecimalNumber(decimal: rounded).intValue
        return whole > 0 ? whole : nil
    }
}

enum RemoteAddOnPolicy {
    /// [access] is the app's own trial-or-purchase state; [remotePurchased] is a verified, unrevoked add-on (or bundle)
    /// transaction.
    static func access(_ access: AccessState, remotePurchased: Bool) -> RemoteAccess {
        switch access {
        case .loading: return .loading
        case .trial: return .trial
        case .purchased: return remotePurchased ? .purchased : .needsAddOn
        case .notStarted, .expired: return .needsFullAccess
        }
    }
}

/// The add-on's lines in remote mode's Settings section (D-093). Strings live in the "RemoteAddOn" table.
enum RemoteAddOnText {
    /// Why remote mode can't be turned on for this purchase state, or nil when it can.
    static func blocker(_ access: RemoteAccess) -> Text? {
        switch access {
        case .needsAddOn:
            return Text("Cloud mode is a one-time add-on to Pairline Full Access. Everything nearby keeps working without it.",
                        tableName: "RemoteAddOn")
        case .needsFullAccess:
            return Text("Cloud mode needs the free trial or Pairline Full Access.", tableName: "RemoteAddOn")
        case .loading, .trial, .purchased:
            return nil
        }
    }

    /// During the trial: it's included now, and what it costs later: with Pairline at the first purchase
    /// ([bundlePrice], when that offer loaded), or added afterwards ([price]).
    static func trialNote(_ access: RemoteAccess, price: String, bundlePrice: String? = nil) -> Text? {
        guard access == .trial else { return nil }
        if let bundlePrice {
            return Text("Cloud mode is part of your free trial. Buy it together with Pairline for \(bundlePrice), or add it later for \(price).",
                        tableName: "RemoteAddOn")
        }
        return Text("Cloud mode is part of your free trial. After you buy Pairline, it's a one-time \(price) add-on.",
                    tableName: "RemoteAddOn")
    }
}

/// The add-on's buy button (D-093): only once Full Access is bought, and only where remote mode exists, so a build
/// without remote mode (App Review before it ships) never offers it.
struct RemoteAddOnButton: View {
    @EnvironmentObject private var purchases: PurchaseManager

    var body: some View {
        if RemoteFeature.isAvailable, purchases.remoteAccess.offersPurchase {
            Button {
                Haptics.tap()
                Task {
                    if await purchases.buyRemote() { Haptics.success() }
                }
            } label: {
                HStack(spacing: 12) {
                    Label {
                        Text("Buy Cloud mode · \(purchases.remotePriceText)", tableName: "RemoteAddOn")
                    } icon: {
                        Image(systemName: "cart")
                    }
                    Spacer(minLength: 12)
                    if purchases.busy { ProgressView() }
                }
            }
            .disabled(purchases.busy)
        }
    }
}
