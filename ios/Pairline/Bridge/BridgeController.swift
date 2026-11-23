import Foundation
import UIKit
import WidgetKit

/// Everything the UI needs from one bridge: link state, status, synced data and actions. There is one for the real
/// link and a separate one (with its own in-memory database) for demo mode, so the two never mix.
@MainActor
final class BridgeController: ObservableObject {
    enum Mode {
        case real
        case demo
    }

    enum ActionError: LocalizedError {
        case locked
        case missingCapability(Capability)

        var errorDescription: String? {
            switch self {
            case .locked:
                return String(localized: "Your free trial has ended. Unlock Pairline to keep using your bridge phone.")
            case .missingCapability(let capability):
                return CapabilityInfo(capability).missingText
            }
        }
    }

    let mode: Mode
    let link: BridgeLink
    let db: AppDatabase
    let calls: CallCenter

    @Published private(set) var phase: LinkPhase
    @Published private(set) var status: BridgeStatus?
    @Published private(set) var deviceInfo: DeviceInfo?
    @Published private(set) var statusUpdatedAt: Date?
    @Published private(set) var syncing = false
    @Published private(set) var latency: TimeInterval?
    @Published var banner: String?
    /// Set by the app: whether link features are allowed (trial or purchase). Demo mode is always allowed.
    @Published var canUseLink = true

    var onPaywallRequest: (() -> Void)?
    var isDemo: Bool { mode == .demo }

    private var inFlight = Set<String>()
    private var lowBatteryNotified = false
    private var lastWidgetSave: WidgetSnapshot?
    private var latencyTimer: Timer?

    init(mode: Mode, link: BridgeLink, db: AppDatabase) {
        self.mode = mode
        self.link = link
        self.db = db
        self.phase = link.phase
        self.calls = CallCenter(useCallKit: mode == .real)
        calls.bridge = self
        link.onPhaseChange = { [weak self] phase in self?.phaseChanged(phase) }
        link.onEvent = { [weak self] inbound in self?.handle(inbound) }
        link.onEstablished = { [weak self] in self?.established() }
        if mode == .real { saveWidget() }
    }

    // MARK: - Capabilities

    var caps: Set<String> { Set(status?.caps ?? []) }

    func has(_ capability: Capability) -> Bool { caps.contains(capability.rawValue) }

    var sims: [SIMInfo] { (status?.sims ?? []).sorted { $0.slot < $1.slot } }
    var isDualSIM: Bool { sims.count > 1 }

    func defaultSim(forSMS: Bool) -> Int? {
        let stored = forSMS ? AppSettings.shared.defaultSMSSim : AppSettings.shared.defaultCallSim
        if stored >= 0, sims.contains(where: { $0.slot == stored }) { return stored }
        return nil
    }

    func simLabel(_ slot: Int?) -> String {
        guard let slot else { return String(localized: "Default SIM") }
        if let sim = sims.first(where: { $0.slot == slot }) {
            return sim.label ?? sim.carrier ?? String(localized: "SIM \(slot + 1)")
        }
        return String(localized: "SIM \(slot + 1)")
    }

    /// The bridge app version is older than this iPhone app expects.
    var bridgeNeedsUpdate: Bool {
        guard let app = status?.app ?? deviceInfo?.app else { return false }
        return Version(app) < Version(AppInfo.minimumBridgeVersion)
    }

    var protocolMismatch: Bool {
        guard let proto = deviceInfo?.proto else { return false }
        return proto != Int(WireProtocol.version)
    }

    func requestPaywall() { onPaywallRequest?() }

    // MARK: - Link events

    private func phaseChanged(_ phase: LinkPhase) {
        self.phase = phase
        if phase != .connected {
            latencyTimer?.invalidate()
            latencyTimer = nil
            calls.linkLost()
        }
        saveWidget()
    }

