#if DEBUG
import CloudKit
import UIKit
import UserNotifications

/// Phase 0 of remote mode (D-072), debug builds only: proves that a record written through CloudKit Web Services by
/// a non-Apple client wakes a suspended iPhone. Launch with `-PairlineRemoteSpike <subscription>`, where
/// `<subscription>` is `db` (`CKDatabaseSubscription`), `zone` (`CKRecordZoneSubscription` on ToPhone) or `query`
/// (`CKQuerySubscription` on ToPhone, record creation only, the payload inside the push). It creates the zones and
/// that one subscription (removing the other two), registers for remote notifications, and writes two web auth tokens,
/// fetched one after the other, to `Library/Caches/remote-spike-token.txt` and `remote-spike-token-spare.txt` for
/// `protocol/tools/cloudkit_mailbox.py`, which a Mac copies off with `xcrun devicectl device copy from`. The tokens are
/// never logged. `-PairlineRemoteSpike report` writes when each delivered notification arrived (and the record it
/// names, for the query subscription) to `remote-spike-deliveries.json`, then clears them, so a Mac without the
/// iPhone's system log can time the pushes. `-PairlineRemoteSpike clear` removes the subscriptions, the zones and the
/// files. `-PairlineRemoteSpikeAPIToken <token>` mints the tokens with another API token (a build signed for iCloud
/// Production needs the Production one). Each step is also written to `remote-spike-status.txt`, without secrets.
enum RemoteSpike {
    static let subscriptionIDs = ["to-phone-db": "db", "to-phone-zone": "zone", "to-phone-query": "query"]

    private static func cacheFile(_ name: String) -> URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent(name)
    }

    static var tokenFile: URL { cacheFile("remote-spike-token.txt") }
    static var spareTokenFile: URL { cacheFile("remote-spike-token-spare.txt") }
    static var deliveriesFile: URL { cacheFile("remote-spike-deliveries.json") }
    static var statusFile: URL { cacheFile("remote-spike-status.txt") }

    private static var apiToken = RemoteCloud.developmentAPIToken

    static func runIfRequested() {
        let args = ProcessInfo.processInfo.arguments
        guard let index = args.firstIndex(of: "-PairlineRemoteSpike"), index + 1 < args.count else { return }
        let step = args[index + 1]
        if let i = args.firstIndex(of: "-PairlineRemoteSpikeAPIToken"), i + 1 < args.count {
            apiToken = args[i + 1]
        }
        try? FileManager.default.removeItem(at: statusFile)
        Task.detached { await run(step) }
    }

    private static func note(_ text: String) {
        log.info("remote", "Spike: \(text)")
        let line = Data("\(Date().timeIntervalSince1970) \(text)\n".utf8)
        if let handle = try? FileHandle(forWritingTo: statusFile) {
            handle.seekToEndOfFile()
            handle.write(line)
            try? handle.close()
        } else {
            try? line.write(to: statusFile)
        }
    }

    private static func run(_ step: String) async {
        note("step \(step), \(apiToken == RemoteCloud.developmentAPIToken ? "Development" : "other") API token")
        do {
            let status = try await RemoteCloud.container.accountStatus()
            guard status == .available else { throw RemoteCloudError.accountUnavailable(status) }
            if step == "clear" {
                try await clear()
                return
            }
            if step == "report" {
                try await report()
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
            for file in [tokenFile, spareTokenFile] {
                let started = Date()
                let token = try await RemoteCloud.fetchWebAuthToken(apiToken: apiToken)
                try Data(token.utf8).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                note("web auth token fetched in \(Int(Date().timeIntervalSince(started) * 1000)) ms (\(token.count) characters)")
            }
        } catch {
            note("failed: \(error)")
        }
    }

    /// When each delivered notification arrived, what kind of CloudKit notification it was and which record it names
    /// (query subscriptions only), and whether the payload field rode along. Never the payload itself.
    private static func report() async throws {
        let center = UNUserNotificationCenter.current()
        let delivered = await center.deliveredNotifications()
        var rows: [[String: Any]] = []
        for item in delivered {
            let userInfo = item.request.content.userInfo
            var row: [String: Any] = ["delivered": item.date.timeIntervalSince1970]
            if let ck = CKNotification(fromRemoteNotificationDictionary: userInfo) {
                row["type"] = ck.notificationType.rawValue
                row["subscription"] = ck.subscriptionID ?? ""
                if let query = ck as? CKQueryNotification {
                    row["record"] = query.recordID?.recordName ?? ""
                    row["reason"] = query.queryNotificationReason.rawValue
                    row["payloadInPush"] = query.recordFields?[RemoteCloud.payloadField] != nil
                }
            } else {
                row["type"] = -1
            }
            rows.append(row)
        }
        rows.sort { ($0["delivered"] as? Double ?? 0) < ($1["delivered"] as? Double ?? 0) }
        let json = try JSONSerialization.data(withJSONObject: ["reportedAt": Date().timeIntervalSince1970,
                                                               "deliveries": rows], options: [.prettyPrinted])
        try json.write(to: deliveriesFile, options: .atomic)
        center.removeAllDeliveredNotifications()
        note("reported \(rows.count) delivered notifications")
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
        for file in [tokenFile, spareTokenFile, deliveriesFile, statusFile] {
            try? FileManager.default.removeItem(at: file)
        }
        note("cleared")
    }
}
#endif
