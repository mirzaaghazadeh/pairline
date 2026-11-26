import SwiftUI

/// Help › "Allow calls from far away" (Cloud mode's direct connection, D-080, D-082, D-083): what the home router has
/// to allow, for any router brand. First choice: turn on UPnP or NAT-PMP, and Pairline sets everything up by itself.
/// Otherwise: forward the port by hand and enter the home's address (Settings › Cloud mode › Port forwarding). Then
/// what to do when the internet provider or a second modem is the reason. Opened from Help, from "Check my home
/// connection" when the router is the problem, and from the Port forwarding screen.
struct RouterHelpView: View {
    /// For the link to the Port forwarding screen; nil where that screen is the one that opened this.
    var remote: RemoteLink? = nil

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Allow calls from far away", tableName: "RemoteMode").font(.largeTitle.weight(.bold))
                Text("Texts and codes reach you through iCloud anywhere. Calls from far away also need a direct connection to the Android phone at home, which your home router makes by opening one port for Pairline. Many routers do this by themselves; on others you turn it on once.", tableName: "RemoteMode")
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                Text("If your router has UPnP or NAT-PMP, turn it on", tableName: "RemoteMode")
                    .font(.title3.weight(.semibold))
                HelpCard(symbol: "1.circle.fill", title: Text("Open your router's settings", tableName: "RemoteMode"),
                         text: Text("On a phone or computer on your home Wi-Fi, open your router's settings page in a web browser. Its address and admin password are usually on a sticker on the router, or in your internet provider's app.", tableName: "RemoteMode"))
                HelpCard(symbol: "2.circle.fill", title: Text("Turn on UPnP or NAT-PMP", tableName: "RemoteMode"),
                         text: Text("Look for UPnP, NAT-PMP or PCP, often under Advanced, NAT, Network or Firewall. Turn it on and save. Can't find it? Search the web for your router's model and \"UPnP\".", tableName: "RemoteMode"))
                HelpCard(symbol: "3.circle.fill", title: Text("Check again", tableName: "RemoteMode"),
                         text: Text("In Pairline, open Settings › Cloud mode › Check my home connection. When it says a direct connection should work, calls from far away work too.", tableName: "RemoteMode"))

                Text("If it doesn't: set up port forwarding yourself", tableName: "RemoteMode")
                    .font(.title3.weight(.semibold))
                HelpCard(symbol: "arrow.left.arrow.right", title: Text("Forward the port", tableName: "RemoteMode"),
                         text: Text("In your router, forward TCP port 47830 to the Android phone. Pairline Bridge shows the phone's address on your home network.", tableName: "RemoteMode"))
                HelpCard(symbol: "pin", title: Text("Keep the Android phone's address", tableName: "RemoteMode"),
                         text: FixedAddressText.full)
                HelpCard(symbol: "globe", title: Text("A fixed name for your home", tableName: "RemoteMode"),
                         text: Text("If your home's internet address changes, turn on your router's DDNS feature to get a fixed name.", tableName: "RemoteMode"))
                HelpCard(symbol: "iphone", title: Text("Enter it in Pairline", tableName: "RemoteMode"),
                         text: Text("Open Settings › Cloud mode › Port forwarding on this iPhone and enter that name, or your home's internet address.", tableName: "RemoteMode"))
                if let remote {
                    NavigationLink {
                        PortForwardingView(remote: remote)
                    } label: {
                        Label {
                            Text("Set up port forwarding", tableName: "RemoteMode")
                        } icon: {
                            Image(systemName: "arrow.left.arrow.right.square")
                        }
                        .font(.headline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .card()
                    }
                }

                Text("If neither works", tableName: "RemoteMode")
                    .font(.title3.weight(.semibold))
                HelpCard(symbol: "building.2", title: Text("If your provider shares one address", tableName: "RemoteMode"),
                         text: Text("Some internet providers share one internet address among many homes (carrier-grade NAT). Then no router setting helps. Ask your provider for a public IPv4 address or for IPv6; some give one on request.", tableName: "RemoteMode"))
                HelpCard(symbol: "server.rack", title: Text("Two boxes at home", tableName: "RemoteMode"),
                         text: Text("If your provider's modem sits in front of your own router, turn on UPnP on the modem too, or put the modem in bridge mode so your router gets the internet address.", tableName: "RemoteMode"))
                HelpCard(symbol: "lock.shield", title: Text("What opening a port means", tableName: "RemoteMode"),
                         text: Text("Pairline opens one port, only to the Android phone, and only your paired iPhone can use it: anything else is turned away. With UPnP on, apps on your home network can also ask the router to open ports; Pairline closes its own when you turn Cloud mode off.", tableName: "RemoteMode"))
                HelpCard(symbol: "phone.fill", title: Text("Talking on the iPhone", tableName: "RemoteMode"),
                         text: Text("To hear and talk on the iPhone, the Android phone also needs Talk on iPhone set up (Android 11 or later).", tableName: "RemoteMode"))
            }
            .padding(20)
        }
        .background(Color(.systemGroupedBackground))
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Why manual port forwarding needs the Android phone at a fixed address on the home network, and how to give it one
/// (D-083). Shared by the Help article and the Port forwarding screen.
enum FixedAddressText {
    static let why = Text("The router's forwarding rule points at one address on your home network. If the Android phone gets a different one (after a restart or a while away), calls from far away stop working until you fix the rule.", tableName: "RemoteMode")
    static let best = Text("Best: in the router, reserve the phone's current address for it (\"DHCP reservation\", \"static lease\", \"address reservation\" or \"always use this IP address\"; Pairline Bridge shows the address). On Android 10 and later, first set that Wi-Fi network's privacy to \"Use device MAC\", or the router won't recognize the phone.", tableName: "RemoteMode")
    static let alternative = Text("Or on the Android phone: Wi-Fi › that network › IP settings › Static, with an address outside the router's automatic range. Only if you know the range: a clash breaks the connection.", tableName: "RemoteMode")
    static let notWithUPnP = Text("Not needed with UPnP or NAT-PMP: Pairline then updates the router itself whenever the phone's address changes.", tableName: "RemoteMode")

    static var full: Text {
        why + Text(verbatim: "\n\n") + best + Text(verbatim: "\n\n") + alternative + Text(verbatim: "\n\n") + notWithUPnP
    }
}
