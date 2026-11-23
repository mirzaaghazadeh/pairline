import Combine
import Foundation
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
        link = LinkManager(identityStore: identity, peerStore: peers, deviceInfo: deviceInfo) { kind in
            #if DEBUG
            if kind == .tcp { return TCPConnector() }
            #endif
            return BluetoothCentral()
        }
        real = BridgeController(mode: .real, link: link, db: AppDatabase.openDefault())
        pairedPeer = peers.peer
        real.onPaywallRequest = { [weak self] in self?.showPaywall = true }
        link.onPeerChange = { [weak self] in self?.peerChanged() }

        purchases.$access
            .removeDuplicates()
            .sink { [weak self] access in self?.accessChanged(access) }
            .store(in: &cancellables)

        let notifier = Notifier.shared
        notifier.configure()
        notifier.onOpenThread = { [weak self] thread in
            self?.tab = .messages
            self?.openThread = thread
        }
        notifier.onMarkRead = { [weak self] thread in
            self?.active.db.markRead(thread: thread)
            self?.active.updateBadge()
        }
        notifier.onOpenStatus = { [weak self] in self?.tab = .bridge }

        purchases.start()
        applyLaunchArguments()
    }

    /// When the app hosts unit tests, its own link stays idle so it can't touch a real or emulated bridge.
    static let isTestHost = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil

    private func accessChanged(_ access: AccessState) {
        guard access != .loading else { return }
        real.canUseLink = access.allowsLink
        link.setAllowed(access.allowsLink && !Self.isTestHost)
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

    /// Debug builds accept `pairline://pair?…` links (Simulator testing, D-018). Release builds pair only by scanning.
    func handleURL(_ url: URL) {
        if url.host == "status" {
            tab = .bridge
            return
        }
        #if DEBUG
        if url.host == "pair", let pairingLink = try? PairingLink(string: url.absoluteString) {
            log.info("pairing", "Pairing link opened (debug build)")
            Task {
                do {
                    if !purchases.access.allowsLink {
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
        link.reconnectNow()
        Task {
            await purchases.refresh()
            await Notifier.shared.refreshAuthorization()
        }
        if real.phase.isConnected { Task { await real.sync() } }
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
        if args.contains("-PairlineDemoOutgoingCall") {
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                if let number = self.demoBridge?.content.people.first?.number {
                    self.active.calls.startCall(number: number, sim: 0)
                }
            }
        }
        #endif
    }
}
