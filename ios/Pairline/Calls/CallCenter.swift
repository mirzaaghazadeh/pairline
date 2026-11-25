import AVFoundation
import CallKit
import Foundation
import UIKit

/// A call on the bridge phone, as the iPhone sees it.
struct ActiveCall: Identifiable, Equatable {
    enum State: Equatable {
        case ringing
        case dialing
        case active
        case held
        case ended(String?)
    }

    let id: UUID
    var bridgeID: OpaqueValue?
    var number: String
    var name: String?
    var isOutgoing: Bool
    var state: State
    var sim: Int?
    var connectedAt: Date?
    var muted = false
    var speaker = false
    /// Whether CallKit shows this call (false in demo mode, and after it was answered on the Android phone).
    var inCallKit: Bool
    var answeredHere = false

    var displayName: String {
        if let name, !name.isEmpty { return name }
        return number.isEmpty ? String(localized: "Unknown caller") : number
    }
}

/// Maps bridge call events to CallKit (real link) or to the in-app call screen (demo mode), and CallKit actions to
/// link requests. Call audio stays on the Android phone unless the bridge can relay it ("Talk on iPhone", D-047).
@MainActor
final class CallCenter: ObservableObject {
    /// Where the call's audio is.
    enum AudioRoute: Equatable {
        case bridge
        case connecting
        /// On this iPhone; `talk` false means listen here, talk on the Android phone.
        case iphone(talk: Bool)
    }

    @Published private(set) var current: ActiveCall?
    @Published private(set) var audioRoute: AudioRoute = .bridge
    /// Set after a call whose audio was on this iPhone, so the app can ask how it sounded.
    @Published var askAudioFeedback = false
    @Published var showCallScreen = false
    /// True only while the call screen is actually on screen. `showCallScreen` can be true while SwiftUI drops the
    /// presentation (another presentation or a transition in progress), so the call bar and the retry use this.
    @Published var callScreenVisible = false
    @Published var notice: String?

    weak var bridge: BridgeController?
    /// The Wi-Fi media channel while one is open (M5); nil without a Wi-Fi link.
    var mediaTransport: (() -> AudioPacketTransport?)?
    private let useCallKit: Bool
    private var adapter: CallKitAdapter?
    private let controller = CXCallController()
    private var audioEngine: CallAudioEngine?
    private var audioSessionActive = false
    private var relayAccepted = false
    private var relayTalk = false
    private var relayStartedAt: Date?
    private var engineStartedAt: Date?
    private var engineRestarts = 0
    private var restartPending = false
    private var echoCancellationFailed = false
    private static let maxEngineRestarts = 6
    /// Route changes come in bursts (CallKit's session, then the route itself); look once they've settled.
    private static let restartSettleDelay: TimeInterval = 0.3

    init(useCallKit: Bool) {
        self.useCallKit = useCallKit
        if useCallKit {
            let adapter = CallKitAdapter(includeInRecents: AppSettings.shared.callsInRecents)
            self.adapter = adapter
            adapter.onAnswer = { [weak self] id, action in self?.performAnswer(id, action) }
            adapter.onEnd = { [weak self] id, action in self?.performEnd(id, action) }
            adapter.onStart = { [weak self] id, action in self?.performStart(id, action) }
            adapter.onMute = { [weak self] id, muted, action in self?.performMute(id, muted, action) }
            adapter.onDTMF = { [weak self] id, digits, action in self?.performDTMF(id, digits, action) }
            adapter.onAudioActivated = { [weak self] in
                self?.audioSessionActive = true
                self?.startEngineIfReady()
            }
            adapter.onAudioDeactivated = { [weak self] in
                self?.audioSessionActive = false
                self?.stopEngine()
            }
            adapter.onReset = { [weak self] in
                // CallKit dropped its calls; the call on the Android phone goes on, so keep it in the app.
                guard let self, self.current != nil else { return }
                self.current?.inCallKit = false
                if UIApplication.shared.applicationState == .active { self.showCallScreen = true }
            }
        }
    }

