import SwiftUI

/// The bridge phone dashboard: link, battery, SIMs, app version and capabilities.
struct BridgeStatusTab: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var purchases: PurchaseManager
    @EnvironmentObject private var bridge: BridgeController

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    BridgeBanners().padding(.horizontal, -16)
                    hero
                    if bridge.phase.isConnected || bridge.status != nil {
                        if bridge.protocolMismatch || bridge.bridgeNeedsUpdate {
                            updateNotice
                        }
                        batteryCard
                        simCards
                        capabilitiesCard
                        quickActions
                    } else {
                        connectionHelp
                    }
                }
                .padding(16)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle(Text("Bridge"))
            .refreshable { await bridge.refreshStatus() }
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
            LinkStatusPill(phase: bridge.phase, isDemo: bridge.isDemo)
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

    private var transportSymbol: String {
        if DebugSettings.marketingScreenshots { return "dot.radiowaves.left.and.right" }
        switch bridge.link.transportKind {
        case .bluetooth: return "dot.radiowaves.left.and.right"
        case .tcp: return "cable.connector"
        case .demo: return "sparkles"
        }
    }

    private var transportName: Text {
        if DebugSettings.marketingScreenshots { return Text("Bluetooth") }
        switch bridge.link.transportKind {
        case .bluetooth: return Text("Bluetooth")
        case .tcp: return Text("Debug TCP")
        case .demo: return Text("Demo")
        }
    }

    // MARK: - Cards

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
                    Text(verbatim: "pairline.navid.tr")
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
                        HStack(spacing: 6) {
                            if sim.defaultSms == true { Tag(text: Text("Messages")) }
                            if sim.defaultVoice == true { Tag(text: Text("Calls")) }
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
