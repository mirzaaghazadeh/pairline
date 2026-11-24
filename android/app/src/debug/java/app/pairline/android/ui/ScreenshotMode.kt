package app.pairline.android.ui

/**
 * DEBUG BUILDS ONLY (D-018). For the website and store screenshots taken on the emulator: the debug TCP link is
 * described like the Bluetooth link a real user sees. Switched by adb through the devtools provider:
 *
 *     adb shell content query --uri content://app.pairline.android.debug.devtools/screenshot-mode-on
 *
 * Release builds have a stub that is always off (src/release).
 */
object ScreenshotMode {
    @Volatile
    var enabled: Boolean = false
}
