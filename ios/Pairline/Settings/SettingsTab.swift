import SwiftUI
import UserNotifications

struct SettingsTab: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var purchases: PurchaseManager
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var bridge: BridgeController
    @ObservedObject private var notifier = Notifier.shared
    @ObservedObject private var liveActivity = BridgeLiveActivity.shared
    @State private var confirmUnpair = false
    @Environment(\.horizontalSizeClass) private var sizeClass
    /// The page open beside the list on a large display (D-063); About until one is chosen.
    @State private var page: Page?

    enum Page: Hashable {
        case blocked, netShare, help, diagnostics, privacy, about
    }

    var body: some View {
        if sizeClass == .regular {
            // A large display: the settings and a page side by side; on iPhone Duo each on its own half (D-063).
            ListDetailSplit {
                settingsList
            } detail: {
                destination(page ?? .about)
                    .id(page ?? .about)
            }
        } else {
            NavigationStack { settingsList }
        }
    }

    @ViewBuilder private func destination(_ page: Page) -> some View {
        switch page {
        case .blocked: BlockedNumbersView()
        case .netShare: NetShareView()
        case .help: HelpCenterView()
        case .diagnostics: DiagnosticsView()
        case .privacy: PrivacySummaryView()
        case .about: AboutView()
        }
    }

    /// Opens a page beside the list on a large display, or pushes it on a phone.
    @ViewBuilder private func pageLink<Label: View>(_ target: Page, @ViewBuilder label: () -> Label) -> some View {
        if sizeClass == .regular {
            let isOpen = (page ?? .about) == target
            Button {
                page = target
            } label: {
                HStack {
                    label()
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.forward")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                .contentShape(Rectangle())
            }
            .foregroundStyle(.primary)
            .listRowBackground(isOpen ? Color.brandBlue.opacity(0.14) : nil)
            .accessibilityAddTraits(isOpen ? .isSelected : [])
        } else {
            NavigationLink {
                destination(target)
            } label: {
                label()
            }
        }
    }

    private var settingsList: some View {
            List {
                purchaseSection
                bridgeSection
                liveActivitySection
                notificationSection
                // Shown once the bridge has reported its SIMs (D-062).
                if !bridge.sims.isEmpty { SIMDefaultsSection() }
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
            if app.pairedPeer != nil || bridge.isDemo {
                pageLink(.blocked) {
                    HStack {
                        Label("Blocked numbers", systemImage: "hand.raised")
                        Spacer()
                        if !bridge.blockedNumbers.isEmpty {
                            Text(verbatim: bridge.blockedNumbers.count.formatted())
                                .foregroundStyle(.secondary)
                        }
                    }
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

    /// The bridge's signal, network and battery on the Lock Screen and in the Dynamic Island (D-053).
    @ViewBuilder private var liveActivitySection: some View {
        if BridgeLiveActivity.isSupported {
            Section {
                Toggle(isOn: $settings.bridgeLiveActivity) {
                    Label {
                        liveText("Bridge status on Lock Screen")
                    } icon: {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                    }
                }
                .onValueChange(settings.bridgeLiveActivity) { _ in liveActivity.settingChanged() }
                if settings.bridgeLiveActivity && !liveActivity.systemEnabled {
                    Button {
                        openSystemSettings()
                    } label: {
                        Label {
                            liveText("Live Activities are off for Pairline. Turn them on in Settings")
                        } icon: {
                            Image(systemName: "exclamationmark.triangle")
                        }
                    }
                }
            } footer: {
                liveText("Shows the bridge phone's signal, network and battery on the Lock Screen and in the Dynamic Island. iOS ends it after 8 hours; opening Pairline starts it again.")
            }
        }
    }

    private func liveText(_ key: String) -> Text { Text(LocalizedStringKey(key), tableName: "LiveActivity") }

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
            pageLink(.netShare) {
                Label { Text("Internet sharing", tableName: "NetShare") } icon: { Image(systemName: "network") }
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
            pageLink(.help) {
                Label("Help center", systemImage: "questionmark.circle")
            }
            pageLink(.diagnostics) {
                Label("Diagnostics", systemImage: "stethoscope")
            }
            pageLink(.privacy) {
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
            pageLink(.about) {
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
                Text(verbatim: "Wi-Fi only").tag(TransportKind.wifi.rawValue)
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
