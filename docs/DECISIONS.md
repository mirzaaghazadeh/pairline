# Decision log

Append-only. To change a decision, add a new entry that supersedes the old one and say so in both.

---

### D-001 — Name: Pairline (2026-11-24)

"Twinline" was dropped because another project already uses it. Pairline means two phones paired as one line.
A quick web search found no app with this name, but that isn't a trademark search. Do a proper one
before launch.
**Status:** provisional.

### D-002 — License: FSL-1.1-ALv2 (2026-11-24)

**Goal:** publish all the code so anyone can verify that Pairline keeps no user data, while stopping others from selling it.
**What FSL does:** it allows reading, building and non-competing use, and forbids competing commercial use.
Each release converts to Apache-2.0 two years after we publish it.
**Consequences:**
- Pairline is "source-available" (fair source), not OSI open source. Say so honestly.
- F-Droid won't list it, because F-Droid requires a free-software license.
- Outside contributions need a contributor agreement (CLA), so we keep the right to relicense.

**Alternative considered:** PolyForm Noncommercial, which never converts to open source. Switch to it if
the two-year conversion becomes unacceptable.

### D-003 — Native apps: Kotlin (Android), Swift (iOS) (2026-11-24)

Telephony, BLE peripheral mode, CallKit and audio work fight cross-platform frameworks. The shared part
is the protocol, kept in sync through PROTOCOL.md and shared test vectors.

### D-004 — Minimum OS: Android 11 (API 30), iOS 16 (2026-11-24)

**Android 11:** the first version with Wireless debugging (Shizuku without a PC), and it has the hotspot APIs we need.
Costs:
- a separate legacy Bluetooth-permission path
- no keystore key agreement (API 31+)
- no `AttributionSource` (API 31+)

Android 11 no longer gets security updates, so recommend a spare phone as the bridge.

**iOS 16:** covers iPhone 8 and newer, and has StoreKit 2, CallKit and CryptoKit.

### D-005 — Business model: free Android app, payments through iOS In-App Purchase (2026-11-24)

**Android:** free, with no purchase code, license checks or accounts.
**iOS:** StoreKit 2 IAP, with entitlements verified on the device from Apple-signed transactions. No receipt
server, no accounts.
**Why public code doesn't hurt sales:** building the iOS app from source needs a Mac, Xcode and a developer
account, so almost every user will buy the official app.

### D-006 — No backend in v1 (2026-11-24)

No accounts, analytics, crash reporting or license server. The only network party is Apple, via StoreKit.
A future remote-mode relay must be opt-in and end-to-end encrypted, and must be documented in PRIVACY.md before it ships.

### D-007 — Package IDs (2026-11-24)

**Provisional IDs:** Android `app.pairline.android`, iOS `app.pairline.ios`.
Finalize them after choosing and registering a domain. Changing the Android applicationId after release breaks
updates, so decide before M7.
**Status:** open.

### D-008 — Free vs paid features on iOS (open)

**Proposal to discuss:**
- **Free:** pairing, SMS, call alerts and call control (audio on the Android phone).
- **Paid:** call audio on the iPhone, USSD, contacts and call-log sync, widgets; later remote mode and the Mac app.

**Pricing model:** one-time purchase vs subscription is undecided. A subscription fits only if there's an ongoing cost, such as a relay.
**Status:** superseded by D-017 (2026-11-24).

### D-009 — Privileged access through Shizuku; root optional (2026-11-24)

Don't build our own ADB pairing or shell launcher in v1. Integrate Shizuku if the user has it installed.
**Allowed uses:** only call audio (M6) and hotspot control (M5).
**Never used to:** change settings silently or install packages.
Check Shizuku's license before adding the dependency.

### D-010 — Crypto: P-256 ECDH + HKDF-SHA256 + AES-256-GCM (2026-11-24)

These are available natively on Android API 30 and iOS 16 (CryptoKit, Secure Enclave). The handshake is Noise-KK-style,
over identity keys pinned during QR pairing.
Needs an external review before 1.0.

### D-011 — Clean-room policy (2026-11-24)

We audited the competitor iTell 1.4 (`docs/research/itell-lessons.md`) to learn what to copy and what to avoid. Only
behavior- and architecture-level lessons may be used. No decompiled code is copied, translated or kept in this repo.

### D-012 — Match iTell's strong features with our own design (2026-11-24)

Users will compare Pairline with iTell, so Pairline offers the features that make iTell good. The full list with
milestones is in `docs/research/itell-lessons.md`, "Features worth matching". Highlights:
- setup videos in picture-in-picture
- test call with automatic audio-method choice
- packet-loss concealment
- "Send to iPhone" share target
- adding contacts from the iPhone
- hotspot band choice
- internet sharing (D-013)

**What stays our own:** only the user-facing feature is matched. Design, protocol, UI and code are our own (D-011), and every
feature follows AGENTS.md's rules. Where iTell's version broke one (for example, downloading routing lists from
third-party sites), ours does it differently.
**Extends D-009:** the privileged allowlist also covers an opt-in Bluetooth restart, used to recover a stuck link.

### D-013 — Internet sharing is opt-in and comes after the first release (2026-11-24)

**What it does:** the bridge phone runs SOCKS5 and HTTP proxies on its hotspot, so the iPhone can use the Android phone's connection and
VPN. Split routing sends chosen destinations directly and everything else through the VPN.
**Constraints:**
- off by default
- proxies listen only on the hotspot interface, with per-session credentials
- only the `netshare` package may open non-local connections, and only for the iPhone's own traffic
- routing lists ship inside the app and update with app releases
- no IP lookups; the active VPN is read from local system state

**Placement:** milestone M8, after the first release (M7), so the core calls/SMS product isn't delayed. Move it
earlier if users need it sooner.

### D-014 — Code hosting: private GitHub repo until the first release (2026-11-24)

The repo lives at `github.com/mirzaaghazadeh/pairline`, **private** for now. It becomes public before the first
release, once the trademark check (D-001), the licensor name in `LICENSE.md` and the security contact in
`SECURITY.md` are done. Public repos also get free GitHub Actions minutes.

### D-015 — Android release builds come only from GitHub Actions (2026-11-24)

**What:** pushing a tag `android-v<versionName>` runs `.github/workflows/android-release.yml`. It builds an
unsigned release APK, runs the policy checks, signs it with `apksigner`, and attaches it to a **draft** GitHub
Release with the SHA-256 and the signing-certificate digest. A person publishes the draft. Users download the
APK from GitHub Releases. Steps: `docs/RELEASING.md`.
**Why:** each published APK traces to one commit and a public build log, and signing outside Gradle lets
anyone rebuild the same unsigned APK without our key (reproducible-build check in `SECURITY.md`).
**Trade-off:** the signing key is stored as a GitHub Actions secret, so GitHub and anyone with write access to
the repo can use it. The alternative was signing on the maintainer's computer. Revisit if more people get
write access.
**Hardening:** actions pinned to commit SHAs, release builds without caches, release APKs keep class names
(`-dontobfuscate`) so they're easier to audit, and Google's encrypted dependency report is left out of the APK.

### D-016 — Android dependencies for M0 (2026-11-24)

All Apache-2.0, all from Google Maven, none make network calls:
- `androidx.core:core-ktx`: Kotlin helpers for Android APIs
- `androidx.activity:activity-compose`: hosts Compose in an activity
- `androidx.compose` BOM with `ui`, `material3`, `ui-tooling-preview`, and `ui-tooling` (debug builds only, for
  previews)

Build tools: Android Gradle Plugin, Kotlin and its Compose compiler plugin, and the Gradle wrapper (all Apache-2.0).
CI uses the GitHub Actions `actions/checkout`, `actions/setup-java`, `actions/upload-artifact` and
`gradle/actions/setup-gradle` (all MIT), pinned to commit SHAs.

`compileSdk` is 37 because current AndroidX requires it. `targetSdk` stays 36 and `minSdk` stays 30 (D-004).

### D-017 — iOS pricing: 3 free days, then a one-time $11.99 purchase (2026-11-24)

**Supersedes D-008** and the M7 roadmap item "free trial through a StoreKit introductory offer" (introductory offers
exist only for subscriptions).

**Model:** the whole app is free to use for 3 days. After that the user buys Pairline once for **$11.99** (no
subscription) to keep using it. The Android bridge app stays free (D-005).

**How, within App Review guideline 3.1.1:** non-subscription apps offer a time-based trial through a free
non-consumable in-app purchase named "XX-day Trial". Two non-consumable products:

| Product ID (provisional, D-007) | Reference name | Price |
|---|---|---|
| `app.pairline.ios.trial3day` | 3-day Trial | Free (Tier 0) |
| `app.pairline.ios.full` | Pairline Full Access | $11.99 |

