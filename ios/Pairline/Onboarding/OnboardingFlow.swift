import SwiftUI

/// First launch: what Pairline is, how the two phones work together, the privacy promise, then a choice between
/// the free trial, buying, or exploring the demo, followed by guided pairing.
struct OnboardingFlow: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var purchases: PurchaseManager
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var page = OnboardingFlow.initialPage
    @State private var showPairing = false
    @State private var pairingAsSheet = false
    private let pageCount = 4

    private static var initialPage: Int {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let index = args.firstIndex(of: "-PairlineOnboardingPage"), index + 1 < args.count {
            return Int(args[index + 1]) ?? 0
        }
        #endif
        return 0
    }

    var body: some View {
        ZStack {
            background.ignoresSafeArea()
            // On a large display held wide (iPhone Duo open, iPad in landscape) each page's artwork gets one pane and
            // its words and buttons the other, so no button lies across the fold (D-063).
            FoldAwarePanes(axes: .horizontal) {
                OnboardingHero(page: page)
                    .animation(.easeInOut, value: page)
            } second: {
                VStack(spacing: 0) {
                    pager(showsHero: false)
                    bottomBar
                }
            } single: {
                VStack(spacing: 0) {
                    pager(showsHero: true)
                    bottomBar
                }
                // A phone-sized column on a large display held upright, rather than stretched buttons.
                .frame(maxWidth: 600)
                .frame(maxWidth: .infinity)
            }
        }
        .fullScreenCover(isPresented: Binding(get: { showPairing && !pairingAsSheet }, set: { showPairing = $0 })) {
            pairing
        }
        // On a large display a sheet, which keeps itself off iPhone Duo's fold. Chosen when pairing starts, so the
        // flow isn't dismissed and restarted if the device opens or closes meanwhile.
        .sheet(isPresented: Binding(get: { showPairing && pairingAsSheet }, set: { showPairing = $0 })) {
            pairing
        }
    }

    private var pairing: some View {
        PairingFlowView(isOnboarding: true)
            .environmentObject(app)
            .environmentObject(purchases)
            .environmentObject(app.settings)
    }

    private func pager(showsHero: Bool) -> some View {
        TabView(selection: $page) {
            WelcomePage(showsHero: showsHero).tag(0)
            HowItWorksPage(showsHero: showsHero).tag(1)
            PrivacyPage(showsHero: showsHero).tag(2)
            ChoicePage(onPair: {
                pairingAsSheet = sizeClass == .regular
                showPairing = true
            }).tag(3)
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .animation(.easeInOut, value: page)
    }

    @ViewBuilder private var bottomBar: some View {
        if page < pageCount - 1 {
            VStack(spacing: 18) {
                PageDots(count: pageCount, current: page)
                Button {
                    Haptics.tap()
                    withAnimation { page += 1 }
                } label: {
                    Text(page == 0 ? "Get started" : "Continue")
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 20)
        }
    }

    private var background: some View {
        LinearGradient(colors: [Color.brandBlue.opacity(0.18), Color(.systemBackground), Color(.systemBackground)],
                       startPoint: .top, endPoint: .bottom)
    }
}

private struct PageDots: View {
    let count: Int
    let current: Int

    var body: some View {
        HStack(spacing: 8) {
            ForEach(0..<count, id: \.self) { index in
                Capsule()
                    .fill(index == current ? Color.brandBlue : Color.secondary.opacity(0.3))
                    .frame(width: index == current ? 22 : 8, height: 8)
                    .animation(.spring(response: 0.35), value: current)
            }
        }
        .accessibilityElement()
        .accessibilityLabel(Text("Page \(current + 1) of \(count)"))
    }
}

/// Each page's artwork, in the other pane when the onboarding is laid out in two (D-063).
private struct OnboardingHero: View {
    let page: Int

    var body: some View {
        Group {
            switch page {
            case 1:
                TwoPhonesIllustration(state: .connected, height: 220)
                    .padding(.horizontal, 36)
            case 2:
                PrivacyShield(scale: 1.4)
            default:
                BrandMarkImage(size: 160)
            }
        }
        .id(page)
        .transition(.opacity)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityHidden(true)
    }
}

private struct BrandMarkImage: View {
    let size: CGFloat

    var body: some View {
        Image("BrandMark")
            .resizable()
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: size * 28 / 120, style: .continuous))
            .shadow(color: Color.brandBlue.opacity(0.35), radius: 24, y: 12)
    }
}

