# Pairline Bridge — Android app

- **Build:** Gradle with Kotlin DSL and a version catalog (`gradle/libs.versions.toml`), JDK 17
- **Versions:** applicationId `app.pairline.android` (provisional, D-007), minSdk 30, compileSdk 37, targetSdk 36
- **UI:** Jetpack Compose
- **Layout:** one `:app` module with packages `ui`, `link`, `protocol`, `telephony`, `privileged`, and
  `netshare` from M8 (see `../docs/ARCHITECTURE.md`)
- **Tests:** `protocol` unit tests read the shared vectors in `../protocol/test-vectors/`
- **Permissions:** no `INTERNET` until M5. Every permission must be listed in `../docs/PRIVACY.md`.

## Commands

```sh
./gradlew assembleDebug            # debug APK in app/build/outputs/apk/debug/
./gradlew test lint                # unit tests and lint
../.github/scripts/android-policy-check.sh app/build/outputs/apk/release/app-release-unsigned.apk release
```

Debug builds use the application ID `app.pairline.android.debug`, so they install next to a release build.
Release APKs come only from GitHub Actions: `../docs/RELEASING.md`.