- **Before the trial starts,** the paywall states the duration, what stops working afterwards, and the price.
- **Trial clock:** starts at the trial transaction's Apple-signed `originalPurchaseDate`, so it's verified on the
  device, survives reinstalling, and needs no server. To resist turning the clock back, the app uses the later of
  the device time and the newest Apple-signed date it has seen, stored in the Keychain.
- **During the trial:** everything works. The app shows the days left and sends a local reminder about 24 hours
  before the end.
- **After the trial without a purchase:** the link features stop (sending SMS, calls, sync, USSD). Already-synced
  messages stay readable. Demo mode, help, settings, Restore Purchases and unpair always work.
- **Full purchase:** unlocks everything for good, verified from `Transaction.currentEntitlements`. Family Sharing
  can be turned on in App Store Connect; the code accepts family-shared entitlements.
- **Local testing:** `ios/Pairline/Store/Pairline.storekit` (StoreKit configuration file), no Apple servers.

### D-018 — Debug-only TCP transport for simulator and emulator testing (2026-11-24)

The iOS Simulator has no Bluetooth, so both apps get a TCP transport that exists **only in debug builds**
(PROTOCOL.md, "TCP"). The Android debug build listens on `127.0.0.1:47800` in the emulator; `adb forward` connects
it to the iOS Simulator. Pairing, handshake and records are identical to BLE, so everything above the transport is
tested for real.
- Android: the TCP code and the `INTERNET` permission live only in the `debug` source set. The release APK still
  has no `INTERNET` permission, and CI checks this on every build (`android-policy-check.sh`).
- iOS: the TCP code is behind `#if DEBUG`.
- The iPhone debug build accepts a pasted or opened `pairline://pair` link, since the Simulator has no camera.
  Release builds pair only by scanning.
- The Android debug build exposes test helpers (current pairing link, fake data) through a debug-only content
  provider, never in release.

### D-019 — Languages at launch: English, Persian, Arabic (2026-11-24)

Both apps ship in English, Persian and Arabic. Persian and Arabic are right-to-left: layouts mirror, and phone
numbers and codes stay left-to-right. Translations are written in-house and need a native-speaker review before
release. Later candidates: Turkish, Urdu, Chinese, Indonesian.

### D-020 — Demo mode on the iPhone (2026-11-24)

Anyone can explore the iPhone app without a bridge phone and without paying: a simulated bridge with sample
conversations, calls, contacts and status, in the user's language. Demo data never mixes with real data, is clearly
labeled, and can be left at any time. It also lets App Review see the app working without two phones.

### D-021 — Website and its backend live in a separate private repo (2026-11-24)

`pairline.navid.tr` hosts the landing page (what Pairline is, screenshots, why it exists, how to use it, download
buttons), the privacy policy and support pages the App Store requires, and a small Node.js + PostgreSQL backend,
deployed with Docker Compose on Coolify. It lives in the private repo `pairline-web`, not in this one.
**The apps never call it** (AGENTS.md rule 2): no update checks, accounts or telemetry. The site itself uses no
third-party trackers.

### D-022 — iOS CI runs on demand while the repo is private (2026-11-24)

GitHub bills macOS runner minutes at 10× on private repos. Until the repo is public (D-014), `ios.yml` runs on pull
requests and manually (`workflow_dispatch`), not on every push. Switch it to every push when the repo goes public.

### D-023 — Android dependencies for M1–M4 (2026-11-24)

Added to `android/gradle/libs.versions.toml`. None of them makes network calls of its own, and the release APK
still has no `INTERNET` permission (checked by CI).

| Library | Why | License |
|---------|-----|---------|
| `com.google.zxing:core` 3.5.4 | Draws the pairing QR code. Only the `core` encoder; not the camera/Android parts | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` 1.9.0 | Background work in the link service and telephony code. Was already on the classpath through Compose; now declared | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime-compose` 2.10.0 | Lifecycle-aware state collection in Compose screens. Also already pulled in by Compose | Apache-2.0 |
| `junit:junit` 4.13.2 (tests only) | Unit tests | EPL-1.0 |
| `org.json:json` (tests only) | JVM unit tests need a real `org.json`; Android's `android.jar` only has stubs. The app itself uses the platform's built-in `org.json` | Public domain |

**JSON:** messages use the platform's `org.json` instead of kotlinx.serialization: no extra runtime library, no
compiler plugin, and it ignores unknown fields as PROTOCOL.md requires.
**Icons:** drawn as our own vector drawables instead of adding `material-icons-extended`.
**No navigation or DI library:** a small state-based navigator and a hand-written service locator are enough for
one activity and one service.

### D-024 — iOS app: Apple frameworks only, SQLite, Swift 5 language mode (2026-11-24)

**No third-party dependencies.** The iPhone app uses only Apple frameworks: SwiftUI, CoreBluetooth, CryptoKit,
CallKit, StoreKit 2, VisionKit/AVFoundation (QR scanning), CoreImage (drawing the download-link QR code), WidgetKit,
UserNotifications, Network (debug TCP only) and the system `libsqlite3`. Tests use XCTest and StoreKitTest. CI checks
that no package manager or analytics/ads/crash SDK appears (`.github/scripts/ios-policy-check.sh`).
**Storage:** SQLite through a ~150-line wrapper instead of Core Data: explicit schema, file protection set when the
file is opened, an in-memory database for demo mode and tests, no model file to keep in sync.
**Minimum iOS 16 kept** (D-004): Xcode 27.2 still targets iOS 15+, so no change. iOS 16 rules out `@Observable`,
SwiftData, TipKit and `ContentUnavailableView`; the app uses `ObservableObject` and its own equivalents.
**Swift 5 language mode on the Swift 6.4 toolchain**, with minimal concurrency checking. CoreBluetooth, CallKit and
UserNotifications delegates are main-queue callbacks; UI state is `@MainActor`. Strict Swift 6 mode is a later
cleanup, not a v1 blocker.
**App icon** is drawn by `ios/tools/make_icon.swift` (CoreGraphics), so it can be regenerated and reviewed as code.

### D-025 — iOS access rules in practice (clarifies D-017) (2026-11-24)

- **Pairing needs the trial or a purchase.** The onboarding choice (start 3 free days / buy / demo) comes before
  pairing, so the trial clock starts when the user decides, not at first launch.
- **When locked** (trial not started or ended), the iPhone doesn't connect at all: no SMS, calls, sync, USSD or
  status. Synced messages stay readable; demo mode, help, settings, Restore Purchases and unpair always work. Unpairing
  while locked forgets the bridge on the iPhone and tells the user to unpair on Android too.
- **Trial clock:** the Keychain high-water mark holds the latest of the device time and every Apple-signed date seen,
  and never goes down. A clock that was wrong in the future can end the trial early; that only affects the 3 days.
- **Refunds** (revoked transactions) lock again; family-shared entitlements are accepted.
- Paywall wording: the duration, what stops working, and the one-time price are stated before the trial starts.

### D-026 — iPhone privacy hardening beyond the rules (2026-11-24)

- The SQLite database and logs are excluded from iCloud and computer backups (`isExcludedFromBackup`), so user content
  stays on the two phones even through backups. The cost: a restored iPhone resyncs from the bridge.
- Copied codes and text go to the local clipboard only (no Universal Clipboard), and codes expire after 3 minutes.
- A "Show message text" setting hides SMS text from notifications.
- Drafts aren't persisted; the outbox keeps only messages the user already sent.
- The widget gets link state, battery, signal and carrier name only.

### D-027 — Calls on iOS before call audio (M6) (2026-11-24)

- No `voip` or `audio` background modes until M6: there's no PushKit and no audio on the iPhone. iOS wakes the app for
  Bluetooth events (`bluetooth-central`), and the app reports incoming calls to CallKit from there.
- When CallKit declines a call (Focus, the Simulator), the in-app call screen shows it while the app is open.
- A call answered on the Android phone ends the iPhone's ringing screen as "answered elsewhere" and stays visible in
  the app with an End button.
- Mute, speaker and keypad tones are sent only when the bridge lists `call.audio` / `call.dtmf`; otherwise the app
  explains that audio is on the Android phone.
- **App Store:** apps that use CallKit can't be offered in mainland China. Remove the China storefront from the
  app's availability in App Store Connect.

### D-028 — iOS localization workflow (2026-11-24)

