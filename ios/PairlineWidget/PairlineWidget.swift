import SwiftUI
import WidgetKit

@main
struct PairlineWidgetBundle: WidgetBundle {
    var body: some Widget {
        BridgeStatusWidget()
    }
}

struct BridgeStatusEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot?
}

struct BridgeStatusProvider: TimelineProvider {
    func placeholder(in context: Context) -> BridgeStatusEntry {
        BridgeStatusEntry(date: Date(), snapshot: .placeholder)
    }

    func getSnapshot(in context: Context, completion: @escaping (BridgeStatusEntry) -> Void) {
        completion(BridgeStatusEntry(date: Date(), snapshot: context.isPreview ? .placeholder : WidgetSnapshot.load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<BridgeStatusEntry>) -> Void) {
        // The app reloads the timeline whenever the status changes; this refresh only ages the "updated" label.
        let entry = BridgeStatusEntry(date: Date(), snapshot: WidgetSnapshot.load())
        completion(Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(15 * 60))))
    }
}

struct BridgeStatusWidget: Widget {
    let kind = "BridgeStatus"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: BridgeStatusProvider()) { entry in
            BridgeStatusWidgetView(entry: entry)
        }
        .configurationDisplayName(Text("Bridge phone"))
        .description(Text("Battery, signal and link state of your bridge phone."))
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

private extension Color {
    static let brandBlue = Color("AccentColor")
    static let brandGreen = Color(red: 0x12 / 255, green: 0xB8 / 255, blue: 0x86 / 255)
    static let brandAmber = Color(red: 0xF5 / 255, green: 0x9F / 255, blue: 0x00 / 255)
    static let brandRed = Color(red: 0xE0 / 255, green: 0x31 / 255, blue: 0x31 / 255)
}

struct BridgeStatusWidgetView: View {
    let entry: BridgeStatusEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        content
            .widgetURL(URL(string: "pairline://status"))
            .modifier(WidgetBackground())
    }

    @ViewBuilder private var content: some View {
        switch family {
        case .accessoryCircular: circular
        case .accessoryRectangular: rectangular
        case .accessoryInline: inline
        case .systemMedium: medium
        default: small
        }
    }

    private var snapshot: WidgetSnapshot? { entry.snapshot }

    private var linkColor: Color {
        switch snapshot?.link {
        case .connected?: return .brandGreen
        case .connecting?: return .brandAmber
        case .locked?: return .brandAmber
        default: return .secondary
        }
    }

    private var linkText: Text {
        switch snapshot?.link {
        case .connected?: return Text("Connected")
        case .connecting?: return Text("Connecting…")
        case .offline?: return Text("Not connected")
        case .locked?: return Text("Locked")
        case .notPaired?, nil: return Text("Not paired")
        }
    }

    private var batteryText: String {
        guard let level = snapshot?.batteryLevel else { return "–" }
        return level.formatted(.percent.scale(1))
    }

    private var batterySymbol: String {
        guard let level = snapshot?.batteryLevel else { return "battery.0" }
        if snapshot?.charging == true { return "battery.100.bolt" }
        switch level {
        case ..<13: return "battery.0"
        case ..<38: return "battery.25"
        case ..<63: return "battery.50"
        case ..<88: return "battery.75"
        default: return "battery.100"
        }
    }

    private var batteryColor: Color {
        guard let level = snapshot?.batteryLevel else { return .secondary }
        if snapshot?.charging == true { return .brandGreen }
        return level <= 15 ? .brandRed : (level <= 30 ? .brandAmber : .primary)
    }

    private var small: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Circle().fill(linkColor).frame(width: 8, height: 8)
                linkText.font(.caption.weight(.semibold)).foregroundStyle(.secondary).lineLimit(1)
                Spacer(minLength: 0)
                if snapshot?.isDemo == true {
                    Text("Demo").font(.caption2.weight(.bold)).foregroundStyle(Color.brandAmber)
                }
            }
            Spacer(minLength: 0)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Image(systemName: batterySymbol).font(.title3).foregroundStyle(batteryColor)
                Text(batteryText).font(.system(.title, design: .rounded).weight(.bold)).minimumScaleFactor(0.7)
            }
            ForEach(snapshot?.sims.prefix(2).map { $0 } ?? []) { sim in
                HStack(spacing: 6) {
                    SignalBarsView(level: sim.signal ?? 0).frame(width: 22, height: 11)
                    Text(sim.carrier ?? "SIM \(sim.slot + 1)").font(.caption).lineLimit(1)
                    Spacer(minLength: 0)
                    if let net = sim.net, net != "none" {
                        Text(net).font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .padding(2)
    }

    private var medium: some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Circle().fill(linkColor).frame(width: 8, height: 8)
                    linkText.font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Image(systemName: batterySymbol).font(.title2).foregroundStyle(batteryColor)
                Text(batteryText).font(.system(.largeTitle, design: .rounded).weight(.bold)).minimumScaleFactor(0.6)
                if let updated = snapshot?.updated {
                    Text(updated, style: .relative).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Divider()
            VStack(alignment: .leading, spacing: 10) {
                Text("Bridge phone").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                if let sims = snapshot?.sims, !sims.isEmpty {
                    ForEach(sims.prefix(2).map { $0 }) { sim in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 6) {
                                SignalBarsView(level: sim.signal ?? 0).frame(width: 24, height: 12)
                                Text(sim.carrier ?? "SIM \(sim.slot + 1)").font(.subheadline.weight(.semibold)).lineLimit(1)
                            }
                            if let net = sim.net, net != "none" {
                                Text(net).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                } else {
                    Text("Open Pairline to connect").font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
            Spacer(minLength: 0)
        }
        .padding(2)
    }

    private var circular: some View {
        Gauge(value: Double(snapshot?.batteryLevel ?? 0), in: 0...100) {
            Image(systemName: "iphone.gen2")
        } currentValueLabel: {
            Text(snapshot?.batteryLevel.map { "\($0)" } ?? "–")
        }
        .gaugeStyle(.accessoryCircular)
    }

    private var rectangular: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 4) {
                Image(systemName: "link")
                linkText
            }
            .font(.headline)
            HStack(spacing: 4) {
                Image(systemName: batterySymbol)
                Text(batteryText)
            }
            if let sim = snapshot?.sims.first {
                Text("\(sim.carrier ?? "SIM") · \(sim.net ?? "")").lineLimit(1)
            }
        }
    }

    private var inline: some View {
        HStack {
            Image(systemName: batterySymbol)
            Text(batteryText)
        }
    }
}

struct SignalBarsView: View {
    let level: Int

    var body: some View {
        GeometryReader { geo in
            HStack(alignment: .bottom, spacing: geo.size.width * 0.08) {
                ForEach(0..<4) { index in
                    RoundedRectangle(cornerRadius: 1.5)
                        .fill(index < level ? Color.primary : Color.secondary.opacity(0.3))
                        .frame(height: geo.size.height * CGFloat(index + 1) / 4)
                }
            }
        }
        .accessibilityElement()
        .accessibilityLabel(Text("Signal \(level) of 4"))
    }
}

private struct WidgetBackground: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOSApplicationExtension 17.0, *) {
            content.containerBackground(for: .widget) { Color(.systemBackground) }
        } else {
            content.padding().background(Color(.systemBackground))
        }
    }
}
