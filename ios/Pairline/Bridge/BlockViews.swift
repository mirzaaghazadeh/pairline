import SwiftUI

/// A request to block numbers, waiting for the user's confirmation (D-043).
struct BlockRequest: Identifiable {
    let numbers: [String]
    /// Set when blocking a contact (all of its numbers).
    var contactName: String?

    var id: String { numbers.joined(separator: ",") }
}

enum BlockActions {
    /// Blocks or unblocks on the bridge phone and says how it went.
    @MainActor
    static func run(app: AppModel, bridge: BridgeController, numbers: [String], block: Bool) {
        Task {
            do {
                try await bridge.setBlocked(numbers, block)
                app.toast = block
                    ? String(localized: "Blocked. Texts and calls from this number won't reach this iPhone.")
                    : String(localized: "Unblocked.")
            } catch {
                app.toast = error.localizedDescription
            }
        }
    }
}

private struct BlockPromptModifier: ViewModifier {
    @Binding var request: BlockRequest?
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController

    func body(content: Content) -> some View {
        content.confirmationDialog(title, isPresented: Binding(get: { request != nil }, set: { if !$0 { request = nil } }),
                                   titleVisibility: .visible, presenting: request) { r in
            Button("Block", role: .destructive) {
                BlockActions.run(app: app, bridge: bridge, numbers: r.numbers, block: true)
            }
        } message: { _ in
            Text("Texts and calls from it won't reach this iPhone, and Pairline Bridge declines its calls on the Android phone when it can. Android's own messaging app still receives its texts.")
        }
    }

    private var title: Text {
        if let name = request?.contactName { return Text("Block \(name)?") }
        return Text("Block this number?")
    }
}

extension View {
    /// Asks before blocking, then blocks on the bridge phone.
    func blockPrompt(_ request: Binding<BlockRequest?>) -> some View {
        modifier(BlockPromptModifier(request: request))
    }
}

/// Settings › Blocked numbers: the bridge's list, which it enforces while this iPhone is away.
struct BlockedNumbersView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @EnvironmentObject private var db: AppDatabase
    @State private var input = ""

    private var entries: [BlockedNumber] {
        bridge.blockedNumbers.sorted { ($0.time ?? 0) > ($1.time ?? 0) }
    }

    private var trimmed: String { input.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        List {
            if bridge.status != nil && !bridge.has(.block) {
                Section {
                    Label(CapabilityInfo(.block).missingText, systemImage: "exclamationmark.triangle")
                        .font(.footnote)
                }
            }
            Section {
                HStack {
                    TextField(String(localized: "Phone number or sender name"), text: $input)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .environment(\.layoutDirection, .leftToRight)
                        .onSubmit(add)
                    Button("Block", action: add)
                        .disabled(trimmed.isEmpty)
                }
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Texts and calls from these numbers don't reach this iPhone. Your bridge phone keeps the list, so it works while this iPhone is away. Android's own messaging app still receives their texts.")
                    Text("End a number with * to block every number that starts with it, for example +234*.", tableName: "Screening")
                }
            }
            // D-085: with Pairline as Android's call-screening app.
            Section {
                Toggle(isOn: Binding(get: { bridge.silenceUnknown }, set: { on in
                    Task {
                        do {
                            try await bridge.setSilenceUnknown(on)
                        } catch {
                            app.toast = error.localizedDescription
                        }
                    }
                })) {
                    Text("Silence unknown callers", tableName: "Screening")
                }
                .disabled(!bridge.has(.callScreen) || !bridge.phase.isConnected)
            } footer: {
                if bridge.status != nil && !bridge.has(.callScreen) {
                    Text(CapabilityInfo(.callScreen).missingText)
                } else {
                    Text("Calls from numbers that aren't in your contacts ring neither phone. They go to voicemail and show as missed. Blocked numbers don't ring at all.", tableName: "Screening")
                }
            }
            Section {
                if entries.isEmpty {
                    Text("No blocked numbers")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(entries, id: \.n) { entry in
                        HStack(spacing: 12) {
                            Image(systemName: "hand.raised.fill")
                                .foregroundStyle(Color.brandRed)
                            VStack(alignment: .leading, spacing: 2) {
                                LTRText(entry.n)
                                if let name = db.knownName(for: entry.n) {
                                    Text(verbatim: name)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .swipeActions {
                            Button("Unblock") {
                                BlockActions.run(app: app, bridge: bridge, numbers: [entry.n], block: false)
                            }
                            .tint(.brandBlue)
                        }
                        .contextMenu {
                            Button {
                                BlockActions.run(app: app, bridge: bridge, numbers: [entry.n], block: false)
                            } label: { Label("Unblock", systemImage: "hand.raised.slash") }
                        }
                    }
                }
            } header: {
                Text("Blocked")
            }
        }
        .navigationTitle(Text("Blocked numbers"))
        .refreshable { await bridge.refreshBlockList() }
        .task { await bridge.refreshBlockList() }
    }

    private func add() {
        let number = trimmed
        guard !number.isEmpty else { return }
        input = ""
        BlockActions.run(app: app, bridge: bridge, numbers: [number], block: true)
    }
}