String Catalogs (`Localizable.xcstrings`, `InfoPlist.xcstrings`, the widget's own catalog), with Persian and Arabic
translations imported through Apple's XLIFF round trip (`xcodebuild -exportLocalizations` / `-importLocalizations`),
so no script edits the catalogs. Durations ("2 days, 5 hours") come from `DateComponentsFormatter` in the app's
language instead of hand-written plurals; real plural variants are used where a count is in a sentence. Phone numbers
and codes are wrapped in Unicode left-to-right isolates; dialers and keypads keep their left-to-right layout. As D-019
says, the translations need a native-speaker review before release.

### D-029 — Debug-only test hooks in the iPhone app (extends D-018) (2026-11-24)

Debug builds accept launch arguments for Simulator testing: `-PairlineAccess purchased|trial|expired|none` (fake
access state, because the Simulator can't run sandbox purchases outside Xcode), `-PairlineTCPPort` (pick a port, for
example next to an Android emulator's `adb forward`), `-PairlinePairLink` (the URL-scheme pairing path without the
system alert), `-PairlineSendSMS <to> <text>` and `-PairlineDial <number>` (exercise sending and dialing end to end
without tapping), `-PairlineDemo`, `-PairlineTab`, `-PairlineThread`, `-PairlineDemoCall`, `-PairlinePaywall` and
`-PairlineOnboardingPage` (screenshots), and `-PairlineMarketing` (hides the demo labels for the website screenshots
in `docs/screenshots/ios/`, which show demo data). All of it is behind `#if DEBUG`; CI fails if any of these names or
the TCP transport appear in the Release binary.

### D-030 — Android call control without the companion call role (2026-11-24)

**Finding (M3 spike, API 36 emulator):** Telecom finds Pairline's companion `InCallService` but skips binding it
("control: false") unless the app holds `MANAGE_ONGOING_CALLS`, a signature|appop|role permission. iTell grants
that app-op through shell (`itell-lessons.md`). With the app-op allowed by hand, the service binds and answer,
decline, end, DTMF, mute and speaker all work.
**What the bridge does now:** it watches calls through per-SIM telephony callbacks (the caller's number comes from
the `PHONE_STATE` broadcast with `READ_CALL_LOG` on Android 12+), answers with `TelecomManager.acceptRingingCall` and
declines/ends with `TelecomManager.endCall` (`ANSWER_PHONE_CALLS`). DTMF, mute and speaker answer `unsupported`, and
`call.dtmf` / `call.audio` stay out of `status.caps` until the companion service has been bound once on that phone.
The release manifest doesn't declare `MANAGE_ONGOING_CALLS`; the debug manifest does, for testing.
**Open for the maintainer:** whether release builds should declare it and offer an opt-in way to grant it (the
Shizuku module, D-009, with the user's explicit action, AGENTS.md rules 5 and 7), or wait for M6. Real phones from
several makers still need testing.

### D-031 — Android bridge UI choices (2026-11-24)

- **Brand palette, not wallpaper colors:** Material 3 with the shared brand colors in light and dark, so the bridge
  looks like the iPhone app and the website.
- **Animated illustrations instead of setup videos (for now):** each wizard step has a Compose-drawn, animated
  illustration. The bundled picture-in-picture videos (ROADMAP M1) still need to be recorded.
- **Persian dates use the Solar Hijri calendar;** Arabic uses the locale's default. Phone numbers, codes, versions and
  the diagnostics report stay left to right in every language.
- **Pairing QR:** release builds set `FLAG_SECURE` on the pairing screen, so the code can't be captured by screen
  recording or appear in the recent-apps preview; debug builds allow screenshots for docs.
- **Maker tips only open screens:** the Xiaomi, Oppo/Realme/OnePlus, Vivo and Huawei/Honor settings screens are opened
  by component name when they exist, otherwise App info. Pairline never changes those settings (AGENTS.md rule 5).

### D-032 — Persian text uses the Estedad typeface (2026-11-24)

The maintainer asked for Persian to be set in **Estedad** ([aminabedi68/Estedad](https://github.com/aminabedi68/Estedad),
release 8.5, `Estedad-v8.5.zip` SHA-256 `e780f2c9527e9d9f673b61f1e093c17d6e217c1e4be15a957bb287e13344728e`), in both
apps and on the website. English and Arabic keep their current fonts (system font in the apps, Vazirmatn for Arabic on
the website).

| | Files | License |
|-|-------|---------|
| iOS (app and widget) | `ios/Shared/Fonts/Estedad-{Regular,Medium,SemiBold,Bold}.ttf`, listed under `UIAppFonts` | SIL OFL 1.1 (`Estedad-OFL.txt`, bundled) |
| Android | `android/app/src/main/res/font/estedad_{regular,medium,semibold,bold}.ttf` | SIL OFL 1.1 (`assets/licenses/Estedad-OFL.txt`, bundled) |

- **A font, not code:** static font files with no code, no network access and no build tooling; D-024's "Apple frameworks
  only" still holds for code. Each file also carries its copyright and OFL notice in its name table. The iPhone app's
  About screen credits it.
- **iOS:** `Shared/AppFont.swift` shadows SwiftUI's `Font.largeTitle` … `Font.caption2` inside the module, so every
  existing `.font(.headline)` call picks Estedad in Persian (sized per text style and scaling with Dynamic Type) and
  returns exactly `Font.system(style)` otherwise. `Font.rounded(_:weight:)` replaces the rounded-design system titles.
  `AppFontAppearance` covers the UIKit-drawn bars (navigation titles, tab labels, bar buttons, segmented pickers).
  Alerts and system menus keep the system font. The language is read once at launch (iOS relaunches the app when its
  language changes).
- **Android:** `PairlineTheme` swaps every Material 3 text style to the Estedad family when the app's locale is `fa`.
- **Website** (`pairline-web`): the variable `estedad-wght.woff2`, limited by `unicode-range` to Arabic-script
  characters, so Latin text stays Inter.
- **Four static weights** (Regular, Medium, SemiBold, Bold; about 160 KB each) instead of the variable TTF, because SwiftUI's
  `.weight()` and Compose's `FontWeight` both pick between static faces reliably. Lighter and heavier weights map to the
  nearest bundled one.

### D-033 — Turkish; the App Store listing in English, Arabic and Turkish (2026-11-24)

Extends D-019 at the maintainer's request.

- **Turkish (`tr`) in both apps:** every iPhone string catalog (app and widget, through the XLIFF workflow of D-028),
  Android `values-tr`, Android's language picker and `locales_config.xml`, and Turkish demo data (made-up names,
  +90 555 numbers, lira amounts). Turkish is left to right and uses the system font.
- **App Store listing:** screenshots and text in English (`en-US`), Arabic (`ar-SA`) and Turkish (`tr`) only
  (`docs/app-store/`). No Persian screenshots or text in the listing.
- **The iPhone app doesn't name its languages on its own screens.** The Settings footer only points to iPhone Settings ›
  Pairline › Language, where iOS lists every language the app has (from `CFBundleLocalizations`), Persian included.
  The Estedad credit in About shows only when the app runs in Persian. The Android app (GitHub, not the App Store)
  keeps its in-app picker with each language in its own name, because Android 11 and 12 have no system per-app
  language setting.
- The Turkish, like the Persian and Arabic, was written from the English source and needs a native speaker's review.

### D-034 — First App Store version: 1.0 on the maintainer's personal team, iPhone and iPad (2026-11-24)

- **App record:** App Store Connect app 6838659501, bundle ID `app.pairline.ios` (settles the iOS part of D-007),
  personal team 7Y68TERDUC. "Pairline" alone is taken, so the store name is "Pairline: SMS & Call Bridge"; the Home
  Screen name stays "Pairline". Version 1.0 (`MARKETING_VERSION`), build 1 uploaded with the `asc` CLI.
- **iPad is supported** (the maintainer's choice). The app was already universal (XcodeGen's preset), and its SwiftUI
  layout works full-screen on iPad; an iPad without a SIM gets the Android phone's texts and calls. iPad screenshots
  (`docs/screenshots/ipad/`) are real iPad Pro 12.9" captures, as Apple requires. The iPhone stays portrait-only
  (`UISupportedInterfaceOrientations~iphone`); iPad allows every orientation, which Apple requires for iPad
  multitasking (upload error ITMS-90474).
- **App Privacy:** published as "Data Not Collected" (`docs/app-store/privacy.json`): no analytics, ads or servers,
  and purchases are processed by Apple.
- **Age rating:** every content answer NONE/false except "Messaging and chat", because Pairline sends and receives SMS.
- **Price and availability:** free download; in-app purchases "3-day Trial" (free) and "Pairline Full Access"
  ($11.99), both non-consumable; every territory except China mainland (D-027).
- **Submitted 2026-11-24 with manual release.** Version 1.0, both in-app purchases and the header/search images went
  to App Review together. After approval the version waits for a manual release, because the Android bridge has no
  signed release on the website yet and Bluetooth/CallKit still need real-device testing. Release with
  `asc versions release` (or the button in App Store Connect) once both are done.

### D-035 — Export compliance: encryption only through Apple's operating system (2026-11-24)

The link's encryption (P-256 ECDH, HKDF-SHA256, AES-256-GCM; PROTOCOL.md) is done entirely with Apple's CryptoKit,
the cryptography built into iOS; the app contains no cryptographic algorithm of its own and no third-party crypto
library (AGENTS.md rule 8, D-024). In App Store Connect's export-compliance questions that is "none of the algorithms
mentioned above" (neither proprietary algorithms nor standard ones implemented instead of or in addition to the
operating system's), which needs no encryption documentation. So `ITSAppUsesNonExemptEncryption` is `NO` in
`Info.plist`, and build 1.0 (1) was marked the same with `asc builds update --uses-non-exempt-encryption=false`.
Revisit this before adding any cryptography that doesn't come from the operating system (for example a bundled
Opus/TLS library in M5/M6), and get the maintainer's sign-off: it is a legal declaration.

### D-036 — A free week, then $9.99 once (supersedes the numbers in D-017) (2026-11-24)

The maintainer changed the pricing: **7 free days**, then **$9.99** once (was 3 days and $11.99). The trial still
starts with a free "7-day Trial" non-consumable (`app.pairline.ios.trial7day`), as App Review Guideline 3.1.1 asks for
time-based trials in non-subscription apps: one tap and Face ID, no payment method, nothing charged. An automatic
trial with no tap was considered and rejected because Apple may reject it under 3.1.1. The old
`app.pairline.ios.trial3day` item can't be deleted (it was once in a submission); it was never approved or sold and
is left out of every submission. Build 1.0 (2) carries the change; it was resubmitted with manual release.

### D-037 — Android 1.0.0, the signing key, and where language detection lives (2026-11-24)

- **Pairline Bridge 1.0.0** (versionCode 1) is the first signed release, built by `android-release.yml` from tag
  `android-v1.0.0`. `app.pairline.android` is final (settles the Android part of D-007). The signing key was created
  on the maintainer's Mac in `~/.pairline-signing/` (outside the repo, mode 600) and stored in GitHub Actions secrets;
  its certificate SHA-256 is in SECURITY.md. **Back up `~/.pairline-signing/` offline:** without it no update can
  ever be installed over 1.0.0.
- **Language by country is a website feature only.** `pairline.navid.tr/country.txt` returns Cloudflare's
  CF-IPCountry, and the site's script uses it (after the browser's language list) to open a first visit in Persian,
  Arabic or Turkish. The apps don't call it: AGENTS.md rules 2 and 3 promise that the apps make no requests to our
  servers and do no IP lookups, and the Android app has no `INTERNET` permission. The apps already open in the
  phone's own language, which is the better signal.
