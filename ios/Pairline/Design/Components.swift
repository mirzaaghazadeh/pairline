import SwiftUI

/// A phone number or code that keeps left-to-right order inside right-to-left text.
struct LTRText: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(verbatim: PhoneNumber.ltr(text))
    }
}

/// Initials (or a symbol for numbers and business senders) on a soft gradient derived from the name.
struct Avatar: View {
    let name: String
    var size: CGFloat = 44

    private static let palettes: [(Color, Color)] = [
        (Color(red: 0.36, green: 0.55, blue: 0.97), Color(red: 0.18, green: 0.37, blue: 0.80)),
        (Color(red: 0.20, green: 0.78, blue: 0.62), Color(red: 0.07, green: 0.58, blue: 0.45)),
        (Color(red: 0.98, green: 0.66, blue: 0.29), Color(red: 0.93, green: 0.45, blue: 0.13)),
        (Color(red: 0.93, green: 0.42, blue: 0.55), Color(red: 0.78, green: 0.22, blue: 0.40)),
        (Color(red: 0.62, green: 0.48, blue: 0.95), Color(red: 0.43, green: 0.29, blue: 0.82)),
        (Color(red: 0.36, green: 0.73, blue: 0.88), Color(red: 0.15, green: 0.52, blue: 0.72)),
    ]

    private var palette: (Color, Color) {
        var hash: UInt32 = 5381
        for scalar in name.unicodeScalars { hash = (hash &* 33) &+ scalar.value }
        return Self.palettes[Int(hash % UInt32(Self.palettes.count))]
    }

    private enum Content {
        case initials(String)
        case symbol(String)
    }

    private var content: Content {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty || PhoneNumber.isPhoneNumber(trimmed) { return .symbol("person.fill") }
        let letters = trimmed.filter { $0.isLetter }
        if letters.count >= 3, letters == letters.uppercased(), letters.unicodeScalars.allSatisfy(\.isASCII),
           !trimmed.contains(" ") {
            return .symbol("building.2.fill")
        }
        let words = trimmed.split(separator: " ").filter { $0.first?.isLetter == true }
        let initials = words.prefix(2).compactMap { $0.first }.map(String.init).joined()
        return .initials(initials.isEmpty ? String(trimmed.prefix(1)) : initials.uppercased())
    }

