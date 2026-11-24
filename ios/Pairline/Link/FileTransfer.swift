import Foundation

/// Files both ways over the Wi-Fi session (PROTOCOL.md, "File transfer", D-045): `file.*` control messages, data in
/// `BULK` records. One outgoing file at a time; up to 4 incoming. Received files land in `Documents/Received`, which
/// the Files app shows under On My iPhone › Pairline.
@MainActor
final class FileTransferManager: ObservableObject {
    static let maxSize: Int64 = 1 << 30
    static let chunkSize = 49_152
    static let maxIncoming = 4
    /// Bulk records handed to the connection but not yet taken by it.
    private static let window = 4
    private static let dataRecord: UInt8 = 0x01

    struct Transfer: Identifiable, Equatable {
        enum Direction: Equatable {
            case outgoing
            case incoming
        }

        enum State: Equatable {
            case queued
            case offered
            case active
            /// Outgoing: every byte sent, waiting for `file.done`.
            case finishing
            case done
            case failed(String?)
            case cancelled
            case declined(String?)
        }

        let id = UUID()
        var fid: UInt32?
        let direction: Direction
        let name: String
        let size: Int64
        var transferred: Int64 = 0
        var state: State
        /// Outgoing: the source. Incoming: the saved file, once done.
        var fileURL: URL?

        var isFinished: Bool {
            switch state {
            case .done, .failed, .cancelled, .declined: return true
            default: return false
            }
        }

        var progress: Double { size > 0 ? min(1, Double(transferred) / Double(size)) : (state == .done ? 1 : 0) }
    }

    private struct Outgoing {
        let id: UUID
        let fid: UInt32
        var handle: FileHandle?
        var sent: Int64 = 0
        var acknowledged: Int64 = 0
        var inFlight = 0
        let size: Int64
    }

    private struct Incoming {
        let id: UUID
        let handle: FileHandle
        let tempURL: URL
        let name: String
        let size: Int64
        var received: Int64 = 0
        var reported: Int64 = 0
    }

    @Published private(set) var transfers: [Transfer] = []
    /// A Wi-Fi session is up and the bridge lists `files`.
    @Published private(set) var canSend = false

    /// Set by the link: sends a control message on the Wi-Fi session. Returns false when there's none.
    var sendControl: ((Outbound) -> Bool)?
    /// Set by the link: sends one bulk record on the Wi-Fi session; the closure runs when the connection took it.
    var sendBulk: ((Data, @escaping () -> Void) -> Bool)?
    /// Called when a file was received (for a notification).
    var onReceived: ((Transfer) -> Void)?

    private var nextFID = UInt32.random(in: 1...0xffff)
    private var outgoing: Outgoing?
    private var incoming: [UInt32: Incoming] = [:]
    private var scoped: [UUID: URL] = [:]
    private var outboxFiles: [UUID: URL] = [:]
    private var wifiUp = false
    private var bridgeHasFiles = false
    private let fileManager = FileManager.default

    // MARK: - Link state

    func setAvailability(wifiUp: Bool, bridgeHasFiles: Bool) {
        let wasUp = self.wifiUp
        self.wifiUp = wifiUp
        self.bridgeHasFiles = bridgeHasFiles
        canSend = wifiUp && bridgeHasFiles
        if wasUp && !wifiUp { sessionClosed() }
        pump()
    }

    /// The Wi-Fi session closed: what was under way fails (no resume in v0); queued files wait for the next one.
    private func sessionClosed() {
        if let current = outgoing {
            try? current.handle?.close()
            outgoing = nil
            update(current.id) { $0.state = .failed(String(localized: "The Wi-Fi connection dropped.")) }
            finishSource(current.id)
        }
        for (_, item) in incoming {
            try? item.handle.close()
            try? fileManager.removeItem(at: item.tempURL)
            update(item.id) { $0.state = .failed(String(localized: "The Wi-Fi connection dropped.")) }
        }
        incoming.removeAll()
    }

    // MARK: - Sending

    /// Queues a file for the bridge phone. `url` may be security-scoped (from the document picker).
    func send(url: URL, fromOutbox: Bool = false) {
        let scopedAccess = url.startAccessingSecurityScopedResource()
        let size: Int64? = {
            if let value = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize { return Int64(value) }
            if let value = (try? fileManager.attributesOfItem(atPath: url.path))?[.size] as? NSNumber {
                return value.int64Value
            }
            return nil
        }()
        var transfer = Transfer(direction: .outgoing, name: FileTransferManager.safeName(url.lastPathComponent),
                                size: size ?? 0, state: .queued, fileURL: url)
        if scopedAccess { scoped[transfer.id] = url }
        if fromOutbox { outboxFiles[transfer.id] = url }
        if size == nil {
            transfer.state = .failed(String(localized: "Couldn't read this file."))
        } else if let size, size > Self.maxSize {
            transfer.state = .failed(String(localized: "Files up to 1 GB can be sent."))
        }
        transfers.insert(transfer, at: 0)
        if transfer.isFinished { finishSource(transfer.id) }
        pump()
    }

