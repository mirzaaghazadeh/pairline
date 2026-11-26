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
(Superseded for remote mode by D-092: no relay; the user's own iCloud and a direct connection.)

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
- **Incoming calls while the iPhone is locked.** iOS keeps only Bluetooth links alive in the background, and doesn't
  relaunch an app the user swiped away. The iPhone's Bridge tab now says so when the link is over Wi-Fi only, and
  when the user swipes Pairline away (while iOS still runs it) a local notification says calls and messages are
  paused until Pairline is opened again; it's removed when Pairline opens.
- **Stop Pairline.** Users asked for a way to turn the bridge off without uninstalling it. Settings › Keep running ›
  Stop Pairline (after a confirmation) ends the link service, the Wi-Fi link, the bridge's hotspot, internet sharing
  and the helper, and keeps them off across reboots and updates (`bridge_stopped`). The home screen then says
  Pairline is stopped, with Start. Starting it again from Settings or the home screen, showing a pairing code or
  finishing setup clears the flag. No new data flow or permission.

### D-057 — The iPhone app declares the `voip` background mode (2026-11-25)

A tester's diagnostics report showed CallKit refusing every incoming call ("CallKit didn't show the call",
`com.apple.CallKit.error`) and every call placed from the iPhone (`requesttransaction` error 1, "unentitled"), so
calls never rang on the iPhone. CallKit's generic error `CXErrorCodeMissingVoIPBackgroundMode` is what an app gets
when `voip` is missing from `UIBackgroundModes`, and Pairline had only `audio` and `bluetooth-central` (D-027 left
`voip` out because there was no PushKit and no call audio). Pairline now declares `voip`. It still uses no PushKit and
no server (rule 2): calls arrive over the link to the bridge phone, and iOS wakes the app for them through
`bluetooth-central` as before. This supersedes D-027's first point. Confirmed by the same tester: with build 12 the
`requesttransaction` errors stopped.
- If CallKit still declines a call (Focus) while Pairline is in the background, a local notification says who is
  calling and opens the in-app call screen; it's removed when the call ends.
- The diagnostics report redacted the error codes away: its number rule matched across line breaks (an error code,
  the line break and the next line's time stamp). Numbers no longer span lines, and the report keeps each line's time
  stamp.

### D-058 — Internet sharing keeps its address; the iPhone checks its Wi-Fi proxy (2026-11-25)

Testers reported "no internet" with internet sharing. The iPhone uses it through a proxy configuration URL pasted
into the Wi-Fi network's settings, and the bridge made a new port and new credentials at every start, so the pasted URL
(and Telegram's proxy) stopped working each time sharing was turned on again, and a URL left in place after sharing
stopped meant no internet at all on that network.
- The bridge's proxy uses port 47830 (another one only if that's taken) and keeps its credentials from one start to
  the next; switching internet sharing off in the bridge app retires them.
- The iPhone reads the current Wi-Fi's proxy setting (locally, `CFNetworkCopySystemProxySettings`) and says whether it
  uses the bridge, isn't set yet, points at an old address, or still points at the bridge while sharing is off (also
  on the Bridge tab, where "no internet" is noticed first).
- Not done: setting the proxy automatically. That needs a VPN configuration on the iPhone, which App Review allows
  only from organization developer accounts.

### D-059 — Pairline turns the debugging settings back on for Talk on iPhone (2026-11-25)

