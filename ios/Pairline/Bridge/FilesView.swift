import SwiftUI
import UniformTypeIdentifiers

/// Files both ways with the bridge phone over Wi-Fi (D-045): pick files to send, follow transfers, share what arrived.
struct FilesView: View {
    @ObservedObject var files: FileTransferManager
    @State private var importing = false

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: files.canSend ? "wifi" : "wifi.slash")
                        .font(.title2)
                        .foregroundStyle(files.canSend ? Color.brandBlue : .secondary)
                        .frame(width: 30)
                    Text(files.canSend
                         ? "Send photos, videos and documents to your bridge phone. Files it sends you are saved in the Files app under Pairline › Received."
                         : "Connect both phones to the same Wi-Fi network to send files. Files go only between your two phones, encrypted.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }
                .card()
                Button("Choose files") { importing = true }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(!files.canSend)
                if !files.transfers.isEmpty {
                    VStack(spacing: 10) {
                        SectionTitle(Text("Transfers"))
                        VStack(spacing: 0) {
                            ForEach(files.transfers) { transfer in
                                TransferRow(transfer: transfer) { files.cancel(transfer.id) }
                                if transfer.id != files.transfers.last?.id {
                                    Divider().padding(.leading, 40)
                                }
                            }
                        }
                        .card(padding: 12)
                    }
                }
            }
            .padding(16)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(Text("Send files"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if files.transfers.contains(where: \.isFinished) {
                Button("Clear") { files.clearFinished() }
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { urls.forEach { files.send(url: $0) } }
        }
    }
}

private struct TransferRow: View {
    let transfer: FileTransferManager.Transfer
    let onCancel: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: transfer.direction == .outgoing ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                .font(.title3)
                .foregroundStyle(tint)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: transfer.name)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                    .truncationMode(.middle)
                if transfer.state == .active || transfer.state == .finishing {
                    ProgressView(value: transfer.progress)
                }
                statusText
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            if !transfer.isFinished {
                Button(action: onCancel) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Cancel"))
            } else if transfer.state == .done, transfer.direction == .incoming, let url = transfer.fileURL {
                ShareLink(item: url) {
                    Image(systemName: "square.and.arrow.up")
                        .font(.title3)
                }
                .accessibilityLabel(Text("Share"))
            }
        }
        .padding(.vertical, 8)
    }

    private var tint: Color {
        switch transfer.state {
        case .done: return .brandGreen
        case .failed, .declined: return .brandRed
        case .cancelled: return .secondary
        default: return .brandBlue
        }
    }

    private var size: String { ByteCountFormatter.string(fromByteCount: transfer.size, countStyle: .file) }

    private var statusText: Text {
        switch transfer.state {
        case .queued, .offered:
            return Text("Waiting · \(size)")
        case .active, .finishing:
            let done = ByteCountFormatter.string(fromByteCount: transfer.transferred, countStyle: .file)
            return Text(verbatim: "\(done) / \(size)")
        case .done:
            return transfer.direction == .outgoing ? Text("Sent · \(size)") : Text("Saved · \(size)")
        case .failed(let reason):
            return reason.map { Text(verbatim: $0) } ?? Text("The transfer failed.")
        case .declined(let reason):
            return reason.map { Text(verbatim: $0) } ?? Text("The other phone declined the file.")
        case .cancelled:
            return Text("Cancelled")
        }
    }
}
