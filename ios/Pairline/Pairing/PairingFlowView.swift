import AVFoundation
import SwiftUI

/// Guided pairing: install the free bridge app on Android, scan its QR code, connect, allow notifications.
struct PairingFlowView: View {
    enum Step {
        case install
        case scan
        case connecting
        case done
    }

    let isOnboarding: Bool
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var purchases: PurchaseManager
    @EnvironmentObject private var settings: AppSettings
    @State private var step: Step = .install
    @State private var error: String?
    @State private var camera: AVAuthorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var pairTask: Task<Void, Never>?
    @State private var showPaywall = false
    #if DEBUG
    @State private var pasted = ""
    #endif

    var body: some View {
        NavigationStack {
            ZStack {
                Color(.systemGroupedBackground).ignoresSafeArea()
                switch step {
                case .install: install.transition(.asymmetric(insertion: .move(edge: .trailing), removal: .opacity))
                case .scan: scan.transition(.asymmetric(insertion: .move(edge: .trailing), removal: .opacity))
                case .connecting: connecting.transition(.opacity)
                case .done: done.transition(.opacity)
                }
            }
            .animation(.spring(response: 0.45, dampingFraction: 0.9), value: step)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if !isOnboarding || step == .scan {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(isOnboarding ? "Back" : "Cancel") {
                            if isOnboarding, step == .scan {
                                step = .install
                            } else {
                                cancel()
                            }
                        }
                    }
                }
                if isOnboarding, step == .install {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Later") { finish() }
                    }
                }
            }
        }
        .interactiveDismissDisabled(step == .connecting)
        .sheet(isPresented: $showPaywall) {
            PaywallView()
                .environmentObject(purchases)
        }
    }

    // MARK: - Install

    private var install: some View {
        ScrollView {
            VStack(spacing: 22) {
                StepHeader(number: 1, of: 2, title: Text("Install Pairline Bridge on your Android phone"),
                           subtitle: Text("It's free. It runs on the Android phone that holds your SIM card."))
                VStack(spacing: 14) {
                    QRCodeImage(text: AppInfo.downloadURL.absoluteString)
                        .frame(width: 170, height: 170)
                        .padding(14)
                        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(.white))
                    Text("Scan this with the Android phone's camera, or open:")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Text(verbatim: "getpairline.com")
                        .font(.title3.weight(.semibold).monospaced())
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
                .card(padding: 20)
                VStack(alignment: .leading, spacing: 12) {
                    HelpBullet(symbol: "checkmark.shield", text: Text("Allow the permissions it asks for: SMS, phone and Bluetooth."))
                    HelpBullet(symbol: "battery.100", text: Text("Let it run without battery restrictions so the link stays up."))
                    HelpBullet(symbol: "qrcode", text: Text("Tap Pair in the bridge app to show a pairing code."))
                }
                .card()
                Button("I've installed it") {
                    Haptics.tap()
                    step = .scan
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            .padding(20)
        }
    }

    // MARK: - Scan

    private var scan: some View {
        ScrollView {
            VStack(spacing: 20) {
                StepHeader(number: 2, of: 2, title: Text("Scan the pairing code"),
                           subtitle: Text("Open Pairline Bridge on the Android phone, tap Pair and point this iPhone at the code."))
                if !purchases.access.allowsLink {
                    VStack(spacing: 12) {
                        Image(systemName: "lock.fill").font(.largeTitle).foregroundStyle(Color.brandAmber)
                        Text("Start your free trial to pair").font(.headline)
                        Text("Pairing and the link need an active trial or a purchase.")
                            .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        Button("See options") { showPaywall = true }
                            .buttonStyle(PrimaryButtonStyle())
                    }
                    .card(padding: 20)
                } else {
                    scanner
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
                        Text("Looking for a Pairline code…")
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
                Button("Allow camera") {
                    AVCaptureDevice.requestAccess(for: .video) { _ in
                        DispatchQueue.main.async { checkCamera() }
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
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
            Text("Messages and calls from your Android phone's SIM now come to this iPhone.")
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Spacer()
            if Notifier.shared.authorization == .notDetermined {
                VStack(spacing: 10) {
                    Button("Allow notifications") {
                        Task {
                            await Notifier.shared.requestAuthorization()
                            finish()
                        }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    Text("So you hear about new messages, codes and calls.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Button("Not now") { finish() }
                        .font(.subheadline.weight(.semibold))
                }
            } else {
                Button("Done") { finish() }
                    .buttonStyle(PrimaryButtonStyle())
            }
        }
        .padding(24)
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
