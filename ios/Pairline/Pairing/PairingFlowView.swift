import AVFoundation
import SwiftUI

/// Guided pairing: install the free bridge app on Android, scan its QR code, connect, allow notifications, and offer
/// Cloud mode. Where Cloud mode (remote mode, D-092) is available, "Pair over the internet" pairs through an invitation
/// instead.
struct PairingFlowView: View {
    enum Step {
        case install
        case scan
        case connecting
        case remote
        case done
        /// After pairing nearby: "Will your Android phone stay at home?", Nearby or Cloud mode (D-099).
        case cloud
    }

    let isOnboarding: Bool
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var purchases: PurchaseManager
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var step: Step
    @State private var error: String?
    @State private var camera: AVAuthorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var pairTask: Task<Void, Never>?
    @State private var showPaywall = false
    @State private var cloudBusy = false
    @State private var cloudError: String?
    /// The answer on the Cloud mode step; starts at the first-run page's choice (D-099).
    @State private var cloudChoice: ConnectionChoice?
    @State private var showCloudInfo = false
    /// Shared with Settings › Connection: once Cloud mode was turned on after its explanation, the switch acts at once.
    @AppStorage("remote.introSeen") private var cloudIntroSeen = false
    #if DEBUG
    @State private var pasted = ""
    #endif

    /// `overInternet`: start at "Pair over the internet" (Settings' row for it).
    init(isOnboarding: Bool, overInternet: Bool = false) {
        self.isOnboarding = isOnboarding
        _step = State(initialValue: overInternet && RemoteFeature.isAvailable ? .remote : Self.initialStep)
    }

    /// The next step slides in from the trailing edge; with Reduce Motion it fades in.
    private var stepTransition: AnyTransition {
        .asymmetric(insertion: reduceMotion ? .opacity : .move(edge: .trailing), removal: .opacity)
    }

