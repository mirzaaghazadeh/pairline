import Combine
import Foundation

/// Local store for synced messages, contacts and call log. The real link uses a file protected with
/// `completeUntilFirstUserAuthentication`; demo mode uses a separate in-memory database, so demo data
/// never mixes with real data (D-020).
@MainActor
final class AppDatabase: ObservableObject {
    /// Bumped after every write; views reload when it changes.
    @Published private(set) var revision = 0

    private let db: SQLiteDatabase
    private(set) var contactIndex: [String: String] = [:]

    init(url: URL?) throws {
        db = try SQLiteDatabase(url: url)
        try migrate()
        rebuildContactIndex()
    }

    /// Opens the on-device database, falling back to memory if the file can't be opened.
    static func openDefault() -> AppDatabase {
        if let url = AppPaths.databaseURL, let database = try? AppDatabase(url: url) {
            return database
        }
        log.error("db", "Couldn't open the database file; using memory")
        return try! AppDatabase(url: nil)
    }

    static func inMemory() -> AppDatabase { try! AppDatabase(url: nil) }

    private func migrate() throws {
        let version = try db.query("PRAGMA user_version").first?.int("user_version") ?? 0
        if version < 1 {
            try db.transaction {
                try db.execute("""
                    CREATE TABLE IF NOT EXISTS messages (
                      key TEXT PRIMARY KEY, thread INTEGER NOT NULL, addr TEXT NOT NULL, body TEXT NOT NULL,
                      time INTEGER NOT NULL, box TEXT NOT NULL, sim INTEGER, state TEXT NOT NULL, cid TEXT, error TEXT)
                    """)
                try db.execute("CREATE INDEX IF NOT EXISTS messages_thread_time ON messages(thread, time)")
                try db.execute("CREATE INDEX IF NOT EXISTS messages_cid ON messages(cid)")
                try db.execute("""
                    CREATE TABLE IF NOT EXISTS threads (
                      thread INTEGER PRIMARY KEY, addrs TEXT NOT NULL, snippet TEXT NOT NULL DEFAULT '',
                      time INTEGER NOT NULL, count INTEGER, unread INTEGER NOT NULL DEFAULT 0,
                      has_older INTEGER NOT NULL DEFAULT 1)
                    """)
                try db.execute("""
                    CREATE TABLE IF NOT EXISTS contacts (
                      key TEXT PRIMARY KEY, name TEXT NOT NULL, starred INTEGER NOT NULL DEFAULT 0,
                      numbers TEXT NOT NULL, emails TEXT NOT NULL)
                    """)
                try db.execute("""
                    CREATE TABLE IF NOT EXISTS calls (
                      key TEXT PRIMARY KEY, number TEXT NOT NULL, name TEXT, type TEXT NOT NULL,
                      time INTEGER NOT NULL, dur INTEGER NOT NULL DEFAULT 0, sim INTEGER)
                    """)
                try db.execute("CREATE INDEX IF NOT EXISTS calls_time ON calls(time)")
                try db.execute("CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                try db.execute("PRAGMA user_version = 1")
            }
        }
    }

    private func changed() {
        revision &+= 1
    }

    // MARK: - Key-value

    func value(_ key: String) -> String? {
        (try? db.query("SELECT value FROM kv WHERE key = ?", [.text(key)]))?.first?.string("value")
    }

    func setValue(_ value: String?, for key: String) {
        if let value {
            try? db.execute("INSERT INTO kv(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                            [.text(key), .text(value)])
        } else {
            try? db.execute("DELETE FROM kv WHERE key = ?", [.text(key)])
        }
    }

    // MARK: - Messages

    /// Inserts or updates messages from the bridge. Returns the ones that were new to this iPhone.
    @discardableResult
    func upsertMessages(_ messages: [SMSMessage], countUnread: Bool) -> [StoredMessage] {
        guard !messages.isEmpty else { return [] }
        var inserted: [StoredMessage] = []
        try? db.transaction {
            for message in messages {
                if try upsert(message, countUnread: countUnread) {
                    inserted.append(StoredMessage(dto: message))
                }
            }
        }
        changed()
        return inserted
    }

    private func upsert(_ m: SMSMessage, countUnread: Bool) throws -> Bool {
        let thread = m.thread ?? PhoneNumber.syntheticThreadID(for: m.addr)
        let state = m.state ?? (m.box == "out" ? "sent" : "received")
        if try !db.query("SELECT key FROM messages WHERE key = ?", [.text(m.key)]).isEmpty {
            try db.execute("UPDATE messages SET thread = ?, state = CASE WHEN ? = 'received' THEN state ELSE ? END WHERE key = ?",
                           [.int(thread), .text(state), .text(state), .text(m.key)])
            if thread >= 0 { try adoptSyntheticThread(for: m.addr, into: thread) }
            return false
        }
        // An incoming SMS announced before Android stored it ("rx:<time>", PROTOCOL.md) and its stored copy are the
        // same message when address and text match within 60 s.
        if m.box == "in", let twin = try sameIncoming(m) {
            if m.key.hasPrefix("rx:") { return false }
            if twin.hasPrefix("rx:") {
                try db.execute("UPDATE messages SET key = ?, thread = ?, time = ? WHERE key = ?",
                               [.text(m.key), .int(thread), .int(m.time), .text(twin)])
                if thread >= 0 { try adoptSyntheticThread(for: m.addr, into: thread) }
                return false
            }
        }
        // A message we sent that the bridge stored before telling us its key: replace the local copy.
        if m.box == "out", let local = try db.query("""
            SELECT key FROM messages WHERE key LIKE 'local:%' AND body = ? AND box = 'out'
            AND ABS(time - ?) < 600000 ORDER BY time LIMIT 1
            """, [.text(m.body), .int(m.time)]).first?.string("key") {
            try db.execute("UPDATE messages SET key = ?, thread = ?, state = ? WHERE key = ?",
                           [.text(m.key), .int(thread), .text(state), .text(local)])
            if thread >= 0 { try adoptSyntheticThread(for: m.addr, into: thread) }
            try touchThread(thread, addr: m.addr, body: m.body, time: m.time, newMessage: false, unread: false)
            return false
        }
        try db.execute("""
            INSERT INTO messages(key, thread, addr, body, time, box, sim, state) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
            """, [.text(m.key), .int(thread), .text(m.addr), .text(m.body), .int(m.time), .text(m.box),
                  .from(m.sim), .text(state)])
        if thread >= 0 { try adoptSyntheticThread(for: m.addr, into: thread) }
        try touchThread(thread, addr: m.addr, body: m.body, time: m.time, newMessage: countUnread,
                        unread: countUnread && m.box == "in")
        return true
    }

    /// The key of an incoming message with the same text from the same address within ±60 s, if one is stored.
    private func sameIncoming(_ m: SMSMessage) throws -> String? {
        let rows = try db.query("""
            SELECT key, addr FROM messages WHERE box = 'in' AND body = ? AND ABS(time - ?) <= 60000 AND key != ?
            """, [.text(m.body), .int(m.time), .text(m.key)])
        let wanted = PhoneNumber.matchKey(m.addr)
        return rows.first { PhoneNumber.matchKey($0.string("addr") ?? "") == wanted }?.string("key")
    }

    /// Moves a conversation started on the iPhone (synthetic id) into the bridge's real thread.
    private func adoptSyntheticThread(for address: String, into thread: Int64) throws {
        let synthetic = PhoneNumber.syntheticThreadID(for: address)
        guard synthetic != thread,
              try !db.query("SELECT thread FROM threads WHERE thread = ?", [.int(synthetic)]).isEmpty else { return }
        try db.execute("UPDATE messages SET thread = ? WHERE thread = ?", [.int(thread), .int(synthetic)])
        if try db.query("SELECT thread FROM threads WHERE thread = ?", [.int(thread)]).isEmpty {
            try db.execute("UPDATE threads SET thread = ?, has_older = 1 WHERE thread = ?", [.int(thread), .int(synthetic)])
        } else {
            try db.execute("UPDATE threads SET unread = unread + (SELECT unread FROM threads WHERE thread = ?) WHERE thread = ?",
                           [.int(synthetic), .int(thread)])
            try db.execute("DELETE FROM threads WHERE thread = ?", [.int(synthetic)])
        }
        try refreshThreadSummary(thread)
    }

    private func touchThread(_ thread: Int64, addr: String, body: String, time: Int64, newMessage: Bool,
                             unread: Bool) throws {
        if let row = try db.query("SELECT time, addrs FROM threads WHERE thread = ?", [.int(thread)]).first {
            var addrs = decodeList(row.string("addrs"))
            if !addrs.contains(where: { PhoneNumber.matchKey($0) == PhoneNumber.matchKey(addr) }) { addrs.append(addr) }
            if time >= row.int64("time") ?? 0 {
                try db.execute("UPDATE threads SET snippet = ?, time = ?, addrs = ? WHERE thread = ?",
                               [.text(body), .int(time), .text(encodeList(addrs)), .int(thread)])
            }
            if newMessage {
                // The bridge's count (from sms.threads) grows with live messages; history pages were already in it.
                try db.execute("UPDATE threads SET count = count + 1 WHERE thread = ? AND count IS NOT NULL",
                               [.int(thread)])
            }
            if unread {
                try db.execute("UPDATE threads SET unread = unread + 1 WHERE thread = ?", [.int(thread)])
            }
        } else {
            // Count unknown until the bridge reports it.
            try db.execute("""
                INSERT INTO threads(thread, addrs, snippet, time, count, unread, has_older) VALUES(?, ?, ?, ?, NULL, ?, ?)
                """, [.int(thread), .text(encodeList([addr])), .text(body), .int(time), .int(unread ? 1 : 0),
                      .int(thread >= 0 && !historyComplete ? 1 : 0)])
        }
    }

    private func refreshThreadSummary(_ thread: Int64) throws {
        guard let last = try db.query("SELECT body, time FROM messages WHERE thread = ? ORDER BY time DESC LIMIT 1",
                                      [.int(thread)]).first else { return }
        try db.execute("UPDATE threads SET snippet = ?, time = MAX(time, ?) WHERE thread = ?",
                       [.text(last.string("body") ?? ""), .int(last.int64("time") ?? 0), .int(thread)])
    }

    /// Thread summaries from `sms.threads`.
    func upsertThreads(_ threads: [SMSThreadSummary]) {
        guard !threads.isEmpty else { return }
        try? db.transaction {
            for t in threads {
                let addrs = encodeList(t.addrs ?? [])
                try db.execute("""
                    INSERT INTO threads(thread, addrs, snippet, time, count) VALUES(?, ?, ?, ?, ?)
                    ON CONFLICT(thread) DO UPDATE SET addrs = excluded.addrs,
                      snippet = CASE WHEN excluded.time >= threads.time THEN excluded.snippet ELSE threads.snippet END,
                      time = MAX(threads.time, excluded.time), count = excluded.count
                    """, [.int(t.thread), .text(addrs),
                          .text(t.snippet ?? ""), .int(t.time ?? 0), .from(t.count)])
                if let first = t.addrs?.first { try adoptSyntheticThread(for: first, into: t.thread) }
            }
        }
        changed()
    }

    /// A message typed on the iPhone, before the bridge confirms it.
    func insertPending(thread: Int64?, to address: String, body: String, sim: Int?, cid: String) -> StoredMessage {
        let threadID = thread ?? existingThread(for: address) ?? PhoneNumber.syntheticThreadID(for: address)
        let now = Date().milliseconds
        let key = "local:\(cid)"
        try? db.transaction {
            try db.execute("""
                INSERT INTO messages(key, thread, addr, body, time, box, sim, state, cid) VALUES(?, ?, ?, ?, ?, 'out', ?, 'pending', ?)
                """, [.text(key), .int(threadID), .text(address), .text(body), .int(now), .from(sim), .text(cid)])
            try touchThread(threadID, addr: address, body: body, time: now, newMessage: true, unread: false)
        }
        changed()
        return StoredMessage(key: key, thread: threadID, addr: address, body: body, time: Date(milliseconds: now),
                             isOutgoing: true, sim: sim, state: .pending, cid: cid, error: nil)
    }

    /// The thread already used for this address, if any.
    func existingThread(for address: String) -> Int64? {
        guard let key = PhoneNumber.matchKey(address) else { return nil }
        let rows = (try? db.query("SELECT thread, addrs FROM threads ORDER BY time DESC")) ?? []
        for row in rows {
            let addrs = decodeList(row.string("addrs"))
            if addrs.count == 1, PhoneNumber.matchKey(addrs[0]) == key { return row.int64("thread") }
        }
        return nil
    }

    func updateState(cid: String, state: MessageState, key: String?, error: String?) {
        try? db.transaction {
            guard let row = try db.query("SELECT key FROM messages WHERE cid = ?", [.text(cid)]).first,
                  let current = row.string("key") else { return }
            try db.execute("UPDATE messages SET state = ?, error = ? WHERE key = ?",
                           [.text(state.rawValue), .from(error), .text(current)])
            if let key, key != current {
                if try db.query("SELECT key FROM messages WHERE key = ?", [.text(key)]).isEmpty {
                    try db.execute("UPDATE messages SET key = ? WHERE key = ?", [.text(key), .text(current)])
                } else {
                    // History sync already brought the stored copy; keep it and drop the local one.
                    try db.execute("UPDATE messages SET state = ?, cid = ? WHERE key = ?",
                                   [.text(state.rawValue), .text(cid), .text(key)])
                    try db.execute("DELETE FROM messages WHERE key = ?", [.text(current)])
                }
            }
        }
        changed()
    }

    func markFailed(cid: String, error: String?) {
        updateState(cid: cid, state: .failed, key: nil, error: error)
    }

    func deleteMessage(key: String) {
        try? db.execute("DELETE FROM messages WHERE key = ?", [.text(key)])
        changed()
    }

    func conversations() -> [Conversation] {
        let rows = (try? db.query("""
            SELECT t.thread, t.addrs, t.snippet, t.time, t.unread,
              (t.has_older = 1 AND (t.count IS NULL OR
                (SELECT COUNT(*) FROM messages m WHERE m.thread = t.thread) < t.count)) AS has_older,
              (SELECT box FROM messages m WHERE m.thread = t.thread ORDER BY time DESC LIMIT 1) AS last_box,
              (SELECT state FROM messages m WHERE m.thread = t.thread ORDER BY time DESC LIMIT 1) AS last_state
            FROM threads t ORDER BY t.time DESC
            """)) ?? []
        return rows.map { row in
            Conversation(thread: row.int64("thread") ?? 0, addresses: decodeList(row.string("addrs")),
                         snippet: row.string("snippet") ?? "", time: Date(milliseconds: row.int64("time") ?? 0),
                         unread: row.int("unread") ?? 0, hasOlder: row.bool("has_older"),
                         lastIsOutgoing: row.string("last_box") == "out",
                         lastState: row.string("last_state").flatMap(MessageState.init(rawValue:)))
        }
    }

    func conversation(thread: Int64) -> Conversation? {
        conversations().first { $0.thread == thread }
    }

    func messages(thread: Int64) -> [StoredMessage] {
        let rows = (try? db.query("SELECT * FROM messages WHERE thread = ? ORDER BY time ASC, key ASC", [.int(thread)])) ?? []
        return rows.map(StoredMessage.init(row:))
    }

    func message(key: String) -> StoredMessage? {
        (try? db.query("SELECT * FROM messages WHERE key = ?", [.text(key)]))?.first.map(StoredMessage.init(row:))
    }

    func message(cid: String) -> StoredMessage? {
        (try? db.query("SELECT * FROM messages WHERE cid = ?", [.text(cid)]))?.first.map(StoredMessage.init(row:))
    }

    /// Conversations whose address, contact name or message text match.
    func searchConversations(_ query: String) -> [(Conversation, StoredMessage?)] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return conversations().map { ($0, nil) } }
        let all = conversations()
        let lower = q.lowercased()
        let digitsKey = PhoneNumber.asciiDigits(q).filter(\.isNumber)
        var result: [(Conversation, StoredMessage?)] = []
        var seen = Set<Int64>()
        for conversation in all {
            let names = conversation.addresses.map { displayName(for: $0) }
            let addressMatch = conversation.addresses.contains {
                digitsKey.count >= 3 && PhoneNumber.asciiDigits($0).filter(\.isNumber).contains(digitsKey)
            }
            if addressMatch || names.contains(where: { $0.lowercased().contains(lower) }) {
                result.append((conversation, nil))
                seen.insert(conversation.thread)
            }
        }
        let escaped = q.replacingOccurrences(of: "%", with: "\\%").replacingOccurrences(of: "_", with: "\\_")
        let rows = (try? db.query("""
            SELECT * FROM messages WHERE body LIKE ? ESCAPE '\\' ORDER BY time DESC LIMIT 200
            """, [.text("%\(escaped)%")])) ?? []
        for row in rows {
            let message = StoredMessage(row: row)
            guard !seen.contains(message.thread), let conversation = all.first(where: { $0.thread == message.thread })
            else { continue }
            seen.insert(message.thread)
            result.append((conversation, message))
        }
        return result
    }