- **The App Store price follows the website's `APP_PRICE_USD`** when the website is given an App Store Connect API key
  (`ASC_*` env): on each deploy it sets the price schedule of `app.pairline.ios.full` to that value. The apps show
  whatever price StoreKit reports, so a price change needs no app update. The key gives App Manager access to every
  app on the team, so it should be a dedicated key that can be revoked on its own.

### D-038 — The bridge runs on Android 9 and later (supersedes the Android 11 minimum) (2026-11-24)

The maintainer's test phone (Galaxy Note 8) runs Android 9, and spare phones that end up as bridges are often that
old. `minSdk` is now 28. Android lint listed every newer API in use, and each now has a fallback:
- **Call direction** (Android 10): on Android 9 a call that arrives ringing is incoming, one being dialed outgoing.
- **Phone account → SIM** (`getSubscriptionId(PhoneAccountHandle)`, Android 11): on Android 9 and 10 the telephony
  phone account's id is the subscription id or the SIM's ICCID, depending on the maker; both are matched.
- **SIM changes and radio state listeners** (Executor variants, Android 10/11): the radio thread is now a Looper
  thread, and on Android 9 and 10 the listeners are created on it so they deliver there; the 5G display-info event
  is only requested on Android 11.
- The setup notice about missing security updates now names the phone's real version (9, 10 or 11).
Bluetooth works the same: Android 9–11 use the install-time `BLUETOOTH`/`BLUETOOTH_ADMIN` permissions the manifest
already declares (up to API 30), and advertising as a peripheral needs no location permission. Tested: build, unit
tests and lint at minSdk 28; on-device testing on the Note 8 is next. Released as Pairline Bridge 1.0.1.

### D-039 — Release APKs are scanned on VirusTotal (2026-11-24)

Sideloaded APKs get asked "is this safe?", and a VirusTotal report is the answer people already know.
`android-release.yml` now uploads the signed APK to VirusTotal after verifying its signature, waits for the scan (up
to 10 minutes) and puts a "VirusTotal" row in the draft's notes: the report link and how many engines flagged it.
- **What's sent:** only the release APK, the same public file attached to the GitHub Release. Nothing from users and
  nothing from the apps; the apps still make no requests (rules 1–3). VirusTotal shares uploaded files with security
  vendors, which is fine for a file we publish anyway.
- **How:** `.github/scripts/virustotal-scan.sh`, plain `curl` and `jq` against the v3 API. No third-party action, so
  the key (`VT_API_KEY` secret) only reaches our own script and VirusTotal.
- **It never blocks a release.** A missing key, an API error, a slow scan or a detection becomes a workflow warning and
  shows in the draft's row; the person publishing the draft reads the report first. New APKs often draw a false
  positive from one or two engines, so a detection is a reason to look, not an automatic stop.
- Releases made before this (1.0.0, 1.0.1) can be scanned with the manual `virustotal.yml` workflow, which adds the
  same row to the existing release notes.

### D-040 — Bluetooth link fixes from the first real-phone tests: fragment cap, quiet while connected, crash log (2026-11-24)