    var body: some View {
        ZStack {
            Circle().fill(LinearGradient(colors: [palette.0, palette.1], startPoint: .topLeading,
                                         endPoint: .bottomTrailing))
            switch content {
            case .initials(let text):
                Text(verbatim: text)
                    .font(.system(size: size * 0.38, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
                    .minimumScaleFactor(0.5)
            case .symbol(let symbol):
                Image(systemName: symbol)
                    .font(.system(size: size * 0.42, weight: .medium))
                    .foregroundStyle(.white.opacity(0.95))
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

struct SignalBars: View {
    let level: Int
    var tint: Color = .primary

    var body: some View {
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(0..<4) { index in
                RoundedRectangle(cornerRadius: 1.5)
                    .fill(index < level ? tint : Color.secondary.opacity(0.25))
                    .frame(width: 4, height: CGFloat(5 + index * 3))
            }
        }
        .accessibilityElement()
        .accessibilityLabel(Text("Signal \(level) of 4"))
    }
}

struct BatteryIndicator: View {
    let level: Int?
    let charging: Bool

    var color: Color {
        guard let level else { return .secondary }
        if charging { return .brandGreen }
        if level <= 15 { return .brandRed }
        if level <= 30 { return .brandAmber }
        return .brandGreen
    }

    var symbol: String {
        guard let level else { return "battery.0" }
        if charging { return "battery.100.bolt" }
        switch level {
        case ..<13: return "battery.0"
        case ..<38: return "battery.25"
        case ..<63: return "battery.50"
        case ..<88: return "battery.75"
        default: return "battery.100"
        }
    }

    var body: some View {
        Image(systemName: symbol)
            .symbolRenderingMode(.palette)
            .foregroundStyle(color, Color.secondary)
            .accessibilityLabel(level.map { Text("Battery \($0) percent") } ?? Text("Battery unknown"))
    }
}

/// "1" or "2" in a small SIM shape.
struct SIMBadge: View {
    let slot: Int

    var body: some View {
        Text(verbatim: "\(slot + 1)")
            .font(.system(size: 10, weight: .bold, design: .rounded))
            .foregroundStyle(.white)
            .frame(width: 15, height: 15)
            .background(RoundedRectangle(cornerRadius: 4, style: .continuous).fill(slot == 0 ? Color.brandBlue : Color.brandGreen))
            .accessibilityLabel(Text("SIM \(slot + 1)"))
    }
}

struct EmptyStateView<Actions: View>: View {
    let symbol: String
    let title: Text
    let message: Text
    @ViewBuilder var actions: Actions

    init(symbol: String, title: Text, message: Text, @ViewBuilder actions: () -> Actions = { EmptyView() }) {
        self.symbol = symbol
        self.title = title
        self.message = message
        self.actions = actions()
    }

    var body: some View {
        VStack(spacing: 14) {
            ZStack {
                Circle().fill(Color.brandBlue.opacity(0.1)).frame(width: 96, height: 96)
                Image(systemName: symbol)
                    .font(.system(size: 38, weight: .medium))
                    .foregroundStyle(Color.brandBlue)
            }
            .padding(.bottom, 4)
            title
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
            message
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            actions
                .padding(.top, 6)
        }
        .padding(32)
        .frame(maxWidth: 420)
        .frame(maxWidth: .infinity)
    }
}

/// A colored banner row (trial days left, demo mode, offline).
struct InfoBanner<Trailing: View>: View {
    let symbol: String
    let tint: Color
    let text: Text
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(tint)
            text
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.primary)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            trailing
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(tint.opacity(0.13)))
    }
}

/// Link state as a dot and a short label.
struct LinkStatusPill: View {
    let phase: LinkPhase
    var isDemo = false
    @State private var pulse = false

    var color: Color {
        switch phase {
        case .connected: return .brandGreen
        case .searching, .securing, .pairing, .waiting: return .brandAmber
        case .locked: return .brandAmber
        case .bluetoothOff, .bluetoothUnauthorized, .bluetoothUnsupported: return .brandRed
        case .notPaired: return .secondary
        }
    }

    var label: Text {
        if isDemo, phase == .connected { return Text("Demo bridge") }
        return LinkStatusPill.title(for: phase)
    }

    static func title(for phase: LinkPhase) -> Text {
        switch phase {
        case .connected: return Text("Connected")
        case .searching: return Text("Looking for your bridge…")
        case .securing: return Text("Securing the link…")
        case .pairing: return Text("Pairing…")
        case .waiting: return Text("Reconnecting soon…")
        case .locked: return Text("Trial ended")
        case .bluetoothOff: return Text("Bluetooth is off")
        case .bluetoothUnauthorized: return Text("Bluetooth not allowed")
        case .bluetoothUnsupported: return Text("Bluetooth unavailable")
        case .notPaired: return Text("Not paired")
        }
    }

    private var isTransient: Bool {
        switch phase {
        case .searching, .securing, .pairing, .waiting: return true
        default: return false
        }
    }

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(color)
                .frame(width: 8, height: 8)
                .scaleEffect(pulse && isTransient ? 1.35 : 1)
                .opacity(pulse && isTransient ? 0.6 : 1)
            label
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Capsule().fill(color.opacity(0.12)))
        .onAppear {
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { pulse = true }
        }
        .accessibilityElement(children: .combine)
    }
}

/// Section title used on dashboards.
struct SectionTitle: View {
    let text: Text

    init(_ text: Text) {
        self.text = text
    }

    var body: some View {
        text
            .font(.footnote.weight(.semibold))
            .foregroundStyle(.secondary)
            .textCase(.uppercase)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 4)
    }
}

/// Copies text and shows a short confirmation.
struct CopyChip: View {
    let label: Text
    let value: String
    var isCode = false
    @State private var copied = false

    var body: some View {
        Button {
            Clipboard.copy(value, isCode: isCode)
            Haptics.success()
            withAnimation(.spring(response: 0.3)) { copied = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) {
                withAnimation { copied = false }
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: copied ? "checkmark" : "doc.on.doc")
                    .font(.caption.weight(.bold))
                if copied {
                    Text("Copied")
                } else {
                    label
                }
            }
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Capsule().fill(Color.brandBlue.opacity(0.14)))
            .foregroundStyle(Color.brandBlue)
        }
        .buttonStyle(.plain)
    }
}
