import SwiftUI

/// Setup guide, staying connected, limits and troubleshooting. Everything is bundled in the app.
struct HelpCenterView: View {
    var body: some View {
        List {
            Section {
                // The two ways to connect, with an example each (D-099).
                if RemoteFeature.isAvailable {
                    NavigationLink {
                        HelpArticleView(article: .ways)
                    } label: {
                        ToolRow(symbol: "arrow.triangle.branch", tint: .brandBlue,
                                title: Text("Nearby or Cloud mode?", tableName: "Connection"),
                                subtitle: Text("Which one is for you, with examples", tableName: "Connection"))
                    }
                }
                NavigationLink {
                    HelpArticleView(article: .setup)
                } label: {
                    ToolRow(symbol: "1.circle.fill", tint: .brandFill, title: Text("Set up the Android phone"),
                            subtitle: Text("Install Pairline Bridge and pair"))
                }
                NavigationLink {
                    HelpArticleView(article: .keepAlive)
                } label: {
                    ToolRow(symbol: "bolt.heart.fill", tint: .brandGreen, title: Text("Keep the link alive"),
                            subtitle: Text("Battery settings that matter"))
                }
                NavigationLink {
                    HelpArticleView(article: .forceQuit)
                } label: {
                    ToolRow(symbol: "rectangle.stack.badge.minus", tint: .brandAmber, title: Text("Don't swipe Pairline away"),
                            subtitle: Text("Why the iPhone app must stay open in the background"))
                }
                NavigationLink {
                    HelpArticleView(article: .calls)
                } label: {
                    ToolRow(symbol: "phone.fill", tint: .brandFill, title: Text("How calls work"),
                            subtitle: Text("Answer here; talk here too with Talk on iPhone", tableName: "CallAudio"))
                }
                // Cloud mode (D-092): the same plain-words explanation as before turning it on.
                if RemoteFeature.isAvailable {
                    NavigationLink {
                        CloudModeInfoPage()
                    } label: {
                        ToolRow(symbol: "icloud.fill", tint: .brandBlue, title: Text("How Cloud mode works", tableName: "RemoteMode"),
                                subtitle: Text("Leave the Android phone at home, on charge and on Wi-Fi.", tableName: "RemoteMode"))
                    }
                    NavigationLink {
                        RouterHelpView(remote: AppModel.shared.link.remote)
                    } label: {
                        ToolRow(symbol: "wifi.router", tint: .brandBlue, title: Text("Allow calls from far away", tableName: "RemoteMode"),
                                subtitle: Text("Let your home router make the direct connection", tableName: "RemoteMode"))
                    }
                    // D-106: the relay the user runs, for a home whose router lets nothing in.
                    NavigationLink {
                        OwnServerGuideView()
                    } label: {
                        ToolRow(symbol: "server.rack", tint: .brandBlue, title: Text("Set up your own server", tableName: "RemoteMode"),
                                subtitle: Text("Calls from far away when your router lets nothing in", tableName: "RemoteMode"))
                    }
                }
            } header: {
                Text("Guides")
            }
            Section {
                ForEach(FAQ.all) { item in
                    DisclosureGroup {
                        Text(item.answer, tableName: item.table)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .padding(.vertical, 4)
                    } label: {
                        Text(item.question, tableName: item.table).font(.body.weight(.medium))
                    }
                }
            } header: {
                Text("Questions")
            }
            Section {
                ForEach(Troubleshooting.all) { item in
                    DisclosureGroup {
                        VStack(alignment: .leading, spacing: 8) {
                            ForEach(Array(item.steps.enumerated()), id: \.offset) { index, step in
                                HStack(alignment: .top, spacing: 8) {
                                    Text(verbatim: "\(index + 1).").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                                    Text(step).font(.subheadline)
                                }
                            }
                        }
                        .padding(.vertical, 4)
                    } label: {
                        Label {
                            Text(item.problem).font(.body.weight(.medium))
                        } icon: {
                            Image(systemName: item.symbol).foregroundStyle(Color.brandAmber)
                        }
                    }
                }
            } header: {
                Text("Troubleshooting")
            }
            Section {
                Link(destination: AppInfo.telegramGroupURL) {
                    Label("Help group on Telegram", systemImage: "bubble.left.and.bubble.right")
                }
                Link(destination: AppInfo.website) {
                    Label {
                        Text(verbatim: "getpairline.com")
                    } icon: {
                        Image(systemName: "safari")
                    }
                }
            } footer: {
                Text("Still stuck? Ask in our Telegram help group, or see the website. Opening one of these links is the only time Pairline sends you online, and only when you tap.")
            }
        }
        .navigationTitle(Text("Help"))
    }
}

