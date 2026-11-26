import Foundation

/// Remote mode (D-080) is in debug builds only for now: it uses the public CloudKit backend in the Development
/// environment while Apple looks into the private database. Release builds show and do nothing of it.
enum RemoteFeature {
    static var isAvailable: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    static let backend: RemoteInvitation.Backend = .public
    static let environment: RemoteInvitation.Environment = .development

    /// `proto` in this iPhone's `device.info`: 1 when it speaks remote mode (PROTOCOL.md, "Version 1").
    static var protocolVersion: Int { isAvailable ? 1 : Int(WireProtocol.version) }

    /// Bridge versions this app works with: v1 is v0 plus remote mode, and the local links stay v0 on the wire.
    static let compatibleVersions = Int(WireProtocol.version)...1
}

/// What remote mode uses while it's on.
struct RemoteSettings: Equatable {
    var on: Bool
    var backend: RemoteInvitation.Backend
    var environment: RemoteInvitation.Environment
    var mailboxes: RemoteMailboxes
    /// When a web auth token last went to the bridge (`remote.config`, the invitation or `remote.token`).
    var tokenSentAt: Date?
}

/// Remote mode's state in the app's own defaults (not the app group): the settings, and the counter that numbers
/// requests sent through the mailbox. Mailbox ids aren't secret; tokens and keys are never stored here. What the
/// bridge said about its direct connection goes in the Keychain instead (this device only, never in a backup), since
/// its public addresses tell roughly where it is.
final class RemoteStore {
    /// Mailbox request ids start here, apart from a session's ids (which restart at 1 on every launch).
    static let firstRequestID = 1_000_000

    private struct Stored: Codable {
        var on: Bool
        var backend: String
        var env: String
        var b: String
        var a: String
        var q: String
        var tokenSentAt: Date?
    }

    private let defaults: UserDefaults
    private let settingsKey: String
    private let counterKey: String
    private let directAccount: String
    private(set) var settings: RemoteSettings?
    private(set) var direct: RemoteDirect

    /// `prefix` keeps test state apart from the app's own.
    init(defaults: UserDefaults = .standard, prefix: String = "") {
        self.defaults = defaults
        settingsKey = prefix + "remote.settings"
        counterKey = prefix + "remote.nextRequestID"
        directAccount = prefix + "remote.direct"
        settings = Self.load(defaults.data(forKey: settingsKey))
        let stored = Keychain.get(account: directAccount)
        var direct = stored.flatMap { try? JSONDecoder().decode(RemoteDirect.self, from: $0) } ?? RemoteDirect()
        direct.endpoints = direct.endpoints.checked
        self.direct = direct
    }

    func saveDirect(_ direct: RemoteDirect) {
        self.direct = direct
        if direct == RemoteDirect() {
            Keychain.delete(account: directAccount)
        } else if let data = try? JSONEncoder().encode(direct) {
            try? Keychain.set(data, account: directAccount)
        }
    }

    private static func load(_ data: Data?) -> RemoteSettings? {
        guard let data, let stored = try? JSONDecoder().decode(Stored.self, from: data),
              let backend = RemoteInvitation.Backend(rawValue: stored.backend),
              let environment = RemoteInvitation.Environment(rawValue: stored.env) else { return nil }
        let mailboxes = RemoteMailboxes(toBridge: stored.b, alert: stored.a, quiet: stored.q)
        guard mailboxes.isValid else { return nil }
        return RemoteSettings(on: stored.on, backend: backend, environment: environment, mailboxes: mailboxes,
                              tokenSentAt: stored.tokenSentAt)
    }

    func save(_ settings: RemoteSettings) {
        self.settings = settings
        let stored = Stored(on: settings.on, backend: settings.backend.rawValue, env: settings.environment.rawValue,
                            b: settings.mailboxes.toBridge, a: settings.mailboxes.alert, q: settings.mailboxes.quiet,
                            tokenSentAt: settings.tokenSentAt)
        defaults.set(try? JSONEncoder().encode(stored), forKey: settingsKey)
    }

    func markTokenSent(at date: Date) {
        guard var settings else { return }
        settings.tokenSentAt = date
        save(settings)
    }

    /// The next id for a request through the mailbox (PROTOCOL.md: from a counter R keeps across restarts).
    func nextRequestID() -> Int {
        let stored = defaults.integer(forKey: counterKey)
        let id = max(stored, Self.firstRequestID)
        defaults.set(id + 1, forKey: counterKey)
        return id
    }

    /// Forgets the settings and the bridge's direct connection. The request counter stays, so no id is ever used twice.
    func clear() {
        settings = nil
        defaults.removeObject(forKey: settingsKey)
        saveDirect(RemoteDirect())
    }
}

/// The iCloud mailbox as remote mode uses it: `RemoteMailbox` is the real one; tests use a fake.
protocol RemoteMailboxing {
    func prepare() async throws
    func tearDown() async throws
    func send(_ sealed: Data, name: String) async throws
    func pending(in box: RemoteMailbox.Box, limit: Int) async throws -> [RemoteMailbox.Record]
    func delete(names: [String], in box: RemoteMailbox.Box) async throws
}

extension RemoteMailbox: RemoteMailboxing {}

/// What the notification service extension left for the app (`RemoteInbox`); tests use a fake.
protocol RemoteInboxing {
    func drain() -> [(recordName: String, plaintext: Data)]
    func isHandled(_ recordName: String) -> Bool
    func markHandled(_ recordName: String)
    func clear()
}

struct SharedRemoteInbox: RemoteInboxing {
    func drain() -> [(recordName: String, plaintext: Data)] { RemoteInbox.drain() }
    func isHandled(_ recordName: String) -> Bool { RemoteInbox.isHandled(recordName) }
    func markHandled(_ recordName: String) { RemoteInbox.markHandled(recordName) }
    func clear() { RemoteInbox.clear() }
}
