import CloudKit
import CryptoKit
import Foundation

/// Why remote mode ("Cloud mode" on screen) isn't working right now (the status in Settings).
enum RemoteProblem: Equatable {
    case iCloudUnavailable
    case offline
    /// Records this iPhone wrote for the bridge stayed unread for 10 minutes.
    case bridgeNotReading
    case failed

    init(_ error: Error) {
        switch (error as? CKError)?.code {
        case .notAuthenticated?, .accountTemporarilyUnavailable?, .permissionFailure?:
            self = .iCloudUnavailable
        case .networkUnavailable?, .networkFailure?, .serviceUnavailable?, .requestRateLimited?, .zoneBusy?:
            self = .offline
        default:
            self = .failed
        }
    }

    var text: String {
        switch self {
        case .iCloudUnavailable: return String(localized: "Sign in to iCloud on this iPhone")
        case .offline: return String(localized: "Can't reach iCloud right now")
        case .bridgeNotReading: return String(localized: "The bridge phone hasn't checked in for 10 minutes")
        case .failed: return String(localized: "iCloud isn't working right now")
        }
    }

    /// What to do about it, for the Settings footer: every problem gets a next step.
    var nextStep: String {
        switch self {
        case .iCloudUnavailable:
            return String(localized: "Sign in to iCloud in this iPhone's Settings. Cloud mode uses your own iCloud.", table: "RemoteMode")
        case .offline:
            return String(localized: "Check this iPhone's internet connection. Pairline keeps trying.", table: "RemoteMode")
        case .bridgeNotReading:
            return String(localized: "Make sure the Android phone is on, online and running Pairline Bridge. What you send waits until it checks in.", table: "RemoteMode")
        case .failed:
            return String(localized: "Pairline keeps trying. If this doesn't clear up, turn Cloud mode off and on again while the phones are linked nearby.", table: "RemoteMode")
        }
    }
}

/// Why remote mode ("Cloud mode") couldn't be turned on, or a remote pairing couldn't start.
enum RemoteError: LocalizedError, Equatable {
    case needsNearbyLink
    case bridgeUpdate
    case bridgeCantReachICloud
    case iCloudUnavailable
    case offline
    case failed

    init(_ error: Error) {
        if let remote = error as? RemoteError {
            self = remote
        } else if let link = error as? LinkError {
            switch link {
            case .notConnected: self = .needsNearbyLink
            case .timeout: self = .failed
            case .bridge(let reply): self = reply.code == "failed" ? .bridgeCantReachICloud : .bridgeUpdate
            }
        } else {
            switch RemoteProblem(error) {
            case .iCloudUnavailable: self = .iCloudUnavailable
            case .offline: self = .offline
            default: self = .failed
            }
        }
    }

    var errorDescription: String? {
        switch self {
        case .needsNearbyLink:
            return String(localized: "Turn Cloud mode on while your phones are linked nearby, over Bluetooth or the same Wi-Fi.", table: "RemoteMode")
        case .bridgeUpdate:
            return String(localized: "Cloud mode needs a newer Pairline Bridge on the Android phone.", table: "RemoteMode")
        case .bridgeCantReachICloud:
            return String(localized: "The bridge phone couldn't reach iCloud. Check its internet connection and try again.")
        case .iCloudUnavailable:
            return String(localized: "Sign in to iCloud on this iPhone, then try again.")
        case .offline:
            return String(localized: "Couldn't reach iCloud. Check this iPhone's internet connection and try again.")
        case .failed:
            return String(localized: "That didn't work. Try again.")
        }
    }
}

/// PROTOCOL.md, "Receiving": what this iPhone accepts from its mailboxes.
enum RemoteReceiving {
    static let maxAge: TimeInterval = 14 * 24 * 3600
    static let maxAhead: TimeInterval = 10 * 60

    /// No more than 14 days old and no more than 10 minutes ahead.
    static func isFresh(_ time: UInt64, now: Date) -> Bool {
        let sent = Double(time) / 1000
        let current = now.timeIntervalSince1970
        return sent >= current - maxAge && sent <= current + maxAhead
    }

    /// Opens a kind 1 record the pinned bridge sent to this iPhone, or returns nil to drop it. The record id must be
    /// the CloudKit record name too, so a copy saved under another name can't slip past the handled-ids check.
    static func open(_ record: RemoteMailbox.Record, identity: KeyAgreementPrivateKey,
                     bridgeKey: P256.KeyAgreement.PublicKey, now: Date) -> Data? {
        guard let header = try? MailboxHeader(parsing: record.sealed), header.kind == .sealed,
              header.direction == .bridgeToRemote, header.recordName == record.name, isFresh(header.time, now: now),
              let opened = try? MailboxCrypto.open(record.sealed, identity: identity, sender: bridgeKey,
                                                   direction: .bridgeToRemote) else { return nil }
        return opened.plaintext
    }
}

