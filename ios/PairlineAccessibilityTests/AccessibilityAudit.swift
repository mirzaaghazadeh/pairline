import XCTest

/// The accessibility audit behind the App Store's Accessibility Nutrition Labels (D-091, D-096). Opens each screen in
/// demo mode, saves a screenshot and prints every issue Apple's automated audit finds as an `A11Y|…` line; it never
/// fails on them. `ios/tools/accessibility-audit.sh` runs it in each mode and turns the lines into a report.
///
/// Settings from the script (as `TEST_RUNNER_` environment variables):
/// - `A11Y_OUT`: folder for the screenshots; `A11Y_TAG`: the mode's name, used in file names and output lines;
/// - `A11Y_SIZE`: a content size category such as `UICTContentSizeCategoryAccessibilityXL`;
/// - `A11Y_LANG`: the app's language (default `en`). Screens reached by tapping need English labels, so in other
///   languages only the ones opened by launch arguments are audited.
final class AccessibilityAudit: XCTestCase {
    private struct Screen {
        let name: String
        var args: [String] = []
        /// Labels to tap, in order, from the screen the arguments open (each label is matched by its beginning).
        var path: [String] = []
        /// A label to scroll to without tapping it, for a section further down a screen.
        var scrollTo: String?
        var wait: UInt32 = 3
    }

    private let screens: [Screen] = [
        Screen(name: "onboarding", args: ["-PairlineResetOnboarding"]),
        Screen(name: "pairing", args: ["-PairlineResetOnboarding", "-PairlinePairingScan"]),
        Screen(name: "messages", args: ["-PairlineDemo", "-PairlineTab", "messages"]),
        Screen(name: "thread", args: ["-PairlineDemo", "-PairlineThread", "1"], wait: 4),
        Screen(name: "calls", args: ["-PairlineDemo", "-PairlineTab", "calls"]),
        Screen(name: "contacts", args: ["-PairlineDemo", "-PairlineTab", "contacts"]),
        Screen(name: "bridge", args: ["-PairlineDemo", "-PairlineTab", "bridge"]),
        Screen(name: "settings", args: ["-PairlineDemo", "-PairlineTab", "settings"]),
        Screen(name: "paywall", args: ["-PairlineDemo", "-PairlinePaywall"]),
        Screen(name: "call", args: ["-PairlineDemo", "-PairlineTab", "calls", "-PairlineDemoCall"], wait: 5),
        Screen(name: "ussd", args: ["-PairlineDemo", "-PairlineTab", "bridge"], path: ["USSD codes"]),
        Screen(name: "carrier", args: ["-PairlineDemo", "-PairlineTab", "bridge"],
               path: ["USSD codes", "Call forwarding, waiting and caller ID"]),
        Screen(name: "phone-tools", args: ["-PairlineDemo", "-PairlineTab", "bridge"], path: ["Find phone"]),
        Screen(name: "files", args: ["-PairlineDemo", "-PairlineTab", "bridge"], path: ["Send files"]),
        Screen(name: "photos", args: ["-PairlineDemo", "-PairlineTab", "bridge"], path: ["Photos on the Android phone"]),
        Screen(name: "help", args: ["-PairlineDemo", "-PairlineTab", "settings"], path: ["Help center"]),
        Screen(name: "netshare", args: ["-PairlineDemo", "-PairlineTab", "settings"], path: ["Internet sharing"]),
        Screen(name: "blocked", args: ["-PairlineDemo", "-PairlineTab", "settings"], path: ["Blocked numbers"]),
        Screen(name: "export", args: ["-PairlineDemo", "-PairlineTab", "settings"], path: ["Export messages"]),
        Screen(name: "privacy", args: ["-PairlineDemo", "-PairlineTab", "settings"], path: ["Privacy"]),
        Screen(name: "diagnostics", args: ["-PairlineDemo", "-PairlineTab", "settings"], path: ["Diagnostics"]),
        Screen(name: "about", args: ["-PairlineDemo", "-PairlineTab", "settings"], path: ["About Pairline"]),
        // Cloud mode (D-092..D-095)
        Screen(name: "pairing-cloud", args: ["-PairlineResetOnboarding", "-PairlinePairingCloudStep"]),
        Screen(name: "pairing-remote", args: ["-PairlineResetOnboarding", "-PairlinePairingRemote"]),
        Screen(name: "settings-cloud", args: ["-PairlineDemo", "-PairlineTab", "settings"], scrollTo: "How Cloud mode works"),
        Screen(name: "cloud-intro", args: ["-PairlineDemo", "-PairlineTab", "settings"], path: ["How Cloud mode works"]),
        Screen(name: "cloud-check", args: ["-PairlineDemo", "-PairlineTab", "settings"], path: ["Check my home connection"]),
        Screen(name: "cloud-ports", args: ["-PairlineDemo", "-PairlineTab", "settings"], path: ["Port forwarding"]),
        Screen(name: "cloud-log", args: ["-PairlineDemo", "-PairlineTab", "settings"], path: ["Connection log"]),
        Screen(name: "router-guide", args: ["-PairlineDemo", "-PairlineTab", "settings"],
               path: ["Help center", "Allow calls from far away"]),
    ]