    func setIncludeInRecents(_ value: Bool) {
        adapter?.setIncludeInRecents(value)
    }

    private var canControl: Bool { bridge?.has(.callControl) ?? false }
    var canDTMF: Bool { bridge?.has(.callDTMF) ?? false }
    /// Mute and speaker work: locally while the audio is on this iPhone, or on the bridge through `call.audio`.
    var canAudio: Bool { audioOnIPhone || (bridge?.has(.callAudio) ?? false) }
    var audioOnIPhone: Bool { if case .iphone = audioRoute { return true } else { return false } }
    /// The bridge can relay this call's audio and the Wi-Fi media channel is open.
    var canRelayAudio: Bool { bridge?.has(.callMedia) == true && (mediaTransport?()?.isOpen ?? false) }
    /// Talk on iPhone is set up on the bridge, but the phones aren't linked over Wi-Fi, which carries the audio.
    var audioNeedsWiFi: Bool {
        audioRoute == .bridge && bridge?.has(.callMedia) == true && !(mediaTransport?()?.isOpen ?? false)
    }

    /// The call screen explains why the audio stayed on the Android phone (no Wi-Fi between the phones).
    @Published var wifiAlert = false
    private var wifiExplained = Set<UUID>()
    static let wifiAlertMutedKey = "calls.wifiAlertMuted"

    /// Once per call, unless the user chose "Don't show again".
    private func explainMissingWiFiIfNeeded(_ call: ActiveCall) {
        guard audioNeedsWiFi, !wifiExplained.contains(call.id),
              !UserDefaults.standard.bool(forKey: Self.wifiAlertMutedKey) else { return }
        wifiExplained.insert(call.id)
        wifiAlert = true
    }

    /// The bridge tried to move the call's audio here and couldn't; the call screen shows its reason, once per call.
    /// A toast would sit behind the call screen, where nobody sees it.
    @Published var relayFailureAlert = false
    private(set) var relayFailureReason: String?
    /// The iPhone itself couldn't start the audio (not the bridge).
    private(set) var relayFailureOnIPhone = false
    private var relayFailureExplained = Set<UUID>()

    private func explainRelayFailure(_ reason: String?, onIPhone: Bool = false) {
        guard let call = current, !relayFailureExplained.contains(call.id) else { return }
        relayFailureExplained.insert(call.id)
        relayFailureReason = reason
        relayFailureOnIPhone = onIPhone
        if callScreenVisible {
            relayFailureAlert = true
        } else {
            notice = String(localized: "The call's audio went back to your Android phone.", table: "CallAudio")
        }
    }

    // MARK: - Bridge events

    func handleIncoming(_ event: CallIncomingEvent) {
        if let current, current.bridgeID == event.call { return }
        if current != nil {
            // One call at a time in v0; the Android phone still shows the waiting call.
            log.info("calls", "Ignoring a second incoming call")
            return
        }
        let number = event.from ?? ""
        let name = event.name ?? bridge?.db.knownName(for: number)
        let call = ActiveCall(id: UUID(), bridgeID: event.call, number: number, name: name, isOutgoing: false,
                              state: .ringing, sim: event.sim, inCallKit: useCallKit)
        current = call
        log.info("calls", "Incoming call")
        if useCallKit, let adapter {
            adapter.reportIncoming(call) { [weak self] error in
                guard let error, let self else { return }
                // Do Not Disturb, Focus or a blocked number: CallKit declined to show it.
                log.warning("calls", "CallKit didn't show the call: \(error.localizedDescription)")
                if self.current?.id == call.id {
                    // Fall back to the in-app call screen (also the case in the Simulator, which has no CallKit UI).
                    self.current?.inCallKit = false
                    self.showCallScreen = true
                    // In the background nobody would see it: say so in a notification that opens the call screen.
                    if UIApplication.shared.applicationState != .active {
                        Notifier.shared.notifyIncomingCall(from: call.name ?? call.number)
                    }
                }
            }
        } else {
            showCallScreen = true
        }
    }