/// Remote mode's mailbox path (D-080; PROTOCOL.md, "Remote mode"): the third way to the bridge after Bluetooth and
/// Wi-Fi, used while no session is up. It seals messages for the bridge into the user's iCloud, catches up on what
/// the bridge left there, and keeps the bridge supplied with web auth tokens. `LinkManager` routes through it.
///
/// Nothing here logs record contents, tokens, keys or mailbox ids.
@MainActor
final class RemoteLink: ObservableObject {
    struct Delivery {
        let plaintext: Data
        /// The notification service extension already showed it.
        let alreadyShown: Bool
    }

    /// A record sealed for the bridge, ready to write.
    struct SealedRecord {
        let name: String
        let data: Data
    }

    typealias MailboxFactory = (RemoteInvitation.Backend, RemoteMailboxes) -> RemoteMailboxing

    /// `remote.token` every 12 hours, and at once when the bridge leaves records unread for 10 minutes.
    static let tokenInterval: TimeInterval = 12 * 3600
    static let unreadLimit: TimeInterval = 10 * 60
    /// A sender deletes its own records older than one day.
    static let ownRecordLifetime: TimeInterval = 24 * 3600
    static let pageSize = 50

    @Published private(set) var settings: RemoteSettings?
    @Published private(set) var problem: RemoteProblem? {
        didSet {
            // The log notes when the mailbox recovers, and when the bridge stops reading it (errors log themselves).
            guard problem != oldValue else { return }
            if problem == nil, let oldValue {
                log.info("remote", "Mailbox working again (was: \(oldValue))")
            } else if problem == .bridgeNotReading {
                log.warning("remote", "The bridge hasn't read the mailbox for 10 minutes")
            }
        }
    }
    /// What the bridge last said about its direct connection (`remote.addr`, `remote.state`).
    @Published private(set) var direct: RemoteDirect

    /// A message from the bridge, handled like a session's plaintext.
    var onMessage: ((Delivery) -> Void)?
    /// Remote mode went on or off.
    var onChange: (() -> Void)?
    /// Sends on a live session when there is one; returns false when there's none.
    var sendOverSession: ((Outbound) -> Bool)?
    var now: () -> Date = Date.init

    private let store: RemoteStore
    private let inbox: RemoteInboxing
    private let identity: () throws -> KeyAgreementPrivateKey
    private let bridgeKey: () -> Data?
    private let makeMailbox: MailboxFactory
    private let fetchToken: (RemoteSettings) async throws -> String
    private let registerForPush: () -> Void
    private var catchUpTask: Task<Bool, Never>?
    private var catchUpAgain = false
    private var resetTask: Task<Void, Never>?
    /// Direct-connection addresses whose handshake failed since the last `remote.addr` (in memory only).
    private var droppedEndpoints = Set<WiFiEndpoint>()
    /// `remote.config` is on its way to the bridge (turning remote mode on).
    private var configuring = false

    init(store: RemoteStore, inbox: RemoteInboxing = SharedRemoteInbox(),
         identity: @escaping () throws -> KeyAgreementPrivateKey, bridgeKey: @escaping () -> Data?,
         makeMailbox: @escaping MailboxFactory = { RemoteMailbox(backend: $0, ids: $1) },
         fetchToken: @escaping (RemoteSettings) async throws -> String = { _ in try await RemoteCloud.fetchWebAuthToken() },
         registerForPush: @escaping () -> Void = {}) {
        self.store = store
        self.inbox = inbox
        self.identity = identity
        self.bridgeKey = bridgeKey
        self.makeMailbox = makeMailbox
        self.fetchToken = fetchToken
        self.registerForPush = registerForPush
        settings = store.settings
        direct = store.direct
    }

    var isOn: Bool { settings?.on == true }

    /// The addresses the direct connection may dial: from the bridge's last `remote.addr`, IPv6 first, minus any
    /// whose handshake failed since. Empty while remote mode is off.
    var directCandidates: [WiFiEndpoint] {
        guard isOn else { return [] }
        return direct.endpoints.candidates.filter { !droppedEndpoints.contains($0) }
    }

    /// The user's manual port forwarding (D-083), while remote mode is on.
    var manualEndpoint: WiFiEndpoint? {
        guard isOn else { return nil }
        return direct.manual?.endpoint
    }

