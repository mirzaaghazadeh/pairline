# Architecture

Pairline is two native apps that talk only to each other.

```
 Android phone (has the SIM)                          iPhone
 ┌───────────────────────────────────┐               ┌───────────────────────────────┐
 │ Pairline Bridge                   │  Bluetooth LE │ Pairline                      │
 │  telephony ── LinkService ── BLE ─┼───────────────┼─ CoreBluetooth ── CallKit     │
 │  (SMS, calls,  (sessions,         │               │                   Messages    │
 │   contacts)     crypto)  Wi-Fi ───┼───────────────┼─ Wi-Fi (M5)       StoreKit 2  │
 │  privileged (Shizuku, optional)   │  local Wi-Fi  │                               │
 └───────────────────────────────────┘               └───────────────────────────────┘
```

No server sits between them. See `PRIVACY.md` for every data flow and `PROTOCOL.md` for the wire format.

## Android bridge app (`android/`)

Start as one `:app` module with these packages; split into Gradle modules only when it grows.

| Package | Responsibility |
|---------|----------------|
| `ui` | Compose screens: setup wizard (permissions, battery optimization, OEM autostart tips, pairing QR), status |
| `link` | `LinkService` foreground service (type `connectedDevice`), BLE advertiser + GATT server, `LinkManager` (one session per transport, event/reply routing, `MediaChannel`), session state |
| `link.wifi` | The Wi-Fi link (M5): listeners on local addresses only, DNS-SD, the UDP media socket, the local-only hotspot. With `update` and `netshare`, the only code allowed to open sockets (`network-code-check.sh`) |
| `files` | File transfer over the Wi-Fi session (M5): queue, `BULK` data, Downloads/Pairline, notifications, the "Send to iPhone" share target |
| `protocol` | Message models, handshake, AEAD records. Pure Kotlin, unit-tested against `protocol/test-vectors/` |
| `telephony` | SMS, calls, contacts, call log, USSD, SIM info |
| `privileged` | Optional Shizuku-backed operations, behind an allowlist |
| `netshare` | Opt-in internet sharing for the iPhone (M8); the only code allowed to open non-local connections |

**SMS**
- Receive via the `SMS_RECEIVED` broadcast (`RECEIVE_SMS`) and read history from `Telephony.Sms` (`READ_SMS`).
- Send with `SmsManager` for the chosen subscription, with sent and delivered callbacks.
- Pairline is not the default SMS app; Android records sent messages in the SMS store itself.

**Calls**
- Observe and control calls through an `InCallService` declared for the companion role (`CALL_COMPANION_APP`).
- Answer, reject and end through the `Call` object; place calls with `TelecomManager.placeCall` (`CALL_PHONE`).
- Whether the companion service is bound without extra steps varies by Android version and maker.
  iTell used a shell command for this. First spike result (D-030): Android 16 binds it only for apps holding
  `MANAGE_ONGOING_CALLS`. Without it, the bridge follows calls through per-SIM telephony callbacks (and the
  `PHONE_STATE` broadcast for the number on 12+), and answers/ends with `TelecomManager` (`ANSWER_PHONE_CALLS`);
  DTMF, mute and speaker then aren't available. When the service is bound it takes over.

**Contacts and call log**
- Read-only (`READ_CONTACTS`, `READ_CALL_LOG`), synced incrementally when the iPhone asks: contacts by
  `CONTACT_LAST_UPDATED_TIMESTAMP` plus `DeletedContacts`, the call log by row id. Adding contacts from the iPhone
  (`WRITE_CONTACTS`) is a separate opt-in setting.

**USSD**
- `TelephonyManager.sendUssdRequest` (needs `CALL_PHONE`).

**Staying alive**
- `LinkService` restarts after boot and after app updates.
- The setup wizard asks the user, through Android's own dialog, to exempt Pairline from battery optimization, and shows autostart tips for Xiaomi, Oppo/Realme, Vivo, Huawei and Samsung.
- Settings are never changed without the user (AGENTS.md rule 5).

**Privileged access**
- Uses Shizuku only if the user installed and enabled it; root is an alternative provider behind the same interface.
- Allowlisted operations only:
  - call-audio capture and injection (M6)
  - hotspot start/stop and band choice (M5)
  - Bluetooth restart to recover a stuck link (M1), only if the user turned that option on

