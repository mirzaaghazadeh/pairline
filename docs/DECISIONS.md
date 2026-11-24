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
