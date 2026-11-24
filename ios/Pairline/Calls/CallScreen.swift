import SwiftUI

/// The in-app call screen: incoming calls in demo mode, and every call placed or answered from the iPhone.
/// Call audio stays on the Android phone until call audio ships (M6), and the screen says so.
struct CallScreen: View {
    @EnvironmentObject private var calls: CallCenter
    @EnvironmentObject private var bridge: BridgeController
    @State private var showKeypad = false
    @State private var typed = ""

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

            // In protocol v0 call audio always stays on the Android phone; `call.audio` only switches its mute and
            // speaker. Streaming to the iPhone (M6) will come with its own capability.
            Label("Audio plays on your Android phone", systemImage: "speaker.wave.2.fill")
                .font(.footnote.weight(.medium))
                .foregroundStyle(.white.opacity(0.85))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Capsule().fill(.white.opacity(0.12)))
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
