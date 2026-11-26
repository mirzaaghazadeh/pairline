import SwiftUI

/// Settings › Messages and alerts (D-083): old codes, codes in the Dynamic Island, quick replies and the
/// "left behind" alert. Everything here works on this iPhone only.
struct InboxSettingsSection: View {
    @EnvironmentObject private var settings: AppSettings
    @State private var editingReplies = false

    var body: some View {
        Section {
            Toggle(isOn: $settings.deleteOldCodes) {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Delete codes after a day", tableName: "Inbox")
                        Text("One-time codes go from this iPhone 24 hours after they arrive. They stay on the Android phone.", tableName: "Inbox")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "key")
                }
            }
            .onValueChange(settings.deleteOldCodes) { on in
                guard on else { return }
                BridgeController.resetCodeScan()
                AppModel.shared.real.deleteOldCodes()
            }
            if BridgeLiveActivity.isSupported {
                Toggle(isOn: $settings.codesInLiveActivity) {
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Codes in the Dynamic Island", tableName: "Inbox")
                            Text("A new code shows there for two minutes, with the bridge status on. Tap it to copy. Never on the Lock Screen.", tableName: "Inbox")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "capsule.fill")
                    }
                }
                .onValueChange(settings.codesInLiveActivity) { on in
                    if !on { BridgeLiveActivity.shared.clearCode() }
                }
            }
            Button {
                editingReplies = true
            } label: {
                Label {
                    Text("Quick replies", tableName: "Inbox").foregroundStyle(.primary)
                } icon: {
                    Image(systemName: "text.bubble")
                }
            }
            Toggle(isOn: $settings.leftBehindAlert) {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Bridge phone left behind", tableName: "Inbox")
                        Text("A notification when the Android phone has been out of reach for 5 minutes.", tableName: "Inbox")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "iphone.slash")
                }
            }
            .onValueChange(settings.leftBehindAlert) { on in
                if !on { Notifier.shared.cancelLeftBehind() }
            }
        } header: {
            Text("Messages and alerts", tableName: "Inbox")
        }
        .sheet(isPresented: $editingReplies) {
            QuickRepliesEditor()
        }
    }
}
