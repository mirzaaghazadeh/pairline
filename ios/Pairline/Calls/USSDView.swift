import SwiftUI

/// Run USSD codes (balance, packages, carrier services) on the bridge phone's SIM.
struct USSDView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @EnvironmentObject private var settings: AppSettings
    @State private var code = ""
    @State private var sim: Int?
    @State private var running = false
    @State private var result: USSDResultSheet.Item?
    @FocusState private var focused: Bool

    private let common: [(code: String, title: LocalizedStringKey, symbol: String)] = [
        ("*#06#", "Show IMEI", "barcode"),
        ("*#21#", "Call forwarding status", "arrowshape.turn.up.right"),
        ("*#43#", "Call waiting status", "phone.badge.waveform"),
        ("*#31#", "Caller ID status", "person.crop.circle.badge.questionmark"),
        ("*#62#", "Forwarding when unreachable", "antenna.radiowaves.left.and.right.slash"),
        ("*#67#", "Forwarding when busy", "phone.down.waves.left.and.right"),
    ]

    var body: some View {
        Form {
            Section {
                NavigationLink {
                    CarrierSettingsView()
                } label: {
                    Label {
                        Text("Call forwarding, waiting and caller ID", tableName: "Carrier")
                    } icon: {
                        Image(systemName: "arrowshape.turn.up.right.circle")
                    }
                }
            }
            Section {
                HStack {
                    TextField(String(localized: "Code, for example *140#"), text: $code)
                        .keyboardType(.phonePad)
                        .font(.title3.monospacedDigit())
                        .environment(\.layoutDirection, .leftToRight)
                        .focused($focused)
                    if !code.isEmpty {
                        Button {
                            code = ""
                        } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                        }
                        .buttonStyle(.borderless)
                    }
                }
                if bridge.isDualSIM {
                    Picker(selection: $sim) {
                        ForEach(bridge.sims) { info in
                            Text(bridge.simLabel(info.slot)).tag(Optional(info.slot))
                        }
                    } label: {
                        Label("SIM", systemImage: "simcard")
                    }
                }
                Button {
                    run(code)
                } label: {
                    HStack {
                        Spacer()
                        if running {
                            ProgressView()
                        } else {
                            Text("Run code").font(.headline)
                        }
                        Spacer()
                    }
                }
                .disabled(!PhoneNumber.isUSSD(code) || running)
            } footer: {
                if bridge.status != nil && !bridge.has(.ussd) {
                    Text(CapabilityInfo(.ussd).missingText)
                } else {
                    Text("Codes run on your Android phone's SIM. Carrier replies appear here and aren't stored.")
                }
            }

            if !settings.savedUSSDCodes.isEmpty {
                Section {
                    ForEach(settings.savedUSSDCodes, id: \.self) { saved in
                        Button {
                            run(saved)
                        } label: {
                            HStack {
                                Image(systemName: "star.fill").foregroundStyle(Color.brandAmber)
                                LTRText(saved).font(.body.monospacedDigit()).foregroundStyle(.primary)
                                if settings.balanceUSSDCode == saved {
                                    Tag(text: Text("Balance", tableName: "Carrier"))
                                }
                                Spacer()
                                Image(systemName: "play.circle").foregroundStyle(Color.brandBlue)
                            }
                        }
                    }
                    .onDelete { offsets in
                        var list = settings.savedUSSDCodes
                        list.remove(atOffsets: offsets)
                        settings.savedUSSDCodes = list
                    }
                } header: {
                    Text("Saved codes")
                }
            }

            Section {
                ForEach(common, id: \.code) { item in
                    Button {
                        code = item.code
                        run(item.code)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: item.symbol)
                                .foregroundStyle(Color.brandBlue)
                                .frame(width: 26)
                            Text(item.title).foregroundStyle(.primary)
                            Spacer()
                            LTRText(item.code)
                                .font(.callout.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            } header: {
                Text("Common codes")
            } footer: {
                Text("Balance and package codes differ by carrier. Save the ones your carrier uses with the star in the result.")
            }
        }
        .navigationTitle(Text("USSD Codes"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $result) { item in
            USSDResultSheet(item: item)
        }
        .onAppear {
            if sim == nil { sim = bridge.sims.first(where: { $0.defaultVoice == true })?.slot ?? bridge.sims.first?.slot }
        }
    }

    private func run(_ code: String) {
        guard PhoneNumber.isUSSD(code), !running else { return }
        focused = false
        running = true
        Task {
            defer { running = false }
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

struct USSDResultSheet: View {
    struct Item: Identifiable {
        let id = UUID()
        let code: String
        let result: USSDResult
        /// The SIM it ran on, kept with the code when it becomes the balance code (D-082).
        var sim: Int?
    }

    let item: Item
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var settings: AppSettings

    private var isSaved: Bool { settings.savedUSSDCodes.contains(item.code) }
    private var isBalance: Bool { settings.balanceUSSDCode == item.code }

    /// Saved codes can become the balance code; the Bridge tab shows its reply (D-082).
    private func toggleBalance() {
        if isBalance {
            settings.balanceUSSDCode = ""
        } else {
            if !isSaved { settings.savedUSSDCodes = settings.savedUSSDCodes + [item.code] }
            settings.balanceUSSDCode = item.code
            settings.balanceUSSDSim = item.sim ?? -1
        }
        Haptics.selection()
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack {
                        Image(systemName: item.result.ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                            .foregroundStyle(item.result.ok ? Color.brandGreen : Color.brandAmber)
                        LTRText(item.code).font(.headline.monospacedDigit())
                    }
                    Text(verbatim: item.result.ok ? (item.result.text ?? "") : (item.result.error ?? ""))
                        .font(.body)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .card()
                    if item.result.ok, let text = item.result.text, !text.isEmpty {
                        CopyChip(label: Text("Copy reply"), value: text)
                    }
                    if item.result.ok {
                        Toggle(isOn: Binding(get: { isBalance }, set: { _ in toggleBalance() })) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Use as balance code", tableName: "Carrier")
                                Text("Shows its reply on the Bridge tab, with a button to check again.", tableName: "Carrier")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .card()
                    }
                }
                .padding(20)
            }
            .navigationTitle(item.result.ok ? Text("Carrier reply") : Text("Code didn't run"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        var list = settings.savedUSSDCodes
                        if isSaved { list.removeAll { $0 == item.code } } else { list.append(item.code) }
                        settings.savedUSSDCodes = list
                        Haptics.selection()
                    } label: {
                        Label {
                            isSaved ? Text("Remove from saved codes") : Text("Save code")
                        } icon: {
                            Image(systemName: isSaved ? "star.fill" : "star")
                        }
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
