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

    /// Accepts any JSON value, so the container moves past an element that didn't decode.
    private struct Skip: Decodable {
        init(from decoder: Decoder) throws {}
    }

    init(from decoder: Decoder) throws {
        var container = try decoder.unkeyedContainer()
        var out: [Element] = []
        while !container.isAtEnd {
            if let element = try? container.decode(Element.self) {
                out.append(element)
            } else if (try? container.decode(Skip.self)) == nil {
                break
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
    /// Android's data SIM (D-073).
    var defaultData: Bool?
    /// Signal in dBm (RSRP on LTE and 5G), Android 10+ (D-082).
    var dbm: Int?
    /// The exact radio technology, such as "LTE-CA" or "NR NSA" (D-082).
    var rat: String?
    /// The network the SIM is registered on; differs from `carrier` when roaming (D-082).
    var op: String?
    /// A voicemail is waiting (the carrier's message-waiting indicator, D-082).
    var vm: Bool?

    var id: Int { slot }
}

/// `notif.posted` (D-077): a notification from an app the user chose on the bridge phone.
struct MirroredNotification: Codable, Equatable {
    var key: String
    var app: String
    var appName: String?
    var title: String?
    var text: String?
    var time: Int64?
}

/// `status.net` (D-073): the bridge phone's own internet sharing. Switched with `net.switch` (needs `net.switch`).
struct BridgeNetState: Codable, Equatable {
    /// Android's own Wi-Fi hotspot is on.
    var hotspot: Bool?
    /// Bluetooth tethering is on.
    var bluetooth: Bool?
    /// Mobile data is on (on the data SIM); null if Android didn't say.
    var data: Bool?

    func value(_ what: NetSwitch) -> Bool? {
        switch what {
        case .hotspot: return hotspot
        case .bluetooth: return bluetooth
        case .data: return data
        }
    }
}

struct BridgeStatus: Codable, Equatable {
    var battery: BatteryInfo?
    var sims: [SIMInfo]?
    var app: String?
    var caps: [String]?
    var time: Int64?
    var net: BridgeNetState?
    /// The bridge phone's ringer (D-074): `normal`, `vibrate` or `silent`.
    var ringer: String?
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

/// `net.share`: internet sharing through the bridge phone (M8, D-048). Reply to a request, or an event when the bridge
/// stops sharing by itself.
struct NetShareInfo: Codable, Equatable {
    var on: Bool
    var host: String?
    var port: Int?
    var user: String?
    var pass: String?
    var pac: String?
    var vpn: Bool?
    var direct: [String]?
}

/// `call.media.state`: the bridge started, stopped or failed to relay call audio (M6, D-047).
struct CallMediaStateEvent: Codable, Equatable {
    var call: OpaqueValue
    var state: String
    var listen: Bool?
    var talk: Bool?
    var reason: String?
}

struct CallStateEvent: Codable, Equatable {
    var call: OpaqueValue
    var state: String
    var dir: String?
    var number: String?
    var name: String?
    var since: Int64?
    var reason: String?
    var sim: Int?
}

/// One entry of the bridge's block list (D-043): a number or sender name, and when it was added (ms).
struct BlockedNumber: Codable, Equatable, Hashable {
    var n: String
    var time: Int64?
}

/// `sms.scheduled` (D-084): messages the bridge will send later, what became of the ones it sent in the last week,
/// and whether it can send at the exact minute (Android's "Alarms & reminders").
struct ScheduledList: Equatable {
    struct Item: Codable, Equatable {
        var cid: String
        var sim: Int?
        var to: String
        var body: String
        var at: Int64
    }

    struct Done: Codable, Equatable {
        var cid: String
        var state: String
        var key: String?
        var error: String?
    }

    var items: [Item]
    var done: [Done]
    var exact: Bool
}

/// `data.usage` (D-084): mobile bytes the bridge phone used between `since` and `until` (ms).
struct DataUsageReply: Codable, Equatable {
    var since: Int64
    var until: Int64
    var bytes: Int64
}

struct USSDResult: Equatable {
    var ok: Bool
    var text: String?
    var error: String?
}

/// `wifi.offer` (M5): where B can be reached over Wi-Fi, and its hotspot while that's on.
struct WiFiOffer: Codable, Equatable {
    var ip: [String]?
    var port: Int?
    var ssid: String?
    var password: String?
    var band: String?
}

/// `file.offer` (M5).
struct FileOfferMessage: Codable, Equatable {
    var fid: UInt32
    var name: String
    var size: Int64
    var type: String?
}

/// `file.answer` (about the peer's offer).
struct FileAnswerMessage: Codable, Equatable {
    var fid: UInt32
    var accept: Bool
    var reason: String?
}

/// `file.done` (about the peer's offer).
struct FileDoneMessage: Codable, Equatable {
    var fid: UInt32
    var ok: Bool
    var reason: String?
}

/// `file.cancel` (about the sender's own offer).
struct FileCancelMessage: Codable, Equatable {
    var fid: UInt32
    var reason: String?
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
    /// A full sync from the beginning (`full: true`): replace the cached copy, once per sync run.
    var full = false
}

struct CallLogSyncReply {
    var calls: [CallLogRecord]
    var cursor: OpaqueValue?
    var more: Bool
    var full = false
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
    /// A notification from an app the user chose on the bridge phone (D-077), and its removal there.
    case notifPosted(MirroredNotification)
    case notifRemoved(String)
    case smsState(SMSStateEvent)
    case smsThreads(SMSThreadsReply)
    case smsHistory(SMSHistoryReply)
    case callIncoming(CallIncomingEvent)
    case callState(CallStateEvent)
    case callMediaState(CallMediaStateEvent)
    case netShare(NetShareInfo)
    case contactsSync(ContactsSyncReply)
    case calllogSync(CallLogSyncReply)
    case ussdResult(USSDResult)
    case wifiOffer(WiFiOffer)
    /// `wifi.media`: B's UDP port for the media channel, or nil when there's none this session.
    case wifiMedia(port: UInt16?)
    case fileOffer(FileOfferMessage)
    case fileAnswer(FileAnswerMessage)
    case fileDone(FileDoneMessage)
    case fileCancel(FileCancelMessage)
    /// The reply to `block.list` / `block.set`, or (without an `id`) the list after it changed on the bridge, and
    /// whether "Silence unknown callers" is on there (D-085; nil from an older bridge).
    case blockList([BlockedNumber], unknown: Bool?)
    /// D-084: the scheduled messages, the data meter, and the bridge's user ringing this iPhone.
    case smsScheduled(ScheduledList)
    case dataUsage(DataUsageReply)
    case remoteRing(Bool)
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
        var net: BridgeNetState?
        var ringer: String?
    }

    private struct MsgBody: Decodable { var msg: SMSMessage }
    private struct KeyBody: Decodable { var key: String }
    private struct ErrorBody: Decodable { var code: String?; var msg: String? }
    private struct ThreadsBody: Decodable { var threads: LossyList<SMSThreadSummary>?; var more: Bool? }
    private struct HistoryBody: Decodable { var msgs: LossyList<SMSMessage>?; var more: Bool? }
    private struct ContactsBody: Decodable {
        var contacts: LossyList<ContactRecord>?
        var deleted: LossyList<String>?
        var cursor: OpaqueValue?
        var more: Bool?
        var full: Bool?
    }
    private struct CallLogBody: Decodable {
        var calls: LossyList<CallLogRecord>?
        var cursor: OpaqueValue?
        var more: Bool?
        var full: Bool?
    }
    private struct USSDBody: Decodable { var ok: Bool?; var text: String?; var error: String? }
    private struct MediaBody: Decodable { var port: Int? }
    private struct OfferBody: Decodable {
        var ip: LossyList<String>?
        var port: Int?
        var ssid: String?
        var password: String?
        var band: String?
    }
    private struct BlockListBody: Decodable { var numbers: LossyList<BlockedNumber>?; var unknown: Bool? }
    private struct ScheduledBody: Decodable {
        var items: LossyList<ScheduledList.Item>?
        var done: LossyList<ScheduledList.Done>?
        var exact: Bool?
    }
    private struct OnBody: Decodable { var on: Bool? }

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
                                           caps: s.caps?.elements, time: s.time, net: s.net, ringer: s.ringer))
        case "ping": message = .ping
        case "pong": message = .pong
        case "unpair": message = .unpair
        case "ok": message = .ok
        case "error":
            let e = try body(ErrorBody.self)
            message = .error(BridgeErrorReply(code: e.code ?? "failed", msg: e.msg))
        case "sms.new": message = .smsNew(try body(MsgBody.self).msg)
        case "notif.posted": message = .notifPosted(try body(MirroredNotification.self))
        case "notif.removed": message = .notifRemoved(try body(KeyBody.self).key)
        case "sms.state": message = .smsState(try body(SMSStateEvent.self))
        case "sms.threads":
            let b = try body(ThreadsBody.self)
            message = .smsThreads(SMSThreadsReply(threads: b.threads?.elements ?? [], more: b.more ?? false))
        case "sms.history":
            let b = try body(HistoryBody.self)
            message = .smsHistory(SMSHistoryReply(msgs: b.msgs?.elements ?? [], more: b.more ?? false))
        case "call.incoming": message = .callIncoming(try body(CallIncomingEvent.self))
        case "call.state": message = .callState(try body(CallStateEvent.self))
        case "call.media.state": message = .callMediaState(try body(CallMediaStateEvent.self))
        case "net.share": message = .netShare(try body(NetShareInfo.self))
        case "contacts.sync":
            let b = try body(ContactsBody.self)
            message = .contactsSync(ContactsSyncReply(contacts: b.contacts?.elements ?? [],
                                                      deleted: b.deleted?.elements ?? [],
                                                      cursor: b.cursor, more: b.more ?? false, full: b.full ?? false))
        case "calllog.sync":
            let b = try body(CallLogBody.self)
            message = .calllogSync(CallLogSyncReply(calls: b.calls?.elements ?? [], cursor: b.cursor,
                                                    more: b.more ?? false, full: b.full ?? false))
        case "ussd.result":
            let b = try body(USSDBody.self)
            message = .ussdResult(USSDResult(ok: b.ok ?? false, text: b.text, error: b.error))
        case "wifi.offer":
            let b = try body(OfferBody.self)
            message = .wifiOffer(WiFiOffer(ip: b.ip?.elements, port: b.port, ssid: b.ssid, password: b.password,
                                           band: b.band))
        case "wifi.media":
            let port = try body(MediaBody.self).port.flatMap { UInt16(exactly: $0) }.flatMap { $0 == 0 ? nil : $0 }
            message = .wifiMedia(port: port)
        case "file.offer": message = .fileOffer(try body(FileOfferMessage.self))
        case "file.answer": message = .fileAnswer(try body(FileAnswerMessage.self))
        case "file.done": message = .fileDone(try body(FileDoneMessage.self))
        case "file.cancel": message = .fileCancel(try body(FileCancelMessage.self))
        case "block.list":
            let b = try body(BlockListBody.self)
            message = .blockList(b.numbers?.elements ?? [], unknown: b.unknown)
        case "sms.scheduled":
            let b = try body(ScheduledBody.self)
            message = .smsScheduled(ScheduledList(items: b.items?.elements ?? [], done: b.done?.elements ?? [],
                                                  exact: b.exact ?? true))
        case "data.usage": message = .dataUsage(try body(DataUsageReply.self))
        case "remote.ring": message = .remoteRing(try body(OnBody.self).on ?? true)
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
    /// The only session this message may go on, when it matters (call audio inside the Bluetooth session, D-072).
    var lane: LinkLane?

    init(_ type: String, _ fields: [String: Any] = [:], isRequest: Bool = true, lane: LinkLane? = nil) {
        self.type = type
        self.fields = fields
        self.isRequest = isRequest
        self.lane = lane
    }

    var timeout: TimeInterval { type == "ussd.run" ? 60 : 15 }

    func json(id: Int?) throws -> Data {
        var object = fields
        object["t"] = type
        if let id { object["id"] = id }
        return try JSONSerialization.data(withJSONObject: object, options: [.withoutEscapingSlashes, .sortedKeys])
    }

    private static func optional(_ value: Any?) -> Any { value ?? NSNull() }

    /// What this iPhone app can receive (PROTOCOL.md, "Capabilities"): B only offers files when `files` is listed,
    /// and rings this iPhone (`remote.ring`) only when `ring` is (D-084).
    static let remoteCaps = ["files", "ring"]

    static func deviceInfo(name: String, os: String, app: String) -> Outbound {
        Outbound("device.info", ["name": name, "platform": "ios", "os": os, "app": app, "proto": Int(WireProtocol.version),
                                 "caps": remoteCaps],
                 isRequest: false)
    }

    /// Requests whose replies can be large: sent on the Wi-Fi session when one is up (PROTOCOL.md, "Two sessions").
    static let bulkTypes: Set<String> = ["sms.threads", "sms.history", "contacts.sync", "calllog.sync"]

    var isBulk: Bool { Outbound.bulkTypes.contains(type) }

    static func fileOffer(fid: UInt32, name: String, size: Int64, type: String?) -> Outbound {
        Outbound("file.offer", ["fid": fid, "name": name, "size": size, "type": optional(type)], isRequest: false)
    }

    static func fileAnswer(fid: UInt32, accept: Bool, reason: String?) -> Outbound {
        var fields: [String: Any] = ["fid": fid, "accept": accept]
        if let reason { fields["reason"] = reason }
        return Outbound("file.answer", fields, isRequest: false)
    }

    static func fileDone(fid: UInt32, ok: Bool, reason: String?) -> Outbound {
        var fields: [String: Any] = ["fid": fid, "ok": ok]
        if let reason { fields["reason"] = reason }
        return Outbound("file.done", fields, isRequest: false)
    }

    static func fileCancel(fid: UInt32, reason: String?) -> Outbound {
        var fields: [String: Any] = ["fid": fid]
        if let reason { fields["reason"] = reason }
        return Outbound("file.cancel", fields, isRequest: false)
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

    /// Calls the voicemail of the SIM in `sim`; Android looks up the number (needs `call.voicemail`, D-082).
    static func callVoicemail(sim: Int?) -> Outbound {
        Outbound("call.dial", ["sim": optional(sim), "voicemail": true])
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

    /// Asks the bridge to relay this call's audio (PROTOCOL.md "Call audio"): over the Wi-Fi media channel, or inside
    /// the Bluetooth session it's sent on (`.link`, D-072), which is why that one goes only on the Bluetooth lane.
    static func callMediaStart(_ call: OpaqueValue, talk: Bool, via: CallAudioPath = .wifi) -> Outbound {
        Outbound("call.media.start", ["call": call.jsonObject, "talk": talk, "via": via.wire],
                 lane: via == .link ? .primary : nil)
    }

    static func callMediaStop(_ call: OpaqueValue) -> Outbound {
        Outbound("call.media.stop", ["call": call.jsonObject])
    }

    /// Turns internet sharing through the bridge on or off (M8). The reply is `net.share` with the proxy details.
    static func netShare(on: Bool) -> Outbound {
        Outbound("net.share", ["on": on])
    }

    /// Switches the bridge phone's hotspot, Bluetooth tethering or mobile data (needs `net.switch`, D-073). The reply
    /// is `ok`; the next `status` shows the result in `net`.
    static func netSwitch(_ what: NetSwitch, on: Bool) -> Outbound {
        Outbound("net.switch", ["what": what.rawValue, "on": on])
    }

    /// Makes the SIM in [slot] the bridge phone's data SIM (needs `net.switch` and `dualsim`, D-073).
    static func netDataSIM(slot: Int) -> Outbound {
        Outbound("net.switch", ["what": "dataSim", "sim": slot])
    }

    /// The conversation was read on this iPhone: mark it read on the bridge phone too (needs `sms.read`, D-074).
    static func smsRead(thread: Int64) -> Outbound { Outbound("sms.read", ["thread": thread]) }

    /// Puts the call on hold or takes it off (needs `call.hold`, D-074).
    static func callHold(_ call: OpaqueValue, hold: Bool) -> Outbound {
        Outbound("call.hold", ["call": call.jsonObject, "hold": hold])
    }

    /// Edits a contact on the bridge phone: any of name, numbers and favourite (needs `contacts.edit`, D-074).
    static func contactsUpdate(key: String, name: String?, numbers: [ContactNumber]?, starred: Bool?) -> Outbound {
        var fields: [String: Any] = ["key": key]
        if let name { fields["name"] = name }
        if let numbers { fields["numbers"] = numbers.map { ["n": $0.n, "label": optional($0.label)] as [String: Any] } }
        if let starred { fields["starred"] = starred }
        return Outbound("contacts.update", fields)
    }

    /// Makes the bridge phone ring loudly, or stops it (needs `phone.find`, D-074).
    static func phoneFind(on: Bool) -> Outbound { Outbound("phone.find", ["on": on]) }

    /// The bridge phone's ringer: `normal`, `vibrate` or `silent` (needs `phone.ringer`, D-074).
    static func phoneRinger(_ mode: String) -> Outbound { Outbound("phone.ringer", ["mode": mode]) }

    /// The user dismissed a mirrored notification here: clear it on the bridge phone too (D-077).
    static func notifDismiss(key: String) -> Outbound { Outbound("notif.dismiss", ["key": key]) }

    /// After a relayed call: whether each side could hear the other, so the bridge settles on what works.
    static func callMediaFeedback(heard: Bool?, heardMe: Bool?) -> Outbound {
        Outbound("call.media.feedback", ["heard": optional(heard), "heardMe": optional(heardMe)])
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

    /// Deletes these call-log entries on the bridge phone (needs `calllog.delete`).
    static func calllogDelete(keys: [String]) -> Outbound { Outbound("calllog.delete", ["keys": keys]) }

    /// Deletes the bridge phone's whole call log (needs `calllog.delete`).
    static var calllogDeleteAll: Outbound { Outbound("calllog.delete", ["all": true]) }

    static var blockList: Outbound { Outbound("block.list") }

    /// The bridge sends this message at `at` (ms), keeping it while this iPhone is away (needs `sms.schedule`, D-084).
    static func smsSchedule(cid: String, sim: Int?, to: String, body: String, at: Int64) -> Outbound {
        Outbound("sms.schedule", ["cid": cid, "sim": optional(sim), "to": to, "body": body, "at": at])
    }

    static func smsUnschedule(cid: String) -> Outbound { Outbound("sms.unschedule", ["cid": cid]) }

    static var smsScheduled: Outbound { Outbound("sms.scheduled") }

    /// The bridge phone's mobile data since `since` (ms), every SIM together (needs `data.usage`, D-084).
    static func dataUsage(since: Int64) -> Outbound { Outbound("data.usage", ["since": since]) }

    static func blockSet(add: [String], remove: [String]) -> Outbound {
        Outbound("block.set", ["add": add, "remove": remove])
    }

    /// "Silence unknown callers" on the bridge phone (needs `call.screen`, D-085).
    static func blockSilenceUnknown(_ on: Bool) -> Outbound {
        Outbound("block.set", ["add": [String](), "remove": [String](), "unknown": on])
    }
}

/// What `net.switch` turns on or off on the bridge phone (D-073).
enum NetSwitch: String, CaseIterable {
    case hotspot
    case bluetooth
    case data
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
    /// The bridge can relay call audio to this iPhone ("Talk on iPhone", M6).
    case callMedia = "call.media"
    /// …and can feed this iPhone's microphone into the call.
    case callMediaTalk = "call.media.talk"
    /// …and the audio can travel inside the Bluetooth session, as Opus (D-072).
    case callMediaLink = "call.media.link"
    /// The bridge's user allows internet sharing for this iPhone (M8).
    case netShare = "net.share"
    /// This iPhone may switch the bridge phone's hotspot, Bluetooth tethering, mobile data and data SIM (D-073).
    case netSwitch = "net.switch"
    /// A conversation read here is marked read on the bridge phone too (D-074).
    case smsRead = "sms.read"
    /// Messages can be scheduled; the bridge sends them at the time (D-084).
    case smsSchedule = "sms.schedule"
    /// The bridge reports its mobile data use (Usage access given, D-084).
    case dataUsage = "data.usage"
    /// A call can be put on hold (D-074).
    case callHold = "call.hold"
    /// Voicemail can be called on a chosen SIM, and SIMs say when one is waiting (D-082).
    case callVoicemail = "call.voicemail"
    /// Contacts can be edited from here (D-074).
    case contactsEdit = "contacts.edit"
    /// The bridge phone can be made to ring loudly (D-074).
    case phoneFind = "phone.find"
    /// The bridge phone's ringer can be switched (D-074).
    case phoneRinger = "phone.ringer"
    /// Notifications from apps chosen on the bridge phone reach this iPhone (D-077).
    case notifMirror = "notif.mirror"
    /// The bridge's Wi-Fi link is on (M5).
    case wifi = "wifi"
    /// The bridge sends and receives files over a Wi-Fi session (M5).
    case files = "files"
    /// The bridge can delete call-history entries too (D-043).
    case calllogDelete = "calllog.delete"
    /// The bridge keeps a block list (D-043).
    case block = "block"
    /// The bridge is Android's call-screening app: blocked calls don't ring, unknown callers can be silenced (D-085).
    case callScreen = "call.screen"

    var id: String { rawValue }
}
