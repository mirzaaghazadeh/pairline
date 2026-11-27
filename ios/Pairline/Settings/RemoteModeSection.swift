import SwiftUI

/// Cloud mode's switch (remote mode, D-092) for Settings › Connection (D-099): the switch with its one-line meaning,
/// the add-on's buy button, and, when it can't work, the one next step. Everything technical is in
/// `CloudModeAdvancedSection`. Shown only where remote mode is available (`RemoteFeature`) and a bridge is paired.
struct CloudModeSwitch: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var purchases: PurchaseManager
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var remote: RemoteLink
    @ObservedObject var bridge: BridgeController
    @ObservedObject private var notifier = Notifier.shared
    @State private var busy = false
    @State private var error: String?
    /// The before-you-turn-it-on screen was read and accepted once ("Turn on"); after that the switch acts at once.
    @AppStorage("remote.introSeen") private var introSeen = false
    @State private var showIntro = false

    var body: some View {
        Toggle(isOn: Binding(get: { remote.isOn }, set: { change(to: $0) })) {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Cloud mode", tableName: "RemoteMode")
                    ConnectionWords.oneLiner(.cloud)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: "icloud")
            }
        }
        .disabled(busy || (!remote.isOn && blocker != nil))
        .sheet(isPresented: $showIntro) {
            RemoteModeIntroView(onTurnOn: {
                introSeen = true
                turn(on: true)
            })
            // The switch's disabled state must never reach the sheet's buttons.
            .environment(\.isEnabled, true)
        }
        RemoteAddOnButton()
        if busy {
            HStack(spacing: 10) {
                ProgressView()
                (remote.isOn ? Text("Turning off…", tableName: "Connection") : Text("Turning on…", tableName: "Connection"))
                    .foregroundStyle(.secondary)
            }
        }
        // Texts and codes reach a locked iPhone through iCloud only as notifications: a way to allow them here.
        if remote.isOn, notifier.authorization == .notDetermined {
            Button {
                Task { await notifier.requestAuthorization() }
            } label: {
                Label("Turn on notifications", systemImage: "bell.badge")
            }
        } else if remote.isOn, notifier.authorization == .denied {
            Button {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            } label: {
                Label("Open Settings", systemImage: "bell.slash")
            }
        }
        if let note {
            note
                .font(.footnote)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// The one line under the switch: an error, a problem's next step, why it can't be turned on, or what it costs.
    private var note: AnyView? {
        if let error {
            return AnyView(Text(error).foregroundStyle(Color.brandRed))
        }
        if remote.isOn, !busy, let problem = remote.problem {
            return AnyView(Text(problem.nextStep).foregroundStyle(Color.brandAmber))
        }
        if remote.isOn, notifier.authorization == .denied || notifier.authorization == .notDetermined {
            return AnyView(Text("Allow notifications for Pairline so texts and codes reach you through iCloud.")
                .foregroundStyle(.secondary))
        }
        if !remote.isOn, let blocker {
            return AnyView(blocker.foregroundStyle(.secondary))
        }
        if let trial = RemoteAddOnText.trialNote(purchases.remoteAccess, price: purchases.remotePriceText,
                                                 bundlePrice: purchases.bundleOffer?.bundlePrice) {
            return AnyView(trial.foregroundStyle(.secondary))
        }
        return nil
    }

    /// Why it can't be turned on right now: the bridge gets its settings over a nearby session, and must speak v1.
    private var blocker: Text? {
        // The purchase state first (D-093): Cloud mode is part of the trial, then an add-on to Full Access.
        if let addOn = RemoteAddOnText.blocker(purchases.remoteAccess) { return addOn }
        if !bridge.phase.isConnected {
            return Text("Turn Cloud mode on while your phones are together, near each other with Bluetooth on or on the same Wi-Fi.", tableName: "Connection")
        }
        if !bridge.bridgeSupportsRemote {
            return Text("Cloud mode needs a newer Pairline Bridge on the Android phone.", tableName: "RemoteMode")
        }
        return nil
    }

    private func change(to on: Bool) {
        guard !busy, on != remote.isOn else { return }
        // The first time, the plain-words screen comes first; its "Turn on" turns it on (D-092).
        if on, !introSeen {
            showIntro = true
            return
        }
        turn(on: on)
    }

    private func turn(on: Bool) {
        guard !busy, on != remote.isOn else { return }
        busy = true
        error = nil
        Task {
            if on {
                do {
                    try await app.link.turnOnRemote()
                    settings.connectionChoice = .cloud
                    Haptics.success()
                } catch {
                    self.error = error.localizedDescription
                    Haptics.error()
                }
            } else {
                await app.link.turnOffRemote()
                settings.connectionChoice = .nearby
            }
            busy = false
        }
    }
}

