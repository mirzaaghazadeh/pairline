import Foundation

/// The bridge phone's latest status, shared with the widget through the App Group container. It holds only
/// status (link state, battery, signal, carrier) — never messages, numbers or contacts — and is overwritten on
/// every update and deleted on unpair (PRIVACY.md).
struct WidgetSnapshot: Codable, Equatable {
    enum Link: String, Codable {
        case connected
        case connecting
        case offline
        case notPaired
        case locked
    }

    struct SIM: Codable, Hashable, Identifiable {
        var slot: Int
        var carrier: String?
        var signal: Int?
        var net: String?
        var id: Int { slot }
    }

    var link: Link
    var batteryLevel: Int?
    var charging: Bool?
    var sims: [SIM]
    var updated: Date
    var isDemo: Bool

    static let appGroupID = "group.app.pairline.ios"
    private static let key = "widget.snapshot.v1"

    static let placeholder = WidgetSnapshot(link: .connected, batteryLevel: 82, charging: false,
                                            sims: [SIM(slot: 0, carrier: "Carrier", signal: 3, net: "LTE")],
                                            updated: Date(), isDemo: false)

    static func load() -> WidgetSnapshot? {
        guard let data = UserDefaults(suiteName: appGroupID)?.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
    }

    func save() {
        guard let data = try? JSONEncoder().encode(self) else { return }
        UserDefaults(suiteName: WidgetSnapshot.appGroupID)?.set(data, forKey: WidgetSnapshot.key)
    }

    static func clear() {
        UserDefaults(suiteName: appGroupID)?.removeObject(forKey: key)
    }
}
