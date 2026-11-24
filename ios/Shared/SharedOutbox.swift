import Foundation

/// Files the Share extension ("Send to bridge phone") hands to the app: each share goes into its own folder under
/// `Outbox/` in the app group container, then the extension posts a Darwin notification. The app sends them over
/// the Wi-Fi link when it can and deletes each file once it's delivered or refused (PRIVACY.md).
enum SharedOutbox {
    static let appGroup = "group.app.pairline.ios"
    /// Posted (Darwin notification, no payload) by the Share extension after it queued files.
    static let darwinNotification = "app.pairline.ios.outbox"
    /// The same limit as a transfer (PROTOCOL.md, "File transfer").
    static let maxFileSize: Int64 = 1 << 30

    static var directory: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent("Outbox", isDirectory: true)
    }

    static func notifyApp() {
        CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(),
                                             CFNotificationName(darwinNotification as CFString), nil, nil, true)
    }
}
