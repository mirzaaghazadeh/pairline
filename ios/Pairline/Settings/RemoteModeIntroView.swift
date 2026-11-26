import SwiftUI

/// What Cloud mode (remote mode, D-092) is, in plain words ("What users are told"): before it's turned on the first
/// time, from "How Cloud mode works", and as the step after pairing. Strings live in the "RemoteMode" table.
struct RemoteModeIntroContent: View {
    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "icloud.and.arrow.down")
                .font(.largeTitle)
                .foregroundStyle(Color.brandBlue)
                .accessibilityHidden(true)
            VStack(spacing: 8) {
                Text("Cloud mode", tableName: "RemoteMode")
                    .font(.title2.weight(.bold))
                    .multilineTextAlignment(.center)
                Text("When your phones aren't near each other, they keep working over the internet: texts, codes and calls reach this iPhone wherever you are.", tableName: "RemoteMode")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            VStack(alignment: .leading, spacing: 14) {
                HelpBullet(symbol: "antenna.radiowaves.left.and.right", text: Text("Nearby, your phones keep using Bluetooth and Wi-Fi. Cloud mode takes over only when they can't reach each other.", tableName: "RemoteMode"))
                HelpBullet(symbol: "server.rack", text: Text("There's no Pairline server and no account. Your phones talk through your own iCloud and your home's internet connection.", tableName: "RemoteMode"))
                HelpBullet(symbol: "lock", text: Text("Texts, calls and codes are locked with keys only your two phones hold. Apple stores only locked records in your iCloud, and they're deleted once read.", tableName: "RemoteMode"))
                HelpBullet(symbol: "eye.slash", text: Text("Apple and internet providers can see that your phones talk, not what they say.", tableName: "RemoteMode"))
            }
            .card(padding: 14)
            VStack(alignment: .leading, spacing: 14) {
                HelpBullet(symbol: "phone.connection", text: Text("Call audio goes straight between your phones, not through iCloud.", tableName: "RemoteMode"))
                HelpBullet(symbol: "wifi.router", text: Text("For calls, your home router opens one port for the Android phone, where it allows that. Only your paired iPhone can use it; anything else is turned away.", tableName: "RemoteMode"))
                HelpBullet(symbol: "house", text: Text("Leave the Android phone at home, on charge and on Wi-Fi.", tableName: "RemoteMode"))
                HelpBullet(symbol: "power", text: Text("It's off unless you turn it on, and you can turn it off any time. That closes the router's port and deletes the records in iCloud.", tableName: "RemoteMode"))
            }
            .card(padding: 14)
        }
    }
}

/// The explanation as a sheet: with `onTurnOn` it ends in "Turn on Cloud mode" / "Not now"; without, in "Done".
struct RemoteModeIntroView: View {
    var onTurnOn: (() -> Void)?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            RemoteModeIntroContent()
                .padding(20)
        }
        .scrollsOnlyWhenNeeded()
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 10) {
                if let onTurnOn {
                    Button {
                        Haptics.tap()
                        dismiss()
                        onTurnOn()
                    } label: {
                        Text("Turn on Cloud mode", tableName: "RemoteMode")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    Button {
                        dismiss()
                    } label: {
                        Text("Not now", tableName: "RemoteMode")
                    }
                    .buttonStyle(SecondaryButtonStyle())
                } else {
                    Button {
                        dismiss()
                    } label: {
                        Text("Done", tableName: "RemoteMode")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 6)
            .background(.regularMaterial)
        }
    }
}