    /// Debug builds launched with `-PairlinePairingScan` start at the scan step, with `-PairlinePairingRemote` at
    /// "Pair over the internet", with `-PairlinePairingCloudStep` at the Cloud mode step.
    private static var initialStep: Step {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-PairlinePairingScan") { return .scan }
        if ProcessInfo.processInfo.arguments.contains("-PairlinePairingRemote") { return .remote }
        if ProcessInfo.processInfo.arguments.contains("-PairlinePairingCloudStep") { return .cloud }
        #endif
        return .install
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color(.systemGroupedBackground).ignoresSafeArea()
                switch step {
                case .install: install.transition(stepTransition)
                case .scan: scan.transition(stepTransition)
                case .connecting: connecting.transition(.opacity)
                case .remote: remote.transition(stepTransition)
                case .done: done.transition(.opacity)
                case .cloud: cloud.transition(stepTransition)
                }
            }
            .animation(.motion(.spring(response: 0.45, dampingFraction: 0.9), reduceMotion: reduceMotion), value: step)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if !isOnboarding || step == .scan || step == .remote {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(isOnboarding || step == .remote ? "Back" : "Cancel") {
                            if step == .remote || (isOnboarding && step == .scan) {
                                // Leaving the remote step cancels its pairing (RemotePairingView).
                                step = .install
                            } else {
                                cancel()
                            }
                        }
                    }
                }
                // "Later" also skips the free-week choice (D-069): the app opens, and the trial can start any time
                // from its banner.
                if isOnboarding, step == .install || (step == .scan && !app.linkAllowed) {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Later") { finish() }
                    }
                }
            }
        }
        .interactiveDismissDisabled(step == .connecting || step == .remote)
        // The free-week prompt after the first sync waits until this flow has closed (D-069).
        .onAppear { app.pairingFlowOpen = true }
        .onDisappear { app.pairingFlowOpen = false }
        .sheet(isPresented: $showPaywall) {
            PaywallView()
                .environmentObject(purchases)
        }
    }

    // MARK: - Install

    /// Sized to fit an iPhone screen without scrolling, with "I've installed it" pinned to the bottom so it's never
    /// below the fold; it still scrolls with very large text.
    private var install: some View {
        ScrollView {
            VStack(spacing: 14) {
                StepHeader(number: 1, of: 2, title: Text("Install Pairline Bridge on your Android phone"),
                           subtitle: Text("It's free. It runs on the Android phone that holds your SIM card."))
                VStack(spacing: 10) {
                    QRCodeImage(text: AppInfo.downloadURL.absoluteString)
                        .frame(width: 110, height: 110)
                        .padding(10)
                        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(.white))
                    Text("Scan this with the Android phone's camera, or open:")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Text(verbatim: "getpairline.com")
                        .font(.headline.monospaced())
                        .foregroundStyle(Color.brandBlue)
                        .environment(\.layoutDirection, .leftToRight)
                    HStack(spacing: 10) {
                        CopyChip(label: Text("Copy link"), value: AppInfo.downloadURL.absoluteString)
                        ShareLink(item: AppInfo.downloadURL) {
                            Label("Share", systemImage: "square.and.arrow.up")
                                .font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 7)
                                .background(Capsule().fill(Color.brandBlue.opacity(0.14)))
                        }
                    }
                }
                .frame(maxWidth: .infinity)
                .card(padding: 14)
                VStack(alignment: .leading, spacing: 10) {
                    HelpBullet(symbol: "checkmark.shield", text: Text("Allow the permissions it asks for: SMS, phone and Bluetooth."))
                    HelpBullet(symbol: "battery.100", text: Text("Let it run without battery restrictions so the link stays up."))
                    HelpBullet(symbol: "qrcode", text: Text("Tap Pair in the bridge app to show a pairing code."))
                }
                .card(padding: 14)
                if RemoteFeature.isAvailable {
                    farAwayButton
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 12)
        }
        .scrollsOnlyWhenNeeded()
        .safeAreaInset(edge: .bottom) {
            Button("I've installed it") {
                Haptics.tap()
                step = .scan
            }
            .buttonStyle(PrimaryButtonStyle())
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 6)
            .background(.regularMaterial)
        }
    }

    // MARK: - Scan

    private var scan: some View {
        ScrollView {
            VStack(spacing: 20) {
                StepHeader(number: 2, of: 2, title: Text("Scan the pairing code"),
                           subtitle: Text("Open Pairline Bridge on the Android phone, tap Pair and point this iPhone at the code."))
                if app.linkAllowed {
                    // A trial, a purchase, or before either: pairing leads to the free first sync (D-069).
                    scanner
                } else if purchases.access.isExpired {
                    VStack(spacing: 12) {
                        Image(systemName: "lock.fill").font(.largeTitle).foregroundStyle(Color.brandAmber)
                        Text("Pairing and the link need an active trial or a purchase.")
                            .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        Button("See options") { showPaywall = true }
                            .buttonStyle(PrimaryButtonStyle())
                    }
                    .card(padding: 20)
                } else {
                    trialChoices
                }
                if let error {
                    Label {
                        Text(error)
                    } icon: {
                        Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Color.brandAmber)
                    }
                    .font(.subheadline)
                    .card()
                }
                Label("The code works once and only for this pairing. Only someone who can see your Android screen can pair.",
                      systemImage: "lock.shield")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if RemoteFeature.isAvailable {
                    farAwayButton
                }
                #if DEBUG
                debugPaste
                #endif
            }
            .padding(20)
        }
        .onAppear(perform: checkCamera)
    }

    @ViewBuilder private var scanner: some View {
        if !QRScannerView.isCameraAvailable {
            cameraMessage(Text("This device has no camera. Use a physical iPhone to scan the pairing code."))
        } else {
            switch camera {
            case .authorized:
                QRScannerView { code in handleScanned(code) }
                    .frame(height: 320)
                    .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 24, style: .continuous)
                            .strokeBorder(Color.brandBlue.opacity(0.6), lineWidth: 3)
                    )
                    .overlay(alignment: .bottom) {
                        // On a Mac (the iPad app, D-076) the FaceTime camera faces the user.
                        (ProcessInfo.processInfo.isiOSAppOnMac
                            ? Text("Hold the Android phone's screen up to this Mac's camera.", tableName: "PhoneTools")
                            : Text("Looking for a Pairline code…"))
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(.ultraThinMaterial, in: Capsule())
                            .padding(12)
                    }
            case .denied, .restricted:
                VStack(spacing: 12) {
                    cameraMessage(Text("Camera access is off. Pairline only uses the camera to scan the pairing code."))
                    Button("Open Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                    .buttonStyle(SecondaryButtonStyle())
                }
            default:
                // "Continue", never "Allow": a button before the system's permission prompt mustn't tell people what
                // to answer (App Review guideline 5.1.1(iv), D-097). The step's title already says what it's for.
                Button("Continue") {
                    AVCaptureDevice.requestAccess(for: .video) { _ in
                        DispatchQueue.main.async { checkCamera() }
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
            }
        }
    }

    /// Before the first scan: the free week or the purchase (D-069). Apple's rule for a paid-once app is that the
    /// trial goes through the free "7-day Trial" item, and that its length, what stops when it ends and the price
    /// show before it starts. Starting either one shows the scanner here.
    @ViewBuilder private var trialChoices: some View {
        if purchases.access == .loading {
            ProgressView()
                .frame(maxWidth: .infinity)
                .card(padding: 24)
        } else {
            VStack(spacing: 14) {
                Text("Start your free trial to pair")
                    .font(.headline)
                    .multilineTextAlignment(.center)
                OptionCard(symbol: "gift.fill", tint: .brandFill, title: Text("Try free for 7 days"),
                           text: Text("Everything works. Then \(purchases.priceText) once to keep it, or it stops connecting.")) {
                    Task { _ = await purchases.startTrial() }
                }
                OptionCard(symbol: "bag.fill", tint: .brandGreen, title: Text("Buy once · \(purchases.priceText)"),
                           text: Text("No subscription. Yours for good.")) {
                    Task { _ = await purchases.buyFull() }
                }
                if purchases.busy {
                    ProgressView()
                }
                Text("The 7-day trial is free. When it ends, sending messages, calls, syncing and USSD stop until you buy Pairline for \(purchases.priceText) once. Messages already synced stay readable. Nothing is charged automatically.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Button("Restore Purchases") { Task { await purchases.restore() } }
                    .font(.footnote.weight(.semibold))
            }
            .alert(Text("Pairline"), isPresented: Binding(get: { purchases.message != nil },
                                                          set: { if !$0 { purchases.message = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(purchases.message ?? "")
            }
        }
    }

    private func cameraMessage(_ text: Text) -> some View {
        VStack(spacing: 10) {
            Image(systemName: "camera.fill").font(.largeTitle).foregroundStyle(.secondary)
            text.font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .card(padding: 24)
    }

    #if DEBUG
    private var debugPaste: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: "Debug: paste a pairing link (Simulator)").font(.caption.weight(.semibold))
            TextField(text: $pasted) { Text(verbatim: "pairline://pair?v=0&id=…") }
                .font(.footnote.monospaced())
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button {
                handleScanned(pasted)
            } label: {
                Text(verbatim: "Pair with this link")
            }
            .disabled(pasted.isEmpty)
        }
        .card()
    }
    #endif

    // MARK: - Over the internet (remote mode, "Cloud mode", D-092)

    /// Says when to use it, so it isn't mistaken for the normal way (D-099).
    private var farAwayButton: some View {
        Button {
            Haptics.tap()
            error = nil
            step = .remote
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "icloud.and.arrow.up")
                    .font(.title3)
                    .foregroundStyle(Color.brandBlue)
                    .frame(width: 30)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Pair over the internet", tableName: "RemoteMode")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text("When the Android phone isn't with you, for example because it's at home.", tableName: "Connection")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
            .card(padding: 14)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }

    /// Needs a trial or the purchase: through iCloud there's no free first sync (D-069) to lead to. After the trial it
    /// is part of the remote-mode add-on (D-093).
    @ViewBuilder private var remote: some View {
        if purchases.remoteAccess.allowsRemote {
            RemotePairingView { step = .done }
        } else if purchases.remoteAccess.offersPurchase {
            ScrollView {
                remoteAddOnOffer
                    .padding(20)
            }
        } else {
            ScrollView {
                trialChoices
                    .padding(20)
            }
        }
    }

    /// Full Access is bought but not the Cloud mode add-on, which pairing over the internet is part of (D-093).
    private var remoteAddOnOffer: some View {
        VStack(spacing: 12) {
            Image(systemName: "icloud.and.arrow.up").font(.largeTitle).foregroundStyle(Color.brandBlue)
            Text("Pairing over the internet is part of Cloud mode, a one-time \(purchases.remotePriceText) add-on to Pairline Full Access.",
                 tableName: "RemoteAddOn")
                .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button {
                Haptics.tap()
                Task {
                    if await purchases.buyRemote() { Haptics.success() }
                }
            } label: {
                Text("Buy Cloud mode · \(purchases.remotePriceText)", tableName: "RemoteAddOn")
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(purchases.busy)
            if let message = purchases.message {
                Text(message).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
        }
        .card(padding: 20)
    }

    // MARK: - Connecting / done

    private var connecting: some View {
        VStack(spacing: 24) {
            Spacer()
            TwoPhonesIllustration(state: .connecting, height: 150)
                .padding(.horizontal, 40)
            Text("Pairing with your bridge phone…").font(.title3.weight(.semibold))
            Text("Keep both phones close together, with Pairline Bridge open.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            ProgressView()
            Spacer()
            Button("Cancel") { cancel() }
                .buttonStyle(SecondaryButtonStyle())
        }
        .padding(24)
    }

    private var done: some View {
        VStack(spacing: 22) {
            Spacer()
            ZStack {
                Circle().fill(Color.brandGreen.opacity(0.15)).frame(width: 130, height: 130)
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 72))
                    .foregroundStyle(Color.brandGreen)
            }
            Text("You're paired!").font(.largeTitle.weight(.bold))
            (app.real.phase == .remote
                ? Text("Texts and codes from your Android phone's SIM now come to this iPhone through iCloud. Calls work once the direct connection to its home is up.")
                : Text("Messages and calls from your Android phone's SIM now come to this iPhone."))
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Spacer()
            if Notifier.shared.authorization == .notDetermined {
                // One "Continue" that always leads to the system's prompt, where "Don't Allow" is the choice: no
                // "Allow" wording and no way around the prompt on this screen (App Review guideline 5.1.1(iv), D-097).
                VStack(spacing: 10) {
                    Text("So you hear about new messages, codes and calls.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Button {
                        Task {
                            await Notifier.shared.requestAuthorization()
                            afterPaired()
                        }
                    } label: {
                        Text("Continue")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                }
            } else {
                Button {
                    afterPaired()
                } label: {
                    offersCloudStep ? Text("Continue") : Text("Done")
                }
                .buttonStyle(PrimaryButtonStyle())
            }
        }
        .padding(24)
    }

    // MARK: - Nearby or Cloud mode, after pairing nearby (D-092, D-093, D-099)

    /// After pairing nearby, one plain question: will the Android phone stay at home? Asked where Cloud mode exists,
    /// while the new bridge is linked and speaks it, and Cloud mode isn't on yet. Before the trial or the purchase the
    /// answer is remembered, and Cloud mode is offered again once the trial runs.
    private var offersCloudStep: Bool {
        guard RemoteFeature.isAvailable, let remote = app.link.remote, !remote.isOn else { return false }
        guard app.real.phase.isConnected, app.real.bridgeSupportsRemote else { return false }
        return purchases.remoteAccess != .loading
    }

    private func afterPaired() {
        if offersCloudStep {
            step = .cloud
        } else {
            finish()
        }
    }

    private var chosenWay: ConnectionChoice { cloudChoice ?? settings.connectionChoice }

    private var cloud: some View {
        ScrollView {
            VStack(spacing: 16) {
                VStack(spacing: 8) {
                    Text("Will your Android phone stay at home?", tableName: "Connection")
                        .font(.title2.weight(.bold))
                        .multilineTextAlignment(.center)
                    Text("Choose how you'll use your phones. You can change this any time in Settings.", tableName: "Connection")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                ConnectionChooser(choice: Binding(get: { chosenWay }, set: { cloudChoice = $0 }),
                                  nearbyTitle: Text("No, it stays with me", tableName: "Connection"),
                                  cloudTitle: Text("Yes, it stays at home", tableName: "Connection"))
                if chosenWay == .cloud {
                    cloudNeeds
                }
                BothCanBeOnNote()
                Button {
                    showCloudInfo = true
                } label: {
                    Label {
                        Text("How Cloud mode works", tableName: "RemoteMode")
                    } icon: {
                        Image(systemName: "questionmark.circle")
                    }
                    .font(.subheadline.weight(.semibold))
                }
                if let cloudError {
                    Label {
                        Text(cloudError)
                    } icon: {
                        Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Color.brandAmber)
                    }
                    .font(.subheadline)
                    .card()
                }
                if let message = purchases.message {
                    Text(message).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }
            }
            .padding(20)
        }
        .scrollsOnlyWhenNeeded()
        .safeAreaInset(edge: .bottom) {
            cloudButton
                .padding(.horizontal, 20)
                .padding(.top, 12)
                .padding(.bottom, 6)
                .background(.regularMaterial)
        }
        .sheet(isPresented: $showCloudInfo) {
            RemoteModeIntroView()
        }
    }

    /// What Cloud mode needs, and what it costs for this purchase state, under the chosen card.
    private var cloudNeeds: some View {
        VStack(alignment: .leading, spacing: 10) {
            HelpBullet(symbol: "bolt.fill", text: Text("Leave the Android phone on its charger and home Wi-Fi.", tableName: "Connection"))
            HelpBullet(symbol: "wifi.router", text: Text("For calls, your home router lets this iPhone in. Most routers do it by themselves; Settings › Connection › Advanced shows how otherwise.", tableName: "Connection"))
            Group {
                switch purchases.remoteAccess {
                case .needsAddOn:
                    RemoteAddOnText.blocker(.needsAddOn)
                case .needsFullAccess:
                    Text("Cloud mode comes with the free trial. Once it starts, Pairline offers to turn it on.", tableName: "Connection")
                default:
                    RemoteAddOnText.trialNote(purchases.remoteAccess, price: purchases.remotePriceText,
                                              bundlePrice: purchases.bundleOffer?.bundlePrice)
                }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card(padding: 14)
    }

    /// Nearby: done. Cloud mode: turn it on, buy the add-on first, or (before the trial) just remember the choice.
    @ViewBuilder private var cloudButton: some View {
        if chosenWay == .nearby {
            Button {
                Haptics.tap()
                settings.connectionChoice = .nearby
                finish()
            } label: {
                Text("Done")
            }
            .buttonStyle(PrimaryButtonStyle())
        } else if purchases.remoteAccess.offersPurchase {
            Button {
                Haptics.tap()
                Task {
                    if await purchases.buyRemote() { Haptics.success() }
                }
            } label: {
                Text("Buy Cloud mode · \(purchases.remotePriceText)", tableName: "RemoteAddOn")
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(purchases.busy)
        } else if purchases.remoteAccess.allowsRemote {
            Button {
                turnOnCloud()
            } label: {
                if cloudBusy {
                    ProgressView()
                } else {
                    Text("Turn on Cloud mode", tableName: "RemoteMode")
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(cloudBusy)
        } else {
            Button {
                Haptics.tap()
                settings.connectionChoice = .cloud
                finish()
            } label: {
                Text("Continue")
            }
            .buttonStyle(PrimaryButtonStyle())
        }
    }

    /// The same path as Settings › Connection's switch; it stays there for later either way.
    private func turnOnCloud() {
        guard !cloudBusy else { return }
        Haptics.tap()
        cloudBusy = true
        cloudError = nil
        Task {
            do {
                try await app.link.turnOnRemote()
                settings.connectionChoice = .cloud
                cloudIntroSeen = true
                Haptics.success()
                cloudBusy = false
                finish()
            } catch {
                cloudError = error.localizedDescription
                Haptics.error()
                cloudBusy = false
            }
        }
    }

    // MARK: - Actions

    private func checkCamera() {
        camera = AVCaptureDevice.authorizationStatus(for: .video)
    }

    private func handleScanned(_ code: String) {
        guard step == .scan else { return }
        let link: PairingLink
        do {
            link = try PairingLink(string: code)
        } catch {
            self.error = PairingError.invalidCode.errorDescription
            Haptics.error()
            return
        }
        error = nil
        Haptics.success()
        step = .connecting
        pairTask = Task {
            do {
                try await app.pair(with: link)
                Haptics.success()
                step = .done
            } catch is CancellationError {
                step = .scan
            } catch {
                self.error = error.localizedDescription
                Haptics.error()
                step = .scan
            }
        }
    }

    private func cancel() {
        if step == .connecting { app.link.cancelPairing() }
        pairTask?.cancel()
        dismiss()
    }

    private func finish() {
        settings.onboardingDone = true
        dismiss()
    }
}

struct StepHeader: View {
    let number: Int
    let of: Int
    let title: Text
    let subtitle: Text

    var body: some View {
        VStack(spacing: 8) {
            Text("Step \(number) of \(of)")
                .font(.caption.weight(.bold))
                .foregroundStyle(Color.brandBlue)
                .textCase(.uppercase)
            title
                .font(.title2.weight(.bold))
                .multilineTextAlignment(.center)
            subtitle
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
    }
}
