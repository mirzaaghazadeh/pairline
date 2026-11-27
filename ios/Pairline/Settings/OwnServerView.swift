import SwiftUI

/// Settings › Connection › Advanced › Your own server (D-106): a relay the user runs, for calls from far away when the
/// home router lets nothing in. The user scans the QR code their server prints (or pastes its link); this iPhone keeps
/// it with Cloud mode's direct connection and hands it to the bridge (`remote.relay`). Shows where the bridge and this
/// iPhone stand with it, and removes it. The host is shown here only, never logged.
struct OwnServerView: View {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var remote: RemoteLink
    @ObservedObject var bridge: BridgeController
    @State private var scanning = false
    @State private var busy = false
    @State private var problem: String?
    @State private var saved: Text?

    /// The bridge takes `remote.relay` (it lists `relay` in `status.caps`).
    private var bridgeTakesIt: Bool { bridge.caps.contains(RelayServer.capability) }

    private var canSet: Bool { remote.isOn && bridgeTakesIt && !busy }

    var body: some View {
        List {
            Section {
                OwnServerText.intro
                    .fixedSize(horizontal: false, vertical: true)
                NavigationLink {
                    OwnServerGuideView()
                } label: {
                    Label {
                        Text("Set up your own server", tableName: "RemoteMode")
                    } icon: {
                        Image(systemName: "server.rack")
                    }
                }
            }
            if remote.isOn, let server = remote.direct.relay {
                Section {
                    row(Text("Server", tableName: "RemoteMode")) {
                        Text(verbatim: server.host)
                            .environment(\.layoutDirection, .leftToRight)
                            .textSelection(.enabled)
                    }
                    row(Text("Android phone", tableName: "RemoteMode")) { bridgeState }
                    Button(role: .destructive) {
                        set(nil)
                    } label: {
                        Text("Remove this server", tableName: "RemoteMode")
                    }
                    .disabled(busy)
                } header: {
                    Text("Your server", tableName: "RemoteMode")
                } footer: {
                    attemptText
                }
            }
            Section {
                if QRScannerView.isCameraAvailable {
                    Button {
                        scanning = true
                    } label: {
                        Label {
                            Text("Scan the server's QR code", tableName: "RemoteMode")
                        } icon: {
                            Image(systemName: "qrcode.viewfinder")
                        }
                    }
                    .disabled(!canSet)
                }
                PasteButton(payloadType: String.self) { strings in
                    guard let text = strings.first else { return }
                    Task { @MainActor in use(text) }
                }
                .disabled(!canSet)
                if busy {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Sending it to the Android phone…", tableName: "RemoteMode")
                            .foregroundStyle(.secondary)
                    }
                }
            } header: {
                remote.direct.relay == nil ? Text("Add your server", tableName: "RemoteMode")
                    : Text("Use another server", tableName: "RemoteMode")
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    if !remote.isOn {
                        Text("Turn Cloud mode on first. Your own server is part of it.", tableName: "RemoteMode")
                    } else if !bridgeTakesIt {
                        Text("Your own server needs a newer Pairline Bridge on the Android phone.", tableName: "RemoteMode")
                    } else {
                        Text("When it starts, your server shows a QR code and a link that starts with pairline-relay://. Scan the code, or copy the link and tap Paste.", tableName: "RemoteMode")
                    }
                    if let problem {
                        Text(problem).foregroundStyle(Color.brandRed)
                    } else if let saved {
                        saved.foregroundStyle(Color.brandGreen)
                    }
                }
            }
        }
        .navigationTitle(Text("Your own server", tableName: "RemoteMode"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $scanning) {
            NavigationStack {
                QRScannerView { code in
                    scanning = false
                    use(code)
                }
                .ignoresSafeArea()
                .navigationTitle(Text("Scan the server's QR code", tableName: "RemoteMode"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button { scanning = false } label: { Text("Cancel") }
                    }
                }
            }
        }
    }

    /// A label and its value side by side, stacked at the accessibility text sizes so nothing is cut off.
    private func row<Value: View>(_ label: Text, @ViewBuilder value: () -> Value) -> some View {
        let shown = value()
        return AdaptiveStack(spacing: 12) {
            label
            Spacer(minLength: 12)
            shown
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.trailing)
        }
    }

    /// The bridge's own word on it (`remote.state` `relay`), in plain words.
    private var bridgeState: Text {
        switch remote.direct.status?.relay {
        case nil:
            return Text("Waiting to hear from it", tableName: "RemoteMode")
        case .up?:
            return Text("Connected to your server", tableName: "RemoteMode")
        case .down?:
            switch remote.direct.status?.relayReason {
            case .unreachable?: return Text("Can't reach your server", tableName: "RemoteMode")
            case .pin?: return Text("Your server's keys changed", tableName: "RemoteMode")
            case .key?: return Text("Your server refused the link", tableName: "RemoteMode")
            case .version?: return Text("Your server needs an update", tableName: "RemoteMode")
            case .full?: return Text("Your server is full", tableName: "RemoteMode")
            // The bridge says `down` without a reason before its first attempt finishes, or for a reason this app
            // doesn't know: still trying, not a failure.
            case nil: return Text("Connecting to your server…", tableName: "RemoteMode")
            }
        }
    }

    /// This iPhone's last try through the server, with when, and the next step where there is one.
    private var attemptText: Text {
        guard let attempt = remote.relayAttempt else {
            return Text("Pairline uses your server whenever your phones aren't near each other.", tableName: "RemoteMode")
        }
        let when = attempt.at.formatted(.relative(presentation: .named))
        switch attempt.outcome {
        case .connected:
            return Text("This iPhone reached your Android phone through it (\(when)).", tableName: "RemoteMode")
        case .noBridge:
            return Text("Your server answered, but the Android phone wasn't there (\(when)). Check that it's on and online.", tableName: "RemoteMode")
        case .wrongKey, .pinMismatch:
            return Text("Your server didn't accept this link (\(when)). Scan its code again.", tableName: "RemoteMode")
        case .badVersion:
            return Text("Your server needs an update (\(when)).", tableName: "RemoteMode")
        case .full:
            return Text("Your server is full (\(when)).", tableName: "RemoteMode")
        case .unreachable, .timeout:
            return Text("This iPhone couldn't reach your server (\(when)). Check that it's running and its port is open.", tableName: "RemoteMode")
        case .handshakeFailed:
            return Text("Something other than your Android phone answered through your server (\(when)).", tableName: "RemoteMode")
        }
    }

    private func use(_ text: String) {
        problem = nil
        saved = nil
        switch RelayServer.parse(text) {
        case .success(let server):
            set(server)
        case .failure(let failure):
            problem = Self.message(for: failure)
            Haptics.error()
        }
    }

    private func set(_ server: RelayServer?) {
        guard !busy else { return }
        busy = true
        problem = nil
        saved = nil
        Task {
            do {
                try await app.link.setRelay(server)
                if server != nil { saved = Text("Saved. The Android phone connects to your server now.", tableName: "RemoteMode") }
                Haptics.success()
            } catch LinkError.bridge(let reply) where reply.code == "unsupported" {
                problem = String(localized: "Your own server needs a newer Pairline Bridge on the Android phone.", table: "RemoteMode")
                Haptics.error()
            } catch {
                // Kept here; the link sends it again on the next session with the bridge.
                if server != nil {
                    saved = Text("Saved on this iPhone. It goes to the Android phone the next time they're linked.", tableName: "RemoteMode")
                }
            }
            busy = false
        }
    }

    static func message(for problem: RelayServer.Problem) -> String {
        switch problem {
        case .notARelayLink:
            return String(localized: "That isn't a Pairline server link. It starts with pairline-relay:// and your server shows it when it starts.", table: "RemoteMode")
        case .unsupportedVersion:
            return String(localized: "That link is from a newer Pairline server. Update Pairline on this iPhone.", table: "RemoteMode")
        case .insideHome:
            return String(localized: "That server's address is inside a home network, which this iPhone can't reach from far away. Use the server's internet address or name.", table: "RemoteMode")
        }
    }
}