    private func established() {
        Task {
            if let reply = try? await link.request(.statusGet), case .status(let s) = reply.message {
                applyStatus(s)
            }
            await sync()
            await flushOutbox()
            await measureLatency()
        }
        latencyTimer?.invalidate()
        latencyTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, UIApplication.shared.applicationState == .active else { return }
                await self.measureLatency()
            }
        }
    }

    func measureLatency() async {
        await link.measureLatency()
        latency = link.latency
    }

    private func handle(_ inbound: Inbound) {
        switch inbound.message {
        case .deviceInfo(let info):
            deviceInfo = info
            if info.proto != nil, info.proto != Int(WireProtocol.version) {
                log.warning("link", "Bridge speaks protocol \(info.proto ?? -1)")
            }
        case .status(let s):
            applyStatus(s)
        case .smsNew(let message):
            let inserted = db.upsertMessages([message], countUnread: Notifier.shared.visibleThread != message.thread)
            for stored in inserted where !stored.isOutgoing {
                notify(stored)
            }
            if !inserted.isEmpty { Haptics.soft() }
            updateBadge()
        case .smsState(let event):
            let state = MessageState(rawValue: event.state) ?? .sent
            db.updateState(cid: event.cid, state: state, key: event.key, error: event.error)
            if state == .failed { Haptics.warning() }
        case .callIncoming(let event):
            calls.handleIncoming(event)
        case .callState(let event):
            calls.handleState(event)
        default:
            break
        }
    }

    private func applyStatus(_ s: BridgeStatus) {
        status = s
        statusUpdatedAt = Date()
        if let level = s.battery?.level, s.battery?.charging != true {
            if level <= 15, !lowBatteryNotified, mode == .real {
                lowBatteryNotified = true
                Notifier.shared.notifyLowBattery(level: level)
            } else if level > 20 {
                lowBatteryNotified = false
            }
        }
        saveWidget()
    }

    private func notify(_ message: StoredMessage) {
        let sender = db.displayName(for: message.addr)
        Notifier.shared.notifyMessage(message, sender: sender, isDemo: isDemo)
    }

    func updateBadge() {
        guard mode == .real else { return }
        Notifier.shared.setBadge(db.unreadCount)
    }

    func callEnded() {
        guard has(.calllog), canUseLink else { return }
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            await syncCallLog()
        }
    }

    // MARK: - Widget

    func saveWidget() {
        guard mode == .real else { return }
        let link: WidgetSnapshot.Link
        switch phase {
        case .connected: link = .connected
        case .searching, .securing, .pairing, .waiting: link = .connecting
        case .notPaired: link = .notPaired
        case .locked: link = .locked
        default: link = .offline
        }
        let snapshot = WidgetSnapshot(link: link, batteryLevel: status?.battery?.level, charging: status?.battery?.charging,
                                      sims: sims.map { .init(slot: $0.slot, carrier: $0.carrier ?? $0.label,
                                                             signal: $0.signal, net: $0.net) },
                                      updated: statusUpdatedAt ?? Date(), isDemo: false)
        var comparable = snapshot
        comparable.updated = lastWidgetSave?.updated ?? snapshot.updated
        guard comparable != lastWidgetSave else { return }
        lastWidgetSave = snapshot
        snapshot.save()
        WidgetCenter.shared.reloadAllTimelines()
    }

    // MARK: - Sync

    /// Pull-to-refresh and reconnects: new messages, contacts and call log.
    func sync() async {
        guard canUseLink, phase.isConnected, !syncing else { return }
        syncing = true
        defer { syncing = false }
        if has(.smsHistory) { await syncMessages() }
        if has(.contacts) { await syncContacts() }
        if has(.calllog) { await syncCallLog() }
        updateBadge()
    }

    private func syncMessages() async {
        do {
            if let newest = db.newestMessageTime {
                var after = newest
                var newInbound: [StoredMessage] = []
                for _ in 0..<20 {
                    guard let reply = try await historyPage(thread: nil, before: nil, after: after, limit: 100) else { break }
                    let inserted = db.upsertMessages(reply.msgs, countUnread: true)
                    newInbound += inserted.filter { !$0.isOutgoing }
                    guard reply.more, let maxTime = reply.msgs.map(\.time).max(), maxTime > after else { break }
                    after = maxTime
                }
                // Catch up on notifications for messages that arrived while the link was down.
                let recent = newInbound.filter { Date().timeIntervalSince($0.time) < 24 * 3600 }
                if recent.count > 3 {
                    Notifier.shared.notifyCaughtUp(count: recent.count)
                } else {
                    recent.forEach(notify)
                }
            } else {
                var before: Int64?
                for _ in 0..<10 {
                    let reply = try await link.request(.smsThreads(limit: 50, before: before))
                    guard case .smsThreads(let page) = reply.message else { break }
                    db.upsertThreads(page.threads)
                    guard page.more, let oldest = page.threads.compactMap(\.time).min(), oldest != before else { break }
                    before = oldest
                }
                var historyBefore: Int64?
                for _ in 0..<5 {
                    guard let reply = try await historyPage(thread: nil, before: historyBefore, after: nil, limit: 100)
                    else { break }
                    db.upsertMessages(reply.msgs, countUnread: false)
                    if !reply.more { db.markAllThreadsComplete() }
                    guard reply.more, let oldest = reply.msgs.map(\.time).min(), oldest != historyBefore else { break }
                    historyBefore = oldest
                }
            }
        } catch {
            log.warning("sync", "Message sync stopped: \(error.localizedDescription)")
        }
    }

    private func historyPage(thread: Int64?, before: Int64?, after: Int64?, limit: Int) async throws -> SMSHistoryReply? {
        let reply = try await link.request(.smsHistory(thread: thread, before: before, after: after, limit: limit))
        guard case .smsHistory(let page) = reply.message else { return nil }
        return page
    }

    /// Loads older messages of one conversation. Returns whether more exist.
    @discardableResult
    func loadOlder(thread: Int64) async -> Bool {
        guard thread >= 0, canUseLink, phase.isConnected, has(.smsHistory) else { return false }
        let before = db.oldestMessageTime(thread: thread)
        do {
            guard let page = try await historyPage(thread: thread, before: before, after: nil, limit: 50) else { return false }
            db.upsertMessages(page.msgs, countUnread: false)
            if !page.more { db.setHasOlder(thread: thread, false) }
            return page.more
        } catch {
            return true
        }
    }

    private func syncContacts() async {
        var cursor = db.value("contacts.cursor").flatMap(OpaqueValue.init(storageString:))
        do {
            for _ in 0..<50 {
                let reply = try await link.request(.contactsSync(since: cursor))
                guard case .contactsSync(let page) = reply.message else { break }
                if page.reset { db.clearContacts() }
                db.upsertContacts(page.contacts, deleted: page.deleted)
                if let next = page.cursor { cursor = next }
                db.setValue(cursor?.storageString, for: "contacts.cursor")
                guard page.more else { break }
            }
        } catch {
            log.warning("sync", "Contacts sync stopped: \(error.localizedDescription)")
        }
    }

    private func syncCallLog() async {
        var cursor = db.value("calllog.cursor").flatMap(OpaqueValue.init(storageString:))
        do {
            for _ in 0..<50 {
                let reply = try await link.request(.calllogSync(since: cursor))
                guard case .calllogSync(let page) = reply.message else { break }
                if page.reset { db.clearCalls() }
                db.upsertCalls(page.calls)
                if let next = page.cursor { cursor = next }
                db.setValue(cursor?.storageString, for: "calllog.cursor")
                guard page.more else { break }
            }
        } catch {
            log.warning("sync", "Call-log sync stopped: \(error.localizedDescription)")
        }
    }

    // MARK: - Messages

    /// Adds the message to the conversation right away and sends it when the bridge is connected.
    @discardableResult
    func sendSMS(to address: String, body: String, sim: Int?, thread: Int64?) throws -> StoredMessage {
        guard canUseLink else {
            requestPaywall()
            throw ActionError.locked
        }
        guard status == nil || has(.smsSend) else { throw ActionError.missingCapability(.smsSend) }
        let cid = "c-" + UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16).lowercased()
        let message = db.insertPending(thread: thread, to: address, body: body, sim: sim, cid: cid)
        Haptics.tap()
        Task { await deliver(message) }
        return message
    }

    func retry(_ message: StoredMessage) {
        guard let cid = message.cid else { return }
        guard canUseLink else { return requestPaywall() }
        db.updateState(cid: cid, state: .pending, key: nil, error: nil)
        Task { await deliver(message) }
    }

    private func deliver(_ message: StoredMessage) async {
        guard let cid = message.cid, !inFlight.contains(cid), phase.isConnected else { return }
        inFlight.insert(cid)
        defer { inFlight.remove(cid) }
        do {
            _ = try await link.request(.smsSend(sim: message.sim, to: message.addr, body: message.body, cid: cid))
        } catch LinkError.notConnected {
            // Stays pending; sent when the bridge reconnects.
        } catch {
            db.markFailed(cid: cid, error: error.localizedDescription)
            Haptics.warning()
        }
    }

    /// Sends messages written while the bridge was away.
    private func flushOutbox() async {
        for conversation in db.conversations() {
            for message in db.messages(thread: conversation.thread)
            where message.state == .pending && message.key.hasPrefix("local:") {
                await deliver(message)
            }
        }
    }

    // MARK: - Other actions

    func runUSSD(code: String, sim: Int?) async throws -> USSDResult {
        guard canUseLink else {
            requestPaywall()
            throw ActionError.locked
        }
        guard has(.ussd) else { throw ActionError.missingCapability(.ussd) }
        let reply = try await link.request(.ussdRun(sim: sim, code: PhoneNumber.dialable(code)))
        guard case .ussdResult(let result) = reply.message else {
            return USSDResult(ok: true, text: nil, error: nil)
        }
        return result
    }

    func addContact(name: String, numbers: [ContactNumber]) async throws {
        guard canUseLink else {
            requestPaywall()
            throw ActionError.locked
        }
        guard has(.contactsAdd) else { throw ActionError.missingCapability(.contactsAdd) }
        _ = try await link.request(.contactsAdd(name: name, numbers: numbers))
        await syncContacts()
    }

    func refreshStatus() async {
        guard phase.isConnected else { return }
        if let reply = try? await link.request(.statusGet), case .status(let s) = reply.message {
            applyStatus(s)
        }
        await measureLatency()
    }

    /// Clears synced data (unpair).
    func wipe() {
        db.wipe()
        status = nil
        deviceInfo = nil
        statusUpdatedAt = nil
        if mode == .real {
            WidgetSnapshot.clear()
            WidgetCenter.shared.reloadAllTimelines()
            Notifier.shared.removeAll()
        }
    }
}