private struct PrivacyShield: View {
    var scale: CGFloat = 1

    var body: some View {
        ZStack {
            Circle().fill(Color.brandGreen.opacity(0.14)).frame(width: 150 * scale, height: 150 * scale)
            Circle().fill(Color.brandGreen.opacity(0.14)).frame(width: 110 * scale, height: 110 * scale)
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 56 * scale))
                .foregroundStyle(Color.brandGreen)
        }
    }
}

private struct WelcomePage: View {
    var showsHero = true
    @State private var appeared = false

    var body: some View {
        VStack(spacing: 22) {
            Spacer()
            if showsHero {
                BrandMarkImage(size: 120)
                    .scaleEffect(appeared ? 1 : 0.85)
                    .opacity(appeared ? 1 : 0)
            }
            VStack(spacing: 10) {
                Text(verbatim: "Pairline")
                    .font(.system(size: 44, weight: .bold, design: .rounded))
                Text("Your Android SIM, on your iPhone.")
                    .font(.title3.weight(.semibold))
                    .multilineTextAlignment(.center)
                Text("Get messages, codes and calls from your Android phone's SIM right here, on the iPhone you carry.")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 12)
            }
            .opacity(appeared ? 1 : 0)
            .offset(y: appeared ? 0 : 12)
            Spacer()
            Spacer()
        }
        .padding(.horizontal, 24)
        .onAppear {
            withAnimation(.spring(response: 0.7, dampingFraction: 0.8).delay(0.1)) { appeared = true }
        }
    }
}

/// Centers a page's content vertically and scrolls it when it doesn't fit (small iPhones, large Dynamic Type,
/// Persian's taller lines), instead of letting the stack squeeze and truncate the text.
private struct CenteredPageScroll<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        GeometryReader { proxy in
            ScrollView(showsIndicators: false) {
                content
                    .padding(.vertical, 16)
                    .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
        }
    }
}

private struct HowItWorksPage: View {
    var showsHero = true

    var body: some View {
        CenteredPageScroll {
            howItWorks
        }
    }

    private var howItWorks: some View {
        VStack(spacing: 26) {
            if showsHero {
                TwoPhonesIllustration(state: .connected, height: 170)
                    .padding(.horizontal, 36)
            }
            VStack(spacing: 8) {
                Text("Two phones, one line")
                    .font(.rounded(.title, weight: .bold))
                Text("Your Android phone keeps the SIM. Your iPhone shows the messages and calls.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            VStack(alignment: .leading, spacing: 16) {
                OnboardingPoint(symbol: "simcard.fill", tint: .brandGreen,
                                title: Text("The bridge"),
                                text: Text("The free Pairline Bridge app runs on the Android phone with your SIM."))
                OnboardingPoint(symbol: "dot.radiowaves.left.and.right", tint: .brandBlue,
                                title: Text("A private link"),
                                text: Text("The phones connect directly over Bluetooth, encrypted end to end."))
                OnboardingPoint(symbol: "iphone", tint: .brandAmber,
                                title: Text("Your iPhone"),
                                text: Text("Messages, calls, contacts and USSD codes appear here, with notifications."))
                // Optional call audio on the iPhone (M6, D-047); its strings live in CallAudio.xcstrings.
                OnboardingPoint(symbol: "phone.and.waveform.fill", tint: .brandGreen,
                                title: Text("Talk on your iPhone", tableName: "CallAudio"),
                                text: Text("Optional: set up “Talk on iPhone” in the bridge app to hear and answer calls with this iPhone. It uses Wireless debugging on the Android phone (no extra app) and the same Wi-Fi.", tableName: "CallAudio"))
            }
        }
        .padding(.horizontal, 28)
    }
}

private struct PrivacyPage: View {
    var showsHero = true

    var body: some View {
        CenteredPageScroll {
            privacy
        }
    }

