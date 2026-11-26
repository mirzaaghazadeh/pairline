import AVFoundation
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
    /// The transports with a session right now (Bluetooth, Wi-Fi, or both).
    @Published private(set) var transports: [TransportKind] = []
    @Published private(set) var status: BridgeStatus?
    @Published private(set) var deviceInfo: DeviceInfo?
    @Published private(set) var statusUpdatedAt: Date?
    @Published private(set) var syncing = false
    @Published private(set) var latency: TimeInterval?
    @Published var banner: String?
    /// Internet sharing through the bridge (M8): the last `net.share` reply or event.
    @Published var netShare: NetShareInfo?
    /// Set by the app: whether link features are allowed (trial or purchase). Demo mode is always allowed.
    @Published var canUseLink = true
    /// Set by the app before a trial or purchase (D-069): the link is up only for one sync after pairing. Sending,
    /// calls and everything else still ask for the trial (`canUseLink` stays false).
    @Published var firstSyncOnly = false
    /// Called when that first sync has finished.
    var onFirstSyncDone: (() -> Void)?
    /// A copy of the bridge's block list (D-043); the bridge's own list is the one that counts.
    @Published private(set) var blockedNumbers: [BlockedNumber] = []

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
        self.blockedNumbers = db.blockedNumbers
        calls.bridge = self
        // Call audio (M6) rides on the Wi-Fi session's media channel (M5); nil while there's no open channel.
        calls.mediaTransport = { [weak link] in (link as? LinkManager)?.mediaChannel as? AudioPacketTransport }
        link.onPhaseChange = { [weak self] phase in self?.phaseChanged(phase) }
        link.onTransportsChange = { [weak self] in
            guard let self else { return }
            self.transports = self.link.activeTransports
        }
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

    /// The SIM Android uses by default, or the first active SIM, which the bridge uses where Android asks every time.
    func androidDefaultSim(forSMS: Bool) -> Int? {
        sims.first(where: { (forSMS ? $0.defaultSms : $0.defaultVoice) == true })?.slot ?? sims.first?.slot
    }

    /// The SIM a new call or message from this iPhone goes out on: the one chosen in Settings, else Android's (D-062).
    func effectiveSim(forSMS: Bool) -> Int? {
        defaultSim(forSMS: forSMS) ?? androidDefaultSim(forSMS: forSMS)
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
        return !RemoteFeature.compatibleVersions.contains(proto)
    }

    /// The bridge speaks remote mode (D-072): it sent `proto` 1 or later.
    var bridgeSupportsRemote: Bool { (deviceInfo?.proto ?? 0) >= 1 }

    func requestPaywall() { onPaywallRequest?() }

    // MARK: - Link events

    private func phaseChanged(_ phase: LinkPhase) {
        let wasRemote = self.phase == .remote
        self.phase = phase
        transports = link.activeTransports
        if phase != .connected {
            latencyTimer?.invalidate()
            latencyTimer = nil
            calls.linkLost()
        }
        // Remote mode (D-072): texts written while the bridge was away go through iCloud.
        if phase == .remote, !wasRemote, canUseLink { Task { await flushOutbox() } }
        saveWidget()
    }

    private func established() {
        Task {
            if let reply = try? await link.request(.statusGet), case .status(let s) = reply.message {
                applyStatus(s)
            }
            await sync()
            await flushOutbox()
            await refreshBlockList()
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
            if let proto = info.proto, !RemoteFeature.compatibleVersions.contains(proto) {
                log.warning("link", "Bridge speaks protocol \(proto)")
            }
        case .status(let s):
            applyStatus(s)
        case .smsNew(let message):
            let inserted = db.upsertMessages([message], countUnread: Notifier.shared.visibleThread != message.thread)
            // Through iCloud the notification service extension may have shown it already.
            for stored in inserted where !stored.isOutgoing && !inbound.alreadyShown {
                notify(stored)
            }
            if !inserted.isEmpty { Haptics.soft() }
            updateBadge()
        case .smsState(let event):
            let state = MessageState(rawValue: event.state) ?? .sent
            db.updateState(cid: event.cid, state: state, key: event.key, error: event.error)
            if state == .failed { Haptics.warning() }
        // Calls through the iCloud mailbox may be minutes old, and can't be answered without a direct connection:
        // they ring only from a session (the extension already showed a call that came through iCloud).
        case .callIncoming(let event) where !inbound.viaMailbox:
            // During the free first sync (D-069) calls aren't brought here: they need the trial or the purchase.
            if canUseLink { calls.handleIncoming(event) }
        case .callState(let event) where !inbound.viaMailbox:
            calls.handleState(event)
        case .callMediaState(let event) where !inbound.viaMailbox:
            calls.handleMediaState(event)
        case .netShare(let info) where inbound.id == nil:
            // The bridge stopped sharing by itself (its user turned it off).
            netShare = info
        case .blockList(let list):
            // Changed in the bridge app itself.
            applyBlockList(list)
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
        requestMicrophoneIfNeeded(s)
    }

    /// The bridge just offered "Talk on iPhone": ask for the microphone now, while the app is open, rather than in
    /// the middle of the first call. iOS asks only once; the answer is kept in Settings › Pairline.
    private func requestMicrophoneIfNeeded(_ s: BridgeStatus) {
        guard mode == .real, s.caps?.contains(Capability.callMediaTalk.rawValue) == true,
              UIApplication.shared.applicationState == .active else { return }
        let session = AVAudioSession.sharedInstance()
        guard session.recordPermission == .undetermined else { return }
        session.requestRecordPermission { granted in
            log.info("audio", "Microphone \(granted ? "allowed" : "denied") for calls")
        }
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

    /// The status the widget and the Live Activity show: link state, battery and each SIM's carrier, network and signal.
    func statusSnapshot() -> WidgetSnapshot {
        let link: WidgetSnapshot.Link
        switch phase {
        case .connected: link = .connected
        case .remote: link = .remote
        case .searching, .securing, .pairing, .waiting: link = .connecting
        case .notPaired: link = .notPaired
        case .locked: link = .locked
        default: link = .offline
        }
        return WidgetSnapshot(link: link, batteryLevel: status?.battery?.level, charging: status?.battery?.charging,
                              sims: sims.map { .init(slot: $0.slot, carrier: $0.carrier ?? $0.label,
                                                     signal: $0.signal, net: $0.net) },
                              updated: statusUpdatedAt ?? Date(), isDemo: isDemo)
    }

    func saveWidget() {
        guard mode == .real else { return }
        let snapshot = statusSnapshot()
        var comparable = snapshot
        comparable.updated = lastWidgetSave?.updated ?? snapshot.updated
        guard comparable != lastWidgetSave else { return }
        lastWidgetSave = snapshot
        snapshot.save()
        WidgetCenter.shared.reloadAllTimelines()
    }

    // MARK: - Sync

    /// Pull-to-refresh and reconnects: new messages, contacts and call log. Through iCloud (remote mode) only new
    /// messages: contacts, the call log and a first full sync are too large for the mailbox.
    func sync() async {
        guard canUseLink || firstSyncOnly, phase.canReachBridge, !syncing else { return }
        syncing = true
        defer { syncing = false }
        if has(.smsHistory) { await syncMessages() }
        if phase.isConnected {
            if has(.contacts) { await syncContacts() }
            if has(.calllog) { await syncCallLog() }
        }
        updateBadge()
        // The free first sync counts only once it ran to the end on a live link (D-069).
        if firstSyncOnly, !canUseLink, phase.isConnected { onFirstSyncDone?() }
    }

    private func syncMessages() async {
        // Smaller pages through iCloud, where a record holds at most 48 KB.
        let pageSize = phase.isConnected ? 100 : 30
        do {
            if let newest = db.newestMessageTime {
                var after = newest
                var newInbound: [StoredMessage] = []
                for _ in 0..<20 {
                    guard let reply = try await historyPage(thread: nil, before: nil, after: after, limit: pageSize)
                    else { break }
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
            } else if phase.isConnected {
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
        guard phase.isConnected else { return }
        var cursor = db.value("contacts.cursor").flatMap(OpaqueValue.init(storageString:))
        var replaced = false
        do {
            for _ in 0..<50 {
                let reply = try await link.request(.contactsSync(since: cursor))
                guard case .contactsSync(let page) = reply.message else { break }
                if page.full && !replaced {
                    db.clearContacts()
                    replaced = true
                }
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
        guard phase.isConnected else { return }
        var cursor = db.value("calllog.cursor").flatMap(OpaqueValue.init(storageString:))
        var replaced = false
        do {
            for _ in 0..<50 {
                let reply = try await link.request(.calllogSync(since: cursor))
                guard case .calllogSync(let page) = reply.message else { break }
                if page.full && !replaced {
                    db.clearCalls()
                    replaced = true
                }
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
        guard let cid = message.cid, !inFlight.contains(cid), phase.canReachBridge else { return }
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

    // MARK: - Deleting (D-043)

    /// Deletes conversations from this iPhone. Android lets only its default messaging app delete texts, so they
    /// stay on the bridge phone; a later sync doesn't bring them back.
    func deleteConversations(_ threads: [Int64]) {
        guard !threads.isEmpty else { return }
        db.deleteConversations(threads)
        if mode == .real { threads.forEach { Notifier.shared.removeDelivered(thread: $0) } }
        log.info("sms", "Deleted \(threads.count) conversation(s) on this iPhone")
        updateBadge()
    }

    /// Deletes single messages from this iPhone (see `deleteConversations`).
    func deleteMessages(_ keys: [String]) {
        guard !keys.isEmpty else { return }
        db.deleteMessages(keys: keys)
        log.info("sms", "Deleted \(keys.count) message(s) on this iPhone")
        updateBadge()
    }

    enum CallDeletion {
        /// Deleted here and on the bridge phone.
        case bothPhones
        /// The bridge can't delete calls right now (not connected, no permission, or no purchase): this iPhone only.
        case iPhoneOnly
        /// The bridge was asked but couldn't: this iPhone only.
        case bridgeFailed
    }

    /// Whether deleting call history right now also deletes it on the bridge phone.
    var deletesCallsOnBridge: Bool { canUseLink && phase.isConnected && has(.calllogDelete) }

    /// Deletes call-history entries here and, when it can, on the bridge phone (`calllog.delete`).
    func deleteCalls(_ keys: [String]) async -> CallDeletion {
        guard !keys.isEmpty else { return .bothPhones }
        guard deletesCallsOnBridge else {
            db.deleteCalls(keys: keys, remember: true)
            return .iPhoneOnly
        }
        do {
            for start in stride(from: 0, to: keys.count, by: 500) {
                _ = try await link.request(.calllogDelete(keys: Array(keys[start..<min(start + 500, keys.count)])))
            }
            db.deleteCalls(keys: keys, remember: false)
            log.info("calls", "Deleted \(keys.count) call(s) on both phones")
            return .bothPhones
        } catch {
            log.warning("calls", "The bridge couldn't delete calls: \(error.localizedDescription)")
            db.deleteCalls(keys: keys, remember: true)
            return .bridgeFailed
        }
    }

    /// "Clear all": the whole call history, here and, when it can, on the bridge phone.
    func clearCallHistory() async -> CallDeletion {
        guard deletesCallsOnBridge else {
            db.clearCallHistory(remember: true)
            return .iPhoneOnly
        }
        do {
            _ = try await link.request(.calllogDeleteAll)
            db.clearCallHistory(remember: false)
            log.info("calls", "Cleared the call history on both phones")
            return .bothPhones
        } catch {
            log.warning("calls", "The bridge couldn't clear its call history: \(error.localizedDescription)")
            db.clearCallHistory(remember: true)
            return .bridgeFailed
        }
    }

    // MARK: - Block list (D-043)

    func isBlocked(_ number: String) -> Bool {
        !number.isEmpty && blockedNumbers.contains { PhoneNumber.blockMatches($0.n, number) }
    }

    /// Fetches the bridge's list (after every connect).
    func refreshBlockList() async {
        guard canUseLink, phase.isConnected, has(.block) else { return }
        if let reply = try? await link.request(.blockList), case .blockList(let list) = reply.message {
            applyBlockList(list)
        }
    }

    /// Blocks or unblocks numbers (or sender names) on the bridge phone, which keeps the list that counts.
    func setBlocked(_ numbers: [String], _ blocked: Bool) async throws {
        guard !numbers.isEmpty else { return }
        guard canUseLink else {
            requestPaywall()
            throw ActionError.locked
        }
        guard status == nil || has(.block) else { throw ActionError.missingCapability(.block) }
        let message: Outbound = blocked ? .blockSet(add: numbers, remove: []) : .blockSet(add: [], remove: numbers)
        let reply = try await link.request(message)
        if case .blockList(let list) = reply.message { applyBlockList(list) }
        log.info("block", blocked ? "Blocked \(numbers.count) number(s)" : "Unblocked \(numbers.count) number(s)")
        Haptics.success()
    }

    private func applyBlockList(_ list: [BlockedNumber]) {
        guard list != blockedNumbers else { return }
        blockedNumbers = list
        db.setBlockedNumbers(list)
    }

    /// The bridge tab's pull-to-refresh; through iCloud too (remote mode), without the latency.
    func refreshStatus() async {
        guard phase.canReachBridge else { return }
        if let reply = try? await link.request(.statusGet), case .status(let s) = reply.message {
            applyStatus(s)
        }
        if phase.isConnected { await measureLatency() }
    }

    /// Clears synced data (unpair).
    func wipe() {
        db.wipe()
        status = nil
        deviceInfo = nil
        statusUpdatedAt = nil
        blockedNumbers = []
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
    static let website = URL(string: "https://getpairline.com")!
    static let privacyURL = URL(string: "https://getpairline.com/privacy")!
    static let termsURL = URL(string: "https://getpairline.com/terms")!
    static let sourceURL = URL(string: "https://github.com/mirzaaghazadeh/pairline")!
    static let downloadURL = URL(string: "https://getpairline.com")!

    static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    static var build: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
    }
}
