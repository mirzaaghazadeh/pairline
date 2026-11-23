package app.pairline.android.link

import android.content.Context

/** Release builds have Bluetooth only. The debug TCP transport lives in src/debug (D-018). */
object ExtraTransports {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, link: LinkManager): List<LinkTransport> = emptyList()
}