    func handleState(_ event: CallStateEvent) {
        if case .ended = mapState(event.state) {
            return handleEnded(event)
        }
        if current == nil {
            if event.state == "ringing", event.dir != "out" {
                return handleIncoming(CallIncomingEvent(call: event.call, sim: event.sim, from: event.number, name: event.name))
            }
            // A call placed or answered on the Android phone itself: show it in the app, without CallKit.
            let number = event.number ?? ""
            current = ActiveCall(id: UUID(), bridgeID: event.call, number: number,
                                 name: event.name ?? bridge?.db.knownName(for: number), isOutgoing: event.dir == "out",
                                 state: mapState(event.state), sim: event.sim,
                                 connectedAt: event.state == "active" ? Date(milliseconds: event.since ?? Date().milliseconds) : nil,
                                 inCallKit: false)
            return
        }
        guard var call = current else { return }
        if call.bridgeID == nil, call.isOutgoing, event.dir == "out" || event.dir == nil {
            call.bridgeID = event.call
        }
        guard call.bridgeID == event.call else { return }
        let newState = mapState(event.state)
        if newState == .active, call.state != .active {
            call.connectedAt = Date(milliseconds: event.since ?? Date().milliseconds)
            if call.isOutgoing {
                adapter?.reportConnected(call.id, at: call.connectedAt ?? Date())
            } else if !call.answeredHere, call.inCallKit {
                // Answered on the Android phone: remove the ringing screen here and keep the call in the app.
                adapter?.reportEnded(call.id, reason: .answeredElsewhere)
                call.inCallKit = false
            }
        }
        if newState == .dialing, call.isOutgoing {
            adapter?.reportConnecting(call.id)
        }
        call.state = newState
        if let name = event.name, call.name == nil { call.name = name }
        if let sim = event.sim, call.sim == nil { call.sim = sim }
        // The number can arrive after call.incoming (Android reads it from a later broadcast).
        if call.number.isEmpty, let number = event.number, !number.isEmpty {
            call.number = number
            if call.name == nil { call.name = bridge?.db.knownName(for: number) }
            if call.inCallKit { adapter?.updateCaller(call) }
        }
        current = call
        // Taken on this iPhone (answered here, or dialed from here): bring the audio over if the bridge can. The
        // bridge relays a call it sees in progress, which includes dialing: a call placed here moves over while it
        // rings, so the ringback plays here too. If that start fails, "active" tries again.
        let dialedHere = newState == .dialing && call.isOutgoing
        if newState == .active || dialedHere, call.answeredHere {
            explainMissingWiFiIfNeeded(call)
            startRelayIfPossible()
        }
    }

