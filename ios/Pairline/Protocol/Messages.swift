import Foundation

// JSON messages carried inside records (PROTOCOL.md, "Messages"). Receivers ignore unknown types and
// unknown fields, and a missing optional field means null.

/// An opaque JSON scalar (string or number) that R stores and sends back unchanged: call ids and sync cursors.
enum OpaqueValue: Codable, Hashable, CustomStringConvertible {
    case int(Int64)
    case double(Double)
    case string(String)

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let value = try? container.decode(Int64.self) {
            self = .int(value)
        } else if let value = try? container.decode(Double.self) {
            self = .double(value)
        } else {
            self = .string(try container.decode(String.self))
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .int(let value): try container.encode(value)
        case .double(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        }
    }

    var jsonObject: Any {
        switch self {
        case .int(let value): return NSNumber(value: value)
        case .double(let value): return NSNumber(value: value)
        case .string(let value): return value
        }
    }

    var description: String {
        switch self {
        case .int(let value): return String(value)
        case .double(let value): return String(value)
        case .string(let value): return value
        }
    }

    /// Stable text form for storage (keeps the JSON type).
    var storageString: String {
        switch self {
        case .int(let value): return "i:\(value)"
        case .double(let value): return "d:\(value)"
        case .string(let value): return "s:\(value)"
        }
    }

    init?(storageString: String) {
        let prefix = storageString.prefix(2)
        let rest = String(storageString.dropFirst(2))
        switch prefix {
        case "i:": guard let v = Int64(rest) else { return nil }; self = .int(v)
        case "d:": guard let v = Double(rest) else { return nil }; self = .double(v)
        case "s:": self = .string(rest)
        default: return nil
        }
    }
}

/// Decodes an array and silently skips elements that don't decode, so one bad item can't break a page.
struct LossyList<Element: Decodable>: Decodable {
    let elements: [Element]

    private struct Skip: Decodable {}

    init(from decoder: Decoder) throws {
        var container = try decoder.unkeyedContainer()
        var out: [Element] = []
        while !container.isAtEnd {
            if let element = try? container.decode(Element.self) {
                out.append(element)
            } else {
                _ = try? container.decode(Skip.self)
            }
        }
        elements = out
    }
}

// MARK: - Shared objects

struct DeviceInfo: Codable, Equatable {
    var name: String?
    var platform: String?
    var os: String?
    var app: String?
    var proto: Int?
}

struct BatteryInfo: Codable, Equatable {
    var level: Int?
    var charging: Bool?
}

struct SIMInfo: Codable, Equatable, Identifiable, Hashable {
    var slot: Int
    var carrier: String?
    var label: String?
    var number: String?
    var signal: Int?
    var net: String?
    var roaming: Bool?
    var defaultSms: Bool?
    var defaultVoice: Bool?

    var id: Int { slot }
}

struct BridgeStatus: Codable, Equatable {
    var battery: BatteryInfo?
    var sims: [SIMInfo]?
    var app: String?
    var caps: [String]?
    var time: Int64?
}

struct SMSMessage: Codable, Equatable {
    var key: String
    var thread: Int64?
    var addr: String
    var body: String
    var time: Int64
    var box: String
    var sim: Int?
    var state: String?
}

struct SMSStateEvent: Codable, Equatable {
    var cid: String
    var state: String
    var key: String?
    var error: String?
}

struct SMSThreadSummary: Codable, Equatable {
    var thread: Int64
    var addrs: [String]?
    var snippet: String?
    var time: Int64?
    var count: Int?
}

struct ContactNumber: Codable, Hashable {
    var n: String
    var label: String?
}

struct ContactRecord: Codable, Equatable {
    var key: String
    var name: String?
    var starred: Bool?
    var numbers: [ContactNumber]?
    var emails: [String]?
}

struct CallLogRecord: Codable, Equatable {
    var key: String
    var number: String?
    var name: String?
    var type: String
    var time: Int64
    var dur: Int?
    var sim: Int?
}

struct CallIncomingEvent: Codable, Equatable {
    var call: OpaqueValue
    var sim: Int?
    var from: String?
    var name: String?
}

struct CallStateEvent: Codable, Equatable {
    var call: OpaqueValue
    var state: String
    var dir: String?
    var number: String?
    var name: String?
    var since: Int64?
    var reason: String?
}

struct USSDResult: Equatable {
    var ok: Bool
    var text: String?
    var error: String?
}

struct BridgeErrorReply: Equatable, Error {
    var code: String
    var msg: String?
}