    func markRead(thread: Int64) {
        try? db.execute("UPDATE threads SET unread = 0 WHERE thread = ? AND unread > 0", [.int(thread)])
        if db.changes > 0 { changed() }
    }

    func markAllRead() {
        try? db.execute("UPDATE threads SET unread = 0 WHERE unread > 0")
        changed()
    }

    var unreadCount: Int {
        (try? db.query("SELECT COALESCE(SUM(unread), 0) AS n FROM threads"))?.first?.int("n") ?? 0
    }

    /// After a history sync that reached the beginning, every conversation is complete, and so is any new one.
    func markAllThreadsComplete() {
        try? db.execute("UPDATE threads SET has_older = 0")
        setValue("1", for: "history.complete")
        changed()
    }

    private var historyComplete: Bool { value("history.complete") == "1" }

    func setHasOlder(thread: Int64, _ hasOlder: Bool) {
        try? db.execute("UPDATE threads SET has_older = ? WHERE thread = ?", [.from(hasOlder), .int(thread)])
        changed()
    }

    var newestMessageTime: Int64? {
        (try? db.query("SELECT MAX(time) AS t FROM messages WHERE key NOT LIKE 'local:%' AND key NOT LIKE 'rx:%'"))?
            .first?.int64("t")
    }

    func oldestMessageTime(thread: Int64) -> Int64? {
        (try? db.query("SELECT MIN(time) AS t FROM messages WHERE thread = ? AND key NOT LIKE 'local:%'",
                       [.int(thread)]))?.first?.int64("t")
    }