struct HelpArticleView: View {
    enum Article {
        case ways
        case setup
        case keepAlive
        case forceQuit
        case calls
    }

    let article: Article

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                switch article {
                case .ways: ways
                case .setup: setup
                case .keepAlive: keepAlive
                case .forceQuit: forceQuit
                case .calls: calls
                }
            }
            .padding(20)
        }
        .background(Color(.systemGroupedBackground))
        .navigationBarTitleDisplayMode(.inline)
    }

    /// Nearby or Cloud mode, in the same words as the first run and pairing (D-099).
    private var ways: some View {
        Group {
            Text("Nearby or Cloud mode?", tableName: "Connection").font(.largeTitle.weight(.bold))
            HelpCard(symbol: ConnectionWords.symbol(.nearby), title: ConnectionWords.title(.nearby),
                     text: ConnectionWords.oneLiner(.nearby) + Text(verbatim: " ") + ConnectionWords.example(.nearby))
            HelpCard(symbol: ConnectionWords.symbol(.cloud), title: ConnectionWords.title(.cloud),
                     text: ConnectionWords.oneLiner(.cloud) + Text(verbatim: " ") + ConnectionWords.example(.cloud)
                        + Text(verbatim: " ") + Text("Turn it on in Settings › Connection while the phones are together.", tableName: "Connection"))
            HelpCard(symbol: "arrow.triangle.2.circlepath", title: Text("Both at once", tableName: "Connection"),
                     text: ConnectionWords.bothCanBeOn)
            HelpCard(symbol: "checklist", title: Text("What Cloud mode needs", tableName: "Connection"),
                     text: Text("The Android phone on its charger and home Wi-Fi, and this iPhone signed in to iCloud. For calls, your home router lets this iPhone in: most do it by themselves, and Settings › Connection › Advanced shows how otherwise.", tableName: "Connection"))
            HelpCard(symbol: "message.badge", title: Text("Texts while you're away", tableName: "Connection"),
                     text: Text("In Cloud mode, texts and one-time codes go both ways, through your own iCloud. A text from this iPhone goes out within a few seconds while the Android phone is on its charger, and within about 10 seconds on battery: it checks iCloud that often while Cloud mode is on. Calls from far away need the direct connection to your home.", tableName: "Connection"))
            NavigationLink {
                RouterHelpView(remote: AppModel.shared.link.remote)
            } label: {
                ToolRow(symbol: "wifi.router", tint: .brandBlue, title: Text("Allow calls from far away", tableName: "RemoteMode"),
                        subtitle: Text("Your router, port forwarding, and when the provider is the reason", tableName: "Connection"))
                    .card()
            }
            .buttonStyle(.plain)
            HelpCard(symbol: "lock.fill", title: Text("Private either way", tableName: "Connection"),
                     text: Text("Nearby, everything goes straight between your phones. With Cloud mode it passes through your own iCloud, locked so only your two phones can open it. There's no Pairline server.", tableName: "Connection"))
        }
    }

    private var setup: some View {
        Group {
            Text("Set up the Android phone").font(.largeTitle.weight(.bold))
            TwoPhonesIllustration(state: .connected, height: 110).padding(.horizontal, 30)
            Step(number: 1, title: Text("Install Pairline Bridge"),
                 text: Text("On the Android phone that holds your SIM, open getpairline.com and download Pairline Bridge. It's free."))
            Step(number: 2, title: Text("Allow what it asks for"),
                 text: Text("Pairline Bridge asks for SMS, phone, Bluetooth and notification access so it can relay messages and calls. Each permission is explained in the app."))
            Step(number: 3, title: Text("Keep it running"),
                 text: Text("Allow it to run without battery restrictions when Android asks. Some phones have extra autostart settings; the bridge app shows where they are."))
            Step(number: 4, title: Text("Scan the pairing code"),
                 text: Text("Tap Pair in Pairline Bridge to show a QR code, then scan it from this iPhone. The code works once, for 5 minutes."))
        }
    }

    private var keepAlive: some View {
        Group {
            Text("Keep the link alive").font(.largeTitle.weight(.bold))
            Text("Android may stop apps in the background to save battery. These settings keep Pairline Bridge connected all day.")
                .foregroundStyle(.secondary)
            HelpCard(symbol: "battery.100", title: Text("Battery optimization"),
                     text: Text("In Pairline Bridge, tap Battery and choose Allow so Android doesn't stop it."))
            HelpCard(symbol: "power", title: Text("Autostart (Xiaomi, Oppo, Vivo, Huawei)"),
                     text: Text("Turn on Autostart for Pairline Bridge in the phone's security or battery app."))
            HelpCard(symbol: "lock.rotation", title: Text("Lock it in recent apps"),
                     text: Text("On many Android phones you can lock Pairline Bridge in the recent-apps screen so cleanups skip it."))
            HelpCard(symbol: "antenna.radiowaves.left.and.right", title: Text("Bluetooth on both phones"),
                     text: Text("Keep Bluetooth on and the phones within about 10 meters. Walls and bags shorten the range."))
            HelpCard(symbol: "bolt.fill", title: Text("Charge the bridge phone"),
                     text: Text("Pairline warns you when the bridge phone's battery runs low."))
        }
    }

    private var forceQuit: some View {
        Group {
            Text("Don't swipe Pairline away").font(.largeTitle.weight(.bold))
            HelpCard(symbol: "rectangle.stack.badge.minus", title: Text("An iOS rule"),
                     text: Text("If you swipe Pairline up in the app switcher, iOS stops it and won't wake it for Bluetooth until you open it again. Messages and calls wait until then."))
            HelpCard(symbol: "checkmark.circle", title: Text("What to do"),
                     text: Text("Just leave Pairline in the background. iOS wakes it when the bridge phone has something new, without draining your battery."))
            HelpCard(symbol: "arrow.clockwise", title: Text("After a restart"),
                     text: Text("After you restart the iPhone, unlock it once. Pairline reconnects on its own."))
        }
    }

    private var calls: some View {
        Group {
            Text("How calls work").font(.largeTitle.weight(.bold))
            HelpCard(symbol: "phone.arrow.down.left", title: Text("Incoming calls"),
                     text: Text("Calls to your Android number ring on this iPhone with the normal iPhone call screen, even when it's locked. Answer or decline here."))
            HelpCard(symbol: "speaker.wave.2", title: Text("Where the audio is", tableName: "CallAudio"),
                     text: Text("Normally you control calls from the iPhone and talk on the Android phone. With Talk on iPhone set up in the bridge app (Android 11 or later, Wireless debugging), calls you take here use this iPhone's microphone and speaker, over Bluetooth or Wi-Fi.", tableName: "CallAudio"))
            HelpCard(symbol: "arrow.clockwise", title: Text("After the Android phone restarts", tableName: "CallAudio"),
                     text: Text("Android turns Wireless debugging off at every restart, so call audio goes back to the Android phone. The bridge app turns it back on by itself as soon as that phone is on Wi-Fi, and starts its helper. Calls keep ringing here meanwhile.", tableName: "CallAudio"))
            HelpCard(symbol: "phone.arrow.up.right", title: Text("Making calls"),
                     text: Text("Call from the keypad, contacts or recents. Choose the SIM on dual-SIM phones."))
            HelpCard(symbol: "clock.arrow.circlepath", title: Text("iPhone Recents"),
                     text: Text("Bridged calls stay out of iPhone Recents unless you turn that on in Settings, because Recents can sync to iCloud."))
        }
    }
}

