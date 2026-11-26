import SwiftUI

/// Call forwarding, call waiting and caller ID on the bridge phone's SIM (D-082), through the standard GSM codes
/// (3GPP TS 22.030) that the bridge runs like any USSD code. The carrier's reply shows in the usual result sheet.
struct CarrierSettingsView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @State private var sim: Int?
    @State private var running: String?
    @State private var result: USSDResultSheet.Item?
    @State private var forwarding: ForwardKind?

    /// The four forwarding conditions and their service codes.
    enum ForwardKind: String, Identifiable, CaseIterable {
        case always = "21"
        case busy = "67"
        case noAnswer = "61"
        case unreachable = "62"

        var id: String { rawValue }

        var title: Text {
            switch self {
            case .always: return Text("Always", tableName: "Carrier")
            case .busy: return Text("When busy", tableName: "Carrier")
            case .noAnswer: return Text("When not answered", tableName: "Carrier")
            case .unreachable: return Text("When unreachable", tableName: "Carrier")
            }
        }

        var symbol: String {
            switch self {
            case .always: return "arrowshape.turn.up.right"
            case .busy: return "phone.down.waves.left.and.right"
            case .noAnswer: return "phone.badge.waveform"
            case .unreachable: return "antenna.radiowaves.left.and.right.slash"
            }
        }
    }

    var body: some View {
        Form {
            if bridge.isDualSIM {
                Section {
                    Picker(selection: $sim) {
                        ForEach(bridge.sims) { info in
                            Text(bridge.simLabel(info.slot)).tag(Optional(info.slot))
                        }
                    } label: {
                        Label("SIM", systemImage: "simcard")
                    }
                }
            }

            Section {
                ForEach(ForwardKind.allCases) { kind in
                    Button {
                        forwarding = kind
                    } label: {
                        row(kind.title, symbol: kind.symbol, busy: running?.contains(kind.rawValue) == true)
                    }
                }
                Button(role: .destructive) {
                    run("##002#")
                } label: {
                    row(Text("Turn off all forwarding", tableName: "Carrier"), symbol: "xmark.circle",
                        busy: running == "##002#")
                }
            } header: {
                Text("Call forwarding", tableName: "Carrier")
            } footer: {
                Text("Send calls to another number. Your carrier may charge for forwarded calls.", tableName: "Carrier")
            }

            Section {
                actionRow(Text("Check", tableName: "Carrier"), code: "*#43#", symbol: "questionmark.circle")
                actionRow(Text("Turn on", tableName: "Carrier"), code: "*43#", symbol: "checkmark.circle")
                actionRow(Text("Turn off", tableName: "Carrier"), code: "#43#", symbol: "xmark.circle")
            } header: {
                Text("Call waiting", tableName: "Carrier")
            } footer: {
                Text("A second call can ring while you're on a call.", tableName: "Carrier")
            }

            Section {
                actionRow(Text("Check", tableName: "Carrier"), code: "*#31#", symbol: "questionmark.circle")
                actionRow(Text("Hide my number on every call", tableName: "Carrier"), code: "*31#", symbol: "eye.slash")
                actionRow(Text("Show my number on every call", tableName: "Carrier"), code: "#31#", symbol: "eye")
            } header: {
                Text("Caller ID", tableName: "Carrier")
            } footer: {
                Text("To hide your number on one call only, touch and hold the call button on the keypad.", tableName: "Carrier")
            }

            Section {
            } footer: {
                Text("These are the standard codes for GSM carriers; not every carrier supports each of them. The carrier's reply shows here and isn't stored.", tableName: "Carrier")
            }
        }
        .navigationTitle(Text("Call settings", tableName: "Carrier"))
        .navigationBarTitleDisplayMode(.inline)
        .disabled(running != nil)
        .sheet(item: $result) { item in
            USSDResultSheet(item: item)
        }
        .sheet(item: $forwarding) { kind in
            ForwardingSheet(kind: kind) { code in
                forwarding = nil
                run(code)
            }
        }
        .onAppear {
            if sim == nil { sim = bridge.effectiveSim(forSMS: false) }
        }
    }

    private func row(_ title: Text, symbol: String, busy: Bool) -> some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .foregroundStyle(Color.brandBlue)
                .frame(width: 26)
            title.foregroundStyle(.primary)
            Spacer()
            if busy { ProgressView() }
        }
    }

    private func actionRow(_ title: Text, code: String, symbol: String) -> some View {
        Button {
            run(code)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: symbol)
                    .foregroundStyle(Color.brandBlue)
                    .frame(width: 26)
                title.foregroundStyle(.primary)
                Spacer()
                if running == code {
                    ProgressView()
                } else {
                    LTRText(code)
                        .font(.callout.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func run(_ code: String) {
        guard running == nil else { return }
        running = code
        Task {
            defer { running = nil }
            do {
                let output = try await bridge.runUSSD(code: code, sim: sim)
                result = .init(code: code, result: output, sim: sim)
                Haptics.success()
            } catch {
                app.toast = error.localizedDescription
                Haptics.error()
            }
        }
    }
}

/// One forwarding condition: forward to a number, turn it off, or ask the carrier how it's set.
private struct ForwardingSheet: View {
    let kind: CarrierSettingsView.ForwardKind
    let run: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var number = ""
    @FocusState private var focused: Bool

    /// What goes between the service code's stars: digits with an optional leading "+".
    private var target: String { PhoneNumber.international(PhoneNumber.dialable(number)).filter { $0 != "*" && $0 != "#" } }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(String(localized: "Number to forward to", table: "Carrier"), text: $number)
                        .keyboardType(.phonePad)
                        .textContentType(.telephoneNumber)
                        .font(.title3.monospacedDigit())
                        .environment(\.layoutDirection, .leftToRight)
                        .focused($focused)
                    Button {
                        run("**\(kind.rawValue)*\(target)#")
                    } label: {
                        Text("Forward to this number", tableName: "Carrier")
                    }
                    .disabled(PhoneNumber.digits(target).count < 3)
                } header: {
                    kind.title
                }
                Section {
                    Button {
                        run("*#\(kind.rawValue)#")
                    } label: {
                        Text("Check", tableName: "Carrier")
                    }
                    Button(role: .destructive) {
                        run("#\(kind.rawValue)#")
                    } label: {
                        Text("Turn off", tableName: "Carrier")
                    }
                }
            }
            .navigationTitle(Text("Call forwarding", tableName: "Carrier"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onAppear { focused = true }
        }
        .presentationDetents([.medium, .large])
    }
}
