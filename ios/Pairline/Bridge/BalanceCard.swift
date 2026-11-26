import SwiftUI

/// The balance code's last reply on the Bridge tab, with a button to check again (D-082). Shown once the user chose a
/// saved USSD code as their balance code. The reply stays in memory only, like every USSD reply.
struct BalanceCard: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @EnvironmentObject private var settings: AppSettings
    @State private var checking = false

    var body: some View {
        if !settings.balanceUSSDCode.isEmpty, bridge.has(.ussd) {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Label {
                        Text("Balance", tableName: "Carrier").font(.headline)
                    } icon: {
                        Image(systemName: "creditcard")
                    }
                    Spacer()
                    LTRText(settings.balanceUSSDCode)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                if let balance = bridge.balance, balance.code == settings.balanceUSSDCode {
                    Text(verbatim: balance.result.ok ? (balance.result.text ?? "") : (balance.result.error ?? ""))
                        .font(.subheadline)
                        .foregroundStyle(balance.result.ok ? .primary : .secondary)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text("Checked \(balance.at, style: .relative) ago", tableName: "Carrier")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    Text("Not checked since Pairline opened.", tableName: "Carrier")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Button {
                    check()
                } label: {
                    HStack {
                        Spacer()
                        if checking {
                            ProgressView()
                        } else {
                            Text("Check balance", tableName: "Carrier").font(.subheadline.weight(.semibold))
                        }
                        Spacer()
                    }
                }
                .buttonStyle(.bordered)
                .disabled(checking || !bridge.phase.isConnected)
            }
            .card()
        }
    }

    private func check() {
        checking = true
        Task {
            defer { checking = false }
            do {
                try await bridge.checkBalance()
                Haptics.success()
            } catch {
                app.toast = error.localizedDescription
                Haptics.error()
            }
        }
    }
}