private struct Step: View {
    let number: Int
    let title: Text
    let text: Text

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Text(verbatim: "\(number)")
                .font(.headline)
                .foregroundStyle(.white)
                .frame(width: 30, height: 30)
                .background(Circle().fill(Color.brandFill))
            VStack(alignment: .leading, spacing: 4) {
                title.font(.headline)
                text.font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
        .card()
    }
}

struct HelpCard: View {
    let symbol: String
    let title: Text
    let text: Text

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(Color.brandBlue)
                .frame(width: 30)
            VStack(alignment: .leading, spacing: 4) {
                title.font(.headline)
                text.font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
        .card()
    }
}

struct FAQ: Identifiable {
    let id: Int
    let question: LocalizedStringKey
    let answer: LocalizedStringKey
    /// String table; nil is the shared Localizable catalog. Call audio and internet sharing have their own tables.
    var table: String?

    /// Where Cloud mode exists (D-092, D-099), the answers about servers and distance say what it changes, and one more
    /// answers why texts work but calls from far away don't.
    static var all: [FAQ] {
        let cloud = RemoteFeature.isAvailable
        var items = base(cloud: cloud)
        if cloud {
            items.insert(FAQ(id: 13, question: "Texts work, but calls from far away don't",
                             answer: "Calls from far away need a direct connection to the Android phone at home, through your home router. If Settings › Connection › Advanced says it's blocked by carrier-grade NAT, your internet provider shares one address among many homes and no router setting helps: ask the provider for a public IPv4 address, or turn on IPv6 if they offer it. Otherwise turn on UPnP or NAT-PMP in the router, or forward TCP port 47830 to the Android phone. Step by step: Help › Allow calls from far away.",
                             table: "Connection"), at: 3)
            items.insert(FAQ(id: 14, question: "Can I use my own server?",
                             answer: "Yes, for calls from far away: if your home router lets nothing in, run Pairline's small server on a server of your own and add it in Settings › Connection › Advanced › Your own server. It only passes along locked data between your two phones. Texts and codes keep coming through your own iCloud. Step by step: Help › Set up your own server.",
                             table: "RemoteMode"), at: 4)
        }
        return items
    }

