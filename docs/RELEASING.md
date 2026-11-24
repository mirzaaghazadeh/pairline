# Releasing

## Android (bridge app)

Release APKs are built only by GitHub Actions (`.github/workflows/android-release.yml`), from a tag, so every
published APK traces back to one commit and one public build log (D-015).

### One-time setup: the signing key

The signing key identifies the app forever. If it's lost, existing users can't update; they have to uninstall and
reinstall. Keep at least two offline backups (for example, a password manager and an encrypted USB drive).

1. Create the key on your own computer, never on a server:

   ```sh
   keytool -genkeypair -v -keystore pairline-release.p12 -storetype PKCS12 \
     -alias pairline -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=Pairline"
   ```

   PKCS12 uses one password for the store and the key.

2. Add it to the repo's GitHub Actions secrets. `gh secret set` without `--body` asks for the value,
   so the password stays out of your shell history:

   ```sh
   base64 -i pairline-release.p12 | gh secret set ANDROID_KEYSTORE_BASE64
   gh secret set ANDROID_KEYSTORE_PASSWORD
   gh secret set ANDROID_KEY_ALIAS --body pairline
   ```

3. Never commit the `.p12` file (`.gitignore` blocks `*.p12`, `*.jks` and `*.keystore`).

4. For the VirusTotal scan (D-039), add a VirusTotal API key (free account → API key): `gh secret set VT_API_KEY`.
   Without it the release still builds; the notes say "not scanned".

### Making a release

1. In `android/app/build.gradle.kts`, raise `versionCode` and set `versionName`, e.g. `0.2.0`. Commit to `main`.
2. Tag that commit and push the tag:

   ```sh
   git tag -s android-v0.2.0 -m "Pairline Bridge 0.2.0"   # -s signs the tag; use -a if you have no signing key
   git push origin android-v0.2.0
   ```

3. The workflow checks that the tag matches `versionName`, builds, runs the policy checks
   (`.github/scripts/android-policy-check.sh`), signs, scans the signed APK on VirusTotal
   (`.github/scripts/virustotal-scan.sh`), and creates a **draft** GitHub Release with the APK, its SHA-256, the
   signing-certificate digest and the VirusTotal report link. Versions starting with `0.` are marked as pre-releases.
4. Review the draft and publish it. If the VirusTotal row shows detections or "not scanned", open the report (or the
   run's warnings) first. One or two engines flagging a new APK is usually a false positive; check which engines and
   what they call it before publishing.

To scan a release that already exists (made before the scan was added, or when the scan didn't finish), run the
**VirusTotal scan of a release** workflow from the Actions tab with the release tag
(`gh workflow run virustotal.yml -f tag=android-v1.0.1`). It adds or replaces the VirusTotal row in that release's notes.
5. The first release only: copy the certificate digest into `SECURITY.md` ("Verifying a release").

### Why signing happens outside Gradle

Gradle builds an unsigned APK, and the workflow signs it with `apksigner`. Anyone can build the same unsigned APK
from the tagged commit without our key, which is the basis for the reproducible-build check in `SECURITY.md`.

## iOS (remote app)

Built and uploaded from Xcode to App Store Connect. Each release note names the git tag and commit
(`ios-v<version>`). Details are added when the iOS project exists.