struct SMSThreadsReply { var threads: [SMSThreadSummary]; var more: Bool }
struct SMSHistoryReply { var msgs: [SMSMessage]; var more: Bool }
struct ContactsSyncReply {
    var contacts: [ContactRecord]
    var deleted: [String]
    var cursor: OpaqueValue?
    var more: Bool
    /// The bridge didn't know our cursor and restarts from the beginning: drop the cache first.
    var reset = false
}

struct CallLogSyncReply {
    var calls: [CallLogRecord]
    var cursor: OpaqueValue?
    var more: Bool
    var reset = false
}

// MARK: - Inbound

enum InboundMessage {
    case deviceInfo(DeviceInfo)
    case status(BridgeStatus)
    case ping
    case pong
    case unpair
    case ok
    case error(BridgeErrorReply)
    case smsNew(SMSMessage)
    case smsState(SMSStateEvent)
    case smsThreads(SMSThreadsReply)
    case smsHistory(SMSHistoryReply)
    case callIncoming(CallIncomingEvent)
    case callState(CallStateEvent)
    case contactsSync(ContactsSyncReply)
    case calllogSync(CallLogSyncReply)
    case ussdResult(USSDResult)
    case unknown
}

struct Inbound {
    let type: String
    let id: Int?
    let message: InboundMessage

    private struct Envelope: Decodable {
        let t: String
        let id: Int?

        enum CodingKeys: String, CodingKey { case t, id }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            t = try c.decode(String.self, forKey: .t)
            id = try? c.decodeIfPresent(Int.self, forKey: .id)
        }
    }

    private struct StatusBody: Decodable {
        var battery: BatteryInfo?
        var sims: LossyList<SIMInfo>?
        var app: String?
        var caps: LossyList<String>?
        var time: Int64?
    }

    private struct MsgBody: Decodable { var msg: SMSMessage }
    private struct ErrorBody: Decodable { var code: String?; var msg: String? }
    private struct ThreadsBody: Decodable { var threads: LossyList<SMSThreadSummary>?; var more: Bool? }
    private struct HistoryBody: Decodable { var msgs: LossyList<SMSMessage>?; var more: Bool? }
    private struct ContactsBody: Decodable {
        var contacts: LossyList<ContactRecord>?
        var deleted: LossyList<String>?
        var cursor: OpaqueValue?
        var more: Bool?
        var reset: Bool?
    }
    private struct CallLogBody: Decodable {
        var calls: LossyList<CallLogRecord>?
        var cursor: OpaqueValue?
        var more: Bool?
        var reset: Bool?
    }
    private struct USSDBody: Decodable { var ok: Bool?; var text: String?; var error: String? }

    static func decode(_ data: Data) throws -> Inbound {
        let decoder = JSONDecoder()
        guard let envelope = try? decoder.decode(Envelope.self, from: data) else { throw ProtocolError.malformedJSON }
        func body<T: Decodable>(_ type: T.Type) throws -> T {
            do { return try decoder.decode(type, from: data) } catch { throw ProtocolError.malformedJSON }
        }
        let message: InboundMessage
        switch envelope.t {
        case "device.info":
            message = .deviceInfo(try body(DeviceInfo.self))
        case "status":
            let s = try body(StatusBody.self)
            message = .status(BridgeStatus(battery: s.battery, sims: s.sims?.elements, app: s.app,
                                           caps: s.caps?.elements, time: s.time))
        case "ping": message = .ping
        case "pong": message = .pong
        case "unpair": message = .unpair
        case "ok": message = .ok
        case "error":
            let e = try body(ErrorBody.self)
            message = .error(BridgeErrorReply(code: e.code ?? "failed", msg: e.msg))
        case "sms.new": message = .smsNew(try body(MsgBody.self).msg)
        case "sms.state": message = .smsState(try body(SMSStateEvent.self))
        case "sms.threads":
            let b = try body(ThreadsBody.self)
            message = .smsThreads(SMSThreadsReply(threads: b.threads?.elements ?? [], more: b.more ?? false))
        case "sms.history":
            let b = try body(HistoryBody.self)
            message = .smsHistory(SMSHistoryReply(msgs: b.msgs?.elements ?? [], more: b.more ?? false))
        case "call.incoming": message = .callIncoming(try body(CallIncomingEvent.self))
        case "call.state": message = .callState(try body(CallStateEvent.self))
        case "contacts.sync":
            let b = try body(ContactsBody.self)
            message = .contactsSync(ContactsSyncReply(contacts: b.contacts?.elements ?? [],
                                                      deleted: b.deleted?.elements ?? [],
                                                      cursor: b.cursor, more: b.more ?? false, reset: b.reset ?? false))
        case "calllog.sync":
            let b = try body(CallLogBody.self)
            message = .calllogSync(CallLogSyncReply(calls: b.calls?.elements ?? [], cursor: b.cursor,
                                                    more: b.more ?? false, reset: b.reset ?? false))
        case "ussd.result":
            let b = try body(USSDBody.self)
            message = .ussdResult(USSDResult(ok: b.ok ?? false, text: b.text, error: b.error))
        default:
            message = .unknown
        }
        return Inbound(type: envelope.t, id: envelope.id, message: message)
    }
}