    /// Something for the direct connection to dial: the bridge's addresses or the user's port forwarding.
    var hasDirectTargets: Bool { !directCandidates.isEmpty || manualEndpoint != nil }

    /// Try the user's port forwarding first while the bridge says its router didn't open a port; after the bridge's
    /// own addresses when it did.
    var manualFirst: Bool { direct.status?.availability != .open }

    /// The last attempt at the user's port forwarding, this run of the app.
    @Published private(set) var manualAttempt: ManualAttempt?

    /// Saves (or, with nil, removes) the user's port forwarding. Only while remote mode is on: it goes with it.
    func setManualForward(_ forward: ManualForward?) {
        guard isOn || forward == nil else { return }
        var direct = self.direct
        guard direct.manual != forward else { return }
        direct.manual = forward
        store.saveDirect(direct)
        self.direct = direct
        manualAttempt = nil
        log.info("remote", forward == nil ? "Port forwarding removed" : "Port forwarding set up by the user")
        onChange?()
    }

    /// The direct connection reached the bridge at the address the user entered, or didn't. Never logs the address.
    func noteManualAttempt(worked: Bool) {
        guard direct.manual != nil else { return }
        if manualAttempt?.worked != worked {
            log.info("remote", worked ? "Reached the bridge at the address you entered"
                                      : "Couldn't reach the bridge at the address you entered")
        }
        manualAttempt = ManualAttempt(at: now(), worked: worked)
    }

    func nextRequestID() -> Int { store.nextRequestID() }

    private func mailbox(for settings: RemoteSettings) -> RemoteMailboxing {
        makeMailbox(settings.backend, settings.mailboxes)
    }

    // MARK: - To the bridge

    private struct Keys {
        let identity: KeyAgreementPrivateKey
        let bridge: P256.KeyAgreement.PublicKey
    }

    private func keys() throws -> Keys {
        guard let key = bridgeKey() else { throw LinkError.notConnected }
        return Keys(identity: try identity(), bridge: try CryptoPrimitives.publicKey(x963: key))
    }

    /// Seals one message for the pinned bridge (kind 1, R→B, a fresh record id).
    func seal(_ json: Data) throws -> SealedRecord {
        let keys = try keys()
        let id = Data.random(count: MailboxHeader.recordIDLength)
        let data = try MailboxCrypto.seal(json, identity: keys.identity, recipient: keys.bridge,
                                          direction: .remoteToBridge, time: UInt64(max(0, now().milliseconds)),
                                          recordID: id)
        return SealedRecord(name: id.hex, data: data)
    }

    func write(_ record: SealedRecord) async throws {
        guard let settings, settings.on else { throw LinkError.notConnected }
        do {
            try await mailbox(for: settings).send(record.data, name: record.name)
            if problem != .bridgeNotReading { problem = nil }
        } catch {
            note(error)
            throw error
        }
    }

    // MARK: - Catch-up

    /// Takes what the bridge left: messages the notification service extension already unsealed, then the alert and
    /// quiet mailboxes; tidies this iPhone's own records and sends a token when one is due. One run at a time: a call
    /// during a run makes it go once more. Returns whether anything arrived.
    @discardableResult
    func catchUp(sessionUp: Bool) async -> Bool {
        if let running = catchUpTask {
            catchUpAgain = true
            return await running.value
        }
        let task = Task { @MainActor [weak self] () -> Bool in
            var got = false
            while let self {
                self.catchUpAgain = false
                got = await self.runCatchUp(sessionUp: sessionUp) || got
                if !self.catchUpAgain {
                    self.catchUpTask = nil
                    break
                }
            }
            return got
        }
        catchUpTask = task
        return await task.value
    }

    private func runCatchUp(sessionUp: Bool) async -> Bool {
        guard let settings, settings.on, let keys = try? keys() else { return false }
        var got = false
        var shown: [String] = []
        for item in inbox.drain() {
            inbox.markHandled(item.recordName)
            shown.append(item.recordName)
            got = true
            deliver(Delivery(plaintext: item.plaintext, alreadyShown: true), for: settings)
        }
        let mailbox = mailbox(for: settings)
        do {
            got = try await take(.alert, from: mailbox, keys: keys, alsoDelete: shown, for: settings) || got
            got = try await take(.quiet, from: mailbox, keys: keys, alsoDelete: [], for: settings) || got
            let unreadSince = try await tidyOwnRecords(mailbox, sessionUp: sessionUp)
            try await sendTokenIfDue(unreadSince: unreadSince)
            if self.settings?.mailboxes == settings.mailboxes { problem = unreadSince == nil ? nil : .bridgeNotReading }
        } catch {
            if self.settings?.mailboxes == settings.mailboxes { note(error) }
        }
        return got
    }

