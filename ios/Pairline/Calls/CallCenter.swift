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

    var displayName: String { name?.isEmpty == false ? name! : number }
}

/// Maps bridge call events to CallKit (real link) or to the in-app call screen (demo mode), and CallKit actions to
/// link requests. Audio stays on the Android phone until M6.
@MainActor
final class CallCenter: ObservableObject {
    @Published private(set) var current: ActiveCall?
    @Published var showCallScreen = false
    @Published var notice: String?

    weak var bridge: BridgeController?
    private let useCallKit: Bool
    private var adapter: CallKitAdapter?
    private let controller = CXCallController()

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
            adapter.onReset = { [weak self] in self?.current = nil }
        }
    }

    func setIncludeInRecents(_ value: Bool) {
        adapter?.setIncludeInRecents(value)
    }

    private var canControl: Bool { bridge?.has(.callControl) ?? false }
    var canDTMF: Bool { bridge?.has(.callDTMF) ?? false }
    var canAudio: Bool { bridge?.has(.callAudio) ?? false }

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
                    self.current?.inCallKit = false
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
                return handleIncoming(CallIncomingEvent(call: event.call, sim: nil, from: event.number, name: event.name))
            }
            // A call placed or answered on the Android phone itself: show it in the app, without CallKit.
            let number = event.number ?? ""
            current = ActiveCall(id: UUID(), bridgeID: event.call, number: number,
                                 name: event.name ?? bridge?.db.knownName(for: number), isOutgoing: event.dir == "out",
                                 state: mapState(event.state), sim: nil,
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
        current = call
    }

    private func handleEnded(_ event: CallStateEvent) {
        guard var call = current, call.bridgeID == event.call || call.bridgeID == nil else { return }
        call.state = .ended(event.reason)
        if call.inCallKit {
            adapter?.reportEnded(call.id, reason: endReason(event.reason, call: call))
        }
        if let message = endNotice(event.reason, call: call) { notice = message }
        current = call
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
        guard let call = current else { return }
        if call.inCallKit { adapter?.reportEnded(call.id, reason: .failed) }
        current = nil
        showCallScreen = false
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
        let call = ActiveCall(id: UUID(), bridgeID: nil, number: dialable, name: bridge.db.knownName(for: dialable),
                              isOutgoing: true, state: .dialing, sim: sim, inCallKit: useCallKit)
        current = call
        showCallScreen = true
        if useCallKit {
            let handle = CXHandle(type: .phoneNumber, value: dialable)
            let action = CXStartCallAction(call: call.id, handle: handle)
            action.contactIdentifier = call.name
            request(action)
        } else {
            Task { await sendDial(call) }
        }
    }

    private func request(_ action: CXCallAction) {
        controller.request(CXTransaction(action: action)) { [weak self] error in
            guard let error else { return }
            Task { @MainActor in
                log.warning("calls", "CallKit transaction failed: \(error.localizedDescription)")
                self?.notice = String(localized: "That didn't work. Try again on the Android phone.")
            }
        }
    }

    // MARK: - CallKit actions → link

    private func performAnswer(_ id: UUID, _ action: CXAnswerCallAction) {
        guard current?.id == id else { return action.fail() }
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
}
