import SwiftUI

struct RootView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.horizontalSizeClass) private var sizeClass

    /// The paywall as a sheet on a phone, full screen on a large display, where a centred sheet would sit across iPhone
    /// Duo's fold with its buttons on it (D-063).
    private func paywall(fullScreen: Bool) -> Binding<Bool> {
        Binding(get: { app.showPaywall && (sizeClass == .regular) == fullScreen }, set: { app.showPaywall = $0 })
    }

    var body: some View {
        Group {
            if !settings.onboardingDone && !app.isDemo {
                OnboardingFlow()
                    .transition(.opacity)
            } else {
                MainView(bridge: app.active)
                    .id(app.isDemo)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.35), value: settings.onboardingDone)
        .animation(.easeInOut(duration: 0.35), value: app.isDemo)
        .sheet(isPresented: paywall(fullScreen: false)) {
            PaywallView()
        }
        .fullScreenCover(isPresented: paywall(fullScreen: true)) {
            PaywallView()
        }
        .sheet(isPresented: $app.showPairing, onDismiss: { app.pairingOverInternet = false }) {
            PairingFlowView(isOnboarding: false, overInternet: app.pairingOverInternet)
        }
        .overlay(alignment: .top) {
            ToastOverlay(text: $app.toast)
        }
        // The Android phone is ringing this iPhone (D-084).
        .overlay(alignment: .bottom) {
            RingingOverlay()
        }
        .tint(.brandBlue)
    }
}

struct MainView: View {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var bridge: BridgeController
    @ObservedObject private var calls: CallCenter
    @State private var retriedCallScreen = false

    init(bridge: BridgeController) {
        self.bridge = bridge
        self.calls = bridge.calls
    }

    var body: some View {
        TabView(selection: $app.tab) {
            MessagesTab()
                .modifier(ongoingCallBar)
                .tabItem { Label("Messages", systemImage: "message.fill") }
                .badge(unreadBadge)
                .tag(AppTab.messages)
            CallsTab()
                .modifier(ongoingCallBar)
                .tabItem { Label("Calls", systemImage: "phone.fill") }
                .tag(AppTab.calls)
            ContactsTab()
                .modifier(ongoingCallBar)
                .tabItem { Label("Contacts", systemImage: "person.crop.circle.fill") }
                .tag(AppTab.contacts)
            BridgeStatusTab()
                .modifier(ongoingCallBar)
                .tabItem { Label("Bridge", systemImage: "iphone.gen2.radiowaves.left.and.right") }
                .tag(AppTab.bridge)
            SettingsTab()
                .modifier(ongoingCallBar)
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
                .tag(AppTab.settings)
        }
        .environmentObject(bridge)
        .environmentObject(bridge.db)
        .environmentObject(bridge.calls)
        .fullScreenCover(isPresented: $calls.showCallScreen) {
            CallScreen()
                .environmentObject(bridge)
                .environmentObject(bridge.calls)
                .environmentObject(bridge.db)
        }
        .modifier(CallAudioFeedbackModifier(calls: calls))
        // Once per pairing: offer to set Talk on iPhone up on the Android phone (D-107).
        .modifier(TalkSetupPromptModifier(bridge: bridge))
        .onValueChange(calls.showCallScreen) { show in
            guard show, !retriedCallScreen else { return }
            // SwiftUI silently drops a fullScreenCover requested during another presentation or a transition.
            // Ask once more if the screen hasn't appeared; after that the call bar stays as the way in.
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                guard calls.showCallScreen, !calls.callScreenVisible, calls.current != nil else { return }
                retriedCallScreen = true
                presentCallScreen()
            }
        }
        .onValueChange(calls.callScreenVisible) { visible in
            if visible { retriedCallScreen = false }
        }
        .onValueChange(calls.current?.id) { _ in retriedCallScreen = false }
        .onReceive(calls.$notice.compactMap { $0 }) { notice in
            app.toast = notice
            calls.notice = nil
        }
        .onReceive(bridge.$banner.compactMap { $0 }) { text in
            app.toast = text
            bridge.banner = nil
        }
    }

    private var ongoingCallBar: OngoingCallInset {
        OngoingCallInset(calls: calls) { presentCallScreen() }
    }

    /// Shows the call screen, re-requesting it when the flag is stuck on true after a dropped presentation.
    private func presentCallScreen() {
        guard calls.showCallScreen else {
            calls.showCallScreen = true
            return
        }
        calls.showCallScreen = false
        DispatchQueue.main.async { calls.showCallScreen = true }
    }

    private var unreadBadge: Int {
        _ = bridge.db.revision
        return bridge.db.unreadCount
    }
}

