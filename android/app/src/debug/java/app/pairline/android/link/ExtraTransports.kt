package app.pairline.android.link

import android.content.Context
import app.pairline.android.debugtools.DebugTcpTransport

/** Debug builds add the TCP transport on 127.0.0.1:47800 for the emulator and the iOS Simulator (D-018). */
object ExtraTransports {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, link: LinkManager): List<LinkTransport> = listOf(
        DebugTcpTransport(link) { listening -> link.updateState { it.copy(debugTcpListening = listening) } },
    )
}
