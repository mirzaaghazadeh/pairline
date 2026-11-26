import XCTest
@testable import Pairline

/// The first-open language (D-081).
final class AppLanguageTests: XCTestCase {
    private let en = "en_US@sw=QWERTY;hw=Automatic"
    private let fa = "fa_IR@sw=Persian;hw=Automatic"
    private let tr = "tr_TR@sw=QWERTY-Turkish;hw=Automatic"
    private let ar = "ar@sw=Arabic;hw=Automatic"

    func testOneOfOurKeyboardsDecides() {
        XCTAssertEqual(AppLanguage.suggest(systemLanguage: "en", keyboards: [en, fa, "emoji@sw=Emoji"], zone: "Europe/Istanbul", region: "US"), "fa")
        XCTAssertEqual(AppLanguage.suggest(systemLanguage: "de", keyboards: [ar], zone: "Europe/Berlin", region: "DE"), "ar")
    }

    func testSeveralKeyboardsAreSettledByTimeZoneThenRegion() {
        XCTAssertEqual(AppLanguage.suggest(systemLanguage: "en", keyboards: [en, fa, tr], zone: "Europe/Istanbul", region: "IR"), "tr")
        XCTAssertEqual(AppLanguage.suggest(systemLanguage: "en", keyboards: [en, fa, tr], zone: "Europe/London", region: "IR"), "fa")
        XCTAssertNil(AppLanguage.suggest(systemLanguage: "en", keyboards: [en, fa, tr], zone: "Europe/London", region: "GB"))
    }

    func testWithoutOurKeyboardsTheIPhoneKeepsItsLanguage() {
        // An English speaker living in Dubai.
        XCTAssertNil(AppLanguage.suggest(systemLanguage: "en", keyboards: [en, "emoji@sw=Emoji"], zone: "Asia/Dubai", region: "AE"))
    }

    func testWithoutAKeyboardListTheTimeZoneThenRegionDecide() {
        XCTAssertEqual(AppLanguage.suggest(systemLanguage: "en", keyboards: nil, zone: "Asia/Tehran", region: "US"), "fa")
        XCTAssertEqual(AppLanguage.suggest(systemLanguage: "en", keyboards: nil, zone: "America/New_York", region: "tr"), "tr")
        XCTAssertNil(AppLanguage.suggest(systemLanguage: "en", keyboards: nil, zone: "America/New_York", region: "US"))
    }

    func testAnIPhoneAlreadyInOneOfOurLanguagesIsLeftAlone() {
        XCTAssertNil(AppLanguage.suggest(systemLanguage: "tr", keyboards: [fa], zone: "Asia/Tehran", region: "IR"))
        XCTAssertNil(AppLanguage.suggest(systemLanguage: "fa", keyboards: [tr], zone: "Europe/Istanbul", region: "TR"))
    }

    func testRunsOnlyOnAFreshInstall() {
        let name = "AppLanguageTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        defaults.set(true, forKey: "onboarding.done")
        AppLanguage.applyOnFirstOpen(defaults: defaults, underTest: false)
        XCTAssertEqual(defaults.string(forKey: AppLanguage.pickedKey), "")
        XCTAssertNil(defaults.persistentDomain(forName: name)?["AppleLanguages"])
    }
}