// MARK: - Outbound

/// A message R sends. Requests get an `id` from the link; events don't.
struct Outbound {
    let type: String
    var fields: [String: Any]
    /// Whether B answers with the same `id`.
    let isRequest: Bool

    init(_ type: String, _ fields: [String: Any] = [:], isRequest: Bool = true) {
        self.type = type
        self.fields = fields
        self.isRequest = isRequest
    }

    var timeout: TimeInterval { type == "ussd.run" ? 60 : 15 }

    func json(id: Int?) throws -> Data {
        var object = fields
        object["t"] = type
        if let id { object["id"] = id }
        return try JSONSerialization.data(withJSONObject: object, options: [.withoutEscapingSlashes, .sortedKeys])
    }

    private static func optional(_ value: Any?) -> Any { value ?? NSNull() }

    static func deviceInfo(name: String, os: String, app: String) -> Outbound {
        Outbound("device.info", ["name": name, "platform": "ios", "os": os, "app": app, "proto": Int(WireProtocol.version)],
                 isRequest: false)
    }

    static var statusGet: Outbound { Outbound("status.get") }
    static var ping: Outbound { Outbound("ping") }
    static var unpair: Outbound { Outbound("unpair", isRequest: false) }

    static func smsSend(sim: Int?, to: String, body: String, cid: String) -> Outbound {
        Outbound("sms.send", ["sim": optional(sim), "to": to, "body": body, "cid": cid])
    }

    static func smsThreads(limit: Int, before: Int64?) -> Outbound {
        Outbound("sms.threads", ["limit": limit, "before": optional(before)])
    }

    static func smsHistory(thread: Int64?, before: Int64? = nil, after: Int64? = nil, limit: Int) -> Outbound {
        var fields: [String: Any] = ["thread": optional(thread), "limit": min(limit, 100)]
        if let after {
            fields["after"] = after
        } else {
            fields["before"] = optional(before)
        }
        return Outbound("sms.history", fields)
    }

    static func callAnswer(_ call: OpaqueValue) -> Outbound { Outbound("call.answer", ["call": call.jsonObject]) }
    static func callReject(_ call: OpaqueValue) -> Outbound { Outbound("call.reject", ["call": call.jsonObject]) }
    static func callEnd(_ call: OpaqueValue) -> Outbound { Outbound("call.end", ["call": call.jsonObject]) }

    static func callDial(sim: Int?, number: String) -> Outbound {
        Outbound("call.dial", ["sim": optional(sim), "number": number])
    }

    static func callDTMF(_ call: OpaqueValue, digit: String) -> Outbound {
        Outbound("call.dtmf", ["call": call.jsonObject, "digit": digit])
    }

    static func callAudio(_ call: OpaqueValue, mute: Bool? = nil, speaker: Bool? = nil) -> Outbound {
        var fields: [String: Any] = ["call": call.jsonObject]
        if let mute { fields["mute"] = mute }
        if let speaker { fields["speaker"] = speaker }
        return Outbound("call.audio", fields)
    }

    static func contactsSync(since: OpaqueValue?) -> Outbound {
        Outbound("contacts.sync", ["since": optional(since?.jsonObject)])
    }

    static func calllogSync(since: OpaqueValue?) -> Outbound {
        Outbound("calllog.sync", ["since": optional(since?.jsonObject)])
    }

    static func contactsAdd(name: String, numbers: [ContactNumber]) -> Outbound {
        Outbound("contacts.add", ["name": name,
                                  "numbers": numbers.map { ["n": $0.n, "label": optional($0.label)] as [String: Any] }])
    }

    static func ussdRun(sim: Int?, code: String) -> Outbound {
        Outbound("ussd.run", ["sim": optional(sim), "code": code])
    }
}

/// Capability names from `status.caps`.
enum Capability: String, CaseIterable, Identifiable {
    case smsReceive = "sms.receive"
    case smsSend = "sms.send"
    case smsHistory = "sms.history"
    case callObserve = "call.observe"
    case callControl = "call.control"
    case callDial = "call.dial"
    case callDTMF = "call.dtmf"
    case callAudio = "call.audio"
    case contacts = "contacts"
    case calllog = "calllog"
    case contactsAdd = "contacts.add"
    case ussd = "ussd"
    case dualSIM = "dualsim"

    var id: String { rawValue }
}
