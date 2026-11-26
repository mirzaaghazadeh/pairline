package app.pairline.android.tools

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import app.pairline.android.AppGraph
import app.pairline.android.R
import app.pairline.android.log.Log
import app.pairline.android.protocol.Msg
import app.pairline.android.ui.LocaleHelper
import org.json.JSONObject

/**
 * "Ring my iPhone" (D-084), Find phone the other way round: the paired iPhone plays a loud sound for up to 30 seconds,
 * or until it's found. Works while the link is up; the iPhone must have said it can (`ring` in its `device.info`
 * `caps`). From the bridge app's home screen and a Quick Settings tile.
 */
object RingIphone {
    /** Whether the iPhone can be rung right now. */
    fun available(): Boolean = AppGraph.link.isConnected && AppGraph.link.peerCanRing

    /** Rings (or stops) the iPhone; tells the user here when it can't. Returns whether the request went out. */
    fun ring(context: Context, on: Boolean = true): Boolean {
        val r = LocaleHelper.wrap(context).resources
        if (!AppGraph.link.isConnected) {
            Toast.makeText(context, r.getString(R.string.ring_iphone_offline), Toast.LENGTH_LONG).show()
            return false
        }
        if (!AppGraph.link.peerCanRing) {
            Toast.makeText(context, r.getString(R.string.ring_iphone_update), Toast.LENGTH_LONG).show()
            return false
        }
        AppGraph.link.send(JSONObject().put("t", Msg.REMOTE_RING).put("on", on))
        if (on) Toast.makeText(context, r.getString(R.string.ring_iphone_sent), Toast.LENGTH_SHORT).show()
        Log.i("ring", if (on) "asked the iPhone to ring" else "asked the iPhone to stop ringing")
        return true
    }
}

/** The Quick Settings tile: one tap rings the iPhone. Shown as unavailable while the iPhone isn't linked. */
class RingIphoneTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = if (RingIphone.available()) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.updateTile()
    }

    override fun onClick() {
        RingIphone.ring(applicationContext)
    }
}
