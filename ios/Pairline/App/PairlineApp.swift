import CloudKit
import SwiftUI
import UIKit

@main
struct PairlineApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = AppModel.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                // Text without its own font uses Estedad in Persian, the system body font otherwise (D-032).
                .environment(\.font, AppFont.usesEstedad ? .body : nil)
                .environmentObject(model)
                .environmentObject(model.purchases)
                .environmentObject(model.settings)
                .onOpenURL { model.handleURL($0) }
                .onValueChange(scenePhase) { phase in
                    if phase == .active { model.didBecomeActive() }
                }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        if launchOptions?[.bluetoothCentrals] != nil {
            log.info("app", "Launched by iOS for a Bluetooth event")
        }
        AppFontAppearance.apply()
        // Creates the link (and the Bluetooth central, for state restoration) as early as possible.
        _ = AppModel.shared
        #if DEBUG
        RemoteSpike.runIfRequested()
        #endif
        return true
    }

    // CloudKit picks the push token up by itself; these only record whether iOS gave one (never the token).
    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        log.info("remote", "Registered for remote notifications")
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        log.info("remote", "Remote notifications unavailable: \((error as NSError).code)")
    }

    /// Remote mode (D-072): a silent push from the quiet mailbox (or any CloudKit push that wakes the app). Catches up
    /// on the mailboxes; replies to waiting requests and events are handled as if they came over a session.
    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        guard RemoteFeature.isAvailable, CKNotification(fromRemoteNotificationDictionary: userInfo) != nil else {
            return completionHandler(.noData)
        }
        Task { @MainActor in
            let got = await AppModel.shared.remoteCatchUp()
            completionHandler(got ? .newData : .noData)
        }
    }

    /// The user swiped Pairline away: iOS won't relaunch it for Bluetooth until it's opened again, so calls and
    /// messages stop reaching this iPhone. Say so while iOS still lets us (it doesn't call this for a suspended app).
    func applicationWillTerminate(_ application: UIApplication) {
        let model = AppModel.shared
        guard model.isPaired, !model.isDemo else { return }
        log.info("app", "Closed by the user")
        Notifier.shared.notifyCallsPaused()
    }
}
