# Architecture

Pairline is two native apps that talk only to each other.

```
 Android phone (has the SIM)                          iPhone
 ┌───────────────────────────────────┐               ┌───────────────────────────────┐
 │ Pairline Bridge                   │  Bluetooth LE │ Pairline                      │
 │  telephony ── LinkService ── BLE ─┼───────────────┼─ CoreBluetooth ── CallKit     │
 │  (SMS, calls,  (sessions,         │               │                   Messages    │
 │   contacts)     crypto)  Wi-Fi ───┼───────────────┼─ Wi-Fi (M5)       StoreKit 2  │
 │  privileged (own helper, opt.)    │  local Wi-Fi  │                               │
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
| `privileged` | Optional shell-rights helper ("Talk on iPhone"), behind an allowlist; `privileged/adb` pairs with and connects to this phone's own Wireless debugging (D-054) |
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
- `LinkService` restarts after boot and after app updates, unless the user stopped Pairline (Settings › Stop
  Pairline, D-056): then the link, the hotspot, internet sharing and the helper stay off until the user starts it
  again from Settings, the home screen, pairing or setup.
- `LinkService` answers every start with `startForeground` (Android takes it out of the foreground when the user
  restricts background activity, and crashes an unanswered start); the home screen warns when background activity
  is restricted.
- The setup wizard asks the user, through Android's own dialog, to exempt Pairline from battery optimization, and shows autostart tips for Xiaomi, Oppo/Realme, Vivo, Huawei and Samsung.
- Settings are never changed without the user (AGENTS.md rule 5).

**Privileged access (D-054)**
- Pairline's own helper process, no third-party app. On Android 11+ the user turns on Developer options and
  Wireless debugging and pairs Pairline once with the 6-digit code (typed into a Pairline notification). Pairline
  then connects to this phone's own adbd on 127.0.0.1 and runs one command: `app_process` from Pairline's APK as
  the shell user (`privileged/HelperMain`). The helper hands its binder to the app through `HelperProvider` (only
  the shell or root user can call it) and serves only `IPrivilegedService`.
- Pairing is adb's own protocol: TLS 1.3 + RFC 5705 keying material + SPAKE2 (BoringSSL, native, `src/main/cpp`),
  then HKDF and AES-GCM (JCA). Connecting uses the platform's TLS with Pairline's key.
- The helper runs only while Talk on iPhone is on (or its setup screen is open), survives Wireless debugging being
  turned off and Pairline's process restarting, and ends at every reboot, when Pairline is updated, or after 10
  minutes without Pairline. After a reboot Pairline starts it again by itself once the user turns Wireless
  debugging back on. Talk on iPhone needs Android 11+ (D-056): Android 10's shell user may neither capture nor
  record call audio. A computer command remains for phones whose Wireless debugging can't be used.
- Allowlisted operations only:
  - call-audio capture and injection (M6), the earpiece and ringer silenced during a relayed call (D-066)
  - the phone's own hotspot and Bluetooth tethering on or off, mobile data on or off, and the data SIM, each only
    when the user flips that switch on the iPhone (D-073)
  - Bluetooth restart to recover a stuck link (M1), only if the user turned that option on

**Setup experience (M1)**
- Setup is the hardest part for users, so each step has a short EN/FA video bundled in the app.
- The videos play in picture-in-picture while the user is in Android Settings: battery, autostart, Wireless debugging.
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
- Hotspots (D-061): the phone's own hotspot is the one Settings leads with. `PhoneHotspot` reads whether it's on (the
  sticky tethering broadcast) and whether the phone has a connection to share or a VPN, for the status text; the user
  turns it on in Android's settings, and the listeners pick up its interface. Pairline's own hotspot, Android's
  local-only one, sits behind a folded row: started by the user from Settings (Android starts one only for an app in
  the foreground), no internet, band choice on Android 16+.

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
- Declares the `voip` background mode, without PushKit: CallKit refuses to report incoming calls from an app that
  doesn't (`CXErrorCodeMissingVoIPBackgroundMode`, D-057).
- Known iOS limit: if the user force-quits the app from the app switcher, iOS won't relaunch it until it's opened again. The UI must explain this.

**Calls**
- Incoming calls are reported with `CXProvider.reportNewIncomingCall`, which shows the native call screen, including on the lock screen.
- Calls bridged through CallKit appear in iPhone Recents, where a tap calls back through Pairline (`INStartCallIntent`). On by default since D-074 (the maintainer's decision; it was off before); Recents may sync to iCloud, which the setting says, and turning it off keeps calls only in Pairline.
- Siri and Shortcuts: "Call with Pairline" (an App Intent). A `pairline://call?number=…` link only fills in the keypad (D-074).

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
| Bluetooth LE GATT | control, SMS, call signaling, contacts, status; call audio as Opus (`MEDIA` frames, D-072) | Low bandwidth; wakes the iPhone app in the background. Android is the peripheral (advertiser + GATT server); iPhone is the central. Call audio goes ahead of other frames and asks Android for its fastest connection during the call. |
| Local Wi-Fi (M5) | everything above when Bluetooth is down; call audio (UDP media channel), files and bulk sync (TCP) | Shared network or the bridge's hotspot. A full session of its own, with its own keys; addresses come from DNS-SD, the QR code or `wifi.offer` over Bluetooth. |
| Remote mode: mailbox in the user's iCloud (M10, opt-in, D-092) | control, SMS, codes, call signaling and status while no session is up | CloudKit records sealed one by one with the pairing keys (Noise K); Apple stores only ciphertext. The bridge writes and polls through CloudKit Web Services with a web auth token from the iPhone; the iPhone gets a push per record, and its notification service extension unseals and shows SMS, codes and calls. No Pairline server. |
| Remote mode: direct connection (M10, opt-in, D-092) | everything a session carries, call audio as Opus (`MEDIA` frames) | One TCP port the bridge asks its home router for (NAT-PMP, PCP, UPnP IGD); its public addresses reach the iPhone only sealed, in the mailbox. The iPhone connects out; the listener accepts only the paired iPhone's handshake. Only while no Bluetooth or Wi-Fi session is up, and in the background only during a call. |

