import SwiftUI
import UserNotifications

struct SettingsTab: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var purchases: PurchaseManager
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var bridge: BridgeController
    @ObservedObject private var notifier = Notifier.shared
    @State private var confirmUnpair = false

    var body: some View {
        NavigationStack {
            List {
                purchaseSection
                bridgeSection
                notificationSection
                callsSection
                languageSection
                helpSection
                telegramSection
                aboutSection
                #if DEBUG
                DebugSection()
                #endif
            }
            .navigationTitle(Text("Settings"))
            .confirmationDialog(Text("Unpair this bridge phone?"), isPresented: $confirmUnpair, titleVisibility: .visible) {
                Button("Unpair", role: .destructive) {
                    app.unpair()
                    Haptics.success()
                }
            } message: {
                if app.real.phase.isConnected {
                    Text("This iPhone forgets the bridge phone and removes the messages, contacts and call history synced from it. Nothing is deleted on the Android phone.")
                } else {
                    Text("This iPhone forgets the bridge phone and removes what was synced from it. The bridge phone isn't connected, so also unpair in Pairline Bridge on the Android phone.")
                }
            }
        }
    }

    // MARK: - Sections

    private var purchaseSection: some View {
        Section {
            HStack(spacing: 14) {
                Image("BrandMark")
                    .resizable()
                    .frame(width: 48, height: 48)
                    .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                    Text(verbatim: "Pairline").font(.headline)
                    accessText.font(.subheadline).foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 4)
            if !purchases.access.isPurchased {
                Button {
                    app.showPaywall = true
                } label: {
                    Label {
                        Text("Unlock Pairline · \(purchases.priceText)")
                    } icon: {
                        Image(systemName: "lock.open.fill")
                    }
                }
            }
            Button {
                Task { await purchases.restore() }
            } label: {
                Label("Restore Purchases", systemImage: "arrow.clockwise")
            }
            .disabled(purchases.busy)
        } footer: {
            if let message = purchases.message {
                Text(message)
            }
        }
    }

    private var accessText: Text {
        switch purchases.access {
        case .loading: return Text("Checking your purchase…")
        case .notStarted: return Text("Free trial not started")
        case .trial(let end): return TrialText.remaining(TrialPolicy.remaining(until: end, now: purchases.trustedNow))
        case .expired: return Text("Free trial ended")
        case .purchased: return Text("Full access · Thank you!")
        }
    }

    private var bridgeSection: some View {
        Section {
            if let peer = app.pairedPeer {
                HStack {
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: app.real.deviceInfo?.name ?? String(localized: "Bridge phone"))
                            Text("Paired \(peer.pairedAt.formatted(date: .abbreviated, time: .omitted))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "iphone.gen2.radiowaves.left.and.right")
                    }
                    Spacer()
                    LinkStatusPill(phase: app.real.phase)
                }
                Button {
                    app.showPairing = true
                } label: {
                    Label("Pair a different bridge phone", systemImage: "qrcode.viewfinder")
                }
                Button(role: .destructive) {
                    confirmUnpair = true
                } label: {
                    Label("Unpair", systemImage: "minus.circle")
                        .foregroundStyle(Color.brandRed)
                }
            } else {
                Button {
                    app.showPairing = true
                } label: {
                    Label("Pair a bridge phone", systemImage: "qrcode.viewfinder")
                }
            }
            if bridge.isDemo {
                Button {
                    app.exitDemo()
                } label: {
                    Label("Exit demo mode", systemImage: "xmark.circle")
                }
            } else {
                Button {
                    app.enterDemo()
                } label: {
                    Label("Explore demo mode", systemImage: "sparkles")
                }
            }
        } header: {
            Text("Bridge phone")
        }
    }

    private var notificationSection: some View {
        Section {
            if notifier.authorization == .denied {
                Button {
                    openSystemSettings()
                } label: {
                    Label("Notifications are off. Turn them on in Settings", systemImage: "bell.slash")
                }
            } else if notifier.authorization == .notDetermined {
                Button {
                    Task { await notifier.requestAuthorization() }
                } label: {
                    Label("Allow notifications", systemImage: "bell.badge")
                }
            }
            Toggle(isOn: $settings.showMessagePreviews) {
                Label("Show message text", systemImage: "text.bubble")
            }
            Toggle(isOn: $settings.lowBatteryAlert) {
                Label("Bridge battery alerts", systemImage: "battery.25")
            }
            Toggle(isOn: $settings.trialReminder) {
                Label("Trial reminder", systemImage: "hourglass")
            }
            .onValueChange(settings.trialReminder) { on in
                if !on { Notifier.shared.cancelTrialReminder() }
            }
        } header: {
            Text("Notifications")
        } footer: {
            Text("One-time codes get a Copy code button in the notification.")
        }
    }

    private var callsSection: some View {
        Section {
            Toggle(isOn: $settings.callsInRecents) {
                Label("Show calls in iPhone Recents", systemImage: "clock.arrow.circlepath")
            }
            .onValueChange(settings.callsInRecents) { on in
                app.real.calls.setIncludeInRecents(on)
            }
            if bridge.isDualSIM {
                Picker(selection: $settings.defaultCallSim) {
                    Text("Bridge default").tag(-1)
                    ForEach(bridge.sims) { sim in
                        Text(bridge.simLabel(sim.slot)).tag(sim.slot)
                    }
                } label: {
                    Label("SIM for calls", systemImage: "phone")
                }
                Picker(selection: $settings.defaultSMSSim) {
                    Text("Bridge default").tag(-1)
                    ForEach(bridge.sims) { sim in
                        Text(bridge.simLabel(sim.slot)).tag(sim.slot)
                    }
                } label: {
                    Label("SIM for messages", systemImage: "message")
                }
            }
        } header: {
            Text("Calls")
        } footer: {
            Text("iPhone Recents can sync to iCloud, so this is off by default. Calls are always listed in Pairline.")
        }
    }

    private var languageSection: some View {
        Section {
            Button {
                openSystemSettings()
            } label: {
                HStack {
                    Label("Language", systemImage: "globe")
                    Spacer()
                    Text(currentLanguageName).foregroundStyle(.secondary)
                }
            }
        } footer: {
            // The languages themselves are listed by iOS on that screen, not named here.
            Text("Change the language in iPhone Settings › Pairline › Language.")
        }
    }

    private var currentLanguageName: String {
        let code = Bundle.main.preferredLocalizations.first ?? "en"
        return Locale(identifier: code).localizedString(forLanguageCode: code)?.capitalized ?? code
    }

    private var helpSection: some View {
        Section {
            NavigationLink {
                HelpCenterView()
            } label: {
                Label("Help center", systemImage: "questionmark.circle")
            }
            NavigationLink {
                DiagnosticsView()
            } label: {
                Label("Diagnostics", systemImage: "stethoscope")
            }
            NavigationLink {
                PrivacySummaryView()
            } label: {
                Label("Privacy", systemImage: "hand.raised")
            }
        } header: {
            Text("Help")
        }
    }

    private var telegramSection: some View {
        Section {
            Link(destination: AppInfo.telegramChannelURL) {
                Label("News on Telegram", systemImage: "paperplane")
            }
            Link(destination: AppInfo.telegramGroupURL) {
                Label("Help group on Telegram", systemImage: "bubble.left.and.bubble.right")
            }
        } header: {
            Text("Telegram")
        } footer: {
            Text("News and help in your language. The group is public, so never post your phone number, messages or codes there.")
        }
    }

    private var aboutSection: some View {
        Section {
            NavigationLink {
                AboutView()
            } label: {
                Label("About Pairline", systemImage: "info.circle")
            }
            Link(destination: AppInfo.sourceURL) {
                Label("Source code", systemImage: "chevron.left.forwardslash.chevron.right")
            }
        } footer: {
            Text("Version \(AppInfo.version) (\(AppInfo.build)) · Link protocol v\(Int(WireProtocol.version))")
        }
    }

    private func openSystemSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }
}

#if DEBUG
/// Developer options that exist only in debug builds (D-018).
struct DebugSection: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var settings: AppSettings
    @AppStorage(DebugSettings.transportKey) private var transport = DebugSettings.pairingTransport.rawValue
    @State private var pasted = ""

    var body: some View {
        Section {
            Picker("Pair over", selection: $transport) {
                Text("Bluetooth").tag(TransportKind.bluetooth.rawValue)
                Text(verbatim: "TCP 127.0.0.1:47800").tag(TransportKind.tcp.rawValue)
            }
            TextField(String(localized: "Paste a pairing link"), text: $pasted)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .font(.footnote.monospaced())
            Button("Pair with pasted link") {
                if let url = URL(string: pasted.trimmingCharacters(in: .whitespacesAndNewlines)) { app.handleURL(url) }
                pasted = ""
            }
            .disabled(pasted.isEmpty)
            Button("Show onboarding again") { settings.onboardingDone = false }
        } header: {
            Text(verbatim: "Debug")
        } footer: {
            Text(verbatim: "Debug builds only. Release builds pair by scanning the QR code.")
        }
    }
}
#endif
