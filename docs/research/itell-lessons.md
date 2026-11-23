# Lessons from auditing iTell 1.4 (build 41)

Static analysis on 2026-11-22 of `itell-1.4-41.apk`:
- SHA-256: `7514edd8a90bc7e9e4dce729538999823896eb678550d73a3045f2bb7555173d`
- Signer: CN=iTell; certificate SHA-256 `c7ed70dd588ffd5b21aba80d8aa40d3a58a2da0ee0646e35aeec12a4e72d579a`
- VirusTotal at the time: 0 of 67 engines flagged it

**Behavior-level notes only.** No decompiled code may be copied into Pairline (D-011).

## What it is

- **App:** `com.itellapp.android`, minSdk 31, targets the latest Android.
- **What it does:** relays the Android phone's SIM calls, SMS, contacts and call log to an iPhone over Bluetooth LE and the
  Android Wi-Fi hotspot.
- **Distribution:** sideloaded "full" edition, with call audio.
- **Server:** has accounts and a license server (`api.itellapp.com`).

## How it gets system-level access

- **No root needed:**
  - It walks the user through Developer options, Wireless debugging and the pairing code, then connects to the phone's own
    ADB daemon over TLS.
  - It starts a Java service with `app_process` running as the shell user (UID 2000). That service only accepts calls from the app's own UID.
- **With root** (it detects Magisk, KernelSU, APatch): it runs a native helper through `su` that reads in-call audio directly from the sound driver (ALSA).
- **What it uses shell access for:**
  - granting itself `WRITE_SECURE_SETTINGS`
  - exempting itself from Doze and background limits
  - enabling the call-companion permission
  - starting the hotspot
  - reading `dumpsys`
  - installing its own updates

## Ideas worth reusing (not code)

- QR pairing with pinned static P-256 keys; AES-GCM records with replay counters.
- Its own encryption key for each file-transfer session.
- Phone numbers, emails and MAC addresses redacted from logs.
- Updates checked for matching hash, same signing certificate and a higher version before installing.
- Short guide videos for the hard setup steps (Developer options, pairing, battery settings).
- Several call-audio methods per chip vendor (Qualcomm, MediaTek, Samsung), plus a compatibility self-test.
  This is evidence that M6 needs a real device matrix.

## Features worth matching — built our own way

These iTell features are strong for users, so Pairline offers them too (D-012). Only the
feature carries over. Design, protocol, UI and code are our own (D-011), and each one follows
AGENTS.md's rules.

| iTell feature | Pairline version | Milestone |
|---------------|------------------|-----------|
| Setup guide videos (battery, developer options, pairing) | Short EN/FA clips shown in picture-in-picture over Android Settings, bundled in the app | M1 |
| Diagnostic report | Readable report the user can see in full before sharing (rule 9) | M1 |
| Bridge app update check | iPhone tells the user when the bridge app is outdated, using the version in the `status` message; no network | M1 |
| Link recovery | Auto-reconnect; optional, opt-in Bluetooth restart through Shizuku | M1 |
| Busy / unanswered call detection | Call-ended reasons shown on the iPhone | M3 |
| Add contacts from the iPhone | Opt-in, `WRITE_CONTACTS` | M4 |
| "Send" from Android's share sheet | Android share target "Send to iPhone" plus an iOS Share extension "Send to bridge phone" | M5 |
| Hotspot with 2.4 / 5 GHz choice | Same, through the privileged module or the local-only hotspot | M5 |
| Automatic BLE ↔ Wi-Fi switching | Bulk traffic moves to Wi-Fi when it's available | M5 |
| Test call with "did you hear it?" feedback and automatic audio-method choice | Same idea, with the known-device list shipped inside the app, not fetched | M6 |
| Lost-audio filling | Packet-loss concealment (Opus PLC) and an echo self-test | M6 |
| Root mode for call audio | Option C in ARCHITECTURE.md | M6 |
| Trial | StoreKit introductory offer | M7 |
| Sharing the Android phone's internet and VPN with the iPhone | Opt-in proxy on the bridge hotspot with split routing; lists bundled in the app, no third-party downloads or IP lookups (D-013) | M8 |
| Many languages (iTell bundles EN, FA, AR, TR, UR, ZH, ID audio) | EN/FA first, then AR, TR, UR, ZH, ID | Later |

## Things to avoid

- Turning USB debugging back on every 30 seconds after the user switches it off, and turning on Wireless debugging.
- Installing updates silently with `pm install` through shell.
- Encrypting logs with a vendor key so users can't see what they're sending.
- Third-party IP lookups (`ipwho.is`, `ipinfo.io`, `api.country.is`).
- Routing lists downloaded from third-party GitHub repositories.
- Required account with phone number; sending the hardware attestation chain and a hashed `ANDROID_ID` to the server.

## Open questions it raises for us

- **Companion role:** iTell enables the call-companion permission (`MANAGE_ONGOING_CALLS` app-op) through shell.
  Check in M3 whether our companion `InCallService` works without shell on each Android version.
- **Wireless debugging and Wi-Fi changes:** Wireless debugging turns off when the Wi-Fi network changes.
  We must explain that to the user and never force it back on (AGENTS.md rule 5).
