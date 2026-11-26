import SwiftUI

/// Strings for internet sharing live in their own table, `NetShare.xcstrings` (D-048). Keys are plain strings so
/// Xcode doesn't also extract them into the shared Localizable catalog.
private func t(_ key: String) -> Text { Text(LocalizedStringKey(key), tableName: "NetShare") }

/// What the current Wi-Fi network's proxy setting (Settings › Wi-Fi › ⓘ › Configure Proxy) has to do with internet
/// sharing. Read locally from iOS's proxy settings; nothing is sent anywhere.
enum WiFiProxyStatus: Equatable {
    /// Points at the bridge's current proxy configuration URL.
    case usingBridge
    /// Sharing is on, but this Wi-Fi has no automatic proxy set.
    case notSet
    /// Points at a Pairline proxy configuration that isn't the current one (an old address or port).
    case outdated
    /// Sharing is off, but this Wi-Fi still points at a Pairline proxy: nothing answers there, so no internet.
    case leftOn
    /// Nothing to say (no Pairline proxy involved).
    case none

    /// The automatic proxy configuration URL set for the current network, if any.
    static func currentPACURL() -> String? {
        guard let settings = CFNetworkCopySystemProxySettings()?.takeRetainedValue() as? [String: Any],
              (settings[kCFNetworkProxiesProxyAutoConfigEnable as String] as? NSNumber)?.boolValue == true else { return nil }
        return settings[kCFNetworkProxiesProxyAutoConfigURLString as String] as? String
    }

    /// A URL the bridge serves (`http://<address>:<port>/proxy.pac`, D-048).
    static func isPairlinePAC(_ url: String) -> Bool { url.hasPrefix("http://") && url.hasSuffix("/proxy.pac") }

    static func current(sharingPAC: String?) -> WiFiProxyStatus {
        let set = currentPACURL()
        if let sharingPAC {
            guard let set else { return .notSet }
            if set == sharingPAC { return .usingBridge }
            return isPairlinePAC(set) ? .outdated : .notSet
        }
        if let set, isPairlinePAC(set) { return .leftOn }
        return .none
    }
}

/// Settings › Internet sharing (M8, D-048): use the bridge phone's connection (and its VPN) on this iPhone. The
/// bridge runs a proxy on your local network; this screen turns it on and shows how to use it. Pairline itself
/// opens no connection: Safari and other apps use the proxy you set in Wi-Fi settings, Telegram uses its own.
struct NetShareView: View {
    @EnvironmentObject private var bridge: BridgeController
    @State private var info: NetShareInfo?
    @State private var busy = false
    @State private var error: String?
    @State private var copied: String?
    @State private var proxy: WiFiProxyStatus = .none
    /// What the user just switched, shown until the bridge's status says so (or a few seconds pass).
    @State private var pending: [NetSwitch: Bool] = [:]
    @State private var pendingSIM: Int?
    @State private var switchError: String?
    @Environment(\.scenePhase) private var scenePhase

    private func readProxy() {
        proxy = WiFiProxyStatus.current(sharingPAC: info?.on == true ? info?.pac : nil)
    }

    // MARK: The bridge phone's own switches (D-073)

    private var net: BridgeNetState? { bridge.status?.net }
    private var canSwitch: Bool { bridge.has(.netSwitch) && bridge.phase.isConnected }

    private func value(_ what: NetSwitch) -> Bool { pending[what] ?? net?.value(what) == true }

    private func switchRow(_ what: NetSwitch) -> some View {
        Toggle(isOn: Binding(get: { value(what) }, set: { flip(what, on: $0) })) {
            switch what {
            case .hotspot: Label { t("Hotspot") } icon: { Image(systemName: "personalhotspot") }
            case .bluetooth: Label { t("Bluetooth tethering") } icon: { Image(systemName: "dot.radiowaves.left.and.right") }
            case .data: Label { t("Mobile data") } icon: { Image(systemName: "antenna.radiowaves.left.and.right") }
            }
        }
        .disabled(!canSwitch || pending[what] != nil)
    }

