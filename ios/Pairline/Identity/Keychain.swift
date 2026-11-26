import Foundation
import Security

/// Generic-password Keychain items, readable after the first unlock and never synced or migrated
/// (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`).
///
/// Items stored `shared` go into the app group, which doubles as a Keychain access group, so the notification
/// service extension can read them (the identity and the paired bridge's key: remote mode's extension unseals records
/// with them, D-072). Reads and deletes look in every group the process may use.
enum Keychain {
    static let service = "app.pairline.ios"
    static let sharedGroup = "group.app.pairline.ios"

    enum KeychainError: Error { case unhandled(OSStatus) }

    private static func base(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    static func set(_ data: Data, account: String, shared: Bool = false) throws {
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        if shared {
            var query = base(account: account)
            query[kSecAttrAccessGroup as String] = sharedGroup
            var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
            if status == errSecItemNotFound { status = add(data, account: account, group: sharedGroup) }
            if status == errSecSuccess { return }
            // The shared group isn't available (an ad-hoc signed test build): keep the item in the app's own group.
        }
        var status = SecItemUpdate(base(account: account) as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            status = add(data, account: account, group: nil)
        }
        guard status == errSecSuccess else { throw KeychainError.unhandled(status) }
    }

    private static func add(_ data: Data, account: String, group: String?) -> OSStatus {
        var item = base(account: account)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        if let group { item[kSecAttrAccessGroup as String] = group }
        return SecItemAdd(item as CFDictionary, nil)
    }

    static func get(account: String) -> Data? {
        var query = base(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    static func delete(account: String) {
        SecItemDelete(base(account: account) as CFDictionary)
    }

    /// Moves an item written before remote mode into the shared group. Does nothing when it's already there or
    /// doesn't exist; if the shared group can't be used, the item stays where it was.
    static func share(account: String) {
        var query = base(account: account)
        query[kSecReturnAttributes as String] = true
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let attributes = result as? [String: Any],
              let group = attributes[kSecAttrAccessGroup as String] as? String, group != sharedGroup,
              let data = attributes[kSecValueData as String] as? Data,
              add(data, account: account, group: sharedGroup) == errSecSuccess else { return }
        // The shared copy exists; only now remove the old one from its own group.
        var old = base(account: account)
        old[kSecAttrAccessGroup as String] = group
        SecItemDelete(old as CFDictionary)
    }
}
