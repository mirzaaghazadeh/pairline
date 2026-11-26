import AppIntents
import Foundation

/// "Call with Pairline" for Siri and the Shortcuts app (D-074): calls a number through the bridge phone. Text shared
/// into a Shortcut works too: the first phone number in it is called. Runs only when the user runs it.
struct CallWithPairlineIntent: AppIntent {
    static var title: LocalizedStringResource = LocalizedStringResource("Call with Pairline", table: "PhoneTools")
    static var description = IntentDescription(LocalizedStringResource(
        "Calls a number through your bridge phone's SIM.", table: "PhoneTools"))
    static var openAppWhenRun = true

    @Parameter(title: LocalizedStringResource("Number", table: "PhoneTools"))
    var number: String

    @MainActor
    func perform() async throws -> some IntentResult {
        AppModel.shared.dialFromOutside(number)
        return .result()
    }
}

struct PairlineShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: CallWithPairlineIntent(), phrases: [
            "Call with \(.applicationName)",
            "Make a \(.applicationName) call",
        ])
    }
}
