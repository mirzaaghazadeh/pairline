// Checks the relay's QR encoder against Apple's QR detector (Core Image), on a Mac. Not part of the relay.
//
//   QR_PNG_DIR=/tmp/qr go test -run TestQRDecodes .   (in relay/)
//   swift tools/qr-check.swift /tmp/qr
//
// Each qr-N.png must decode to exactly the text in qr-N.txt.
import CoreImage
import Foundation

let dir = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : ".")
let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])!
var failures = 0
var checked = 0
for i in 0..<100 {
    let png = dir.appendingPathComponent("qr-\(i).png")
    let txt = dir.appendingPathComponent("qr-\(i).txt")
    guard FileManager.default.fileExists(atPath: png.path), let expected = try? String(contentsOf: txt, encoding: .utf8),
          let image = CIImage(contentsOf: png) else { continue }
    checked += 1
    let decoded = (detector.features(in: image).first as? CIQRCodeFeature)?.messageString
    if decoded == expected {
        print("qr-\(i): ok (\(expected.utf8.count) bytes)")
    } else {
        failures += 1
        print("qr-\(i): FAILED, decoded \(decoded.map { "\"\($0.prefix(60))…\"" } ?? "nothing")")
    }
}
print("\(checked) checked, \(failures) failed")
exit(failures == 0 && checked > 0 ? 0 : 1)
