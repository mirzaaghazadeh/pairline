import SwiftUI

/// Remote mode (D-072) in Settings: what it is in plain words, whether it's working, and the switch (off by default).
/// Shown only where remote mode is available (`RemoteFeature`) and a bridge is paired.
struct RemoteModeSection: View {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var remote: RemoteLink
    @ObservedObject var bridge: BridgeController
    @ObservedObject private var notifier = Notifier.shared
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        Section {
            Toggle(isOn: Binding(get: { remote.isOn }, set: { change(to: $0) })) {
                Label("Remote mode", systemImage: "icloud")
            }
            .disabled(busy || (!remote.isOn && blocker != nil))
            HStack(spacing: 12) {
                Text("Status")
                Spacer(minLength: 12)
                if busy {
                    ProgressView()
                } else {
                    status
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.trailing)
                }
            }
        } header: {
            Text("Remote mode")
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                if let error {
                    Text(error).foregroundStyle(Color.brandRed)
                }
                if !remote.isOn, let blocker {
                    blocker
                }
                if remote.isOn, notifier.authorization == .denied || notifier.authorization == .notDetermined {
                    Text("Allow notifications for Pairline so texts and codes reach you through iCloud.")
                }
                Text("Keeps texts and codes coming when your phones are apart. There's no Pairline server: messages are locked with keys only your two phones hold, and Apple stores only locked records in your iCloud until they're read. Apple and internet providers can see that your phones talk, not what they say. Calls still need the phones together for now.")
            }
        }
    }

    private var status: Text {
        guard remote.isOn else { return Text("Off") }
        if let problem = remote.problem { return Text(problem.text) }
        return bridge.phase.isConnected ? Text("On · using the nearby link") : Text("Via iCloud")
    }

    /// Why it can't be turned on right now: the bridge gets its settings over a nearby session, and must speak v1.
    private var blocker: Text? {
        if !bridge.phase.isConnected { return Text("Turn remote mode on while your phones are linked nearby.") }
        if !bridge.bridgeSupportsRemote { return Text("Remote mode needs a newer Pairline Bridge on the Android phone.") }
        return nil
    }

    private func change(to on: Bool) {
        guard !busy, on != remote.isOn else { return }
        busy = true
        error = nil
        Task {
            if on {
                do {
                    try await app.link.turnOnRemote()
                    Haptics.success()
                } catch {
                    self.error = error.localizedDescription
                    Haptics.error()
                }
            } else {
                await app.link.turnOffRemote()
            }
            busy = false
        }
    }
}