The maintainer's decision, replacing rule 5's "never re-enable USB or Wireless debugging": testers kept losing Talk on
iPhone because Android switches Wireless debugging off at every restart and whenever Wi-Fi drops, and the helper can
only start while it's on.
- Once the helper runs, it grants Pairline itself `WRITE_SECURE_SETTINGS` (`cmd package grant`, a development
  permission the shell may grant; Pairline's own package only). While Talk on iPhone is on and the helper isn't
  running, Pairline turns Developer options, USB debugging and Wireless debugging back on (Wireless debugging only on
  a Wi-Fi network, where it can run), then starts the helper as before. If it keeps going off (e.g. the user declines
  Android's "allow on this network" prompt) the retries back off from 15 s to about half an hour.
- It also switches off Android's 7-day expiry of debugging authorizations, which would otherwise make the user pair
  again after a long uptime.
- Turning Talk on iPhone off or "Forget pairing" returns the permission (`cmd package revoke`) and ends all of this.
  No other setting is ever changed. AGENTS.md rules 5 and 7 and PRIVACY.md say so.

### D-060 — Call audio and link reliability fixes from tester reports (2026-11-25)

- **The helper states its identity to the audio server.** Started with `app_process`, the helper builds its contexts
  from the system context, whose app-ops package isn't the shell's; recording then fails to start ("call audio
  capture didn't start", seen on a tester's Android 15 Samsung). The helper now uses a context that says
  `com.android.shell` and its uid (package, app-ops package, attribution) and an AudioManager made for it.
- **Pairing from the notification runs in the link's foreground service,** not in the notification's broadcast
  receiver, which Android may end (with the app) while pairing still runs.
- **This phone's earpiece is silent during a relayed call** (voice-call volume 0 as the shell user, restored when the
  relay ends or Pairline's process dies). If the iPhone then can't hear the caller, the next call leaves it on.
- **The bridge holds an LE link to the iPhone during a session.** With the phones also paired over classic Bluetooth
  (e.g. for Bluetooth tethering), iOS may run the session over the classic link and end it when it moves to LE,
  again and again ("the bridge's service went away", seen in a tester's log).
- **No speech cut by the jitter buffers.** Both directions keep up to 1 s and bring extra delay down by skipping quiet
  frames only, instead of dropping up to 180 ms at once after a Wi-Fi stall.
- **iPhone:** the in-app call screen turns off at the ear while the audio is on the earpiece, and a dial the bridge
  accepted but never reported ends after 30 s.
- **Call-audio failures list every source tried** (with Android's audio mode), so one report from the iPhone says why.

### D-061 — One hotspot to explain: the phone's own; Pairline's moves under a fold (2026-11-25)

Testers found the hotspot setup hard. One had both on at once: Pairline's local-only hotspot (`AndroidShare_7069`) and
the phone's own (`sayad's Z Fold3`), and the iPhone showed "No Internet Connection" on both. The maintainer chose to
keep Pairline's hotspot as a fallback, out of the way:
- **Bridge, Settings › Wi-Fi link:** "This phone's hotspot" is the main row. It opens Android's hotspot settings (only
  opens them; rule 5) and says, from Android's sticky tethering broadcast and the phone's networks (read locally): off
  (how to use it), on and sharing, on without internet (no mobile data), or on with a VPN, which Android routes hotspot
  traffic around, so the iPhone gets no VPN, or no internet where only the VPN gets through; Internet sharing (M8) is
  the way to use the VPN. With Talk on iPhone on and its helper not running while the phone isn't on Wi-Fi, it adds
  that the helper starts only on Wi-Fi (D-054). The status row says "Ready on this phone's hotspot" when that's where
  the link listens.
- **Pairline's own hotspot** sits behind a folded row, "Pairline's own hotspot (no internet)", for carriers that block
  tethering; it opens by itself while that hotspot runs or failed.
- **iPhone:** it removes the bridge hotspots it saved in iOS's known networks (only those it added) when the bridge
  offers no hotspot or another one, and on unpair, so old `AndroidShare_…` networks don't pile up and iOS doesn't
  rejoin one without internet. The help answer leads with the phone's own hotspot.
- Not done: starting or reading the phone's own hotspot (name, password) from Pairline. It needs system permissions,
  and the helper, which could, runs only after the phone was on Wi-Fi.

### D-062 — Choosing the SIM for calls and messages, where people look for it (2026-11-25)

The maintainer asked for a setting to choose which SIM calls and messages use. The iPhone had one, but only as two
pickers inside Settings › Calls ("Bridge default", SIM 1, SIM 2), so nobody found it. Now:
- **A "SIM cards" section in iPhone Settings,** shown once the bridge has reported its SIMs: "SIM for calls" and "SIM
  for messages", each "Same as the Android phone (SIM n · carrier)" or a SIM. With one SIM it's greyed out and says
  why. The Bridge tab links to the same choice under its SIM cards when there are two.
- **It's Pairline's own choice, kept on the iPhone.** Android's default SIMs don't change: changing them needs a
  system permission (the helper could, but it runs only for Talk on iPhone), and rule 5 keeps Pairline away from
  system settings. Where Android asks every time, the bridge already uses the first active SIM, and the iPhone now says
  which.
- **The Bridge tab's "Calls" and "Messages" tags** mark the SIM this iPhone's calls and messages go out on, not only
  Android's default. One call or message can still use the other SIM (the dialer and composer pickers start on it).

### D-063 — iPhone Duo: two panes on the big display, nothing to tap on the fold (2026-11-25)

The maintainer asked whether the iPhone app follows Apple's iPhone Duo design (HIG "Designing for iPhone Duo") and to
fix what doesn't, using their `iphone-duo-skills` (installed for this checkout only, under `.claude/`, not committed).
The app is built with the iOS 27.2 SDK, so on iPhone Duo it already fills both displays and its bars run down the side;
`TabView`, `NavigationStack`, lists, sheets and alerts adapt by themselves. What didn't:
- **Call screen, keypad and onboarding** centred their buttons, so on the open display, folded like a book, the End
  button, the keys and Continue sat in the fold. On a large regular-width display (iPhone Duo open, iPad) they now use
  two panes (`FoldAwarePanes`): caller | call buttons, number | keys, artwork | words and buttons. The gap sits on the
  fold's division region (iOS 27.1 `reservedRegions`, read also while the device lies flat, so nothing moves when it
  bends), or in the middle without one. Held upright, the call screen and keypad put the content above and the
  controls below (laptop pose), and the keys shrink to fit. On a phone the layouts are unchanged. The call screen with
  its keypad open also no longer overflows a short landscape screen.
- **Messages** was one list stretched across the display. On a large display it now shows the conversations and the
  open conversation side by side (`NavigationSplitView`): the same hierarchy with one more level showing. The open
  conversation stays open when the device opens or closes, and unsent text is kept per conversation, in memory only.
- **Toolbar items** carry a title as well as a symbol, for overflow menus and the side bar. Text items (Edit/Done,
  Delete) no longer share a slot with a symbol item, so nothing jumps between the top bar and the side bar. Call
  history's one-item "More" menu is now a "Clear All Calls" button (it still asks first).
- **The ongoing-call bar** sat a fixed 56 pt above the bottom, to clear a bottom tab bar. It now sits in each tab's safe
  area, so it clears the tab bar wherever it is (on the side on iPhone Duo), and lists and the composer stay above it.
- **Onboarding's pairing** opens as a sheet on a large display, since sheets keep off the fold by themselves.
- Not changed: the QR scanner (VisionKit turns it with the device); call history, contacts, Bridge and Settings stay
  single lists, which may scroll across the fold. `ArrangementView` (iOS 27.1) wasn't used, because that path can't run
  on the iOS 27.0 simulator here; `FoldAwarePanes` reads the same fold region and also works on iPad and iOS 16.
- Checked on iPhone and iPad mini simulators, with renders of each screen at the iPhone Duo display sizes (fold band
  clear of every control) and unit tests of the split. Not yet seen on screen: the ongoing-call bar in its new place,
  onboarding's two panes, and right-to-left two-pane layouts. Not yet seen on the iPhone Duo simulator, which needs the
  iOS 27.2 simulator runtime: check every pose there (closed, open, book, laptop, Split View on both sides) before the
  next TestFlight build.

### D-064 — Pairing Talk on iPhone, made easier from a walk-through on a tester's phone (2026-11-25)

The maintainer asked to find where a normal user gets stuck. Talk on iPhone's setup was run, remotely, on a tester's
Daria phone (Android 15) as a first-time user would, twice. Found and fixed:
- **"Pair" opened the long Developer options list,** with Wireless debugging highlighted near the bottom; the user had
  to tap its name (its switch turns it off) and then find "Pair device with pairing code". It now opens the Wireless
  debugging page itself, through the intent the Settings app answers for its Quick Settings tile's long-press
  (`QS_TILE_PREFERENCES` with the WirelessDebugging tile's component), on Android 11+; where that fails it falls back
  to the highlighted list, then About phone. Opens only; the user still turns things on (rule 5).
- **The code notification was gone before people looked up from the code.** A pop-up lasts about 5 seconds. While
  Pairline waits for the code it now pops up again every 8 seconds, at most four times, from a silent high-priority
  channel. Android pops up an update only when its text changed, so the reminders alternate the wording ("Still
  waiting for the code…"). Checked: Android holds updates back while the code is being typed, so typed digits stay.
- **In the shade, Android folded it under the link's notification,** hiding "Enter code" behind two taps. It now has
  its own notification group.
- **"Forget pairing" acted at once;** undoing it means pairing again, so it asks first.
- Seen in the same session and not changed here: with the iPhone app in the background, each Bluetooth wake-up
  reconnects Wi-Fi and iOS drops it again within about 30 seconds (wasted battery; a call wakes the app properly).

### D-065 — Call audio was silent both ways on a tester's phones: three causes (2026-11-25)

Test calls between a tester's Daria (Android 15) and iPhone 14 Pro Max, with both phones' logs read live:
- **Android: the helper's recorders took the app's identity.** AudioRecord and AudioTrack made while a binder call is
  handled take the caller's uid and pid as their client (native `AudioRecord::set`). The helper made them inside the
  app's call, so the audio server saw uid 2000 claiming 10367, refused it ("tried to pass itself off as …") and
  interception never started ("startInput failed"). The helper now makes them without the caller's identity
  (`Binder.clearCallingIdentity`). Helper version 4 replaces a running older helper, which survives app updates.
- **Android: a failed interception was remembered for good.** The relay then always started on the fallback source,
  which carried no usable audio there. Interception is asked first on every call now (a failure is per call), the
  fallback is remembered only between the other sources, and what was learned before these fixes is reset once
  (`audioLearningEpoch`).
- **iPhone: the call audio engine stopped 0.15 s after it started and never ran again.** Turning echo cancellation on
  changes the audio configuration, and iOS stops the engine. Build 13 ignored changes in the first second; then a fresh
  engine per change switched echo cancellation on again and stopped again, six times. Now, once changes settle
  (0.3 s), the same engine is restarted for the new formats (tap and player rebuilt, echo cancellation left on), only
  if iOS really stopped it, at most six times a call.
- **iPhone: Speaker while the audio was still on its way to the iPhone went to the Android phone,** which played the
  caller on its loudspeaker. Speaker now always means the iPhone's own once the relay has been asked for.
- Also seen: a call whose far end hung up stayed up on both phones; Android itself had it active until the iPhone
  ended it ("LOCAL"). The network never ended it; Pairline follows Android's call state.

### D-066 — Ring only on the iPhone (2026-11-25)

The maintainer asked that the Android phone not ring while the iPhone is connected. New setting in the bridge,
Settings › "Ring only on the iPhone", on by default: when an incoming call that reaches the iPhone (not a blocked
number) starts ringing while the iPhone is linked, the helper calls `TelecomManager.silenceRinger()` (the shell user
holds MODIFY_PHONE_STATE), right away and twice more within a second, because Telecom silences only a ring that has
started. It's the volume key's silence, not a setting change: the call still rings on the iPhone and can be answered
on either phone. It needs Talk on iPhone's helper (version 5 adds the call); without it both phones ring, and the
setting says so. PRIVACY.md lists it with the helper's rights.

### D-067 — Caller shown, numbers written with +, and Ring only on the iPhone made to work (2026-11-25)

From the next round of test calls on the tester's phones:
- **Ring only on the iPhone didn't silence anything.** Telecom checks the calling package against the calling uid, and
  the helper's TelecomManager came from the system context, which names the wrong package, so `silenceRinger` was
  refused and the refusal swallowed. The helper now makes its TelecomManager for the shell identity, as it does its
  AudioManager (D-060), and a failure reaches the app's log. Helper version 6.
- **Incoming calls showed "Unknown" on the iPhone.** Android 12+'s call-state callback carries no number, so a call it
  saw first was announced without one; the companion call service brings the number a moment later, but the bridge
  only sent an update when the call's state changed. It now sends the number and name as soon as they arrive, and the
  iPhone updates its call screen (it already handled a late number).
- **Numbers written 0098… are shown as +98….** "00" is the international prefix in Iran, Turkey and most countries;
  the iPhone writes it as "+" for a call's number (incoming, outgoing and calls placed on the Android phone). Same
  number, dialed the same; contacts still match on the last nine digits.
- The relay's learned settings are reset once more: on the tester's phone the post-call question had been answered
  "they couldn't hear me" twice during the broken builds, which turns talking off.

### D-068 — The phone's own hotspot, tested away from Wi-Fi (2026-11-25)

On the tester's Daria (Android 15): Wi-Fi off, mobile data on, Android's own hotspot on; the iPhone (its own mobile
data off) joined it. The iPhone had internet through it (no "No Internet Connection", websites load), Pairline linked
over it by itself, calls relayed both ways over it, Ring only on the iPhone worked, and Talk on iPhone's helper kept
running after Wireless debugging went off with the Wi-Fi. One fix: Android 15 lists the hotspot itself as a Wi-Fi
network (with LOCAL_NETWORK), so "This phone's hotspot" took the phone for being on Wi-Fi and the status row said
"Ready on this phone's Wi-Fi network"; such networks no longer count as Wi-Fi.

### D-069 — No payment before pairing: pair, one free sync, then the free week (2026-11-25)

The maintainer: showing the trial and the price on the first screen loses people; they should pair and see their own
messages first. The rules of D-036 stay (a free week through the free "7-day Trial" item, then $9.99 once); what
changes is when they're offered:
- **The start screen has no price:** "Pair your Android phone" and "Explore the demo", with Restore Purchases, Privacy
  and Terms.
- **Pairing works before a trial, for one free first sync.** After pairing, the link stays up until the bridge's
  messages, contacts and call log have synced once; then the iPhone closes it and shows the paywall (Start 7-day free
  trial, Buy now, the trial's terms, and a close button for later). What synced stays readable, as after a trial.
  Nothing else runs in that window: sending, calls (an incoming call isn't brought to the iPhone), USSD, files and the
  rest still ask for the trial, and nothing syncs again until the trial starts or Pairline is bought. The banner
  "Start your free week to connect" stays until then.
- **Only once:** a flag in the Keychain (next to the trial clock) marks the free sync as used, so unpairing and pairing
  again, or reinstalling, doesn't give another one; the pairing screen then offers the free week or the purchase before
  scanning, with "Later" to skip.
- **Why Apple accepts it:** guideline 3.1.1 asks a paid-once app's time-limited trial to go through a free
  non-consumable "XX-day Trial" item, with its length, what stops and the price shown before it starts. That's
  unchanged. The free first sync isn't time-based and doesn't run out; it's a small free part of the app, which 3.1.1
  allows. The user can always close the offer and keep looking around, and the demo stays free (D-020).
- App Store screenshots: the start screen no longer shows prices, so the "Pay once" slide shows the paywall instead.
  `-PairlinePairingScan` (debug builds) opens pairing at its scan step.

### D-070 — iPhone Duo, every screen looked at open: each tab in two parts (2026-11-26)

Seen on the iPhone Duo simulator (iOS 27.2) for the first time, D-063's layouts weren't enough: the call screen's
buttons sat in the bottom corner of an empty half, and Contacts, Calls, Bridge and Settings were one phone list
stretched across the display with the fold through it. The maintainer asked for a full pass. Each screen was then
captured open, in English and Arabic, and checked:
- **Messages, Contacts, Settings:** the system split view with its own widths: the list stays narrow, the conversation,
  contact or settings page gets the rest (on iPhone Duo the fold runs through it, which is fine for reading while the
  device lies flat), and only that column gets the vertical side bar. Contacts and Settings open their first item
  (a favorite; About) instead of an empty half. A list that ends exactly at the fold was tried and dropped: iOS either
  folds such a list away, or (two side-by-side navigation stacks) keeps an empty 84 pt strip for a side bar in each.
- **Calls:** recent calls and the keypad side by side in one navigation stack, split at the fold so no key sits on it;
  the title on the leading side. The keypad shrinks its keys to fit any short space.
- **Bridge:** two columns at the fold: the phone, battery and SIM cards; what it can do and the quick actions.
- **Call screen:** caller and buttons each centred in their half, level with each other.
- **Paywall:** full screen on a large display, the offer on one half and the price, buttons and terms on the other
  (above each other on an upright iPad), so no button sits on the fold; still a sheet on a phone.
- iPad gets the same layouts (split views, two-column Bridge, side-by-side Calls in landscape, full-screen paywall).
  The App Store iPhone Duo slides lost the drawn fold line.

### D-071 — Licensor named; no trademark filing (2026-11-26)

- **Licensor:** Navid Mirzaaghazadeh, in `LICENSE.md`'s notice. The repo is already public, and the licensor named
  there is who a store takedown of a copy (a competing use under FSL-1.1) is filed for.
- **No trademark filing,** the maintainer's decision. This replaces D-001's "do a proper trademark search before
  launch". The name and logo stay outside the license (its Trademarks clause, README), so a copy may not use them.

### D-072 — Call audio over Bluetooth, as Opus inside the Bluetooth session (2026-11-26)

Talk on iPhone needed the Wi-Fi link for its audio (D-047), so away from a shared Wi-Fi (in the street, in a car) the
call's audio stayed on the Android phone unless the user turned its hotspot on. The maintainer asked for audio over
Bluetooth, with a setting, and Bluetooth as the default if it works well.
- **Setting:** iPhone Settings › Call audio: **Bluetooth** (default) or **Wi-Fi**. The choice is what a call tries
  first; when it isn't up (no Bluetooth session, a bridge without `call.media.link`, no Wi-Fi link) the other is
  used. Wi-Fi keeps uncompressed PCM (D-047).
- **Codec:** Opus, 16 kHz mono, 20 ms frames, the VoIP application, 32 kbit/s: wideband speech like an HD-voice
  call, about 80 bytes a frame, so a frame fits one BLE fragment (raw PCM is 640 bytes and 32 KB/s each way, more than
  BLE carries reliably). Each platform's own codec: Android's `MediaCodec` (its Opus encoder came with Android 10;
  Talk on iPhone needs 11+) and iOS's AudioToolbox (`kAudioFormatOpus`). **No new dependency**, nothing that could
  reach a network. The bridge lists `call.media.link` only when it finds both an encoder and a decoder.
- **Wire (PROTOCOL.md "Call audio"):** a new `MEDIA` frame (`0x22`) on the Bluetooth (or debug TCP) session, channel
  3, with keys of its own (`"pairline v0 link media"`), so the two media paths never share a key and nonce. Ordered
  like the control channel: a replayed or forged record closes the connection. One record holds one or more packets,
  each with a 2-byte length, so a sender that fell behind catches up in one frame. A new packet kind `0x02` (Opus).
  `call.media.start` takes `via: "link"`. No version bump: like `BULK`, nothing is sent until the bridge accepted a
  request an older app never makes. New vectors (`link_media`) on both sides.
- **Bluetooth behaviour:** at each frame boundary the audio goes before frames still waiting (a 30 KB sync page can't
  hold up the voice; fragments of two frames still never interleave); audio that waited over 200 ms is dropped
  rather than played late; during the call the bridge asks Android for high connection priority and the 2M PHY
  through the LE link it already holds (`BleTransport.holdLeLink`), and returns to balanced after. Jitter buffers hold
  80 ms on Bluetooth (deliveries come in bursts), 60 ms on Wi-Fi as before.
- **Expected:** a short gap now and then when Bluetooth stalls, filled by the jitter buffer's concealment. Bluetooth
  tethering between the same two phones shares the radio and can cause longer stalls; Wi-Fi stays available for that.
- **Tested:** unit tests and vectors on both sides, the iPhone's Opus encoder and decoder round trip, and the
  Simulator against `fake_bridge.py` over the debug TCP link, which echoes `MEDIA` frames: with `-PairlineLinkMediaTest`
  (debug builds) the app sent 50 Opus frames of a tone (32 kbit/s, 71 bytes on average), got all 50 back and decoded
  them at the tone's level. Android's `MediaCodec` Opus and the Bluetooth path itself need real phones next.

### D-073 — The Android phone's hotspot, Bluetooth tethering, mobile data and data SIM, switched from the iPhone (2026-11-26)

Away from Wi-Fi, the iPhone gets internet through the Android phone's hotspot or Bluetooth tethering, but the user had
to take the Android phone out to turn them on: Pairline only opened Android's hotspot settings (D-061, rule 5). The
maintainer asked for switches on the iPhone, and for Bluetooth tethering to be shown and explained.
- **Rule 5 and 7, amended by the maintainer:** a switch the user flips on the iPhone is the user asking. While Talk on
  iPhone's helper runs, the Android phone's hotspot, Bluetooth tethering, mobile data and data SIM change when, and
  only when, the user flips that switch. AGENTS.md and PRIVACY.md say so.
- **How:** three helper operations (helper version 7), each on the allowlist with its arguments checked:
  `setTethering(type, on)` with Android's own `TetheringManager` (a system API, reached by reflection as the shell
  user, which holds `TETHER_PRIVILEGED`; Android's carrier entitlement check still runs), only the Wi-Fi hotspot and
  Bluetooth kinds; `setMobileData(on)` with Android's own `svc data` (or `cmd phone data`); and
  `setDataSubscription(subId)` (`SubscriptionManager.setDefaultDataSubId`, `MODIFY_PHONE_STATE`), only an active SIM.
  Android 11 or later (the helper's own minimum), with no new permission in the app's manifest.
- **Wire:** `status.net` (`hotspot`, `bluetooth`, `data`), `sims[].defaultData`, and the `net.switch` request
  (`what`, `on` or `sim`), under the capability `net.switch` (listed while the helper runs). No version bump: older
  apps ignore the new fields and never send the request. Turning a hotspot off is answered before it stops, since the
  iPhone may be on it. Pairline's own local-only hotspot gives way when the phone's hotspot is turned on.
- **Reading** needs no privilege: Android's tethering broadcast (`bt-pan` is Bluetooth tethering; Android 11 and 12
  list it only once a device has joined, so there it shows as on from when Pairline turned it on), and the data SIM's
  `isDataEnabled`. The bridge's own Settings › This phone's hotspot also says when Bluetooth tethering is on and how
  the iPhone joins it (Settings › Bluetooth, pair once), and that the iPhone can switch these.
- **iPhone:** Settings › Internet sharing starts with an "Android phone" section: Hotspot, Bluetooth tethering, Mobile
  data and, with two SIMs, Data SIM. Without the capability the switches show the state, disabled, with what's
  needed. A help answer explains the switches and joining over Wi-Fi or Bluetooth.
- **Left out on purpose:** airplane mode, which would cut the Bluetooth link the switch travels on, so the iPhone
  couldn't turn it back off; and the preferred network type, which Android exposes per carrier in ways that vary too
  much to switch safely without a real phone to test on.
- **Tested:** unit tests on both sides (requests, errors, caps, status decoding), `fake_bridge.py` answers
  `net.switch`, and the iPhone screen in English and Persian on the Simulator in demo mode. The helper's tethering,
  mobile-data and data-SIM calls need a real phone next.

### D-074 — Calls from Recents, Siri and links; reply, read sync and mute; hold and call waiting; Find phone; contact edits; a setup guide instead of videos (2026-11-26)

The maintainer asked to finish what users expect of a phone app, to make a Pairline call in the iPhone's Recents work,
to show calls in Recents by default, and to drop the setup videos for guidance inside the app.
- **Recents:** tapping a Pairline call in the Phone app's Recents did nothing: iOS hands the app an
  `INStartCallIntent`, which Pairline didn't take (`NSUserActivityTypes` and `onContinueUserActivity` now do; the call
  is placed once the bridge is linked, after a cold start). "Show calls in iPhone Recents" is now **on by default**
  (the maintainer's decision; ARCHITECTURE.md had it off because Recents can sync to iCloud, which the setting says).
  A user who turned it off keeps it off.
- **Siri and Shortcuts:** "Call with Pairline", an App Intent (text shared into a Shortcut works: its first phone
  number is called). `pairline://call?number=…` links only fill in the keypad: any page could open one. Pairline as
  iOS's default calling app (iOS 18.2+) needs an entitlement from Apple; not requested.
- **Messages:** reply from the notification (sent through the bridge like a typed message, queued if the bridge is
  away); mute a conversation on the iPhone (no banner or sound; one-time codes still notify); read sync: `sms.read`
  marks a conversation's received messages read on Android. Only the default messaging app may write Android's SMS
  store, so Talk on iPhone's helper gives Pairline its own WRITE_SMS app-op (helper version 8, Pairline's package only,
  returned with "Forget pairing"); capability `sms.read`. Whether the default messaging app shows the change depends on
  that app.
- **Calls:** `call.hold` (the companion call service's `Call.hold`/`unhold`), CallKit's Hold button and an in-app one;
  call waiting: a second call rings in CallKit (two call groups) and on the call screen; answering it puts the first on
  hold (Android does), Swap brings the held one back, and either can be ended. An Audio button (iOS's route picker)
  moves the call's audio on the iPhone to a headset or the car.
- **Find phone** (`phone.find`): the Android phone rings on the alarm stream at full volume for up to a minute, even
  on silent, with a Stop button in its notification; the alarm volume is put back. **Ringer** (`phone.ringer`, status
  `ringer`): sound, vibrate or silent from the iPhone. Silent is Do Not Disturb to Android: it needs Do Not Disturb
  access, which only the user grants (`ACCESS_NOTIFICATION_POLICY` declared so Pairline is listed; the bridge shows a
  notification that opens the setting when it's missing).
- **Contacts:** `contacts.update` (name, numbers, favourite), under the same opt-in as adding (`contacts.edit`).
- **Setup guide instead of videos:** when a step opens Android Settings (Developer options, Wireless debugging, the
  maker's battery settings), its exact instructions wait in a notification to pull down there, with a tap back; it
  clears when Pairline is on screen again. The videos item in the roadmap is closed by this.
- **Not done:** the preferred network type (D-073); Pairline as iOS's default calling app.
- **Tested:** unit tests on both sides (caps, requests, status, number detection), `fake_bridge.py` answers the new
  requests, both apps build and lint clean. CallKit, Recents, Siri, hold and call waiting, read sync and the ringer
  need real phones.

### D-075 — Root mode under Settings › Advanced; restricted settings guided (2026-11-26)

- **Root mode,** the maintainer's choice, kept out of the way under a new Settings › Advanced: on a rooted phone
  (Magisk, KernelSU, APatch), Talk on iPhone's helper starts with `su 2000 -c <the same start command>` instead of
  through Wireless debugging. It runs as the shell user, exactly as before, never as root: the allowlist, the caller
  check and everything the helper may do stay the same (rule 7). No Developer options, no pairing, and it starts
  again by itself after a restart without Wi-Fi. The switch is offered only when a `su` binary is found (checked by
  looking for the file, so no root prompt until the user turns it on); the root app asks once. If `su` is missing or
  refused, Talk on iPhone says so and offers Wireless debugging again.
- **"App was denied access" (restricted settings):** Android 13 and later block SMS and call-history permissions for
  apps installed from a downloaded or local file until the user allows restricted settings. A tester hit it. When
  Android refuses one of those permissions and this copy came from a file (`InstallSourceInfo.packageSource`, read
  locally), the bridge opens App info and leaves the steps in the setup guide's notification (⋮ › Allow restricted
  settings, then Permissions › SMS), instead of leaving the user at Android's dialog.

### D-076 — The iPad app on Apple Silicon Macs (2026-11-26)

The maintainer asked for a Mac app; the iPad app runs on Apple Silicon Macs as it is, so no separate app.
- `SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD` is now YES (it was NO without a recorded reason); the app builds for "My Mac
  (Designed for iPad)". Both TestFlight groups already offer iOS builds on Apple Silicon Macs
  (`iosBuildsAvailableForAppleSiliconMac`). The App Store side is the "iPhone and iPad Apps on Apple Silicon Macs"
  setting in App Store Connect's Pricing and Availability, which the API doesn't expose.
- On a Mac: the Bluetooth link works as on iPad; CallKit's call screen isn't there, so calls use Pairline's own call
  screen (the existing fallback); pairing scans the Android screen with the Mac's camera (the AVFoundation scanner,
  since VisionKit's isn't on Mac), with a hint to hold the phone up to it.
- Not tested on a Mac yet.

### D-077 — Notifications from chosen Android apps, on the iPhone (2026-11-26)

- **Opt-in twice:** the user gives Pairline Bridge notification access (Android's own screen; for an app installed
  from a file that's a restricted setting first, and the setup guide says how), then chooses apps in Bridge › Settings
  › Notifications from apps. None are chosen at first. On the iPhone, Settings › Notifications › "Notifications from
  Android apps" (on) can hide them all.
- **What's sent:** `notif.posted` with the app's name, the title and up to 1,000 characters of text, over the
  encrypted link only (rule 1), never logged; a later post with the same key replaces it. `notif.removed` when it
  goes away on Android, `notif.dismiss` when the user swipes it away on the iPhone (iOS's custom dismiss action), so
  both phones agree. Ongoing notifications (music, navigation, downloads, foreground services), group summaries, calls
  and Pairline's own are left out. Nothing is sent while the iPhone isn't linked; nothing is queued.
- **Listing apps:** `<queries>` for home-screen apps (MAIN/LAUNCHER) instead of `QUERY_ALL_PACKAGES`.
- **iPhone:** a local notification per mirrored one, grouped by app, title and app name, text unless "Show message
  text" is off.
- **Tested:** unit tests (caps, decoding), both apps build and lint clean; needs a real phone.

### D-078 — Restart Bluetooth when stuck (opt-in, Settings › Advanced) (2026-11-26)

- Android's Bluetooth stack sometimes stops advertising until Bluetooth is turned off and on. With "Restart Bluetooth
  when stuck" on (off by default) and Talk on iPhone's helper running, three advertising failures within ten minutes
  while the iPhone isn't linked make the helper run `cmd bluetooth_manager disable`/`enable` (`svc bluetooth` before
  Android 12), at most once an hour. Failures during a connection don't count: some chips can't advertise while
  connected. Helper version 9; rule 5 lists it as a setting changed only with the user's opt-in.
- The "Did you hear it?" test after a relayed call is in place on the iPhone (`call.media.feedback`, the bridge picks
  the capture source and mic handling); the roadmap item is closed except for a shipped list of known devices, which
  needs real-device results first.

### D-079 — Export messages; the trial purchase on testers' iPhones (2026-11-26)

- **Export messages** (iPhone Settings › Help › Export messages): every conversation on the iPhone as a readable
  text file and a CSV, made on the device (complete file protection), handed to the share sheet only when the user
  taps one, and deleted when the screen closes. An encrypted backup format isn't added: the messages stay in
  Android's own SMS store, which is the copy that matters, and a new format would need its own crypto spec (rule 8).
- **Trial purchase on TestFlight:** on one tester's iPhone Apple's purchase sheet stayed up after "Get"; on another
  StoreKit failed with "SKInternalErrorDomain error 14" (no public meaning; reported elsewhere as a StoreKit system
  error when the account can't complete it). The purchase now names the scene on screen
  (`Product.purchase(confirmIn:)`, iOS 17+) so the sheet attaches to Pairline's window; failures log their domain,
  code and underlying error to diagnostics and tell the user what to check (signed in under Media & Purchases,
  in-app purchases allowed in Screen Time, restart).

### D-080 — Every Android release lists what's new and its contributors (2026-11-26)

The maintainer asked for a changelog and a contributors list on every release. Only 1.1.1 to 1.3.1 had a "What's new",
written by hand into the published notes; the others had only the build table.
- **`android/CHANGELOG.md`** holds each version's notes, written for the people who use the app, newest first. It's the
  one source: the release workflow copies from it, and a tag whose version has no section fails before the build.
- **What's new** in a release covers every version since the newest *published* release older than it, one heading
  each, so 1.7.0 published after the 1.4.0 to 1.6.0 drafts still lists everything people get.
- **Contributors** are the GitHub accounts (a name when a commit has no account) behind the commits in that range that
  touch `android/` or `protocol/`, most commits first, bots left out. Mentions make GitHub show the avatars.
- `.github/scripts/release-notes.sh` builds both parts with git and `gh` (no new dependency); the workflow checks out
  the full history for it. Every existing release, 1.0.0 to 1.7.0, got the same notes from it.

### D-081 — The first language comes from the phone's country (2026-11-26)

The maintainer asked both apps to open in the right language the first time, from the carrier or time zone, and to
show the language on first open. Only fresh installs: anyone who already set Pairline up keeps their language.
- **Android:** before setup starts, Pairline reads the SIM card's country, else the mobile network's, else the time
  zone's region (ICU), else the region setting; the first one known decides (a US SIM keeps English even in Tehran's
  time zone). Iran and Afghanistan give Persian, Turkey Turkish, the Arab League countries Arabic, and it applies only
  when the phone itself is in English or a language Pairline doesn't have. It sets the app's own language (Android
  13+'s per-app language, the stored choice before). No permission is needed and nothing leaves the phone. Setup gets
  a first step, **Language**, listing the four languages in their own names with the current one selected; when it was
  picked from the country, the text says so ("from your SIM card or time zone").
- **iPhone:** the iPhone usually has no SIM of the bridge's country, and a time zone alone would put everyone with an
  English iPhone in Istanbul or Dubai into Turkish or Arabic. So the keyboards decide: with one Persian, Arabic or
  Turkish keyboard added, that language; with several, the time zone, then the region picks among them; with none,
  the iPhone's language stays. (`UITextInputMode.activeInputModes`; only when iOS gives no keyboard list do the time
  zone and region decide alone.) It's written as the app's own `AppleLanguages`, the same setting as iPhone Settings ›
  Pairline › Language, before SwiftUI starts (`PairlineMain`), so the first screen is already in that language; checked
  on the Simulator: a fresh install with a Persian keyboard opened in Persian, right to left in Estedad.
- **Shown on first open, D-033 kept:** the iPhone's welcome page has a globe button with the current language's own
  name that opens iPhone Settings › Pairline, where iOS lists the languages. The app still doesn't list them itself.
- The unit tests' host app skips the check, so tests keep the Simulator's language.

### D-082 — Carrier tools: call settings, voicemail, balance and signal detail (2026-11-26)

The maintainer picked a set of features built only from what the two apps already do (ROADMAP, "More from the same
two phones"). This is the first: things a phone's own Settings › Phone and status bar offer.
- **Call forwarding, call waiting and caller ID** (Calls › USSD codes › Call forwarding, waiting and caller ID) run the
  standard GSM service codes of 3GPP TS 22.030 through the existing `ussd.run`: `**21*n#`, `**67*n#`, `**61*n#`,
  `**62*n#` to forward always, when busy, unanswered or unreachable, `#xx#` off, `*#xx#` to ask, `##002#` all off;
  `*43#`/`#43#` call waiting; `*31#`/`#31#` hide or show the number on every call. Android's `sendUssdRequest` hands
  service codes to the same MMI handling and returns the carrier's answer, which shows in the usual result sheet. No
  new message: the bridge's USSD check now also allows a `+` right after a `*`, for forwarding to `+90…`.
- **Hide the number on one call:** touching and holding the keypad's call button offers "Call with my number hidden",
  which dials `#31#` before the number (GSM's per-call caller-ID suppression), through the normal `call.dial`.
- **Voicemail:** each SIM in `status.sims` carries `vm`, Android's message-waiting indicator (READ_PHONE_STATE, which
  the bridge already holds). The iPhone notifies once each time it turns on (the announced SIMs are remembered in
  user defaults so a relaunch doesn't repeat it), tags the SIM on the Bridge tab, and "Call voicemail" there or
  touching and holding 1 on an empty keypad sends `call.dial` with `voicemail: true`; Android places a `voicemail:`
  call on that SIM's phone account and looks up the number itself. Visual voicemail isn't possible: Android keeps
  READ_VOICEMAIL for the dialer and carrier apps.
- **Balance:** a successful USSD result has "Use as balance code" (saved with the SIM it ran on). The Bridge tab then
  has a Balance card with the reply and "Check balance"; `pairline://balance` runs it too, so a Shortcut or a Home
  Screen link can. A link can't carry a code of its own, so no web page can make the bridge run one. The reply stays
  in memory like every USSD reply; no widget, since the widget's snapshot holds no carrier text (PRIVACY.md).
- **Signal detail:** `dbm` (strongest `CellSignalStrength`, RSRP on LTE and 5G, Android 10+), `rat` (the exact
  technology with Android 11's display override: "LTE-CA", "NR NSA", "HSPA+"…) and `op` (the registered network's
  name, shown when it isn't the SIM's carrier). dBm changes every few seconds, so the bridge leaves it out when it
  decides whether the status changed: it rides along with other changes and the 60-second status instead of sending
  a status every 2 s over Bluetooth.
- Capability `call.voicemail`. iOS strings in a new `Carrier` table (EN/FA/AR/TR). Unit tests on both sides; needs a
  real phone on a GSM carrier.

### D-083 — The iPhone's inbox: groups, old codes, quick replies, codes in the Dynamic Island, "left behind" (2026-11-26)

The second part of "More from the same two phones". All on the iPhone; no protocol change.
- **Groups:** chips over the conversation list (All, People, Codes, Transactions, Offers, Services) with each group's
  unread conversations counted. Sorted on the iPhone each time the list shows, from the sender and the latest
  message: a sender in the bridge's contacts is always People; otherwise a one-time code (the existing detector), then
  bank and payment words, then offer words (EN/FA/AR/TR, specific words such as "لغو11", "% off", "kampanya"), then
  the sender (a full phone number is People, except Iran's bulk-sender ranges 1000…, 2000…, 3000…, 5000…, 20004…;
  short codes and names are Services). Keyword lists rather than a model: predictable, nothing to download, and wrong
  guesses only move a conversation between filters. Only the chosen filter is stored.
- **Delete codes after a day** (Settings › Messages and alerts, off by default): received messages with a one-time
  code are deleted from the iPhone 24 hours after they arrive, through the normal delete (tombstones, D-043), so a sync
  doesn't bring them back; they stay on the Android phone, which only its default SMS app can change. The scan
  remembers how far it got, so each message is checked once; the text check runs off the main thread.
- **Quick replies:** a speech-bubble button in the composer puts a saved reply into the message field (not sent
  straight away, so it can be edited). Four defaults in the user's language until they edit the list; kept in user
  defaults.
- **Codes in the Dynamic Island** (on by default): a new code shows in the bridge Live Activity's Dynamic Island for
  two minutes (`code` in its content state, relevance raised so it takes the island), and a tap opens
  `pairline://code`, which copies it the same way as "Copy code" (this iPhone only, expires after 3 minutes). Never in
  the Lock Screen view: the Dynamic Island shows only while the iPhone is unlocked. The link carries no code.
- **Bridge phone left behind** (on by default): when a connected link drops (not when this iPhone's Bluetooth is
  off, or the app is locked or unpaired), a local notification is scheduled 5 minutes ahead and withdrawn if the link
  comes back first. iOS holds the timer, so it works while Pairline is suspended. It uses no location.
- Strings in a new `Inbox` table; the widget extension's two new strings in its own catalog. Unit tests for the
  grouping; needs real use to tune the word lists.

### D-084 — Scheduled SMS sent by the bridge, a mobile-data meter, and ringing the iPhone from Android (2026-11-26)

The third part of "More from the same two phones": three things only the two phones together can do.
- **Scheduled SMS:** touching and holding the send button offers "Send later…" (date and time, up to a year).
  `sms.schedule` gives the message to the bridge, which keeps it in private storage and sends it at that time
  through the normal sender with the iPhone's `cid`, so it goes even when the iPhone is off or away, and `sms.state`
  follows when the iPhone is linked. The iPhone shows it in the conversation at its time with a clock (a new
  `scheduled` state the outbox never sends), and "Cancel scheduled message" sends `sms.unschedule`. For an iPhone
  that was away at the time, `sms.scheduled` lists what's waiting and `done` (each sent message's state and the key
  Android stored it under, kept 7 days) so the iPhone's copy merges with the synced one; a message the bridge no
  longer knows is marked not sent. One alarm for the earliest message, re-armed when the link service starts (alarms
  don't survive a restart or update). **Exact timing** needs `SCHEDULE_EXACT_ALARM`: Android 12 grants it, 13 and
  later ask the user (Alarms & reminders), so the bridge falls back to Doze-friendly inexact alarms, shows a
  notification leading to the setting, and tells the iPhone (`exact`), which says it may be a few minutes late.
  `USE_EXACT_ALARM` was not used: it's meant for alarm-clock and calendar apps.
- **Mobile-data meter:** a Bridge-tab card with the bridge phone's mobile data since the start of the user's data
  month (day 1–28) and an optional monthly limit, with a notification at 80% and 100% once a month. `data.usage`
  reads Android's `NetworkStatsManager` total for mobile networks (every SIM; Android 9 needs each SIM's subscriber
  id, which READ_PHONE_STATE still gives there). Usage access (`PACKAGE_USAGE_STATS`) is a special access only the
  user can give; without it the bridge answers `permission` and shows a notification leading to the screen. Only the
  total in bytes leaves the phone, never per-app figures. Refreshed with the status at most every 15 minutes.
- **Ring my iPhone:** the reverse of Find phone, from the bridge app's home screen or a Quick Settings tile.
  `remote.ring` goes only to an iPhone that listed `ring` in its `device.info` caps. The iPhone makes a two-tone beep
  itself (no sound file) through the playback audio category, so it sounds with the ring switch on silent at the
  media volume (iOS doesn't let an app raise it); its existing `audio` background mode lets it start when the
  Bluetooth link wakes the app. 30 seconds at most, never during a call; a notification says why, and "Found it" or
  opening Pairline stops it.
- Android strings in a new `strings_more.xml` per language, iOS strings in a new `Extras` table. Unit tests on both
  sides; needs real phones (exact alarms on Android 13+, Usage access, background audio on the iPhone).

### D-085 — Call screening: blocked calls never ring, silence unknown callers, blocked prefixes (2026-11-26)

Until now a blocked call was kept off the iPhone and declined on the bridge (D-043), but it rang there for a moment
first, and nothing could quiet callers who aren't in the contacts.
- **Android's call-screening role** (Android 10+), which any app may hold once the user agrees in Android's own
  dialog: Pairline Bridge › Blocked numbers › "Block calls before they ring". `PairlineScreeningService` sees each
  incoming call's number before it rings: a blocked number is turned away (rejected, no notification, still in the
  call log), and with **Silence unknown callers** a number that isn't in the bridge's contacts (or a hidden one) is
  silenced, which sends it to voicemail. The call tracker keeps a silenced call off the iPhone too (the service
  notes it for 30 seconds), so it rings neither phone, like the iPhone's own setting. Capability `call.screen`.
- **The setting lives on the bridge** with the block list, so it works while the iPhone is away; `block.list` carries
  `unknown` and `block.set` takes it, so the iPhone's Blocked numbers screen has the same switch. Android 9 and phones
  without the role keep the D-043 behaviour.
- **Prefixes:** an entry ending with `*` and two or more digits blocks every number starting with it: `+234*` (with
  `+` or `00` it's an international prefix, matching numbers written with either), `0900*` (as the digits come).
  The same rule on both phones (`PhoneNumbers.blockPrefix`, `PhoneNumber.blockPrefix`), for calls and texts alike.
- Strings: Android `strings_more.xml`, iOS `Screening` table. Unit tests on both sides; needs a real phone.

### D-086 — Reply to Android app notifications from the iPhone (2026-11-26)

With D-077 the iPhone shows notifications from apps the user chose on the Android phone; now it can answer them, so
a second WhatsApp or Telegram on the Android phone's number is usable from the iPhone.
- The bridge keeps, for each mirrored notification, the app's own **reply action**: the one marked
  `SEMANTIC_ACTION_REPLY`, else the first that takes free text (`RemoteInput`). That's the action Wear OS and Android
  Auto use, so apps handle it as a reply typed in the notification. `notif.posted` says `reply: true` when there is one.
- The iPhone gives those notifications a Reply field (a second category, `mirror.reply`; authentication required, as
  for SMS replies). `notif.reply` carries the key and the text; the bridge fills the action's `RemoteInput` results
  and fires its intent. When the link is down or the notification is gone, the iPhone says "Reply not sent" (without
  the text). Nothing is stored, logged or queued.
- No other actions (mark as read, like) for now: their meaning differs between apps, and a wrong tap can't be undone.
- No new permission: notification access already covers reading and firing a notification's actions.

### D-087 — Live captions of relayed calls, on the iPhone only (2026-11-26)

Asked for "using the call-audio relay, without call recording". Captions help in noise, for the hard of hearing,
and in a language the user reads better than they hear.
- A **CC** button on Pairline's call screen while the call's audio is on the iPhone (Talk on iPhone). The call
  engine hands each decoded 20 ms frame of the **caller's** voice to `LiveCaptions` (nothing of the iPhone's own
  microphone); Apple's `SFSpeechRecognizer` turns it into text with `requiresOnDeviceRecognition`, and a request is
  renewed every 55 seconds so long calls keep going. The panel shows the last ~240 characters.
- **On the device or not at all:** only languages the iPhone reports `supportsOnDeviceRecognition` for are offered
  (Pairline's four first, then the user's own); otherwise the panel explains how to add a dictation language. Server
  recognition would send call audio to Apple, which rule 1 rules out.
- **No recording, no transcript:** the text lives in memory during the call and goes when it ends or CC is turned off.
  CallKit's own call screen can't show captions, so they're on Pairline's call screen only. Touching and holding CC
  picks the language.
- New iOS permission: Speech Recognition (`NSSpeechRecognitionUsageDescription`), asked the first time CC is tapped.
  Strings in a `Captions` table. Needs a real relayed call to tune.

### D-088 — Browse the Android phone's photos from the iPhone (2026-11-26)

Files already go both ways (D-045), but only what the Android side chooses to send. This lets the iPhone pick.
- **Opt-in on the Android phone:** Pairline Bridge › Settings › "Photos for the iPhone", off by default; turning it on
  asks for `READ_MEDIA_IMAGES` (`READ_EXTERNAL_STORAGE` up to Android 12). Capability `media.photos`, listed only with
  the Wi-Fi link on.
- **`media.list`** pages the photos newest first (MediaStore, ordered by when added, then id; the cursor is
  "seconds:id"), each with a thumbnail made on the phone (Android's own `loadThumbnail`, at most 128 px and ~4 KB as
  JPEG), within the usual 30 KB page. Answered only on the Wi-Fi session: thumbnails would crowd the Bluetooth link.
- **`media.get`** hands the photo to the existing file transfer, so it arrives like any file (Files › Pairline ›
  Received) and the iPhone opens it in Quick Look, where Share › Save Image puts it in Photos. No iOS Photos
  permission is needed for that.
- Only photos for now; videos would need streaming rather than copying to be pleasant. Strings: Android
  `strings_more.xml`, iOS `Photos` table. Unit tests on both sides; needs real phones.

### D-089 — Picture and group messages (MMS) (2026-11-26)

The Help FAQ promised MMS, and group conversations didn't show at all. No new permission: `READ_SMS` reads Android's
MMS store and `SEND_SMS` lets an app that isn't the default messaging app send through Android's own MMS service.
- **Reading:** `MmsStore` reads `content://mms` (messages the default app sent or downloaded; a notification not yet
  downloaded has nothing to show), with each message's addresses and parts. A Message gets `parts` (attachments
  besides text and SMIL, pictures with a ~4 KB thumbnail made on the phone) and `addrs` (everyone in a group but this
  phone, whose SIM numbers are left out). `sms.history` merges SMS and MMS in one time order (the top of the merged
  list is within the top of each list, so one page is right); `sms.threads` lists MMS-only and group threads; a
  content observer announces newly downloaded MMS as `sms.new`, a moment after Android writes their parts.
- **Attachments** come in full with `mms.part`, through the file transfer (Wi-Fi), and open in Quick Look.
- **Sending:** the composer's picture button (iOS's own picker; the picture is scaled to at most 1280 px and ~280 KB,
  which carriers take) and any message in a group conversation go as MMS. The picture travels in `mms.attach` pieces of
  24 KB (any session, Bluetooth too), then `mms.send` builds an `m-send-req` PDU (`MmsPdu`, OMA-WAP-MMS-ENC 1.2:
  multipart/mixed, text as UTF-8, pictures by their well-known types) in the private cache, hands it to
  `SmsManager.sendMultimediaMessage` through Pairline's FileProvider (read granted to Android's phone and MMS
  service only) and reports `sms.state` sent or failed; Android stores the sent message itself. MMS needs mobile data
  on the bridge phone; there are no delivery reports. An MMS isn't kept in the iPhone's outbox: it needs the link
  when sent, and one left waiting is marked not sent.
- iPhone database version 3 adds `parts` and `addrs` to messages. Group conversations show everyone's names, and the
  sender above each incoming run of messages.
- Strings in an `MMS` table. Unit tests: the PDU's encoding, keys, participants, the merged history's storage, the
  picture sizing. Needs real phones and carriers.

### D-090 — Tehran's time zone always means Persian (2026-11-26)

The maintainer asked that Tehran's time zone give Persian on first open, amending D-081. Asia/Tehran is used only in
Iran, so unlike Istanbul's or Dubai's it says little about visitors and a lot about the person.
- **Android:** a time zone whose country (ICU) is Iran gives Persian before the SIM is looked at, so an Iranian with a
  foreign SIM, or with none, starts in Persian. Other time zones keep D-081's order (SIM, network, time zone, region).
- **iPhone:** Tehran's time zone gives Persian whatever keyboards were added; other time zones still only choose among
  the keyboards' languages.
- Still only on a fresh install, and only when the phone itself isn't in Persian, Arabic or Turkish (a Turkish phone
  in Tehran stays Turkish). Nothing new is read; PRIVACY.md already lists the time zone.
- **Open:** iPhone Settings › Pairline shows a Language row only when the iPhone has two or more languages in General
  › Language & Region. On an iPhone with one, a person put in Persian this way has no way back to English short of
  adding a language there.

### D-092 — Remote mode: the user's own iCloud as doorbell and mailbox, a direct connection through the home router (2026-11-26)

The maintainer's design, built on the `remote-mode` branch as milestone M10 (written there as D-072, then D-080, and
renumbered each time `main`'s new decisions were merged in). **Supersedes D-006's "future remote-mode relay"
sentence:** there is still no Pairline server. The bridge stays at home (on charge, on home Wi-Fi) and the iPhone keeps
getting calls, SMS and one-time codes, and makes calls and sends SMS, from anywhere. Only Apple and the user's own two
phones take part: no third-party service (no STUN/TURN, Firebase or Tailscale).

- **Doorbell and mailbox: CloudKit in the user's own iCloud** (container `iCloud.app.pairline.ios`). The iPhone
  fetches a CloudKit web auth token (`CKFetchWebAuthTokenOperation` with the app's CloudKit API token) and sends it
  to the bridge over the encrypted link or in a remote-pairing invitation. The bridge reads and writes records through
  CloudKit Web Services. Every record is sealed on its own (PROTOCOL.md "Mailbox records": a one-way Noise K pattern
  with the pinned identity keys, platform primitives only), so Apple stores only ciphertext. Records are deleted once
  handled.
- **Waking the iPhone:** CloudKit subscriptions on an alert mailbox (mutable push; a Notification Service Extension
  unseals the record and shows the SMS, code or caller) and a quiet one (silent push: status, delivery reports). A
  call is to ring through CallKit with `CXProvider.reportNewIncomingVoIPPushPayload` from the extension once Apple
  grants the notification-filtering entitlement (`com.apple.developer.usernotifications.filtering`); until then a
  time-sensitive notification opens the call. The extension reads the keys from the app group's Keychain group.
- **Commands from the iPhone** go over a live session (Bluetooth, Wi-Fi or the direct connection) when one is up,
  otherwise as mailbox records, which the bridge checks on a timer (Apple has no push for web clients).
- **Direct connection:** the bridge asks the home router to forward one TCP port (NAT-PMP, then PCP, then UPnP IGD;
  small clients of our own, no new dependency), learns the public IPv4 and its global IPv6 address, and sends them
  sealed (`remote.addr`). The iPhone connects out to them and runs the same session as the Wi-Fi link. The public
  listener reads only the hello and the confirm before the handshake authenticates the pinned iPhone, drops anything
  else within 10 s, and rate-limits. Turning remote mode off, or forgetting the pairing, closes the router mapping,
  stops the listener and deletes the CloudKit records.
- **Audio:** D-072's Opus, as `MEDIA` frames inside the session, carried over the direct connection (no UDP port, no
  second mapping); local links keep their own paths. A call moves between the direct connection and Bluetooth as the
  user leaves or comes home.
- **In the background** the iPhone closes the direct session unless a call is in progress: iOS suspends an app with
  only a socket open, so events sent into it would wait unread, while the mailbox's pushes wake the iPhone.
- **Android code** lives in `link/remote/`; `network-code-check.sh` lets it reach only CloudKit's web services host
  and, by address, the local router and the paired iPhone.
- **What users are told** (a screen before turning it on, PRIVACY.md, README): no Pairline server; content locked
  with keys only the two phones hold; Apple stores only locked records in the user's own iCloud; audio goes straight
  between the phones; the home router opens one port only the paired iPhone can use; Apple and internet providers can
  see that the phones talk, not what they say. Off by default. A status line shows "Direct connection" or "Via
  iCloud"; the local log records connections, redacted (rule 9).
- **Remote pairing** ("Pair over the internet" in the apps, D-094): pairing while the phones are apart. The iPhone
  makes a one-time invitation (10 minutes, single use); it's opened, pasted or scanned in Pairline on the Android
  phone and allowed there; the phones meet through the mailbox and both show a 6-digit code from the handshake, which
  is checked on both phones. A copied invitation produces different codes, and the pairing stops. This replaces
  "seeing the QR code" as the trust anchor for this one flow only, chosen explicitly on both phones; outside it,
  release builds still pair only from the camera. Known limit: an intercepted invitation's iCloud token reaches only
  Pairline's records in that user's iCloud, which stay sealed, until Apple expires it.
- **Protocol:** v1 is v0 plus remote mode (PROTOCOL.md "Remote mode (v1, D-092)", vectors `v1-remote.json`); local
  links stay v0 on the wire. Status goes to the mailbox at once only when something that matters changes (network,
  SIMs, charging, each 10 % of battery), signal bars and battery percent at most every 10 minutes; `remote.state`
  reasons are fixed codes. Remote mode's own message types are listed (`remote.config`, `remote.token`, `remote.addr`,
  `remote.state`, `remote.check`, `rpair.*`); D-084's `remote.ring` is not one of them.

**Phase 0 results (2026-11-26):**

- **Router** (`protocol/tools/router_check.py`, read-only): the maintainer's OpenWrt router first answered none of
  NAT-PMP, PCP or UPnP IGD, and the home network has no global IPv6. With miniupnpd installed and its "Start service"
  ticked, it answered NAT-PMP and UPnP with a public IPv4 address, and the bridge mapped TCP 47830 through NAT-PMP:
  the direct connection opened (Check my home connection now maps at once when the router answers).
- **CloudKit setup:** container `iCloud.app.pairline.ios`, iCloud and push on `app.pairline.ios`, App ID
  `app.pairline.ios.notifications` with the App Group, the `Mail` record type in Development only, an API token
  (outside the repo, mode 600) and a management token (Keychain, `cktool`). Command-line device builds sign with
  the App Store Connect API key (`-authenticationKeyPath`), because Xcode has no account signed in.
- **Web auth tokens are not single-use.** A token from `CKFetchWebAuthTokenOperation` works for many requests; each
  response returns a refreshed token in `X-Apple-CloudKit-Web-Auth-Token` that works only once its trailing `__` is
  removed; a refreshed chain holds. An unused token still worked after 4 hours, and a chain refreshed every 20
  minutes kept working for 5 hours (Apple's archived reference says 30 minutes; that no longer holds). Both ended
  only when "Access iCloud Data on the Web" was switched off and on, which cancels every web token of the account;
  longer lifetimes are unmeasured. The bridge uses its token every few minutes, and the iPhone sends a fresh one every
  12 hours and whenever the bridge stops reading, so neither limit is reached.
- **No push for the bridge.** CloudKit Web Services' push for web clients (`tokens/create`, then the webcourier
  long-poll) answers HTTP 403 "This endpoint is deprecated." So the bridge checks the R→B mailbox on a timer: every
  5 s for 2 minutes after hearing from the iPhone, every 30 s within 10 minutes, otherwise every 2 minutes.
- **Blocker: the private database is refused** on the maintainer's account, which has no Advanced Data Protection
  and has "Access iCloud Data on the Web" on. Tokens from the iPhone get HTTP 403 `ACCESS_DENIED`, "private db
  access disabled for this account"; a token from Apple's web sign-in for the same user (a second, Development-only
  API token redirecting to localhost) gets HTTP 401 `AUTHENTICATION_FAILED` on the private and shared databases.
  Both read the user record through the public database, both are Development tokens, and GET and POST requests
  fail alike. Other projects tie the 403 to Advanced Data Protection, which doesn't apply here; nothing published
  explains it for a plain account. Whether it affects every account or only some is untested (it needs a second
  Apple ID). Turning "Access iCloud Data on the Web" off and on only invalidated the existing tokens. iCloud.com
  shows the account's Notes, and the CloudKit Console ("Act As iCloud Account", which uses Apple's own developer
  API) lists the container's private zones for the same user, so only third-party web access is refused. A focused
  sample app reproduces it from the iPhone alone; it went to Apple Developer Technical Support on 2026-11-26
  (Case-ID 23765312).
