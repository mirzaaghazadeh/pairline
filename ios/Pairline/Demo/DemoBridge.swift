import Foundation

/// A simulated bridge phone for demo mode (D-020). It implements the same `BridgeLink` interface as the real link
/// and answers the same JSON messages, so the whole app above the link runs unchanged. Nothing leaves the iPhone.
@MainActor
final class DemoBridge: BridgeLink {
    private(set) var phase: LinkPhase = .securing {
        didSet { if phase != oldValue { onPhaseChange?(phase) } }
    }
    private(set) var latency: TimeInterval? = 0.042
    let transportKind: TransportKind = .demo

    var onPhaseChange: ((LinkPhase) -> Void)?
    var onEvent: ((Inbound) -> Void)?
    var onEstablished: (() -> Void)?

    let content: DemoContent
    private var messages: [SMSMessage] = []
    private var contacts: [ContactRecord] = []
    private var calls: [CallLogRecord] = []
    private var nextKey = 1000
    private var activeCall: (id: Int64, number: String, dir: String, state: String)?
    private var nextCallID: Int64 = 1
    private var battery = 76
    private var timers: [Timer] = []
    private var started = false

    init(content: DemoContent = .current()) {
        self.content = content
        buildData()
    }

    /// Connects after a short pause, like a real bridge, and sends a one-time code a little later.
    func start() {
        guard !started else { return }
        started = true
        after(0.7) { [weak self] in
            guard let self else { return }
            self.phase = .connected
            self.emit(["t": "device.info", "name": "Pixel 8", "platform": "android", "os": "15", "app": "0.1.0", "proto": 0])
            self.emit(self.statusBody(id: nil))
            self.onEstablished?()
        }
        after(9) { [weak self] in self?.simulateIncomingSMS(otp: true) }
    }

    func stop() {
        timers.forEach { $0.invalidate() }
        timers.removeAll()
    }

    func reconnectNow() {}

    func measureLatency() async {
        latency = Double.random(in: 0.030...0.070)
    }

    // MARK: - Data

    private func buildData() {
        let now = Date().milliseconds
        for (index, person) in content.people.enumerated() {
            contacts.append(ContactRecord(key: "c:\(index + 1)", name: person.name, starred: person.starred,
                                          numbers: [ContactNumber(n: person.number, label: person.label)],
                                          emails: person.email.map { [$0] } ?? []))
        }
        for (threadIndex, conversation) in content.conversations.enumerated() {
            let address = conversation.person.map { content.people[$0].number } ?? conversation.sender
            for line in conversation.lines {
                nextKey += 1
                messages.append(SMSMessage(key: "sms:\(nextKey)", thread: Int64(threadIndex + 1), addr: address,
                                           body: line.text, time: now - Int64(line.minutesAgo) * 60_000,
                                           box: line.fromMe ? "out" : "in", sim: threadIndex % 3 == 2 ? 1 : 0,
                                           state: line.fromMe ? "delivered" : "received"))
            }
        }
        for (index, call) in content.calls.enumerated() {
            let person = call.person.map { content.people[$0] }
            calls.append(CallLogRecord(key: "cl:\(index + 1)", number: person?.number ?? call.number, name: person?.name,
                                       type: call.type, time: now - Int64(call.minutesAgo) * 60_000, dur: call.duration,
                                       sim: call.sim))
        }
    }

    private func statusBody(id: Int?) -> [String: Any] {
        var body: [String: Any] = [
            "t": "status",
            "battery": ["level": battery, "charging": false],
            "sims": [
                ["slot": 0, "carrier": content.carriers.0, "label": "SIM 1", "number": content.ownNumber, "signal": 4,
                 "net": "5G", "roaming": false, "defaultSms": true, "defaultVoice": true],
                ["slot": 1, "carrier": content.carriers.1, "label": "SIM 2", "number": NSNull(), "signal": 2,
                 "net": "LTE", "roaming": false, "defaultSms": false, "defaultVoice": false],
            ],
            "app": "0.1.0",
            // Demo mode has no audio relay: calls stay "audio on the Android phone".
            "caps": Capability.allCases.filter { ![.callAudio, .callMedia, .callMediaTalk, .netShare].contains($0) }
                .map(\.rawValue),
            "time": Date().milliseconds,
        ]
        if let id { body["id"] = id }
        return body
    }

    // MARK: - BridgeLink

    func request(_ message: Outbound) async throws -> Inbound {
        guard phase == .connected else { throw LinkError.notConnected }
        let id = Int.random(in: 1...1_000_000)
        try await Task.sleep(nanoseconds: UInt64.random(in: 60_000_000...180_000_000))
        let reply = try respond(to: message, id: id)
        let inbound = try Inbound.decode(JSONSerialization.data(withJSONObject: reply))
        if case .error(let error) = inbound.message { throw LinkError.bridge(error) }
        return inbound
    }

    func send(_ message: Outbound) {}

