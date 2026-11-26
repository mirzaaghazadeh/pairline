import SwiftUI

/// Settings › Cloud mode › Port forwarding (D-083): for a home router without UPnP, NAT-PMP or PCP, the user forwards
/// the bridge's port by hand and tells this iPhone where the home can be reached (its internet address or a DDNS
/// name). The bridge already listens while Cloud mode is on; it just can't learn the home's address by itself, and
/// Pairline never asks an outside service for it. Kept in the Keychain with the rest of the direct connection, and gone
/// when Cloud mode goes off.
struct PortForwardingView: View {
    @ObservedObject var remote: RemoteLink
    @State private var host = ""
    @State private var port = String(ManualForward.defaultPort)
    @State private var problem: ManualForward.Problem?
    @State private var loaded = false

    var body: some View {
        List {
            Section {
                Text("If your router has UPnP or NAT-PMP, turn it on instead: Pairline then sets everything up by itself.", tableName: "RemoteMode")
                    .fixedSize(horizontal: false, vertical: true)
                NavigationLink {
                    RouterHelpView(remote: nil)
                } label: {
                    Label {
                        Text("Allow calls from far away", tableName: "RemoteMode")
                    } icon: {
                        Image(systemName: "wifi.router")
                    }
                }
            }
            Section {
                step(1, Text("In your router, forward TCP port 47830 to the Android phone. Pairline Bridge shows the phone's address on your home network.", tableName: "RemoteMode"))
                step(2, Text("Keep the Android phone's address", tableName: "RemoteMode").fontWeight(.semibold)
                     + Text(verbatim: "\n") + FixedAddressText.why + Text(verbatim: "\n\n") + FixedAddressText.best
                     + Text(verbatim: "\n\n") + FixedAddressText.alternative)
                step(3, Text("If your home's internet address changes, turn on your router's DDNS feature to get a fixed name.", tableName: "RemoteMode"))
                step(4, Text("Enter that name, or your home's internet address, below.", tableName: "RemoteMode"))
            } header: {
                Text("If it doesn't: set it up yourself", tableName: "RemoteMode")
            }
            Section {
                TextField(text: $host, prompt: Text(verbatim: "myhome.ddns.net")) {
                    Text("Your home's internet address or DDNS name", tableName: "RemoteMode")
                }
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .textContentType(.URL)
                .environment(\.layoutDirection, .leftToRight)
                .disabled(!remote.isOn)
                HStack(spacing: 12) {
                    Text("Port", tableName: "RemoteMode")
                    Spacer(minLength: 12)
                    TextField(text: $port, prompt: Text(verbatim: String(ManualForward.defaultPort))) {
                        Text("Port", tableName: "RemoteMode")
                    }
                    .keyboardType(.numberPad)
                    .multilineTextAlignment(.trailing)
                    .environment(\.layoutDirection, .leftToRight)
                    .frame(maxWidth: 120)
                    .disabled(!remote.isOn)
                }
                Button {
                    save()
                } label: {
                    Text("Save", tableName: "RemoteMode")
                }
                .disabled(!remote.isOn || host.trimmingCharacters(in: .whitespaces).isEmpty)
                if remote.direct.manual != nil {
                    Button(role: .destructive) {
                        remove()
                    } label: {
                        Text("Remove port forwarding", tableName: "RemoteMode")
                    }
                }
            } header: {
                Text("Your home's internet address or DDNS name", tableName: "RemoteMode")
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    if !remote.isOn {
                        Text("Turn Cloud mode on first. Port forwarding is kept while it's on.", tableName: "RemoteMode")
                    }
                    if let problem {
                        Text(Self.message(for: problem)).foregroundStyle(Color.brandRed)
                    }
                    if remote.direct.manual != nil, problem == nil {
                        status
                    }
                    if remote.direct.manual != nil, remote.direct.status?.reason == .carrierNAT {
                        Text("Your internet provider shares one internet address among many homes, so port forwarding can't help there.", tableName: "RemoteMode")
                            .foregroundStyle(Color.brandAmber)
                    }
                }
            }
        }
        .navigationTitle(Text("Port forwarding", tableName: "RemoteMode"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: load)
    }

    private func step(_ number: Int, _ text: Text) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(verbatim: "\(number)")
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .foregroundStyle(.secondary)
            text
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// Whether the direct connection reached the bridge at the saved address, and when it last tried.
    @ViewBuilder private var status: some View {
        if let attempt = remote.manualAttempt {
            let when = attempt.at.formatted(.relative(presentation: .named))
            if attempt.worked {
                Text("Your Android phone answered at this address (\(when)).", tableName: "RemoteMode")
                    .foregroundStyle(Color.brandGreen)
            } else {
                Text("Couldn't reach your Android phone at the address you entered. Last tried \(when).", tableName: "RemoteMode")
                    .foregroundStyle(Color.brandAmber)
            }
        } else {
            Text("Saved. Pairline uses it whenever your phones aren't near each other.", tableName: "RemoteMode")
        }
    }

    static func message(for problem: ManualForward.Problem) -> String {
        switch problem {
        case .empty:
            return String(localized: "Enter your home's internet address or a DDNS name.", table: "RemoteMode")
        case .notAnAddress:
            return String(localized: "That isn't an internet address or a DNS name. Enter something like myhome.ddns.net.", table: "RemoteMode")
        case .insideHome:
            return String(localized: "That's an address inside a home network; enter your home's internet address or a DDNS name.", table: "RemoteMode")
        case .badPort:
            return String(localized: "The port is a number from 1 to 65535.", table: "RemoteMode")
        }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        if let manual = remote.direct.manual {
            host = manual.host
            port = String(manual.port)
        }
    }

    private func save() {
        switch ManualForward.make(host: host, port: port) {
        case .success(let forward):
            problem = nil
            host = forward.host
            port = String(forward.port)
            remote.setManualForward(forward)
            Haptics.success()
        case .failure(let problem):
            self.problem = problem
            Haptics.error()
        }
    }

    private func remove() {
        remote.setManualForward(nil)
        host = ""
        port = String(ManualForward.defaultPort)
        problem = nil
    }
}
