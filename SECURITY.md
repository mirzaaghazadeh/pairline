# Security

## Reporting a vulnerability

Email **[security contact — set before the first release]**. Please don't open a public issue for
security bugs. Include steps to reproduce and the affected app and version. We aim to acknowledge
reports within 7 days.

## Design

- Link encryption and pairing: [docs/PROTOCOL.md](docs/PROTOCOL.md) — P-256 ECDH, HKDF-SHA256,
  AES-256-GCM, QR pairing with pinned keys.
- Every data flow and permission: [docs/PRIVACY.md](docs/PRIVACY.md).
- Rules every contributor follows: [AGENTS.md](AGENTS.md), "Non-negotiable rules".

## Verifying a release

### Android

Release APKs are built and signed only by GitHub Actions (`.github/workflows/android-release.yml`) from the
tagged commit. Each release note links the build run. Process: [docs/RELEASING.md](docs/RELEASING.md).

1. Check the file hash matches the one in the release notes:
   `shasum -a 256 pairline-<version>.apk`
2. Check the signing certificate:
   `apksigner verify --print-certs pairline-<version>.apk`
   The "certificate SHA-256 digest" must match
   **`958c4cca6cd6d2b073a403ad86649d34faff992172d2c600cee625d17c8945c7`** (the Pairline Bridge signing key,
   used since 1.0.0).
3. Check the build provenance (releases whose notes have a "Build provenance" row): a statement, signed through
   Sigstore by the release workflow's GitHub identity and kept in Sigstore's public log, that this exact file was
   built by `android-release.yml` from the tagged commit. With the [GitHub CLI](https://cli.github.com):
   ```sh
   gh attestation verify pairline-bridge-<version>.apk --repo mirzaaghazadeh/pairline \
     --signer-workflow mirzaaghazadeh/pairline/.github/workflows/android-release.yml
   ```
   The release also carries the attestation as `pairline-bridge-<version>.apk.sigstore.json`; add
   `--bundle pairline-bridge-<version>.apk.sigstore.json` to check against that file instead of GitHub's copy.
4. Reproducible build: build the tagged commit yourself and check that the release is your build plus our
   signature. You need JDK 17 and the Android SDK with the NDK and CMake versions in the notes' "Toolchain" row,
   and `apksigner` (Android build-tools) on your `PATH`:
   ```sh
   git clone https://github.com/mirzaaghazadeh/pairline && cd pairline
   git checkout android-v<version>
   cd android && ./gradlew --no-daemon --no-build-cache assembleRelease
   pip install apksigcopier   # the tool F-Droid uses for the same check
   apksigcopier compare --unsigned pairline-bridge-<version>.apk app/build/outputs/apk/release/app-release-unsigned.apk
   ```
   `apksigcopier` copies our signature onto your build and checks that it verifies; the signature covers every
   byte of the APK, so it only verifies if your build is identical. Linux and macOS give the same build for
   releases after 1.8.0. For 1.8.0 and earlier, build on Linux: the macOS NDK labels one of its own files with a
   different compiler build, which ended up in the native library's `.comment` section (D-100). The release
   workflow does this check too, on a second machine, and its result is the notes' "Reproducible build" row.

Each release note also links the APK's VirusTotal report. The workflow uploads the signed APK there before the
release is published; the report's SHA-256 is the same as the release file's. Its "Trackers" row is the result of
checking the APK against every tracker signature [εxodus Privacy](https://reports.exodus-privacy.eu.org/en/trackers/)
knows, the same way εxodus's own scanner does (`.github/scripts/trackers-scan.py`).

### iOS

Apple re-signs and encrypts App Store builds, so they can't be compared byte-for-byte with the
source. Each release states the exact git tag and commit it was built from.

## Supported versions

Before 1.0, only the latest release receives security fixes.
