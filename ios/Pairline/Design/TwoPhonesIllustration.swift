import SwiftUI

/// The Pairline motif: the Android bridge phone (with the SIM) and the iPhone, joined by a line with data
/// flowing along it.
struct TwoPhonesIllustration: View {
    enum State {
        case connected
        case connecting
        case idle
    }

    var state: State = .connected
    var height: CGFloat = 150
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.layoutDirection) private var layoutDirection

    private var phoneWidth: CGFloat { height * 0.52 }

    private var lineColor: Color {
        switch state {
        case .connected: return .brandGreen
        case .connecting: return .brandAmber
        case .idle: return .secondary.opacity(0.5)
        }
    }

    var body: some View {
        HStack(spacing: 0) {
            phone(symbol: "simcard.fill", caption: Text("Android"), tint: .brandGreen)
            link
            phone(symbol: "bubble.left.and.bubble.right.fill", caption: Text("iPhone"), tint: .brandBlue)
        }
        // The bridge phone stays on the leading side in every language.
        .environment(\.layoutDirection, .leftToRight)
        .frame(height: height + 22)
        .accessibilityElement()
        .accessibilityLabel(Text("Your Android phone and iPhone, linked"))
    }

    private func phone(symbol: String, caption: Text, tint: Color) -> some View {
        VStack(spacing: 6) {
            ZStack {
                RoundedRectangle(cornerRadius: phoneWidth * 0.22, style: .continuous)
                    .fill(Color.cardBackground)
                    .shadow(color: .black.opacity(0.12), radius: 10, y: 6)
                RoundedRectangle(cornerRadius: phoneWidth * 0.22, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.12), lineWidth: 2)
                RoundedRectangle(cornerRadius: phoneWidth * 0.16, style: .continuous)
                    .fill(LinearGradient(colors: [tint.opacity(0.18), tint.opacity(0.06)], startPoint: .top,
                                         endPoint: .bottom))
                    .padding(phoneWidth * 0.08)
                Capsule()
                    .fill(Color.primary.opacity(0.15))
                    .frame(width: phoneWidth * 0.26, height: 4)
                    .frame(maxHeight: .infinity, alignment: .top)
                    .padding(.top, phoneWidth * 0.12)
                Image(systemName: symbol)
                    .font(.system(size: phoneWidth * 0.32, weight: .semibold))
                    .foregroundStyle(tint)
            }
            .frame(width: phoneWidth, height: height)
            // As wide as the phone drawing: at the largest text sizes the caption shrinks to fit rather than end in
            // "…" (D-096's audit).
            caption
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
        }
    }

    private var link: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let midY = height / 2
            ZStack {
                Path { path in
                    path.move(to: CGPoint(x: 0, y: midY))
                    path.addLine(to: CGPoint(x: width, y: midY))
                }
                .stroke(lineColor.opacity(state == .idle ? 1 : 0.35),
                        style: StrokeStyle(lineWidth: 3, lineCap: .round, dash: state == .idle ? [4, 6] : []))
                if state != .idle {
                    if reduceMotion {
                        dots(offset: 0.2)
                    } else {
                        TimelineView(.animation) { context in
                            let t = context.date.timeIntervalSinceReferenceDate
                            dots(offset: CGFloat(t.truncatingRemainder(dividingBy: 1.6) / 1.6))
                        }
                    }
                }
                Circle()
                    .fill(lineColor)
                    .frame(width: 16, height: 16)
                    .overlay(Circle().strokeBorder(Color.cardBackground, lineWidth: 3))
                    .position(x: width / 2, y: midY)
            }
        }
        .frame(height: height + 22)
        .padding(.horizontal, -2)
    }

    /// Three dots travelling from the Android phone to the iPhone, drawn in the link's own coordinate space.
    private func dots(offset: CGFloat) -> some View {
        let color = lineColor
        let midY = height / 2
        return Canvas { context, size in
            for index in 0..<3 {
                let progress = (offset + CGFloat(index) / 3).truncatingRemainder(dividingBy: 1)
                let rect = CGRect(x: progress * size.width - 3, y: midY - 3, width: 6, height: 6)
                context.opacity = Double(sin(progress * .pi))
                context.fill(Path(ellipseIn: rect), with: .color(color))
            }
        }
    }
}