The emulator tests run over the debug TCP link, so the Bluetooth path first met real phones this week. Three
findings, each fixed in Pairline Bridge 1.0.2:
- **Crash after pairing on Android 13+ (a tester's Android 15 phone).** An iPhone negotiates ATT MTU 517, so the
  bridge cut frames into 514-byte fragments (MTU − 3). Android 13+ throws `IllegalArgumentException` for a
  notification longer than 512 bytes (the longest attribute value), on the Bluetooth thread, which killed the app as
  soon as the first large message (the sync after pairing) went out. Fragments are now `min(MTU − 3, 512)` on both
  sides (PROTOCOL.md; receivers already accept any size, so this is not a wire-format change and needs no version
  bump). A rejected notification now ends that link instead of the app, and an oversized frame is logged and dropped.
- **Reconnect loop.** Android keeps advertising after a phone connects, under a new random address. The iPhone app,
  still looking for its bridge, takes that for another bridge and connects to it too; the Galaxy Note 8 then drops the
  first link (HCI reason 0x08), every ~0.5 s. Reproduced from a Mac acting like the iPhone. The bridge now stops
  advertising when a phone connects and starts again when it disconnects, or after 8 s if the link never touched the
  Pairline service (a watch or earbuds must not hide the bridge).
- **Crashes are now visible.** An uncaught exception is written to the local log (code locations; messages go through
  the redactor) before the process dies, so diagnostics shows it after the restart (rule 9; PRIVACY.md updated).

**The Galaxy Note 8 (Android 9) still doesn't finish connecting to Apple devices.** With the loop gone, a Mac
connects and both sides exchange features and versions, then macOS waits for a link-layer step that never
completes ("PHYReady: no" in its log); no GATT request reaches the bridge, and the Note 8's controller drops the
link 40 s later (LL procedure timeout). The iPhone's links end the same way from the bridge's side: up for a few
seconds, no GATT request, closed by the iPhone. Asking for the 1M PHY on connection completed on the Note 8 but
didn't change this, so it was taken out again. It happens below Android's APIs, in the Bluetooth chip's firmware,
and the app has no lever on it. Other Android 9 phones may be fine; until more are tested, the minimum stays Android 9.

### D-041 — The Android app gets `INTERNET`: Wi-Fi link and a daily update check against GitHub (2026-11-24)

The maintainer asked for the bridge to check GitHub for new releases and suggest updating, and chose "daily, with
an off switch" over opt-in or a browser-only button. This amends AGENTS.md rule 2, which promised that Pairline
starts no outside connections:
- **What's sent:** one HTTPS request a day to `api.github.com` for the public release list, and the APK download from
  GitHub when the user taps Update. Nothing about the user, their phone or their data; GitHub sees the phone's IP
  address and that a Pairline app asked. On by default, explained during setup, off with one switch in Settings.
- **Installing stays manual (rule 6):** the APK must match the SHA-256 in the release notes and be signed with the
  same certificate as the installed app, and it installs through Android's own confirmation screen.
- **Fenced in CI:** `.github/scripts/network-code-check.sh` fails the build if network code appears outside
  `update/` (GitHub hosts only), `link/wifi/` (the M5 Wi-Fi link, local addresses only) and `netshare/` (M8). The
  release APK may now declare `INTERNET`; `android-policy-check.sh` no longer fails on it.
- PRIVACY.md lists the new flow; the website's privacy page follows.

Also added: M9, an Android **client** mode so two Android phones can pair (maintainer request), and on M4 deleting
SMS and call history from the iPhone. Decision numbers reserved for the parallel work: D-042–D-043 (delete, block
list, update check details), D-044–D-045 (M5 Wi-Fi link, files), D-046–D-047 (M6 call audio, Shizuku),
D-048 (M8), D-049 (M9).

### D-046 — Shizuku for privileged access: `dev.rikka.shizuku:api` + `provider` 13.1.5 (2026-11-24)

Android gives normal apps no way to hear a phone call or speak into it; the shell user can on Android 10+
(`CAPTURE_AUDIO_OUTPUT`, `MODIFY_PHONE_STATE`, `MODIFY_AUDIO_ROUTING` — checked on the Android 16 emulator; missing on
Android 9). Shizuku is the established, open-source way for a user to lend an app shell rights without root.
- **Library:** `dev.rikka.shizuku:api` and `:provider` 13.1.5, Apache-2.0. Binder IPC to the user's Shizuku app
  only; no network code (the CI network fence scans our sources; the library opens no sockets).
- **Fenced (AGENTS.md rule 7):** everything privileged runs in `privileged/PrivilegedService`, a Shizuku user service
  in its own process, behind `IPrivilegedService.aidl`, which has exactly four operations: `probe`,
  `allowOngoingCalls` (Pairline's own package only), `startCapture` (`VOICE_DOWNLINK`/`VOICE_CALL` at 8/16/48 kHz),
  `startInject` (16-bit PCM to the telephony output, optional bridge-mic mute restored on stop). Every call checks
  that the caller's uid is Pairline's; every argument is checked against a fixed list. Audio is attributed to the
  shell package (`createPackageContext("com.android.shell")`), which is what the process really is.
- **User in control (rule 5):** Pairline never installs or starts Shizuku and never turns on Wireless debugging; the
  setup screen only opens Shizuku, the Play Store and Developer options for the user. Shizuku shows its own permission
  dialog. Everything else in Pairline works without it.
- **`MANAGE_ONGOING_CALLS`** moves from the debug manifest to every build (answers the M3 open question). It's an
  app-op nobody gets at install; the privileged service turns it on for Pairline when the user turns on "Talk on
  iPhone", which lets Android bind the companion `InCallService` → keypad, mute and speaker from the iPhone.
- R8 keeps `PrivilegedService(Context)` because Shizuku instantiates it by name.

### D-047 — Call audio on the iPhone: uncompressed 16 kHz PCM over the Wi-Fi media channel (2026-11-24)

- **Path:** caller's voice ← `VOICE_DOWNLINK` capture (the far end only, so the iPhone user doesn't hear themselves;
  `VOICE_CALL` as the fallback) → 20 ms frames → Wi-Fi media channel (UDP, AES-GCM, PROTOCOL.md "Call audio") →
  iPhone; iPhone microphone → same channel → 60 ms jitter buffer → telephony output (the call's uplink), paced one
  frame per 20 ms by the app so the pipe to the service can't build delay.
- **No codec:** 32 KB/s each way is nothing on a local network, needs nothing on either side and adds no latency.
  The packet has a kind byte so a compressed format can come later.
- **Learning what works:** after a relayed call the iPhone can send `call.media.feedback`. "Couldn't hear the
  caller" switches the capture source; "they couldn't hear me" first stops muting the bridge's microphone, then
  after two more failures reports talking as unsupported (`call.media.talk` disappears). Settings can reset this.
- **Honest capabilities:** `call.media` only when the user turned on Talk on iPhone, Shizuku is ready and capture is
  allowed; `call.media.talk` only when a telephony output exists too. Without talk the iPhone still hears the call
  and the user talks on the bridge phone.
- **Tested:** unit tests (packets, jitter buffer, resampling, caps), builds incl. release/R8. Real calls still need a
  real Android 10+ phone with Shizuku (the first tester has Android 15); the emulator has no real call audio.

### D-048 — Internet sharing: one SOCKS5 + HTTP proxy port on the local network, PAC for iOS (2026-11-24)

- **Why a proxy and not plain tethering:** Android sends hotspot clients' traffic around its own VPN, so tethering
  alone doesn't give the iPhone the bridge's VPN. A proxy's outbound connections are the bridge app's own, so they
  follow Android's default network, which is the VPN when one is on.
- **One port, both protocols:** the first byte tells SOCKS5 (`0x05`) from HTTP. SOCKS5 (CONNECT only, RFC 1929
  username/password) for Telegram, which takes a one-tap `tg://socks` link. HTTP CONNECT and absolute-form requests
  (one request per connection) for iOS's Wi-Fi proxy settings, plus `GET /proxy.pac`.
- **Safety:** bound to the bridge's Wi-Fi/hotspot address only, never all interfaces; only private/link-local
  clients; port and credentials new on every start and kept in memory only. HTTP needs the password too, except from
  the paired iPhone's Wi-Fi-link address once M5 knows it (iOS's automatic proxy settings can't carry a password).
  The PAC file holds no secret. Logs keep only a connection count.
- **Off by default, two switches:** the bridge's user must allow sharing (`net.share` capability), then the iPhone
  starts and stops it. It survives link drops (so browsing doesn't break when Bluetooth blinks) and stops when either
  side turns it off. Disallowing on the bridge stops it and tells the iPhone (`net.share` event).
- **Split routing in the PAC file:** local addresses always direct; `.ir` direct when the user turns that on (the
  list ships in the app, D-013); everything else `PROXY` with no `DIRECT` fallback, so a dropped bridge stops
  traffic instead of quietly bypassing the VPN.
- **VPN shown, app name not:** `ACCESS_NETWORK_STATE` tells whether a VPN network exists; Android doesn't reveal which
  app owns it, and we do no IP lookups.
- **Tested:** JVM tests run the proxy on loopback against an echo server (SOCKS5 with/without password, HTTP 407 and
  CONNECT, PAC contents, parsing). A real iPhone↔bridge test needs the M5 Wi-Fi link.

### D-049 — The logo: two chain links (2026-11-24)

The maintainer picked option 4 of five Telegram avatar directions ("Chain") and made it Pairline's logo everywhere:
two links of a chain, one for each phone, the white one passing over the green one at the top and under it at the
bottom, on the brand-blue gradient (#2A6FD6 → #15468F). It replaces the two-phones mark.
- **One geometry** (a 512-unit artboard: 220 × 120 pill links, 32-unit strokes, overlapping by 100), used by
  `ios/tools/make_icon.swift` (app icon light/dark/tinted, launch mark, brand mark), the Android adaptive icon,
  monochrome icon and status-bar icon (`res/drawable/ic_launcher_*.xml`, `ic_stat_pairline.xml`), and in
  `pairline-web` (logo, favicons, PWA icons, share images, the Telegram avatars in `design/telegram/`).
- **The interlock** is drawn without masks: draw the white link, the green link, then the white link's top half
  again. That works in SVG (with no ids, so the mark can repeat on a page), CoreGraphics and Android vectors.
  One-colour versions (tinted iOS icon, themed Android icon, notification icon) keep the two links apart by tone or
  simply overlap.
- Users see it with the next builds: iOS after the next App Store upload, Android from the next release.

### D-050 — No Android client mode (drops M9) (2026-11-24)

The maintainer decided against M9 (the Android app working as a client of another Android phone, added in D-041). The
Android app stays a bridge only; the iPhone stays the only remote. The started work is kept, unmerged, on the branch
`archive/m9-android-client-start` (one commit: the R-role session in Kotlin) in case this comes back. Decision numbers
reserved for it in D-041 are free again.

### D-044 — The Wi-Fi link is a full link of its own, next to Bluetooth (2026-11-24)

The Galaxy Note 8 can't hold a Bluetooth link with Apple devices (D-040), and call audio (M6) needs a low-latency
channel. So the Wi-Fi link (M5) is not an add-on to the Bluetooth session but a **complete session**: each Wi-Fi TCP
connection runs the same pairing, handshake and records as Bluetooth, with its own keys (PROTOCOL.md, "Wi-Fi").
- **Two sessions at once.** With both up, the bridge keeps one session per transport. Events go to Bluetooth while it
  is up — iOS wakes Pairline for Bluetooth but not for sockets — and move to Wi-Fi when it drops; replies go back on
  the session the request came from; the iPhone sends the bulk sync requests over Wi-Fi. This makes `link.switch`
  unnecessary, so it isn't used. The other option, Wi-Fi channels keyed from the Bluetooth session, would have left
  phones without working Bluetooth with nothing.
- **Media and bulk keys** come from the Wi-Fi session's own `okm` (the derivation in PROTOCOL.md is unchanged). Media
  is one record per UDP datagram (≤ 1200 bytes of payload, a 64-record replay window, probes to open the path); the
  UDP port travels in a new `wifi.media` message. File data uses a new frame type `BULK` (0x21) on the Wi-Fi TCP
  connection. Neither needs a version bump: an older app never receives them (a bridge sends `BULK` only for a
  transfer the iPhone accepted, and media only after `wifi.media`, which older iPhones ignore).
- **Finding the bridge:** the pairing QR code carries the bridge's local addresses and port (optional parameters;
  old links still work), the bridge advertises `_pairline._tcp` over DNS-SD with a random name (never the phone's
  name or the device id), and `wifi.offer` over Bluetooth repeats the addresses and, while it runs, the hotspot's
  name and passphrase. The iPhone pairs over whichever transport reaches the bridge first. The iPhone starts looking
  on Wi-Fi only once the bridge has mentioned Wi-Fi (pair link, `wifi.offer` or the `wifi` capability), so an older
  bridge never triggers the Local Network prompt.
- **Local only.** The bridge binds one listener per local address of its Wi-Fi and hotspot interfaces — never mobile
  data or VPN interfaces, even when they have a private address — and drops peers that aren't private, link-local or
  unique-local. The media socket is bound to the session's address and its Android `Network`, because unbound UDP
  sockets leave on the default network (often mobile data when the Wi-Fi has no internet); accepted TCP sockets
  already follow their network. The listener can be turned off in Settings (on by default).
- **Keep-alive:** ping after 15 s of silence, close after 45 s, so a Wi-Fi drop moves traffic back to Bluetooth fast.
- **Call audio (D-047)** uses this media channel: the bridge hands it to its relay as it opens and closes, and the
  iPhone's `CallCenter` takes `LinkManager.mediaChannel`. Internet sharing (D-048) learns the iPhone's address from
  the authenticated Wi-Fi session.
- **Hotspot (best effort):** Android's local-only hotspot, started by the user from Settings — Android starts one
  only for an app in the foreground, and needs `NEARBY_WIFI_DEVICES` on 13+ or location permission with Location on
  for 9–12. Band choice uses `startLocalOnlyHotspotWithConfiguration` + `SoftApConfiguration.Builder.setChannels`,
  public only since Android 16 (API 36); older versions let Android choose. The iPhone joins with
  `NEHotspotConfiguration` (Hotspot Configuration entitlement; the App ID on team 7Y68TERDUC needs that capability
  before the next archive).
- **Known limit:** over Wi-Fi alone, the iPhone app is suspended in the background like any app with only a socket,
  so messages and calls arrive while it's open or recently used. Apple's Local Push Connectivity
  (`NEAppPushProvider`) could fix that later without any server.
- **Emulator testing:** the emulator's NAT (`adb emu redir`) delivers to its emulated mobile-data interface, so a
  debug build on an emulator accepts that interface, and the debug manifest adds `CHANGE_NETWORK_STATE` so the media
  socket may bind to it. Neither is in release builds.

### D-045 — Files: both ways over the Wi-Fi session, accepted automatically, 1 GiB at most (2026-11-24)

- **Over Wi-Fi only.** Offers, answers and data travel on the Wi-Fi session (`file.offer` / `file.answer` /
  `file.done` / `file.cancel`, data in `BULK` records of 48 KiB). Bluetooth would take minutes for a photo, and
  splitting control (Bluetooth) from data (Wi-Fi) would leave half a transfer behind when either drops. A transfer
  fails when its Wi-Fi session ends; there's no resume in v0. Files shared while Wi-Fi is down wait for it.
- **No prompt on the receiving phone.** Both phones belong to the person who started the transfer, so the receiver
  accepts on its own unless the file is over 1 GiB, there's no room, or 4 files are already arriving. 1 GiB covers
  photos, videos and documents without needing resumable transfers.
- **Where files land.** Android 10+: Downloads/Pairline through MediaStore, with no storage permission. Android 9:
  Pairline's own folder in shared storage (no `WRITE_EXTERNAL_STORAGE`), opened from the notification through a
  FileProvider. iPhone: `Documents/Received`, shown in the Files app (`UIFileSharingEnabled`); received files are
  included in device backups like any document.
- **Sending.** Android: "Send to iPhone" in every share sheet (it accepts only `content:` URIs, never a `file:` path
  that could point into Pairline's own data) and "Send a file to iPhone" on Home. iPhone: a Share extension, "Send to
  bridge phone", which copies the files into the app group's outbox and wakes the app with a Darwin notification;
  the app sends them over the Wi-Fi link and deletes them after delivery. The extension never talks to the bridge
  itself, so it needs no keys.
- File names are shown on the phones (notifications, the transfer list) but never logged.

### D-042 — How the Android update check works (2026-11-24)

Details for D-041's daily check. All of it lives in `app.pairline.android.update`, the only package allowed to reach
GitHub (`network-code-check.sh`).
- **When:** while the link service runs it looks every hour whether a day has passed since the last attempt (3 hours
  after a failed one), and the app checks the same way when it's opened. Only after setup is done, and never while
  "Check for updates" is off. "Check now" in Settings works with the switch off too. No scheduler library: a
  coroutine in `LinkService` is enough, so no new dependency.
- **What:** `GET https://api.github.com/repos/mirzaaghazadeh/pairline/releases?per_page=20` with
  `HttpsURLConnection` and `org.json`, a generic `User-Agent: Pairline-Bridge` (no version, no device), no cookies.
  Redirects are followed by hand, at most 5, and only to HTTPS GitHub hosts (`api.github.com`, `github.com`,
  `objects.githubusercontent.com`, `release-assets.githubusercontent.com`). Replies are capped at 4 MB, APKs at 150 MB
  and at the size GitHub lists.
- **Which release:** the newest one that is published (not a draft), not a pre-release, tagged `android-v<x.y.z>`
  (exactly three numbers), with the asset `pairline-bridge-<x.y.z>.apk` on github.com and exactly one
  `| APK SHA-256 | `<64 hex>` |` row in its notes (written by `android-release.yml`). Releases missing any of these
  are skipped, so an APK that can't be checked is never offered. It's offered when its version is higher than
  `BuildConfig.VERSION_NAME` without a `-debug`-style suffix, compared as numbers (1.10.0 > 1.9.0).
- **Telling the user:** one notification per new version on a new "App updates" channel (default importance), a
  card on the home screen and a row in Settings with the version and a "What's new" link to the release page, which
  opens in the browser only when tapped.
- **Installing (rule 6):** "Update" first makes sure Android's "Install unknown apps" is allowed for Pairline
  (`REQUEST_INSTALL_PACKAGES`); if not, it explains and opens Android's own screen, and carries on when the user comes
  back (Android 11 restarts the app when that switch changes, so the request is remembered for 10 minutes). The APK
  goes to the app's cache and must pass, in order: SHA-256 equal to the notes' row (hashed while downloading and
  again from the file), package name equal to ours, version code higher than the installed one, and signing
  certificates (Android 9+ signing info) exactly equal to the installed app's. Only then is it written into a
  `PackageInstaller` session, hashed once more while copying (the session is abandoned if it changed), and committed
  with `USER_ACTION_REQUIRED` on Android 12+, so Android always shows its own confirmation screen. The cache file is
  deleted afterwards and after any failure. A failed check or verification is logged by reason (no hashes, no URLs)
  and shown in plain words; nothing is installed.
- **Why a session and not `ACTION_VIEW` with a FileProvider:** no provider to declare, the file never leaves the
  app's private cache, and the hash can be checked on the exact bytes Android installs.
- **Debug builds** have adb-only devtools hooks (`update-pretend/<version>`, `update-check`, `update-state`,
  `update-start`, `update-install-cached/<name>/<sha256>`) to test the path against real releases and locally built
  APKs. Tested on the Android 11 emulator: the real 1.0.2 release is found, downloaded and its checksum matches; a
  debug build refuses it (different package); a wrong checksum, the same version and a different signing key are
  each refused; a correctly signed newer debug build installs through Android's confirmation screen.
- **Setup and Settings:** the privacy step of the setup wizard now says the app asks GitHub once a day and that this
  can be turned off; Settings has an Updates section (switch, Check now, last check, installed version). The
  privacy texts that said the app had no internet permission were corrected.

### D-043 — Deleting from the iPhone, and a block list kept on the bridge (2026-11-24)

**Deleting.**
- **SMS are deleted on the iPhone only.** Android lets only the default SMS app write to its SMS store, and Pairline
  isn't one (becoming it would take over the user's messaging), so there's no `sms.delete`. Conversations (swipe, or
  Edit with multi-select) and single messages (context menu) are removed from Pairline's database, and the confirm
  dialog says the messages stay on the Android phone and why.
- **Tombstones** keep later syncs from bringing them back: a deleted message's key (and, for an `rx:` message, its
  number's match key, because its stored copy arrives under another key within 60 s); a deleted conversation's
  thread id with the time of its newest message, so a newer message brings the conversation back with just that
  message (and nothing to "load earlier"); for a conversation started on the iPhone (negative id) also the number,
  because the bridge files it under its own thread id. A message deleted before the bridge reported its key is
  caught by its `cid` when `sms.state` arrives. The newest synced time is remembered, so deleting the newest
  messages, or everything, doesn't make the next sync fetch them again. No text and no hashes of text are kept;
  unpairing deletes the tombstones with everything else.
- **Call history is deleted on both phones when it can be:** new request `calllog.delete` (`keys` or `all: true`),
  capability `calllog.delete` while the bridge holds `READ_CALL_LOG` and the new `WRITE_CALL_LOG`. `WRITE_CALL_LOG`
  joined the optional "Call history" step of the setup wizard: both are in Android's call-log permission group, so
  it's one dialog (or no dialog at all for users who already allowed reading). Asking only when the user first
  deletes on the iPhone was rejected: the bridge can't show a permission dialog while it sits in a drawer, and a
  notification asking for it would be noisier than one more line in a step the user already sees. Existing users
  see "Delete call history from iPhone" in "What your iPhone can do" with a Fix button. Without the capability, or
  when the bridge isn't connected, the iPhone deletes locally and keeps tombstones (keys, and for "Clear all" the
  newest cleared time); the confirm dialog says which will happen. Entries the bridge deleted keep no tombstone,
  since Android may reuse their ids.

**Block list.**
- **The bridge keeps the list that counts** (`block.list` / `block.set`, capability `block`), because it must work
  while the iPhone is away. It's a small JSON list in the bridge app's private preferences (excluded from backups);
  the iPhone keeps a copy in its database for the UI. Changes made in the bridge app's own screen (Settings ›
  Blocked numbers) reach the iPhone as a `block.list` event without an `id`. At most 500 entries (one 30 KB page).
