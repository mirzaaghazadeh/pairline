import ActivityKit
import Foundation

/// The bridge-status Live Activity on the Lock Screen and in the Dynamic Island (D-053). Built into the app (which
/// starts and updates it) and the widget extension (which draws it). Like the widget it carries only status — link
/// state, the bridge's name, battery, carrier, network type and signal — never numbers, messages or contacts, and it
/// is updated on the device only (no push server: rule 2).
@available(iOS 16.2, *)
struct BridgeActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var link: WidgetSnapshot.Link
        /// The bridge phone's name ("Pixel 8"), once its device info has arrived.
        var name: String?
        var batteryLevel: Int?
        var charging: Bool?
        var sims: [WidgetSnapshot.SIM]

        /// The SIM the compact Dynamic Island shows: the first slot.
        var primarySIM: WidgetSnapshot.SIM? { sims.first }
    }

    /// Demo mode's sample bridge (labelled "Demo"); switching between demo and the real bridge starts a new one.
    var isDemo: Bool
}

@available(iOS 16.2, *)
extension BridgeActivityAttributes.ContentState {
    /// The Live Activity's content for a bridge status snapshot; nil when there's no bridge to show (not paired).
    init?(snapshot: WidgetSnapshot, name: String?) {
        guard snapshot.link != .notPaired else { return nil }
        self.init(link: snapshot.link, name: name, batteryLevel: snapshot.batteryLevel, charging: snapshot.charging,
                  sims: snapshot.sims.sorted { $0.slot < $1.slot })
    }
}
