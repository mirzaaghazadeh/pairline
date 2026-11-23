import SwiftUI

/// One-time purchase with a free 3-day trial (D-017). Before the trial starts it states the duration, what stops
/// working afterwards, and the price (App Review guideline 3.1.1).
struct PaywallView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var purchases: PurchaseManager
    var onFinished: (() -> Void)?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 22) {
                    hero
                    if !purchases.access.isPurchased { priceCard }
                    features
                    footer
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 16)
            }
            .safeAreaInset(edge: .bottom) {
                // The offer stays visible without scrolling.
                offer
                    .padding(.horizontal, 20)
                    .padding(.top, 14)
                    .padding(.bottom, 6)
                    .background(.regularMaterial)
            }
            .background(
                LinearGradient(colors: [Color.brandBlue.opacity(0.16), Color(.systemBackground)], startPoint: .top,
                               endPoint: .center)
                    .ignoresSafeArea()
            )
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title2)
                            .symbolRenderingMode(.hierarchical)
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityLabel(Text("Close"))
                }
            }
            .alert(Text("Pairline"), isPresented: Binding(get: { purchases.message != nil },
                                                          set: { if !$0 { purchases.message = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(purchases.message ?? "")
            }
        }
        .onValueChange(purchases.access) { access in
            if access.isPurchased {
                Haptics.success()
            }
        }
        .task { await purchases.loadProducts() }
    }

    private var hero: some View {
        VStack(spacing: 10) {
            Image("BrandMark")
                .resizable()
                .frame(width: 72, height: 72)
                .clipShape(RoundedRectangle(cornerRadius: 17, style: .continuous))
                .shadow(color: Color.brandBlue.opacity(0.35), radius: 16, y: 8)
            Text("Pairline Full Access")
                .font(.system(.title, design: .rounded).weight(.bold))
                .multilineTextAlignment(.center)
            Text("Your Android SIM on your iPhone. Pay once, keep it for good.")
                .font(.headline.weight(.regular))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    private var features: some View {
        VStack(alignment: .leading, spacing: 14) {
            FeatureRow(symbol: "message.fill", tint: .brandFill, title: Text("Messages and one-time codes"),
                       text: Text("Read and send SMS, and copy codes straight from the notification."))
            FeatureRow(symbol: "phone.fill", tint: .brandGreen, title: Text("Calls on your iPhone"),
                       text: Text("Ring, answer, decline and dial with the iPhone call screen."))
            FeatureRow(symbol: "person.2.fill", tint: .brandAmber, title: Text("Contacts and call history"),
                       text: Text("Synced from the Android phone and kept on this iPhone only."))
            FeatureRow(symbol: "simcard.2.fill", tint: .brandFill, title: Text("Dual SIM, USSD and widget"),
                       text: Text("Pick a SIM, check your balance, see battery and signal at a glance."))
            FeatureRow(symbol: "lock.shield.fill", tint: .brandGreen, title: Text("Private by design"),
                       text: Text("End-to-end encrypted between your phones. No servers, no accounts, no tracking."))
        }
        .card(padding: 18)
    }

    @ViewBuilder private var offer: some View {
        switch purchases.access {
        case .purchased:
            VStack(spacing: 10) {
                Image(systemName: "checkmark.seal.fill").font(.system(size: 44)).foregroundStyle(Color.brandGreen)
                Text("You own Pairline").font(.title2.weight(.bold))
                Text("Thank you for supporting independent, private software.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Button("Done") { finish() }
                    .buttonStyle(PrimaryButtonStyle())
                    .padding(.top, 6)
            }
            .card(padding: 20)
        case .notStarted, .loading:
            VStack(spacing: 10) {
                Button {
                    Task {
                        if await purchases.startTrial() { finish() }
                    }
                } label: {
                    busyLabel(Text("Start 3-day free trial"))
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(purchases.busy)
                Button {
                    Task {
                        if await purchases.buyFull() { finish() }
                    }
                } label: {
                    Text("Buy now for \(purchases.priceText)")
                }
                .buttonStyle(SecondaryButtonStyle())
                .disabled(purchases.busy)
                Text("Free for 3 days, then \(purchases.priceText) once to keep using Pairline. If you don't buy, sending messages, calls, syncing and USSD stop after the trial; your synced messages stay readable. Nothing is charged automatically.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        case .trial(let end):
            VStack(spacing: 10) {
                Label {
                    TrialText.remaining(TrialPolicy.remaining(until: end, now: purchases.trustedNow))
                } icon: {
                    Image(systemName: "hourglass")
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.brandAmber)
                buyButton
            }
        case .expired:
            VStack(spacing: 10) {
                Label("Your free trial has ended", systemImage: "hourglass.bottomhalf.filled")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.brandAmber)
                buyButton
                Text("Your synced messages stay readable without buying. Demo mode and help always work.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
    }

    private var buyButton: some View {
        Button {
            Task {
                if await purchases.buyFull() { finish() }
            }
        } label: {
            busyLabel(Text("Buy Pairline for \(purchases.priceText)"))
        }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(purchases.busy)
    }

    private func busyLabel(_ text: Text) -> some View {
        ZStack {
            text.opacity(purchases.busy ? 0 : 1)
            if purchases.busy { ProgressView().tint(.white) }
        }
    }

    private var priceCard: some View {
        HStack(alignment: .center, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("One-time purchase").font(.headline)
                Text("No subscription, no account.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Text(verbatim: purchases.priceText)
                .font(.system(.title, design: .rounded).weight(.bold))
                .foregroundStyle(Color.brandBlue)
        }
        .card(padding: 18)
        .overlay(
            RoundedRectangle(cornerRadius: Metrics.corner, style: .continuous)
                .strokeBorder(Color.brandBlue, lineWidth: 2)
        )
    }

    private var footer: some View {
        VStack(spacing: 12) {
            Button("Restore Purchases") {
                Task { await purchases.restore() }
            }
            .font(.subheadline.weight(.semibold))
            .disabled(purchases.busy)
            HStack(spacing: 18) {
                Link(destination: AppInfo.privacyURL) { Text("Privacy Policy") }
                Link(destination: AppInfo.termsURL) { Text("Terms of Use") }
            }
            .font(.footnote)
            Text("The Android bridge app is free. Payment is handled by Apple.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    private func finish() {
        onFinished?()
        dismiss()
    }
}

private struct FeatureRow: View {
    let symbol: String
    let tint: Color
    let title: Text
    let text: Text

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 34, height: 34)
                .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(tint))
            VStack(alignment: .leading, spacing: 2) {
                title.font(.subheadline.weight(.semibold))
                text.font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