    func testAudit() throws {
        guard #available(iOS 17, *) else { throw XCTSkip("The accessibility audit needs iOS 17") }
        let env = ProcessInfo.processInfo.environment
        let out = env["A11Y_OUT"] ?? NSTemporaryDirectory()
        let tag = env["A11Y_TAG"] ?? "default"
        let lang = env["A11Y_LANG"] ?? "en"
        var extra = ["-PairlineAccess", "purchased", "-AppleLanguages", "(\(lang))"]
        if let size = env["A11Y_SIZE"] { extra += ["-UIPreferredContentSizeCategoryName", size] }

        for screen in screens {
            if (!screen.path.isEmpty || screen.scrollTo != nil) && lang != "en" {
                print("A11Y-SKIP|\(tag)|\(screen.name)|opened by tapping English labels")
                continue
            }
            let app = XCUIApplication()
            app.launchArguments = screen.args + extra
            app.launch()
            sleep(screen.wait)
            if let problem = open(screen, in: app) {
                print("A11Y-SKIP|\(tag)|\(screen.name)|\(problem)")
                app.terminate()
                continue
            }
            try? XCUIScreen.main.screenshot().pngRepresentation
                .write(to: URL(fileURLWithPath: "\(out)/\(tag)-\(screen.name).png"))
            print("A11Y-SCREEN|\(tag)|\(screen.name)")
            do {
                try app.performAccessibilityAudit(for: .all) { issue in
                    let element = issue.element.map {
                        let f = $0.frame
                        return "type=\($0.elementType.rawValue) label='\($0.label.replacingOccurrences(of: "|", with: "/"))' "
                            + "frame=\(Int(f.minX)),\(Int(f.minY)),\(Int(f.width)),\(Int(f.height))"
                    } ?? "-"
                    print("A11Y|\(tag)|\(screen.name)|\(Self.kind(issue.auditType))|\(issue.compactDescription)|\(element)")
                    return true
                }
            } catch {
                // A screen that never settles (a running call timer) can time the audit out.
                print("A11Y-SKIP|\(tag)|\(screen.name)|the audit didn't finish: \(error.localizedDescription)")
            }
            app.terminate()
        }
    }

    /// Taps each label of the path in turn (links and rows only, scrolling down to find them), then scrolls to
    /// `scrollTo`. Returns why it couldn't, or nil once the screen is open.
    private func open(_ screen: Screen, in app: XCUIApplication) -> String? {
        for label in screen.path {
            guard let target = find(label, in: app, tappable: true) else {
                return "no '\(label)' to tap (not shown in demo mode?)"
            }
            target.tap()
            sleep(2)
        }
        // Every path leads to a pushed screen, which has a back button.
        if !screen.path.isEmpty && !app.navigationBars.buttons.firstMatch.exists {
            return "tapping '\(screen.path.last ?? "")' didn't open a screen"
        }
        if let label = screen.scrollTo, find(label, in: app, tappable: false) == nil {
            return "no '\(label)' on the screen"
        }
        return nil
    }

    private func find(_ label: String, in app: XCUIApplication, tappable: Bool) -> XCUIElement? {
        let predicate = NSPredicate(format: "label BEGINSWITH %@", label)
        var candidates = [app.buttons.matching(predicate).firstMatch, app.cells.matching(predicate).firstMatch]
        if !tappable { candidates.append(app.staticTexts.matching(predicate).firstMatch) }
        for _ in 0..<10 {
            if let found = candidates.first(where: { $0.exists && $0.isHittable }) { return found }
            app.swipeUp()
        }
        return nil
    }

    @available(iOS 17, *)
    private static func kind(_ type: XCUIAccessibilityAuditType) -> String {
        let names: [(XCUIAccessibilityAuditType, String)] = [
            (.contrast, "contrast"), (.elementDetection, "elementDetection"), (.hitRegion, "hitRegion"),
            (.sufficientElementDescription, "description"), (.dynamicType, "dynamicType"), (.textClipped, "textClipped"),
            (.trait, "trait"),
        ]
        let matched = names.filter { type.contains($0.0) }.map(\.1)
        return matched.isEmpty ? "other(\(type.rawValue))" : matched.joined(separator: "+")
    }
}