- **Until Apple answers, tests use the public database** (the maintainer's decision; debug builds only; the
  shipping design stays the private database). `Mail` gets a queryable `mailbox` ID and a `sealed` text field
  (a push can't carry a BYTES field: "unsupported field type in notification"), and is readable only by the
  iCloud user who wrote it. The iPhone subscribes to new records with its random mailbox ID written by its own
  iCloud user. Result: **20 of 20 records written through CloudKit Web Services woke the locked iPhone**, a median
  1.61 s from the start of the write (1.14 to 2.44 s; the write itself takes about 1 s). The pushes allow
  modification, so the notification service extension can unlock them before they're shown.
- **Filtering entitlement** requested on 2026-11-26 (Apple request ID Q7U98356ZR), for the extension
  `app.pairline.ios.notifications`, use case end-to-end encrypted messaging.
- **Tested on the maintainer's iPhone 16e** with a stand-in bridge (`protocol/tools/fake_remote_bridge.py`), then with
  a real bridge (a Galaxy Note 3 on Android 10, paired over the internet): codes checked on both phones, SMS with a
  one-time code shown on the locked iPhone by the extension, texts sent from the iPhone through the mailbox, the
  bridge's name, battery and signal shown "Via iCloud" in the app, the Live Activity and the widget, and the direct
  connection through the router. That Note 3 can't advertise over Bluetooth LE ("too many advertisers") and can't run
  Talk on iPhone (Android 10), so calls with sound wait for an Android 11+ phone.

### D-093 — Remote mode is a $9.99 one-time add-on after Full Access, included in the free trial (2026-11-26)

The maintainer's pricing for remote mode (D-092). Extends D-017 and D-036; the Android app stays free.

- **Product:** `app.pairline.ios.remote`, a non-consumable In-App Purchase, $9.99 once, no subscription. Like Full
  Access it's verified on the iPhone from Apple-signed transactions (StoreKit 2, `Transaction.currentEntitlements`
  and `Transaction.updates`); there's no receipt server, and Restore Purchases brings it back.
