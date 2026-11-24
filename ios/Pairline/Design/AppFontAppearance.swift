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

        UITabBar.appearance().standardAppearance = tabBarAppearance()

        let segment = [NSAttributedString.Key.font: font("Estedad-Medium", 13, .subheadline)]
        UISegmentedControl.appearance().setTitleTextAttributes(segment, for: .normal)
        UISegmentedControl.appearance().setTitleTextAttributes(segment, for: .selected)
    }

    /// Tab bar labels. The tab bar draws them from its `UITabBarAppearance`, which ignores the older
    /// `UITabBarItem.appearance()` title attributes. Only the font is set, so the colors and the default background
    /// stay; `scrollEdgeAppearance` stays nil, so UIKit still derives the transparent look at the scroll edge.
    private static func tabBarAppearance() -> UITabBarAppearance {
        let title = [NSAttributedString.Key.font: font("Estedad-Medium", 10, .caption2)]
        let item = UITabBarItemAppearance()
        for state in [item.normal, item.selected, item.disabled, item.focused] {
            state.titleTextAttributes = title
        }
        let appearance = UITabBarAppearance()
        appearance.stackedLayoutAppearance = item
        appearance.inlineLayoutAppearance = item
        appearance.compactInlineLayoutAppearance = item
        return appearance
    }

    private static func font(_ name: String, _ size: CGFloat, _ style: UIFont.TextStyle) -> UIFont {
        guard let base = UIFont(name: name, size: size) else {
            log.error("app", "Bundled font \(name) is missing; using the system font")
            return .preferredFont(forTextStyle: style)
        }
        return UIFontMetrics(forTextStyle: style).scaledFont(for: base)
    }
}
