#if DEBUG
import CloudKit
import UIKit
import UserNotifications

/// Phase 0 of remote mode (D-072), debug builds only: proves that a record written through CloudKit Web Services by
/// a non-Apple client wakes a suspended iPhone. Launch with `-PairlineRemoteSpike <subscription>`, where
/// `<subscription>` is `db` (`CKDatabaseSubscription`), `zone` (`CKRecordZoneSubscription` on ToPhone) or `query`
/// (`CKQuerySubscription` on ToPhone, record creation only, the payload inside the push). It creates the zones and
/// that one subscription (removing the other two), registers for remote notifications, and writes a web auth token to
/// `Library/Caches/remote-spike-token.txt` for `protocol/tools/cloudkit_mailbox.py`, which a Mac copies off with
/// `xcrun devicectl device copy from`. The token is never logged. `-PairlineRemoteSpike clear` removes the
/// subscriptions, the zones and the token file.
enum RemoteSpike {
    static let subscriptionIDs = ["to-phone-db": "db", "to-phone-zone": "zone", "to-phone-query": "query"]

    static var tokenFile: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("remote-spike-token.txt")
    }

    static func runIfRequested() {
        let args = ProcessInfo.processInfo.arguments
        guard let index = args.firstIndex(of: "-PairlineRemoteSpike"), index + 1 < args.count else { return }
        let step = args[index + 1]
        Task.detached { await run(step) }
    }

    private static func note(_ text: String) {
        log.info("remote", "Spike: \(text)")
    }

    private static func run(_ step: String) async {
        do {
            let status = try await RemoteCloud.container.accountStatus()
            guard status == .available else { throw RemoteCloudError.accountUnavailable(status) }
            if step == "clear" {
                try await clear()
                return
            }
            let granted = try await UNUserNotificationCenter.current()
                .requestAuthorization(options: [.alert, .sound, .badge])
            note("notifications allowed: \(granted)")
            await MainActor.run { UIApplication.shared.registerForRemoteNotifications() }
            try await RemoteCloud.ensureZones()
            note("zones ready")
            try await saveSubscription(step)
            note("subscription \(step) saved")
            let started = Date()
            let token = try await RemoteCloud.fetchWebAuthToken()
            try Data(token.utf8).write(to: tokenFile, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            note("web auth token fetched in \(Int(Date().timeIntervalSince(started) * 1000)) ms (\(token.count) characters)")
        } catch {
            note("failed: \(error)")
        }
    }

    private static func notificationInfo(desiredKeys: [String]? = nil) -> CKSubscription.NotificationInfo {
        let info = CKSubscription.NotificationInfo()
        info.title = "Pairline"
        info.alertBody = "Remote mode test"
        info.soundName = "default"
        info.shouldSendMutableContent = true
        info.desiredKeys = desiredKeys
        return info
    }

    private static func saveSubscription(_ kind: String) async throws {
        let subscription: CKSubscription
        switch kind {
        case "db":
            let sub = CKDatabaseSubscription(subscriptionID: "to-phone-db")
            sub.notificationInfo = notificationInfo()
            subscription = sub
        case "zone":
            let sub = CKRecordZoneSubscription(zoneID: RemoteCloud.toPhoneZone, subscriptionID: "to-phone-zone")
            sub.notificationInfo = notificationInfo()
            subscription = sub
        case "query":
            let sub = CKQuerySubscription(recordType: RemoteCloud.mailRecordType, predicate: NSPredicate(value: true),
                                          subscriptionID: "to-phone-query", options: [.firesOnRecordCreation])
            sub.zoneID = RemoteCloud.toPhoneZone
            sub.notificationInfo = notificationInfo(desiredKeys: [RemoteCloud.payloadField])
            subscription = sub
        default:
            throw CKError(.invalidArguments)
        }
        let others = subscriptionIDs.keys.filter { $0 != subscription.subscriptionID }
        let (saved, deleted) = try await RemoteCloud.database.modifySubscriptions(saving: [subscription],
                                                                                deleting: Array(others))
        for (_, result) in saved { if case .failure(let error) = result { throw error } }
        for (id, result) in deleted {
            if case .failure(let error) = result, (error as? CKError)?.code != .unknownItem {
                note("removing \(id): \(error)")
            }
        }
    }

    private static func clear() async throws {
        _ = try? await RemoteCloud.database.modifySubscriptions(saving: [], deleting: Array(subscriptionIDs.keys))
        _ = try? await RemoteCloud.database.modifyRecordZones(
            saving: [], deleting: [RemoteCloud.toPhoneZone, RemoteCloud.toBridgeZone])
        try? FileManager.default.removeItem(at: tokenFile)
        note("cleared")
    }
}
#endif
