# Pairline

Use your Android phone's SIM card on your iPhone — calls, SMS, contacts and more — over an
end-to-end encrypted link between your own two phones.

<p align="center">
  <img src="docs/images/readme-screens.png" width="100%"
       alt="Pairline Bridge on Android, connected to the iPhone; the iPhone app's Messages with a one-time code, an incoming call, and the bridge's SIM, signal and battery">
</p>

## Download

| App | Get it | Needs |
|-----|--------|-------|
| **Pairline Bridge** for the Android phone with the SIM (free) | **[Download the latest APK](https://getpairline.com/download/android)** · [latest release on GitHub](https://github.com/mirzaaghazadeh/pairline/releases/latest) · [all versions](https://github.com/mirzaaghazadeh/pairline/releases) | Android 9 or later |
| **Pairline** for iPhone | Coming soon to the App Store · [getpairline.com](https://getpairline.com/download) | iOS 16 or later |

Both links always point to the newest release. Each release's notes list the APK's SHA-256, its signing
certificate and its [VirusTotal](https://www.virustotal.com) report; see [Verifying releases](#verifying-releases).
Progress: [docs/ROADMAP.md](docs/ROADMAP.md).

## Our promise

- **Your data stays on your phones.** Messages, contacts, call history and call audio go only
  between your two paired phones. They never touch our servers — in version 1 we don't run any.
- **No accounts, no analytics, no ads, no trackers.**
- **No settings changed behind your back.** Pairline changes no system setting you didn't ask for. The one
  exception is Talk on iPhone: once you've set it up, and only while it's on, it turns Developer options and
  debugging back on when its helper needs them. Turn Talk on iPhone off and that stops.
- **No silent updates.** Android asks you before every update is installed.
- **Check it yourself.** The full source is here. [docs/PRIVACY.md](docs/PRIVACY.md) lists every
  data flow and every permission, and why it's needed.

## How it works

1. Install the free **Pairline Bridge** app on the Android phone that has the SIM card.
2. Install **Pairline** on your iPhone.
3. Pair them once by scanning a QR code. The two phones exchange keys; nobody else ever sees them.
4. Incoming calls ring on your iPhone's normal call screen, and SMS arrive in the Pairline app.
5. Optional, on Android 11 or later: set up **Talk on iPhone** once to hear and talk on the iPhone itself.
   Pairline starts a small helper through the Android phone's own Wireless debugging: no root and no other app.
   While the iPhone is linked, the Android phone stops ringing too ("Ring only on the iPhone", on by default).

The phones talk over Bluetooth LE, plus local Wi-Fi (or the Android phone's own hotspot) for call audio and files.
Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/PROTOCOL.md](docs/PROTOCOL.md).

**Remote mode (in development, off unless you turn it on).** Leave the Android phone at home and keep your texts,
codes and calls on the iPhone anywhere. There is still no Pairline server: messages travel as locked records through
your own iCloud, which only your two phones can open, and calls go straight to your home through one port your router
opens for your paired iPhone alone. [docs/PRIVACY.md](docs/PRIVACY.md) lists exactly what Apple and your router see.
A three-minute intro video, in English, Persian, Turkish and Arabic, is on [getpairline.com](https://getpairline.com).

## Pricing

- **Android app:** free.
- **iPhone app:** free download. Pair your Android phone first: the first sync of your messages, contacts and call
  history is free, then a free week, then a one-time $9.99 In-App Purchase with free updates. No subscription.
  Demo mode is always free.
- **Remote mode (when it ships):** included in the free week, then a one-time $9.99 add-on after the main purchase.

## License

Pairline is **source-available** under the
[Functional Source License 1.1 (FSL-1.1-ALv2)](LICENSE.md):

- You **may** read, audit, build and modify the code for personal, internal, educational or
  research use.
- You **may not** sell it, or use it to offer a competing product or service.
- Each release automatically becomes **Apache-2.0** two years after it's published.
- The name and logo are trademarks and aren't covered by the license.

## Contributing and help

Issues and pull requests are welcome; the maintainer reviews every change before it goes in. Read
[AGENTS.md](AGENTS.md) first: its privacy and security rules apply to every change.

Questions and news on Telegram: the [help group](https://t.me/pairline_groups) and the news channel in
[English](https://t.me/pairline_en), [Persian](https://t.me/pairline_fa) or [Turkish](https://t.me/pairline_tr).

Thanks to Sayyad Abbasi and Amirreza Azadmehr for testing on real phones and helping with the final debugging.

## Verifying releases

Android APKs are built and signed only by GitHub Actions from a tagged commit. Each release's notes link the
build run and list the APK's SHA-256, the signing-certificate fingerprint and the APK's VirusTotal report.
How to check them: [SECURITY.md](SECURITY.md#verifying-a-release).

## Documentation

| Document | What's in it |
|----------|--------------|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the two apps are built and how they connect |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | Pairing, encryption and message format |
| [docs/PRIVACY.md](docs/PRIVACY.md) | Every data flow and permission |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Milestones and current progress |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Why things are the way they are |
| [SECURITY.md](SECURITY.md) | Reporting vulnerabilities, verifying releases |
| [AGENTS.md](AGENTS.md) | Rules for contributors and coding agents |
