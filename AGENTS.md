# AGENTS.md — Pairline

Instructions for AI coding agents (Claude Code, Codex, …) and for humans working in this repo.
Read this file first, then `docs/ROADMAP.md` to find the current milestone.

## What Pairline is

Pairline lets an iPhone use the SIM card of a nearby Android phone: calls, SMS, contacts,
call log, USSD codes and phone status, relayed over an end-to-end encrypted link between the
user's own two phones (Bluetooth LE for control, local Wi-Fi for audio and files).

- `android/` — the **bridge** app, installed on the Android phone that holds the SIM. **Free.**
- `ios/` — the **remote** app on the iPhone. Free download; paid features through **Apple In-App Purchase**.
- The code is public so users can audit it. License: **FSL-1.1-ALv2** (`LICENSE.md`): anyone may
  read, build and modify it for non-competing use; nobody else may sell it or a competing
  product; each release becomes Apache-2.0 two years after we publish it.

## Non-negotiable rules

These are promises to users. Breaking one blocks a release. If a task seems to need breaking
one, stop and ask the maintainer instead of working around it.

### Privacy

1. **User content never leaves the user's own two devices.** SMS text, phone numbers, contacts,
   call log, call audio, files and one-time codes travel only over the encrypted Pairline link
   between the paired phones. Never send them to any server, and never write them to logs.
2. **No accounts and no Pairline backend in v1.** The apps make no requests to our servers. The only
   outside party is Apple (App Store / StoreKit) on the iPhone. The opt-in internet-sharing feature
   (M8, `netshare`) forwards the iPhone's own traffic; Pairline itself still starts no outside connections.
3. **No analytics, ads, crash-reporting, attribution or IP-geolocation code.** No Firebase, Sentry,
   AppsFlyer, ipinfo-style lookups, etc.
4. **`docs/PRIVACY.md` lists every data flow and permission.** A change that adds or changes a data
   flow or permission must update `PRIVACY.md` in the same change.

### Security

5. **Never change system settings the user didn't ask for.** In particular, never re-enable USB
   debugging or Wireless debugging after the user turns them off; detect it and explain instead.
6. **Never install updates silently.** Android updates go through the system package-installer
   confirmation. Privileged (shell/root) access must never be used for `pm install`.
7. **Privileged access is optional and fenced.** Shell access comes through Shizuku; root is
   optional and never required. All privileged calls live in one small module with an explicit
   allowlist of operations, validate every argument, and accept calls only from our own UID.
8. **Crypto only as specified in `docs/PROTOCOL.md`,** using platform primitives (Android JCA,
   iOS CryptoKit). No home-made algorithms. Any wire-format change bumps the protocol version and
   updates `PROTOCOL.md` and the shared test vectors.
9. **Logs stay readable by the user.** They are local, redacted (no phone numbers, emails,
   MAC addresses or message text), and shared only by an explicit user action that shows the exact
   text first. Never encrypt logs to a key the user doesn't hold.
10. **Clean-room.** We studied the competitor iTell only at the behavior and architecture level
    (`docs/research/itell-lessons.md`). Never copy, paste or translate decompiled code from any
    third-party app.

### Business rules

- The Android app is free: no license checks, paywalls, accounts or purchase code.
- All payments happen in the iOS app via **StoreKit 2**. Entitlements are verified on the device
  from Apple-signed transactions; there is no receipt server.
- iOS pricing: 3 free days through a free "3-day Trial" item, then a one-time $11.99 purchase (D-017).
  Demo mode is always free (D-020).

## Tech stack

|                | Android (bridge)                                    | iOS (remote)                                   |
|----------------|-----------------------------------------------------|------------------------------------------------|
| Language       | Kotlin                                              | Swift                                          |
| UI             | Jetpack Compose                                     | SwiftUI                                        |
| Minimum OS     | Android 11 (API 30)                                 | iOS 16                                         |
| Target         | latest (API 36)                                     | latest                                         |
| Build          | Gradle, Kotlin DSL + version catalog, JDK 17        | Xcode + XcodeGen (`ios/project.yml`)           |
| Key frameworks | Telephony/SmsManager, InCallService (companion), BLE GATT server, Shizuku (optional) | CallKit, CoreBluetooth, AVAudioSession, CryptoKit, StoreKit 2 |

Every new dependency gets an entry in `docs/DECISIONS.md`: why it's needed, its license, and
confirmation that it makes no network calls of its own.

## Repo layout

```
android/        Android bridge app (Gradle project) — created in M0
ios/            iOS remote app (XcodeGen project) — created in M0
protocol/       shared test vectors for the link protocol — created in M0
docs/
  ARCHITECTURE.md   components, transports, background behavior
  PROTOCOL.md       pairing, crypto and message format (draft v0)
  PRIVACY.md        every data flow and permission; must match the code
  ROADMAP.md        milestones; the current one is marked
  DECISIONS.md      append-only decision log (D-001, D-002, …)
  RELEASING.md      how releases are built, signed and published
  research/itell-lessons.md   what we learned from auditing iTell
.github/        CI and release workflows, policy-check scripts
SECURITY.md     vulnerability reporting and release verification
LICENSE.md      FSL-1.1-ALv2
```

## Commands

**Android** (run in `android/`; JDK 17; the SDK path comes from `ANDROID_HOME` or `android/local.properties`):

- Build the debug APK: `./gradlew assembleDebug` (output in `app/build/outputs/apk/debug/`)
- Unit tests: `./gradlew test`
- Lint: `./gradlew lint`
- Everything CI runs: `./gradlew assembleDebug assembleRelease test lint`, then
  `../.github/scripts/android-policy-check.sh app/build/outputs/apk/release/app-release-unsigned.apk release`
  (fails on `INTERNET` permission, forbidden SDKs or a debuggable release)
