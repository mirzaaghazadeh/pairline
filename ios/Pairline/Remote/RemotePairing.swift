import CryptoKit
import Foundation

/// Remote pairing on the iPhone (D-092; PROTOCOL.md, "Remote pairing", the R side): an invitation the owner sends to
/// their own Android phone, the bridge's commitment, the 6-digit code checked on both phones, and both confirms.
/// The screen polls (`poll()` every 3 s); everything else happens here, so tests can drive it with a fake mailbox.
///
/// Never logged: the invitation link (only its redacted description), the secret, the token, keys or nonces.
@MainActor
final class RemotePairing: ObservableObject {
    enum Phase: Equatable {
        case creating
        /// The invitation is out; `answered` once the bridge's `rpair.hello` arrived.
        case inviting(uri: String, expires: Date, answered: Bool)
        case comparing(code: String)
        /// This user said the codes match; waiting for the bridge's `rpair.confirm`.
        case confirming
        case paired
        case failed(Failure)
        case cancelled
    }

    enum Failure: Equatable {
        case iCloudUnavailable
        case offline
        case failed
        case expired
        /// A second, different `rpair.hello`: someone else has the invitation.
        case someoneElse
        /// A malformed `rpair.*` message, or a reveal that doesn't match the commitment.
        case notVerified
        /// No confirm from the bridge within 10 minutes of the code appearing.
        case timedOut

        init(_ error: Error) {
            switch RemoteProblem(error) {
            case .iCloudUnavailable: self = .iCloudUnavailable
            case .offline: self = .offline
            default: self = .failed
            }
        }
    }

    struct Environment {
        var identity: () throws -> KeyAgreementPrivateKey
        var makeMailbox: (RemoteMailboxes) -> RemoteMailboxing
        var fetchToken: () async throws -> String
        var now: () -> Date = Date.init
        /// The codes match: pin the bridge and turn remote mode on with this pairing's mailboxes.
        var adopt: (PairedPeer, RemoteSettings) async -> Void
        /// The pairing ended after the match without the bridge's confirm: forget it again.
        var rollBack: () -> Void
        /// It ended before the match: put back what preparing this pairing's mailboxes replaced.
        var restore: () async -> Void
    }

    /// The invitation's lifetime, and how long both sides wait once the codes show.
    static let lifetime: TimeInterval = 600
    static let pollInterval: TimeInterval = 3

    @Published private(set) var phase: Phase = .creating

    private let env: Environment
    private var secret = Data()
    private var remoteKey = Data()
    private var mailboxes: RemoteMailboxes?
    private var mailbox: RemoteMailboxing?
    private var expiry = Date.distantPast
    private var hello: (key: Data, deviceID: Data, commit: Data)?
    private var nonceR: Data?
    private var codeShownAt: Date?
    private var matched = false
    private var ownConfirmSent = false
    private var bridgeDidConfirm = false
    /// Records already looked at (a delete that failed doesn't make them count twice).
    private var seen = Set<String>()
    private var polling = false

    init(environment: Environment) {
        env = environment
    }

    var isActive: Bool {
        switch phase {
        case .creating, .inviting, .comparing, .confirming: return true
        case .paired, .failed, .cancelled: return false
        }
    }

    // MARK: - Steps

    /// Step 1: a secret, three mailboxes and their subscriptions (the alert one is how `rpair.hello` arrives), a
    /// fresh web auth token for the bridge, and 10 minutes.
    func start() async {
        do {
            let identity = try env.identity()
            let ids = RemoteMailboxes.random()
            let mailbox = env.makeMailbox(ids)
            self.mailbox = mailbox
            mailboxes = ids
            try await mailbox.prepare()
            let token = try await env.fetchToken()
            let expirySeconds = UInt64(env.now().addingTimeInterval(Self.lifetime).timeIntervalSince1970)
            secret = Data.random(count: RemoteInvitation.secretLength)
            remoteKey = identity.publicKeyX963
            let invitation = try RemoteInvitation(remoteKey: remoteKey, secret: secret, backend: RemoteFeature.backend,
                                                  environment: RemoteFeature.environment, token: token, mailboxes: ids,
                                                  expiry: expirySeconds)
            guard phase == .creating else { return }
            expiry = Date(timeIntervalSince1970: TimeInterval(expirySeconds))
            phase = .inviting(uri: invitation.uri, expires: expiry, answered: false)
            log.info("pairing", "Remote invitation ready: \(invitation)")
        } catch {
            log.warning("pairing", "Couldn't create a remote invitation: \(RemoteError(error))")
            end(.failed(Failure(error)))
        }
    }