    private static func base(cloud: Bool) -> [FAQ] {
        [
        FAQ(id: 1, question: "What is Pairline?",
            answer: "Pairline lets this iPhone use the SIM card in your Android phone: messages, calls, contacts, call history and USSD codes, over an encrypted link between your two phones."),
        cloud
            ? FAQ(id: 2, question: "Does my data go through a server?",
                  answer: "No. Nearby, the phones talk directly over Bluetooth and Wi-Fi, encrypted end to end. With Cloud mode, locked records pass through your own iCloud, which only your two phones can open. Pairline has no servers and no accounts, and it contains no analytics or ads.",
                  table: "Connection")
            : FAQ(id: 2, question: "Does my data go through a server?",
                  answer: "No. The phones talk directly over Bluetooth, encrypted end to end. Pairline has no servers and no accounts, and it contains no analytics or ads."),
        cloud
            ? FAQ(id: 3, question: "Do I need both phones nearby?",
                  answer: "No. Near each other, the phones talk over Bluetooth and Wi-Fi. If the Android phone stays home, turn on Cloud mode in Settings › Connection while they're together: calls, texts and codes then reach this iPhone anywhere with internet.",
                  table: "Connection")
            : FAQ(id: 3, question: "Do I need both phones nearby?",
                  answer: "Yes. The link uses Bluetooth, so keep the phones within about 10 meters. Leaving the Android phone at home while you're out isn't supported yet."),
        FAQ(id: 4, question: "Can I talk on the iPhone during calls?",
            answer: "Yes, with Talk on iPhone: set it up in the bridge app under Settings › Talk on iPhone. It needs Android 11 or later and Wireless debugging (no extra app, no root). The audio goes over Bluetooth, or over Wi-Fi if you choose it in Settings › Connection › Advanced › Call audio. Without it you answer here and talk on the Android phone.",
            table: "Connection"),
        FAQ(id: 9, question: "Why does call audio go back to the Android phone sometimes?",
            answer: "Android turns Wireless debugging off every time the Android phone restarts or leaves Wi-Fi. The bridge app turns it back on by itself as soon as that phone is on Wi-Fi, and starts its helper. Some phones also only allow listening: then you hear calls here and talk on the Android phone.",
            table: "CallAudio"),
        FAQ(id: 10, question: "Can this iPhone use the Android phone's internet?",
            answer: "Yes. Turn on Internet sharing in the bridge app, then use Settings › Internet sharing here: it gives a link for your Wi-Fi proxy settings and a one-tap Telegram setting. Traffic goes through the Android phone and its VPN, if one is on.",
            table: "NetShare"),
        FAQ(id: 11, question: "iOS says “No Internet Connection” on the Android phone’s hotspot",
            answer: "Use the Android phone’s normal hotspot (on Android: Settings › Hotspot & tethering, or Pairline Bridge › Settings › This phone’s hotspot) and join it here in Settings › Wi-Fi: it shares the Android phone’s internet, and Pairline connects over it too. Pairline’s own hotspot (named AndroidShare_…) only links the two phones, so iOS shows “No Internet Connection” on it. If the normal hotspot has no internet either, Pairline Bridge › Settings › This phone’s hotspot says why: mobile data is off there, or a VPN is on, which Android doesn’t share over a hotspot. For the VPN, use Settings › Internet sharing here.",
            table: "Hotspot"),
        FAQ(id: 12, question: "Can I turn on the Android phone's hotspot from here?",
            answer: "Yes, while Talk on iPhone is on in the bridge app: Settings › Internet sharing here switches the Android phone's hotspot, Bluetooth tethering, mobile data and data SIM. Then join the hotspot in Settings › Wi-Fi, or, for Bluetooth tethering, tap the Android phone in Settings › Bluetooth (pair once when asked). Bluetooth is slower but needs no Wi-Fi.",
            table: "NetShare"),
        FAQ(id: 5, question: "What happens after the free trial?",
            answer: "After 7 days, sending messages, calls, syncing and USSD stop until you buy Pairline once. Messages already on this iPhone stay readable, and demo mode, help and settings keep working."),
        FAQ(id: 6, question: "Is it a subscription?",
            answer: "No. Pairline is a one-time purchase. The Android app is free."),
        FAQ(id: 7, question: "Can I see the source code?",
            answer: "Yes. Pairline's source code is public so anyone can check what it does."),
        FAQ(id: 8, question: "Does Pairline work with group messages or MMS?",
            answer: "Yes, with the latest Pairline Bridge. Picture and group messages show here with their pictures and everyone in the group, and you can send a picture or a group message. Sending needs mobile data on the Android phone, and full pictures open over Wi-Fi."),
        ]
    }
}

