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

1. In `android/app/build.gradle.kts`, raise `versionCode` and set `versionName`, e.g. `0.2.0`. In the same commit,
   add a `## 0.2.0 (date)` section to `android/CHANGELOG.md`: what's new, written for the people who use the app
   (bold lead, then what changed and what it needs). Commit to `main`.
2. Tag that commit and push the tag:

   ```sh
   git tag -s android-v0.2.0 -m "Pairline Bridge 0.2.0"   # -s signs the tag; use -a if you have no signing key
   git push origin android-v0.2.0
   ```

3. The workflow checks that the tag matches `versionName` and that `CHANGELOG.md` has its section, builds, runs the
   policy checks (`.github/scripts/android-policy-check.sh`), signs, scans the signed APK on VirusTotal
   (`.github/scripts/virustotal-scan.sh`), and creates a **draft** GitHub Release with what's new, the contributors,
   the APK, its SHA-256, the signing-certificate digest and the VirusTotal report link
   (`.github/scripts/release-notes.sh`, D-080). What's new covers every version since the last *published* release,
   so publishing 1.7.0 after leaving 1.4.0 to 1.6.0 as drafts still tells people everything they get. Contributors
   are the GitHub accounts behind the commits in that range that touch `android/` or `protocol/`. Versions starting
   with `0.` are marked as pre-releases.
4. Review the draft and publish it. If the VirusTotal row shows detections or "not scanned", open the report (or the
   run's warnings) first. One or two engines flagging a new APK is usually a false positive; check which engines and
   what they call it before publishing.

To scan a release that already exists (made before the scan was added, or when the scan didn't finish), run the
**VirusTotal scan of a release** workflow from the Actions tab with the release tag
(`gh workflow run virustotal.yml -f tag=android-v1.0.1`). It adds or replaces the VirusTotal row in that release's notes.

To redo an existing release's what's new and contributors (after fixing its changelog section, or after publishing an
earlier draft changes what counts as "since the last published release"), run
`GH_REPO=mirzaaghazadeh/pairline .github/scripts/release-notes.sh 1.7.0 android/CHANGELOG.md` and replace the part
of the notes above "## Pairline Bridge …" with its output (`gh release edit android-v1.7.0 --notes-file …`).
5. The first release only: copy the certificate digest into `SECURITY.md` ("Verifying a release").

### Why signing happens outside Gradle

Gradle builds an unsigned APK, and the workflow signs it with `apksigner`. Anyone can build the same unsigned APK
from the tagged commit without our key, which is the basis for the reproducible-build check in `SECURITY.md`.

## iOS (remote app)

Built and uploaded to App Store Connect from Xcode (Product › Archive › Distribute) or from the command line. Each
release note names the git tag and commit (`ios-v<version>`).

1. Raise `CURRENT_PROJECT_VERSION` in `ios/project.yml` (every upload needs a new build number) and run
   `xcodegen generate`.
2. Archive and upload with an App Store Connect API key (team 7Y68TERDUC):
   ```sh
   AUTH=(-allowProvisioningUpdates -authenticationKeyPath AuthKey_<KEY_ID>.p8
         -authenticationKeyID <KEY_ID> -authenticationKeyIssuerID <ISSUER_ID>)
   xcodebuild -scheme Pairline -configuration Release -destination 'generic/platform=iOS' \
     -archivePath build/Pairline.xcarchive archive "${AUTH[@]}"
   # ExportOptions.plist: method app-store-connect, destination upload, teamID 7Y68TERDUC, signingStyle automatic
   PATH=/usr/bin:$PATH xcodebuild -exportArchive -archivePath build/Pairline.xcarchive \
     -exportOptionsPlist ExportOptions.plist -exportPath build/export "${AUTH[@]}"
   ```
   The export needs Apple's `/usr/bin/rsync`; with Homebrew's rsync first in `PATH` it fails with
   "exportArchive Copy failed".
3. Uploading doesn't submit anything. Attach the build to the version in App Store Connect and submit it for review
   when it's time; `Info.plist` already answers the export-compliance question (D-035).