    /// Opens and hands on every record of `box` not handled before, oldest first, and deletes them all, dropped ones
    /// too (PROTOCOL.md, "Receiving").
    private func take(_ box: RemoteMailbox.Box, from mailbox: RemoteMailboxing, keys: Keys, alsoDelete: [String],
                      for settings: RemoteSettings) async throws -> Bool {
        var got = false
        var extra = alsoDelete
        for _ in 0..<5 {
            let records = try await mailbox.pending(in: box, limit: Self.pageSize)
            for record in records where !inbox.isHandled(record.name) {
                inbox.markHandled(record.name)
                if let plaintext = RemoteReceiving.open(record, identity: keys.identity, bridgeKey: keys.bridge,
                                                        now: now()) {
                    got = true
                    deliver(Delivery(plaintext: plaintext, alreadyShown: false), for: settings)
                } else {
                    log.info("remote", "Dropped a mailbox record")
                }
            }
            try await mailbox.delete(names: records.map(\.name) + extra, in: box)
            extra = []
            if records.count < Self.pageSize { break }
        }
        return got
    }

    private func deliver(_ delivery: Delivery, for settings: RemoteSettings) {
        // Remote mode went off (or the pairing ended) during this run: the rest isn't for this pairing any more.
        guard self.settings?.mailboxes == settings.mailboxes else { return }
        onMessage?(delivery)
    }

    /// Deletes this iPhone's records older than a day. Returns when the oldest record the bridge hasn't read was
    /// written, once that's 10 minutes ago; never while a session is up, since the bridge reads the mailbox only
    /// without one.
    private func tidyOwnRecords(_ mailbox: RemoteMailboxing, sessionUp: Bool) async throws -> Date? {
        let records = try await mailbox.pending(in: .toBridge, limit: Self.pageSize)
        let now = now()
        var old: [String] = []
        var oldestUnread: Date?
        for record in records {
            guard let header = try? MailboxHeader(parsing: record.sealed) else {
                old.append(record.name)
                continue
            }
            let written = Date(timeIntervalSince1970: Double(header.time) / 1000)
            if now.timeIntervalSince(written) > Self.ownRecordLifetime {
                old.append(record.name)
            } else if oldestUnread.map({ written < $0 }) ?? true {
                oldestUnread = written
            }
        }
        try await mailbox.delete(names: old, in: .toBridge)
        guard !sessionUp, let oldestUnread, now.timeIntervalSince(oldestUnread) >= Self.unreadLimit else { return nil }
        return oldestUnread
    }

    /// `remote.token` every 12 hours, and once when the bridge stopped reading (no token since its oldest unread
    /// record). On a session when one is up, otherwise through the mailbox.
    private func sendTokenIfDue(unreadSince: Date?) async throws {
        guard let settings, settings.on else { return }
        let now = now()
        if let sent = settings.tokenSentAt, now.timeIntervalSince(sent) < Self.tokenInterval,
           !(unreadSince.map { sent < $0 } ?? false) {
            return
        }
        let token = try await fetchToken(settings)
        let message = Outbound("remote.token", ["token": token], isRequest: false)
        if sendOverSession?(message) != true {
            try await write(seal(message.json(id: nil)))
        }
        store.markTokenSent(at: now)
        if self.settings?.mailboxes == settings.mailboxes { self.settings = store.settings }
        log.info("remote", "Sent the bridge a fresh iCloud token")
    }

    // MARK: - On and off

    /// Turns remote mode on: fresh mailboxes and their subscriptions, a token, and `remote.config` to the bridge
    /// through `request` (a session); on once the bridge answers `ok`.
    func turnOn(request: (Outbound) async throws -> Inbound) async throws {
        let new = RemoteSettings(on: true, backend: RemoteFeature.backend, environment: RemoteFeature.environment,
                                 mailboxes: .random(), tokenSentAt: nil)
        let mailbox = mailbox(for: new)
        do {
            try await mailbox.prepare()
            let token = try await fetchToken(new)
            // The bridge may send `remote.addr` right after its `ok`, before this task goes on.
            configuring = true
            defer { configuring = false }
            let reply = try await request(.remoteConfig(new, token: token))
            guard case .ok = reply.message else { throw RemoteError.failed }
        } catch {
            let reason = RemoteError(error)
            log.warning("remote", "Couldn't turn remote mode on: \(reason)")
            Task { try? await mailbox.tearDown() }
            store.saveDirect(RemoteDirect())
            direct = store.direct
            throw reason
        }
        var on = new
        on.tokenSentAt = now()
        adopt(on)
    }

