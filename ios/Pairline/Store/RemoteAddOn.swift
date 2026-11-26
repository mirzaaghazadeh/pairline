import SwiftUI

/// Whether remote mode may run, under D-081: it's part of the free trial, and after the trial it's a one-time add-on
/// (`app.pairline.ios.remote`) to Full Access. Remote pairing ("Pair a phone that's far away") follows the same rule.
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

    /// The "Buy remote mode" button shows only here, and only where remote mode exists (`RemoteFeature`).
    var offersPurchase: Bool { self == .needsAddOn }

    /// Remote mode, if on, turns off: the access is known and doesn't cover it.
    var endsRemote: Bool { self == .needsAddOn || self == .needsFullAccess }
}

enum RemoteAddOnPolicy {
    /// [access] is the app's own trial-or-purchase state; [remotePurchased] is a verified, unrevoked add-on transaction.
    static func access(_ access: AccessState, remotePurchased: Bool) -> RemoteAccess {
        switch access {
        case .loading: return .loading
        case .trial: return .trial
        case .purchased: return remotePurchased ? .purchased : .needsAddOn
        case .notStarted, .expired: return .needsFullAccess
        }
    }
}

/// The add-on's lines in remote mode's Settings section (D-081). Strings live in the "RemoteAddOn" table.
enum RemoteAddOnText {
    /// Why remote mode can't be turned on for this purchase state, or nil when it can.
    static func blocker(_ access: RemoteAccess) -> Text? {
        switch access {
        case .needsAddOn:
            return Text("Remote mode is a one-time add-on to Pairline Full Access. Everything nearby keeps working without it.",
                        tableName: "RemoteAddOn")
        case .needsFullAccess:
            return Text("Remote mode needs the free trial or Pairline Full Access.", tableName: "RemoteAddOn")
        case .loading, .trial, .purchased:
            return nil
        }
    }

    /// During the trial: it's included now, and what it costs later.
    static func trialNote(_ access: RemoteAccess, price: String) -> Text? {
        guard access == .trial else { return nil }
        return Text("Remote mode is part of your free trial. After you buy Pairline, it's a one-time \(price) add-on.",
                    tableName: "RemoteAddOn")
    }
}

/// The add-on's buy button (D-081): only once Full Access is bought, and only where remote mode exists, so a build
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
                        Text("Buy remote mode · \(purchases.remotePriceText)", tableName: "RemoteAddOn")
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
