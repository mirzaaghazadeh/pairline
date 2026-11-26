import CloudKit
import CryptoKit
import UserNotifications

/// Remote mode's notification service extension (D-092): an alert-mailbox push carries a record sealed for this
/// iPhone (PROTOCOL.md, "Mailbox records"). It unseals it with the identity key and the paired bridge's key from the
/// shared Keychain group, shows the message or call the way the app would, and leaves the plaintext in the app group
/// for the app (RemoteInbox). Anything it can't open keeps CloudKit's generic placeholder; the app fetches it later.
/// No network, no logging of content.
final class NotificationService: UNNotificationServiceExtension {
    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var placeholder: UNNotificationContent?

    override func didReceive(_ request: UNNotificationRequest,
                             withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        self.contentHandler = contentHandler
        placeholder = request.content
        contentHandler(RemoteNotification.content(for: request.content) ?? request.content)
        self.contentHandler = nil
    }

    override func serviceExtensionTimeWillExpire() {
        if let contentHandler, let placeholder { contentHandler(placeholder) }
        contentHandler = nil
    }
}

enum RemoteNotification {
    /// The notification for a pushed record, or nil to keep the placeholder.
    static func content(for original: UNNotificationContent) -> UNNotificationContent? {
        guard let notification = CKNotification(fromRemoteNotificationDictionary: original.userInfo) as? CKQueryNotification,
              let name = notification.recordID?.recordName,
              let text = notification.recordFields?["sealed"] as? String,
              let sealed = Data(base64URL: text),
              let header = try? MailboxHeader(parsing: sealed) else { return nil }
        if header.kind == .invite {
            // Remote pairing: the app, which is open and holds the invitation secret, handles it.
            return simple(title: String(localized: "Pairline"),
                          body: String(localized: "A phone answered your pairing invitation."), from: original)
        }
        guard !RemoteInbox.isHandled(name),
              let identity = ExtensionKeys.identity(), let bridge = ExtensionKeys.bridgeKey(),
              let opened = try? MailboxCrypto.open(sealed, identity: identity, sender: bridge, direction: .bridgeToRemote),
              isTimely(opened.header.time),
              let message = try? JSONSerialization.jsonObject(with: opened.plaintext) as? [String: Any] else { return nil }
        RemoteInbox.store(recordName: name, plaintext: opened.plaintext)
        RemoteInbox.markHandled(name)
        switch message["t"] as? String {
        case "sms.new": return sms(message["msg"] as? [String: Any], name: name, from: original)
        case "call.incoming": return incomingCall(message, from: original)
        case "call.state": return missedCall(message, from: original)
        default: return nil
        }
    }

    /// PROTOCOL.md, "Receiving": at most 14 days old and 10 minutes ahead.
    private static func isTimely(_ time: UInt64) -> Bool {
        let now = Date().timeIntervalSince1970 * 1000
        let ms = Double(time)
        return ms >= now - 14 * 24 * 3600 * 1000 && ms <= now + 10 * 60 * 1000
    }

    private static var showPreviews: Bool { RemoteInbox.showsMessageText }

    private static func base(from original: UNNotificationContent) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.sound = original.sound ?? .default
        return content
    }

    private static func simple(title: String, body: String, from original: UNNotificationContent) -> UNNotificationContent {
        let content = base(from: original)
        content.title = title
        content.body = body
        return content
    }

    /// As the app's Notifier.notifyMessage: the sender, the text (or a placeholder when previews are off), and a
    /// one-time code with its "Copy code" action.
    private static func sms(_ msg: [String: Any]?, name: String, from original: UNNotificationContent) -> UNNotificationContent? {
        guard let msg, let addr = msg["addr"] as? String, let body = msg["body"] as? String else { return nil }
        let thread = (msg["thread"] as? NSNumber)?.int64Value ?? 0
        let code = OTPDetector.code(in: body)
        let content = base(from: original)
        content.title = addr
        if showPreviews {
            content.body = body
        } else {
            content.body = code == nil ? String(localized: "New message") : String(localized: "New one-time code")
        }
        content.threadIdentifier = "thread-\(thread)"
        content.categoryIdentifier = code == nil ? "sms" : "sms.otp"
        var info: [String: Any] = ["thread": NSNumber(value: thread), "remoteRecord": name]
        if let code { info["code"] = code }
        content.userInfo = info
        content.interruptionLevel = code == nil ? .active : .timeSensitive
        content.relevanceScore = code == nil ? 0.5 : 1
        return content
    }

    /// Until the filtering entitlement lets the extension ring the call through CallKit (D-092), an incoming call
    /// is a time-sensitive notification.
    private static func incomingCall(_ message: [String: Any], from original: UNNotificationContent) -> UNNotificationContent {
        let caller = (message["name"] as? String) ?? (message["from"] as? String) ?? String(localized: "Unknown caller")
        let content = base(from: original)
        content.title = String(localized: "Incoming call")
        content.body = String(localized: "\(caller) is calling your Android number.")
        content.interruptionLevel = .timeSensitive
        content.relevanceScore = 1
        return content
    }

    private static func missedCall(_ message: [String: Any], from original: UNNotificationContent) -> UNNotificationContent? {
        guard message["state"] as? String == "ended", message["dir"] as? String == "in" else { return nil }
        let caller = (message["name"] as? String) ?? (message["number"] as? String) ?? String(localized: "Unknown caller")
        return simple(title: String(localized: "Missed call"), body: caller, from: original)
    }
}

/// The identity key and the paired bridge's key, read from the shared Keychain group where the app keeps them
/// (Pairline/Identity: accounts `identity.kind`, `identity.key`, `peer`).
enum ExtensionKeys {
    static func identity() -> KeyAgreementPrivateKey? {
        guard let kind = Keychain.get(account: "identity.kind"), let key = Keychain.get(account: "identity.key") else {
            return nil
        }
        switch String(decoding: kind, as: UTF8.self) {
        case "secureEnclave": return try? SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation: key)
        case "keychain": return try? P256.KeyAgreement.PrivateKey(rawRepresentation: key)
        default: return nil
        }
    }

    static func bridgeKey() -> P256.KeyAgreement.PublicKey? {
        struct Peer: Decodable { let publicKey: Data }
        guard let data = Keychain.get(account: "peer"),
              let peer = try? JSONDecoder().decode(Peer.self, from: data) else { return nil }
        return try? CryptoPrimitives.publicKey(x963: peer.publicKey)
    }
}
