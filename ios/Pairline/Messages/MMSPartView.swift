import QuickLook
import SwiftUI

/// One attachment of a picture message in the conversation (D-089): the thumbnail the bridge sent, and on a tap the
/// full file through the file transfer (Wi-Fi), opened in Quick Look. Other attachments show as a labelled chip.
struct MMSPartView: View {
    let message: StoredMessage
    let part: MMSPart
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @ObservedObject private var files = AppModel.shared.link.files
    @State private var waiting = false
    @State private var known = Set<UUID>()
    @State private var preview: URL?

    private var thumbnail: UIImage? {
        part.thumb.flatMap { Data(base64Encoded: $0) }.flatMap(UIImage.init(data:))
    }

    var body: some View {
        Button(action: open) {
            Group {
                if part.isImage, let thumbnail {
                    Image(uiImage: thumbnail)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 200, height: 200 * min(1.4, thumbnail.size.height / max(thumbnail.size.width, 1)))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                } else {
                    Label {
                        Text(verbatim: part.name ?? part.type)
                    } icon: {
                        Image(systemName: part.isImage ? "photo" : "paperclip")
                    }
                    .font(.subheadline)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Capsule().fill(Color.secondary.opacity(0.15)))
                }
            }
            .overlay {
                if waiting { ProgressView().padding(10).background(Circle().fill(.regularMaterial)) }
            }
        }
        .buttonStyle(.plain)
        .disabled(message.isLocalMMS)
        .accessibilityLabel(part.isImage ? Text("Picture", tableName: "MMS") : Text(verbatim: part.name ?? part.type))
        .quickLookPreview($preview)
        .onReceive(files.$transfers) { transfers in
            guard waiting else { return }
            let new = transfers.filter { $0.direction == .incoming && !known.contains($0.id) }
            if let done = new.last(where: { $0.state == .done }), let url = done.fileURL {
                waiting = false
                preview = url
            } else if new.contains(where: { $0.isFinished && $0.state != .done }) {
                waiting = false
            }
        }
    }

    private func open() {
        guard !waiting else { return }
        guard bridge.phase.isConnected else {
            app.toast = String(localized: "Connect to the bridge phone over Wi-Fi to open attachments.", table: "MMS")
            return
        }
        known = Set(files.transfers.filter { $0.direction == .incoming }.map(\.id))
        waiting = true
        Task {
            do {
                try await bridge.fetchPart(of: message, part)
            } catch LinkError.notConnected {
                waiting = false
                app.toast = String(localized: "Connect to the bridge phone over Wi-Fi to open attachments.", table: "MMS")
            } catch {
                waiting = false
                app.toast = error.localizedDescription
            }
        }
    }
}