    private func respond(to message: Outbound, id: Int) throws -> [String: Any] {
        let f = message.fields
        switch message.type {
        case "status.get":
            return statusBody(id: id)
        case "ping":
            return ["t": "pong", "id": id]
        case "sms.threads":
            return ["t": "sms.threads", "id": id, "threads": threadSummaries(), "more": false]
        case "sms.history":
            return history(fields: f, id: id)
        case "sms.send":
            return sendSMS(fields: f, id: id)
        case "contacts.sync":
            // The demo is small, so every sync is a full one.
            return ["t": "contacts.sync", "id": id, "contacts": contacts.map(encode), "deleted": [],
                    "cursor": "demo-\(contacts.count)", "more": false, "full": true]
        case "calllog.sync":
            return ["t": "calllog.sync", "id": id, "calls": calls.map(encode), "cursor": "demo-\(calls.count)",
                    "more": false, "full": true]
        case "contacts.add":
            let name = f["name"] as? String ?? ""
            let numbers = (f["numbers"] as? [[String: Any]] ?? []).compactMap { n -> ContactNumber? in
                guard let number = n["n"] as? String else { return nil }
                return ContactNumber(n: number, label: n["label"] as? String)
            }
            contacts.append(ContactRecord(key: "c:\(contacts.count + 100)", name: name, starred: false, numbers: numbers,
                                          emails: []))
            return ["t": "ok", "id": id]
        case "ussd.run":
            try? awaitDelay()
            let code = f["code"] as? String ?? ""
            let text: String
            switch code {
            case "*140#", "*123#", "*100#": text = content.ussdBalance
            case "*555#", "*1#": text = content.ussdMenu
            case "*#06#": text = "IMEI: 35 209900 176148 1"
            default: return ["t": "ussd.result", "id": id, "ok": false, "error": content.ussdUnknown]
            }
            return ["t": "ussd.result", "id": id, "ok": true, "text": text]
        case "call.dial":
            return dial(number: f["number"] as? String ?? "", id: id)
        case "call.answer":
            guard let call = activeCall else { return ["t": "error", "id": id, "code": "invalid", "msg": "no call"] }
            activeCall?.state = "active"
            after(0.3) { [weak self] in self?.emitCallState(call.id, "active", dir: call.dir, number: call.number) }
            return ["t": "ok", "id": id]
        case "call.reject", "call.end":
            guard let call = activeCall else { return ["t": "ok", "id": id] }
            let reason = message.type == "call.reject" ? "rejected" : "local"
            finishCall(call, reason: reason, answered: call.state == "active")
            return ["t": "ok", "id": id]
        case "call.dtmf", "call.audio":
            return ["t": "ok", "id": id]
        default:
            return ["t": "error", "id": id, "code": "unsupported", "msg": "Not in the demo"]
        }
    }

    private func awaitDelay() throws {}

    private func threadSummaries() -> [[String: Any]] {
        let grouped = Dictionary(grouping: messages, by: { $0.thread ?? 0 })
        return grouped.map { thread, items in
            let last = items.max { $0.time < $1.time }!
            return ["thread": thread, "addrs": [last.addr], "snippet": last.body, "time": last.time, "count": items.count]
        }
    }

    private func history(fields f: [String: Any], id: Int) -> [String: Any] {
        let thread = (f["thread"] as? NSNumber)?.int64Value
        let limit = (f["limit"] as? Int) ?? 50
        var items = messages.filter { thread == nil || $0.thread == thread }
        if let after = (f["after"] as? NSNumber)?.int64Value {
            items = items.filter { $0.time > after }.sorted { $0.time < $1.time }
        } else {
            if let before = (f["before"] as? NSNumber)?.int64Value { items = items.filter { $0.time < before } }
            items.sort { $0.time > $1.time }
        }
        let page = Array(items.prefix(limit))
        return ["t": "sms.history", "id": id, "msgs": page.map(encode), "more": items.count > limit]
    }

    private func sendSMS(fields f: [String: Any], id: Int) -> [String: Any] {
        let cid = f["cid"] as? String ?? ""
        let to = f["to"] as? String ?? ""
        let body = f["body"] as? String ?? ""
        nextKey += 1
        let key = "sms:\(nextKey)"
        let thread = threadID(for: to)
        let sent = SMSMessage(key: key, thread: thread, addr: to, body: body, time: Date().milliseconds, box: "out",
                              sim: (f["sim"] as? Int) ?? 0, state: "sent")
        messages.append(sent)
        after(0.8) { [weak self] in self?.emit(["t": "sms.state", "cid": cid, "state": "sent", "key": key]) }
        after(2.0) { [weak self] in self?.emit(["t": "sms.state", "cid": cid, "state": "delivered", "key": key]) }
        if content.people.contains(where: { PhoneNumber.matchKey($0.number) == PhoneNumber.matchKey(to) }) {
            after(4.5) { [weak self] in
                guard let self else { return }
                self.receive(from: to, text: self.content.replies.randomElement() ?? "👍")
            }
        }
        return ["t": "ok", "id": id]
    }

