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
| `link` | `LinkService` foreground service (type `connectedDevice`), BLE advertiser + GATT server, Wi-Fi transport (M5), session state |
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
  iTell used a shell command for this. Verify in the M3 spike before building on it.

**Contacts and call log**
- Read-only (`READ_CONTACTS`, `READ_CALL_LOG`), synced incrementally using content observers.

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
- A diagnostics screen shows a readable report; "Share report" shows the exact text before it's sent anywhere.

**Sharing files (M5)**
- Android: a share target, "Send to iPhone", appears in every app's share sheet.
- iOS: a Share extension, "Send to bridge phone".
- Files go over the encrypted bulk channel on Wi-Fi; `file.offer` and `file.answer` travel over BLE.

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
| `Link` | CoreBluetooth central, session handshake, AEAD records, Wi-Fi transport (M5) |
| `Calls` | CallKit `CXProvider` / `CXCallController`, mapped to link messages; audio session (M6) |
| `Messages` | SMS inbox, local store, notifications |
| `Store` | StoreKit 2 products, entitlements, paywall |
| `Protocol` | Message models and crypto (CryptoKit), tested against `protocol/test-vectors/` |
| Share extension (M5) | "Send to bridge phone" from any iPhone app |

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
- Join the bridge's hotspot with `NEHotspotConfiguration` (Hotspot Configuration capability), or use a shared network.
- Needs the Local Network permission.

## Transports

| Channel | Carries | Notes |
|---------|---------|-------|
| Bluetooth LE GATT | control, SMS, call signaling, contacts, status | Always on, low bandwidth. Android is the peripheral (advertiser + GATT server); iPhone is the central. |
| Local Wi-Fi (M5) | call audio, files, bulk sync | Android hotspot or shared network. Address and keys are sent over the BLE link. |
| Internet relay (later, paid, opt-in) | everything, when the phones are far apart | End-to-end encrypted; the relay sees only ciphertext. Must be added to `PRIVACY.md` before it ships. |

The Android app declares no `INTERNET` permission until M5. After that, CI checks that it only opens sockets to
local addresses. The one exception is the opt-in `netshare` package (M8), which forwards only the iPhone's own traffic.

## Call audio options (M6)

| Option | How | Pros | Cons |
|--------|-----|------|------|
| A. Audio stays on Android | iPhone controls the call; the user talks on the Android phone | No privileges, works everywhere | Not the full experience. **Default until M6.** |
| B. Shizuku (shell) | Capture and inject in-call audio with shell privileges | No root | Depends on chip and maker; needs Wireless debugging setup |
| C. Root | Direct audio paths | Works on more chips | Few users are rooted |
| D. Carrier call forwarding to VoIP | Forward calls to a SIP number reachable on the iPhone | No device hacks | Needs a third-party VoIP provider (privacy impact) and carrier support |

M6 starts with a spike: test B on 3–5 devices (Qualcomm, MediaTek, Samsung Exynos) and record a compatibility matrix in `docs/`.
