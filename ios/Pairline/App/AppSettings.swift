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
    /// CallKit calls in iPhone Recents, where a tap calls back (D-074). On by default since D-074, the maintainer's
    /// decision; Recents can sync to iCloud, which the setting's footer says, and turning it off keeps calls in Pairline.
    @AppStorage("calls.recents") var callsInRecents = true
    /// Notifications from the apps chosen on the bridge phone show here (D-077). The apps are chosen there.
    @AppStorage("notifications.mirror") var mirrorNotifications = true
    /// Where a call's audio travels first when it's on this iPhone: Bluetooth (default) or Wi-Fi; the other is used
    /// when the first isn't up (D-072).
    @AppStorage("calls.audioPath") var callAudioPath: CallAudioPath = .link
    @AppStorage("messages.defaultSim") var defaultSMSSim = -1
    @AppStorage("calls.defaultSim") var defaultCallSim = -1
    @AppStorage("ussd.saved") var savedUSSDCodesRaw = ""
    /// The saved code that checks the balance, shown as a card on the Bridge tab and run by `pairline://balance`
    /// (D-082). Only this code can run from a link: a link can't carry a code of its own.
    @AppStorage("ussd.balance") var balanceUSSDCode = ""
    @AppStorage("ussd.balanceSim") var balanceUSSDSim = -1
    /// The bridge's signal, network and battery as a Live Activity on the Lock Screen and in the Dynamic Island (D-053).
    @AppStorage("liveActivity.bridge") var bridgeLiveActivity = true

    var savedUSSDCodes: [String] {
        get { savedUSSDCodesRaw.split(separator: "\n").map(String.init) }
        set { savedUSSDCodesRaw = newValue.joined(separator: "\n") }
    }
}