The Android app declares `INTERNET` since D-041. CI (`network-code-check.sh`) confines socket code to `link/wifi/`
(local addresses only, checked at run time on every bind and accept), `update/` (GitHub only), the opt-in
`netshare` package (M8), which forwards only the iPhone's own traffic, `privileged/adb/` (this phone's own Wireless
debugging, 127.0.0.1 only) and the opt-in `link/remote/` (remote mode: CloudKit Web Services and the home router
only).

**Remote mode (M10, D-092; "Cloud mode" in the apps, D-094).** The bridge stays at home; the iPhone keeps texts, codes and calls from anywhere, with only
Apple and the two phones involved. While no nearby session is up, `LinkManager` on both sides adds two paths:

- **The mailbox in iCloud.** Events from the bridge go to the iPhone's *alert* mailbox (pushed as a notification the
  extension unseals: SMS, codes, calls) or *quiet* mailbox (a silent push; status, replies), and requests from the
  iPhone to the bridge's mailbox, which the bridge checks every 5 s for 2 minutes after hearing from the iPhone, every
  30 s within 10 minutes, then every 2 minutes (CloudKit has no push for web clients). Status changes that matter
  go at once; battery and signal details at most every 10 minutes.
- **The direct connection.** The bridge's `DirectLink` keeps a router mapping and a public listener while remote
  mode is on and announces its public addresses (`remote.addr`) or why there are none (`remote.state`: carrier-grade
  NAT, no mapping, not on Wi-Fi). The iPhone's direct lane connects to them (IPv6 first) and runs the Wi-Fi session
  without its UDP media and bulk channels; call audio rides inside it as Opus `MEDIA` frames, as on Bluetooth.
  A nearby link always wins; in the background the iPhone closes the direct session unless a call is on, so events
  fall back to the mailbox, whose pushes wake the app.

"Pair over the internet" (remote pairing) replaces the QR code with a one-time invitation the user sends to their own
Android phone (a link, or its QR code scanned there) and a 6-digit code checked on both phones. Cloud mode is off by
default; the iPhone offers it right after a normal pairing (D-094); it's part of the trial, then a one-time add-on
(D-093).

**The Wi-Fi link and iOS background.** iOS keeps Bluetooth sessions alive in the background (the app is woken for
Bluetooth events) but suspends apps with only a socket open. So with both links up, events stay on Bluetooth; over
Wi-Fi alone, the iPhone gets new messages and calls while Pairline is open or recently used. Apple's Local Push
Connectivity (`NEAppPushProvider`, a separate entitlement) could close that gap later without a server.

## Call audio options (M6)

| Option | How | Pros | Cons |
|--------|-----|------|------|
| A. Audio stays on Android | iPhone controls the call; the user talks on the Android phone | No privileges, works everywhere | Not the full experience. **Default until M6.** |
| B. Shell rights (Pairline's helper through Wireless debugging; Shizuku until D-054) | Capture and inject in-call audio with shell privileges | No root, no extra app | Depends on chip and maker; needs Wireless debugging on after each restart |
| C. Root | Direct audio paths | Works on more chips | Few users are rooted |
| D. Carrier call forwarding to VoIP | Forward calls to a SIP number reachable on the iPhone | No device hacks | Needs a third-party VoIP provider (privacy impact) and carrier support |

M6 starts with a spike: test B on 3–5 devices (Qualcomm, MediaTek, Samsung Exynos) and record a compatibility matrix in `docs/`.

**Two paths for the audio (D-072).** Under option B the audio travels either inside the Bluetooth session (Opus at
16 kHz and 32 kbit/s, `MEDIA` frames; Android's `MediaCodec` and iOS's AudioToolbox, no codec library of ours) or on
the Wi-Fi link's UDP media channel (raw 16 kHz PCM). The iPhone's Settings › Call audio picks which one a call tries
first (Bluetooth by default) and the other is used when the first isn't up. Android: `LinkMediaChannel` (one per
Bluetooth or debug TCP session; batches what's waiting, drops audio older than 200 ms), `BleTransport` (pulls audio
at each frame boundary, before queued frames), `audio/OpusCodec`. iPhone: `LinkMediaChannel`, `BLETransport` (same
rule), `Calls/OpusCodec`.