- **The rule** (`RemoteAddOnPolicy`, unit-tested): remote mode, and pairing over the internet, work while the 7-day
  trial runs, and afterwards only with Full Access **and** the add-on. The trial shows a line that Cloud mode is part
  of it and becomes a $9.99 add-on after Full Access. No trial and no Full Access: the app's own paywall comes first,
  and the add-on isn't offered.
- **Sold only after Full Access:** the "Buy Cloud mode" button appears only once Full Access is bought, and only in a
  build where remote mode is available (`RemoteFeature`), so a build without it (App Review before it ships) never
  shows a purchase button for it.
- **When it stops being covered** (the trial ended without the add-on, or a refund) while remote mode is on, the iPhone
  turns it off the usual way: `remote.config` off to the bridge (over a session, or one last mailbox record), then its
  records and subscriptions in iCloud go. Nothing is turned off while the purchase state is still loading.
- **Debug builds** fake it with `-PairlineRemoteAccess purchased|none|reset` (remembered like `-PairlineAccess`); a
  faked "purchased" access state counts as the add-on bought too, so a test iPhone keeps remote mode.
- **Not done yet:** the product doesn't exist in App Store Connect. Creating it there (price tier, localized name and
  description in English, Arabic and Turkish, review screenshot) waits for the maintainer's OK; until then it exists
  only in `ios/Pairline/Store/Pairline.storekit` for Xcode and the unit tests.