    /// The SIMs to pick the data SIM from: only with two active SIMs.
    private var dataSIMs: [SIMInfo] { (bridge.status?.sims ?? []).count > 1 ? bridge.status?.sims ?? [] : [] }

    @ViewBuilder private var switchesSection: some View {
        if net != nil {
            Section {
                switchRow(.hotspot)
                switchRow(.bluetooth)
                switchRow(.data)
                if !dataSIMs.isEmpty {
                    Picker(selection: Binding(
                        get: { pendingSIM ?? dataSIMs.first { $0.defaultData == true }?.slot ?? -1 },
                        set: { pickDataSIM($0) }
                    )) {
                        ForEach(dataSIMs) { sim in Text(verbatim: sim.label ?? "SIM \(sim.slot + 1)").tag(sim.slot) }
                    } label: {
                        Label { t("Data SIM") } icon: { Image(systemName: "simcard.2") }
                    }
                    .disabled(!canSwitch || pendingSIM != nil)
                }
                if let switchError {
                    Text(verbatim: switchError).font(.footnote).foregroundStyle(.red)
                }
            } header: {
                t("Android phone")
            } footer: {
                if !bridge.has(.netSwitch) {
                    Text(CapabilityInfo(.netSwitch).missingText)
                } else if value(.bluetooth) {
                    t("Bluetooth tethering: on this iPhone, tap the Android phone in Settings › Bluetooth (pair once when asked). It's slower than the hotspot but needs no Wi-Fi.")
                } else {
                    t("These switch the Android phone itself. Join its hotspot here in Settings › Wi-Fi: the fastest way to use its internet, and Pairline connects over it too.")
                }
            }
        }
    }

    private func flip(_ what: NetSwitch, on: Bool) {
        pending[what] = on
        switchError = nil
        Task {
            do {
                _ = try await bridge.link.request(.netSwitch(what, on: on))
                // The bridge's next status shows it; until then (at most a few seconds) the switch shows the choice.
                try? await Task.sleep(nanoseconds: 6_000_000_000)
            } catch {
                switchError = Self.describe(error)
            }
            pending[what] = nil
        }
    }

    private func pickDataSIM(_ slot: Int) {
        guard slot >= 0 else { return }
        pendingSIM = slot
        switchError = nil
        Task {
            do {
                _ = try await bridge.link.request(.netDataSIM(slot: slot))
                try? await Task.sleep(nanoseconds: 6_000_000_000)
            } catch {
                switchError = Self.describe(error)
            }
            pendingSIM = nil
        }
    }

    /// Android's refusals in words (the bridge's own message is English, for diagnostics).
    static func describe(_ error: Error) -> String {
        guard case LinkError.bridge(let reply) = error else { return error.localizedDescription }
        switch reply.code {
        case "permission": return String(localized: "The carrier or Android didn't allow this on the Android phone.", table: "NetShare")
        case "unsupported": return CapabilityInfo(.netSwitch).missingText
        default: return String(localized: "The Android phone couldn't switch it. Try again, or switch it on the Android phone.", table: "NetShare")
        }
    }

