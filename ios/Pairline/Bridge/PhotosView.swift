import QuickLook
import SwiftUI

/// The Android phone's photos (D-088): thumbnails page by page over the Wi-Fi link, and a tap copies the original to
/// this iPhone with the normal file transfer (Files › Pairline › Received), then shows it. Thumbnails are kept in
/// memory only while this screen is open.
struct PhotosView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var bridge: BridgeController
    @ObservedObject var files: FileTransferManager
    @State private var items: [MediaPage.Item] = []
    @State private var thumbs: [Int64: UIImage] = [:]
    @State private var cursor: String?
    @State private var more = true
    @State private var loading = false
    @State private var problem: String?
    /// Waiting for the photo asked for: the next incoming file that wasn't there before is shown.
    @State private var waiting = false
    @State private var knownIncoming = Set<UUID>()
    @State private var preview: URL?

    private let columns = [GridItem(.adaptive(minimum: 96), spacing: 2)]

    var body: some View {
        ScrollView {
            if let problem {
                Text(verbatim: problem)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(24)
            }
            LazyVGrid(columns: columns, spacing: 2) {
                ForEach(items) { item in
                    Button {
                        fetch(item)
                    } label: {
                        thumbnail(item)
                    }
                    .buttonStyle(.plain)
                    .onAppear { if item.id == items.last?.id { Task { await loadMore() } } }
                }
            }
            if loading { ProgressView().padding(24) }
        }
        .navigationTitle(Text("Photos on the Android phone", tableName: "Photos"))
        .navigationBarTitleDisplayMode(.inline)
        .overlay(alignment: .bottom) {
            if waiting {
                Label {
                    Text("Copying the photo over Wi-Fi…", tableName: "Photos")
                } icon: {
                    ProgressView()
                }
                .font(.subheadline.weight(.semibold))
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Capsule().fill(.regularMaterial))
                .padding(.bottom, 20)
            }
        }
        .quickLookPreview($preview)
        .task { if items.isEmpty { await loadMore() } }
        .onReceive(files.$transfers) { transfers in
            guard waiting else { return }
            let new = transfers.filter { $0.direction == .incoming && !knownIncoming.contains($0.id) }
            if let done = new.last(where: { $0.state == .done }), let url = done.fileURL {
                waiting = false
                preview = url
            } else if let failed = new.last(where: \.isFinished), failed.state != .done {
                waiting = false
                app.toast = String(localized: "The photo didn't arrive. Try again.", table: "Photos")
            }
        }
    }

    @ViewBuilder private func thumbnail(_ item: MediaPage.Item) -> some View {
        Color.secondary.opacity(0.15)
            .aspectRatio(1, contentMode: .fill)
            .overlay {
                if let image = thumbs[item.mid] {
                    Image(uiImage: image).resizable().scaledToFill()
                }
            }
            .clipped()
            .contentShape(Rectangle())
    }

    private func loadMore() async {
        guard more, !loading else { return }
        guard bridge.has(.mediaPhotos) else {
            problem = CapabilityInfo(.mediaPhotos).missingText
            more = false
            return
        }
        loading = true
        defer { loading = false }
        do {
            let reply = try await bridge.link.request(.mediaList(cursor: cursor, limit: 30))
            guard case .mediaList(let page) = reply.message else { return }
            problem = nil
            items += page.items
            cursor = page.cursor
            more = page.more && page.cursor != nil
            for item in page.items {
                if let b64 = item.thumb, let data = Data(base64Encoded: b64), let image = UIImage(data: data) {
                    thumbs[item.mid] = image
                }
            }
        } catch LinkError.notConnected {
            problem = String(localized: "Photos come over the Wi-Fi link. Put both phones on the same Wi-Fi, or join the Android phone's hotspot.", table: "Photos")
        } catch {
            problem = error.localizedDescription
        }
    }

    private func fetch(_ item: MediaPage.Item) {
        guard !waiting else { return }
        knownIncoming = Set(files.transfers.filter { $0.direction == .incoming }.map(\.id))
        waiting = true
        Task {
            do {
                _ = try await bridge.link.request(.mediaGet(mid: item.mid))
            } catch {
                waiting = false
                app.toast = error.localizedDescription
            }
        }
    }
}