**Setup experience (M1)**
- Setup is the hardest part for users, so each step has a short EN/FA video bundled in the app.
- The videos play in picture-in-picture while the user is in Android Settings: battery, autostart, Shizuku.
  Until they're recorded, each wizard step has an animated illustration drawn in Compose (D-031).
- A diagnostics screen shows a readable report; "Share report" shows the exact text before it's sent anywhere.

**Debug builds (D-018)**
- A TCP transport on `127.0.0.1:47800` runs the same pairing, handshake and records as BLE, for the emulator and
  the iOS Simulator; an adb-only content provider hands out the pairing link. `protocol/tools/fake_remote.py` plays
  the iPhone in end-to-end tests (`android/README.md`). None of this is in the release APK.

**Wi-Fi link (M5, D-044)**
- `WifiTransport` binds one listening socket (TCP 47820) per local address of the Wi-Fi and hotspot interfaces —
  never mobile data or VPN interfaces — re-binds on network changes, and closes connections from non-local peers.
- Each Wi-Fi connection is a full session (pairing, handshake, control records), so a phone whose Bluetooth can't
  reach an iPhone (D-040) works over Wi-Fi alone. With Bluetooth also up, `LinkManager` keeps both sessions: events
  go to Bluetooth, replies to the session that asked; when Bluetooth drops, events move to Wi-Fi.
- Discovery: DNS-SD `_pairline._tcp` with a random name, the addresses in the pairing QR code, and `wifi.offer` over
  Bluetooth.
- Media: after each Wi-Fi handshake a UDP socket is bound to that session's local address and its network, and the
  port goes to the iPhone in `wifi.media`. `LinkManager.media` (a `StateFlow<MediaChannel?>`) publishes it once the
  first authentic datagram arrives; call audio (M6) uses it.
- Hotspot: Android's local-only hotspot, started by the user from Settings (Android starts one only for an app in
  the foreground); band choice on Android 16+.

**Sharing files (M5, D-045)**
- Android: a share target, "Send to iPhone", appears in every app's share sheet; Home also has "Send a file to
  iPhone". Received files go to Downloads/Pairline (Android 10+) or Pairline's own folder (Android 9).
- iOS: a Share extension, "Send to bridge phone", and a "Send a file" button; received files appear in the Files app.
- `file.offer` / `file.answer` / `file.done` / `file.cancel` and the data (`BULK` frames) all travel on the Wi-Fi
  session; there's no file transfer over Bluetooth.

**Internet sharing (M8, opt-in)**
- The iPhone joins the bridge hotspot. `netshare` runs a SOCKS5 proxy and an HTTP proxy (with a PAC file for
  iOS Wi-Fi settings), so the iPhone's traffic can go through the Android phone's connection and its VPN.
- The proxies listen only on the hotspot interface and use credentials generated for each session.
- Split routing sends chosen domains and IP ranges directly and everything else through the VPN. The lists
  ship inside the app and update only with app releases.
- It shows which VPN app is active by reading local system state, never by looking up the IP address.

## iOS remote app (`ios/`)

| Group | Responsibility |
|-------|----------------|
| `Protocol` | Frames, hello, records, pairing MACs, BLE fragmenting, TCP framing, JSON message catalog (CryptoKit), tested against `protocol/test-vectors/` |
| `Identity` | Identity key (Secure Enclave or Keychain), pinned peer, Keychain helpers |
| `Link` | `LinkSession` (pairing + handshake + records per connection), `LinkManager` (requests with ids and timeouts, events, reconnection with backoff, idle watchdog), `BluetoothCentral` (CoreBluetooth central with restoration), `TCPTransport` (debug only); M5: `WiFiTransport` / `WiFiConnector` (Network.framework TCP, DNS-SD browsing, address candidates), `MediaSocket` (the UDP `MediaChannel`), `FileTransferManager`, `HotspotJoiner` (`NEHotspotConfiguration`). `LinkManager` runs a Bluetooth session and a Wi-Fi session side by side and routes each request |
| `Bridge` | `BridgeLink` interface, `BridgeController` (sync engine, outbox, status, widget snapshot), status dashboard |
| `Data` | SQLite store for messages, threads, contacts, call log and sync cursors |
| `Messages` | Conversation list, thread, composer, new message, OTP detection, local notifications |
| `Calls` | `CallCenter`: CallKit `CXProvider` / `CXCallController` mapped to `call.*` messages, in-app call screen, dialer, recents, USSD; audio session (M6) |
| `Contacts`, `Settings`, `Onboarding`, `Pairing` | Screens; QR scanner (VisionKit `DataScannerViewController`, AVFoundation fallback) |
| `Store` | StoreKit 2 products, trial state machine (D-017, D-025), paywall |
| `Demo` | `DemoBridge`: the same `BridgeLink` interface with localized sample data, in-memory only (D-020) |
| `PairlineWidget` | WidgetKit extension: bridge battery, signal and link state from the app group |
| `PairlineShare` (M5) | Share extension "Send to bridge phone": copies what you share into the app group's outbox; the app sends it over the Wi-Fi link |