    private var privacy: some View {
        VStack(spacing: 26) {
            if showsHero {
                PrivacyShield()
            }
            VStack(spacing: 8) {
                Text("Nothing leaves your two phones")
                    .font(.rounded(.title, weight: .bold))
                    .multilineTextAlignment(.center)
                Text("Pairline is built so your messages and calls stay yours.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            VStack(alignment: .leading, spacing: 16) {
                OnboardingPoint(symbol: "server.rack", tint: .brandBlue, title: Text("No servers, no accounts"),
                                text: Text("There's no Pairline cloud. You never sign up."))
                OnboardingPoint(symbol: "eye.slash.fill", tint: .brandGreen, title: Text("No tracking"),
                                text: Text("No analytics, ads or crash reporters. Ever."))
                OnboardingPoint(symbol: "chevron.left.forwardslash.chevron.right", tint: .brandAmber,
                                title: Text("Open for inspection"),
                                text: Text("The source code is public, so anyone can check these promises."))
            }
        }
        .padding(.horizontal, 28)
    }
}

private struct ChoicePage: View {
    let onPair: () -> Void
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var purchases: PurchaseManager

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                Spacer(minLength: 24)
                Text("How would you like to start?")
                    .font(.rounded(.title, weight: .bold))
                    .multilineTextAlignment(.center)
                switch purchases.access {
                case .purchased:
                    OptionCard(symbol: "checkmark.seal.fill", tint: .brandGreen, title: Text("You own Pairline"),
                               text: Text("Next, pair your Android phone.")) {
                        onPair()
                    }
                case .trial:
                    OptionCard(symbol: "hourglass", tint: .brandGreen, title: Text("Your free trial is on"),
                               text: Text("Next, pair your Android phone.")) {
                        onPair()
                    }
                default:
                    // No prices on the first screen (D-069): the free week and the purchase are offered in the pairing
                    // flow, right before scanning, with the trial's terms.
                    OptionCard(symbol: "iphone.gen2.radiowaves.left.and.right", tint: .brandFill,
                               title: Text("Pair your Android phone"),
                               text: Text("Install the free bridge app on it, then scan one code.")) {
                        onPair()
                    }
                }
                OptionCard(symbol: "sparkles", tint: .brandAmber, title: Text("Explore the demo"),
                           text: Text("See Pairline with sample data. No Android phone needed, free.")) {
                    app.enterDemo()
                }
                if purchases.busy {
                    ProgressView().padding(.top, 4)
                }
                HStack(spacing: 16) {
                    Button("Restore Purchases") { Task { await purchases.restore() } }
                    Link(destination: AppInfo.privacyURL) { Text("Privacy") }
                    Link(destination: AppInfo.termsURL) { Text("Terms") }
                }
                .font(.footnote.weight(.semibold))
                Spacer(minLength: 24)
            }
            .padding(.horizontal, 24)
        }
        .alert(Text("Pairline"), isPresented: Binding(get: { purchases.message != nil },
                                                      set: { if !$0 { purchases.message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(purchases.message ?? "")
        }
    }
}

/// A tappable choice card: symbol, title and one line of text. Used on the start screen and in the pairing flow.
struct OptionCard: View {
    let symbol: String
    let tint: Color
    let title: Text
    let text: Text
    let action: () -> Void

    var body: some View {
        Button {
            Haptics.tap()
            action()
        } label: {
            HStack(spacing: 14) {
                Image(systemName: symbol)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 46, height: 46)
                    .background(RoundedRectangle(cornerRadius: 13, style: .continuous).fill(tint))
                VStack(alignment: .leading, spacing: 3) {
                    title.font(.headline).foregroundStyle(.primary)
                    text.font(.subheadline).foregroundStyle(.secondary)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(.tertiary)
            }
            .card()
        }
        .buttonStyle(.plain)
    }
}

struct OnboardingPoint: View {
    let symbol: String
    let tint: Color
    let title: Text
    let text: Text

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 40, height: 40)
                .background(Circle().fill(tint.opacity(0.14)))
            VStack(alignment: .leading, spacing: 2) {
                title.font(.headline)
                text.font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