/// Words shared by the screens that mention the user's own server (D-106).
enum OwnServerText {
    static let intro = Text("If your home router lets nothing in, calls from far away can go through a small server you run yourself. Both phones connect out to it, and it only passes along data locked with keys only your two phones hold. Texts and codes still come through your own iCloud, which also wakes this iPhone.", tableName: "RemoteMode")
}

/// A row that opens the user's own server: its settings when a bridge is paired and Cloud mode exists, the guide
/// otherwise. For the router guide, port forwarding and the home check, where the router is the problem.
struct OwnServerLink: View {
    var remote: RemoteLink?
    @EnvironmentObject private var app: AppModel

    var body: some View {
        NavigationLink {
            if let remote, !app.isDemo, app.isPaired {
                OwnServerView(remote: remote, bridge: app.real)
            } else {
                OwnServerGuideView()
            }
        } label: {
            Label {
                Text("Use your own server", tableName: "RemoteMode")
            } icon: {
                Image(systemName: "server.rack")
            }
        }
    }
}

/// Help › Set up your own server (D-106): from renting a small server to scanning its code, what the server sees, and
/// where the full guide is (`relay/README.md`).
struct OwnServerGuideView: View {
    /// The command that starts the relay with Docker (PROTOCOL.md: it listens on 443 and prints its link).
    static let runCommand = "docker run -d --name pairline-relay --restart unless-stopped -p 443:443 -v pairline-relay:/data -e PAIRLINE_RELAY_PUBLIC=YOUR.SERVER.IP ghcr.io/mirzaaghazadeh/pairline-relay"
    static let logsCommand = "docker logs pairline-relay"

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Set up your own server", tableName: "RemoteMode").font(.largeTitle.weight(.bold))
                Text("For calls from far away when your home router lets nothing in. It takes about 15 minutes, once.", tableName: "RemoteMode")
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                HelpCard(symbol: "1.circle.fill", title: Text("A small server", tableName: "RemoteMode"),
                         text: Text("Rent a small Linux server (a VPS) from any hosting company, or use a computer you already have that's reachable from the internet. Pick one near your home, ideally in the same country: every word of a call goes through it, and a far-away server adds delay. The smallest plan is plenty.", tableName: "RemoteMode"))
                HelpCard(symbol: "2.circle.fill", title: Text("Install Docker", tableName: "RemoteMode"),
                         text: Text("On the server, install Docker. Many hosting companies offer it ready to use; otherwise docs.docker.com has the steps for each Linux.", tableName: "RemoteMode"))
                VStack(alignment: .leading, spacing: 10) {
                    HelpCard(symbol: "3.circle.fill", title: Text("Start Pairline's server", tableName: "RemoteMode"),
                             text: Text("Run this on the server, with your server's internet address or name in place of YOUR.SERVER.IP:", tableName: "RemoteMode"))
                    CommandBlock(command: Self.runCommand)
                }
                VStack(alignment: .leading, spacing: 10) {
                    HelpCard(symbol: "4.circle.fill", title: Text("Show its QR code", tableName: "RemoteMode"),
                             text: Text("Then run this. It shows a QR code and a link that starts with pairline-relay://.", tableName: "RemoteMode"))
                    CommandBlock(command: Self.logsCommand)
                }
                HelpCard(symbol: "5.circle.fill", title: Text("Scan it on this iPhone", tableName: "RemoteMode"),
                         text: Text("Open Settings › Connection › Advanced › Your own server and scan the code, or copy the link and paste it there. The iPhone passes it to the Android phone by itself.", tableName: "RemoteMode"))
                HelpCard(symbol: "network", title: Text("Port 443", tableName: "RemoteMode"),
                         text: Text("Your server listens on port 443, like a website, so it also works on networks that block other ports. Allow TCP port 443 in the server's firewall. If something else on the server uses 443, use another port in both places, for example -p 8443:443 and PAIRLINE_RELAY_PUBLIC=YOUR.SERVER.IP:8443.", tableName: "RemoteMode"))
                HelpCard(symbol: "eye.slash", title: Text("What your server sees", tableName: "RemoteMode"),
                         text: Text("Only locked data: it passes bytes between your two phones and can't read or change them. It does see both phones' internet addresses and when and how much they talk, so use a server you trust. It keeps nothing but its own keys.", tableName: "RemoteMode"))
                HelpCard(symbol: "key", title: Text("Keep the link to yourself", tableName: "RemoteMode"),
                         text: Text("Anyone with the link can use your server to pass data along, though never to reach your phones. The full guide explains how to make a new link, update the server and build it yourself: relay/README.md in Pairline's source code.", tableName: "RemoteMode"))
                HelpCard(symbol: "icloud", title: Text("iCloud still does its part", tableName: "RemoteMode"),
                         text: Text("Texts and codes keep coming through your own iCloud, which is also what wakes this iPhone. Your server carries calls and the live connection while you're away.", tableName: "RemoteMode"))
            }
            .padding(20)
        }
        .background(Color(.systemGroupedBackground))
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// A shell command to type on the server: monospaced, left to right in every language, selectable, with a Copy button.
private struct CommandBlock: View {
    let command: String
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: command)
                .font(.footnote.monospaced())
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .environment(\.layoutDirection, .leftToRight)
            Button {
                UIPasteboard.general.string = command
                copied = true
                Haptics.success()
            } label: {
                Label {
                    copied ? Text("Copied") : Text("Copy")
                } icon: {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                }
                .font(.subheadline.weight(.semibold))
            }
            .accessibilityHint(Text("Copies the command", tableName: "RemoteMode"))
        }
        .card()
    }
}