    /// Remote mode is on with these settings (turned on in Settings, or a remote pairing). Subscribes again once an
    /// earlier clean-up has finished, since every pairing uses the same subscription ids.
    func adopt(_ settings: RemoteSettings) {
        store.save(settings)
        self.settings = settings
        problem = nil
        registerForPush()
        let mailbox = mailbox(for: settings)
        let previous = resetTask
        Task {
            await previous?.value
            do {
                try await mailbox.prepare()
            } catch {
                if self.settings?.mailboxes == settings.mailboxes { self.note(error) }
            }
        }
        log.info("remote", "Remote mode on (\(settings.backend.rawValue) backend, \(settings.environment.rawValue))")
        onChange?()
    }

    /// Remote mode off, or the pairing forgotten: the settings, what the extension left, the records in iCloud and
    /// both subscriptions go. `farewell` (`unpair` or `remote.config` off) is written last, so it stays for the bridge.
    @discardableResult
    func reset(farewell: SealedRecord? = nil) -> Task<Void, Never> {
        inbox.clear()
        guard let old = settings else { return Task {} }
        store.clear()
        settings = nil
        problem = nil
        direct = store.direct
        droppedEndpoints = []
        manualAttempt = nil
        let mailbox = mailbox(for: old)
        let task = Task {
            for box in RemoteMailbox.Box.allCases {
                if let records = try? await mailbox.pending(in: box, limit: 200) {
                    try? await mailbox.delete(names: records.map(\.name), in: box)
                }
            }
            if let farewell { try? await mailbox.send(farewell.data, name: farewell.name) }
            try? await mailbox.tearDown()
        }
        resetTask = task
        log.info("remote", "Remote mode off")
        onChange?()
        return task
    }

    /// Waits for the last `reset`'s clean-up in iCloud.
    func waitForReset() async {
        await resetTask?.value
    }

    /// Saves the subscriptions for the current mailboxes again (a remote pairing that didn't finish replaced them).
    func resubscribe() async {
        guard let settings, settings.on else { return }
        do {
            try await mailbox(for: settings).prepare()
        } catch {
            note(error)
        }
    }

    // MARK: - From the bridge

    /// `remote.addr` from the paired bridge (a session record, or a mailbox record sealed with its pinned key):
    /// replaces the direct-connection addresses, keeping only valid public ones. Returns whether it was taken.
    @discardableResult
    func bridgeAddresses(_ plaintext: Data) -> Bool {
        guard isOn || configuring,
              let object = try? JSONSerialization.jsonObject(with: plaintext) as? [String: Any] else { return false }
        var direct = self.direct
        direct.endpoints = DirectEndpoints(message: object)
        store.saveDirect(direct)
        self.direct = direct
        droppedEndpoints = []
        log.info("remote", "The bridge's direct-connection addresses: \(direct.endpoints.summary)")
        return true
    }

    /// The direct connection's handshake failed at this address: skip it until the next `remote.addr`. The user's own
    /// port forwarding is never skipped or removed for that; the attempt is noted as failed instead.
    func dropDirect(_ endpoint: WiFiEndpoint) {
        if endpoint == direct.manual?.endpoint { return noteManualAttempt(worked: false) }
        guard direct.endpoints.candidates.contains(endpoint), droppedEndpoints.insert(endpoint).inserted else { return }
        log.warning("remote", "Skipping the bridge's \(DirectAddress.family(endpoint)) address until it sends new ones")
    }

    /// `remote.state`, shown in Settings: `direct`, and of `reason` only a cause the app recognizes in it.
    func bridgeState(_ plaintext: Data) {
        guard isOn || configuring, let object = try? JSONSerialization.jsonObject(with: plaintext) as? [String: Any],
              let status = DirectStatus(message: object) else { return }
        if direct.status != status {
            log.info("remote", "The bridge's direct connection: \(status.availability.rawValue)"
                     + (status.reason.map { " (\($0.rawValue))" } ?? ""))
        }
        var direct = self.direct
        direct.status = status
        store.saveDirect(direct)
        self.direct = direct
    }

    private func note(_ error: Error) {
        problem = RemoteProblem(error)
        let detail = (error as? CKError).map { "CloudKit error \($0.code.rawValue)" } ?? "\(type(of: error))"
        log.warning("remote", "Mailbox: \(detail)")
    }

    /// Diagnostics: no ids, tokens or keys.
    var summary: String {
        guard let settings, settings.on else { return "off" }
        return "on (\(settings.backend.rawValue), \(settings.environment.rawValue)); problem: "
            + "\(problem.map { "\($0)" } ?? "none"); direct: \(direct.status?.availability.rawValue ?? "?")"
    }
}
