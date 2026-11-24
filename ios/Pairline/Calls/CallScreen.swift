import SwiftUI

/// The in-app call screen: incoming calls in demo mode, and every call placed or answered from the iPhone.
/// It says where the call's audio is: on this iPhone ("Talk on iPhone", M6) or on the Android phone.
struct CallScreen: View {
    @EnvironmentObject private var calls: CallCenter
    @EnvironmentObject private var bridge: BridgeController
    @State private var showKeypad = false
    @State private var typed = ""
    @State private var showAudioInfo = false

    private var audioLabel: Text {
        switch calls.audioRoute {
        case .iphone(talk: true): return Text("Audio on this iPhone", tableName: "CallAudio")
        case .iphone(talk: false): return Text("Listening here · talk on your Android phone", tableName: "CallAudio")
        case .connecting: return Text("Moving audio to this iPhone…", tableName: "CallAudio")
        case .bridge:
            // Set up on the bridge, but without Wi-Fi between the phones the audio can't come over: say so.
            if calls.audioNeedsWiFi { return Text("Audio on your Android phone: no Wi-Fi link to it", tableName: "CallAudio") }
            return Text("Audio plays on your Android phone")
        }
    }

    private var audioSymbol: String {
        switch calls.audioRoute {
        case .iphone: return "iphone.gen3"
        case .connecting: return "arrow.left.arrow.right"
        case .bridge: return "speaker.wave.2.fill"
        }
    }

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.07, green: 0.16, blue: 0.33), Color(red: 0.02, green: 0.04, blue: 0.09)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            if let call = calls.current {
                content(call)
            } else {
                Color.clear.onAppear { calls.showCallScreen = false }
            }
        }
        .preferredColorScheme(.dark)
        .onAppear { calls.callScreenVisible = true }
        .onDisappear { calls.callScreenVisible = false }
        .sheet(isPresented: $showAudioInfo) { CallAudioInfoSheet() }
        // Talk on iPhone is set up, but the phones aren't on the same Wi-Fi: say why the audio is on the other phone.
        .alert(Text("Why is the audio on the Android phone?", tableName: "CallAudio"), isPresented: $calls.wifiAlert) {
            Button { showAudioInfo = true } label: { Text("How to fix", tableName: "CallAudio") }
            Button(role: .cancel) {} label: { Text("OK", tableName: "CallAudio") }
            Button {
                UserDefaults.standard.set(true, forKey: CallCenter.wifiAlertMutedKey)
            } label: {
                Text("Don't show again", tableName: "CallAudio")
            }
        } message: {
            Text("Talk on iPhone is set up, but this iPhone isn't linked to the Android phone over Wi-Fi, which carries the call audio. Put both phones on the same Wi-Fi or join the Android phone's own hotspot. Already on the same Wi-Fi? Check iPhone Settings › Pairline › Local Network is on; guest Wi-Fi often keeps devices apart.", tableName: "CallAudio")
        }
        // The bridge tried and failed: say so here (with its reason) instead of a toast behind the call screen.
        .alert(Text("The audio stayed on the Android phone", tableName: "CallAudio"), isPresented: $calls.relayFailureAlert) {
            Button { showAudioInfo = true } label: { Text("How to fix", tableName: "CallAudio") }
            Button(role: .cancel) {} label: { Text("OK", tableName: "CallAudio") }
        } message: {
            (calls.relayFailureOnIPhone
                ? Text("This iPhone couldn't start the call's audio. Check that Pairline may use the microphone (iPhone Settings › Pairline), then try another call.", tableName: "CallAudio")
                : Text("Pairline Bridge couldn't move this call's audio to the iPhone. Update Pairline Bridge and try another call. If it keeps happening, open Pairline Bridge › Settings › Diagnostics, share the report and send it to us.", tableName: "CallAudio"))
                + Text(verbatim: calls.relayFailureReason.map { "\n\n\($0)" } ?? "")
        }
    }

    private func content(_ call: ActiveCall) -> some View {
        VStack(spacing: 0) {
            HStack {
                Button {
                    calls.showCallScreen = false
                } label: {
                    Image(systemName: "chevron.down")
                        .font(.title3.weight(.semibold))
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel(Text("Hide call screen"))
                Spacer()
                HStack(spacing: 6) {
                    if bridge.isDemo && !DebugSettings.marketingScreenshots {
                        Text("Demo").font(.caption.weight(.bold)).foregroundStyle(Color.brandAmber)
                    }
                    if let sim = call.sim, bridge.isDualSIM {
                        SIMBadge(slot: sim)
                        Text(bridge.simLabel(sim))
                    } else {
                        Text("Pairline")
                    }
                }
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.white.opacity(0.7))
                Spacer()
                Color.clear.frame(width: 44, height: 44)
            }
            .padding(.horizontal, 12)
            .foregroundStyle(.white)

            Spacer(minLength: 12)
            Avatar(name: call.name ?? call.number, size: 104)
                .shadow(color: .black.opacity(0.3), radius: 16, y: 8)
            Group {
                if let name = call.name, !name.isEmpty {
                    Text(verbatim: name)
                } else if call.number.isEmpty {
                    Text("Unknown caller")
                } else {
                    LTRText(call.number)
                }
            }
            .font(.rounded(.largeTitle, weight: .semibold))
            .foregroundStyle(.white)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .padding(.horizontal, 24)
            .padding(.top, 18)
            if call.name != nil, !call.number.isEmpty {
                LTRText(call.number)
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.65))
                    .padding(.top, 2)
            }
            statusLine(call)
                .font(.headline.weight(.medium))
                .foregroundStyle(.white.opacity(0.8))
                .padding(.top, 10)

            // Where the call's audio is: on this iPhone when the bridge relays it (call.media, D-047), otherwise on
            // the Android phone. Tapping explains how to set up Talk on iPhone.
            Button {
                showAudioInfo = true
            } label: {
                Label { audioLabel } icon: { Image(systemName: audioSymbol) }
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(.white.opacity(0.85))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(Capsule().fill(.white.opacity(0.12)))
            }
            .buttonStyle(.plain)
            .padding(.top, 18)
            Spacer(minLength: 16)

            if showKeypad && call.state == .active {
                keypad
            } else if call.state != .ringing || call.isOutgoing {
                controls(call)
            }
            Spacer(minLength: 16)
            actions(call)
                .padding(.bottom, 36)
        }
    }

    private func statusLine(_ call: ActiveCall) -> some View {
        Group {
            switch call.state {
            case .ringing:
                Text(call.isOutgoing ? "Ringing…" : "Incoming call")
            case .dialing:
                Text("Calling…")
            case .active:
                if let start = call.connectedAt {
                    Text(start, style: .timer).monospacedDigit()
                } else {
                    Text("Connected")
                }
            case .held:
                Text("On hold")
            case .ended(let reason):
                switch reason {
                case "busy"?: Text("Busy")
                case "missed"?: Text("No answer")
                case "rejected"?: Text("Declined")
                case "failed"?: Text("Call failed")
                default: Text("Call ended")
                }
            }
        }
    }

    private func controls(_ call: ActiveCall) -> some View {
        HStack(spacing: 28) {
            CallControlButton(symbol: call.muted ? "mic.slash.fill" : "mic.slash", title: Text("Mute"),
                              isOn: call.muted, isAvailable: calls.canAudio) {
                calls.setMuted(!call.muted)
            }
            CallControlButton(symbol: "circle.grid.3x3.fill", title: Text("Keypad"), isOn: showKeypad,
                              isAvailable: calls.canDTMF) {
                if calls.canDTMF {
                    withAnimation { showKeypad = true }
                } else {
                    calls.sendDigit("")
                }
            }
            .disabled(call.state != .active)
            CallControlButton(symbol: "speaker.wave.3.fill", title: Text("Speaker"), isOn: call.speaker,
                              isAvailable: calls.canAudio) {
                calls.setSpeaker(!call.speaker)
            }
        }
        .padding(.horizontal, 24)
    }

    private var keypad: some View {
        VStack(spacing: 12) {
            Text(verbatim: typed.isEmpty ? " " : typed)
                .font(.title2.monospacedDigit())
                .foregroundStyle(.white)
                .environment(\.layoutDirection, .leftToRight)
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(72), spacing: 18), count: 3), spacing: 12) {
                ForEach(["1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"], id: \.self) { digit in
                    Button {
                        typed.append(digit)
                        calls.sendDigit(digit)
                        Haptics.tap()
                    } label: {
                        Text(verbatim: digit)
                            .font(.system(size: 28, design: .rounded))
                            .foregroundStyle(.white)
                            .frame(width: 66, height: 66)
                            .background(Circle().fill(.white.opacity(0.14)))
                    }
                }
            }
            .environment(\.layoutDirection, .leftToRight)
            Button("Hide keypad") { withAnimation { showKeypad = false } }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white.opacity(0.8))
        }
    }

    @ViewBuilder private func actions(_ call: ActiveCall) -> some View {
        if call.state == .ringing && !call.isOutgoing {
            HStack(spacing: 80) {
                RoundCallButton(symbol: "phone.down.fill", color: .brandRed, title: Text("Decline")) {
                    Haptics.tap()
                    calls.hangUp()
                }
                RoundCallButton(symbol: "phone.fill", color: .brandGreen, title: Text("Accept")) {
                    Haptics.success()
                    calls.answer()
                }
            }
            // Decline stays on the left and Accept on the right in every language, like the iPhone call screen.
            .environment(\.layoutDirection, .leftToRight)
        } else if case .ended = call.state {
            RoundCallButton(symbol: "phone.down.fill", color: .gray, title: Text("Ended")) {}
                .disabled(true)
        } else {
            RoundCallButton(symbol: "phone.down.fill", color: .brandRed, title: Text("End")) {
                Haptics.tap()
                calls.hangUp()
            }
        }
    }
}

struct CallControlButton: View {
    let symbol: String
    let title: Text
    let isOn: Bool
    let isAvailable: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: symbol)
                    .font(.system(size: 24, weight: .medium))
                    .foregroundStyle(isOn ? Color.black : Color.white)
                    .frame(width: 72, height: 72)
                    .background(Circle().fill(isOn ? Color.white : Color.white.opacity(0.14)))
                    .opacity(isAvailable ? 1 : 0.45)
                title
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.white.opacity(isAvailable ? 0.9 : 0.5))
            }
        }
        .buttonStyle(.plain)
        .accessibilityValue(isOn ? Text("On") : Text("Off"))
    }
}

struct RoundCallButton: View {
    let symbol: String
    let color: Color
    let title: Text
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: symbol)
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 76, height: 76)
                    .background(Circle().fill(color))
                    .shadow(color: color.opacity(0.4), radius: 12, y: 6)
                title
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.white.opacity(0.85))
            }
        }
        .buttonStyle(.plain)
    }
}
