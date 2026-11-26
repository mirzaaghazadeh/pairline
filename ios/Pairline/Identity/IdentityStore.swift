import CryptoKit
import Foundation
import Security

/// The iPhone's long-term P-256 identity key (PROTOCOL.md, "Roles and keys"): a Secure Enclave key when the
/// device has one, otherwise a software key kept in the Keychain (the Simulator always uses the Keychain path).
final class IdentityStore {
    enum Kind: String {
        case secureEnclave
        case keychain
    }

    private let kindAccount: String
    private let keyAccount: String
    private var cached: KeyAgreementPrivateKey?
    private(set) var kind: Kind?

    /// `prefix` keeps test keys apart from the app's own. The key lives in the shared Keychain group, so remote
    /// mode's notification service extension can unseal records (D-072); a key from before that moves there once.
    init(prefix: String = "") {
        kindAccount = prefix + "identity.kind"
        keyAccount = prefix + "identity.key"
        Keychain.share(account: kindAccount)
        Keychain.share(account: keyAccount)
    }

    func identity() throws -> KeyAgreementPrivateKey {
        if let cached { return cached }
        if let key = load() {
            cached = key
            return key
        }
        let key = try create()
        cached = key
        return key
    }

    var publicKey: Data? { try? identity().publicKeyX963 }

    private func load() -> KeyAgreementPrivateKey? {
        guard let kindData = Keychain.get(account: kindAccount),
              let kind = Kind(rawValue: String(decoding: kindData, as: UTF8.self)),
              let keyData = Keychain.get(account: keyAccount) else { return nil }
        switch kind {
        case .secureEnclave:
            guard SecureEnclave.isAvailable,
                  let key = try? SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation: keyData) else { return nil }
            self.kind = .secureEnclave
            return key
        case .keychain:
            guard let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: keyData) else { return nil }
            self.kind = .keychain
            return key
        }
    }

    private func create() throws -> KeyAgreementPrivateKey {
        if SecureEnclave.isAvailable {
            var error: Unmanaged<CFError>?
            if let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                                                            .privateKeyUsage, &error),
               let key = try? SecureEnclave.P256.KeyAgreement.PrivateKey(compactRepresentable: false,
                                                                         accessControl: access) {
                try Keychain.set(key.dataRepresentation, account: keyAccount, shared: true)
                try Keychain.set(Data(Kind.secureEnclave.rawValue.utf8), account: kindAccount, shared: true)
                kind = .secureEnclave
                log.info("identity", "Created a Secure Enclave identity key")
                return key
            }
        }
        let key = P256.KeyAgreement.PrivateKey(compactRepresentable: false)
        try Keychain.set(key.rawRepresentation, account: keyAccount, shared: true)
        try Keychain.set(Data(Kind.keychain.rawValue.utf8), account: kindAccount, shared: true)
        kind = .keychain
        log.info("identity", "Created a Keychain identity key")
        return key
    }

    /// Deletes the identity key. A new one is created on next use; any pairing must be redone.
    func reset() {
        Keychain.delete(account: keyAccount)
        Keychain.delete(account: kindAccount)
        cached = nil
        kind = nil
    }
}

/// The paired bridge: its device id and pinned identity key. One pairing per device in v0.
struct PairedPeer: Codable, Equatable {
    var deviceID: Data
    var publicKey: Data
    var pairedAt: Date
    var name: String?
    var peripheralID: UUID?
    /// The transport pairing used: "ble", "wifi", or "tcp" (debug builds only).
    var transport: String
    /// How to reach the bridge over Wi-Fi (M5); nil until the bridge offered it.
    var wifi: WiFiHints?
}

final class PeerStore {
    private let account: String
    private(set) var peer: PairedPeer?

    /// In the shared Keychain group, like the identity key (the extension needs the bridge's pinned key).
    init(prefix: String = "") {
        account = prefix + "peer"
        Keychain.share(account: account)
        if let data = Keychain.get(account: account) {
            peer = try? JSONDecoder().decode(PairedPeer.self, from: data)
        }
    }

    func save(_ peer: PairedPeer) {
        self.peer = peer
        if let data = try? JSONEncoder().encode(peer) {
            try? Keychain.set(data, account: account, shared: true)
        }
    }

    func update(_ change: (inout PairedPeer) -> Void) {
        guard var current = peer else { return }
        change(&current)
        guard current != peer else { return }
        save(current)
    }

    func clear() {
        peer = nil
        Keychain.delete(account: account)
    }
}