    private func handleEnded(_ event: CallStateEvent) {
        guard var call = current, call.bridgeID == event.call || call.bridgeID == nil else { return }
        call.state = .ended(event.reason)
        if call.inCallKit {
            adapter?.reportEnded(call.id, reason: endReason(event.reason, call: call))
        }
        Notifier.shared.clearIncomingCall()
        if let message = endNotice(event.reason, call: call) { notice = message }
        current = call
        stopRelay(callEnded: true)
        log.info("calls", "Call ended: \(event.reason ?? "-")")
        let id = call.id
        Timer.scheduledTimer(withTimeInterval: 1.5, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.current?.id == id else { return }
                self.current = nil
                self.showCallScreen = false
            }
        }
        bridge?.callEnded()
    }

    private func mapState(_ state: String) -> ActiveCall.State {
        switch state {
        case "ringing": return .ringing
        case "dialing": return .dialing
        case "active": return .active
        case "held": return .held
        default: return .ended(nil)
        }
    }

    private func endReason(_ reason: String?, call: ActiveCall) -> CXCallEndedReason {
        switch reason {
        case "missed": return .unanswered
        case "rejected": return call.isOutgoing ? .remoteEnded : .declinedElsewhere
        case "busy", "failed": return .failed
        case "local": return call.state == .ringing ? .declinedElsewhere : .remoteEnded
        default: return .remoteEnded
        }
    }

    private func endNotice(_ reason: String?, call: ActiveCall) -> String? {
        guard call.isOutgoing else { return nil }
        switch reason {
        case "busy": return String(localized: "\(call.displayName) is busy.")
        case "missed": return String(localized: "\(call.displayName) didn't answer.")
        case "rejected": return String(localized: "\(call.displayName) declined the call.")
        case "failed": return String(localized: "The call couldn't be completed.")
        default: return nil
        }
    }

    /// The link dropped: CallKit can't control a call we can't reach.
    func linkLost() {
        stopRelay(callEnded: false)
        guard let call = current else { return }
        if call.inCallKit { adapter?.reportEnded(call.id, reason: .failed) }
        current = nil
        showCallScreen = false
    }

    // MARK: - Call audio on this iPhone (D-047)

    /// `call.media.state` from the bridge.
    func handleMediaState(_ event: CallMediaStateEvent) {
        guard let call = current, call.bridgeID == event.call else { return }
        switch event.state {
        case "active":
            relayTalk = event.talk ?? false
            if audioOnIPhone { audioRoute = .iphone(talk: relayTalk) }
        case "failed", "stopped":
            guard audioRoute != .bridge else { return }
            log.info("audio", "Bridge stopped relaying call audio: \(event.reason ?? "-")")
            if event.state == "failed" {
                explainRelayFailure(event.reason)
            } else if call.state == .active {
                notice = String(localized: "The call's audio went back to your Android phone.", table: "CallAudio")
            }
            stopRelay(callEnded: false)
        default:
            break
        }
    }

    private func startRelayIfPossible() {
        // Demo mode has no audio to relay; the call screen still shows how a call looks with Talk on iPhone set up.
        if let bridge, bridge.isDemo, audioRoute == .bridge, bridge.has(.callMedia) {
            audioRoute = .iphone(talk: bridge.has(.callMediaTalk))
            return
        }
        guard audioRoute == .bridge, !relayAccepted, canRelayAudio,
              let bridge, let bridgeID = current?.bridgeID else { return }
        audioRoute = .connecting
        let talk = bridge.has(.callMediaTalk)
        if !useCallKit || current?.inCallKit == false { activateOwnAudioSession() }
        Task {
            do {
                _ = try await bridge.link.request(.callMediaStart(bridgeID, talk: talk))
                guard self.current?.bridgeID == bridgeID else { return }
                self.relayAccepted = true
                self.relayTalk = talk
                self.startEngineIfReady()
            } catch {
                log.warning("audio", "Bridge didn't start call audio: \(error.localizedDescription)")
                guard self.current?.bridgeID == bridgeID, self.audioRoute == .connecting else { return }
                self.audioRoute = .bridge
                if case LinkError.bridge(let reply) = error {
                    self.explainRelayFailure(reply.msg ?? reply.code)
                } else {
                    self.explainRelayFailure(error.localizedDescription)
                }
            }
        }
    }

    /// CallKit activates the audio session for a call it shows; the engine starts once both the bridge has accepted
    /// and the session is active.
    private func startEngineIfReady() {
        guard relayAccepted, audioSessionActive, audioEngine == nil,
              let transport = mediaTransport?(), transport.isOpen else { return }
        do {
            let engine = try startedEngine(transport)
            engine.muted = current?.muted ?? false
            if current?.speaker == true { engine.setSpeaker(true) }
            engine.onConfigurationChange = { [weak self, weak engine] in
                guard let self, let engine else { return }
                self.restartEngine(after: engine)
            }
            audioEngine = engine
            engineStartedAt = Date()
            relayStartedAt = relayStartedAt ?? Date()
            audioRoute = .iphone(talk: relayTalk)
        } catch {
            log.warning("audio", "Couldn't start call audio here: \(error.localizedDescription)")
            explainRelayFailure(error.localizedDescription, onIPhone: true)
            stopRelay(callEnded: false)
        }
    }

    /// Echo cancellation first; if the engine won't start with it (some routes and devices refuse), a fresh engine
    /// without it, so the call's audio still comes here. Remembered for the rest of the call.
    private func startedEngine(_ transport: AudioPacketTransport) throws -> CallAudioEngine {
        if !echoCancellationFailed {
            let engine = CallAudioEngine(transport: transport, voiceProcessing: true)
            do {
                try engine.start()
                return engine
            } catch {
                log.warning("audio", "Call audio didn't start with echo cancellation (\(error.localizedDescription)); trying without")
                echoCancellationFailed = true
            }
        }
        let engine = CallAudioEngine(transport: transport, voiceProcessing: false)
        try engine.start()
        return engine
    }

    /// iOS changed the audio route or format: once the changes settle, build a fresh engine in the same audio session
    /// if iOS stopped this one. Capped, so a device that keeps changing can't loop.
    ///
    /// Not ignored right after the start (D-065): on a tester's iPhone 14 Pro Max the route changed 0.1 s after the
    /// engine started (CallKit's session settling) and iOS stopped the engine; ignoring that change left the whole call
    /// silent both ways. Whether the change stopped the engine is what decides, not how old the engine is.
    private func restartEngine(after engine: CallAudioEngine) {
        guard audioEngine === engine, !restartPending else { return }
        restartPending = true
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.restartSettleDelay) { [weak self, weak engine] in
            guard let self else { return }
            self.restartPending = false
            guard let engine, self.audioEngine === engine else { return }
            if engine.engineRunning { return }   // the change didn't stop it
            guard self.engineRestarts < Self.maxEngineRestarts else {
                log.warning("audio", "Audio route keeps changing; leaving call audio as it is")
                return
            }
            self.engineRestarts += 1
            log.info("audio", "Call audio engine stopped by iOS; restarting it (\(self.engineRestarts))")
            do {
                // The same engine, rebuilt for the new route: a fresh one switches echo cancellation on again, which
                // makes iOS change the configuration and stop it again.
                try engine.restartAfterConfigurationChange()
                if self.current?.speaker == true { engine.setSpeaker(true) }
            } catch {
                log.warning("audio", "Couldn't restart call audio (\(error.localizedDescription)); starting a fresh engine")
                engine.stop()
                self.audioEngine = nil
                self.startEngineIfReady()
            }
        }
    }

    private func stopEngine() {
        audioEngine?.stop()
        audioEngine = nil
    }

    private func stopRelay(callEnded: Bool) {
        let wasRelaying = audioOnIPhone
        let relayedFor = relayStartedAt.map { Date().timeIntervalSince($0) } ?? 0
        stopEngine()
        if relayAccepted, !callEnded, let bridgeID = current?.bridgeID, let bridge {
            Task { _ = try? await bridge.link.request(.callMediaStop(bridgeID)) }
        }
        relayAccepted = false
        relayStartedAt = nil
        engineStartedAt = nil
        engineRestarts = 0
        if callEnded { echoCancellationFailed = false }   // try echo cancellation again on the next call
        audioRoute = .bridge
        if !useCallKit || current?.inCallKit == false {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            audioSessionActive = false
        }
        // Ask how it sounded after a real conversation, so the bridge can settle on what works on that phone.
        if callEnded, wasRelaying, relayedFor >= 8 { askAudioFeedback = true }
    }

    /// The in-app call screen (no CallKit, e.g. the Simulator) needs its own voice-chat audio session.
    private func activateOwnAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth])
            try session.setActive(true)
            audioSessionActive = true
        } catch {
            log.warning("audio", "Couldn't activate the audio session: \(error.localizedDescription)")
        }
    }

    /// Prepares the session CallKit is about to activate, so it comes up as a voice chat.
    private func prepareCallKitAudioSession() {
        guard canRelayAudio else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth])
    }

    /// The answers to "How did the call sound?".
    func sendAudioFeedback(heard: Bool?, heardMe: Bool?) {
        askAudioFeedback = false
        guard let bridge else { return }
        Task { _ = try? await bridge.link.request(.callMediaFeedback(heard: heard, heardMe: heardMe)) }
    }

    // MARK: - User actions (in-app UI)

    func answer() {
        guard let call = current else { return }
        if call.inCallKit {
            request(CXAnswerCallAction(call: call.id))
        } else {
            Task { await sendAnswer() }
        }
    }

    func hangUp() {
        guard let call = current else { return }
        if call.inCallKit {
            request(CXEndCallAction(call: call.id))
        } else {
            Task { await sendEnd(call) }
        }
    }

    func setMuted(_ muted: Bool) {
        guard let call = current else { return }
        if audioOnIPhone, !call.inCallKit {
            audioEngine?.muted = muted
            current?.muted = muted
            return
        }
        guard canAudio else {
            notice = String(localized: "Call audio is on your Android phone for now, so mute it there.")
            return
        }
        if call.inCallKit {
            request(CXSetMutedCallAction(call: call.id, muted: muted))
        } else {
            Task { await sendAudio(mute: muted, speaker: nil) }
        }
    }

    func setSpeaker(_ on: Bool) {
        // On its way here counts too (D-065): sent to the bridge then, it put the Android phone on its loudspeaker,
        // which played the caller out loud there. The engine applies it when it starts.
        if audioOnIPhone || audioRoute == .connecting {
            audioEngine?.setSpeaker(on)
            current?.speaker = on
            return
        }
        guard canAudio else {
            notice = String(localized: "Call audio is on your Android phone for now, so use its speaker button.")
            return
        }
        Task { await sendAudio(mute: nil, speaker: on) }
    }

    func sendDigit(_ digit: String) {
        guard let call = current, let bridgeID = call.bridgeID else { return }
        guard canDTMF else {
            notice = String(localized: "Your bridge app can't send keypad tones yet. Use the keypad on the Android phone.")
            return
        }
        Task { _ = try? await bridge?.link.request(.callDTMF(bridgeID, digit: digit)) }
    }

    /// Starts a call from the dialer, contacts or call log.
    func startCall(number: String, sim: Int?) {
        guard let bridge else { return }
        guard bridge.canUseLink else {
            bridge.requestPaywall()
            return
        }
        guard bridge.phase.isConnected else {
            notice = String(localized: "Your bridge phone isn't connected right now.")
            return
        }
        guard bridge.has(.callDial) else {
            notice = String(localized: "Your bridge app can't place calls yet. Allow the phone permission in Pairline Bridge.")
            return
        }
        guard current == nil else {
            notice = String(localized: "There's already a call in progress.")
            return
        }
        let dialable = PhoneNumber.dialable(number)
        guard !dialable.isEmpty else { return }
        var call = ActiveCall(id: UUID(), bridgeID: nil, number: dialable, name: bridge.db.knownName(for: dialable),
                              isOutgoing: true, state: .dialing, sim: sim, inCallKit: useCallKit)
        call.answeredHere = true // placed from this iPhone, so its audio belongs here too
        current = call
        showCallScreen = true
        if useCallKit {
            let handle = CXHandle(type: .phoneNumber, value: dialable)
            let action = CXStartCallAction(call: call.id, handle: handle)
            action.contactIdentifier = call.name
            request(action) { [weak self] in
                // CallKit refused (the Simulator has none): place the call without it.
                guard let self, self.current?.id == call.id else { return }
                self.current?.inCallKit = false
                Task { await self.sendDial(call) }
            }
        } else {
            Task { await sendDial(call) }
        }
    }

    private func request(_ action: CXCallAction, fallback: (() -> Void)? = nil) {
        controller.request(CXTransaction(action: action)) { [weak self] error in
            guard let error else { return }
            Task { @MainActor in
                log.warning("calls", "CallKit transaction failed: \(error.localizedDescription)")
                if let fallback {
                    fallback()
                } else if let self, let call = self.current, call.inCallKit {
                    // Run the action without CallKit.
                    self.current?.inCallKit = false
                    if action is CXAnswerCallAction { Task { await self.sendAnswer() } }
                    if action is CXEndCallAction { Task { await self.sendEnd(call) } }
                    if let mute = action as? CXSetMutedCallAction {
                        Task { await self.sendAudio(mute: mute.isMuted, speaker: nil) }
                    }
                } else {
                    self?.notice = String(localized: "That didn't work. Try again on the Android phone.")
                }
            }
        }
    }

    // MARK: - CallKit actions → link

    private func performAnswer(_ id: UUID, _ action: CXAnswerCallAction) {
        guard current?.id == id else { return action.fail() }
        prepareCallKitAudioSession()
        Task {
            if await sendAnswer() { action.fulfill() } else { action.fail() }
        }
    }

    private func performEnd(_ id: UUID, _ action: CXEndCallAction) {
        guard let call = current, call.id == id else { return action.fulfill() }
        current?.inCallKit = false
        action.fulfill()
        Task { await sendEnd(call) }
    }

    private func performStart(_ id: UUID, _ action: CXStartCallAction) {
        guard let call = current, call.id == id else { return action.fail() }
        prepareCallKitAudioSession()
        Task {
            if await sendDial(call) {
                action.fulfill()
                adapter?.reportConnecting(id)
            } else {
                action.fail()
            }
        }
    }

    private func performMute(_ id: UUID, _ muted: Bool, _ action: CXSetMutedCallAction) {
        if audioOnIPhone {
            // The microphone is this iPhone's: mute it here (the bridge then gets silence).
            audioEngine?.muted = muted
            current?.muted = muted
            return action.fulfill()
        }
        guard canAudio else { return action.fail() }
        Task {
            if await sendAudio(mute: muted, speaker: nil) { action.fulfill() } else { action.fail() }
        }
    }

    private func performDTMF(_ id: UUID, _ digits: String, _ action: CXPlayDTMFCallAction) {
        guard canDTMF, let bridgeID = current?.bridgeID else { return action.fail() }
        Task {
            for digit in digits {
                _ = try? await bridge?.link.request(.callDTMF(bridgeID, digit: String(digit)))
            }
            action.fulfill()
        }
    }

    // MARK: - Link requests

    @discardableResult
    private func sendAnswer() async -> Bool {
        guard let call = current, let bridgeID = call.bridgeID, let bridge else { return false }
        guard canControl else {
            notice = String(localized: "Your bridge app can't answer calls yet. Answer on the Android phone.")
            return false
        }
        do {
            _ = try await bridge.link.request(.callAnswer(bridgeID))
            current?.answeredHere = true
            if current?.state == .ringing { current?.state = .active; current?.connectedAt = Date() }
            showCallScreen = true
            return true
        } catch {
            notice = error.localizedDescription
            return false
        }
    }

    private func sendEnd(_ call: ActiveCall) async {
        guard let bridgeID = call.bridgeID, let bridge else {
            current = nil
            showCallScreen = false
            return
        }
        let ringingIncoming = call.state == .ringing && !call.isOutgoing
        do {
            _ = try await bridge.link.request(ringingIncoming ? .callReject(bridgeID) : .callEnd(bridgeID))
        } catch {
            notice = error.localizedDescription
        }
    }

    @discardableResult
    private func sendDial(_ call: ActiveCall) async -> Bool {
        guard let bridge else { return false }
        do {
            _ = try await bridge.link.request(.callDial(sim: call.sim, number: call.number))
            watchDial(call.id)
            return true
        } catch {
            notice = error.localizedDescription
            if current?.id == call.id {
                current = nil
                showCallScreen = false
            }
            return false
        }
    }

    /// The bridge accepted the dial but never reported a call for it (Android dropped it): after 30 s, end it here
    /// instead of showing "Calling…" with ringback for ever.
    private func watchDial(_ id: UUID) {
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 30_000_000_000)
            guard let self, let call = self.current, call.id == id, call.bridgeID == nil else { return }
            log.warning("calls", "The bridge never reported the dialed call; ending it here")
            if call.inCallKit { self.adapter?.reportEnded(id, reason: .failed) }
            self.notice = String(localized: "The Android phone didn't place the call.", table: "CallAudio")
            self.stopRelay(callEnded: true)
            self.current = nil
            self.showCallScreen = false
        }
    }

    @discardableResult
    private func sendAudio(mute: Bool?, speaker: Bool?) async -> Bool {
        guard let bridgeID = current?.bridgeID, let bridge else { return false }
        do {
            _ = try await bridge.link.request(.callAudio(bridgeID, mute: mute, speaker: speaker))
            if let mute { current?.muted = mute }
            if let speaker { current?.speaker = speaker }
            return true
        } catch {
            notice = error.localizedDescription
            return false
        }
    }
}