/// Cloud mode's technical side, for Settings › Connection › Advanced (D-099): what's working, the direct connection
/// for calls, port forwarding, the home connection check, the router guide and the connection log.
struct CloudModeAdvancedSection: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var purchases: PurchaseManager
    @ObservedObject var remote: RemoteLink
    @ObservedObject var bridge: BridgeController
    @State private var showLog = false

    var body: some View {
        Section {
            HStack(spacing: 12) {
                Text("Status")
                Spacer(minLength: 12)
                status
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.trailing)
            }
            if remote.isOn, let direct = directText {
                HStack(spacing: 12) {
                    Text("Direct connection")
                    Spacer(minLength: 12)
                    direct
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.trailing)
                }
            }
            // Manual port forwarding (D-095), for a router without UPnP, NAT-PMP or PCP.
            if remote.isOn {
                NavigationLink {
                    PortForwardingView(remote: remote)
                } label: {
                    HStack(spacing: 12) {
                        Label {
                            Text("Port forwarding", tableName: "RemoteMode")
                        } icon: {
                            Image(systemName: "arrow.left.arrow.right.square")
                        }
                        Spacer(minLength: 12)
                        (remote.direct.manual != nil ? Text("Set up by you", tableName: "RemoteMode")
                                                     : Text("Not set up", tableName: "RemoteMode"))
                            .foregroundStyle(.secondary)
                    }
                }
            }
            // Free, with remote mode on or off: the bridge asks its router, read-only (`remote.check`).
            HomeConnectionCheckRow(bridge: bridge, remote: remote, manualSet: remote.direct.manual != nil)
            NavigationLink {
                RouterHelpView(remote: remote)
            } label: {
                Label {
                    Text("Allow calls from far away", tableName: "RemoteMode")
                } icon: {
                    Image(systemName: "wrench.and.screwdriver")
                }
            }
            CloudModeInfoLink()
            Button {
                showLog = true
            } label: {
                Label {
                    Text("Connection log", tableName: "RemoteMode")
                } icon: {
                    Image(systemName: "doc.text.magnifyingglass")
                }
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
        } header: {
            Text("Cloud mode", tableName: "RemoteMode")
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                if remote.isOn, remote.direct.manual != nil, let attempt = remote.manualAttempt, !attempt.worked,
                   !bridge.isDirect {
                    let when = attempt.at.formatted(.relative(presentation: .named))
                    Text("Couldn't reach your Android phone at the address you entered. Last tried \(when).", tableName: "RemoteMode")
                        .foregroundStyle(Color.brandAmber)
                }
                if remote.isOn, remote.direct.manual != nil, remote.direct.status?.reason == .carrierNAT {
                    Text("Your internet provider shares one internet address among many homes, so port forwarding can't help there.", tableName: "RemoteMode")
                        .foregroundStyle(Color.brandAmber)
                }
                Text("Texts and codes travel through your own iCloud. Calls go straight between your phones, which needs your home router to let this iPhone in: the check above tells you whether it does.", tableName: "Connection")
            }
        }
    }

    private var status: Text {
        guard remote.isOn else { return Text("Off") }
        if let problem = remote.problem { return Text(problem.text) }
        if bridge.isDirect { return Text("On · direct connection") }
        return bridge.phase.isConnected ? Text("On · using the nearby link") : Text("On")
    }

    /// What the bridge said about its direct connection (`remote.state`), or that it's in use; nil before it said.
    private var directText: Text? {
        if bridge.isDirect { return Text("Connected") }
        // The router opened nothing, but the user forwarded the port by hand (D-095): say that instead of the reason.
        if remote.direct.manual != nil, remote.direct.status?.availability != .open,
           remote.direct.status?.reason != .carrierNAT, remote.direct.status?.reason != .notOnWiFi {
            switch remote.manualAttempt?.worked {
            case true?: return Text("Port forwarding: working", tableName: "RemoteMode")
            case false?: return Text("Port forwarding: not reached", tableName: "RemoteMode")
            case nil: return Text("Port forwarding: set up by you", tableName: "RemoteMode")
            }
        }
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
}

/// "How Cloud mode works" as a pushed page: from Settings (demo mode included), Advanced and Help.
struct CloudModeInfoLink: View {
    var body: some View {
        NavigationLink {
            CloudModeInfoPage()
        } label: {
            Label {
                Text("How Cloud mode works", tableName: "RemoteMode")
            } icon: {
                Image(systemName: "questionmark.circle")
            }
        }
    }
}

struct CloudModeInfoPage: View {
    var body: some View {
        ScrollView {
            RemoteModeIntroContent()
                .padding(20)
        }
        .background(Color(.systemGroupedBackground))
        .navigationBarTitleDisplayMode(.inline)
    }
}
