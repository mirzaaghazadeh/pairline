import SwiftUI

/// A screen as two panes on a large, regular-width display (iPhone Duo's inner display, iPad), or as its usual single
/// column on a phone-sized one (D-063).
///
/// Side by side when the space is wider than tall, `first` above `second` when it's taller. The gap between them sits on
/// iPhone Duo's fold, read from its division region (iOS 27.1) including while the device lies flat, so nothing moves
/// when it bends and nothing tappable lands in the curve. Without a fold the panes split the space in half.
///
/// `first` is what people look at (the caller, the typed number, artwork) and `second` holds the controls, so these end
/// up on the trailing side or on the bottom half, the flat half of a device standing like a laptop. `single` is shown
/// on compact-width screens and when the screen doesn't fit the allowed `axes`.
///
/// Keep state in the screen, not in the panes: opening or folding the device switches between these layouts.
struct FoldAwarePanes<First: View, Second: View, Single: View>: View {
    var axes: Axis.Set = [.horizontal, .vertical]
    @ViewBuilder var first: First
    @ViewBuilder var second: Second
    @ViewBuilder var single: Single
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        if sizeClass == .regular {
            GeometryReader { proxy in
                if let split = PaneSplit(proxy: proxy, axes: axes) {
                    if split.axis == .horizontal {
                        HStack(spacing: 0) {
                            first.frame(width: split.firstLength).frame(maxHeight: .infinity)
                            Color.clear.frame(width: split.gap)
                            second.frame(maxWidth: .infinity, maxHeight: .infinity)
                        }
                    } else {
                        VStack(spacing: 0) {
                            first.frame(height: split.firstLength).frame(maxWidth: .infinity)
                            Color.clear.frame(height: split.gap)
                            second.frame(maxWidth: .infinity, maxHeight: .infinity)
                        }
                    }
                } else {
                    single.frame(width: proxy.size.width, height: proxy.size.height)
                }
            }
        } else {
            single
        }
    }
}

/// A list beside its detail on a large display (D-063): conversations and the open one, contacts and a contact, settings
/// and a settings page. The system split view, with its own column widths: the list stays narrow and the detail gets the
/// rest (on iPhone Duo the fold runs through the detail, which is fine for reading while the device lies flat), and only
/// the detail column gets iPhone Duo's vertical side bar. Only used on regular-width screens; phones push the detail.
struct ListDetailSplit<ListContent: View, Detail: View>: View {
    @ViewBuilder var list: ListContent
    @ViewBuilder var detail: Detail

    var body: some View {
        NavigationSplitView {
            list
        } detail: {
            NavigationStack { detail }
        }
        .navigationSplitViewStyle(.balanced)
    }
}

/// A scroll view whose content sits in the middle of the pane when it's shorter than the pane, so a short pane has no
/// empty end, and scrolls when it's taller.
struct CenteredScroll<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                content
                    .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
        }
    }
}

/// Where two panes meet in a given space: on the fold when there is one, otherwise in the middle.
struct PaneSplit: Equatable {
    var axis: Axis
    /// The width (side by side) or height (stacked) of the first pane.
    var firstLength: CGFloat
    var gap: CGFloat

    /// Apple's gap between panes on iPhone Duo's inner display, and the least the fold gets.
    static let minimumGap: CGFloat = 24
    /// Below this a pane is too small for a phone-sized layout, so the screen stays in one column.
    static let minimumPane: CGFloat = 300

    init?(proxy: GeometryProxy, axes: Axis.Set) {
        var fold: CGRect?
        // Xcode 27's SDK (Swift 6.4) has reservedRegions; GitHub's Mac runners stop at Xcode 26.6, whose SDK doesn't,
        // so CI builds without it (the panes then meet in the middle). Release builds come from Xcode 27.2 (AGENTS.md).
        #if compiler(>=6.4)
        if #available(iOS 27.1, *) {
            // Inactive too, so the layout is already clear of the fold while the device lies flat. The frame includes
            // the clearance the system keeps for controls, and is mirrored like the stacks in right-to-left languages.
            fold = proxy.reservedRegions(kind: .division, options: .includeInactive).first?.frame
        }
        #endif
        self.init(size: proxy.size, fold: fold, axes: axes)
    }

    init?(size: CGSize, fold: CGRect?, axes: Axis.Set) {
        guard size.width > 0, size.height > 0 else { return nil }
        let fold = fold.flatMap { $0.isNull || $0.isInfinite ? nil : $0 }
        // A fold that runs top to bottom puts the panes side by side; one that runs across stacks them.
        let horizontal: Bool
        if let fold, fold.width != fold.height {
            horizontal = fold.height > fold.width
        } else {
            horizontal = size.width > size.height
        }
        axis = horizontal ? .horizontal : .vertical
        guard axes.contains(horizontal ? .horizontal : .vertical) else { return nil }

        let length = horizontal ? size.width : size.height
        var start = length / 2, end = length / 2
        if let fold {
            let foldStart = horizontal ? fold.minX : fold.minY
            let foldEnd = horizontal ? fold.maxX : fold.maxY
            // Only a fold inside this space counts (in Split View the app may sit beside it).
            if foldEnd > 0 && foldStart < length {
                start = max(0, foldStart)
                end = min(length, foldEnd)
            }
        }
        if end - start < Self.minimumGap {
            let middle = (start + end) / 2
            start = middle - Self.minimumGap / 2
            end = middle + Self.minimumGap / 2
        }
        guard start >= Self.minimumPane, length - end >= Self.minimumPane else { return nil }
        firstLength = start
        gap = end - start
    }
}
