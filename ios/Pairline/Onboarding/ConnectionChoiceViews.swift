import SwiftUI

/// The two ways to use Pairline, the same words on every screen (D-099): **Nearby**, the phones near each other over
/// Bluetooth and Wi-Fi, and **Cloud mode**, the Android phone at home and this iPhone anywhere with internet. Shown on
/// the first-run page, in pairing, in Settings and in Help. Strings live in the "Connection" table.
enum ConnectionWords {
    static func title(_ choice: ConnectionChoice) -> Text {
        switch choice {
        case .nearby: return Text("Nearby", tableName: "Connection")
        case .cloud: return Text("Cloud mode", tableName: "RemoteMode")
        }
    }

    static func oneLiner(_ choice: ConnectionChoice) -> Text {
        switch choice {
        case .nearby:
            return Text("The phones are near each other and talk over Bluetooth and Wi-Fi. Nothing to set up.", tableName: "Connection")
        case .cloud:
            return Text("The Android phone stays home; this iPhone keeps calls, texts and codes anywhere with internet.", tableName: "Connection")
        }
    }

    static func example(_ choice: ConnectionChoice) -> Text {
        switch choice {
        case .nearby:
            return Text("For example, the Android phone is in your bag, your pocket or on your desk.", tableName: "Connection")
        case .cloud:
            return Text("For example, it stays home on its charger while you're at work or travelling.", tableName: "Connection")
        }
    }

    static func symbol(_ choice: ConnectionChoice) -> String {
        switch choice {
        case .nearby: return "antenna.radiowaves.left.and.right"
        case .cloud: return "icloud.fill"
        }
    }

    static func tint(_ choice: ConnectionChoice) -> Color {
        switch choice {
        case .nearby: return .brandGreen
        case .cloud: return .brandBlue
        }
    }

    static var bothCanBeOn: Text {
        Text("Both can be on. At home the phones talk directly; when you leave, Cloud mode takes over.", tableName: "Connection")
    }

    static var changeLater: Text {
        Text("You can change this any time in Settings.", tableName: "Connection")
    }
}