- End to end on the emulator against the fake iPhone (debug builds only, D-018): install the debug APK, then
  `adb forward tcp:47800 tcp:47800`, read the pairing link with
  `adb shell content query --uri content://app.pairline.android.debug.devtools/pairing`, and run
  `python3 -I protocol/tools/fake_remote.py pair --pair-uri '<link>' status threads listen --seconds 30`.
  Inject events with `adb emu sms send 5551234 "Your code is 4821"` and `adb emu gsm call 5551234`.
  Details: `android/README.md`.

Release APKs are built only by GitHub Actions from an `android-v*` tag: `docs/RELEASING.md`.

**iOS** (run in `ios/`; Xcode 27.2, XcodeGen; the generated `Pairline.xcodeproj` isn't committed):

- Generate the project: `xcodegen generate`
- Build: `xcodebuild -scheme Pairline -destination 'generic/platform=iOS Simulator' build`
- Unit tests (protocol vectors, session, link, data, OTP, trial, StoreKit):
  `xcodebuild -scheme Pairline -destination 'platform=iOS Simulator,name=iPhone 18 Pro' test`
  (any installed iPhone simulator from `xcrun simctl list devices available`)
- Everything CI runs: the tests, a Release build
  (`xcodebuild -scheme Pairline -configuration Release -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`),
  then `../.github/scripts/ios-policy-check.sh <path>/Release-iphonesimulator/Pairline.app`
  (fails on third-party SDKs or debug-only code in the Release binary). CI builds the tests ad-hoc signed
  (`CODE_SIGN_IDENTITY=-`) because the Keychain needs an entitlement. `StoreKitTests` skip on CI when the runner's
  StoreKit test session serves no products, so **run them locally before every release**
- Running from Xcode uses `Pairline/Store/Pairline.storekit`, so the trial and purchase work without Apple's servers.
- End to end on the Simulator against the fake bridge (no Android device needed), debug builds only (D-029):
  `python3 -I protocol/tools/fake_bridge.py --port 47810 --control-port 47811`, then
  `xcrun simctl launch booted app.pairline.ios -PairlineAccess purchased -PairlineTCPPort 47810 -PairlinePairLink "<printed link>"`,
  and inject events with `echo otp | nc 127.0.0.1 47811` (`help` lists commands). Use port 47800 (the default) to
  reach the Android emulator through `adb forward` instead.
- Translations: `xcodebuild -exportLocalizations -project Pairline.xcodeproj -localizationPath <dir> -exportLanguage fa -exportLanguage ar`,
  translate the XLIFF files, then `xcodebuild -importLocalizations -project Pairline.xcodeproj -localizationPath <file>.xliff` (D-028).
- App icon: `swift tools/make_icon.swift Pairline/Resources/Assets.xcassets`

**CI:** `.github/workflows/android.yml` runs on every push and pull request that touches `android/` or `protocol/`.
`.github/workflows/ios.yml` runs on pull requests that touch `ios/` or `protocol/`, and manually (D-022).

## Development machine

- macOS with Xcode 27.2, JDK 17, Gradle and XcodeGen (Homebrew)
- Android SDK at `~/Library/Android/sdk` (platforms 33, 36 and 37.0; build-tools 33.0.1, 35.0.0, 36.0.0, 37.0.0),
  with emulators `pairline-30` (Android 11) and `pairline-36` (Android 16):
  `~/Library/Android/sdk/emulator/emulator -avd pairline-36`
- iOS Simulator "iPhone 18 Pro" (iOS 27). Full two-app test: run the Android debug build on the emulator,
  `adb forward tcp:47800 tcp:47800`, read the pairing link from the devtools provider (see Commands), then
  `xcrun simctl launch booted app.pairline.ios -PairlineAccess purchased -PairlinePairLink "<link>"`
- Bluetooth, real carrier SMS/calls and CallKit need **real devices**. Everything above the transport can be
  tested on the Android emulator and the iOS Simulator through the debug-only TCP link (D-018):
  `adb forward tcp:47800 tcp:47800`, then fake SMS and calls with `adb emu sms send <number> <text>` and
  `adb emu gsm call <number>`.

## Working agreements

- Start each session by reading the current milestone in `docs/ROADMAP.md`; tick its checklist as
  work lands.
- Record decisions in `docs/DECISIONS.md` (append-only, dated). Don't silently reverse one; add a
  new entry that supersedes it.
- Both apps implement `docs/PROTOCOL.md`; shared test vectors in `protocol/` must pass on both.
- User-visible strings in English, Persian and Arabic from the start (D-019). Persian and Arabic are
  right-to-left; check every screen mirrored, with phone numbers and codes kept left-to-right.
- Persian text uses the bundled Estedad typeface (D-032). On iOS use text styles (`.font(.headline)`) or
  `.font(.rounded(_:weight:))`, not `.system(...)`, for anything that shows words; on Android use
  `MaterialTheme.typography`. Both switch to Estedad in Persian automatically.
- Small, reviewable commits. Never commit secrets, signing keys, keystores, `.env` files or
  provisioning profiles.
- **Commit and push as you go.** After each finished, building piece of work, commit it and push to
  `origin` (`github.com/mirzaaghazadeh/pairline`, private until D-014's conditions are met). Don't
  hold a long session's work back for one big commit at the end. Check that the build and tests pass
  before each push; CI (`.github/workflows/`) runs on every push to `main`.
- The website and its backend (`pairline.navid.tr`) live in a separate private repo, `pairline-web`,
  never in this one. The apps never call it (rule 2).

## Open items before the first public release

- Licensor name in `LICENSE.md`.
- Domain, final package IDs and bundle ID (D-007).
- Trademark check for the name "Pairline" (D-001).
- Security contact address in `SECURITY.md`.