    @ViewBuilder private var proxyRow: some View {
        switch proxy {
        case .usingBridge:
            Label { t("This Wi-Fi uses the bridge phone's internet.") } icon: {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
            }
        case .notSet:
            Label { t("This Wi-Fi isn't set up yet: paste the URL below in its proxy settings.") } icon: {
                Image(systemName: "exclamationmark.circle.fill").foregroundStyle(Color.brandAmber)
            }
        case .outdated:
            Label { t("This Wi-Fi's proxy points to an old address. Paste the URL below again.") } icon: {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Color.brandAmber)
            }
        case .leftOn:
            Label {
                t("This Wi-Fi still points to the bridge's proxy, which is off, so this iPhone has no internet on it. Set Settings › Wi-Fi › ⓘ › Configure Proxy back to Off, or turn sharing on.")
            } icon: {
                Image(systemName: "wifi.exclamationmark").foregroundStyle(.red)
            }
        case .none:
            EmptyView()
        }
    }

    var body: some View {
        List {
            if proxy != .none {
                Section { proxyRow }
            }
            switchesSection
            Section {
                Toggle(isOn: Binding(get: { info?.on == true }, set: { turn($0) })) {
                    Label { t("Use the bridge phone's internet") } icon: { Image(systemName: "network") }
                }
                .disabled(busy || !bridge.has(.netShare) || !bridge.phase.isConnected)
            } footer: {
                if !bridge.has(.netShare) {
                    t("Turn on Internet sharing in Pairline Bridge › Settings on your Android phone first.")
                } else {
                    t("Traffic you send through it goes out through the Android phone's connection, and through its VPN if one is on. Both phones need the same Wi-Fi, or this iPhone joined to the Android phone's hotspot.")
                }
            }

            if let info, info.on, let host = info.host, let port = info.port {
                Section {
                    Label {
                        info.vpn == true ? t("The bridge phone's VPN is on.") : t("The bridge phone has no VPN on.")
                    } icon: {
                        Image(systemName: info.vpn == true ? "lock.shield.fill" : "exclamationmark.shield")
                    }
                    if let direct = info.direct, !direct.isEmpty {
                        let suffixes = direct.map { "." + $0 }.joined(separator: ", ")
                        Text(String(format: String(localized: "Sites ending in %@ go direct.", table: "NetShare"), suffixes))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                if let pac = info.pac {
                    Section {
                        copyRow(pac, label: "Proxy configuration URL")
                    } header: {
                        t("Safari and most apps")
                    } footer: {
                        t("Open Settings › Wi-Fi, tap ⓘ next to your network, then Configure Proxy › Automatic, and paste this URL. Set it back to Off when you stop sharing.")
                    }
                }

                if let user = info.user, let pass = info.pass {
                    Section {
                        Button {
                            openTelegram(host: host, port: port, user: user, pass: pass)
                        } label: {
                            Label { t("Use in Telegram") } icon: { Image(systemName: "paperplane.fill") }
                        }
                        copyRow(host, label: "Server")
                        copyRow(String(port), label: "Port")
                        copyRow(user, label: "Username")
                        copyRow(pass, label: "Password")
                    } header: {
                        t("Apps with SOCKS5 proxy settings")
                    } footer: {
                        t("These stay the same until internet sharing is switched off in Pairline Bridge.")
                    }
                }
            }

            if let error {
                Section { Text(verbatim: error).foregroundStyle(.red) }
            }
        }
        .navigationTitle(t("Internet sharing"))
        .navigationBarTitleDisplayMode(.inline)
        .onReceive(bridge.$netShare) { info = $0; readProxy() }
        // The bridge's status confirmed a switch: show it instead of the pending choice.
        .onReceive(bridge.$status) { status in
            guard let net = status?.net else { return }
            for (what, wanted) in pending where net.value(what) == wanted { pending[what] = nil }
            if let slot = pendingSIM, status?.sims?.first(where: { $0.slot == slot })?.defaultData == true { pendingSIM = nil }
        }
        .onAppear { info = bridge.netShare; readProxy() }
        // Back from Settings › Wi-Fi: check the proxy again.
        .onValueChange(scenePhase) { if $0 == .active { readProxy() } }
    }

    private func copyRow(_ value: String, label: String) -> some View {
        Button {
            Clipboard.copy(value)
            copied = value
        } label: {
            HStack {
                t(label).foregroundStyle(.primary)
                Spacer()
                LTRText(value)
                    .font(.callout.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Image(systemName: copied == value ? "checkmark" : "doc.on.doc")
                    .foregroundStyle(Color.accentColor)
            }
        }
    }

    private func turn(_ on: Bool) {
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                let reply = try await bridge.link.request(.netShare(on: on))
                if case .netShare(let value) = reply.message { bridge.netShare = value }
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func openTelegram(host: String, port: Int, user: String, pass: String) {
        var components = URLComponents()
        components.scheme = "tg"
        components.host = "socks"
        components.queryItems = [
            URLQueryItem(name: "server", value: host),
            URLQueryItem(name: "port", value: String(port)),
            URLQueryItem(name: "user", value: user),
            URLQueryItem(name: "pass", value: pass),
        ]
        if let url = components.url { UIApplication.shared.open(url) }
    }
}
