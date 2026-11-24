package app.pairline.android.files

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import app.pairline.android.AppGraph
import app.pairline.android.R
import app.pairline.android.link.WifiLinkStatus
import app.pairline.android.log.Log

/**
 * "Send to iPhone" in Android's share sheet (M5). Opens the shared files at once (the read permission the sharing
 * app gave may not outlast this screen), queues them for the paired iPhone, says what happens next, and closes. The
 * files go over the Wi-Fi link only; they wait for it if it isn't up yet.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        Log.i(TAG, "share sheet: ${uris.size} item(s)")
        if (!AppGraph.peers.paired.value) {
            toast(R.string.share_not_paired)
            finish()
            return
        }
        if (uris.isEmpty()) {
            toast(R.string.share_nothing)
            finish()
            return
        }
        val type = intent.type
        val app = applicationContext
        val ready = AppGraph.link.wifiToken != null && AppGraph.link.peerAcceptsFiles
        val wifiOff = AppGraph.link.state.value.wifi == WifiLinkStatus.OFF
        // Opening the files can touch storage; do it off the main thread but while this screen still holds the grant.
        Thread({
            val queued = runCatching { AppGraph.files.send(uris, type) }.getOrElse {
                Log.w(TAG, "sharing failed", it)
                0
            }
            runOnUiThread {
                val message = when {
                    queued == 0 -> R.string.share_nothing
                    wifiOff -> R.string.share_wifi_off
                    ready -> R.string.share_sending
                    else -> R.string.share_waiting
                }
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
                finish()
            }
        }, "pairline-share").start()
    }

    private fun toast(message: Int) = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()

    private fun sharedUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }.take(MAX_FILES)
    }

    private companion object {
        const val TAG = "share"
        const val MAX_FILES = 20
    }
}