    private func threadID(for address: String) -> Int64 {
        if let existing = messages.first(where: { PhoneNumber.matchKey($0.addr) == PhoneNumber.matchKey(address) })?.thread {
            return existing
        }
        return (messages.compactMap(\.thread).max() ?? 0) + 1
    }

    private func receive(from address: String, text: String) {
        nextKey += 1
        let message = SMSMessage(key: "sms:\(nextKey)", thread: threadID(for: address), addr: address, body: text,
                                 time: Date().milliseconds, box: "in", sim: 0, state: "received")
        messages.append(message)
        emit(["t": "sms.new", "msg": encode(message)])
    }

    private func dial(number: String, id: Int) -> [String: Any] {
        guard activeCall == nil else { return ["t": "error", "id": id, "code": "busy", "msg": "call in progress"] }
        let callID = nextCallID
        nextCallID += 1
        activeCall = (callID, number, "out", "dialing")
        after(0.4) { [weak self] in self?.emitCallState(callID, "dialing", dir: "out", number: number) }
        after(3.0) { [weak self] in
            guard let self, self.activeCall?.id == callID else { return }
            self.activeCall?.state = "active"
            self.emitCallState(callID, "active", dir: "out", number: number)
        }
        return ["t": "ok", "id": id]
    }

    private func finishCall(_ call: (id: Int64, number: String, dir: String, state: String), reason: String, answered: Bool) {
        activeCall = nil
        emitCallState(call.id, "ended", dir: call.dir, number: call.number, reason: reason)
        let type = call.dir == "out" ? "out" : (answered ? "in" : (reason == "rejected" ? "rejected" : "missed"))
        let person = content.people.first { PhoneNumber.matchKey($0.number) == PhoneNumber.matchKey(call.number) }
        calls.insert(CallLogRecord(key: "cl:\(calls.count + 100)", number: call.number, name: person?.name, type: type,
                                   time: Date().milliseconds, dur: answered ? 42 : 0, sim: 0), at: 0)
    }

    private func emitCallState(_ id: Int64, _ state: String, dir: String, number: String, reason: String? = nil) {
        let person = content.people.first { PhoneNumber.matchKey($0.number) == PhoneNumber.matchKey(number) }
        var body: [String: Any] = ["t": "call.state", "call": id, "state": state, "dir": dir, "number": number,
                                   "since": Date().milliseconds]
        if let name = person?.name { body["name"] = name }
        if let reason { body["reason"] = reason }
        emit(body)
    }

    // MARK: - Demo actions

    func simulateIncomingSMS(otp: Bool) {
        guard phase == .connected else { return }
        if otp {
            receive(from: content.incomingOTP.sender, text: content.incomingOTP.text)
        } else if let person = content.people.first {
            receive(from: person.number, text: content.replies.randomElement() ?? "👋")
        }
    }

    func simulateIncomingCall() {
        guard phase == .connected, activeCall == nil else { return }
        let person = content.people[0]
        let callID = nextCallID
        nextCallID += 1
        activeCall = (callID, person.number, "in", "ringing")
        emit(["t": "call.incoming", "call": callID, "sim": 0, "from": person.number, "name": person.name])
        emitCallState(callID, "ringing", dir: "in", number: person.number)
        // Unanswered calls stop ringing after a while, like a real phone.
        after(30) { [weak self] in
            guard let self, let call = self.activeCall, call.id == callID, call.state == "ringing" else { return }
            self.finishCall(call, reason: "missed", answered: false)
        }
    }

    /// The other side hangs up an active demo call.
    func simulateRemoteHangUp() {
        guard let call = activeCall else { return }
        finishCall(call, reason: "remote", answered: call.state == "active")
    }

    // MARK: - Helpers

    private func emit(_ body: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: body), let inbound = try? Inbound.decode(data) else {
            return
        }
        onEvent?(inbound)
    }

    private func after(_ seconds: TimeInterval, _ block: @escaping () -> Void) {
        let timer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { _ in
            Task { @MainActor in block() }
        }
        timers.append(timer)
    }

    private func encode(_ m: SMSMessage) -> [String: Any] {
        ["key": m.key, "thread": m.thread ?? 0, "addr": m.addr, "body": m.body, "time": m.time, "box": m.box,
         "sim": m.sim ?? 0, "state": m.state ?? "received"]
    }

    private func encode(_ c: ContactRecord) -> [String: Any] {
        ["key": c.key, "name": c.name ?? "", "starred": c.starred ?? false,
         "numbers": (c.numbers ?? []).map { ["n": $0.n, "label": $0.label ?? "mobile"] }, "emails": c.emails ?? []]
    }

    private func encode(_ c: CallLogRecord) -> [String: Any] {
        ["key": c.key, "number": c.number ?? "", "name": c.name ?? NSNull(), "type": c.type, "time": c.time,
         "dur": c.dur ?? 0, "sim": c.sim ?? 0]
    }
}