    func cancel(_ id: UUID) {
        guard let transfer = transfers.first(where: { $0.id == id }), !transfer.isFinished else { return }
        switch transfer.direction {
        case .outgoing:
            if let current = outgoing, current.id == id {
                _ = sendControl?(.fileCancel(fid: current.fid, reason: "cancelled"))
                try? current.handle?.close()
                outgoing = nil
            }
            update(id) { $0.state = .cancelled }
            finishSource(id)
            pump()
        case .incoming:
            if let fid = transfer.fid, let item = incoming.removeValue(forKey: fid) {
                try? item.handle.close()
                try? fileManager.removeItem(at: item.tempURL)
                _ = sendControl?(.fileDone(fid: fid, ok: false, reason: "cancelled"))
            }
            update(id) { $0.state = .cancelled }
        }
    }

    func clearFinished() {
        transfers.removeAll { $0.isFinished }
    }

    private func pump() {
        guard outgoing == nil, canSend,
              let index = transfers.lastIndex(where: { $0.direction == .outgoing && $0.state == .queued }) else { return }
        let transfer = transfers[index]
        let fid = nextFID
        nextFID = nextFID == UInt32.max ? 1 : nextFID + 1
        guard sendControl?(.fileOffer(fid: fid, name: transfer.name, size: transfer.size,
                                      type: FileTransferManager.mimeType(transfer.name))) == true else { return }
        transfers[index].fid = fid
        transfers[index].state = .offered
        outgoing = Outgoing(id: transfer.id, fid: fid, size: transfer.size)
        log.info("files", "Offered a file (\(transfer.size) bytes)")
    }

    private func answered(_ message: FileAnswerMessage) {
        guard var current = outgoing, current.fid == message.fid,
              transfers.first(where: { $0.id == current.id })?.state == .offered else { return }
        guard message.accept else {
            outgoing = nil
            update(current.id) { $0.state = .declined(FileTransferManager.reasonText(message.reason)) }
            finishSource(current.id)
            return pump()
        }
        guard let url = transfers.first(where: { $0.id == current.id })?.fileURL,
              let handle = try? FileHandle(forReadingFrom: url) else {
            _ = sendControl?(.fileCancel(fid: current.fid, reason: "failed"))
            outgoing = nil
            update(current.id) { $0.state = .failed(String(localized: "Couldn't read this file.")) }
            finishSource(current.id)
            return pump()
        }
        current.handle = handle
        outgoing = current
        update(current.id) { $0.state = .active }
        fill()
    }

    /// Keeps up to `window` records in the connection; the next is read when one is taken.
    private func fill() {
        while var current = outgoing, current.inFlight < Self.window, current.sent < current.size {
            let want = Int(min(Int64(Self.chunkSize), current.size - current.sent))
            guard let data = try? current.handle?.read(upToCount: want), !data.isEmpty else {
                _ = sendControl?(.fileCancel(fid: current.fid, reason: "failed"))
                try? current.handle?.close()
                outgoing = nil
                update(current.id) { $0.state = .failed(String(localized: "The file changed while it was being sent.")) }
                finishSource(current.id)
                return pump()
            }
            var record = Data([Self.dataRecord])
            record.append(Data.bigEndian(current.fid))
            record.append(Data.bigEndian(UInt64(current.sent)))
            record.append(data)
            let fid = current.fid
            let count = Int64(data.count)
            let accepted = sendBulk?(record) { [weak self] in self?.chunkTaken(fid: fid, bytes: count) } ?? false
            guard accepted else { return } // the session is going away; sessionClosed() cleans up
            current.sent += count
            current.inFlight += 1
            outgoing = current
        }
        if let current = outgoing, current.sent >= current.size, current.inFlight == 0 {
            try? current.handle?.close()
            outgoing?.handle = nil
            update(current.id) {
                $0.transferred = current.size
                if $0.state == .active { $0.state = .finishing }
            }
        }
    }

    private func chunkTaken(fid: UInt32, bytes: Int64) {
        guard var current = outgoing, current.fid == fid else { return }
        current.inFlight -= 1
        current.acknowledged += bytes
        outgoing = current
        let acknowledged = current.acknowledged
        update(current.id) { $0.transferred = acknowledged }
        fill()
    }

