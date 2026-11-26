import SwiftUI

private func t(_ key: String) -> Text { Text(LocalizedStringKey(key), tableName: "PhoneTools") }

/// Settings › Export messages (D-079): every conversation on this iPhone as a readable text file and a CSV file
/// (for a spreadsheet). Made on this iPhone; it goes only where the user sends it from the share sheet. The files are
/// deleted when the screen closes.
struct ExportMessagesView: View {
    @EnvironmentObject private var bridge: BridgeController
    @State private var files: [URL] = []
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        List {
            Section {
                t("Makes a copy of every conversation on this iPhone: one text file to read, and one CSV file for a spreadsheet. It isn't sent anywhere: you choose where to save or share it.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            Section {
                if files.isEmpty {
                    Button {
                        make()
                    } label: {
                        Label { t("Create export") } icon: { Image(systemName: "square.and.arrow.down.on.square") }
                    }
                    .disabled(busy)
                } else {
                    ForEach(files, id: \.self) { url in
                        ShareLink(item: url) {
                            Label { Text(verbatim: url.lastPathComponent) } icon: { Image(systemName: "square.and.arrow.up") }
                        }
                    }
                }
            } footer: {
                t("The files hold your messages as plain text. Keep them somewhere safe, and delete them when you no longer need them.")
            }
            if let error {
                Section { Text(verbatim: error).foregroundStyle(.red) }
            }
        }
        .navigationTitle(t("Export messages"))
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { MessageExport.remove(files) }
    }

    private func make() {
        busy = true
        error = nil
        let db = bridge.db
        Task {
            defer { busy = false }
            do {
                files = try MessageExport.makeFiles(db: db)
                log.info("export", "Exported \(files.count) files")
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

/// The export's contents (D-079): conversations newest first, each message with its time, direction and SIM.
@MainActor
enum MessageExport {
    static func makeFiles(db: AppDatabase) throws -> [URL] {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("export-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let day = DateFormatter()
        day.locale = Locale(identifier: "en_US_POSIX")
        day.dateFormat = "yyyy-MM-dd"
        let stamp = ISO8601DateFormatter()
        stamp.formatOptions = [.withInternetDateTime]

        var text = ""
        var csv = "conversation,address,direction,sim,time,text\n"
        for conversation in db.conversations().sorted(by: { $0.time > $1.time }) {
            let names = conversation.addresses.map { db.displayName(for: $0) }.joined(separator: ", ")
            text += "=== \(names) (\(conversation.addresses.joined(separator: ", ")))\n"
            for m in db.messages(thread: conversation.thread) {
                let who = m.isOutgoing ? "→" : "←"
                let sim = m.sim.map { " SIM \($0 + 1)" } ?? ""
                text += "[\(stamp.string(from: m.time))\(sim)] \(who) \(m.body)\n"
                csv += [names, m.addr, m.isOutgoing ? "out" : "in", m.sim.map { String($0 + 1) } ?? "",
                        stamp.string(from: m.time), m.body].map(csvField).joined(separator: ",") + "\n"
            }
            text += "\n"
        }
        let base = "Pairline messages \(day.string(from: Date()))"
        let txt = dir.appendingPathComponent(base + ".txt")
        let table = dir.appendingPathComponent(base + ".csv")
        try Data(text.utf8).write(to: txt, options: [.completeFileProtection])
        // A byte-order mark so spreadsheet apps read Persian and Arabic correctly.
        try Data(("\u{FEFF}" + csv).utf8).write(to: table, options: [.completeFileProtection])
        return [txt, table]
    }

    static func remove(_ files: [URL]) {
        for dir in Set(files.map { $0.deletingLastPathComponent() }) { try? FileManager.default.removeItem(at: dir) }
    }

    private static func csvField(_ value: String) -> String {
        guard value.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) else { return value }
        return "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }
}
