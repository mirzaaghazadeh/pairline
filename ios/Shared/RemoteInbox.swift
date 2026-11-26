import Foundation

/// Remote mode's hand-off between the notification service extension and the app (D-092): the extension unseals an
/// alert-mailbox record, shows it, and leaves its plaintext here; the app takes each message once (as if it had come
/// over a session), deletes the file and the CloudKit record. Both sides note handled record names so a record that
/// arrives twice (push and catch-up) is handled once (PROTOCOL.md, "Receiving": 14 days).
///
/// Everything lives in the app group container, protected until first unlock. Plaintext files exist only between
/// the push and the app's next run.
enum RemoteInbox {
    static let retention: TimeInterval = 14 * 24 * 3600

    private static var directory: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: SharedOutbox.appGroup)?
            .appendingPathComponent("RemoteInbox", isDirectory: true)
    }

    private static var handledFile: URL? { directory?.appendingPathComponent("handled.json") }

    /// Record names are 32 lowercase hex digits (PROTOCOL.md); anything else never becomes a file name.
    private static func isRecordName(_ name: String) -> Bool {
        name.utf8.count == 32 && name.utf8.allSatisfy { (0x30...0x39).contains($0) || (0x61...0x66).contains($0) }
    }

    private static func ensureDirectory() -> URL? {
        guard let directory else { return nil }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                 attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        return directory
    }

    /// The extension leaves an unsealed message for the app.
    static func store(recordName: String, plaintext: Data) {
        guard isRecordName(recordName), let directory = ensureDirectory() else { return }
        try? plaintext.write(to: directory.appendingPathComponent(recordName + ".json"),
                             options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    /// The app takes every waiting message, oldest first, and removes them.
    static func drain() -> [(recordName: String, plaintext: Data)] {
        guard let directory,
              let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.creationDateKey])
        else { return [] }
        let messages = files.filter { $0.pathExtension == "json" && isRecordName($0.deletingPathExtension().lastPathComponent) }
            .sorted { created($0) < created($1) }
        return messages.compactMap { url in
            defer { try? FileManager.default.removeItem(at: url) }
            guard let data = try? Data(contentsOf: url) else { return nil }
            return (url.deletingPathExtension().lastPathComponent, data)
        }
    }

    private static func created(_ url: URL) -> Date {
        (try? url.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast
    }

    // MARK: Handled record names

    private static func loadHandled() -> [String: TimeInterval] {
        guard let url = handledFile, let data = try? Data(contentsOf: url),
              let map = try? JSONDecoder().decode([String: TimeInterval].self, from: data) else { return [:] }
        return map
    }

    static func isHandled(_ recordName: String) -> Bool {
        loadHandled()[recordName] != nil
    }

    /// Notes a record as handled (and forgets ones older than 14 days).
    static func markHandled(_ recordName: String, at date: Date = Date()) {
        guard isRecordName(recordName), let directory = ensureDirectory() else { return }
        let cutoff = date.timeIntervalSince1970 - retention
        var map = loadHandled().filter { $0.value >= cutoff }
        map[recordName] = date.timeIntervalSince1970
        if let data = try? JSONEncoder().encode(map) {
            try? data.write(to: directory.appendingPathComponent("handled.json"),
                            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    // MARK: Settings the extension needs

    private static let showsTextKey = "notifications.previews"

    /// Settings › "Show message text", mirrored for the extension (the app keeps the setting in its own defaults).
    static func setShowsMessageText(_ on: Bool) {
        UserDefaults(suiteName: SharedOutbox.appGroup)?.set(on, forKey: showsTextKey)
    }

    static var showsMessageText: Bool {
        UserDefaults(suiteName: SharedOutbox.appGroup)?.object(forKey: showsTextKey) as? Bool ?? true
    }

    /// Remote mode off or the pairing forgotten: nothing of it stays.
    static func clear() {
        if let directory { try? FileManager.default.removeItem(at: directory) }
    }
}
