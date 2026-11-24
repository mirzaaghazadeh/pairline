import SwiftUI

/// Strings for call audio live in their own table, `CallAudio.xcstrings` (D-047).
private func t(_ key: LocalizedStringKey) -> Text { Text(key, tableName: "CallAudio") }

/// Explains where call audio is and how to get it on this iPhone ("Talk on iPhone", D-047).
struct CallAudioInfoSheet: View {
    @EnvironmentObject private var calls: CallCenter
    @EnvironmentObject private var bridge: BridgeController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label { statusText } icon: {
                        Image(systemName: calls.audioOnIPhone ? "iphone.gen3" : "speaker.wave.2.fill")
                    }
                    .font(.body)
                }
                if !calls.audioOnIPhone {
                    Section {
                        step(1, "On the Android phone, open Pairline Bridge › Settings › Talk on iPhone.")
                        step(2, "Install and start Shizuku, a free app, and allow Pairline in it. The bridge app shows each step.")
                        step(3, "Keep both phones on the same Wi-Fi. Calls you take on this iPhone then use its microphone and speaker.")
                    } header: {
                        t("Talk on your iPhone")
                    } footer: {
                        t("Android keeps call audio away from apps, so this needs Android 10 or later and Shizuku. Some phones only allow listening; then you hear the call here and talk on the Android phone.")
                    }
                }
                Section {
                    t("Call audio goes between your two phones over your Wi-Fi, encrypted end to end. It's never recorded or stored.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle(t("Call audio"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var statusText: Text {
        switch calls.audioRoute {
        case .iphone(talk: true): return t("This call's audio is on this iPhone.")
        case .iphone(talk: false):
            return t("You hear this call here. Talk on your Android phone: it can't take audio from the iPhone.")
        case .connecting: return t("Moving the audio to this iPhone…")
        case .bridge:
            if bridge.has(.callMedia) {
                return t("Your bridge is set up for Talk on iPhone, but the phones aren't on the same Wi-Fi right now.")
            }
            return t("This call's audio plays on your Android phone.")
        }
    }

    private func step(_ number: Int, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(verbatim: "\(number)")
                .font(.rounded(.subheadline, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 24, height: 24)
                .background(Circle().fill(Color.accentColor))
            t(text)
                .font(.subheadline)
        }
        .padding(.vertical, 2)
    }
}

/// After a call whose audio was on this iPhone: one tap tells the bridge what worked, so it settles on the capture
/// and microphone settings that suit that Android phone.
struct CallAudioFeedbackModifier: ViewModifier {
    @ObservedObject var calls: CallCenter

    func body(content: Content) -> some View {
        content.confirmationDialog(
            t("How did the call sound?"),
            isPresented: Binding(get: { calls.askAudioFeedback }, set: { if !$0 { calls.askAudioFeedback = false } }),
            titleVisibility: .visible
        ) {
            Button { calls.sendAudioFeedback(heard: true, heardMe: true) } label: { t("Both of us heard each other") }
            Button { calls.sendAudioFeedback(heard: false, heardMe: nil) } label: { t("I couldn't hear them") }
            Button { calls.sendAudioFeedback(heard: true, heardMe: false) } label: { t("They couldn't hear me") }
            Button(role: .cancel) { calls.askAudioFeedback = false } label: { t("Not now") }
        } message: {
            t("Your answer helps Pairline pick what works on your Android phone. Nothing else is sent.")
        }
    }
}
