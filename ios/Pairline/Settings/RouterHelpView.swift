import SwiftUI

/// Help › "Allow calls from far away" (Cloud mode's direct connection, D-080, D-082): what the home router has to
/// allow, in steps that fit any router brand, and what to do when the internet provider or a second modem is the
/// reason. Opened from Help and from "Check my home connection" when the router is the problem.
struct RouterHelpView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Allow calls from far away", tableName: "RemoteMode").font(.largeTitle.weight(.bold))
                Text("Texts and codes reach you through iCloud anywhere. Calls from far away also need a direct connection to the Android phone at home, which your home router makes by opening one port for Pairline. Many routers do this by themselves; on others you turn it on once.", tableName: "RemoteMode")
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                HelpCard(symbol: "1.circle.fill", title: Text("Open your router's settings", tableName: "RemoteMode"),
                         text: Text("On a phone or computer on your home Wi-Fi, open your router's settings page in a web browser. Its address and admin password are usually on a sticker on the router, or in your internet provider's app.", tableName: "RemoteMode"))
                HelpCard(symbol: "2.circle.fill", title: Text("Turn on UPnP or NAT-PMP", tableName: "RemoteMode"),
                         text: Text("Look for UPnP, NAT-PMP or PCP, often under Advanced, NAT, Network or Firewall. Turn it on and save. Can't find it? Search the web for your router's model and \"UPnP\".", tableName: "RemoteMode"))
                HelpCard(symbol: "3.circle.fill", title: Text("Check again", tableName: "RemoteMode"),
                         text: Text("In Pairline, open Settings › Cloud mode › Check my home connection. When it says a direct connection should work, calls from far away work too.", tableName: "RemoteMode"))
                HelpCard(symbol: "building.2", title: Text("If your provider shares one address", tableName: "RemoteMode"),
                         text: Text("Some internet providers share one internet address among many homes (carrier-grade NAT). Then no router setting helps. Ask your provider for a public IPv4 address or for IPv6; some give one on request.", tableName: "RemoteMode"))
                HelpCard(symbol: "server.rack", title: Text("Two boxes at home", tableName: "RemoteMode"),
                         text: Text("If your provider's modem sits in front of your own router, turn on UPnP on the modem too, or put the modem in bridge mode so your router gets the internet address.", tableName: "RemoteMode"))
                HelpCard(symbol: "lock.shield", title: Text("What turning on UPnP means", tableName: "RemoteMode"),
                         text: Text("Apps on your home network can ask the router to open ports. Pairline opens one, closes it when you turn Cloud mode off, and only your paired iPhone can use it.", tableName: "RemoteMode"))
                HelpCard(symbol: "phone.fill", title: Text("Talking on the iPhone", tableName: "RemoteMode"),
                         text: Text("To hear and talk on the iPhone, the Android phone also needs Talk on iPhone set up (Android 11 or later).", tableName: "RemoteMode"))
            }
            .padding(20)
        }
        .background(Color(.systemGroupedBackground))
        .navigationBarTitleDisplayMode(.inline)
    }
}
