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
| Contacts you add on the iPhone (opt-in, M4) | Yes, encrypted | Your bridge phone only | Android Contacts | Until you delete them |
| Files you send (M5) | Yes, encrypted | The other paired phone only | Where you save them | Until you delete them |
| Your iPhone's internet traffic, only while internet sharing is on (M8) | Yes | Through your bridge phone (and its VPN, if one is on) to the sites your iPhone visits | Not stored, not logged | — |
| Call events (who's calling) | Yes, encrypted | Your paired iPhone only | iPhone Recents only if you turn that setting on (Recents can sync to iCloud) | Per iOS settings |
| Call audio (M6) | Yes, encrypted | Streamed live to your iPhone | Not stored | — |
| USSD results | Yes, encrypted | Your paired iPhone only | Not stored | — |
| Phone status (battery, signal, carrier) | Yes, encrypted | Your paired iPhone only | Not stored | — |
| Pairing keys | No | — | Android Keystore-protected storage; iOS Secure Enclave or Keychain | Until unpair |
| Purchases | Yes | Apple (App Store) | Apple | Apple's policy. Pairline only checks Apple-signed transactions on the device |
| App logs | Only if you choose to share them | Wherever you send them | On the device, last 7 days, readable by you | Rolling |
| Backups of the Android app's data | No | — | The Android app opts out of Google cloud backup and of copying to a new phone | — |

## Network connections

| App | Connects to | Why |
|-----|-------------|-----|
| Android bridge | Your iPhone over Bluetooth LE | The link |
| Android bridge | Your iPhone over local Wi-Fi (from M5) | Audio, files |
| iPhone app | Your bridge phone over Bluetooth LE and local Wi-Fi | The link |
| iPhone app | Apple App Store (StoreKit) | Purchases |
| Android bridge, only while internet sharing is on (M8) | The sites your iPhone visits | Forwards your iPhone's own traffic; Pairline starts no connections of its own |

Nothing else. The Android app doesn't request the `INTERNET` permission until the Wi-Fi link exists
(M5). After that, it only connects to addresses on your local network, except when you turn on
internet sharing (M8). Setup videos, routing lists and the known-device list ship inside the app,
so nothing is downloaded.

## Permissions

### Android

| Permission | Why | Asked |
|------------|-----|-------|
| `RECEIVE_SMS`, `READ_SMS`, `SEND_SMS` | Relay SMS both ways | Setup |
| `READ_PHONE_STATE`, `READ_PHONE_NUMBERS` | List SIMs, pick a SIM, show your numbers on the iPhone | Setup |
| `CALL_PHONE` | Place calls and run USSD codes from the iPhone | Setup |
| `ANSWER_PHONE_CALLS` | Answer and end calls from the iPhone | Setup |
| `CALL_COMPANION_APP` | Receive call events as a companion app | Install |
| `READ_CONTACTS` | Show caller names on the iPhone | When you turn on contacts sync |
| `READ_CALL_LOG` | Show call history on the iPhone | When you turn on call-log sync |
| `WRITE_CONTACTS` (M4) | Add contacts from the iPhone | When you turn on that option |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`; `NEARBY_WIFI_DEVICES` (Android 13+) (M5) | Create the hotspot the iPhone joins | When you turn on the Wi-Fi link |
| `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (Android 12+); `BLUETOOTH`, `BLUETOOTH_ADMIN` (Android 11) | The Bluetooth link | Setup |
| `ACCESS_FINE_LOCATION` (Android 11–12, only if M1 testing shows it's needed) | Some Bluetooth/Wi-Fi operations require it on older Android; never used to get your location | Setup |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keep the link running | Install |
| `POST_NOTIFICATIONS` (Android 13+) | The status notification the background service requires | Setup |
| `RECEIVE_BOOT_COMPLETED` | Restart the link after a reboot | Install |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Lets Pairline open Android's own dialog asking you to keep it running | Setup, optional |
| `INTERNET` (from M5) | Local Wi-Fi sockets only | Install |

### iOS

| Permission | Why |
|------------|-----|
| Bluetooth | The link |
| Camera | Scan the pairing QR code |
| Notifications | New SMS and status alerts |
| Local Network (M5) | The Wi-Fi link |
| Microphone (M6) | Call audio |
| Contacts (optional, off by default) | Save a contact from the bridge into iPhone Contacts |

## Things Pairline will never do

- Upload your messages, contacts, call history, audio or files to any server.
- Include analytics, advertising or tracking code.
- Look up your IP address or location with third-party services.
- Download routing lists, device lists or anything else from third-party sites.
- Turn on debugging settings, or install updates, without asking you.
