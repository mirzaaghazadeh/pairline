import SwiftUI

/// "Pair a phone that's far away" (D-080; PROTOCOL.md, "Remote pairing"): a short explanation, then the invitation to
/// send, the code the two people compare on a call, and the confirmation. Only where `RemoteFeature.isAvailable`.
struct RemotePairingView: View {
    let onPaired: () -> Void
    @EnvironmentObject private var app: AppModel
    @State private var pairing: RemotePairing? = Self.debugPreview

    /// Debug builds launched with `-PairlineRemotePairingPreview <step>` show that step (screenshots, RTL checks).
    private static var debugPreview: RemotePairing? {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let index = args.firstIndex(of: "-PairlineRemotePairingPreview"), index + 1 < args.count {
            return RemotePairing.preview(args[index + 1])
        }
        #endif
        return nil
    }

    var body: some View {
        if let pairing {
            RemotePairingSteps(pairing: pairing, onPaired: onPaired, onNew: create, onBack: { self.pairing = nil })
                .id(ObjectIdentifier(pairing))
        } else {
            intro
        }
    }

    private var intro: some View {
        ScrollView {
            VStack(spacing: 18) {
                Image(systemName: "icloud.and.arrow.up")
                    .font(.largeTitle)
                    .foregroundStyle(Color.brandBlue)
                RemotePairingHeader(title: Text("Pair a phone that's far away"),
                                    subtitle: Text("For when the Android phone with your SIM is somewhere else, like at home, and someone there can help."))
                VStack(alignment: .leading, spacing: 12) {
                    HelpBullet(symbol: "arrow.down.app", text: Text("Ask them to install Pairline Bridge on the Android phone from getpairline.com."))
                    HelpBullet(symbol: "square.and.arrow.up", text: Text("Send them the invitation from the next screen. They open it on the Android phone and allow the pairing."))
                    HelpBullet(symbol: "phone", text: Text("Call them: you'll both see a 6-digit code. Pair only if the codes match."))
                }
                .card(padding: 14)
                Label("The invitation works once, for 10 minutes. Send it only to the person helping you.",
                      systemImage: "lock.shield")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding(20)
        }
        .scrollsOnlyWhenNeeded()
        .safeAreaInset(edge: .bottom) {
            Button("Create invitation") {
                Haptics.tap()
                create()
            }
            .buttonStyle(PrimaryButtonStyle())
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 6)
            .background(.regularMaterial)
        }
    }

    private func create() {
        let new = app.makeRemotePairing()
        pairing = new
        Task { await new.start() }
    }
}

private struct RemotePairingSteps: View {
    @ObservedObject var pairing: RemotePairing
    let onPaired: () -> Void
    let onNew: () -> Void
    let onBack: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                content
            }
            .padding(20)
        }
        .task {
            // The bridge's records arrive in the alert mailbox; look every 3 s while this screen is open.
            while !Task.isCancelled, pairing.isActive {
                try? await Task.sleep(nanoseconds: UInt64(RemotePairing.pollInterval * 1_000_000_000))
                await pairing.poll()
            }
        }
        .onValueChange(pairing.phase) { phase in
            if phase == .paired {
                Haptics.success()
                onPaired()
            }
        }
        .onDisappear { pairing.cancel() }
    }

    @ViewBuilder private var content: some View {
        switch pairing.phase {
        case .creating:
            waiting(Text("Creating the invitation…"))
        case .inviting(let uri, let expires, let answered):
            invitation(uri: uri, expires: expires, answered: answered)
        case .comparing(let code):
            compare(code)
        case .confirming:
            waiting(Text("Waiting for the Android phone to confirm…"))
            Button("Cancel") {
                pairing.cancel()
                onBack()
            }
            .buttonStyle(SecondaryButtonStyle())
        case .failed(let failure):
            failed(failure)
        case .paired, .cancelled:
            EmptyView()
        }
    }

    private func waiting(_ text: Text) -> some View {
        VStack(spacing: 16) {
            ProgressView()
            text
                .font(.headline)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }

    // MARK: - Invitation

    /// The link as a share button and a QR code (the same invitation), with the time left.
    private func invitation(uri: String, expires: Date, answered: Bool) -> some View {
        let start = Date()
        return VStack(spacing: 18) {
            RemotePairingHeader(title: Text("Send this invitation"),
                                subtitle: Text("Share it with the person helping you. It opens in Pairline Bridge on the Android phone; the QR code holds the same invitation."))
            VStack(spacing: 14) {
                // The invitation holds a secret and an iCloud token: not read out by VoiceOver.
                QRCodeImage(text: uri)
                    .frame(width: 200, height: 200)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(.white))
                    .accessibilityHidden(true)
                ShareLink(item: uri) {
                    Label("Share invitation", systemImage: "square.and.arrow.up")
                }
                .buttonStyle(PrimaryButtonStyle())
                Text("Expires in \(Text(timerInterval: start...max(start, expires), countsDown: true))")
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
            .card(padding: 16)
            HStack(spacing: 10) {
                ProgressView()
                Text(answered ? "The Android phone answered. Getting the code…" : "Waiting for the Android phone…")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Code

    private func compare(_ code: String) -> some View {
        VStack(spacing: 18) {
            RemotePairingHeader(title: Text("Compare the codes"),
                                subtitle: Text("Read this code to the person holding the Android phone on a call. Their phone shows a code too."))
            // Digits only, always left to right, as "123 456".
            Text(verbatim: "\(code.prefix(3)) \(code.suffix(3))")
                .font(.system(size: 56, weight: .bold, design: .rounded).monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .environment(\.layoutDirection, .leftToRight)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
                .card()
                .accessibilityLabel(Text(verbatim: code.map(String.init).joined(separator: " ")))
            Label("If the codes are different, cancel: someone else may have the invitation.",
                  systemImage: "exclamationmark.shield")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Button("The codes match") {
                Haptics.tap()
                Task { await pairing.confirm() }
            }
            .buttonStyle(PrimaryButtonStyle())
            Button("Cancel") {
                pairing.cancel()
                onBack()
            }
            .buttonStyle(SecondaryButtonStyle())
        }
    }

    // MARK: - Failure

    private func failed(_ failure: RemotePairing.Failure) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.largeTitle)
                .foregroundStyle(Color.brandAmber)
            Text(failureText(failure))
                .multilineTextAlignment(.center)
            Button("New invitation") {
                Haptics.tap()
                onNew()
            }
            .buttonStyle(PrimaryButtonStyle())
        }
        .frame(maxWidth: .infinity)
        .card(padding: 20)
    }

    private func failureText(_ failure: RemotePairing.Failure) -> String {
        switch failure {
        case .iCloudUnavailable:
            return String(localized: "Sign in to iCloud on this iPhone to pair a phone that's far away.")
        case .offline:
            return RemoteError.offline.errorDescription ?? ""
        case .failed:
            return RemoteError.failed.errorDescription ?? ""
        case .expired:
            return String(localized: "The invitation expired before the Android phone answered. Create a new one.")
        case .someoneElse:
            return String(localized: "Someone else opened this invitation too, so pairing was stopped to keep you safe. Create a new one and send it only to the person helping you.")
        case .notVerified:
            return String(localized: "The Android phone's answer didn't check out, so pairing was stopped to keep you safe. Try again with a new invitation.")
        case .timedOut:
            return String(localized: "The Android phone didn't confirm in time. Try again with a new invitation.")
        }
    }
}

private struct RemotePairingHeader: View {
    let title: Text
    let subtitle: Text

    var body: some View {
        VStack(spacing: 8) {
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