    var messageCount: Int {
        (try? db.query("SELECT COUNT(*) AS n FROM messages"))?.first?.int("n") ?? 0
    }

    // MARK: - Contacts

    func upsertContacts(_ contacts: [ContactRecord], deleted: [String]) {
        guard !contacts.isEmpty || !deleted.isEmpty else { return }
        try? db.transaction {
            for c in contacts {
                let numbers = (try? JSONEncoder().encode(c.numbers ?? [])).map { String(decoding: $0, as: UTF8.self) } ?? "[]"
                let name = (c.name?.isEmpty == false ? c.name : c.numbers?.first?.n) ?? ""
                try db.execute("""
                    INSERT INTO contacts(key, name, starred, numbers, emails) VALUES(?, ?, ?, ?, ?)
                    ON CONFLICT(key) DO UPDATE SET name = excluded.name, starred = excluded.starred,
                      numbers = excluded.numbers, emails = excluded.emails
                    """, [.text(c.key), .text(name), .from(c.starred ?? false), .text(numbers),
                          .text(encodeList(c.emails ?? []))])
            }
            for key in deleted {
                try db.execute("DELETE FROM contacts WHERE key = ?", [.text(key)])
            }
        }
        rebuildContactIndex()
        changed()
    }

    func contacts() -> [Contact] {
        let rows = (try? db.query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE")) ?? []
        return rows.map { row in
            let numbers = (row.string("numbers")?.data(using: .utf8))
                .flatMap { try? JSONDecoder().decode([ContactNumber].self, from: $0) } ?? []
            return Contact(key: row.string("key") ?? "", name: row.string("name") ?? "", starred: row.bool("starred"),
                           numbers: numbers, emails: decodeList(row.string("emails")))
        }
    }

    func contact(forNumber number: String) -> Contact? {
        guard let key = PhoneNumber.matchKey(number) else { return nil }
        return contacts().first { contact in contact.numbers.contains { PhoneNumber.matchKey($0.n) == key } }
    }

    var contactCount: Int {
        (try? db.query("SELECT COUNT(*) AS n FROM contacts"))?.first?.int("n") ?? 0
    }

    private func rebuildContactIndex() {
        var index: [String: String] = [:]
        for contact in contacts() {
            for number in contact.numbers {
                if let key = PhoneNumber.matchKey(number.n) { index[key] = contact.name }
            }
        }
        contactIndex = index
    }

    /// The contact name for an address, or the address itself.
    func displayName(for address: String) -> String {
        if let key = PhoneNumber.matchKey(address), let name = contactIndex[key] { return name }
        return address
    }

    func knownName(for address: String) -> String? {
        PhoneNumber.matchKey(address).flatMap { contactIndex[$0] }
    }

    // MARK: - Call log

    func upsertCalls(_ calls: [CallLogRecord]) {
        guard !calls.isEmpty else { return }
        try? db.transaction {
            for c in calls {
                try db.execute("""
                    INSERT INTO calls(key, number, name, type, time, dur, sim) VALUES(?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(key) DO UPDATE SET number = excluded.number, name = excluded.name, type = excluded.type,
                      time = excluded.time, dur = excluded.dur, sim = excluded.sim
                    """, [.text(c.key), .text(c.number ?? ""), .from(c.name), .text(c.type), .int(c.time),
                          .int(Int64(c.dur ?? 0)), .from(c.sim)])
            }
        }
        changed()
    }

    func calls(limit: Int = 500) -> [CallLogItem] {
        let rows = (try? db.query("SELECT * FROM calls ORDER BY time DESC LIMIT ?", [.int(Int64(limit))])) ?? []
        return rows.map { row in
            CallLogItem(key: row.string("key") ?? "", number: row.string("number") ?? "", name: row.string("name"),
                        type: CallLogType(rawValue: row.string("type") ?? "") ?? .incoming,
                        time: Date(milliseconds: row.int64("time") ?? 0), duration: row.int("dur") ?? 0,
                        sim: row.int("sim"))
        }
    }

    func calls(matching number: String, limit: Int = 20) -> [CallLogItem] {
        guard let key = PhoneNumber.matchKey(number) else { return [] }
        return calls(limit: 1000).filter { PhoneNumber.matchKey($0.number) == key }.prefix(limit).map { $0 }
    }

    func clearContacts() {
        try? db.execute("DELETE FROM contacts")
        rebuildContactIndex()
        changed()
    }

    func clearCalls() {
        try? db.execute("DELETE FROM calls")
        changed()
    }

    // MARK: - Wipe

    /// Deletes everything synced from the bridge (unpair).
    func wipe() {
        try? db.transaction {
            for table in ["messages", "threads", "contacts", "calls", "kv"] {
                try db.execute("DELETE FROM \(table)")
            }
        }
        contactIndex = [:]
        changed()
    }

    // MARK: - Helpers

    private func encodeList(_ list: [String]) -> String {
        (try? JSONEncoder().encode(list)).map { String(decoding: $0, as: UTF8.self) } ?? "[]"
    }

    private func decodeList(_ text: String?) -> [String] {
        guard let data = text?.data(using: .utf8) else { return [] }
        return (try? JSONDecoder().decode([String].self, from: data)) ?? []
    }
}

private extension StoredMessage {
    init(row: SQLRow) {
        self.init(key: row.string("key") ?? "", thread: row.int64("thread") ?? 0, addr: row.string("addr") ?? "",
                  body: row.string("body") ?? "", time: Date(milliseconds: row.int64("time") ?? 0),
                  isOutgoing: row.string("box") == "out", sim: row.int("sim"),
                  state: MessageState(rawValue: row.string("state") ?? "") ?? .received,
                  cid: row.string("cid"), error: row.string("error"))
    }

    init(dto m: SMSMessage) {
        self.init(key: m.key, thread: m.thread ?? PhoneNumber.syntheticThreadID(for: m.addr), addr: m.addr, body: m.body,
                  time: Date(milliseconds: m.time), isOutgoing: m.box == "out", sim: m.sim,
                  state: MessageState(rawValue: m.state ?? "") ?? (m.box == "out" ? .sent : .received),
                  cid: nil, error: nil)
    }
}
