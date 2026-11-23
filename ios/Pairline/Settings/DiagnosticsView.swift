import SwiftUI

/// A readable report (redacted log plus link state). It's shown in full here, and only shared when the user taps
/// Share (AGENTS.md rule 9).
struct DiagnosticsView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var purchases: PurchaseManager
    @State private var report = ""
    @State private var confirmClear = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Label {
                    Text("This is the exact text that is shared if you tap Share. Phone numbers, emails and device addresses are removed, and message text is never logged.")
                } icon: {
                    Image(systemName: "eye")
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
                Text(verbatim: report)
                    .font(.system(.caption, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .environment(\.layoutDirection, .leftToRight)
                    .card(padding: 12)
            }
            .padding(16)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(Text("Diagnostics"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                Menu {
                    Button(role: .destructive) {
                        confirmClear = true
                    } label: {
                        Label("Clear log", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                ShareLink(item: report) {
                    Image(systemName: "square.and.arrow.up")
                }
                .accessibilityLabel(Text("Share report"))
            }
        }
        .confirmationDialog(Text("Clear the log?"), isPresented: $confirmClear) {
            Button("Clear log", role: .destructive) {
                log.clear()
                build()
            }
        }
        .onAppear(perform: build)
    }

    private func build() {
        let bridge = app.real
        var lines: [String] = []
        lines.append("Pairline \(AppInfo.version) (\(AppInfo.build)), protocol v\(WireProtocol.version)")
        lines.append("iOS \(UIDevice.current.systemVersion), \(UIDevice.current.model)")
        lines.append("Language: \(Bundle.main.preferredLocalizations.first ?? "?")")
        lines.append("Access: \(describe(purchases.access))")
        lines.append("Paired: \(app.isPaired ? "yes" : "no"), transport: \(app.link.transportKind.rawValue)")
        lines.append("Link: \(describe(bridge.phase))")
        if let error = app.link.lastSessionError { lines.append("Last link error: \(error)") }
        if let info = bridge.deviceInfo {
            lines.append("Bridge: \(info.platform ?? "?") \(info.os ?? "?"), app \(info.app ?? "?"), protocol \(info.proto.map(String.init) ?? "?")")
        }
        if let status = bridge.status {
            lines.append("Bridge battery: \(status.battery?.level.map { "\($0)%" } ?? "?")\(status.battery?.charging == true ? " charging" : "")")
            lines.append("SIMs: \(status.sims?.count ?? 0); caps: \((status.caps ?? []).sorted().joined(separator: " "))")
        }
        lines.append("Stored: \(bridge.db.messageCount) messages, \(bridge.db.contactCount) contacts")
        lines.append("")
        lines.append("— Log (last 7 days) —")
        let text = log.text()
        lines.append(text.isEmpty ? "(empty)" : text)
        report = Redactor.redact(lines.joined(separator: "\n"))
    }

    private func describe(_ access: AccessState) -> String {
        switch access {
        case .loading: return "loading"
        case .notStarted: return "trial not started"
        case .trial: return "trial"
        case .expired: return "trial ended"
        case .purchased: return "purchased"
        }
    }

    private func describe(_ phase: LinkPhase) -> String {
        switch phase {
        case .connected: return "connected"
        case .notPaired: return "not paired"
        case .locked: return "locked"
        case .bluetoothOff: return "bluetooth off"
        case .bluetoothUnauthorized: return "bluetooth not allowed"
        case .bluetoothUnsupported: return "bluetooth unsupported"
        case .searching: return "searching"
        case .securing: return "handshake"
        case .waiting: return "waiting to retry"
        case .pairing: return "pairing"
        }
    }
}
