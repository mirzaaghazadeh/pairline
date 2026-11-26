import Foundation

enum MessageState: String {
    case received
    case pending
    case sent
    case delivered
    case failed
}

struct StoredMessage: Identifiable, Equatable {
    var key: String
    var thread: Int64
    var addr: String
    var body: String
    var time: Date
    var isOutgoing: Bool
    var sim: Int?
    var state: MessageState
    var cid: String?
    var error: String?

    var id: String { key }
}

struct Conversation: Identifiable, Equatable {
    var thread: Int64
    var addresses: [String]
    var snippet: String
    var time: Date
    var unread: Int
    var hasOlder: Bool
    var lastIsOutgoing: Bool
    var lastState: MessageState?

    var id: Int64 { thread }
    var primaryAddress: String { addresses.first ?? "" }
}

struct Contact: Identifiable, Equatable, Hashable {
    var key: String
    var name: String
    var starred: Bool
    var numbers: [ContactNumber]
    var emails: [String]

    var id: String { key }
    var primaryNumber: String? { numbers.first?.n }
}

enum CallLogType: String {
    case incoming = "in"
    case outgoing = "out"
    case missed
    case rejected
    case blocked
    case voicemail
}

struct CallLogItem: Identifiable, Equatable {
    var key: String
    var number: String
    var name: String?
    var type: CallLogType
    var time: Date
    var duration: Int
    var sim: Int?

    var id: String { key }
}

extension Date {
    init(milliseconds: Int64) {
        self.init(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
    }

    var milliseconds: Int64 { Int64((timeIntervalSince1970 * 1000).rounded()) }
}
