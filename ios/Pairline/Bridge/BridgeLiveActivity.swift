import ActivityKit
import Combine
import UIKit

/// Shows the active bridge's signal, network and battery as a Live Activity on the Lock Screen and in the Dynamic
/// Island (D-053), while Settings › "Bridge status on Lock Screen" is on.
///
/// iOS only lets an app start a Live Activity while it's in the foreground (starting one from the background needs
/// a push server, which Pairline doesn't have: rule 2), and ends every Live Activity after 8 hours at most. An app
/// may update its own Live Activity from the background, and Pairline runs in the background over Bluetooth. So:
/// the activity starts (or is renewed, so its 8 hours count from now) when Pairline comes to the foreground, follows
/// the bridge's status from then on, and ends when the setting is turned off or the bridge is unpaired.
@MainActor
final class BridgeLiveActivity: ObservableObject {
    static let shared = BridgeLiveActivity()

    /// Live Activities are allowed for Pairline in iOS Settings (re-read whenever Pairline comes to the foreground).
    @Published private(set) var systemEnabled = true

    /// Live Activities with the content API need iOS 16.2; older systems don't show the setting.
    static var isSupported: Bool {
        if #available(iOS 16.2, *) { return true }
        return false
    }

    /// A foreground visit renews an activity older than this, so it lasts 8 hours from the latest app open.
    nonisolated static let renewAfter: TimeInterval = 30 * 60

    private weak var bridge: BridgeController?
    private var observation: AnyCancellable?
    private var startedAt: Date?
    private var lastState: AnyHashable?

    /// The code shown in the Dynamic Island (D-083) and copied by a tap on it (`pairline://code`).
    private(set) var code: String?
    private var codeExpiry: Task<Void, Never>?
    static let codeLifetime: TimeInterval = 120

    private init() {}

    /// A one-time code just arrived from `bridge`: show it in the Dynamic Island for two minutes, when the setting is on.
    func showCode(_ code: String, for bridge: BridgeController) {
        guard AppSettings.shared.codesInLiveActivity, bridge === self.bridge else { return }
        self.code = code
        refresh(inForeground: Self.inForeground)
        codeExpiry?.cancel()
        codeExpiry = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.codeLifetime * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.clearCode()
        }
    }

    /// Copied or expired: back to the bridge status.
    func clearCode() {
        guard code != nil else { return }
        code = nil
        codeExpiry?.cancel()
        refresh(inForeground: Self.inForeground)
    }

    /// Follows this bridge (the real one, or the demo's while demo mode is on).
    func follow(_ bridge: BridgeController) {
        guard bridge !== self.bridge else { return }
        self.bridge = bridge
        lastState = nil
        // @Published fires before the property changes; read the bridge on the next turn of the main queue.
        observation = Publishers.CombineLatest4(bridge.$phase, bridge.$transports, bridge.$status, bridge.$deviceInfo)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.refresh(inForeground: Self.inForeground) }
        refresh(inForeground: Self.inForeground)
    }

    private static var inForeground: Bool { UIApplication.shared.applicationState == .active }

    func appBecameActive() {
        if #available(iOS 16.2, *) { systemEnabled = ActivityAuthorizationInfo().areActivitiesEnabled }
        refresh(inForeground: true)
    }

    /// The Settings switch changed (Pairline is in the foreground).
    func settingChanged() {
        refresh(inForeground: true)
    }

    /// Whether a foreground visit replaces an activity started at `startedAt`.
    nonisolated static func shouldRenew(startedAt: Date?, now: Date) -> Bool {
        guard let startedAt else { return true }
        return now.timeIntervalSince(startedAt) >= renewAfter
    }

    private func refresh(inForeground: Bool) {
        guard #available(iOS 16.2, *) else { return }
        guard AppSettings.shared.bridgeLiveActivity, let bridge,
              var state = BridgeActivityAttributes.ContentState(snapshot: bridge.statusSnapshot(),
                                                               name: bridge.deviceInfo?.name) else {
            endAll()
            return
        }
        state.code = AppSettings.shared.codesInLiveActivity ? code : nil
        let running = Activity<BridgeActivityAttributes>.activities.first {
            $0.activityState == .active && $0.attributes.isDemo == bridge.isDemo
        }
        if let running, !(inForeground && Self.shouldRenew(startedAt: startedAt, now: Date())) {
            guard AnyHashable(state) != lastState else { return }
            lastState = AnyHashable(state)
            // While a code shows, the activity asks for the Dynamic Island ahead of other apps' (D-083).
            let relevance: Double = state.code == nil ? 0 : 50
            Task { await running.update(ActivityContent(state: state, staleDate: nil, relevanceScore: relevance)) }
            return
        }
        // From the background iOS doesn't start one; the next time Pairline is opened does.
        guard inForeground, systemEnabled else { return }
        start(state, isDemo: bridge.isDemo)
    }

    @available(iOS 16.2, *)
    private func start(_ state: BridgeActivityAttributes.ContentState, isDemo: Bool) {
        let previous = Activity<BridgeActivityAttributes>.activities
        do {
            // A low relevance score lets calls, timers and other apps' activities take the Dynamic Island first.
            _ = try Activity.request(attributes: BridgeActivityAttributes(isDemo: isDemo),
                                     content: ActivityContent(state: state, staleDate: nil, relevanceScore: 0),
                                     pushType: nil)
            startedAt = Date()
            lastState = AnyHashable(state)
            log.info("activity", "Bridge Live Activity started")
        } catch {
            log.warning("activity", "Couldn't start the bridge Live Activity: \(error.localizedDescription)")
            return
        }
        for activity in previous {
            Task { await activity.end(nil, dismissalPolicy: .immediate) }
        }
    }

    @available(iOS 16.2, *)
    private func endAll() {
        let activities = Activity<BridgeActivityAttributes>.activities
        guard !activities.isEmpty else { return }
        startedAt = nil
        lastState = nil
        for activity in activities {
            Task { await activity.end(nil, dismissalPolicy: .immediate) }
        }
        log.info("activity", "Bridge Live Activity ended")
    }
}
