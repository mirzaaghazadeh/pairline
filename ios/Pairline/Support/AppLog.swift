import Foundation
import os

/// Removes personal data from log text (AGENTS.md rule 9): phone numbers, emails, MAC addresses, UUIDs and IP
/// addresses (remote mode, D-092: a home's internet address tells roughly where it is). Callers never pass message
/// text or contact names to the log in the first place; this is the second fence.
enum Redactor {
    private static let rules: [(NSRegularExpression, String)] = {
        let patterns: [(String, String)] = [
            (#"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"#, "[email]"),
            (#"\b[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\b"#, "[id]"),
            (#"\b(?:[0-9A-Fa-f]{2}[:\-]){5}[0-9A-Fa-f]{2}\b"#, "[mac]"),
            // IPv6 in brackets (with a port), written out in full, or compressed ("::"); then IPv4 with a port.
            (#"\[[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*(?:%\w+)?\](?::\d{1,5})?"#, "[ip]"),
            (#"(?<![\w:])(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}(?:%\w+)?(?![\w:])"#, "[ip]"),
            (#"(?<![\w:])(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,6})?::(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,6})?(?:%\w+)?(?![\w:])"#, "[ip]"),
            (#"(?<![\d.])\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?(?![\d.])"#, "[ip]"),
            // Five or more digits, optionally with a leading + and separators: phone numbers and codes. Spaces and
            // tabs only, never a line break, so one line's number can't swallow the next line's time stamp.
            (#"\+?\d(?:[\d \t\-().]*\d){4,}"#, "[number]"),
        ]
        return patterns.compactMap { pattern, template in
            (try? NSRegularExpression(pattern: pattern)).map { ($0, template) }
        }
    }()

    /// A log line's leading time stamp ("2026-11-24T18:28:03Z ") isn't personal data: it's kept as it is.
    private static let timestamp = try? NSRegularExpression(pattern: #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z "#)

    /// Redacts a whole report line by line, keeping each line's time stamp.
    static func redactLines(_ text: String) -> String {
        text.split(separator: "\n", omittingEmptySubsequences: false).map { line -> String in
            let line = String(line)
            let range = NSRange(line.startIndex..., in: line)
            guard let match = timestamp?.firstMatch(in: line, range: range),
                  let end = Range(match.range, in: line)?.upperBound else { return redact(line) }
            return String(line[..<end]) + redact(String(line[end...]))
        }.joined(separator: "\n")
    }

    static func redact(_ text: String) -> String {
        var out = text
        for (regex, template) in rules {
            out = regex.stringByReplacingMatches(in: out, range: NSRange(out.startIndex..., in: out),
                                                 withTemplate: template)
        }
        return out
    }
}

/// Local, redacted, user-readable log (PRIVACY.md: "App logs", last 7 days). Never sent anywhere by the app;
/// the user can read it in Settings › Diagnostics and share it after seeing the exact text.
final class AppLog {
    static let shared = AppLog()

    enum Level: String {
        case info = "INFO"
        case warning = "WARN"
        case error = "ERROR"
    }

    static let retention: TimeInterval = 7 * 24 * 3600
    private static let maxBytes = 512 * 1024

    private let queue = DispatchQueue(label: "app.pairline.log")
    private let fileURL: URL?
    #if DEBUG
    private static let systemLog = Logger(subsystem: "app.pairline.ios", category: "app")
    #endif

    private let formatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    init(directory: URL? = AppPaths.logsDirectory) {
        fileURL = directory?.appendingPathComponent("pairline.log")
        queue.async { [weak self] in self?.prune() }
    }

    func info(_ category: String, _ message: String) { write(.info, category, message) }
    func warning(_ category: String, _ message: String) { write(.warning, category, message) }
    func error(_ category: String, _ message: String) { write(.error, category, message) }

    private func write(_ level: Level, _ category: String, _ message: String) {
        let line = "\(formatter.string(from: Date())) \(level.rawValue) [\(category)] \(Redactor.redact(message))\n"
        #if DEBUG
        print(line, terminator: "")
        // Debug builds also write the (already redacted) line to the device's system log, so a tester's iPhone can be
        // followed from a connected Mac (`idevicesyslog -p Pairline`). Release builds never do.
        Self.systemLog.log("\(line, privacy: .public)")
        #endif
        queue.async { [fileURL] in
            guard let fileURL, let data = line.data(using: .utf8) else { return }
            if let handle = try? FileHandle(forWritingTo: fileURL) {
                handle.seekToEndOfFile()
                handle.write(data)
                try? handle.close()
            } else {
                try? data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            }
        }
    }

    /// The exact text the user sees before sharing.
    func text() -> String {
        queue.sync {
            guard let fileURL, let data = try? Data(contentsOf: fileURL) else { return "" }
            return String(decoding: data, as: UTF8.self)
        }
    }

    func clear() {
        queue.sync {
            guard let fileURL else { return }
            try? FileManager.default.removeItem(at: fileURL)
        }
    }

    /// Drops lines older than the retention period and caps the file size.
    private func prune() {
        guard let fileURL, let data = try? Data(contentsOf: fileURL) else { return }
        let cutoff = Date().addingTimeInterval(-AppLog.retention)
        var lines = String(decoding: data, as: UTF8.self).split(separator: "\n", omittingEmptySubsequences: true)
        lines.removeAll { line in
            guard let stamp = line.split(separator: " ").first, let date = formatter.date(from: String(stamp)) else {
                return true
            }
            return date < cutoff
        }
        var text = lines.joined(separator: "\n")
        if !text.isEmpty { text += "\n" }
        if text.utf8.count > AppLog.maxBytes {
            text = String(text.suffix(AppLog.maxBytes / 2))
        }
        try? Data(text.utf8).write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}

let log = AppLog.shared
