import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// "Send to bridge phone" in the share sheet. The extension can't reach the bridge itself (the link, its keys and the
/// Wi-Fi session live in the app), so it copies the shared files into the app group's outbox and tells the app,
/// which sends them over the Wi-Fi link (`SharedOutbox`, PROTOCOL.md "File transfer"). Nothing leaves the iPhone here.
final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        let model = ShareModel(providers: providers) { [weak self] queued in
            if queued {
                self?.extensionContext?.completeRequest(returningItems: nil)
            } else {
                self?.extensionContext?.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain,
                                                                         code: NSUserCancelledError))
            }
        }
        let host = UIHostingController(rootView: ShareView(model: model)
            .environment(\.font, AppFont.usesEstedad ? .body : nil))
        addChild(host)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        host.didMove(toParent: self)
    }
}

@MainActor
final class ShareModel: ObservableObject {
    enum Phase: Equatable {
        case ready
        case copying
        case queued(Int)
        case failed
    }

    @Published private(set) var phase: Phase = .ready
    @Published private(set) var skipped = 0
    let count: Int
    private let providers: [NSItemProvider]
    private let finish: (Bool) -> Void

    init(providers: [NSItemProvider], finish: @escaping (Bool) -> Void) {
        self.providers = providers
        self.count = providers.count
        self.finish = finish
    }

    func cancel() { finish(false) }

    func done() { finish(true) }

    /// Copies every shared item into a new outbox folder, then wakes the app.
    func queue() {
        guard phase == .ready, let outbox = SharedOutbox.directory else {
            phase = .failed
            return
        }
        phase = .copying
        let folder = outbox.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let group = DispatchGroup()
        let tally = Tally()
        for provider in providers {
            guard let type = provider.registeredTypeIdentifiers.first(where: {
                UTType($0)?.conforms(to: .data) == true || UTType($0)?.conforms(to: .item) == true
            }) else {
                tally.add(ok: false)
                continue
            }
            group.enter()
            // The file at `url` exists only during this callback: copy it now.
            _ = provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                defer { group.leave() }
                tally.add(ok: url.map { ShareModel.copy($0, into: folder) } ?? false)
            }
        }
        group.notify(queue: .main) { [weak self] in
            guard let self else { return }
            let (copied, skipped) = tally.values
            self.skipped = skipped
            if copied > 0 {
                SharedOutbox.notifyApp()
                self.phase = .queued(copied)
            } else {
                try? FileManager.default.removeItem(at: folder)
                self.phase = .failed
            }
        }
    }

    nonisolated private static func copy(_ url: URL, into folder: URL) -> Bool {
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
        guard size <= SharedOutbox.maxFileSize else { return false }
        let name = url.lastPathComponent.isEmpty ? "file" : url.lastPathComponent
        var destination = folder.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: destination.path) {
            destination = folder.appendingPathComponent(UUID().uuidString + "-" + name)
        }
        return (try? FileManager.default.copyItem(at: url, to: destination)) != nil
    }

    private final class Tally: @unchecked Sendable {
        private let lock = NSLock()
        private var copied = 0
        private var skipped = 0

        func add(ok: Bool) {
            lock.lock()
            if ok { copied += 1 } else { skipped += 1 }
            lock.unlock()
        }

        var values: (Int, Int) {
            lock.lock()
            defer { lock.unlock() }
            return (copied, skipped)
        }
    }
}

struct ShareView: View {
    @ObservedObject var model: ShareModel

    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                Image(systemName: icon)
                    .font(.largeTitle)
                    .foregroundStyle(Color.accentColor)
                    .padding(.top, 24)
                Text(message)
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                if model.skipped > 0, case .queued = model.phase {
                    Text("Some items couldn't be added. Files up to 1 GB can be sent.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                if model.phase == .copying { ProgressView() }
                Spacer()
                switch model.phase {
                case .ready:
                    Button(action: model.queue) {
                        Text("Send").font(.headline).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                case .queued, .failed:
                    Button(action: model.done) {
                        Text("Done").font(.headline).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                case .copying:
                    EmptyView()
                }
            }
            .padding(20)
            .navigationTitle(Text("Send to bridge phone"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if model.phase == .ready { Button("Cancel", action: model.cancel) }
                }
            }
        }
    }

    private var icon: String {
        switch model.phase {
        case .queued: return "checkmark.circle.fill"
        case .failed: return "exclamationmark.triangle.fill"
        default: return "arrow.up.doc.fill"
        }
    }

    private var message: String {
        switch model.phase {
        case .ready, .copying:
            return String(localized: "Send to your bridge phone over Wi-Fi. Files go only between your two phones, encrypted.")
        case .queued:
            return String(localized: "Ready to go. Pairline sends it when it's connected to your bridge phone over Wi-Fi. Open Pairline if it isn't running.")
        case .failed:
            return String(localized: "This couldn't be added. Files up to 1 GB can be sent.")
        }
    }
}
