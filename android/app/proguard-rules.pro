# R8 still shrinks and optimizes, but class names stay readable. That keeps the release APK easy to audit
# and lets .github/scripts/android-policy-check.sh find forbidden SDKs by package name.
-dontobfuscate

# Shizuku starts the privileged service by class name in its own process and calls the Context constructor
# through reflection (D-046), so R8 must keep both.
-keep class app.pairline.android.privileged.PrivilegedService {
    public <init>(android.content.Context);
}
