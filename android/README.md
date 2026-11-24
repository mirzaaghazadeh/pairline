# Pairline Bridge — Android app

The free Android app that lends this phone's SIM to the user's iPhone. See `../docs/ARCHITECTURE.md` for the big
picture and `../docs/PROTOCOL.md` for the wire format.

- **Build:** Gradle with Kotlin DSL and a version catalog (`gradle/libs.versions.toml`), JDK 17
- **Versions:** applicationId `app.pairline.android` (final, D-037), minSdk 28 (Android 9, D-038), compileSdk 37, targetSdk 36
- **UI:** Jetpack Compose, Material 3, English / Persian / Arabic / Turkish (D-019)
- **Permissions:** no `INTERNET` in release builds until M5. Every permission is listed in `../docs/PRIVACY.md`.

## Layout

One `:app` module (`app/src/main/java/app/pairline/android/`):

| Package | What's in it |
|---------|--------------|
| `protocol` | Pure Kotlin on JCA: frames, hello, P-256 ECDH, HKDF, AES-GCM records with replay checks, BLE fragmenting, TCP framing, the bridge-side `BridgeSession` state machine, message helpers and 30 KB paging |
| `link` | `LinkService` (foreground service), `LinkManager` (sessions on one thread, keep-alive), `BleTransport` (advertiser + GATT server), `IdentityStore` (Keystore keys), pairing codes, `MessageRouter` |
| `telephony` | Status and capabilities, SIM directory, SMS receive/send/history, calls (telephony callbacks + companion `InCallService`), contacts and call-log sync, USSD |
| `log` | The local, redacted, 7-day log and its `Redactor` |
| `ui` | Setup wizard, home, pairing, settings, permissions, keep-alive tips, diagnostics, help; theme and icons |

Build-type source sets:

- `src/debug/` — **debug builds only (D-018):** the TCP transport on `127.0.0.1:47800`, the `INTERNET` permission it
  needs, an adb-only devtools provider, and `MANAGE_ONGOING_CALLS` for testing the companion call service.
- `src/release/` — empty stand-ins, so the release APK contains none of the above (CI checks it).

## Commands

```sh
./gradlew assembleDebug            # debug APK in app/build/outputs/apk/debug/
./gradlew test lint                # unit tests and lint
../.github/scripts/android-policy-check.sh app/build/outputs/apk/release/app-release-unsigned.apk release
```

Unit tests read the shared vectors from `../protocol/test-vectors/v0.json` (the path is passed in by Gradle) and must
reproduce every value. Debug builds use the application ID `app.pairline.android.debug`, so they install next to a
release build. Release APKs come only from GitHub Actions: `../docs/RELEASING.md`.

## Testing end to end on an emulator

The emulator has no Bluetooth link to an iPhone, so debug builds also accept the iPhone over TCP, and
`../protocol/tools/fake_remote.py` plays the iPhone. Everything above the transport (pairing, handshake, records,
messages) is the real code.

```sh
# Once: an emulator (any API 28+ image works; telephony is simulated)
avdmanager create avd -n pairline-36 -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_6
emulator -avd pairline-36 -no-window -no-audio -no-snapshot &

adb install -r -g app/build/outputs/apk/debug/app-debug.apk
# Skip the wizard and start the service (or go through the wizard on screen):
adb shell content query --uri content://app.pairline.android.debug.devtools/setup-done
adb shell am start -n app.pairline.android.debug/app.pairline.android.MainActivity

adb forward tcp:47800 tcp:47800
LINK=$(adb shell content query --uri content://app.pairline.android.debug.devtools/pairing | sed -n 's/.*uri=\(pairline:[^,]*\),.*/\1/p')
python3 -I ../protocol/tools/fake_remote.py pair --pair-uri "$LINK" status ping threads history
python3 -I ../protocol/tools/fake_remote.py session listen --seconds 30 --auto-answer &
adb emu sms send 5551234 "Your code is 4821"     # → sms.new
adb emu gsm call 5551234                         # → call.incoming, call.state …
adb emu gsm cancel 5551234
python3 -I ../protocol/tools/fake_remote.py session send-sms --to 5550001111 --body "hi" contacts calllog
adb shell content query --uri content://app.pairline.android.debug.devtools/state
```

The devtools provider answers only adb (the shell user), root and the app itself.

**Companion call service.** Android 12+ binds a companion `InCallService` only for apps that hold
`MANAGE_ONGOING_CALLS`. To test that path on an emulator (debug builds declare the permission):
`adb shell appops set app.pairline.android.debug MANAGE_ONGOING_CALLS allow`. Without it, calls use the telephony
callbacks: answer, decline and end work; keypad tones, mute and speaker answer `unsupported`.

**Languages and themes.** `adb shell cmd locale set-app-locales app.pairline.android.debug --locales fa` (or `ar`,
`en-US`), and `adb shell cmd uimode night yes|no`. Screenshots of the main screens are in
`../docs/screenshots/android/`.
