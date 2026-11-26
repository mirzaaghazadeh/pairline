import Foundation
import UIKit

/// The app's language (D-081). On a fresh install Pairline starts in Persian, Arabic or Turkish when the iPhone is in
/// English (or a language Pairline doesn't have) and the person clearly uses one of those: they have its keyboard, and
/// when they have several, the time zone or region picks among them. With no keyboard list to read, the time zone,
/// then the region decides. Tehran's time zone always means Persian (D-090). The choice is the same per-app language as iPhone Settings › Pairline › Language, where
/// it can be changed, and it has to be made before anything loads a string, so `PairlineMain` runs it first.
/// Nothing is sent anywhere. Following D-033 the app never lists its languages; it only shows the current one.
enum AppLanguage {
    static let ours: Set<String> = ["fa", "ar", "tr"]
    /// "" once the first-open check ran and picked nothing, else the language it set. Absent before.
    static let pickedKey = "language.firstOpenPick"

    private static let languageByCountry: [String: String] = {
        var map: [String: String] = ["IR": "fa", "AF": "fa", "TR": "tr"]
        for country in ["SA", "AE", "QA", "KW", "BH", "OM", "YE", "IQ", "SY", "JO", "LB", "PS",
                        "EG", "LY", "TN", "DZ", "MA", "SD", "MR"] {
            map[country] = "ar"
        }
        return map
    }()

    /// Time zones of those countries (iOS has no time zone → country lookup).
    private static let countryByZone: [String: String] = [
        "Asia/Tehran": "IR", "Asia/Kabul": "AF", "Europe/Istanbul": "TR", "Asia/Istanbul": "TR",
        "Asia/Riyadh": "SA", "Asia/Dubai": "AE", "Asia/Qatar": "QA", "Asia/Kuwait": "KW", "Asia/Bahrain": "BH",
        "Asia/Muscat": "OM", "Asia/Aden": "YE", "Asia/Baghdad": "IQ", "Asia/Damascus": "SY", "Asia/Amman": "JO",
        "Asia/Beirut": "LB", "Asia/Gaza": "PS", "Asia/Hebron": "PS", "Africa/Cairo": "EG", "Africa/Tripoli": "LY",
        "Africa/Tunis": "TN", "Africa/Algiers": "DZ", "Africa/Casablanca": "MA", "Africa/El_Aaiun": "MA",
        "Africa/Khartoum": "SD", "Africa/Nouakchott": "MR",
    ]

    /// The language to start in, or nil to follow the iPhone. `keyboards` are the keyboards' languages ("en-US", "fa";
    /// identifiers such as "fa_IR@sw=Persian" work too), nil when unknown; `zone` a time zone identifier; `region` an
    /// ISO code.
    static func suggest(systemLanguage: String, keyboards: [String]?, zone: String?, region: String?) -> String? {
        if ours.contains(systemLanguage.lowercased()) { return nil }
        // Tehran's time zone is used only in Iran: Persian whatever the keyboards (D-090).
        if zone.flatMap({ countryByZone[$0] }) == "IR" { return "fa" }
        let fromCountry = [zone.flatMap { countryByZone[$0] }, region?.uppercased()]
            .compactMap { $0.flatMap { languageByCountry[$0] } }
        guard let keyboards else { return fromCountry.first }
        var typed: [String] = []
        for keyboard in keyboards {
            let code = keyboard.split(separator: "@").first?.split(whereSeparator: { $0 == "_" || $0 == "-" }).first
            if let code = code.map({ String($0).lowercased() }), ours.contains(code), !typed.contains(code) {
                typed.append(code)
            }
        }
        if typed.count <= 1 { return typed.first }
        return fromCountry.first(where: typed.contains)
    }

    /// Runs once, before the UI: only on a fresh install, and only if no language was chosen in iPhone Settings.
    static func applyOnFirstOpen(defaults: UserDefaults = .standard, underTest: Bool = isUnderTest) {
        // The unit tests' host app keeps the Simulator's language, so tests see the strings they expect.
        guard !underTest, defaults.object(forKey: pickedKey) == nil else { return }
        defaults.set("", forKey: pickedKey)
        // Someone who already used Pairline keeps the language they had.
        guard defaults.object(forKey: "onboarding.done") == nil else { return }
        if let id = Bundle.main.bundleIdentifier, defaults.persistentDomain(forName: id)?["AppleLanguages"] != nil { return }
        let system = Locale.preferredLanguages.first
            .flatMap { Locale(identifier: $0).language.languageCode?.identifier } ?? "en"
        // The keyboards the person added ("en-US", "fa", "emoji"); readable before the UI starts.
        let keyboards = UITextInputMode.activeInputModes.compactMap(\.primaryLanguage)
        guard let tag = suggest(
            systemLanguage: system,
            keyboards: keyboards.isEmpty ? nil : keyboards,
            zone: TimeZone.current.identifier,
            region: Locale.current.region?.identifier
        ) else { return }
        defaults.set([tag], forKey: "AppleLanguages")
        defaults.set(tag, forKey: pickedKey)
    }

    private static var isUnderTest: Bool { ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil }

    /// The language the app runs in, named in itself ("English", "Türkçe").
    static var shownName: String {
        let code = Bundle.main.preferredLocalizations.first ?? "en"
        return Locale(identifier: code).localizedString(forLanguageCode: code)?.capitalized ?? code
    }

    /// iPhone Settings › Pairline, where iOS lists the languages.
    @MainActor static func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }
}

/// Picks the first-open language before SwiftUI starts and anything reads a string (D-081).
@main
enum PairlineMain {
    static func main() {
        AppLanguage.applyOnFirstOpen()
        PairlineApp.main()
    }
}