/// Simple dotted version comparison ("0.1.10" > "0.1.9").
struct Version: Comparable {
    let parts: [Int]

    init(_ text: String) {
        parts = text.split(whereSeparator: { !$0.isNumber && $0 != "." }).first.map {
            $0.split(separator: ".").map { Int($0) ?? 0 }
        } ?? [0]
    }

    static func < (lhs: Version, rhs: Version) -> Bool {
        for index in 0..<max(lhs.parts.count, rhs.parts.count) {
            let a = index < lhs.parts.count ? lhs.parts[index] : 0
            let b = index < rhs.parts.count ? rhs.parts[index] : 0
            if a != b { return a < b }
        }
        return false
    }

    static func == (lhs: Version, rhs: Version) -> Bool { !(lhs < rhs) && !(rhs < lhs) }
}

enum AppInfo {
    /// Oldest bridge app this iPhone app works well with. Raise when the protocol gains features the app relies on.
    static let minimumBridgeVersion = "0.1.0"
    static let website = URL(string: "https://pairline.navid.tr")!
    static let privacyURL = URL(string: "https://pairline.navid.tr/privacy")!
    static let termsURL = URL(string: "https://pairline.navid.tr/terms")!
    static let sourceURL = URL(string: "https://github.com/mirzaaghazadeh/pairline")!
    static let downloadURL = URL(string: "https://pairline.navid.tr")!

    static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    static var build: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
    }
}