    private func peerDone(_ message: FileDoneMessage) {
        guard let current = outgoing, current.fid == message.fid else { return }
        try? current.handle?.close()
        outgoing = nil
        update(current.id) {
            if message.ok {
                $0.state = .done
                $0.transferred = $0.size
            } else if message.reason == "cancelled" {
                $0.state = .cancelled
            } else {
                $0.state = .failed(FileTransferManager.reasonText(message.reason))
            }
        }
        if message.ok { log.info("files", "The bridge received a file") }
        finishSource(current.id)
        pump()
    }

    /// Stops security-scoped access and removes a share-extension file once it's sent (or given up on).
    private func finishSource(_ id: UUID) {
        scoped.removeValue(forKey: id)?.stopAccessingSecurityScopedResource()
        if let url = outboxFiles.removeValue(forKey: id) { try? fileManager.removeItem(at: url) }
    }

    // MARK: - Receiving

    func handle(_ message: InboundMessage) {
        switch message {
        case .fileOffer(let offer): offered(offer)
        case .fileAnswer(let answer): answered(answer)
        case .fileDone(let done): peerDone(done)
        case .fileCancel(let cancel): peerCancelled(cancel)
        default: break
        }
    }

    private func offered(_ offer: FileOfferMessage) {
        let name = FileTransferManager.safeName(offer.name)
        func decline(_ reason: String, _ text: String) {
            _ = sendControl?(.fileAnswer(fid: offer.fid, accept: false, reason: reason))
            var transfer = Transfer(direction: .incoming, name: name, size: max(0, offer.size), state: .declined(text))
            transfer.fid = offer.fid
            transfers.insert(transfer, at: 0)
        }
        guard incoming[offer.fid] == nil else { return }
        guard offer.size >= 0, offer.size <= Self.maxSize else {
            return decline("too_large", String(localized: "Files up to 1 GB can be received."))
        }
        guard incoming.count < Self.maxIncoming else {
            return decline("busy", String(localized: "Too many files at once. Send it again in a moment."))
        }
        guard FileTransferManager.freeSpace() > offer.size + 50_000_000 else {
            return decline("no_space", String(localized: "There isn't enough free space on this iPhone."))
        }
        let directory = fileManager.temporaryDirectory.appendingPathComponent("Incoming", isDirectory: true)
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let temp = directory.appendingPathComponent(UUID().uuidString)
        guard fileManager.createFile(atPath: temp.path, contents: nil),
              let handle = try? FileHandle(forWritingTo: temp) else {
            return decline("failed", String(localized: "Couldn't save the file."))
        }
        var transfer = Transfer(direction: .incoming, name: name, size: offer.size, state: .active)
        transfer.fid = offer.fid
        transfers.insert(transfer, at: 0)
        incoming[offer.fid] = Incoming(id: transfer.id, handle: handle, tempURL: temp, name: name, size: offer.size)
        _ = sendControl?(.fileAnswer(fid: offer.fid, accept: true, reason: nil))
        log.info("files", "Receiving a file (\(offer.size) bytes)")
        if offer.size == 0 { complete(offer.fid) }
    }

    /// One `BULK` record from the Wi-Fi session.
    func handleBulk(_ plaintext: Data) {
        guard plaintext.first == Self.dataRecord, plaintext.count > 13,
              let fid = plaintext.readBigEndian(UInt32.self, at: 1),
              let offset = plaintext.readBigEndian(UInt64.self, at: 5) else { return } // unknown kinds are ignored
        guard var item = incoming[fid] else { return } // finished or cancelled: late data is ignored
        let data = plaintext.dropFirst(13)
        guard offset == UInt64(item.received), item.received + Int64(data.count) <= item.size else {
            return fail(fid, reason: "failed")
        }
        do {
            try item.handle.write(contentsOf: data)
        } catch {
            return fail(fid, reason: "no_space")
        }
        item.received += Int64(data.count)
        let shouldReport = item.received - item.reported >= 262_144 || item.received == item.size
        if shouldReport { item.reported = item.received }
        incoming[fid] = item
        if shouldReport {
            let received = item.received
            update(item.id) { $0.transferred = received }
        }
        if item.received == item.size { complete(fid) }
    }

    private func complete(_ fid: UInt32) {
        guard let item = incoming.removeValue(forKey: fid) else { return }
        try? item.handle.close()
        do {
            let folder = try FileTransferManager.receivedFolder()
            let destination = FileTransferManager.freeURL(in: folder, name: item.name)
            try fileManager.moveItem(at: item.tempURL, to: destination)
            _ = sendControl?(.fileDone(fid: fid, ok: true, reason: nil))
            update(item.id) {
                $0.state = .done
                $0.transferred = item.size
                $0.fileURL = destination
            }
            log.info("files", "Received a file")
            if let transfer = transfers.first(where: { $0.id == item.id }) { onReceived?(transfer) }
        } catch {
            try? fileManager.removeItem(at: item.tempURL)
            _ = sendControl?(.fileDone(fid: fid, ok: false, reason: "failed"))
            update(item.id) { $0.state = .failed(String(localized: "Couldn't save the file.")) }
        }
    }