### D-094 — Remote mode is called "Cloud mode"; normal pairing offers it; pairing from far away is "Pair over the internet" (2026-11-26)

The maintainer's decision, after testing remote mode on real phones: "away" and "remote" read as a special situation,
while the feature is simply how the phones stay connected over the internet whenever they aren't linked nearby.

- **Names in the apps:** the feature is **Cloud mode** (fa «حالت ابری», ar «الوضع السحابي», tr «Bulut modu»); remote
  pairing is **Pair over the internet** (fa «جفت کردن از راه اینترنت», ar «الإقران عبر الإنترنت», tr «İnternet üzerinden
  eşleştir»). The link states keep saying how the phones are linked right now: "Via iCloud" (texts and codes; calls
  need the direct connection) and "Direct connection". The add-on (D-093) is the Cloud mode add-on; its product id
  `app.pairline.ios.remote` stays.
- **The code, the protocol and the docs' internals keep "remote mode"** (`remote.*` messages, `link/remote/`,
  `Remote/`, PROTOCOL.md's "Remote mode (v1, D-092)"), so nothing on the wire or in storage changes. User-facing docs
  say "Cloud mode (remote mode in the code)".
- **"Cloud" never means a Pairline server.** Every screen that explains it says the records go through the user's own
  iCloud, locked with keys only their two phones hold, and that calls go straight between the phones where the home
  router allows.
