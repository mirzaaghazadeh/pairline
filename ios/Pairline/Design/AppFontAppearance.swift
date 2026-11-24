import UIKit

/// Gives the UIKit-drawn parts of the SwiftUI app (navigation bar titles, tab bar labels, bar buttons, segmented
/// pickers) the same Estedad faces as `AppFont` in Persian (D-032). Does nothing in English and Arabic.
enum AppFontAppearance {
    static func apply() {
        guard AppFont.usesEstedad else { return }

        let navigation = UINavigationBar.appearance()
        navigation.titleTextAttributes = [.font: font("Estedad-SemiBold", 17, .headline)]
        navigation.largeTitleTextAttributes = [.font: font("Estedad-Bold", 34, .largeTitle)]

        let barButton = [NSAttributedString.Key.font: font("Estedad-Regular", 17, .body)]
        for state: UIControl.State in [.normal, .highlighted, .disabled] {
            UIBarButtonItem.appearance().setTitleTextAttributes(barButton, for: state)
        }

        let tab = [NSAttributedString.Key.font: font("Estedad-Medium", 10, .caption2)]
        UITabBarItem.appearance().setTitleTextAttributes(tab, for: .normal)
        UITabBarItem.appearance().setTitleTextAttributes(tab, for: .selected)

        let segment = [NSAttributedString.Key.font: font("Estedad-Medium", 13, .subheadline)]
        UISegmentedControl.appearance().setTitleTextAttributes(segment, for: .normal)
        UISegmentedControl.appearance().setTitleTextAttributes(segment, for: .selected)
    }

    private static func font(_ name: String, _ size: CGFloat, _ style: UIFont.TextStyle) -> UIFont {
        guard let base = UIFont(name: name, size: size) else {
            log.error("app", "Bundled font \(name) is missing; using the system font")
            return .preferredFont(forTextStyle: style)
        }
        return UIFontMetrics(forTextStyle: style).scaledFont(for: base)
    }
}
