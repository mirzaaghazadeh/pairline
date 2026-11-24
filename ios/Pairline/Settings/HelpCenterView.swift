import SwiftUI

/// Setup guide, staying connected, limits and troubleshooting. Everything is bundled in the app.
struct HelpCenterView: View {
    var body: some View {
        List {
            Section {
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
                     text: Text("Normally you control calls from the iPhone and talk on the Android phone. With Talk on iPhone set up in the bridge app (Android 10 or later, Wireless debugging, the same Wi-Fi), calls you take here use this iPhone's microphone and speaker.", tableName: "CallAudio"))
            HelpCard(symbol: "arrow.clockwise", title: Text("After the Android phone restarts", tableName: "CallAudio"),
                     text: Text("Android turns Wireless debugging off at every restart, so call audio goes back to the Android phone. The bridge app shows a notification: tap it and turn Wireless debugging on again, and the bridge starts its helper by itself. Calls keep ringing here meanwhile.", tableName: "CallAudio"))
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

    static let all: [FAQ] = [
        FAQ(id: 1, question: "What is Pairline?",
            answer: "Pairline lets this iPhone use the SIM card in your Android phone: messages, calls, contacts, call history and USSD codes, over an encrypted link between your two phones."),
        FAQ(id: 2, question: "Does my data go through a server?",
            answer: "No. The phones talk directly over Bluetooth, encrypted end to end. Pairline has no servers and no accounts, and it contains no analytics or ads."),
        FAQ(id: 3, question: "Do I need both phones nearby?",
            answer: "Yes. The link uses Bluetooth, so keep the phones within about 10 meters. Leaving the Android phone at home while you're out isn't supported yet."),
        FAQ(id: 4, question: "Can I talk on the iPhone during calls?",
            answer: "Yes, with Talk on iPhone: set it up in the bridge app under Settings › Talk on iPhone. It needs Android 10 or later, Wireless debugging (no extra app, no root) and both phones on the same Wi-Fi. Without it you answer here and talk on the Android phone.",
            table: "CallAudio"),
        FAQ(id: 9, question: "Why does call audio go back to the Android phone sometimes?",
            answer: "Android turns Wireless debugging off every time the Android phone restarts. The bridge app then shows a notification; tap it and turn Wireless debugging on again, and the bridge starts its helper by itself. Some phones also only allow listening: then you hear calls here and talk on the Android phone.",
            table: "CallAudio"),
        FAQ(id: 10, question: "Can this iPhone use the Android phone's internet?",
            answer: "Yes. Turn on Internet sharing in the bridge app, then use Settings › Internet sharing here: it gives a link for your Wi-Fi proxy settings and a one-tap Telegram setting. Traffic goes through the Android phone and its VPN, if one is on.",
            table: "NetShare"),
        FAQ(id: 11, question: "iOS says “No Internet Connection” on the Android phone’s hotspot",
            answer: "The hotspot Pairline Bridge starts only links your two phones: Android doesn’t let apps share the internet through it, so iOS shows “No Internet Connection” and this iPhone uses its own mobile data. If it has none, turn on the Android phone’s own hotspot instead (Settings › Hotspot & tethering on Android) and join that: it shares the internet, and Pairline connects over it too.",
            table: "Hotspot"),
        FAQ(id: 5, question: "What happens after the free trial?",
            answer: "After 7 days, sending messages, calls, syncing and USSD stop until you buy Pairline once. Messages already on this iPhone stay readable, and demo mode, help and settings keep working."),
        FAQ(id: 6, question: "Is it a subscription?",
            answer: "No. Pairline is a one-time purchase. The Android app is free."),
        FAQ(id: 7, question: "Can I see the source code?",
            answer: "Yes. Pairline's source code is public so anyone can check what it does."),
        FAQ(id: 8, question: "Does Pairline work with group messages or MMS?",
            answer: "Not yet. Pairline handles SMS text messages. Picture messages (MMS) are planned."),
    ]
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