/// The CallKit provider. Callbacks arrive on the main queue.
final class CallKitAdapter: NSObject, CXProviderDelegate {
    private let provider: CXProvider

    var onAnswer: ((UUID, CXAnswerCallAction) -> Void)?
    var onEnd: ((UUID, CXEndCallAction) -> Void)?
    var onStart: ((UUID, CXStartCallAction) -> Void)?
    var onMute: ((UUID, Bool, CXSetMutedCallAction) -> Void)?
    var onDTMF: ((UUID, String, CXPlayDTMFCallAction) -> Void)?
    var onReset: (() -> Void)?
    var onAudioActivated: (() -> Void)?
    var onAudioDeactivated: (() -> Void)?

    init(includeInRecents: Bool) {
        provider = CXProvider(configuration: CallKitAdapter.configuration(includeInRecents: includeInRecents))
        super.init()
        provider.setDelegate(self, queue: .main)
    }

    static func configuration(includeInRecents: Bool) -> CXProviderConfiguration {
        let configuration = CXProviderConfiguration()
        configuration.supportsVideo = false
        configuration.maximumCallGroups = 1
        configuration.maximumCallsPerCallGroup = 1
        configuration.supportedHandleTypes = [.phoneNumber, .generic]
        configuration.includesCallsInRecents = includeInRecents
        let symbol = UIImage.SymbolConfiguration(pointSize: 40, weight: .regular)
        configuration.iconTemplateImageData = UIImage(systemName: "phone.connection.fill", withConfiguration: symbol)?
            .pngData()
        return configuration
    }

