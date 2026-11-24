import SwiftUI

/// First launch: what Pairline is, how the two phones work together, the privacy promise, then a choice between
/// the free trial, buying, or exploring the demo, followed by guided pairing.
struct OnboardingFlow: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var purchases: PurchaseManager
    @State private var page = OnboardingFlow.initialPage
    @State private var showPairing = false
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
            VStack(spacing: 0) {
                TabView(selection: $page) {
                    WelcomePage().tag(0)
                    HowItWorksPage().tag(1)
                    PrivacyPage().tag(2)
                    ChoicePage(onPair: { showPairing = true }).tag(3)
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .animation(.easeInOut, value: page)

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
        }
        .fullScreenCover(isPresented: $showPairing) {
            PairingFlowView(isOnboarding: true)
                .environmentObject(app)
                .environmentObject(purchases)
                .environmentObject(app.settings)
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

private struct WelcomePage: View {
    @State private var appeared = false

    var body: some View {
        VStack(spacing: 22) {
            Spacer()
            Image("BrandMark")
                .resizable()
                .frame(width: 120, height: 120)
                .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                .shadow(color: Color.brandBlue.opacity(0.35), radius: 24, y: 12)
                .scaleEffect(appeared ? 1 : 0.85)
                .opacity(appeared ? 1 : 0)
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
    var body: some View {
        CenteredPageScroll {
            howItWorks
        }
    }

    private var howItWorks: some View {
        VStack(spacing: 26) {
            TwoPhonesIllustration(state: .connected, height: 170)
                .padding(.horizontal, 36)
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
                                text: Text("Optional: set up “Talk on iPhone” in the bridge app to hear and answer calls with this iPhone. It needs the free Shizuku app and the same Wi-Fi.", tableName: "CallAudio"))
            }
        }
        .padding(.horizontal, 28)
    }
}

private struct PrivacyPage: View {
    var body: some View {
        CenteredPageScroll {
            privacy
        }
    }

    private var privacy: some View {
        VStack(spacing: 26) {
            ZStack {
                Circle().fill(Color.brandGreen.opacity(0.14)).frame(width: 150, height: 150)
                Circle().fill(Color.brandGreen.opacity(0.14)).frame(width: 110, height: 110)
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 56))
                    .foregroundStyle(Color.brandGreen)
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
                    OptionCard(symbol: "gift.fill", tint: .brandFill, title: Text("Try free for 7 days"),
                               text: Text("Everything works. Then \(purchases.priceText) once to keep it, or it stops connecting.")) {
                        Task {
                            if await purchases.startTrial() { onPair() }
                        }
                    }
                    OptionCard(symbol: "bag.fill", tint: .brandGreen, title: Text("Buy once · \(purchases.priceText)"),
                               text: Text("No subscription. Yours for good.")) {
                        Task {
                            if await purchases.buyFull() { onPair() }
                        }
                    }
                }
                OptionCard(symbol: "sparkles", tint: .brandAmber, title: Text("Explore the demo"),
                           text: Text("See Pairline with sample data. No Android phone needed, free.")) {
                    app.enterDemo()
                }
                if purchases.busy {
                    ProgressView().padding(.top, 4)
                }
                Text("The 7-day trial is free. When it ends, sending messages, calls, syncing and USSD stop until you buy Pairline for \(purchases.priceText) once. Messages already synced stay readable. Nothing is charged automatically.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.top, 4)
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

private struct OptionCard: View {
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
