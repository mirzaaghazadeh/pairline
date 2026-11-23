# Roadmap

**Current milestone: M0 — Project setup**

Agents: tick checkboxes as work lands, and move the "Current milestone" line when a milestone is done.

## M0 — Project setup

- [x] Repo docs: README, AGENTS.md, LICENSE (FSL-1.1-ALv2), PRIVACY, PROTOCOL draft, ARCHITECTURE, DECISIONS, SECURITY
- [x] Android Gradle project in `android/`: Kotlin, Compose, minSdk 30, compileSdk 37 (D-016), targetSdk 36,
      applicationId `app.pairline.android` (provisional, D-007); `./gradlew assembleDebug test lint` pass
- [ ] iOS XcodeGen project in `ios/`: SwiftUI, iOS 16, bundle ID `app.pairline.ios` (provisional); builds for the
      simulator; has a unit-test target
- [ ] `protocol/test-vectors/` with a README and the first HKDF and AES-GCM vectors
- [ ] English and Persian (RTL) string resources in both apps
- [ ] CI (GitHub Actions): build and test both apps; fail if a forbidden SDK appears, or if the Android manifest
      declares `INTERNET` before M5 — **Android done** (`android.yml`), iOS pending
- [x] Android release workflow: tag → signed APK on a draft GitHub Release (D-015, `docs/RELEASING.md`)
- [ ] Replace the "Commands" section in AGENTS.md with the real commands — **Android done**, iOS pending
- [ ] Fill in the licensor name in LICENSE.md

## M1 — Pairing and encrypted link

- [ ] Identity keys (Android Keystore-protected; iOS Secure Enclave)
- [ ] QR pairing (Android shows the code, iPhone scans it) per PROTOCOL.md
- [ ] Android: BLE advertiser + GATT server in a `connectedDevice` foreground service; restart after boot
- [ ] iOS: CoreBluetooth central with background mode and state restoration
- [ ] Session handshake and AEAD records; both apps pass the shared test vectors
- [ ] `status` message (battery, signal, carrier) shown on the iPhone
- [ ] Unpair from either side
- [ ] Android setup wizard: permissions, battery-optimization request, OEM autostart tips
- [ ] Setup guide videos (EN/FA), bundled in the app, shown in picture-in-picture over Android Settings
- [ ] Readable diagnostics screen with a "Share report" action that shows the exact text first
- [ ] Bridge app version in `status`; the iPhone tells the user when the Android app needs an update (no network)
- [ ] Auto-reconnect after the Bluetooth link drops; optional, opt-in Bluetooth restart (Shizuku) when it's stuck

## M2 — SMS

- [ ] Incoming SMS → iPhone notification and inbox
- [ ] Send SMS from the iPhone, choose SIM, sent/delivered status
- [ ] Message history sync (paged)
- [ ] One-time-code detection with a "Copy" action in the iPhone notification

## M3 — Calls (control only; audio stays on Android)

- [ ] Spike: companion `InCallService` binding on Android 11–16 and major makers
- [ ] Incoming call → CallKit on the iPhone; answer, reject and end from the iPhone
- [ ] Dial from the iPhone, choosing the SIM
- [ ] Call-ended reasons on the iPhone: busy, unanswered, declined
- [ ] Setting: show bridged calls in iPhone Recents (off by default; Recents can sync to iCloud)

## M4 — Contacts, call log, USSD, extras

- [ ] Contacts and call-log sync (incremental)
- [ ] Add contacts from the iPhone to the bridge phone (opt-in, `WRITE_CONTACTS`)
- [ ] USSD codes from the iPhone
- [ ] Dual-SIM support everywhere
- [ ] Block list
- [ ] iOS widget with the bridge phone's battery and signal; low-battery alert

## M5 — Local Wi-Fi link and file transfer

- [ ] Bridge hotspot or shared network; iPhone joins with `NEHotspotConfiguration`
- [ ] Hotspot band choice (2.4 / 5 GHz)
- [ ] Media and bulk channel keys and records per PROTOCOL.md
- [ ] Automatic switch: bulk traffic moves to Wi-Fi when available, back to BLE when not
- [ ] File transfer both ways
- [ ] "Send to iPhone" in Android's share sheet; iOS Share extension "Send to bridge phone"
- [ ] Add Android `INTERNET` permission; CI check that sockets only target local addresses (the opt-in
      `netshare` package in M8 is the only exception)

## M6 — Call audio on the iPhone

- [ ] Spike: Shizuku-based capture and injection on 3–5 devices (Qualcomm, MediaTek, Samsung Exynos);
      compatibility matrix in `docs/`
- [ ] `privileged` package with allowlisted operations (AGENTS.md rule 7)
- [ ] Audio streaming over the Wi-Fi link; choose a codec (Opus vs AAC-ELD)
- [ ] Packet-loss concealment (fill short gaps instead of silence) and an echo self-test
- [ ] Test call with "Did you hear it?" feedback; automatic choice of the audio method; known-device list
      shipped inside the app (never fetched)
- [ ] Root mode (option C) for devices where Shizuku isn't enough
- [ ] Clean fallback to "audio on the Android phone" when a device isn't supported

## M7 — Payments and first release

- [ ] Decide the free/paid split (D-008)
- [ ] StoreKit 2 products and paywall; on-device entitlement checks; Restore Purchases
- [ ] Free trial through a StoreKit introductory offer
- [ ] Android release signing; reproducible-build instructions; publish hash and certificate fingerprint
- [ ] Privacy policy page generated from PRIVACY.md; App Store privacy label "Data Not Collected"
- [ ] App Store submission; Android APK on the website
- [ ] External review of PROTOCOL.md

## M8 — Internet sharing (opt-in)

After the first release so it doesn't delay it; move it earlier if it matters more (D-013).

- [ ] Proxy on the bridge hotspot so the iPhone can use the Android phone's connection, including its VPN
  - SOCKS5 for apps like Telegram (one-tap `tg://socks` link)
  - HTTP proxy plus a PAC file for iOS Wi-Fi proxy settings
- [ ] Listens only on the hotspot interface, with credentials generated per session
- [ ] Split routing: chosen domains and IP ranges go direct, the rest through the VPN. Lists ship inside the
      app and update only with app releases
- [ ] Shows which VPN app is active on the bridge phone, read locally (no IP lookups)
- [ ] `netshare` package is the only code allowed to open non-local connections, and only for the iPhone's
      own traffic; CI and PRIVACY.md updated

## Later

- Remote mode through an end-to-end encrypted relay (paid, opt-in)
- Mac app
- Notification mirroring from chosen Android apps
- MMS (picture messages)
- Encrypted backup and export of SMS history
- More languages: Arabic, Turkish, Urdu, Chinese, Indonesian