- **Normal pairing offers it.** After pairing with the QR code, the iPhone offers to turn Cloud mode on (with the same
  explanation as the before-you-turn-it-on screen), where it's available and the purchase state allows it (D-093);
  "Not now" leaves it off and it stays in Settings. The Android phone can't turn it on (the iPhone supplies the iCloud
  access); its home screen says whether it's on and where to turn it on.
- **Automatic, no mode switching:** with Cloud mode on, the phones use Bluetooth or Wi-Fi when they're near each other
  and the internet (iCloud, and the direct connection) whenever they aren't, without the user doing anything. While
  Cloud mode links them, D-083's "left your bridge phone behind" alert doesn't fire: being apart is the point.

### D-095 — Manual port forwarding when the router can't open the port by itself (2026-11-26)

The maintainer's decision: "if [UPnP or NAT-PMP] can be enabled, enable it; if not, the user does it manually."

- **First choice stays automatic:** the apps tell users to turn on UPnP or NAT-PMP (or PCP) when their router has it;
  Pairline then opens and closes the port itself (D-092).
- **Otherwise, by hand:** the user forwards TCP 47830 in the router to the Android phone (Pairline Bridge shows its
  home-network address and port), gives that phone a fixed address there (DHCP reservation; "Use device MAC" for that
  Wi-Fi on Android 10+; Help explains why), and enters the home's internet address, or a DDNS name from the router's
  own DDNS feature for a changing address, on the iPhone: Settings › Cloud mode › Port forwarding. Help › "Allow calls
  from far away" explains both ways for any router brand.
- **Why the user types the address:** without NAT-PMP, PCP or UPnP the router doesn't tell the Android phone the
  home's internet address, and Pairline never asks outside "what's my IP" services (AGENTS.md rule 2).
- **New outside party, by the user's choice:** a DDNS name the user entered is looked up through the iPhone's normal
  DNS, so their DNS resolver and the name's provider see the lookup (PRIVACY.md). Nothing else changes: the bridge
  already listens while Cloud mode is on, the handshake pins the bridge's key, and nothing changes on the wire.
- The entry lives in the iPhone's Keychain (this device only), is never logged (rule 9), and goes when Cloud mode is
  turned off or the pairing is forgotten. Carrier-grade NAT still can't be helped by any router setting.