- **What blocking does on the bridge:** no `sms.new` for texts from the number; its received texts are left out of
  `sms.threads` and `sms.history` (texts sent to it stay); its incoming calls get no `call.incoming` /
  `call.state` and are declined (companion `InCallService`, or `TelecomManager.endCall` with `ANSWER_PHONE_CALLS`;
  without either they just aren't shown); its incoming entries are left out of `calllog.sync`. Android's own
  messaging app and dialer still see them: only the default SMS app or the system's call screening can block those,
  and the texts say so. A call the iPhone already shows when the number arrives late is left alone, so the iPhone
  never has a ringing call that silently disappears.
- **Matching:** with 7+ digits on both sides, the trailing digits must agree, as many as the shorter number has, up
  to 9 (so a number saved with its country code matches its local form even in countries with 7-digit numbers);
  shorter numbers and sender names such as "BANK" match exactly. The existing 9-digit match key alone missed
  `+354 555 1234` vs `555 1234`. Both apps and `fake_bridge.py` implement the same rule and test the same cases.
- **Where to block on the iPhone:** a conversation's ⋯ menu, a call's context menu, a contact's page (all of its
  numbers), and Settings › Blocked numbers (add by hand, swipe to unblock). Blocking asks first; unblocking doesn't.
- History pages on the bridge skip a blocked sender's texts by reading on (up to 20 rounds of `limit + 200` rows)
  so a page is never empty while more remain; with no blocked numbers it's the same single query as before.

