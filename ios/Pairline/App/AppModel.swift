import Combine
import Foundation
import Intents
import SwiftUI
import UIKit

enum AppTab: Hashable {
    case messages
    case calls
    case contacts
    case bridge
    case settings
}

/// Root state: purchases, the real link and its data, and demo mode.
@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    let settings = AppSettings.shared
    let purchases: PurchaseManager
    let identity = IdentityStore()
    let peers = PeerStore()
    let link: LinkManager
    let real: BridgeController

    @Published private(set) var demo: BridgeController?
    private(set) var demoBridge: DemoBridge?
    @Published private(set) var pairedPeer: PairedPeer?
    @Published var tab: AppTab = .messages
    @Published var openThread: Int64?
    /// Opens a new message to this number (from Contacts).
    @Published var composeTo: (name: String?, number: String)?
    @Published var showPaywall = false
    @Published var showPairing = false
    @Published var toast: String?

    private var cancellables = Set<AnyCancellable>()

    var active: BridgeController { demo ?? real }
    var isDemo: Bool { demo != nil }
    var isPaired: Bool { pairedPeer != nil }

    private init() {
        purchases = PurchaseManager()
        let deviceInfo = Outbound.deviceInfo(name: UIDevice.current.model, os: UIDevice.current.systemVersion,
                                             app: AppInfo.version)
        var remote: RemoteLink?
        if RemoteFeature.isAvailable {
            remote = RemoteLink(store: RemoteStore(), identity: { [identity] in try identity.identity() },
                                bridgeKey: { [peers] in peers.peer?.publicKey },
                                registerForPush: { if !Self.isTestHost { UIApplication.shared.registerForRemoteNotifications() } })
        }
        link = LinkManager(identityStore: identity, peerStore: peers, deviceInfo: deviceInfo, remote: remote) { kind in
            #if DEBUG
            if kind == .tcp { return TCPConnector() }
            #endif
            if kind == .wifi { return WiFiConnector() }
            return BluetoothCentral()
        }
        real = BridgeController(mode: .real, link: link, db: AppDatabase.openDefault())
        pairedPeer = peers.peer
        real.onPaywallRequest = { [weak self] in self?.showPaywall = true }
        real.onFirstSyncDone = { [weak self] in self?.firstSyncDone() }
        link.onPeerChange = { [weak self] in self?.peerChanged() }

        purchases.$access
            .removeDuplicates()
            .sink { [weak self] access in self?.accessChanged(access) }
            .store(in: &cancellables)
        purchases.$remoteAccess
            .removeDuplicates()
            .sink { [weak self] access in self?.remoteAccessChanged(access) }
            .store(in: &cancellables)

        let notifier = Notifier.shared
        notifier.configure()
        notifier.onOpenThread = { [weak self] thread in
            self?.tab = .messages
            self?.openThread = thread
        }
        notifier.onMarkRead = { [weak self] thread in
            self?.active.markRead(thread: thread)
        }
        notifier.onMirrorDismissed = { [weak self] key in
            guard let self, self.real.has(.notifMirror), self.real.phase.isConnected else { return }
            Task { _ = try? await self.real.link.request(.notifDismiss(key: key)) }
        }
        notifier.onReply = { [weak self] thread, addr, sim, text in
            guard let self else { return }
            do {
                try self.active.sendSMS(to: addr, body: text, sim: sim, thread: thread)
                self.active.markRead(thread: thread)
                log.info("notify", "Replied from a notification")
            } catch {
                log.warning("notify", "Couldn't reply from a notification: \(error.localizedDescription)")
            }
        }
        notifier.onOpenStatus = { [weak self] in self?.tab = .bridge }
        // Remote mode (D-080): an alert push shown while Pairline is open; take what it announced.
        notifier.onRemotePush = { [weak self] in Task { await self?.remoteCatchUp() } }
        if link.remote?.isOn == true, !Self.isTestHost { UIApplication.shared.registerForRemoteNotifications() }

        // The bridge status on the Lock Screen and in the Dynamic Island follows the bridge on screen (D-053).
        $demo
            .sink { [weak self] demo in
                guard let self, !Self.isTestHost else { return }
                BridgeLiveActivity.shared.follow(demo ?? self.real)
            }
            .store(in: &cancellables)

        link.onJoinedBridgeHotspot = { [weak self] in
            self?.toast = String(localized: "Joined the Android phone’s hotspot. It only links your two phones, so iOS shows “No Internet Connection”; this iPhone uses its own mobile data meanwhile. For the Android phone’s internet, use its own hotspot instead.", table: "Hotspot")
        }
        link.files.onReceived = { [weak self] _ in
            self?.toast = String(localized: "File received. Find it in the Files app under Pairline › Received.")
        }
        // The Share extension queues files in the app group and posts this; send them once Wi-Fi is up.
        CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(), nil, { _, _, _, _, _ in
            Task { @MainActor in AppModel.shared.link.files.collectOutbox() }
        }, SharedOutbox.darwinNotification as CFString, nil, .deliverImmediately)
        link.files.collectOutbox()

        purchases.start()
        applyLaunchArguments()
    }

    /// When the app hosts unit tests, its own link stays idle so it can't touch a real or emulated bridge.
    static let isTestHost = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil

    // MARK: - Free first sync (D-069)

    /// The free first sync before a trial was used (kept in the Keychain).
    @Published private(set) var firstSyncUsed = FirstSyncStore.used
    /// The pairing flow is on screen; the free-week prompt waits until it closes.
    var pairingFlowOpen = false {
        didSet { if oldValue, !pairingFlowOpen { presentTrialPromptSoon() } }
    }
    private var trialPromptPending = false

    /// Pairing is open before a trial: it leads to the free first sync.
    var firstSyncAvailable: Bool {
        FirstSyncPolicy.firstSyncOnly(access: purchases.access, firstSyncUsed: firstSyncUsed)
    }

    /// Whether pairing and the link may run now: a trial, a purchase, or the free first sync.
    var linkAllowed: Bool {
        FirstSyncPolicy.linkAllowed(access: purchases.access, firstSyncUsed: firstSyncUsed)
    }

    /// The first sync before a trial finished: the link waits for the trial or purchase, and the free week is offered.
    private func firstSyncDone() {
        guard !firstSyncUsed else { return }
        FirstSyncStore.markUsed()
        firstSyncUsed = true
        log.info("store", "Free first sync done; the link waits for a trial or purchase")
        accessChanged(purchases.access)
        trialPromptPending = true
        presentTrialPromptSoon()
    }

    private func presentTrialPromptSoon() {
        guard trialPromptPending else { return }
        Task { @MainActor in
            // After the pairing flow or onboarding has finished animating away.
            try? await Task.sleep(nanoseconds: 800_000_000)
            guard self.trialPromptPending, !self.pairingFlowOpen, self.settings.onboardingDone, !self.isDemo,
                  self.purchases.access == .notStarted, !self.showPaywall else { return }
            self.trialPromptPending = false
            self.showPaywall = true
        }
    }

    private func accessChanged(_ access: AccessState) {
        guard access != .loading else { return }
        real.canUseLink = access.allowsLink
        real.firstSyncOnly = FirstSyncPolicy.firstSyncOnly(access: access, firstSyncUsed: firstSyncUsed)
        link.setAllowed(FirstSyncPolicy.linkAllowed(access: access, firstSyncUsed: firstSyncUsed) && !Self.isTestHost)
        switch access {
        case .trial(let end):
            Notifier.shared.scheduleTrialReminder(trialEnd: end, price: purchases.priceText)
        case .purchased:
            Notifier.shared.cancelTrialReminder()
        default:
            break
        }
        real.saveWidget()
    }

    private func peerChanged() {
        let old = pairedPeer
        pairedPeer = peers.peer
        if old != nil, pairedPeer == nil {
            // Unpaired (here or by the bridge): synced data goes too.
            real.wipe()
            toast = String(localized: "Unpaired. Your synced messages were removed from this iPhone.")
        }
        real.saveWidget()
    }

    // MARK: - Pairing

    func pair(with link: PairingLink) async throws {
        if pairedPeer != nil, pairedPeer?.deviceID != link.deviceID {
            // Pairing a different bridge replaces the old one and its data.
            self.link.unpair()
            real.wipe()
        }
        try await self.link.pair(with: link)
        pairedPeer = peers.peer
    }

    func unpair() {
        link.unpair()
        real.wipe()
        pairedPeer = nil
    }

    // MARK: - Remote mode (D-080)

    /// What the bridge left in iCloud, once the purchase state is known (a push can launch the app before it is).
    @discardableResult
    func remoteCatchUp() async -> Bool {
        for _ in 0..<20 where purchases.access == .loading {
            try? await Task.sleep(nanoseconds: 250_000_000)
        }
        // Not covered any more (the trial ended without the add-on, D-081): remote mode is being turned off.
        guard purchases.remoteAccess.allowsRemote else { return false }
        return await link.remoteCatchUp()
    }

    /// Remote mode is part of the trial, then an add-on (D-081). When the purchase state no longer covers it, it turns
    /// off the usual way, which tells the bridge to stop and clears this iPhone's part in iCloud; Settings then shows
    /// why and, after Full Access, the add-on.
    private func remoteAccessChanged(_ access: RemoteAccess) {
        guard access.endsRemote, let remote = link.remote, remote.isOn else { return }
        log.info("store", "Remote mode isn't covered by the purchase state (\(access)); turning it off")
        Task { await link.turnOffRemote() }
    }

    /// A remote pairing ("Pair a phone that's far away") wired to this app's keys, link and iCloud.
    func makeRemotePairing() -> RemotePairing {
        let pairing = RemotePairing(environment: .init(
            identity: { [identity] in try identity.identity() },
            makeMailbox: { RemoteMailbox(backend: RemoteFeature.backend, ids: $0) },
            fetchToken: { try await RemoteCloud.fetchWebAuthToken() },
            adopt: { [weak self] peer, settings in await self?.adoptRemotePairing(peer: peer, settings: settings) },
            rollBack: { [weak self] in self?.unpair() },
            restore: { [weak self] in await self?.link.remote?.resubscribe() }))
        link.onRemotePairConfirm = { [weak pairing] in pairing?.bridgeConfirmed() }
        return pairing
    }

    /// The codes matched: this bridge replaces any paired one (and its data), then the link goes through iCloud.
    private func adoptRemotePairing(peer: PairedPeer, settings: RemoteSettings) async {
        if pairedPeer != nil {
            link.unpair()
            real.wipe()
            await link.remote?.waitForReset()
        }
        link.adoptRemotePairing(peer: peer, settings: settings)
        pairedPeer = peers.peer
    }

    /// Debug builds accept `pairline://pair?…` links (Simulator testing, D-018). Release builds pair only by scanning.
    // MARK: - Calls asked for outside Pairline (D-074)

    /// A number for the keypad, from a `pairline://call?number=…` link. A link only fills in the keypad: any web page
    /// or app can open one, so the user still taps Call.
    @Published var dialerPrefill: String?

    /// A call the user asked for in the iPhone's Recents or with Siri or a Shortcut: placed once the bridge phone is
    /// linked (after a cold start that takes a few seconds).
    func dialFromOutside(_ text: String) {
        guard let number = PhoneNumber.firstNumber(in: text) else {
            toast = String(localized: "No phone number to call.", table: "PhoneTools")
            return
        }
        tab = .calls
        Task { @MainActor in
            for _ in 0..<30 where !self.active.phase.isConnected || self.active.status == nil {
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
            log.info("calls", "Calling back from outside Pairline")
            self.active.calls.startCall(number: number, sim: nil)
        }
    }

    /// iPhone Recents (and Siri) hand a CallKit app's call back as an `INStartCallIntent`.
    func handleCallActivity(_ activity: NSUserActivity) {
        let intent = activity.interaction?.intent
        let handle = (intent as? INStartCallIntent)?.contacts?.first?.personHandle?.value
            ?? (intent as? INStartAudioCallIntent)?.contacts?.first?.personHandle?.value
        guard let handle else {
            log.warning("calls", "A call from Recents came without a number")
            return
        }
        dialFromOutside(handle)
    }

    func handleURL(_ url: URL) {
        if url.host == "status" {
            tab = .bridge
            return
        }
        if url.host == "call" {
            let number = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?.first { $0.name == "number" }?.value
            if let number, let dialable = PhoneNumber.firstNumber(in: number) {
                tab = .calls
                dialerPrefill = dialable
            }
            return
        }
        #if DEBUG
        if url.host == "pair", let pairingLink = try? PairingLink(string: url.absoluteString) {
            log.info("pairing", "Pairing link opened (debug build)")
            Task {
                do {
                    if !linkAllowed {
                        toast = PairingError.locked.errorDescription
                        return
                    }
                    try await pair(with: pairingLink)
                    settings.onboardingDone = true
                    toast = String(localized: "Paired with your bridge phone.")
                } catch {
                    toast = error.localizedDescription
                }
            }
        }
        #endif
    }

    // MARK: - Demo mode

    func enterDemo() {
        guard demo == nil else { return }
        let bridge = DemoBridge()
        let controller = BridgeController(mode: .demo, link: bridge, db: AppDatabase.inMemory())
        demoBridge = bridge
        demo = controller
        tab = .messages
        bridge.start()
        log.info("demo", "Entered demo mode")
    }

    func exitDemo() {
        demoBridge?.stop()
        demoBridge = nil
        demo = nil
        tab = .messages
        log.info("demo", "Left demo mode")
    }

    // MARK: - Lifecycle

    func didBecomeActive() {
        Notifier.shared.clearCallsPaused()
        link.appBecameActive()
        link.reconnectNow()
        link.files.collectOutbox()
        if !Self.isTestHost { BridgeLiveActivity.shared.appBecameActive() }
        Task {
            await purchases.refresh()
            await Notifier.shared.refreshAuthorization()
        }
        if real.phase.isConnected { Task { await real.sync() } }
        Task { await remoteCatchUp() }
    }

    /// Remote mode's direct connection can't wake a suspended app, so it closes unless a call is on (D-080).
    func didEnterBackground() {
        link.appMovedToBackground()
    }

    private func applyLaunchArguments() {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-PairlineDemo") {
            settings.onboardingDone = true
            enterDemo()
        }
        if args.contains("-PairlineResetOnboarding") { settings.onboardingDone = false }
        if let index = args.firstIndex(of: "-PairlineTab"), index + 1 < args.count {
            switch args[index + 1] {
            case "calls": tab = .calls
            case "contacts": tab = .contacts
            case "bridge": tab = .bridge
            case "settings": tab = .settings
            default: tab = .messages
            }
        }
        if args.contains("-PairlinePaywall") { showPaywall = true }
        // Opens pairing at its scan step (screenshots of the free-week choice there, D-069), or at its first step.
        if args.contains("-PairlinePairingScan") || args.contains("-PairlinePairingInstall")
            || args.contains("-PairlinePairingRemote") { showPairing = true }
        // Same path as opening the pairing link through the URL scheme, without the system's confirmation alert.
        if let index = args.firstIndex(of: "-PairlinePairLink"), index + 1 < args.count, let url = URL(string: args[index + 1]) {
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                self.handleURL(url)
            }
        }
        if let index = args.firstIndex(of: "-PairlineThread"), index + 1 < args.count, let thread = Int64(args[index + 1]) {
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                self.openThread = thread
            }
        }
        if args.contains("-PairlineDemoCall") {
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                self.demoBridge?.simulateIncomingCall()
            }
        }
        // Sends one SMS through the link once connected (end-to-end tests against a bridge).
        if let index = args.firstIndex(of: "-PairlineSendSMS"), index + 2 < args.count {
            let to = args[index + 1]
            let body = args[index + 2]
            Task { @MainActor in
                for _ in 0..<30 where !self.active.phase.canReachBridge {
                    try? await Task.sleep(nanoseconds: 500_000_000)
                }
                let message = try? self.active.sendSMS(to: to, body: body, sim: nil, thread: nil)
                if let message { self.openThread = message.thread }
            }
        }
        // Sends one file over Wi-Fi once the bridge can take files (end-to-end tests of the bulk channel).
        if let index = args.firstIndex(of: "-PairlineSendFile"), index + 1 < args.count {
            let url = URL(fileURLWithPath: args[index + 1])
            Task { @MainActor in
                for _ in 0..<120 where !self.link.files.canSend {
                    try? await Task.sleep(nanoseconds: 500_000_000)
                }
                self.link.files.send(url: url)
            }
        }
        // Block list and deleting (D-043) through the link once connected and synced, for end-to-end tests:
        // -PairlineBlock <number>, -PairlineUnblock <number>, -PairlineDeleteThread <thread>,
        // -PairlineDeleteCall <key>, -PairlineClearCalls.
        let value = { (flag: String) -> String? in
            guard let index = args.firstIndex(of: flag), index + 1 < args.count else { return nil }
            return args[index + 1]
        }
        let block = value("-PairlineBlock"), unblock = value("-PairlineUnblock")
        let deleteThread = value("-PairlineDeleteThread").flatMap { Int64($0) }, deleteCall = value("-PairlineDeleteCall")
        let clearCalls = args.contains("-PairlineClearCalls")
        if block != nil || unblock != nil || deleteThread != nil || deleteCall != nil || clearCalls {
            Task { @MainActor in
                for _ in 0..<40 where !self.active.phase.isConnected || self.active.status == nil || self.active.syncing {
                    try? await Task.sleep(nanoseconds: 500_000_000)
                }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if let block { try? await self.active.setBlocked([block], true) }
                if let unblock { try? await self.active.setBlocked([unblock], false) }
                if let deleteThread { self.active.deleteConversations([deleteThread]) }
                if let deleteCall { _ = await self.active.deleteCalls([deleteCall]) }
                if clearCalls { _ = await self.active.clearCallHistory() }
            }
        }
        // Places one call through the link once connected.
        if let index = args.firstIndex(of: "-PairlineDial"), index + 1 < args.count {
            let number = args[index + 1]
            Task { @MainActor in
                for _ in 0..<30 where !self.active.phase.isConnected || self.active.status == nil {
                    try? await Task.sleep(nanoseconds: 500_000_000)
                }
                self.active.calls.startCall(number: number, sim: nil)
            }
        }
        #endif
    }
}