    private func fail(_ fid: UInt32, reason: String) {
        guard let item = incoming.removeValue(forKey: fid) else { return }
        try? item.handle.close()
        try? fileManager.removeItem(at: item.tempURL)
        _ = sendControl?(.fileDone(fid: fid, ok: false, reason: reason))
        update(item.id) { $0.state = .failed(FileTransferManager.reasonText(reason)) }
    }

    private func peerCancelled(_ message: FileCancelMessage) {
        guard let item = incoming.removeValue(forKey: message.fid) else { return }
        try? item.handle.close()
        try? fileManager.removeItem(at: item.tempURL)
        update(item.id) { $0.state = .cancelled }
    }

    // MARK: - Share extension outbox

    /// Queues files the Share extension left in the app group's `Outbox` folder (each in its own subfolder).
    func collectOutbox() {
        guard let outbox = SharedOutbox.directory,
              let folders = try? fileManager.contentsOfDirectory(at: outbox, includingPropertiesForKeys: nil) else { return }
        let queued = Set(outboxFiles.values.map(\.standardizedFileURL))
        for folder in folders {
            guard let files = try? fileManager.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) else {
                continue
            }
            if files.isEmpty {
                try? fileManager.removeItem(at: folder)
                continue
            }
            for file in files where !queued.contains(file.standardizedFileURL) {
                send(url: file, fromOutbox: true)
            }
        }
    }

    // MARK: - Helpers

    private func update(_ id: UUID, _ change: (inout Transfer) -> Void) {
        guard let index = transfers.firstIndex(where: { $0.id == id }) else { return }
        change(&transfers[index])
    }

    /// The base name only: no path separators or control characters, at most 255 UTF-8 bytes, never hidden.
    nonisolated static func safeName(_ raw: String) -> String {
        var name = String(raw.unicodeScalars.filter {
            !CharacterSet.controlCharacters.contains($0) && $0 != "/" && $0 != "\\" && $0 != ":"
        })
        name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        while name.hasPrefix(".") { name.removeFirst() }
        while name.utf8.count > 255 { name.removeLast() }
        return name.isEmpty ? "file" : name
    }

    static func freeURL(in folder: URL, name: String) -> URL {
        let url = folder.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: url.path) else { return url }
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        for n in 2...9_999 {
            let candidate = folder.appendingPathComponent(ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)")
            if !FileManager.default.fileExists(atPath: candidate.path) { return candidate }
        }
        return folder.appendingPathComponent(UUID().uuidString + (ext.isEmpty ? "" : ".\(ext)"))
    }

    static func receivedFolder() throws -> URL {
        let documents = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil,
                                                    create: true)
        let folder = documents.appendingPathComponent("Received", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    static func freeSpace() -> Int64 {
        let url = FileManager.default.temporaryDirectory
        if let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
           let capacity = values.volumeAvailableCapacityForImportantUsage {
            return capacity
        }
        return Int64.max
    }

    static func mimeType(_ name: String) -> String? {
        switch (name as NSString).pathExtension.lowercased() {
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "heic": return "image/heic"
        case "gif": return "image/gif"
        case "mp4": return "video/mp4"
        case "mov": return "video/quicktime"
        case "pdf": return "application/pdf"
        case "txt": return "text/plain"
        case "zip": return "application/zip"
        case "mp3": return "audio/mpeg"
        case "m4a": return "audio/mp4"
        case "vcf": return "text/vcard"
        case "apk": return "application/vnd.android.package-archive"
        default: return nil
        }
    }

    static func reasonText(_ reason: String?) -> String? {
        switch reason {
        case "too_large"?: return String(localized: "The file is too large for the other phone.")
        case "no_space"?: return String(localized: "The other phone doesn't have enough free space.")
        case "busy"?: return String(localized: "The other phone is busy. Try again in a moment.")
        case "declined"?: return String(localized: "The other phone declined the file.")
        case "cancelled"?: return String(localized: "Cancelled on the other phone.")
        default: return String(localized: "The transfer failed.")
        }
    }
}

/// The app group folder the Share extension leaves files in.
enum SharedOutbox {
    static let appGroup = "group.app.pairline.ios"
    /// Posted (Darwin notification) by the Share extension after it queued files.
    static let darwinNotification = "app.pairline.ios.outbox"

    static var directory: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent("Outbox", isDirectory: true)
    }
}
