import AVKit
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

    /// The call's audio is on this iPhone's earpiece (not the speaker): the screen should turn off at the ear.
    private var earpieceCall: Bool {
        calls.audioOnIPhone && calls.current?.speaker != true
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
        .onAppear {
            calls.callScreenVisible = true
            UIDevice.current.isProximityMonitoringEnabled = earpieceCall
        }
        .onDisappear {
            calls.callScreenVisible = false
            UIDevice.current.isProximityMonitoringEnabled = false
        }
        // Screen off at the ear, like a native call, while the audio is on this iPhone's earpiece. CallKit does this
        // for its own screen only.
        .onValueChange(earpieceCall) { UIDevice.current.isProximityMonitoringEnabled = $0 }
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

    /// One column on a phone. On a large display (iPhone Duo open, iPad) the caller and the controls get a pane each,
    /// on either side of the fold, or the caller above and the controls below when the screen is taller than wide.
    private func content(_ call: ActiveCall) -> some View {
        FoldAwarePanes {
            VStack(spacing: 0) {
                header(call)
                Spacer(minLength: 12)
                callerInfo(call, avatarSize: 96)
                Spacer(minLength: 12)
                if captionsAvailable { CaptionsPanel().padding(.horizontal, 16).padding(.bottom, 12) }
            }
        } second: {
            // The keypad or controls and the call buttons as one group, centred in their half, level with the caller:
            // pushed to the bottom they left a short, wide half empty (D-063). They sit side by side when the half is
            // too short to stack them.
            ViewThatFits(in: .vertical) {
                VStack(spacing: 32) {
                    middleControls(call)
                    actions(call)
                }
                HStack(spacing: 36) {
                    middleControls(call)
                    actions(call)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.horizontal, 12)
        } single: {
            VStack(spacing: 0) {
                header(call)
                Spacer(minLength: 12)
                callerInfo(call, avatarSize: 104)
                Spacer(minLength: 16)
                if captionsAvailable { CaptionsPanel().padding(.horizontal, 16).padding(.bottom, 12) }
                middleControls(call)
                Spacer(minLength: 16)
                actions(call)
                    .padding(.bottom, 36)
            }
        }
    }

    private func header(_ call: ActiveCall) -> some View {
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
            // Live captions of the caller (D-087): only while the call's audio is on this iPhone.
            if captionsAvailable {
                CaptionsButton()
            } else {
                Color.clear.frame(width: 44, height: 44)
            }
        }
        .padding(.horizontal, 12)
        .foregroundStyle(.white)
    }

    private var captionsAvailable: Bool {
        if case .iphone = calls.audioRoute { return !bridge.isDemo }
        return false
    }

    /// The caller, the call's state and where its audio is.
    @ViewBuilder private func callerInfo(_ call: ActiveCall, avatarSize: CGFloat) -> some View {
        Avatar(name: call.name ?? call.number, size: avatarSize)
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
        // the Android phone. Tapping explains how to set up Talk on iPhone. Left out of App Store screenshots, where
        // the demo has no Wi-Fi link to bring the audio over (with Talk on iPhone it comes to the iPhone).
        if !DebugSettings.marketingScreenshots {
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
        }
        if let other = calls.other { otherCall(other) }
    }

    /// The second call (call waiting, D-074): answer or decline it while it rings, switch to it or end it on hold.
    private func otherCall(_ other: ActiveCall) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Group {
                    if other.state == .ringing {
                        Text("Second call", tableName: "PhoneTools")
                    } else {
                        Text("On hold")
                    }
                }
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white.opacity(0.7))
                Text(verbatim: other.displayName)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if other.state == .ringing {
                Button { calls.endOther() } label: { Image(systemName: "phone.down.fill") }
                    .buttonStyle(.borderedProminent).tint(.brandRed)
                    .accessibilityLabel(Text("Decline"))
                Button { calls.answerOther() } label: { Image(systemName: "phone.fill") }
                    .buttonStyle(.borderedProminent).tint(.brandGreen)
                    .accessibilityLabel(Text("Accept"))
            } else {
                if other.state == .held && calls.canHold {
                    Button { calls.swapCalls() } label: { Text("Swap", tableName: "PhoneTools") }
                        .buttonStyle(.bordered).tint(.white)
                }
                Button { calls.endOther() } label: { Image(systemName: "phone.down.fill") }
                    .buttonStyle(.borderedProminent).tint(.brandRed)
                    .accessibilityLabel(Text("End"))
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16).fill(.white.opacity(0.12)))
        .padding(.horizontal, 24)
        .padding(.top, 14)
    }

    /// The keypad while it's open, otherwise mute, keypad and speaker (not while an incoming call rings).
    @ViewBuilder private func middleControls(_ call: ActiveCall) -> some View {
        if showKeypad && call.state == .active {
            keypad
        } else if call.state != .ringing || call.isOutgoing {
            controls(call)
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
        VStack(spacing: 22) {
            firstRow(call)
            HStack(spacing: 28) {
                // D-074: hold on the bridge phone, and where this iPhone plays the call (headset, car, speaker).
                CallControlButton(symbol: "pause.fill", title: Text("Hold", tableName: "PhoneTools"),
                                  isOn: call.state == .held, isAvailable: calls.canHold) {
                    calls.setHeld(call.state != .held)
                }
                .disabled(call.state != .active && call.state != .held)
                if calls.audioOnIPhone { AudioRouteButton() }
            }
        }
    }

    private func firstRow(_ call: ActiveCall) -> some View {
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

/// Moves the call's audio on this iPhone to a Bluetooth headset, the car or the speaker and back: iOS's own route
/// picker (D-074).
struct AudioRouteButton: View {
    var body: some View {
        VStack(spacing: 8) {
            RoutePicker()
                .frame(width: 72, height: 72)
                .background(Circle().fill(Color.white.opacity(0.14)))
            Text("Audio", tableName: "PhoneTools")
                .font(.caption.weight(.medium))
                .foregroundStyle(.white.opacity(0.9))
        }
    }

    private struct RoutePicker: UIViewRepresentable {
        func makeUIView(context: Context) -> AVRoutePickerView {
            let picker = AVRoutePickerView()
            picker.tintColor = .white
            picker.activeTintColor = .white
            picker.prioritizesVideoDevices = false
            return picker
        }

        func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
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
