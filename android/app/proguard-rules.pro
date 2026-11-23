# R8 still shrinks and optimizes, but class names stay readable. That keeps the release APK easy to audit
# and lets .github/scripts/android-policy-check.sh find forbidden SDKs by package name.
-dontobfuscate