/// Puts the ongoing-call bar at the bottom of a tab's own safe area: above the tab bar when it's at the bottom, and
/// beside it when iPhone Duo puts it on the side (D-063). Lists scroll above the bar instead of under it.
struct OngoingCallInset: ViewModifier {
    @ObservedObject var calls: CallCenter
    let open: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .bottom, spacing: 0) {
                // Keyed on what's actually on screen, so a dropped presentation never leaves a call without controls.
                if let call = calls.current, !calls.callScreenVisible {
                    OngoingCallBar(call: call, open: open)
                        .padding(.vertical, 8)
                        .transition(.slide(from: .bottom, reduceMotion: reduceMotion))
                }
            }
    }
}

/// A slim bar for a call that's in progress while the call screen is hidden.
struct OngoingCallBar: View {
    let call: ActiveCall
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(spacing: 10) {
                Image(systemName: "phone.fill")
                Text(call.displayName).lineLimit(1)
                Spacer()
                if let start = call.connectedAt {
                    Text(start, style: .timer).monospacedDigit()
                } else {
                    Text(call.state == .ringing ? "Incoming call" : "Calling…")
                }
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Capsule().fill(Color.brandGreenFill))
            .padding(.horizontal, 16)
            .shadow(color: .black.opacity(0.15), radius: 8, y: 4)
        }
        .buttonStyle(.plain)
    }
}

struct ToastOverlay: View {
    @Binding var text: String?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            if let text {
                Text(text)
                    .font(.subheadline.weight(.medium))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(.regularMaterial, in: Capsule())
                    .shadow(color: .black.opacity(0.12), radius: 10, y: 4)
                    .padding(.horizontal, 24)
                    .padding(.top, 8)
                    .transition(.slide(from: .top, reduceMotion: reduceMotion))
                    .onTapGesture { self.text = nil }
                    .task(id: text) {
                        // Long enough to read: 3.5 s, or about 15 characters a second for longer notes. A tap closes it.
                        let seconds = max(3.5, Double(text.count) / 15)
                        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                        withAnimation { self.text = nil }
                    }
                    .accessibilityAddTraits(.isStaticText)
            }
        }
        .animation(.motion(.spring(response: 0.4, dampingFraction: 0.85), reduceMotion: reduceMotion), value: text)
    }
}

