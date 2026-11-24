import SwiftUI

/// Strings for internet sharing live in their own table, `NetShare.xcstrings` (D-048). Keys are plain strings so
/// Xcode doesn't also extract them into the shared Localizable catalog.
private func t(_ key: String) -> Text { Text(LocalizedStringKey(key), tableName: "NetShare") }

/// Settings › Internet sharing (M8, D-048): use the bridge phone's connection (and its VPN) on this iPhone. The
/// bridge runs a proxy on your local network; this screen turns it on and shows how to use it. Pairline itself
/// opens no connection: Safari and other apps use the proxy you set in Wi-Fi settings, Telegram uses its own.
struct NetShareView: View {
    @EnvironmentObject private var bridge: BridgeController
    @State private var info: NetShareInfo?
    @State private var busy = false
    @State private var error: String?
    @State private var copied: String?

    var body: some View {
        List {
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
                        t("These change every time you turn sharing on.")
                    }
                }
            }

            if let error {
                Section { Text(verbatim: error).foregroundStyle(.red) }
            }
        }
        .navigationTitle(t("Internet sharing"))
        .navigationBarTitleDisplayMode(.inline)
        .onReceive(bridge.$netShare) { info = $0 }
        .onAppear { info = bridge.netShare }
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
