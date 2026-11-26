import SwiftUI

/// The bridge's answer to "Check my home connection" (`remote.check`, PROTOCOL.md "Remote messages", D-080): whether
/// remote mode's direct connection could work at its home, found by asking the router read-only.
struct HomeCheckReply: Equatable {
    enum Router: String, Equatable {
        case natPMP = "nat-pmp"
        case pcp
        case upnp

        /// The protocol's own name, the same in every language.
        var name: String {
            switch self {
            case .natPMP: return "NAT-PMP"
            case .pcp: return "PCP"
            case .upnp: return "UPnP"
            }
        }
    }

    var likely: Bool
    /// `remote.state`'s fixed codes; nil when likely, or for a code this app doesn't know.
    var reason: DirectStatus.Reason?
    var router: Router?
    /// The bridge has a global IPv6 address, which can work when the router lets connections in.
    var ipv6: Bool
}

extension HomeCheckReply: Decodable {
    private enum CodingKeys: String, CodingKey { case direct, reason, router, ipv6 }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        likely = (try? c.decodeIfPresent(String.self, forKey: .direct)) == "likely"
        reason = likely ? nil : (try? c.decodeIfPresent(String.self, forKey: .reason))?.flatMap(DirectStatus.Reason.init(text:))
        router = (try? c.decodeIfPresent(String.self, forKey: .router))?.flatMap(Router.init(rawValue:))
        ipv6 = (try? c.decodeIfPresent(Bool.self, forKey: .ipv6)) ?? false
    }
}

extension HomeCheckReply {
    /// The verdict in plain words.
    var verdict: Text {
        if likely { return Text("A direct connection should work: your home router can open a port for Pairline.", tableName: "RemoteMode") }
        switch reason {
        case .carrierNAT?:
            return Text("Your internet provider shares one internet address among many homes (carrier-grade NAT), so nothing at home can open a port.", tableName: "RemoteMode")
        case .noPublicAddress?:
            return Text("Your router doesn't have its own internet address: another router or modem sits in front of it. Putting that modem in bridge mode may help.", tableName: "RemoteMode")
        case .router?:
            return Text("Your router doesn't open ports on request. Turning on UPnP or NAT-PMP in the router's settings usually fixes this.", tableName: "RemoteMode")
        case .notOnWiFi?:
            return Text("The Android phone isn't on Wi-Fi. Connect it to your home Wi-Fi and check again.", tableName: "RemoteMode")
        case nil:
            return Text("A direct connection doesn't look possible on this network.", tableName: "RemoteMode")
        }
    }

    /// What it means for remote mode, or nil when there's nothing to add.
    var consequence: Text? {
        if likely || reason == .notOnWiFi { return nil }
        if ipv6 {
            return Text("The Android phone also has an IPv6 address, which may work if your router lets incoming connections through. Otherwise texts and codes still come through iCloud, but calls need a direct connection.", tableName: "RemoteMode")
        }
        return Text("Texts and codes still come through iCloud, but calls need a direct connection.", tableName: "RemoteMode")
    }
}

/// "Check my home connection" in Settings › Remote mode: free, with remote mode on or off, over any link to the
/// bridge (or through iCloud). The bridge only asks its router; nothing is opened.
struct HomeConnectionCheckRow: View {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var bridge: BridgeController
    @State private var running = false
    @State private var result: HomeCheckReply?
    @State private var failure: String?

    /// Any link to the bridge: a nearby or direct session, or remote mode's mailbox. An older bridge answers
    /// `unsupported`, which shows as "update the bridge app".
    private var canCheck: Bool {
        bridge.phase.isConnected || bridge.phase == .remote
    }

    var body: some View {
        Button {
            check()
        } label: {
            HStack(spacing: 12) {
                Label {
                    Text("Check my home connection", tableName: "RemoteMode")
                } icon: {
                    Image(systemName: "wifi.router")
                }
                Spacer(minLength: 12)
                if running { ProgressView() }
            }
        }
        .disabled(running || !canCheck)
        if let result {
            VStack(alignment: .leading, spacing: 6) {
                Label {
                    result.verdict
                } icon: {
                    Image(systemName: result.likely ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(result.likely ? Color.brandGreen : Color.brandAmber)
                }
                .font(.subheadline)
                if let consequence = result.consequence {
                    consequence
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                if let router = result.router {
                    Text("Router answers: \(router.name)", tableName: "RemoteMode")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(.vertical, 2)
        } else if let failure {
            Text(failure)
                .font(.footnote)
                .foregroundStyle(Color.brandRed)
        }
    }

    private func check() {
        guard !running else { return }
        running = true
        failure = nil
        Task {
            do {
                let reply = try await app.link.request(.remoteCheck)
                if case .remoteCheck(let check) = reply.message {
                    result = check
                    log.info("remote", "Home connection check: \(check.likely ? "likely" : "unlikely")"
                             + (check.reason.map { " (\($0.rawValue))" } ?? "")
                             + (check.router.map { ", router answers \($0.name)" } ?? ""))
                    if check.likely { Haptics.success() } else { Haptics.error() }
                } else {
                    result = nil
                    failure = String(localized: "The bridge phone sent an answer this app doesn't understand. Update both apps.", table: "RemoteMode")
                }
            } catch {
                result = nil
                failure = error.localizedDescription
                Haptics.error()
            }
            running = false
        }
    }
}
