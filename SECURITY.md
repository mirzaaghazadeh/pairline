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
3. Reproducible build: build the tagged commit with the documented toolchain and compare the result
   (instructions come with the first release).

### iOS

Apple re-signs and encrypts App Store builds, so they can't be compared byte-for-byte with the
source. Each release states the exact git tag and commit it was built from.

## Supported versions

Before 1.0, only the latest release receives security fixes.
