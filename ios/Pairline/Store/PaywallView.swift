import SwiftUI

/// One-time purchase with a free 7-day trial (D-017, D-036). Before the trial starts it states the duration, what stops
/// working afterwards, and the price (App Review guideline 3.1.1). Where Cloud mode exists, the first purchase can add
/// it as Full Access + Cloud mode for less than the two apart (D-093); elsewhere the paywall is exactly as before.
struct PaywallView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var purchases: PurchaseManager
    var onFinished: (() -> Void)?
    /// The "Add Cloud mode" switch: buy Full Access + Cloud mode instead of Full Access alone.
    @State private var addCloud = false

    /// The first-purchase bundle, where Cloud mode exists in this build and every price loaded.
    private var cloudOffer: BundleOffer? { RemoteFeature.isAvailable ? purchases.bundleOffer : nil }
    private var buyingBundle: Bool { addCloud && cloudOffer != nil }
    /// The price of what the buy buttons buy now.
    private var chosenPrice: String { buyingBundle ? cloudOffer?.bundlePrice ?? purchases.priceText : purchases.priceText }

    private func buyChosen() async -> Bool {
        buyingBundle ? await purchases.buyBundle() : await purchases.buyFull()
    }

    var body: some View {
        NavigationStack {
            // Full screen on a large display (D-063): what Pairline does in one pane, the price, the offer and its
            // terms in the other (side by side, or above each other on a tall screen), so no button sits on iPhone
            // Duo's fold and neither pane is left half empty. One column on a phone.
            FoldAwarePanes {
                CenteredScroll {
                    VStack(spacing: 22) {
                        hero
                        features
                    }
                    .frame(maxWidth: 560)
                    .padding(24)
                }
            } second: {
                CenteredScroll {
                    VStack(spacing: 18) {
                        if !purchases.access.isPurchased {
                            priceCard
                            if let cloudOffer { cloudChoice(cloudOffer) }
                        }
                        offer
                        footer
                            .padding(.top, 8)
                    }
                    .frame(maxWidth: 480)
                    .padding(24)
                }
            } single: {
                singleColumn
                    .frame(maxWidth: 600)
                    .frame(maxWidth: .infinity)
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
                        Label {
                            Text("Close")
                        } icon: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.title2)
                                .symbolRenderingMode(.hierarchical)
                                .foregroundStyle(.secondary)
                        }
                    }
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

    private var singleColumn: some View {
        ScrollView {
            VStack(spacing: 22) {
                hero
                if !purchases.access.isPurchased {
                    priceCard
                    if let cloudOffer { cloudChoice(cloudOffer) }
                }
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
    }

    private var hero: some View {
        VStack(spacing: 10) {
            Image("BrandMark")
                .resizable()
                .frame(width: 72, height: 72)
                .clipShape(RoundedRectangle(cornerRadius: 17, style: .continuous))
                .shadow(color: Color.brandBlue.opacity(0.35), radius: 16, y: 8)
            Text("Pairline Full Access")
                .font(.rounded(.title, weight: .bold))
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
                    // The plain secondary label, not `.secondary`: on the material behind the offer that becomes a
                    // vibrant gray that Increase Contrast barely darkens.
                    .foregroundStyle(Color(.secondaryLabel))
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
                    busyLabel(Text("Start 7-day free trial"))
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(purchases.busy)
                Button {
                    Task {
                        if await buyChosen() { finish() }
                    }
                } label: {
                    Text("Buy now for \(chosenPrice)")
                }
                .buttonStyle(SecondaryButtonStyle())
                .disabled(purchases.busy)
                Text("Free for 7 days, then \(purchases.priceText) once to keep using Pairline. If you don't buy, sending messages, calls, syncing and USSD stop after the trial; your synced messages stay readable. Nothing is charged automatically.")
                    .font(.caption)
                    .foregroundStyle(Color(.secondaryLabel))
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
                    .foregroundStyle(Color(.secondaryLabel))
                    .multilineTextAlignment(.center)
            }
        }
    }

    private var buyButton: some View {
        Button {
            Task {
                if await buyChosen() { finish() }
            }
        } label: {
            busyLabel(buyingBundle
                      ? Text("Buy both for \(chosenPrice)", tableName: "RemoteAddOn")
                      : Text("Buy Pairline for \(purchases.priceText)"))
        }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(purchases.busy)
    }

    /// "Add Cloud mode" (D-093): the first purchase can include it for less than buying it later. The saving comes
    /// from the three products' prices in this storefront; it isn't shown when the bundle doesn't cost less.
    private func cloudChoice(_ offer: BundleOffer) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            // The switch sits on the title's row; the badge goes beside the title when it fits, under it otherwise, so
            // the title never breaks onto two lines on a narrow iPhone.
            Toggle(isOn: $addCloud) {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) {
                        cloudTitle
                        savingBadge(offer)
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        cloudTitle
                        savingBadge(offer)
                    }
                }
            }
            .tint(Color.brandGreenFill)
            Text("Texts, codes and calls over the internet when your phones are apart. Both for \(offer.bundlePrice) instead of \(offer.separatePrice); added later, Cloud mode alone costs \(offer.remotePrice).",
                 tableName: "RemoteAddOn")
                .font(.footnote)
                .foregroundStyle(Color(.secondaryLabel))
                .fixedSize(horizontal: false, vertical: true)
        }
        .disabled(purchases.busy)
        .card(padding: 18)
    }

    private var cloudTitle: some View {
        Label {
            Text("Add Cloud mode", tableName: "RemoteAddOn")
        } icon: {
            Image(systemName: "icloud.fill").foregroundStyle(Color.brandBlue)
        }
        .font(.headline)
        .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder
    private func savingBadge(_ offer: BundleOffer) -> some View {
        if let saving = offer.savingPercent {
            Text("Save \(saving)%", tableName: "RemoteAddOn")
                .font(.caption.weight(.bold))
                .foregroundStyle(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(Capsule().fill(Color.brandGreenFill))
                .fixedSize()
        }
    }

    private func busyLabel(_ text: Text) -> some View {
        ZStack {
            text.multilineTextAlignment(.center).opacity(purchases.busy ? 0 : 1)
            if purchases.busy { ProgressView().tint(.white) }
        }
    }

    /// The heading and the price share the top row; what's included runs full width under them, so a long price (₺999,99)
    /// doesn't squeeze the text into a narrow column.
    private var priceCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            AdaptiveStack(verticalAlignment: .firstTextBaseline, spacing: 12) {
                Text("One-time purchase").font(.headline)
                Spacer(minLength: 0)
                Text(verbatim: chosenPrice)
                    .font(.rounded(.title, weight: .bold))
                    .foregroundStyle(Color.brandBlue)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            if buyingBundle {
                Text("Pairline Full Access and Cloud mode", tableName: "RemoteAddOn")
                    .font(.subheadline.weight(.medium))
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text("No subscription, no account.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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
