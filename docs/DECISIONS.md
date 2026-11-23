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
**Status:** open — decide in M7.

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
