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
        return true
    }
}