    /// Looks at the alert mailbox: invitation records until the code shows, then the bridge's confirm. Also ends the
    /// pairing at the invitation's expiry, or 10 minutes after the code appeared.
    func poll() async {
        guard isActive, !polling, let mailbox else { return }
        polling = true
        defer { polling = false }
        let now = env.now()
        if case .inviting = phase, now >= expiry { return end(.failed(.expired)) }
        if let shown = codeShownAt, now.timeIntervalSince(shown) >= Self.lifetime { return end(.failed(.timedOut)) }
        guard let records = try? await mailbox.pending(in: .alert, limit: 50) else { return }
        var handled: [String] = []
        for record in records where isActive && !seen.contains(record.name) {
            guard let header = try? MailboxHeader(parsing: record.sealed), header.recordName == record.name,
                  RemoteReceiving.isFresh(header.time, now: env.now()) else {
                seen.insert(record.name)
                handled.append(record.name)
                continue
            }
            switch header.kind {
            case .invite:
                seen.insert(record.name)
                handled.append(record.name)
                guard !matched, let opened = try? MailboxCrypto.openInvite(record.sealed, secret: secret,
                                                                           direction: .bridgeToRemote) else { continue }
                await handleInvite(opened.plaintext)
            case .sealed:
                // The bridge's confirm. Before this user's match it waits in the mailbox: the other person may tap
                // first.
                guard matched, isBridgeConfirm(record) else { continue }
                seen.insert(record.name)
                handled.append(record.name)
                bridgeConfirmed()
            }
        }
        try? await mailbox.delete(names: handled, in: .alert)
    }

    private func handleInvite(_ plaintext: Data) async {
        guard let object = try? JSONSerialization.jsonObject(with: plaintext) as? [String: Any],
              let type = object["t"] as? String else { return }
        switch type {
        case "rpair.hello":
            guard let key = Self.field(object, "k", length: WireProtocol.publicKeyLength),
                  (try? CryptoPrimitives.publicKey(x963: key)) != nil,
                  let deviceID = Self.field(object, "id", length: WireProtocol.deviceIDLength),
                  let commit = Self.field(object, "c", length: 32) else { return end(.failed(.notVerified)) }
            if let hello {
                // The same hello again is harmless; a different one means someone else has the invitation.
                if hello.key != key || hello.deviceID != deviceID || hello.commit != commit {
                    log.warning("pairing", "A second phone answered the remote invitation; stopped")
                    end(.failed(.someoneElse))
                }
                return
            }
            hello = (key, deviceID, commit)
            let nonce = Data.random(count: 32)
            nonceR = nonce
            do {
                try await sendInvite(["t": "rpair.nonce", "n": nonce.base64URL])
            } catch {
                return end(.failed(Failure(error)))
            }
            if case .inviting(let uri, let expires, _) = phase {
                phase = .inviting(uri: uri, expires: expires, answered: true)
            }
            log.info("pairing", "The Android phone answered the remote invitation")
        case "rpair.reveal":
            guard let hello, let nonceR, codeShownAt == nil else { return }
            guard let nonceB = Self.field(object, "n", length: 32),
                  RemotePairingCrypto.commit(remoteKey: remoteKey, bridgeKey: hello.key, nonceB: nonceB) == hello.commit
            else {
                log.warning("pairing", "The Android phone's reveal didn't match its commitment; stopped")
                return end(.failed(.notVerified))
            }
            codeShownAt = env.now()
            phase = .comparing(code: RemotePairingCrypto.code(secret: secret, remoteKey: remoteKey, bridgeKey: hello.key,
                                                              nonceR: nonceR, nonceB: nonceB))
        default:
            break
        }
    }