Everything above `BridgeLink` is shared by the real link and demo mode. The transport is behind `FrameTransport`
/ `LinkConnector`, so BLE, the debug TCP link and (M5) Wi-Fi plug in under the same session code.

**Link in the background**
- Uses the `bluetooth-central` background mode with state preservation and restoration, so iOS wakes or relaunches the app for Bluetooth events.
- Known iOS limit: if the user force-quits the app from the app switcher, iOS won't relaunch it until it's opened again. The UI must explain this.

**Calls**
- Incoming calls are reported with `CXProvider.reportNewIncomingCall`, which shows the native call screen, including on the lock screen.
- Calls bridged through CallKit can appear in iPhone Recents, which may sync to iCloud. Keep this off by default (`includesCallsInRecents = false`) and make it a setting.

**Messages**
- Local database protected with `completeUntilFirstUserAuthentication`, so messages arriving while the phone is locked can still be saved.
- Notifications through `UNUserNotificationCenter`.

**Purchases**
- StoreKit 2: `Product`, `Transaction.currentEntitlements`, `Transaction.updates`.
- Verified on the device; no server.

**Wi-Fi (M5)**
- Same Wi-Fi network as the bridge, or the bridge's hotspot, joined with `NEHotspotConfiguration` (Hotspot
  Configuration capability; iOS asks the user) when a `wifi.offer` carries its name and passphrase.
- Needs the Local Network permission (`NSLocalNetworkUsageDescription`, `NSBonjourServices` = `_pairline._tcp`).
- Pairing tries Bluetooth and the QR code's Wi-Fi addresses at once; whichever reaches the bridge first pairs.

## Transports

| Channel | Carries | Notes |
|---------|---------|-------|
| Bluetooth LE GATT | control, SMS, call signaling, contacts, status | Low bandwidth; wakes the iPhone app in the background. Android is the peripheral (advertiser + GATT server); iPhone is the central. |
| Local Wi-Fi (M5) | everything above when Bluetooth is down; call audio (UDP media channel), files and bulk sync (TCP) | Shared network or the bridge's hotspot. A full session of its own, with its own keys; addresses come from DNS-SD, the QR code or `wifi.offer` over Bluetooth. |
| Internet relay (later, paid, opt-in) | everything, when the phones are far apart | End-to-end encrypted; the relay sees only ciphertext. Must be added to `PRIVACY.md` before it ships. |

The Android app declares `INTERNET` since D-041. CI (`network-code-check.sh`) confines socket code to `link/wifi/`
(local addresses only, checked at run time on every bind and accept), `update/` (GitHub only) and the opt-in
`netshare` package (M8), which forwards only the iPhone's own traffic.

**The Wi-Fi link and iOS background.** iOS keeps Bluetooth sessions alive in the background (the app is woken for
Bluetooth events) but suspends apps with only a socket open. So with both links up, events stay on Bluetooth; over
Wi-Fi alone, the iPhone gets new messages and calls while Pairline is open or recently used. Apple's Local Push
Connectivity (`NEAppPushProvider`, a separate entitlement) could close that gap later without a server.

## Call audio options (M6)

| Option | How | Pros | Cons |
|--------|-----|------|------|
| A. Audio stays on Android | iPhone controls the call; the user talks on the Android phone | No privileges, works everywhere | Not the full experience. **Default until M6.** |
| B. Shizuku (shell) | Capture and inject in-call audio with shell privileges | No root | Depends on chip and maker; needs Wireless debugging setup |
| C. Root | Direct audio paths | Works on more chips | Few users are rooted |
| D. Carrier call forwarding to VoIP | Forward calls to a SIP number reachable on the iPhone | No device hacks | Needs a third-party VoIP provider (privacy impact) and carrier support |

M6 starts with a spike: test B on 3–5 devices (Qualcomm, MediaTek, Samsung Exynos) and record a compatibility matrix in `docs/`.