/// Demo, trial and connection banners shown above the main lists.
struct BridgeBanners: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var purchases: PurchaseManager
    @EnvironmentObject private var bridge: BridgeController

    var body: some View {
        VStack(spacing: 8) {
            if bridge.isDemo && DebugSettings.marketingScreenshots {
                EmptyView()
            } else if bridge.isDemo {
                InfoBanner(symbol: "sparkles", tint: .brandAmber, text: Text("Demo mode · sample data")) {
                    Menu {
                        Button {
                            app.demoBridge?.simulateIncomingSMS(otp: true)
                        } label: { Label("Receive a code", systemImage: "number") }
                        Button {
                            app.demoBridge?.simulateIncomingSMS(otp: false)
                        } label: { Label("Receive a message", systemImage: "message") }
                        Button {
                            app.demoBridge?.simulateIncomingCall()
                        } label: { Label("Receive a call", systemImage: "phone.arrow.down.left") }
                        Divider()
                        Button(role: .destructive) {
                            app.exitDemo()
                        } label: { Label("Exit demo", systemImage: "xmark.circle") }
                    } label: {
                        Text("Try it")
                            .font(.subheadline.weight(.semibold))
                    }
                    Button {
                        app.exitDemo()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title3)
                            .symbolRenderingMode(.hierarchical)
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityLabel(Text("Exit demo"))
                }
            } else {
                switch purchases.access {
                case .trial(let end):
                    let left = TrialPolicy.remaining(until: end, now: purchases.trustedNow)
                    if left.days < 2 {
                        InfoBanner(symbol: "hourglass", tint: .brandAmber, text: TrialText.remaining(left)) {
                            Button("Unlock") { app.showPaywall = true }
                                .font(.subheadline.weight(.semibold))
                        }
                    }
                case .expired:
                    InfoBanner(symbol: "lock.fill", tint: .brandAmber,
                               text: Text("Your free trial has ended. Synced messages stay readable.")) {
                        Button("Unlock") { app.showPaywall = true }
                            .font(.subheadline.weight(.semibold))
                    }
                case .notStarted where app.isPaired:
                    InfoBanner(symbol: "gift.fill", tint: .brandBlue, text: Text("Start your free week to connect.")) {
                        Button("Start") { app.showPaywall = true }
                            .font(.subheadline.weight(.semibold))
                    }
                default:
                    EmptyView()
                }
                if app.isPaired, purchases.access.allowsLink, !bridge.phase.isConnected {
                    OfflineBanner(phase: bridge.phase)
                }
                if RemoteFeature.isAvailable, app.isPaired, let remote = app.link.remote {
                    CloudModeOfferBanner(remote: remote, bridge: app.real)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 4)
    }
}

/// The user chose Cloud mode (first run or pairing, D-099) but it couldn't be turned on then, before the free trial:
/// offered here once it can be, while the phones are together. Closing it means "nearby only"; Settings › Connection
/// still has the switch.
struct CloudModeOfferBanner: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var purchases: PurchaseManager
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var remote: RemoteLink
    @ObservedObject var bridge: BridgeController
    @AppStorage("remote.introSeen") private var introSeen = false
    @State private var showIntro = false
    @State private var busy = false

    private var shown: Bool {
        settings.connectionChoice == .cloud && !remote.isOn && purchases.remoteAccess.allowsRemote
            && bridge.phase.isConnected && bridge.bridgeSupportsRemote
    }

    var body: some View {
        if shown {
            InfoBanner(symbol: "icloud", tint: .brandBlue,
                       text: Text("You chose Cloud mode. Turn it on now, while your phones are together.", tableName: "Connection")) {
                Button {
                    showIntro = true
                } label: {
                    Text("Turn on", tableName: "Connection")
                        .font(.subheadline.weight(.semibold))
                }
                .disabled(busy)
                Button {
                    settings.connectionChoice = .nearby
                    app.toast = String(localized: "You can turn on Cloud mode any time in Settings › Connection.", table: "Connection")
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title3)
                        .symbolRenderingMode(.hierarchical)
                        .foregroundStyle(.secondary)
                }
                .accessibilityLabel(Text("Not now", tableName: "RemoteMode"))
            }
            .sheet(isPresented: $showIntro) {
                RemoteModeIntroView(onTurnOn: turnOn)
            }
        }
    }

    private func turnOn() {
        guard !busy else { return }
        busy = true
        Task {
            do {
                try await app.link.turnOnRemote()
                introSeen = true
                Haptics.success()
                app.toast = String(localized: "Cloud mode is on.", table: "Connection")
            } catch {
                app.toast = error.localizedDescription
                Haptics.error()
            }
            busy = false
        }
    }
}

struct OfflineBanner: View {
    let phase: LinkPhase
    @EnvironmentObject private var app: AppModel

    var body: some View {
        InfoBanner(symbol: symbol, tint: tint, text: text) {
            Button("Help") { app.tab = .bridge }
                .font(.subheadline.weight(.semibold))
        }
    }

    /// Remote mode (D-092) says what works through iCloud: calls need the direct connection.
    private var text: Text {
        phase == .remote
            ? Text("Via Cloud mode: texts and codes work. Calls need the direct connection to your home.")
            : LinkStatusPill.title(for: phase)
    }

    private var symbol: String {
        switch phase {
        case .bluetoothOff, .bluetoothUnauthorized, .bluetoothUnsupported: return "antenna.radiowaves.left.and.right.slash"
        case .remote: return "icloud"
        default: return "arrow.triangle.2.circlepath"
        }
    }

    private var tint: Color {
        switch phase {
        case .bluetoothOff, .bluetoothUnauthorized, .bluetoothUnsupported: return .brandRed
        case .remote: return .brandBlue
        default: return .brandAmber
        }
    }
}

enum TrialText {
    /// "Free trial: 2 days, 5 hours left", with the duration written by the system in the app's language
    /// (correct plurals and digits).
    static func remaining(_ left: (days: Int, hours: Int)) -> Text {
        let formatter = DateComponentsFormatter()
        formatter.unitsStyle = .full
        formatter.allowedUnits = left.days > 0 ? [.day, .hour] : [.hour]
        formatter.maximumUnitCount = 2
        formatter.calendar = Formatters.appCalendar
        let duration = formatter.string(from: DateComponents(day: left.days, hour: left.hours)) ?? ""
        return Text("Free trial: \(duration) left")
    }
}