    /// This user says the codes match: pin the bridge, turn remote mode on with this pairing's mailboxes, and send
    /// `rpair.confirm` (kind 1). Paired once the bridge's confirm decrypts.
    func confirm() async {
        guard case .comparing = phase, let hello, let mailbox, let mailboxes else { return }
        phase = .confirming
        matched = true
        let now = env.now()
        let peer = PairedPeer(deviceID: hello.deviceID, publicKey: hello.key, pairedAt: now, name: nil,
                              peripheralID: nil, transport: "remote", wifi: nil)
        // The invitation carried a token, so the bridge has one from now.
        let settings = RemoteSettings(on: true, backend: RemoteFeature.backend, environment: RemoteFeature.environment,
                                      mailboxes: mailboxes, tokenSentAt: now)
        await env.adopt(peer, settings)
        do {
            let id = Data.random(count: MailboxHeader.recordIDLength)
            let sealed = try MailboxCrypto.seal(Data(#"{"t":"rpair.confirm"}"#.utf8), identity: env.identity(),
                                                recipient: CryptoPrimitives.publicKey(x963: hello.key),
                                                direction: .remoteToBridge,
                                                time: UInt64(max(0, env.now().milliseconds)), recordID: id)
            try await mailbox.send(sealed, name: id.hex)
        } catch {
            return end(.failed(Failure(error)))
        }
        ownConfirmSent = true
        log.info("pairing", "Confirmed the remote pairing on this iPhone")
        if bridgeDidConfirm {
            finish()
        } else {
            await poll()
        }
    }

    /// The bridge's `rpair.confirm` decrypted (here, or in the link's catch-up once remote mode is on).
    func bridgeConfirmed() {
        guard matched, isActive else { return }
        bridgeDidConfirm = true
        if ownConfirmSent { finish() }
    }

    func cancel() {
        guard isActive else { return }
        end(.cancelled)
    }

    private func finish() {
        guard phase == .confirming else { return }
        phase = .paired
        log.info("pairing", "Paired remotely")
    }

    #if DEBUG
    /// Debug builds: a pairing frozen at one step for screenshots (`-PairlineRemotePairingPreview invite|code|failed`,
    /// with `-PairlinePairingRemote`). It has no mailbox, so it never polls or sends anything.
    static func preview(_ step: String) -> RemotePairing {
        let pairing = RemotePairing(environment: .init(
            identity: { P256.KeyAgreement.PrivateKey() }, makeMailbox: { RemoteMailbox(backend: .public, ids: $0) },
            fetchToken: { "" }, adopt: { _, _ in }, rollBack: {}, restore: {}))
        switch step {
        case "code": pairing.phase = .comparing(code: "042917")
        case "failed": pairing.phase = .failed(.someoneElse)
        default:
            pairing.phase = .inviting(uri: "pairline://invite?v=1&preview=1", expires: Date().addingTimeInterval(562),
                                      answered: false)
        }
        return pairing
    }
    #endif

    // MARK: - Helpers

    private func isBridgeConfirm(_ record: RemoteMailbox.Record) -> Bool {
        guard let hello, let identity = try? env.identity(),
              let bridgeKey = try? CryptoPrimitives.publicKey(x963: hello.key),
              let opened = try? MailboxCrypto.open(record.sealed, identity: identity, sender: bridgeKey,
                                                   direction: .bridgeToRemote),
              let object = try? JSONSerialization.jsonObject(with: opened.plaintext) as? [String: Any] else { return false }
        return object["t"] as? String == "rpair.confirm"
    }

    private func sendInvite(_ object: [String: Any]) async throws {
        guard let mailbox else { throw LinkError.notConnected }
        let id = Data.random(count: MailboxHeader.recordIDLength)
        let plaintext = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        let sealed = try MailboxCrypto.sealInvite(plaintext, secret: secret, direction: .remoteToBridge,
                                                  time: UInt64(max(0, env.now().milliseconds)), recordID: id)
        try await mailbox.send(sealed, name: id.hex)
    }

    /// A base64url (no padding) field of exactly `length` bytes.
    private static func field(_ object: [String: Any], _ name: String, length: Int) -> Data? {
        guard let text = object[name] as? String, let data = Data(base64URL: text), data.count == length else { return nil }
        return data
    }

    /// Stops polling and cleans up. After the match the pairing is forgotten again (the bridge is told); before it,
    /// this pairing's records and subscriptions go and the ones they replaced come back.
    private func end(_ outcome: Phase) {
        guard isActive else { return }
        phase = outcome
        if case .failed(let failure) = outcome { log.info("pairing", "Remote pairing ended: \(failure)") }
        if matched {
            env.rollBack()
            return
        }
        guard let mailbox else { return }
        let restore = env.restore
        Task {
            for box in [RemoteMailbox.Box.alert, .toBridge] {
                if let records = try? await mailbox.pending(in: box, limit: 50) {
                    try? await mailbox.delete(names: records.map(\.name), in: box)
                }
            }
            try? await mailbox.tearDown()
            await restore()
        }
    }
}