    func setIncludeInRecents(_ value: Bool) {
        provider.configuration = CallKitAdapter.configuration(includeInRecents: value)
    }

    func reportIncoming(_ call: ActiveCall, completion: @escaping (Error?) -> Void) {
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: PhoneNumber.isPhoneNumber(call.number) ? .phoneNumber : .generic,
                                       value: call.number)
        update.localizedCallerName = call.name
        update.hasVideo = false
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.supportsDTMF = true
        provider.reportNewIncomingCall(with: call.id, update: update) { error in
            DispatchQueue.main.async { completion(error) }
        }
    }

    func updateCaller(_ call: ActiveCall) {
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: PhoneNumber.isPhoneNumber(call.number) ? .phoneNumber : .generic,
                                       value: call.number)
        update.localizedCallerName = call.name
        provider.reportCall(with: call.id, updated: update)
    }

    func reportConnecting(_ id: UUID) {
        provider.reportOutgoingCall(with: id, startedConnectingAt: Date())
    }

    func reportConnected(_ id: UUID, at date: Date) {
        provider.reportOutgoingCall(with: id, connectedAt: date)
    }

    func reportEnded(_ id: UUID, reason: CXCallEndedReason) {
        provider.reportCall(with: id, endedAt: Date(), reason: reason)
    }

    func providerDidReset(_ provider: CXProvider) { onReset?() }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) { onAnswer?(action.callUUID, action) }
    func provider(_ provider: CXProvider, perform action: CXEndCallAction) { onEnd?(action.callUUID, action) }
    func provider(_ provider: CXProvider, perform action: CXStartCallAction) { onStart?(action.callUUID, action) }
    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        onMute?(action.callUUID, action.isMuted, action)
    }
    func provider(_ provider: CXProvider, perform action: CXPlayDTMFCallAction) {
        onDTMF?(action.callUUID, action.digits, action)
    }
    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) { action.fail() }
    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) { onAudioActivated?() }
    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) { onAudioDeactivated?() }
}