/// A card that can be chosen: symbol, title, one sentence and an example, with a check mark when chosen.
struct ChoiceCard: View {
    let symbol: String
    let tint: Color
    let title: Text
    let text: Text
    var example: Text?
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button {
            Haptics.tap()
            action()
        } label: {
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: symbol)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(tint))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    title.font(.headline).foregroundStyle(.primary)
                    text.font(.subheadline).foregroundStyle(.primary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let example {
                        example.font(.footnote).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(selected ? Color.brandBlue : Color(.tertiaryLabel))
                    .accessibilityHidden(true)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(Color(.secondarySystemGroupedBackground))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(selected ? Color.brandBlue : Color.secondary.opacity(0.18), lineWidth: selected ? 2.5 : 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}

/// Nearby and Cloud mode as two cards to choose from. Pairing asks it as a question, with its own titles ("No, it stays
/// with me" / "Yes, it stays at home").
struct ConnectionChooser: View {
    @Binding var choice: ConnectionChoice
    var nearbyTitle: Text = ConnectionWords.title(.nearby)
    var cloudTitle: Text = ConnectionWords.title(.cloud)

    var body: some View {
        VStack(spacing: 12) {
            card(.nearby, title: nearbyTitle)
            card(.cloud, title: cloudTitle)
        }
    }

    private func card(_ option: ConnectionChoice, title: Text) -> some View {
        ChoiceCard(symbol: ConnectionWords.symbol(option), tint: ConnectionWords.tint(option), title: title,
                   text: ConnectionWords.oneLiner(option), example: ConnectionWords.example(option),
                   selected: choice == option) {
            choice = option
        }
    }
}

/// "Both can be on …" with an icon, under the two cards.
struct BothCanBeOnNote: View {
    var body: some View {
        Label {
            ConnectionWords.bothCanBeOn
                .fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: "arrow.triangle.2.circlepath")
                .foregroundStyle(Color.brandBlue)
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// How the phones are connected right now, in plain words, with the next step when it isn't fine (Settings ›
/// Connection, D-099).
struct ConnectionStatus {
    enum Mood { case good, cloud, working, problem, off }

    let title: Text
    let detail: Text?
    let mood: Mood

    @MainActor
    static func of(_ bridge: BridgeController, cloudOn: Bool) -> ConnectionStatus {
        if bridge.isDemo {
            return ConnectionStatus(title: Text("Demo bridge"), detail: nil, mood: .good)
        }
        switch bridge.phase {
        case .connected:
            if bridge.isDirect {
                return ConnectionStatus(
                    title: Text("Cloud mode · straight to your home", tableName: "Connection"),
                    detail: Text("Calls, texts and codes work from anywhere.", tableName: "Connection"),
                    mood: .cloud)
            }
            return ConnectionStatus(title: nearbyTitle(bridge),
                                    detail: Text("The phones are near each other and talk directly.", tableName: "Connection"),
                                    mood: .good)
        case .remote:
            return ConnectionStatus(
                title: Text("Cloud mode · through iCloud", tableName: "Connection"),
                detail: Text("Texts and codes come through your own iCloud. Calls need the direct connection to your home: see Advanced.", tableName: "Connection"),
                mood: .cloud)
        case .searching, .securing, .waiting:
            return ConnectionStatus(
                title: Text("Looking for your Android phone…", tableName: "Connection"),
                detail: cloudOn
                    ? Text("Cloud mode keeps trying through iCloud meanwhile.", tableName: "Connection")
                    : Text("Bring the phones near each other, with Bluetooth on on both and Pairline Bridge running.", tableName: "Connection"),
                mood: .working)
        case .pairing:
            return ConnectionStatus(title: Text("Pairing…"), detail: nil, mood: .working)
        case .bluetoothOff:
            return ConnectionStatus(
                title: Text("Bluetooth is off"),
                detail: Text("Turn on Bluetooth in Control Center to reach your Android phone nearby.", tableName: "Connection"),
                mood: .problem)
        case .bluetoothUnauthorized:
            return ConnectionStatus(
                title: Text("Bluetooth isn't allowed"),
                detail: Text("Allow Bluetooth for Pairline in iPhone Settings › Pairline.", tableName: "Connection"),
                mood: .problem)
        case .bluetoothUnsupported:
            return ConnectionStatus(title: Text("Bluetooth unavailable"), detail: nil, mood: .problem)
        case .locked:
            return ConnectionStatus(
                title: Text("Paused: the free trial ended", tableName: "Connection"),
                detail: Text("Unlock Pairline at the top of Settings to connect again.", tableName: "Connection"),
                mood: .off)
        case .notPaired:
            return ConnectionStatus(
                title: Text("Not paired"),
                detail: Text("Pair your Android phone to start.", tableName: "Connection"),
                mood: .off)
        }
    }

    /// "Nearby · Bluetooth", "Nearby · Wi-Fi" or "Nearby · Bluetooth and Wi-Fi".
    @MainActor
    private static func nearbyTitle(_ bridge: BridgeController) -> Text {
        let kinds = bridge.transports.isEmpty ? [bridge.link.transportKind] : bridge.transports
        if DebugSettings.marketingScreenshots || (kinds.contains(.bluetooth) && !kinds.contains(.wifi)) {
            return Text("Nearby · Bluetooth", tableName: "Connection")
        }
        if kinds.contains(.wifi) && (kinds.contains(.bluetooth) || kinds.contains(.tcp)) {
            return Text("Nearby · Bluetooth and Wi-Fi", tableName: "Connection")
        }
        if kinds.contains(.wifi) {
            return Text("Nearby · Wi-Fi", tableName: "Connection")
        }
        if kinds.contains(.tcp) {
            return Text(verbatim: "Nearby · Debug TCP")
        }
        return Text("Nearby", tableName: "Connection")
    }

    var color: Color {
        switch mood {
        case .good: return .brandGreen
        case .cloud: return .brandBlue
        case .working: return .brandAmber
        case .problem: return .brandRed
        case .off: return .secondary
        }
    }

    var symbol: String {
        switch mood {
        case .good: return "antenna.radiowaves.left.and.right"
        case .cloud: return "icloud.fill"
        case .working: return "arrow.triangle.2.circlepath"
        case .problem: return "exclamationmark.triangle.fill"
        case .off: return "pause.circle"
        }
    }
}
