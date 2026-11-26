import ActivityKit
import SwiftUI
import WidgetKit

/// The bridge-status Live Activity (D-053): the bridge SIM's signal and network type in the Dynamic Island, like the
/// iPhone's own status bar, and the bridge's name, link state, battery and SIMs on the Lock Screen. Pairline starts
/// and updates it (`BridgeLiveActivity` in the app); tapping it opens the Bridge tab.
@available(iOSApplicationExtension 16.2, *)
struct BridgeLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: BridgeActivityAttributes.self) { context in
            ActivityLockScreenView(state: context.state, isDemo: context.attributes.isDemo)
                .widgetURL(statusURL)
        } dynamicIsland: { context in
            let state = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    ActivitySIMView(sim: state.primarySIM, connected: state.link.reachesBridge, large: true)
                        .padding(.leading, 6)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    ActivityBatteryView(level: state.batteryLevel, charging: state.charging)
                        .padding(.trailing, 6)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    ActivityBottomRow(state: state, isDemo: context.attributes.isDemo)
                        .padding(.horizontal, 6)
                }
            } compactLeading: {
                CompactSignalView(state: state)
            } compactTrailing: {
                CompactTrailingView(state: state)
            } minimal: {
                CompactSignalView(state: state)
            }
            .widgetURL(statusURL)
            .keylineTint(activityGreen)
        }
    }
}

private let statusURL = URL(string: "pairline://status")
private let activityGreen = Color(red: 0x12 / 255, green: 0xB8 / 255, blue: 0x86 / 255)
private let activityAmber = Color(red: 0xF5 / 255, green: 0x9F / 255, blue: 0x00 / 255)
private let activityRed = Color(red: 0xE0 / 255, green: 0x31 / 255, blue: 0x31 / 255)

private func linkColor(_ link: WidgetSnapshot.Link) -> Color {
    switch link {
    case .connected, .direct, .remote: return activityGreen
    case .connecting, .locked: return activityAmber
    case .offline, .notPaired: return .secondary
    }
}

private func linkText(_ link: WidgetSnapshot.Link) -> Text {
    switch link {
    case .connected: return Text("Connected")
    case .direct: return Text("Direct connection")
    case .remote: return Text("Via iCloud")
    case .connecting: return Text("Connecting…")
    case .offline: return Text("Not connected")
    case .locked: return Text("Locked")
    case .notPaired: return Text("Not paired")
    }
}

private func carrierName(_ sim: WidgetSnapshot.SIM) -> String {
    sim.carrier ?? "SIM \(sim.slot + 1)"
}

// MARK: - Dynamic Island

/// Signal bars of the first SIM, or a crossed-out antenna while the bridge isn't connected.
@available(iOSApplicationExtension 16.2, *)
private struct CompactSignalView: View {
    let state: BridgeActivityAttributes.ContentState

    var body: some View {
        if state.link.reachesBridge, let sim = state.primarySIM {
            SignalBarsView(level: sim.signal ?? 0)
                .frame(width: 17, height: 11)
        } else {
            Image(systemName: "antenna.radiowaves.left.and.right.slash")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
        }
    }
}

/// The first SIM's network type ("5G", "LTE"), the battery when there's none, or the link state's dot.
@available(iOSApplicationExtension 16.2, *)
private struct CompactTrailingView: View {
    let state: BridgeActivityAttributes.ContentState

    var body: some View {
        if !state.link.reachesBridge {
            Circle().fill(linkColor(state.link)).frame(width: 8, height: 8)
        } else if let net = state.primarySIM?.net, !net.isEmpty {
            Text(verbatim: net).font(.caption.weight(.semibold)).lineLimit(1)
        } else if let level = state.batteryLevel {
            Text(verbatim: level.formatted(.percent.scale(1))).font(.caption.weight(.semibold))
        } else {
            Circle().fill(linkColor(state.link)).frame(width: 8, height: 8)
        }
    }
}

@available(iOSApplicationExtension 16.2, *)
private struct ActivityBottomRow: View {
    let state: BridgeActivityAttributes.ContentState
    let isDemo: Bool

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(linkColor(state.link)).frame(width: 7, height: 7)
            nameText.font(.caption.weight(.semibold)).lineLimit(1)
            linkText(state.link).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            if isDemo { DemoBadge() }
            Spacer(minLength: 4)
            if state.sims.count > 1 {
                ActivitySIMView(sim: state.sims[1], connected: state.link.reachesBridge, large: false)
            }
        }
    }

    private var nameText: Text {
        if let name = state.name { return Text(verbatim: name) }
        return Text("Bridge phone")
    }
}

// MARK: - Lock Screen

@available(iOSApplicationExtension 16.2, *)
private struct ActivityLockScreenView: View {
    let state: BridgeActivityAttributes.ContentState
    let isDemo: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "iphone.gen2.radiowaves.left.and.right")
                    .font(.headline)
                    .foregroundStyle(activityGreen)
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 6) {
                        nameText.font(.headline).lineLimit(1)
                        if isDemo { DemoBadge() }
                    }
                    HStack(spacing: 5) {
                        Circle().fill(linkColor(state.link)).frame(width: 7, height: 7)
                        linkText(state.link).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 8)
                ActivityBatteryView(level: state.batteryLevel, charging: state.charging)
            }
            if !state.sims.isEmpty {
                HStack(spacing: 16) {
                    ForEach(state.sims) { sim in
                        ActivitySIMView(sim: sim, connected: state.link.reachesBridge, large: true)
                    }
                    Spacer(minLength: 0)
                }
            }
        }
        .padding(16)
    }

    private var nameText: Text {
        if let name = state.name { return Text(verbatim: name) }
        return Text("Bridge phone")
    }
}

// MARK: - Pieces

/// One SIM: signal bars, network type and carrier. Bars show empty while the bridge isn't connected (the last
/// values may be old).
private struct ActivitySIMView: View {
    let sim: WidgetSnapshot.SIM?
    let connected: Bool
    let large: Bool

    var body: some View {
        if let sim {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    SignalBarsView(level: connected ? (sim.signal ?? 0) : 0)
                        .frame(width: large ? 20 : 15, height: large ? 12 : 9)
                    if let net = sim.net, !net.isEmpty {
                        Text(verbatim: net).font((large ? Font.subheadline : .caption).weight(.bold))
                    }
                }
                if large {
                    Text(verbatim: carrierName(sim)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            .opacity(connected ? 1 : 0.6)
        }
    }
}

private struct ActivityBatteryView: View {
    let level: Int?
    let charging: Bool?

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: symbol).foregroundStyle(color)
            Text(verbatim: level.map { $0.formatted(.percent.scale(1)) } ?? "–")
                .font(.rounded(.subheadline, weight: .semibold))
        }
    }

    private var symbol: String {
        guard let level else { return "battery.0" }
        if charging == true { return "battery.100.bolt" }
        switch level {
        case ..<13: return "battery.0"
        case ..<38: return "battery.25"
        case ..<63: return "battery.50"
        case ..<88: return "battery.75"
        default: return "battery.100"
        }
    }

    private var color: Color {
        guard let level else { return .secondary }
        if charging == true { return activityGreen }
        return level <= 15 ? activityRed : (level <= 30 ? activityAmber : .primary)
    }
}

private struct DemoBadge: View {
    var body: some View {
        Text("Demo")
            .font(.caption2.weight(.bold))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Capsule().fill(activityAmber.opacity(0.25)))
    }
}