### D-051 — The website moves to getpairline.com; the iPhone app ships as 1.0.0 (2026-11-24)

The maintainer moved the website from `pairline.navid.tr` to **`getpairline.com`** (Coolify updated). Every link now
points there: the iPhone app (website, privacy, terms and download links, the help and pairing screens, the StoreKit
test file), the App Store listing (marketing, support and privacy URLs per language — Turkish now uses `/tr` pages —
and the install step in the descriptions), TestFlight test information, README, AGENTS.md and PRIVACY.md. Earlier
entries in this log keep the old name. The Android app links to no website, so it needs no release for this. The
apps still never contact the site (rule 2).

The iPhone app's version string becomes **1.0.0** (it was "1.0"); the unreleased App Store version was renamed to
match, and build 5 is the first 1.0.0 build.

### D-052 — The first App Store version is 1.0.1 (build 6); the demo is a fully set-up bridge (2026-11-24)

Supersedes the version part of D-051. Build 5 (1.0.0) was in App Review when two things turned up: demo mode showed
"Hear calls on iPhone", "Talk from iPhone" and internet sharing as not available, and the paywall screenshot (website
gallery, in-app purchase review screenshots) still had the old two-phones logo. Build 6 fixes the demo and was
uploaded as 1.0.1, because a build for an update after 1.0.0 can't carry 1.0.0. The maintainer chose to cancel build
5's review and submit build 6 instead, so the unreleased App Store version was renamed to **1.0.1** and goes to review
with build 6 and the two in-app purchases (7-day Trial, Full Access).

Demo mode now reports every capability: the Bridge tab ticks them all, an answered demo call shows "Audio on this
iPhone" (no audio is played), and Internet sharing shows sample proxy details. Before, the demo deliberately left call
audio out to show how a missing capability is explained; seeing everything ready is what people trying the app expect.

### D-053 — Bridge status as an always-on Live Activity (Lock Screen and Dynamic Island) (2026-11-24)

No app can change the iPhone's own status-bar signal bars, so the bridge SIM's signal goes where iOS allows it: a Live
Activity. The compact Dynamic Island shows the first SIM's bars and network type ("5G"), like the status bar, or a
crossed-out antenna while the bridge is unreachable; the expanded island and the Lock Screen add the bridge's name,
link state, battery and the second SIM. Tapping it opens the Bridge tab. The maintainer chose the always-on version
over one shown only during calls, and asked for an on/off switch: Settings › "Bridge status on Lock Screen", **on by
default**. It follows whichever bridge is on screen, so demo mode shows its sample bridge (marked "Demo").

iOS limits shape how it works: an app can start a Live Activity only while it's in the foreground unless a server sends
a push, and Pairline has no server (rule 2), so it starts when Pairline is opened; iOS ends every Live Activity after 8
hours at most, so each app open renews an activity older than 30 minutes and the 8 hours count from the latest open;
updates come from the app itself in the background as bridge status arrives over Bluetooth or Wi-Fi. When iOS has
ended it, it stays off until Pairline is next opened. The content is status only, the same as the widget (link state,
name, battery, carrier, network type, signal), and it is ended immediately when the switch is turned off or the bridge
is unpaired. It needs iOS 16.2; on 16.0 and 16.1 the switch isn't shown, and ActivityKit is weak-linked so those
versions still launch. Its relevance score is the lowest, so calls, timers and other apps take the Dynamic Island first.

Review risk, accepted by the maintainer: Apple's guidelines describe Live Activities as tasks with a start and an end; a
reviewer could object to a status display that's always there. If that happens, the fallback is to show it only
during calls (the same views, started when a call begins).

### D-054 — Pairline's own helper replaces Shizuku; BoringSSL for pairing with Wireless debugging (2026-11-24)

