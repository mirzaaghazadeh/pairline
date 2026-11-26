import SwiftUI

extension Color {
    static let brandBlue = Color("BrandBlue")
    /// Blue for filled surfaces behind white text and glyphs (stays deep in dark mode).
    static let brandFill = Color("BrandFill")
    static let brandGreen = Color("BrandGreen")
    static let brandAmber = Color("BrandAmber")
    static let brandRed = Color("BrandRed")
    static let bubbleIncoming = Color("BubbleIncoming")
    static let cardBackground = Color("CardBackground")
}

enum Metrics {
    static let corner: CGFloat = 18
    static let smallCorner: CGFloat = 12
    static let padding: CGFloat = 16
}

/// The large, filled call-to-action button.
struct PrimaryButtonStyle: ButtonStyle {
    var tint: Color = .brandFill
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 52)
            .padding(.horizontal, 16)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(tint.opacity(isEnabled ? 1 : 0.4))
            )
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .opacity(configuration.isPressed ? 0.9 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.8), value: configuration.isPressed)
    }
}

/// The tinted, secondary button.
struct SecondaryButtonStyle: ButtonStyle {
    var tint: Color = .brandBlue

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(tint)
            .frame(maxWidth: .infinity, minHeight: 50)
            .padding(.horizontal, 16)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(tint.opacity(0.12))
            )
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.8), value: configuration.isPressed)
    }
}

struct CardModifier: ViewModifier {
    var padding: CGFloat = Metrics.padding

    func body(content: Content) -> some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: Metrics.corner, style: .continuous)
                    .fill(Color.cardBackground)
            )
            .overlay(
                RoundedRectangle(cornerRadius: Metrics.corner, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.05))
            )
    }
}

extension View {
    func card(padding: CGFloat = Metrics.padding) -> some View {
        modifier(CardModifier(padding: padding))
    }

    /// iOS 16-compatible `onChange` that passes the new value.
    func onValueChange<V: Equatable>(_ value: V, perform: @escaping (V) -> Void) -> some View {
        onChange(of: value, perform: perform)
    }

    /// A scroll view that only moves when its content is taller than the screen (iOS 16.4 and later), so a page that
    /// fits doesn't look scrollable.
    @ViewBuilder func scrollsOnlyWhenNeeded() -> some View {
        if #available(iOS 16.4, *) {
            scrollBounceBehavior(.basedOnSize)
        } else {
            self
        }
    }
}

enum Formatters {
    /// "9:41", "Yesterday", "Tuesday" or "12/03/26" for list rows.
    static func listTime(_ date: Date, now: Date = Date()) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            return date.formatted(date: .omitted, time: .shortened)
        }
        if calendar.isDateInYesterday(date) {
            return String(localized: "Yesterday")
        }
        if let days = calendar.dateComponents([.day], from: date, to: now).day, days < 7 {
            return date.formatted(.dateTime.weekday(.wide))
        }
        return date.formatted(date: .numeric, time: .omitted)
    }

    /// Day separators in a conversation.
    static func dayHeader(_ date: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return String(localized: "Today") }
        if calendar.isDateInYesterday(date) { return String(localized: "Yesterday") }
        return date.formatted(.dateTime.weekday(.wide).day().month(.wide))
    }

    /// A calendar in the app's own language, so formatted durations match the UI language.
    static var appCalendar: Calendar {
        var calendar = Calendar.current
        calendar.locale = Locale(identifier: Bundle.main.preferredLocalizations.first ?? "en")
        return calendar
    }

    static func duration(_ seconds: Int) -> String {
        let formatter = DateComponentsFormatter()
        formatter.calendar = appCalendar
        formatter.unitsStyle = .abbreviated
        formatter.allowedUnits = seconds >= 3600 ? [.hour, .minute] : [.minute, .second]
        formatter.zeroFormattingBehavior = .dropAll
        return formatter.string(from: TimeInterval(seconds)) ?? ""
    }

    static func clock(_ seconds: Int) -> String {
        let formatter = DateComponentsFormatter()
        formatter.unitsStyle = .positional
        formatter.allowedUnits = seconds >= 3600 ? [.hour, .minute, .second] : [.minute, .second]
        formatter.zeroFormattingBehavior = .pad
        return formatter.string(from: TimeInterval(seconds)) ?? ""
    }
}
