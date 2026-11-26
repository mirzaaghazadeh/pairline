import SwiftUI

// Helpers for Reduce Motion and the accessibility text sizes (docs/app-store/accessibility.md). Increase Contrast is
// handled by the colors themselves: every brand color has a high-contrast variant in the asset catalog.

extension AnyTransition {
    /// Slides in from `edge` with a fade; only the fade when Reduce Motion is on.
    static func slide(from edge: Edge, reduceMotion: Bool) -> AnyTransition {
        reduceMotion ? .opacity : .move(edge: edge).combined(with: .opacity)
    }
}

extension Animation {
    /// `animation`, or a short ease without springs or bounces when Reduce Motion is on.
    static func motion(_ animation: Animation, reduceMotion: Bool) -> Animation {
        reduceMotion ? .easeInOut(duration: 0.2) : animation
    }
}

/// A row that becomes a column at the accessibility text sizes, so long text wraps instead of being cut off.
struct AdaptiveStack<Content: View>: View {
    var horizontalAlignment: HorizontalAlignment = .leading
    var verticalAlignment: VerticalAlignment = .center
    var spacing: CGFloat?
    @ViewBuilder var content: () -> Content
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: horizontalAlignment, spacing: spacing))
            : AnyLayout(HStackLayout(alignment: verticalAlignment, spacing: spacing))
        layout { content() }
    }
}
