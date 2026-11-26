import SwiftUI

/// The bridge phone dashboard: link, battery, SIMs, app version and capabilities.
struct BridgeStatusTab: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var purchases: PurchaseManager
    @EnvironmentObject private var bridge: BridgeController

    private var hasStatus: Bool { bridge.phase.isConnected || bridge.status != nil }

    var body: some View {
        NavigationStack {
            // Two columns on a large display, one on each half of iPhone Duo (D-063): the phone, its battery and SIMs,
            // and what it can do. One column on a phone.
            FoldAwarePanes(axes: .horizontal) {
                column {
                    overview
                }
            } second: {
                column {
                    if hasStatus {
                        capabilitiesCard
                        quickActions
                    } else {
                        connectionHelp
                    }
                }
            } single: {
                column {
                    overview
                    if hasStatus {
                        capabilitiesCard
                        quickActions
                    } else {
                        connectionHelp
                    }
                }
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle(Text("Bridge"))
            .refreshable { await bridge.refreshStatus() }
            .onAppear(perform: readProxy)
            .onReceive(bridge.$netShare) { _ in readProxy() }
            .onValueChange(scenePhase) { if $0 == .active { readProxy() } }
        }
    }

    private func column<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                content()
            }
            .padding(16)
        }
    }

    /// The bridge phone itself: the link, then (once it has reported) notices, battery and SIM cards.
    @ViewBuilder private var overview: some View {
        BridgeBanners().padding(.horizontal, -16)
        hero
        if hasStatus {
            if bridge.protocolMismatch || bridge.bridgeNeedsUpdate {
                updateNotice
            }
            if wifiOnly { wifiOnlyNotice }
            if proxy == .leftOn || proxy == .outdated { proxyNotice }
            batteryCard
            simCards
        }
    }

    // MARK: - Hero

    private var illustrationState: TwoPhonesIllustration.State {
        switch bridge.phase {
        case .connected: return .connected
        case .searching, .securing, .pairing, .waiting: return .connecting
        default: return .idle
        }
    }

    private var hero: some View {
        VStack(spacing: 14) {
            TwoPhonesIllustration(state: illustrationState, height: 112)
                .padding(.horizontal, 28)
            LinkStatusPill(phase: bridge.phase, isDemo: bridge.isDemo, isDirect: bridge.isDirect)
            VStack(spacing: 4) {
                Text(verbatim: bridge.deviceInfo?.name ?? String(localized: "Bridge phone"))
                    .font(.title2.weight(.bold))
                HStack(spacing: 10) {
                    if bridge.phase.isConnected {
                        if let latency = bridge.latency {
                            Label {
                                Text("\(Int(latency * 1000)) ms")
                            } icon: {
                                Image(systemName: "bolt.horizontal.fill")
                            }
                        }
                        Label {
                            transportName
                        } icon: {
                            Image(systemName: transportSymbol)
                        }
                    } else if let updated = bridge.statusUpdatedAt {
                        Text("Last update \(updated, style: .relative) ago")
                    }
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 18)
        .card()
    }

    private var shownTransports: [TransportKind] {
        bridge.transports.isEmpty ? [bridge.link.transportKind] : bridge.transports
    }

    private var transportSymbol: String {
        if DebugSettings.marketingScreenshots { return "dot.radiowaves.left.and.right" }
        if shownTransports.contains(.wifi) { return "wifi" }
        switch shownTransports.first ?? .bluetooth {
        case .bluetooth: return "dot.radiowaves.left.and.right"
        case .tcp: return "cable.connector"
        case .wifi: return "wifi"
        case .direct: return "globe"
        case .demo: return "sparkles"
        }
    }

    /// "Bluetooth", "Wi-Fi" or "Bluetooth + Wi-Fi" (PROTOCOL.md: both sessions can be up at once).
    private var transportName: Text {
        if DebugSettings.marketingScreenshots { return Text("Bluetooth") }
        let kinds = shownTransports
        if kinds.contains(.bluetooth), kinds.contains(.wifi) { return Text("Bluetooth + Wi-Fi") }
        if kinds.contains(.tcp), kinds.contains(.wifi) { return Text(verbatim: "Debug TCP + Wi-Fi") }
        switch kinds.first ?? .bluetooth {
        case .bluetooth: return Text("Bluetooth")
        case .tcp: return Text("Debug TCP")
        case .wifi: return Text("Wi-Fi")
        // Remote mode's direct connection: the pill above says "Direct connection".
        case .direct: return Text("Internet")
        case .demo: return Text("Demo")
        }
    }

    // MARK: - Cards

    /// Linked over Wi-Fi alone: iOS keeps only Bluetooth links alive in the background, so calls and messages arrive
    /// only while Pairline is open or was just used (ARCHITECTURE.md, "The Wi-Fi link and iOS background").
    private var wifiOnly: Bool {
        bridge.phase.isConnected && !bridge.isDemo && !DebugSettings.marketingScreenshots && shownTransports == [.wifi]
    }

    /// The current Wi-Fi's proxy points at the bridge's internet sharing, but sharing is off (no internet at all on that
    /// network) or the address changed. Checked when the tab appears and when Pairline comes back to the front.
    @State private var proxy: WiFiProxyStatus = .none
    @Environment(\.scenePhase) private var scenePhase

    private func readProxy() {
        let share = bridge.netShare
        proxy = WiFiProxyStatus.current(sharingPAC: share?.on == true ? share?.pac : nil)
    }

    private var proxyNotice: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "wifi.exclamationmark")
                .font(.title2)
                .foregroundStyle(.red)
            VStack(alignment: .leading, spacing: 6) {
                Text("No internet on this Wi-Fi?", tableName: "NetShare").font(.headline)
                (proxy == .leftOn
                    ? Text("This Wi-Fi still points to the bridge's proxy, which is off, so this iPhone has no internet on it. Set Settings › Wi-Fi › ⓘ › Configure Proxy back to Off, or turn sharing on.", tableName: "NetShare")
                    : Text("This Wi-Fi's proxy points to an old address. Paste the URL below again.", tableName: "NetShare"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                NavigationLink {
                    NetShareView()
                } label: {
                    Text("Internet sharing", tableName: "NetShare").font(.subheadline.weight(.semibold))
                }
            }
        }
        .card()
    }

    private var wifiOnlyNotice: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "wifi.exclamationmark")
                .font(.title2)
                .foregroundStyle(Color.brandAmber)
            VStack(alignment: .leading, spacing: 6) {
                Text("Linked over Wi-Fi only", tableName: "Hotspot").font(.headline)
                Text("iOS pauses Wi-Fi links while Pairline is in the background, so calls and messages reach this iPhone only while Pairline is open. Over Bluetooth they keep coming while the iPhone is locked: keep Bluetooth on on both phones and the phones within about 10 meters.", tableName: "Hotspot")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .card()
    }

    private var updateNotice: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "arrow.down.app.fill")
                .font(.title2)
                .foregroundStyle(Color.brandAmber)
            VStack(alignment: .leading, spacing: 6) {
                Text("Update your bridge app").font(.headline)
                Text(bridge.protocolMismatch
                     ? "Your bridge app speaks a different link version. Update Pairline Bridge on your Android phone to keep everything working."
                     : "A newer Pairline Bridge is needed for everything to work. Install the latest version on your Android phone.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Link(destination: AppInfo.downloadURL) {
                    Text(verbatim: "getpairline.com")
                        .font(.subheadline.weight(.semibold))
                }
            }
        }
        .card()
    }

    private var batteryCard: some View {
        let level = bridge.status?.battery?.level
        let charging = bridge.status?.battery?.charging ?? false
        return HStack(spacing: 16) {
            ZStack {
                Circle().stroke(Color.secondary.opacity(0.15), lineWidth: 8)
                Circle()
                    .trim(from: 0, to: CGFloat(level ?? 0) / 100)
                    .stroke(BatteryIndicator(level: level, charging: charging).color,
                            style: StrokeStyle(lineWidth: 8, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(.easeOut(duration: 0.6), value: level)
                Image(systemName: charging ? "bolt.fill" : "iphone.gen2")
                    .font(.title3)
                    .foregroundStyle(BatteryIndicator(level: level, charging: charging).color)
            }
            .frame(width: 64, height: 64)
            VStack(alignment: .leading, spacing: 4) {
                Text("Battery").font(.subheadline).foregroundStyle(.secondary)
                Text(level.map { (Double($0) / 100).formatted(.percent.precision(.fractionLength(0))) } ?? "–")
                    .font(.rounded(.title, weight: .bold))
                    .monospacedDigit()
                Text(charging ? "Charging" : (level ?? 100) <= 20 ? "Charge soon to stay connected" : "On battery")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .card()
        .accessibilityElement(children: .combine)
    }

    private var simCards: some View {
        VStack(spacing: 10) {
            SectionTitle(Text("SIM cards"))
            if bridge.sims.isEmpty {
                Text("No SIM information yet.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .card()
            }
            ForEach(bridge.sims) { sim in
                HStack(spacing: 14) {
                    SIMBadge(slot: sim.slot).scaleEffect(1.5).frame(width: 28)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(verbatim: sim.carrier ?? sim.label ?? "SIM \(sim.slot + 1)")
                            .font(.headline)
                        HStack(spacing: 6) {
                            if let number = sim.number {
                                LTRText(number)
                            } else {
                                Text("Number hidden")
                            }
                        }
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        // The SIM this iPhone's calls and messages go out on (D-062), not only Android's default.
                        HStack(spacing: 6) {
                            if bridge.effectiveSim(forSMS: true) == sim.slot { Tag(text: Text("Messages")) }
                            if bridge.effectiveSim(forSMS: false) == sim.slot { Tag(text: Text("Calls")) }
                            if sim.roaming == true { Tag(text: Text("Roaming"), tint: .brandAmber) }
                        }
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 6) {
                        SignalBars(level: sim.signal ?? 0, tint: (sim.signal ?? 0) <= 1 ? .brandAmber : .brandGreen)
                        Text(verbatim: sim.net == "none" ? "—" : (sim.net ?? ""))
                            .font(.caption.weight(.bold))
                            .foregroundStyle(.secondary)
                    }
                }
                .card()
                .accessibilityElement(children: .combine)
            }
            if bridge.isDualSIM {
                NavigationLink {
                    List { SIMDefaultsSection() }
                        .navigationTitle(Text("SIM cards"))
                        .navigationBarTitleDisplayMode(.inline)
                } label: {
                    HStack {
                        Label { Text("Choose SIMs for calls and messages", tableName: "SIM") } icon: {
                            Image(systemName: "simcard.2")
                        }
                        .font(.subheadline.weight(.semibold))
                        Spacer()
                        Image(systemName: "chevron.forward")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .card()
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var capabilitiesCard: some View {
        VStack(spacing: 10) {
            SectionTitle(Text("What this bridge can do"))
            VStack(spacing: 0) {
                ForEach(Capability.allCases.map(CapabilityInfo.init)) { info in
                    let available = bridge.has(info.capability)
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: info.symbol)
                            .font(.body)
                            .foregroundStyle(available ? Color.brandBlue : .secondary)
                            .frame(width: 26)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(info.title).font(.subheadline.weight(.medium))
                            if !available {
                                Text(info.missingText)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        Spacer()
                        Image(systemName: available ? "checkmark.circle.fill" : "minus.circle")
                            .foregroundStyle(available ? Color.brandGreen : .secondary)
                            .accessibilityLabel(available ? Text("Available") : Text("Not available"))
                    }
                    .padding(.vertical, 10)
                    if info.capability != Capability.allCases.last {
                        Divider().padding(.leading, 38)
                    }
                }
            }
            .card(padding: 14)
        }
    }

    private var quickActions: some View {
        VStack(spacing: 10) {
            SectionTitle(Text("Tools"))
            VStack(spacing: 0) {
                NavigationLink {
                    USSDView()
                } label: {
                    ToolRow(symbol: "number.square.fill", tint: .brandFill, title: Text("USSD codes"),
                            subtitle: Text("Balance, packages and carrier services"))
                }
                Divider().padding(.leading, 52)
                if !bridge.isDemo {
                    NavigationLink {
                        FilesView(files: app.link.files)
                    } label: {
                        ToolRow(symbol: "doc.on.doc.fill", tint: .brandBlue, title: Text("Send files"),
                                subtitle: Text("Photos and documents over Wi-Fi, both ways"))
                    }
                    Divider().padding(.leading, 52)
                }
                NavigationLink {
                    HelpCenterView()
                } label: {
                    ToolRow(symbol: "lifepreserver.fill", tint: .brandGreen, title: Text("Keep the link alive"),
                            subtitle: Text("Tips for a connection that lasts all day"))
                }
            }
            .card(padding: 12)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder private var connectionHelp: some View {
        VStack(alignment: .leading, spacing: 14) {
            switch bridge.phase {
            case .notPaired:
                Text("No bridge phone yet").font(.headline)
                Text("Install Pairline Bridge on the Android phone that holds your SIM, then scan its pairing code.")
                    .font(.subheadline).foregroundStyle(.secondary)
                Button("Pair a bridge phone") { app.showPairing = true }
                    .buttonStyle(PrimaryButtonStyle())
                Button("Explore the demo") { app.enterDemo() }
                    .buttonStyle(SecondaryButtonStyle())
            case .locked:
                Text("Link paused").font(.headline)
                Text("Your free trial has ended, so this iPhone stopped connecting to the bridge phone. Your synced messages are still here.")
                    .font(.subheadline).foregroundStyle(.secondary)
                Button("Unlock Pairline") { app.showPaywall = true }
                    .buttonStyle(PrimaryButtonStyle())
            case .bluetoothOff:
                Text("Bluetooth is off").font(.headline)
                Text("Turn on Bluetooth in Control Center or Settings to reach your bridge phone.")
                    .font(.subheadline).foregroundStyle(.secondary)
            case .bluetoothUnauthorized:
                Text("Bluetooth isn't allowed").font(.headline)
                Text("Pairline needs Bluetooth to talk to your bridge phone. Allow it in Settings.")
                    .font(.subheadline).foregroundStyle(.secondary)
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
                .buttonStyle(PrimaryButtonStyle())
            default:
                Text("Looking for your bridge phone").font(.headline)
                VStack(alignment: .leading, spacing: 10) {
                    HelpBullet(symbol: "antenna.radiowaves.left.and.right", text: Text("Keep both phones within Bluetooth range, about 10 meters."))
                    HelpBullet(symbol: "wifi", text: Text("Or connect both phones to the same Wi-Fi network."))
                    HelpBullet(symbol: "iphone.gen2", text: Text("Make sure Pairline Bridge is running on the Android phone and Bluetooth is on there."))
                    HelpBullet(symbol: "battery.100", text: Text("Exclude Pairline Bridge from battery optimization on the Android phone."))
                }
                Button("Try now") {
                    bridge.link.reconnectNow()
                    Haptics.tap()
                }
                .buttonStyle(SecondaryButtonStyle())
            }
            NavigationLink {
                HelpCenterView()
            } label: {
                Label("Troubleshooting", systemImage: "questionmark.circle")
                    .font(.subheadline.weight(.semibold))
            }
        }
        .card()
    }
}

struct Tag: View {
    let text: Text
    var tint: Color = .brandBlue

    var body: some View {
        text
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Capsule().fill(tint.opacity(0.14)))
            .foregroundStyle(tint)
    }
}

struct ToolRow: View {
    let symbol: String
    let tint: Color
    let title: Text
    let subtitle: Text

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(.white)
                .frame(width: 38, height: 38)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(tint))
            VStack(alignment: .leading, spacing: 2) {
                title.font(.body.weight(.medium)).foregroundStyle(.primary)
                subtitle.font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.forward")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

struct HelpBullet: View {
    let symbol: String
    let text: Text

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: symbol)
                .foregroundStyle(Color.brandBlue)
                .frame(width: 22)
            text
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
