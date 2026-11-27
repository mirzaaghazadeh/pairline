import SwiftUI

// Talk on iPhone's state on the bridge (D-107; PROTOCOL.md "Talk on iPhone's state"): where calls play, and when they
// can't play on this iPhone, why and what to do. Strings live in `CallAudio.xcstrings` with the rest of call audio.

private func t(_ key: String) -> Text { Text(LocalizedStringKey(key), tableName: "CallAudio") }

/// What to say about one `status.talk`: on the Bridge tab (title and detail) and on the call screen (one short line).
struct TalkPresentation {
    enum Action: Equatable {
        /// `talk.setup`: the bridge opens its setup screen (setup still to do, or the pairing to redo).
        case setUp
        /// `talk.setup` too: the bridge turns the switch back on.
        case turnOn
    }

    let symbol: String
    let tint: Color
    let title: Text
    let detail: Text?
    /// For the call screen while the voice stays on the Android phone; nil when it can move here.
    let callLine: Text?
    let action: Action?
    /// Calls play on this iPhone.
    let ready: Bool

    /// `callMedia`: the bridge lists `call.media` now. It's the authority: a `ready` without it reads as starting.
    init(_ talk: TalkState, callMedia: Bool) {
        let android = "speaker.wave.2.fill"
        switch talk.state {
        case .ready where callMedia:
            symbol = "iphone.gen3"
            tint = .brandGreen
            title = t("Calls play on this iPhone")
            detail = t("When you answer here, you hear and talk on this iPhone.")
            callLine = nil
            action = nil
            ready = true
            return
        case .ready, .starting:
            symbol = "hourglass"
            tint = .secondary
            title = t("Getting ready on your Android phone…")
            detail = t("Talk on iPhone is starting there. Calls play on this iPhone in a moment.")
            callLine = t("Talk on iPhone is starting on your Android phone.")
            action = nil
        case .setup:
            symbol = android
            tint = .brandAmber
            title = t("Calls play on your Android phone")
            detail = t("Set up Talk on iPhone there once (about 2 minutes) to hear and talk here.")
            callLine = t("The voice is on your Android phone: Talk on iPhone isn't set up there yet.")
            action = .setUp
        case .off:
            symbol = android
            tint = .brandAmber
            title = t("Talk on iPhone is off on your Android phone")
            detail = t("Calls play on your Android phone until you turn it on.")
            callLine = t("The voice is on your Android phone: Talk on iPhone is off there.")
            action = .turnOn
        case .waiting, .failed:
            symbol = android
            tint = .brandAmber
            title = talk.state == .waiting ? t("Calls play on your Android phone for now") : t("Calls play on your Android phone")
            switch talk.reason {
            case .wifi?:
                detail = t("Starts when your Android phone is on Wi-Fi.")
                callLine = t("The voice is on your Android phone until it's on Wi-Fi.")
                action = nil
            case .debugging?:
                detail = t("Wireless debugging is off on your Android phone; Pairline turns it back on when it can. If this doesn't change, open Pairline Bridge there.")
                callLine = t("The voice is on your Android phone: Wireless debugging is off there.")
                action = nil
            case .pairing?:
                detail = t("Pair again on your Android phone: Pairline Bridge › Talk on iPhone.")
                callLine = t("The voice is on your Android phone: pair Talk on iPhone again there.")
                action = .setUp
            case .root?:
                detail = t("Root access was refused on your Android phone.")
                callLine = t("The voice is on your Android phone: root access was refused there.")
                action = nil
            case .other?, nil:
                detail = t("Talk on iPhone didn't start on your Android phone. Open Pairline Bridge there to see why.")
                callLine = t("The voice is on your Android phone: Talk on iPhone didn't start there.")
                action = nil
            }
        case .unsupported:
            symbol = android
            tint = .secondary
            title = t("Calls play on your Android phone")
            detail = t("This Android phone can't send call audio to the iPhone (needs Android 11).")
            callLine = t("The voice is on your Android phone: it can't send call audio to the iPhone.")
            action = nil
        }
        ready = false
    }
}

/// Bridge tab: where calls play (D-107). Only for a bridge that reports `status.talk`; older ones keep the capability
/// list's "Hear calls on iPhone" line.
struct TalkCard: View {
    @EnvironmentObject private var bridge: BridgeController
    let talk: TalkState

    var body: some View {
        let p = TalkPresentation(talk, callMedia: bridge.has(.callMedia))
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: p.symbol)
                    .font(.title2)
                    .foregroundStyle(p.tint)
                    .frame(width: 30)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    p.title
                        .font(.headline)
                        .fixedSize(horizontal: false, vertical: true)
                    if let detail = p.detail {
                        detail
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .combine)
            if let action = p.action {
                TalkSetupButton(action: action)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }
}

