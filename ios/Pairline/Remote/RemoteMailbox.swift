import CloudKit
import Foundation

/// The three mailboxes of remote mode in the user's iCloud (PROTOCOL.md, "Mailboxes in iCloud"; D-072): this iPhone
/// writes sealed records for the bridge, and subscribes to the alert mailbox (a push its notification service
/// extension may change: it unseals the record and shows it) and the quiet one (a silent push).
///
/// Two backends with the same records: the user's private database (zones), which is the design, and the public
/// database (a `mailbox` field on each record, readable only by the iCloud user who wrote it), which debug builds use
/// while D-072's question to Apple is open.
struct RemoteMailbox {
    typealias Backend = RemoteInvitation.Backend

    enum Box: CaseIterable {
        case toBridge, alert, quiet
    }

    struct Record {
        let name: String
        let sealed: Data
    }

    static let zones: [Box: CKRecordZone.ID] = [
        .alert: CKRecordZone.ID(zoneName: "ToPhone", ownerName: CKCurrentUserDefaultName),
        .quiet: CKRecordZone.ID(zoneName: "ToPhoneQuiet", ownerName: CKCurrentUserDefaultName),
        .toBridge: CKRecordZone.ID(zoneName: "ToBridge", ownerName: CKCurrentUserDefaultName),
    ]
    static let subscriptionIDs: [Box: String] = [.alert: "remote-alert", .quiet: "remote-quiet"]
    static let sealedField = "sealed"
    static let payloadField = "payload"
    static let mailboxField = "mailbox"
    /// A `sealed` text of at most this many characters rides in the push; larger records use `payload`.
    static let maxSealedText = 2_700

    let backend: Backend
    let ids: RemoteMailboxes
    var container: CKContainer = RemoteCloud.container

    private var database: CKDatabase {
        backend == .private ? container.privateCloudDatabase : container.publicCloudDatabase
    }

    private func recordID(_ name: String, in box: Box) -> CKRecord.ID {
        backend == .private ? CKRecord.ID(recordName: name, zoneID: Self.zones[box]!) : CKRecord.ID(recordName: name)
    }

    /// Creates the zones (private backend) and both subscriptions. Saving what exists changes nothing.
    func prepare() async throws {
        if backend == .private {
            let zones = [Box.alert, .quiet, .toBridge].map { CKRecordZone(zoneID: Self.zones[$0]!) }
            let (saved, _) = try await database.modifyRecordZones(saving: zones, deleting: [])
            for (id, result) in saved {
                if case .failure(let error) = result { throw RemoteCloudError.zone(id.zoneName, error) }
            }
        }
        let me = try await container.userRecordID()
        let subscriptions = [subscription(.alert, user: me), subscription(.quiet, user: me)]
        let (saved, _) = try await database.modifySubscriptions(saving: subscriptions, deleting: [])
        for (_, result) in saved {
            if case .failure(let error) = result { throw error }
        }
    }

    /// Removes both subscriptions and, in the private backend, the zones with everything in them.
    func tearDown() async throws {
        _ = try? await database.modifySubscriptions(saving: [], deleting: Array(Self.subscriptionIDs.values))
        if backend == .private {
            _ = try? await database.modifyRecordZones(saving: [], deleting: Array(Self.zones.values))
        }
    }

    private func subscription(_ box: Box, user: CKRecord.ID) -> CKSubscription {
        let predicate: NSPredicate
        if backend == .public {
            predicate = NSPredicate(format: "%K == %@ AND creatorUserRecordID == %@", Self.mailboxField, ids.id(box),
                                    CKRecord.Reference(recordID: user, action: .none))
        } else {
            predicate = NSPredicate(value: true)
        }
        let sub = CKQuerySubscription(recordType: RemoteCloud.mailRecordType, predicate: predicate,
                                      subscriptionID: Self.subscriptionIDs[box]!, options: [.firesOnRecordCreation])
        if backend == .private { sub.zoneID = Self.zones[box] }
        let info = CKSubscription.NotificationInfo()
        info.desiredKeys = [Self.sealedField]
        if box == .alert {
            // What shows if the notification service extension can't open the record; it normally replaces this.
            info.titleLocalizationKey = "remote.push.title"
            info.alertLocalizationKey = "remote.push.body"
            info.soundName = "default"
            info.shouldSendMutableContent = true
        } else {
            info.shouldSendContentAvailable = true
        }
        sub.notificationInfo = info
        return sub
    }

    /// Writes one sealed record for the bridge; [name] is the record id in hex (PROTOCOL.md).
    func send(_ sealed: Data, name: String) async throws {
        let record = CKRecord(recordType: RemoteCloud.mailRecordType, recordID: recordID(name, in: .toBridge))
        let text = sealed.base64URL
        if text.count <= Self.maxSealedText {
            record[Self.sealedField] = text as CKRecordValue
        } else {
            record[Self.payloadField] = sealed as CKRecordValue
        }
        if backend == .public { record[Self.mailboxField] = ids.toBridge as CKRecordValue }
        _ = try await database.save(record)
    }

    /// One record of [box], for a push that didn't carry the sealed text (a large record).
    func fetch(name: String, in box: Box) async throws -> Record? {
        do {
            return Self.parse(try await database.record(for: recordID(name, in: box)))
        } catch let error as CKError where error.code == .unknownItem {
            return nil
        }
    }

    /// Everything waiting in [box], oldest first: catches up on records whose push didn't arrive.
    func pending(in box: Box, limit: Int = 50) async throws -> [Record] {
        let predicate = backend == .public
            ? NSPredicate(format: "%K == %@", Self.mailboxField, ids.id(box))
            : NSPredicate(value: true)
        let query = CKQuery(recordType: RemoteCloud.mailRecordType, predicate: predicate)
        let zone = backend == .private ? Self.zones[box] : nil
        let (results, _) = try await database.records(matching: query, inZoneWith: zone, resultsLimit: limit)
        let records: [CKRecord] = results.compactMap { _, result in try? result.get() }
        let oldestFirst = records.sorted { ($0.creationDate ?? .distantPast) < ($1.creationDate ?? .distantPast) }
        return oldestFirst.compactMap(Self.parse)
    }

    /// Deletes handled records; records that are already gone count as deleted.
    func delete(names: [String], in box: Box) async throws {
        guard !names.isEmpty else { return }
        _ = try await database.modifyRecords(saving: [], deleting: names.map { recordID($0, in: box) })
    }

    static func parse(_ record: CKRecord) -> Record? {
        guard RemoteMailboxes.isValidID(record.recordID.recordName) else { return nil }
        if let text = record[sealedField] as? String, let data = Data(base64URL: text) {
            return Record(name: record.recordID.recordName, sealed: data)
        }
        if let data = record[payloadField] as? Data {
            return Record(name: record.recordID.recordName, sealed: data)
        }
        return nil
    }
}

extension RemoteMailboxes {
    /// Three fresh mailbox ids: 16 random bytes each, as 32 lowercase hex digits.
    static func random() -> RemoteMailboxes {
        func id() -> String { Data.random(count: 16).hex }
        return RemoteMailboxes(toBridge: id(), alert: id(), quiet: id())
    }

    func id(_ box: RemoteMailbox.Box) -> String {
        switch box {
        case .toBridge: return toBridge
        case .alert: return alert
        case .quiet: return quiet
        }
    }
}
