# Privacy — every data flow

This file is a promise. If the code ever does something that isn't listed here, that's a bug.
Any change that adds or changes a data flow or permission must update this file in the same change
(AGENTS.md rule 4).

## Short version

- **Pairline has no servers in version 1.** Nothing about you is sent to us.
- **Your data moves only between your two phones.** Messages, contacts, call history and call audio go
  directly between them, encrypted.
- **Apple is the only outside company involved,** and only when you buy something in the iPhone app.

## Data flows

| Data | Leaves the device? | Goes to | Stored where | How long |
|------|--------------------|---------|--------------|----------|
| SMS (text, numbers, time) | Yes, encrypted | Your paired iPhone only | Android's own SMS store, as usual; on iPhone, Pairline's local database | Until you delete it or unpair |
| Contacts | Yes, encrypted | Your paired iPhone only | iPhone: local cache | Until unpair |
| Call log | Yes, encrypted | Your paired iPhone only | iPhone: local cache | Until unpair |
| Contacts you add on the iPhone (opt-in, off until you turn it on in the bridge app) | Yes, encrypted | Your bridge phone only | Android Contacts | Until you delete them |
| Files you send (M5) | Yes, encrypted | The other paired phone only | Where you save them | Until you delete them |
| Your iPhone's internet traffic, only while internet sharing is on (M8) | Yes | Through your bridge phone (and its VPN, if one is on) to the sites your iPhone visits | Not stored, not logged | — |
| Call events (who's calling) | Yes, encrypted | Your paired iPhone only | iPhone Recents only if you turn that setting on (Recents can sync to iCloud) | Per iOS settings |
| Call audio, only with "Talk on iPhone" on and only during a call you take on the iPhone (M6) | Yes, encrypted | Streamed live between your two phones over your local Wi-Fi: the caller's voice to the iPhone, your iPhone's microphone into the call | Not stored, not recorded; logs keep only frame counts and loudness levels | — |
| Shizuku permission state (M6) | No | — | Shizuku (a separate app you install) remembers that you allowed Pairline | Until you revoke it in Shizuku |
| USSD results | Yes, encrypted | Your paired iPhone only | Not stored | — |
| Phone status (battery, signal, carrier, your SIM numbers if Android knows them) | Yes, encrypted | Your paired iPhone only | Not stored | — |
| Device model and system version (`device.info`: e.g. "Google Pixel 7", Android 16, app version; on the iPhone side the generic model "iPhone") | Yes, encrypted | The other paired phone only | Bridge app settings: the iPhone's model and iOS/app version, to show them on screen | Until unpair |
| Pairing keys | No | — | Android Keystore-protected storage; iOS Secure Enclave or Keychain | Until unpair |
| Pairing code (the QR code's one-time secret) | Only as the QR code on your screen | — | Bridge app memory only, never on disk or in logs | 5 minutes, or until used once |
| SMS on the bridge | — | — | Pairline keeps no copy: it reads Android's own SMS store when the iPhone asks, and Android stores messages sent from the iPhone in its Sent box as usual | — |
| Purchases | Yes | Apple (App Store) | Apple | Apple's policy. Pairline only checks Apple-signed transactions on the device |
| App logs, including a crash's code locations | Only if you choose to share them | Wherever you send them | On the device, last 7 days, readable by you | Rolling |
| Backups of the Android app's data | No | — | The Android app opts out of Google cloud backup and of copying to a new phone | — |

### On the iPhone

| Data | Leaves the iPhone? | Stored where | How long |
|------|--------------------|--------------|----------|
| Synced SMS, contacts and call log | No | Pairline's SQLite database in the app's Application Support folder, file protection `completeUntilFirstUserAuthentication` (readable after the first unlock, so messages that arrive while locked can be saved), **excluded from iCloud and computer backups** | Until unpair (unpairing deletes it) |
| Messages you write before the bridge confirms them | Yes, encrypted, to your bridge phone only | Same database (an outbox), sent when the link is up | Until sent; then like any message |
| New-message and code notifications | No (iOS may mirror notifications to your own Apple Watch) | iOS Notification Center. Sender name or number and, if "Show message text" is on, the text | Until you clear them; unpairing clears them |
| One-time codes | No | Detected on the iPhone only. "Copy code" puts the code on **this iPhone's** clipboard: never on Universal Clipboard, and it expires after 3 minutes. Other copied text is also local-only | 3 minutes |
| Widget status (link state, bridge battery, signal, carrier name) | No | The app group container shared with Pairline's own widget. No messages, numbers or contacts | Overwritten on every status update; deleted on unpair |
| Identity key and pinned bridge key | No | Secure Enclave key (or Keychain key on devices without one) and Keychain items, `AfterFirstUnlockThisDeviceOnly`, never synced or migrated | Until unpair (the bridge key) / app removal (the identity key) |
| Trial clock | No | The latest trusted time seen (D-017), in the Keychain, so turning the clock back doesn't extend the trial | Until app removal |
| Preferences and saved USSD codes | No | iOS user defaults (no message content) | Until app removal |
| Demo mode data | No | Made-up sample data in memory only, never mixed with real data | Until you leave demo mode |
| App logs | Only if you tap Share after reading them | Application Support, redacted (no phone numbers, emails, device ids or message text) | Last 7 days |
| Pairing link (QR code) | No | Read from the camera and used once; the secret is never stored or logged | Until pairing ends |

## Network connections

| App | Connects to | Why |
|-----|-------------|-----|
| Android bridge | Your iPhone over Bluetooth LE | The link |
| Android bridge | Your iPhone over local Wi-Fi (from M5) | Audio, files |
| Android bridge, unless you turn it off | `api.github.com` once a day, and GitHub's download servers when you tap Update | Checks for a newer Pairline Bridge release. The request carries no user data; GitHub sees your IP address and that a Pairline app asked (D-041) |
| iPhone app | Your bridge phone over Bluetooth LE and local Wi-Fi | The link |
| iPhone app | Apple App Store (StoreKit) | Purchases |
| Android bridge, only while internet sharing is on (M8) | The sites your iPhone visits | Forwards your iPhone's own traffic; Pairline starts no connections of its own |

Nothing else. The Android app's network code is confined by CI to three places: the update check (GitHub only),
the Wi-Fi link (addresses on your local network only) and internet sharing (M8, only while you have it on).
**Debug builds only** (never the release APK): the bridge also listens on `127.0.0.1:47800` inside the phone or
emulator, so developers can connect a test iPhone app through `adb forward` (D-018); it isn't reachable from any
network. Setup videos, routing lists and the known-device list ship inside the app, so nothing else is downloaded.
Links you tap in Settings (the source code, Pairline's Telegram news channel and help group for your language) are
handed to your browser or the Telegram app; the bridge doesn't open them itself.

**iPhone app:** it makes no network requests of its own; the only outside party is Apple (StoreKit, App Store
purchases and restores). Links you tap — the bridge download page `pairline.navid.tr`, the privacy policy, the terms,
the source code, Pairline's Telegram news channel and help group (`t.me`, for the app's language), or a link inside a
message — open in Safari or the Telegram app, only when you tap them. The Telegram help group is public, and the app
says so next to the link. **Debug builds only** (never
release builds, checked by CI with `ios-policy-check.sh`): the app can connect to a test bridge on `127.0.0.1`
(loopback, D-018) instead of Bluetooth.

## Permissions

### Android

| Permission | Why | Asked |
|------------|-----|-------|
| `RECEIVE_SMS`, `READ_SMS`, `SEND_SMS` | Relay SMS both ways; read history when the iPhone asks; look up a sent message's id so both phones agree on it | Setup |
| `READ_PHONE_STATE`, `READ_PHONE_NUMBERS` | List SIMs, pick a SIM, see calls ringing, show signal and your numbers on the iPhone | Setup |
| `CALL_PHONE` | Place calls and run USSD codes from the iPhone | Setup |
| `ANSWER_PHONE_CALLS` | Answer, decline and end calls from the iPhone | Setup |
| `CALL_COMPANION_APP` | Receive call events as a companion app | Install |
| `READ_CALL_LOG` | The caller's number on Android 12+, call history on the iPhone, missed vs declined | Setup, optional |
| `READ_CONTACTS` | Caller names and contacts on the iPhone | Setup, optional |
| `WRITE_CONTACTS` | Add contacts from the iPhone | Only when you turn on "Add contacts from iPhone" in Settings (off by default) |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`; `NEARBY_WIFI_DEVICES` (Android 13+) (M5) | Create the hotspot the iPhone joins | When you turn on the Wi-Fi link |
| `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (Android 12+, "Nearby devices"); `BLUETOOTH`, `BLUETOOTH_ADMIN` (Android 9–11, at install) | The Bluetooth link. The advertisement carries only Pairline's service id: no name or number | Setup |
| `ACCESS_FINE_LOCATION` | **Not requested.** The bridge only advertises and accepts connections, which doesn't need location on any supported Android version | — |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keep the link running | Install |
| `POST_NOTIFICATIONS` (Android 13+) | The status notification the background service requires | Setup |
| `RECEIVE_BOOT_COMPLETED` | Restart the link after a reboot and after an app update | Install |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Lets Pairline open Android's own dialog asking you to keep it running | Setup, optional |
| `INTERNET` | The Wi-Fi link (local addresses only) and the daily update check against GitHub, which you can turn off (D-041) | Install |
| `INTERNET` (**debug builds only**) | The local test transport on `127.0.0.1:47800` (D-018) | Install |
| `MANAGE_ONGOING_CALLS` | Lets Android bind Pairline's companion call service, which gives the iPhone the keypad, mute and speaker during calls (Android 12+). Declared by every build but granted to nobody at install: Pairline turns on this app-op through Shizuku only when you turn on "Talk on iPhone" (D-046) | Only with Talk on iPhone |
| Shizuku access (a separate, optional app) | Call audio on the iPhone: capture the caller's voice and feed your iPhone's microphone into the call, in a small service that runs with Android's shell rights and does only those things (AGENTS.md rule 7, D-046). Shizuku asks you first; you start Shizuku yourself and Pairline never turns on Wireless debugging | When you set up Talk on iPhone |

### iOS

| Permission | Why | Asked |
|------------|-----|-------|
| Bluetooth (`NSBluetoothAlwaysUsageDescription`) | The link | When you first pair (or at launch once paired) |
| Camera (`NSCameraUsageDescription`) | Scan the pairing QR code; frames are processed on the device and never recorded | When you open the scanner |
| Notifications | New SMS, one-time codes, bridge battery alerts and the trial reminder (all local; no push) | After pairing, or from Settings |
| Background mode `bluetooth-central` | iOS wakes Pairline for Bluetooth events from the bridge phone | No prompt |
| Microphone (`NSMicrophoneUsageDescription`) | Only during a call you take on the iPhone with "Talk on iPhone": your voice goes to the bridge phone and into the call. Never recorded or stored (M6, D-047) | When the bridge first offers Talk on iPhone |
| Background mode `audio` | Keeps a relayed call's audio running while the iPhone is locked or another app is open | No prompt |
| CallKit (no prompt) | Shows calls on the iPhone call screen. Calls are added to iPhone Recents only if you turn that on | — |
| In-App Purchase (no prompt) | The free 7-day trial and the one-time purchase (D-017, D-036) | — |
| App Group `group.app.pairline.ios` (no prompt) | Shares the bridge status with Pairline's widget | — |
| Local Network (M5) | The Wi-Fi link | Not yet requested |
| Microphone (M6) | Call audio | Not yet requested |
| Contacts (optional, off by default; not yet used) | Save a contact from the bridge into iPhone Contacts | Not yet requested |

## Things Pairline will never do

- Upload your messages, contacts, call history, audio or files to any server.
- Include analytics, advertising or tracking code.
- Look up your IP address or location with third-party services.
- Download routing lists, device lists or anything else from third-party sites.
- Turn on debugging settings, or install updates, without asking you.
