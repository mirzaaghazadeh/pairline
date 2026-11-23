import Foundation

/// On-device storage locations. Everything Pairline stores lives under Application Support/Pairline,
/// protected with `completeUntilFirstUserAuthentication` (so messages that arrive while the phone is locked
/// can be saved) and excluded from iCloud and computer backups, so user content stays on this device (PRIVACY.md).
enum AppPaths {
    static let appGroupID = "group.app.pairline.ios"

    static let rootDirectory: URL? = {
        let fm = FileManager.default
        guard var url = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
            .appendingPathComponent("Pairline", isDirectory: true) else { return nil }
        do {
            try fm.createDirectory(at: url, withIntermediateDirectories: true,
                                   attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try url.setResourceValues(values)
        } catch {
            return nil
        }
        return url
    }()

    static let logsDirectory: URL? = subdirectory("Logs")

    static var databaseURL: URL? { rootDirectory?.appendingPathComponent("pairline.sqlite") }

    static func subdirectory(_ name: String) -> URL? {
        guard let root = rootDirectory else { return nil }
        let url = root.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(
            at: url, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        return url
    }
}
