import Foundation

/// Pairline's Telegram news channel and help-group topic for the language the app is shown in. Persian and Turkish
/// have their own; everyone else, Arabic included, gets the English ones (the website follows the same rule).
/// Opening one is a tap on a link that iOS hands to Telegram or Safari: the app itself never contacts Telegram.
extension AppInfo {
    static var telegramChannelURL: URL { URL(string: "https://t.me/pairline_\(telegramLanguage)")! }

    static var telegramGroupURL: URL {
        // English is the group's General topic (1); Persian and Turkish have topics of their own.
        let topic = ["en": 1, "fa": 5, "tr": 7][telegramLanguage] ?? 1
        return URL(string: "https://t.me/pairline_groups/\(topic)")!
    }

    private static var telegramLanguage: String {
        let code = Bundle.main.preferredLocalizations.first ?? "en"
        if code.hasPrefix("fa") { return "fa" }
        if code.hasPrefix("tr") { return "tr" }
        return "en"
    }
}
