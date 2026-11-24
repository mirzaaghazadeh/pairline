package app.pairline.android.link

import android.content.Context
import android.os.Build
import app.pairline.android.debugtools.DebugTcpTransport
import app.pairline.android.link.wifi.WifiDebug

/** Debug builds add the TCP transport on 127.0.0.1:47800 for the emulator and the iOS Simulator (D-018). */
object ExtraTransports {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, link: LinkManager): List<LinkTransport> {
        // The emulator's NAT (`adb emu redir`) lands on its emulated mobile-data interface; let the Wi-Fi link
        // listen there too, on an emulator only, so the iOS Simulator can test it (PROTOCOL.md, "Wi-Fi").
        WifiDebug.allowEmulatorMobileInterface = isEmulator()
        return listOf(
            DebugTcpTransport(link) { listening -> link.updateState { it.copy(debugTcpListening = listening) } },
        )
    }

    private fun isEmulator(): Boolean =
        Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic") ||
            Build.PRODUCT.startsWith("sdk_gphone")
}
