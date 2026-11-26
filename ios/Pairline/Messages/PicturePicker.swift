import PhotosUI
import SwiftUI

/// The composer's picture button (D-089): a photo from the library, made small enough for carriers' MMS limits (at most
/// 1280 px and about 280 KB as JPEG) before it's attached. Nothing is read from the library but the photo chosen.
struct PicturePicker: View {
    @Binding var picture: Data?
    @State private var item: PhotosPickerItem?

    var body: some View {
        PhotosPicker(selection: $item, matching: .images, photoLibrary: .shared()) {
            Image(systemName: "photo")
                .font(.system(size: 20))
                .foregroundStyle(Color.brandBlue)
                .padding(.vertical, 8)
        }
        .accessibilityLabel(Text("Add a picture", tableName: "MMS"))
        .onChange(of: item) { newItem in
            guard let newItem else { return }
            Task {
                let data = try? await newItem.loadTransferable(type: Data.self)
                picture = data.flatMap(Self.forMMS)
                item = nil
            }
        }
    }

    /// Scales and compresses a picture until it fits an MMS.
    static func forMMS(_ data: Data) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        var side: CGFloat = 1280
        while side >= 320 {
            let scale = min(1, side / max(image.size.width, image.size.height, 1))
            let size = CGSize(width: max(1, (image.size.width * scale).rounded()),
                              height: max(1, (image.size.height * scale).rounded()))
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            let scaled = UIGraphicsImageRenderer(size: size, format: format).image { _ in
                image.draw(in: CGRect(origin: .zero, size: size))
            }
            for quality in [0.7, 0.55, 0.4] {
                if let jpeg = scaled.jpegData(compressionQuality: quality), jpeg.count <= maxBytes { return jpeg }
            }
            side *= 0.75
        }
        return nil
    }

    static let maxBytes = 280_000
}
