import SwiftUI

/// Persian text uses the bundled Estedad typeface (D-032); English and Arabic keep the system font.
///
/// The fonts live in `Shared/Fonts` and are listed under `UIAppFonts` in the app's and the widget's Info.plist.
enum AppFont {
    /// True when the app runs in Persian. iOS relaunches the app when its language changes, so this is read once.
    static let usesEstedad: Bool = Bundle.main.preferredLocalizations.first == "fa"

    /// PostScript name of the bundled face closest to `weight` (Regular, Medium, SemiBold and Bold are bundled).
    static func faceName(_ weight: Font.Weight) -> String {
        switch weight {
        case .bold, .heavy, .black: return "Estedad-Bold"
        case .semibold: return "Estedad-SemiBold"
        case .medium: return "Estedad-Medium"
        default: return "Estedad-Regular"
        }
    }

    /// Point size of each text style at the default Dynamic Type size (Large). `relativeTo:` scales it from there.
    static func baseSize(_ style: Font.TextStyle) -> CGFloat {
        switch style {
        case .largeTitle: return 34
        case .title: return 28
        case .title2: return 22
        case .title3: return 20
        case .headline, .body: return 17
        case .callout: return 16
        case .subheadline: return 15
        case .footnote: return 13
        case .caption: return 12
        case .caption2: return 11
        @unknown default: return 17
        }
    }

    /// The font for a text style: Estedad in Persian (scaling with Dynamic Type), the system font otherwise.
    static func text(_ style: Font.TextStyle) -> Font {
        guard usesEstedad else { return .system(style) }
        let weight: Font.Weight = style == .headline ? .semibold : .regular
        return .custom(faceName(weight), size: baseSize(style), relativeTo: style)
    }
}

/// These members deliberately shadow SwiftUI's own `Font.largeTitle` … `Font.caption2` inside this module (Swift
/// prefers a declaration from the importing module over an identical one it imports), so every `.font(.headline)`,
/// `.font(.subheadline.weight(.semibold))` and so on in the app and the widget picks Estedad in Persian without a
/// custom modifier at each call site. In English and Arabic they return `Font.system(style)`, which is exactly what
/// SwiftUI's own members return. Don't refer to these from inside `AppFont`; use `Font.system(_:)` there.
extension Font {
    static var largeTitle: Font { AppFont.text(.largeTitle) }
    static var title: Font { AppFont.text(.title) }
    static var title2: Font { AppFont.text(.title2) }
    static var title3: Font { AppFont.text(.title3) }
    static var headline: Font { AppFont.text(.headline) }
    static var subheadline: Font { AppFont.text(.subheadline) }
    static var body: Font { AppFont.text(.body) }
    static var callout: Font { AppFont.text(.callout) }
    static var footnote: Font { AppFont.text(.footnote) }
    static var caption: Font { AppFont.text(.caption) }
    static var caption2: Font { AppFont.text(.caption2) }

    /// Display text in the rounded system design (titles, the price, the battery level); Estedad in Persian.
    static func rounded(_ style: Font.TextStyle, weight: Font.Weight) -> Font {
        guard AppFont.usesEstedad else { return .system(style, design: .rounded).weight(weight) }
        return .custom(AppFont.faceName(weight), size: AppFont.baseSize(style), relativeTo: style)
    }

    /// Fixed-size rounded text (avatar initials); Estedad in Persian. Like `.system(size:)`, it doesn't scale.
    static func rounded(size: CGFloat, weight: Font.Weight) -> Font {
        guard AppFont.usesEstedad else { return .system(size: size, weight: weight, design: .rounded) }
        return .custom(AppFont.faceName(weight), fixedSize: size)
    }
}
