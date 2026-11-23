import Foundation
import SwiftUI

/// User preferences (UserDefaults; no user content).
@MainActor
final class AppSettings: ObservableObject {
    static let shared = AppSettings()

    @AppStorage("onboarding.done") var onboardingDone = false
    @AppStorage("notifications.previews") var showMessagePreviews = true
    @AppStorage("notifications.lowBattery") var lowBatteryAlert = true
    @AppStorage("notifications.trialReminder") var trialReminder = true
    /// CallKit calls in iPhone Recents (which can sync to iCloud). Off by default (ARCHITECTURE.md, "Calls").
    @AppStorage("calls.recents") var callsInRecents = false
    @AppStorage("messages.defaultSim") var defaultSMSSim = -1
    @AppStorage("calls.defaultSim") var defaultCallSim = -1
    @AppStorage("ussd.saved") var savedUSSDCodesRaw = ""

    var savedUSSDCodes: [String] {
        get { savedUSSDCodesRaw.split(separator: "\n").map(String.init) }
        set { savedUSSDCodesRaw = newValue.joined(separator: "\n") }
    }
}
