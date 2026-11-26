import CloudKit
import Foundation

/// Remote mode's doorbell and mailbox: the user's own private CloudKit database (D-080). Records hold only sealed
/// payloads; the bridge phone reaches the same database through CloudKit Web Services with a web auth token that this
/// iPhone fetches and sends it over the encrypted link.
enum RemoteCloud {
    static let containerIdentifier = "iCloud.app.pairline.ios"

    /// CloudKit API token for web services in the Development environment. API tokens are made to be embedded in
    /// clients (CloudKit JS web pages ship theirs); one alone reads nothing: CloudKit also wants the user's web auth
    /// token, which only a signed-in device running Pairline can fetch. Production gets its own token when the schema
    /// is deployed there.
    static let developmentAPIToken = "88f1d920f369a9d3976a70aa7d7ddb5ac7faca8b1545f01fc9c2aef5ce435a1b"

    /// Records for the iPhone (written by the bridge) and for the bridge (written by the iPhone). Custom zones, because
    /// database and zone subscriptions don't see the default zone.
    static let toPhoneZone = CKRecordZone.ID(zoneName: "ToPhone", ownerName: CKCurrentUserDefaultName)
    static let toBridgeZone = CKRecordZone.ID(zoneName: "ToBridge", ownerName: CKCurrentUserDefaultName)
    static let mailRecordType = "Mail"
    /// The sealed message (PROTOCOL.md); the only field.
    static let payloadField = "payload"

    static var container: CKContainer { CKContainer(identifier: containerIdentifier) }
    static var database: CKDatabase { container.privateCloudDatabase }

    /// Fetches a web auth token for the bridge (`CKFetchWebAuthTokenOperation`).
    static func fetchWebAuthToken(apiToken: String = developmentAPIToken) async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            let operation = CKFetchWebAuthTokenOperation(apiToken: apiToken)
            operation.fetchWebAuthTokenResultBlock = { continuation.resume(with: $0) }
            database.add(operation)
        }
    }

    /// Creates both zones; saving a zone that exists changes nothing.
    static func ensureZones() async throws {
        let (saved, _) = try await database.modifyRecordZones(
            saving: [CKRecordZone(zoneID: toPhoneZone), CKRecordZone(zoneID: toBridgeZone)], deleting: [])
        for (id, result) in saved {
            if case .failure(let error) = result { throw RemoteCloudError.zone(id.zoneName, error) }
        }
    }
}

enum RemoteCloudError: Error, CustomStringConvertible {
    case zone(String, Error)
    case accountUnavailable(CKAccountStatus)

    var description: String {
        switch self {
        case .zone(let name, let error): return "zone \(name): \(error)"
        case .accountUnavailable(let status): return "iCloud account status \(status.rawValue)"
        }
    }
}
