# Pairline — iOS app

The iPhone side ("remote") of Pairline: messages, calls, contacts, call history, USSD and bridge status from the
SIM in your Android phone, over the encrypted link in `../docs/PROTOCOL.md`.

- **Project:** XcodeGen `project.yml`. The generated `.xcodeproj` isn't committed; run `xcodegen generate`.
- **Versions:** bundle ID `app.pairline.ios` (provisional, D-007), iOS 16+, Swift 6.4 toolchain in Swift 5 mode
  (D-024), SwiftUI. Apple frameworks only; no third-party code.
- **Targets:** `Pairline` (app), `PairlineWidget` (WidgetKit: bridge battery, signal, link state), `PairlineTests`.
- **Capabilities:** Background Modes `bluetooth-central` (`audio` from M6), In-App Purchase, App Group
  `group.app.pairline.ios` (app + widget). Hotspot Configuration comes with M5.
- **Languages:** English, Persian, Arabic (`Pairline/Resources/*.xcstrings`, D-019, D-028).

## Layout

| Folder | What's in it |
|--------|--------------|
| `Pairline/Protocol` | Frames, hello, records, pairing, BLE fragmenting, TCP framing, JSON messages |
| `Pairline/Identity` | Secure Enclave / Keychain identity key, pinned bridge |
| `Pairline/Link` | Session state machine, link manager (requests, reconnection), CoreBluetooth central, debug TCP |
| `Pairline/Bridge` | The `BridgeLink` interface, sync engine and status dashboard |
| `Pairline/Data` | SQLite store |
| `Pairline/Messages`, `Calls`, `Contacts`, `Settings`, `Onboarding`, `Pairing` | Screens and their logic |
| `Pairline/Store` | StoreKit 2 trial and purchase (D-017), paywall, `Pairline.storekit` for local testing |
| `Pairline/Demo` | Demo bridge with localized sample data (D-020) |
| `PairlineWidget`, `Shared` | The widget and the status snapshot it shares with the app |
| `PairlineTests` | Protocol vectors, session/link against an in-test bridge, codecs, data, OTP, trial, StoreKit |
| `tools/make_icon.swift` | Draws the app icon |

## Build, test, run

See "Commands" in `../AGENTS.md`. In short:

```sh
cd ios && xcodegen generate
xcodebuild -scheme Pairline -destination 'platform=iOS Simulator,name=iPhone 18 Pro' test
```

Open `Pairline.xcodeproj` in Xcode and set your own team to run on a device. Running from Xcode uses
`Pairline/Store/Pairline.storekit`, so the trial and the purchase work locally.

**Without an Android phone:** start `../protocol/tools/fake_bridge.py` and pair the Simulator's debug build with the
link it prints (debug builds only; release builds pair only by scanning the QR code), or explore demo mode.

## Needs a real iPhone

Bluetooth, CallKit's call screen, background wake-ups, the Secure Enclave and the camera scanner don't work in the
Simulator. Test them with a real iPhone and an Android phone running Pairline Bridge.