struct Troubleshooting: Identifiable {
    let id: Int
    let symbol: String
    let problem: LocalizedStringKey
    let steps: [LocalizedStringKey]

    static let all: [Troubleshooting] = [
        Troubleshooting(id: 1, symbol: "antenna.radiowaves.left.and.right.slash", problem: "The iPhone can't find the bridge", steps: [
            "Make sure both phones have Bluetooth turned on.",
            "Open Pairline Bridge on the Android phone and make sure it says it's running.",
            "Bring the phones closer together.",
            "Turn Bluetooth off and on again on the Android phone.",
        ]),
        Troubleshooting(id: 2, symbol: "qrcode", problem: "Pairing fails", steps: [
            "Show a fresh code in Pairline Bridge; each code works once, for 5 minutes.",
            "Keep Pairline Bridge open on the screen while you scan.",
            "If you paired with another iPhone before, pairing again replaces it.",
        ]),
        Troubleshooting(id: 3, symbol: "message.badge", problem: "Messages arrive late or not at all", steps: [
            "Don't swipe Pairline away in the iPhone's app switcher.",
            "Exclude Pairline Bridge from battery optimization on the Android phone.",
            "Allow notifications for Pairline in iPhone Settings.",
            "Pull down in Messages to sync.",
        ]),
        Troubleshooting(id: 4, symbol: "phone.badge.waveform", problem: "Calls don't ring on the iPhone", steps: [
            "Check that call control is on in Pairline Bridge.",
            "Turn off Focus or Do Not Disturb, which can silence calls.",
            "Check the Bridge tab: Incoming call alerts should show a check mark.",
        ]),
        Troubleshooting(id: 5, symbol: "key.slash", problem: "It says the bridge doesn't recognize this iPhone", steps: [
            "The Android app's data was probably cleared, or it was paired with another iPhone.",
            "Show a new pairing code on the Android phone and scan it again.",
        ]),
    ]
}