Supersedes the Shizuku parts of D-046 and changes AGENTS.md rules 2 and 7 (the maintainer's decision: "built-in
only"). Shizuku itself only connects to the phone's Wireless debugging and starts a process with shell rights;
Pairline Bridge now does the same from its own APK, so users install no second app and never "allow Pairline in
Shizuku".

- **Pairing (Android 11+), once:** the user turns on Developer options and Wireless debugging (Pairline only opens
  the screens; rule 5), taps Pair, then "Pair device with pairing code". Pairline finds the pairing service with
  mDNS and pops up a notification; the 6-digit code is typed into it (Android closes the code screen if the user
  leaves it). The protocol is adb's own: TLS 1.3, RFC 5705 keying material, SPAKE2 with the code, then each side's
  public key under AES-128-GCM with an HKDF key. Pairline's 2048-bit RSA key is stored encrypted with an Android
  Keystore key in no-backup storage; Android lists it as "Pairline" under Paired devices.
- **Starting:** Pairline connects to this phone's own adbd on 127.0.0.1 (TLS with its key) and runs one command:
  `app_process` from Pairline's APK as the shell user, detached with `setsid` (the shell waits a second so adbd
  doesn't end it, and prints a marker; Pairline retries if adbd dropped the command). `HelperMain` hosts the
  unchanged `PrivilegedService` and hands its binder to the app through `HelperProvider` (INTERACT_ACROSS_USERS_FULL,
  shell or root caller only). The four allowlisted operations and the caller-uid check are unchanged.
- **Lifecycle:** the helper runs while Talk on iPhone is on or its setup screen is open, and is stopped otherwise
  (when Pairline can start it again). It keeps running when Wireless debugging is turned off (so banking apps that
  refuse to open with it on still work) and re-attaches when Pairline's process restarts; it exits when Pairline is
  updated or removed, after 10 minutes without Pairline, and at every reboot. After a reboot Pairline notices
  Wireless debugging coming back on and starts the helper by itself; a once-per-boot notification asks for that.
- **Android 10** has no Wireless debugging: the user runs one `adb shell sh …/start-helper.sh` command from a
  computer after each restart (also offered on 11+ as "Start from a computer instead"). Android 9 stays unsupported.
- **Native code and its dependency:** SPAKE2 isn't in Android's crypto APIs, and Android's API for TLS keying
  material exists only from Android 12 (Android 11 blocks the platform method for apps, checked on the emulator).
  So pairing uses BoringSSL's SPAKE2 and TLS through a small JNI bridge (`src/main/cpp`); HKDF and AES-GCM stay in
  Kotlin with JCA, and connecting uses the platform TLS. BoringSSL comes as the NDK prefab package
  `io.github.vvb2060.ndk:boringssl` 20251124 (built from Google's BoringSSL; OpenSSL/ISC licenses, the same package
  Shizuku builds with). It is a crypto and TLS library with no network code of its own; Pairline's native code opens
  one connection, to 127.0.0.1. Rule 8 is about Pairline's own protocol; this follows adb's, using BoringSSL, not
  home-made crypto. The APK grows by about 1 MB per processor type. CI installs NDK 27.1.12297006 and CMake 3.22.1;
  the native build passes `-ffile-prefix-map` and no build ID, so builds stay reproducible.
- **Network fence:** `privileged/adb/` joins the allowed packages (local mDNS lookups for this phone's own
  `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp` services, and 127.0.0.1). PRIVACY.md lists the pairing key, the
  notification and the helper's rights.
- **Limits:** Wireless debugging needs this phone on a Wi-Fi network, so a phone that only shares its hotspot must
  start the helper while on Wi-Fi (it then keeps running until a reboot) or from a computer. Before Android 14 one
  NsdManager resolves one service at a time and stale announcements can block it, so each service is resolved on its
  own NsdManager. Verified on the Android 11 and 16 emulators; real phones (Samsung, Xiaomi) still to test.
- Removed: the Shizuku libraries, its provider and `<queries>` entry, and every Shizuku string; help and setup texts
  in EN/FA/AR/TR describe Wireless debugging instead.

### D-055 — The bridge's hotspot has no internet; Android's own hotspot is the way to share it (2026-11-24)

A user reported "the hotspot doesn't work: the iPhone says No Internet Connection". That is how Pairline's hotspot
(M5) works: Android's local-only hotspot links the two phones but lets no app route internet through it. An iPhone
with its own mobile data keeps using it; one without (common, since the SIM is in the Android phone) is offline while
joined. Nothing explained this. Now:
- The bridge's hotspot setting says what the iPhone will show, and a new row opens Android's own Hotspot & tethering
  screen (only opens it; rule 5): that hotspot shares the phone's internet, and the Wi-Fi link already listens on its
  interface. The bridge now rescans as soon as Android's hotspot or tethering changes (its broadcasts), not only every
  20 seconds.
- The iPhone explains it in a notice right after it joins the bridge's hotspot (long notices now stay up long enough
  to read), and both apps have a Help entry. Arabic texts quote iOS's Arabic wording.
- Not done: starting a tethering hotspot from Pairline (it would need the helper's shell rights for a system
  setting) and internet sharing (M8) over the local-only hotspot, whose random name means the iPhone's proxy setting
  would have to be entered again each time.

### D-056 — Talk on iPhone needs Android 11; a Stop switch on the bridge; audio failures explained (2026-11-25)

Testing on a Galaxy Note 3 running Android 10 and reports from a tester's Samsung showed:
- **Android 10 can't relay call audio.** Its shell user holds neither `CAPTURE_AUDIO_OUTPUT` nor `RECORD_AUDIO`, so
  the helper could start (from a computer, since Android 10 has no Wireless debugging) and still capture nothing.
  Talk on iPhone is now offered only on Android 11+; on Android 10 the bridge says why instead of a computer setup
  that ends in silence. This supersedes D-054's "computer command on Android 10". The computer command stays as an
  alternative for Android 11+ phones whose Wireless debugging can't be used. Both apps and the help texts say
  Android 11.
- **Crash when background activity is restricted.** Android takes the link service out of the foreground and then
  crashes the app if a later `startForegroundService` isn't answered with `startForeground`. Every start is now
  answered, and the home screen warns (with a Fix button to the app's settings) while background activity is
  restricted. Receivers for protected system broadcasts (battery, hotspot/tethering) are registered exported: on
  Android 12 and older AndroidX's not-exported flag adds a permission that system broadcasts don't carry.
- **"Moving audio to this iPhone…" then back to the Android phone.** The bridge accepted `call.media.start` but its
  capture failed. Some phones open only one of `VOICE_DOWNLINK` and `VOICE_CALL`, so the bridge now tries the other
  one before failing and remembers whichever opened. When it still fails, the iPhone shows an alert on the call
  screen with the bridge's reason and how to send a diagnostics report; before, a notice appeared behind the call
  screen, where nobody saw it.
- **Call audio from Android's own interception API, after the call's audio mode is set.** The iPhone asks for the
  audio as soon as a call it placed is dialing, when Android may not have switched to its in-call audio mode yet;
  capture then fails or records nothing. The bridge now waits (up to 4 s) for an in-call mode before capturing. On
  Android 13+, where the phone maker enables it (`isPstnCallAudioInterceptable`, the shell's
  `CALL_AUDIO_INTERCEPTION`), the helper uses Android's call-audio interception API
  (`getCallDownlinkExtractionAudioRecord` / `getCallUplinkInjectionAudioTrack`), which exists for this purpose; it
  refuses until the in-call mode is set, so the bridge asks again every 30 ms within the same 4 s. The older
  sources (`VOICE_DOWNLINK`, `VOICE_CALL`, the telephony output) remain the fallback, and the learned source now
  cycles through all three. Helper version 2 (a running version-1 helper is replaced).
- **The bridge's microphone stays on by default.** On several audio systems (some Samsung, Qualcomm and MediaTek
  phones) muting the phone's microphone also silences the voice fed into the call, so the caller heard nothing
  from the iPhone. The mute is now off unless set; the learning from D-047 still turns it off after "they couldn't
  hear me". This supersedes D-047's default.
- **The iPhone keeps the audio when its own audio engine is picky.** If the call-audio engine won't start with echo
  cancellation (voice processing), the iPhone tries a fresh engine without it for the rest of the call instead of
  handing the audio back to the Android phone. When iOS stops the engine for a route change (AirPods, car, speaker,
  CallKit), a fresh engine starts in the same session (at most 6 times per call). An iPhone-side failure gets its
  own alert (microphone permission) instead of the bridge one.

### D-057 — The iPhone app declares the `voip` background mode (2026-11-25)

A tester's diagnostics report showed CallKit refusing every incoming call ("CallKit didn't show the call",
`com.apple.CallKit.error`) and every call placed from the iPhone (`requesttransaction` errors), so calls never rang on
the iPhone. CallKit's generic error `CXErrorCodeMissingVoIPBackgroundMode` is what an app gets when `voip` is missing
from `UIBackgroundModes`, and Pairline had only `audio` and `bluetooth-central` (D-027 left `voip` out because there
was no PushKit and no call audio). Pairline now declares `voip`. It still uses no PushKit and no server (rule 2): calls
arrive over the link to the bridge phone, and iOS wakes the app for them through `bluetooth-central` as before. This
supersedes D-027's first point.
- If CallKit still declines a call (Focus) while Pairline is in the background, a local notification says who is
  calling and opens the in-app call screen; it's removed when the call ends.
- The diagnostics report redacted the error codes away: its number rule matched across line breaks (an error code,
  the line break and the next line's time stamp). Numbers no longer span lines, and the report keeps each line's time
  stamp.
- **Incoming calls while the iPhone is locked.** iOS keeps only Bluetooth links alive in the background, and doesn't
  relaunch an app the user swiped away. The iPhone's Bridge tab now says so when the link is over Wi-Fi only, and
  when the user swipes Pairline away (while iOS still runs it) a local notification says calls and messages are
  paused until Pairline is opened again; it's removed when Pairline opens.
- **Stop Pairline.** Users asked for a way to turn the bridge off without uninstalling it. Settings › Keep running ›
  Stop Pairline (after a confirmation) ends the link service, the Wi-Fi link, the bridge's hotspot, internet sharing
  and the helper, and keeps them off across reboots and updates (`bridge_stopped`). The home screen then says
  Pairline is stopped, with Start. Starting it again from Settings or the home screen, showing a pairing code or
  finishing setup clears the flag. No new data flow or permission.