/// "Set up on Android phone" / "Turn on" (D-107): sends `talk.setup`, then says what happens on the Android phone, or
/// why it couldn't. `onDark`: on the call screen's dark background.
struct TalkSetupButton: View {
    @EnvironmentObject private var bridge: BridgeController
    let action: TalkPresentation.Action
    var onDark = false
    @State private var sending = false
    @State private var sent = false
    @State private var failure: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button(action: send) {
                HStack(spacing: 8) {
                    if sending {
                        ProgressView()
                            .tint(onDark ? .white : nil)
                    }
                    Group {
                        switch action {
                        case .setUp: t("Set up on Android phone")
                        case .turnOn: t("Turn on")
                        }
                    }
                    .font(.subheadline.weight(.semibold))
                }
            }
            .buttonStyle(.borderedProminent)
            .tint(onDark ? .white.opacity(0.22) : .brandFill)
            .disabled(sending)
            if sent {
                Label {
                    t("Pairline Bridge on your Android phone shows what to do.")
                } icon: {
                    Image(systemName: "checkmark.circle.fill")
                }
                .font(.footnote)
                .foregroundStyle(onDark ? Color.white.opacity(0.85) : Color.brandGreen)
                .fixedSize(horizontal: false, vertical: true)
            }
            if let failure {
                Label {
                    Text(verbatim: failure)
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill")
                }
                .font(.footnote)
                .foregroundStyle(onDark ? Color.white.opacity(0.85) : Color.brandRed)
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func send() {
        sending = true
        failure = nil
        Task {
            do {
                try await bridge.requestTalkSetup()
                sent = true
                Haptics.success()
            } catch {
                sent = false
                failure = error.localizedDescription
                Haptics.error()
            }
            sending = false
        }
    }
}

/// The call screen's line while a call's voice stays on the Android phone because Talk on iPhone isn't ready there
/// (D-107), with "Set up on Android phone" where a step is missing. The Wi-Fi case of an older bridge keeps its own
/// alert (`audioNeedsWiFi`).
struct TalkCallLine: View {
    @EnvironmentObject private var calls: CallCenter
    @EnvironmentObject private var bridge: BridgeController

    var body: some View {
        if !bridge.isDemo, case .bridge = calls.audioRoute, !calls.audioNeedsWiFi, !bridge.has(.callMedia),
           let talk = bridge.talk {
            let p = TalkPresentation(talk, callMedia: false)
            if let line = p.callLine {
                VStack(spacing: 10) {
                    line
                        .font(.footnote)
                        .foregroundStyle(.white.opacity(0.8))
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                    if let action = p.action {
                        TalkSetupButton(action: action, onDark: true)
                    }
                }
                .padding(.horizontal, 28)
            }
        }
    }
}

/// Once per pairing, when the bridge first says Talk on iPhone isn't set up (D-107): offers to start it there. Not in
/// demo mode, never over the pairing flow or a call.
struct TalkSetupPromptModifier: ViewModifier {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var bridge: BridgeController
    @State private var show = false

    func body(content: Content) -> some View {
        content
            .onAppear(perform: check)
            .onValueChange(bridge.talk) { _ in check() }
            .onValueChange(bridge.calls.current?.id) { _ in check() }
            .alert(t("Hear calls on this iPhone?"), isPresented: $show) {
                Button {
                    answered()
                    Task {
                        do {
                            try await bridge.requestTalkSetup()
                            app.toast = String(localized: "Pairline Bridge on your Android phone shows what to do.", table: "CallAudio")
                        } catch {
                            app.toast = error.localizedDescription
                        }
                    }
                } label: {
                    t("Set up on Android phone")
                }
                Button(role: .cancel) {
                    answered()
                } label: {
                    t("Not now")
                }
            } message: {
                t("Calls play on your Android phone for now. Set up Talk on iPhone there once (about 2 minutes), and you hear and talk on this iPhone.")
            }
    }

    private var key: String? {
        app.pairedPeer.map { "talk.setupPrompted." + $0.deviceID.hex }
    }

    private func check() {
        guard !bridge.isDemo, bridge.talk?.state == .setup, bridge.phase.isConnected || bridge.phase == .remote,
              bridge.calls.current == nil, !app.showPairing, !app.pairingFlowOpen, let key,
              !UserDefaults.standard.bool(forKey: key) else { return }
        show = true
    }

    /// Remembered only once answered: an alert SwiftUI dropped behind another sheet comes back at the next change.
    private func answered() {
        if let key { UserDefaults.standard.set(true, forKey: key) }
    }
}
