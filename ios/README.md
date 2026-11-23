# Pairline — iOS app

Created in milestone M0 (see `../docs/ROADMAP.md`). Planned setup:

- **Project:** XcodeGen `project.yml`. The generated `.xcodeproj` isn't committed; run `xcodegen generate`.
- **Versions:** bundle ID `app.pairline.ios` (provisional, D-007), iOS 16, Swift 6, SwiftUI
- **Groups:** `Link/`, `Calls/` (CallKit), `Messages/`, `Store/` (StoreKit 2), `Protocol/`
  (see `../docs/ARCHITECTURE.md`)
- **Extra target (M5):** Share extension, "Send to bridge phone"
- **Capabilities:**
  - Background Modes: `bluetooth-central`; `audio` from M6
  - In-App Purchase
  - Hotspot Configuration (M5)
- **Info.plist usage strings:**
  - `NSBluetoothAlwaysUsageDescription`
  - `NSCameraUsageDescription` (QR pairing)
  - `NSLocalNetworkUsageDescription` (M5)
  - `NSMicrophoneUsageDescription` (M6)
  - `NSContactsUsageDescription` (optional feature)
- **Tests:** unit tests read the shared vectors in `../protocol/test-vectors/`
