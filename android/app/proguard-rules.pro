# R8 still shrinks and optimizes, but class names stay readable. That keeps the release APK easy to audit
# and lets .github/scripts/android-policy-check.sh find forbidden SDKs by package name.
-dontobfuscate

# Pairline's helper process starts at HelperMain.main through app_process, by class name (D-054). Nothing in the
# app calls it, so R8 must keep it.
-keep class app.pairline.android.privileged.HelperMain {
    public static void main(java.lang.String[]);
}
