import SwiftUI

/// Remote mode (D-080) in Settings: what it is in plain words, whether it's working, and the switch (off by default).
/// Shown only where remote mode is available (`RemoteFeature`) and a bridge is paired.
struct RemoteModeSection: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var purchases: PurchaseManager
    @ObservedObject var remote: RemoteLink
    @ObservedObject var bridge: BridgeController
    @ObservedObject private var notifier = Notifier.shared
    @State private var busy = false
    @State private var error: String?
    /// The before-you-turn-it-on screen was read and accepted once ("Turn on"); after that the switch acts at once.
    @AppStorage("remote.introSeen") private var introSeen = false
    @State private var intro: IntroSheet?
    @State private var showLog = false

    private enum IntroSheet: Identifiable {
        case beforeTurningOn
        case info
        var id: Self { self }
    }

    var body: some View {
        Section {
            Toggle(isOn: Binding(get: { remote.isOn }, set: { change(to: $0) })) {
                Label("Remote mode", systemImage: "icloud")
            }
            .disabled(busy || (!remote.isOn && blocker != nil))
            RemoteAddOnButton()
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
            if remote.isOn, !busy, let direct = directText {
                HStack(spacing: 12) {
                    Text("Direct connection")
                    Spacer(minLength: 12)
                    direct
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.trailing)
                }
            }
            // Free, with remote mode on or off: the bridge asks its router, read-only (`remote.check`).
            HomeConnectionCheckRow(bridge: bridge)
            Button {
                intro = .info
            } label: {
                Label {
                    Text("How remote mode works", tableName: "RemoteMode")
                } icon: {
                    Image(systemName: "questionmark.circle")
                }
            }
            // On a row that's never disabled, so the sheets' buttons never inherit a disabled state.
            .sheet(item: $intro) { sheet in
                RemoteModeIntroView(onTurnOn: introAction(for: sheet))
            }
            .sheet(isPresented: $showLog) {
                NavigationStack {
                    DiagnosticsView()
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button { showLog = false } label: { Text("Done", tableName: "RemoteMode") }
                            }
                        }
                }
                .environmentObject(app)
                .environmentObject(purchases)
            }
            Button {
                showLog = true
            } label: {
                Label {
                    Text("Connection log", tableName: "RemoteMode")
                } icon: {
                    Image(systemName: "doc.text.magnifyingglass")
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
                if let note = RemoteAddOnText.trialNote(purchases.remoteAccess, price: purchases.remotePriceText) {
                    note
                }
                if remote.isOn, notifier.authorization == .denied || notifier.authorization == .notDetermined {
                    Text("Allow notifications for Pairline so texts and codes reach you through iCloud.")
                }
                Text("Keeps texts and codes coming through your own iCloud when your phones are apart, and calls too when your home router allows a direct connection. There's no Pairline server: everything is locked with keys only your two phones hold.", tableName: "RemoteMode")
            }
        }
    }

    private var status: Text {
        guard remote.isOn else { return Text("Off") }
        if let problem = remote.problem { return Text(problem.text) }
        if bridge.isDirect { return Text("On · direct connection") }
        return bridge.phase.isConnected ? Text("On · using the nearby link") : Text("Via iCloud")
    }

    /// What the bridge said about its direct connection (`remote.state`), or that it's in use; nil before it said.
    private var directText: Text? {
        if bridge.isDirect { return Text("Connected") }
        guard let status = remote.direct.status else { return nil }
        switch status.availability {
        case .open:
            return Text("Open")
        case .unavailable:
            return status.reason.map { Text("Not possible: \($0.text)") } ?? Text("Not possible")
        case .blocked:
            return status.reason.map { Text("Blocked: \($0.text)") } ?? Text("Blocked by the network")
        }
    }

    /// Why it can't be turned on right now: the bridge gets its settings over a nearby session, and must speak v1.
    private var blocker: Text? {
        // The purchase state first (D-081): remote mode is part of the trial, then an add-on to Full Access.
        if let addOn = RemoteAddOnText.blocker(purchases.remoteAccess) { return addOn }
        if !bridge.phase.isConnected { return Text("Turn remote mode on while your phones are linked nearby.") }
        if !bridge.bridgeSupportsRemote { return Text("Remote mode needs a newer Pairline Bridge on the Android phone.") }
        return nil
    }

    private func change(to on: Bool) {
        guard !busy, on != remote.isOn else { return }
        // The first time, the plain-words screen comes first; its "Turn on" turns it on (D-080).
        if on, !introSeen {
            intro = .beforeTurningOn
            return
        }
        turn(on: on)
    }

    /// "Turn on" in the before-you-turn-it-on screen; the information-only screen has just "Done".
    private func introAction(for sheet: IntroSheet) -> (() -> Void)? {
        guard sheet == .beforeTurningOn else { return nil }
        return {
            introSeen = true
            turn(on: true)
        }
    }

    private func turn(on: Bool) {
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
