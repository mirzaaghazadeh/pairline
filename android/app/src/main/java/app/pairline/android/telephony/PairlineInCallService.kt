package app.pairline.android.telephony

import android.content.Intent
import android.os.IBinder
import android.telecom.Call
import android.telecom.InCallService
import app.pairline.android.AppGraph

/**
 * Companion InCallService (CALL_COMPANION_APP). Android binds it during calls on phones where companion apps
 * are supported, without making Pairline the phone app; the regular dialer keeps showing the call.
 * Whether Android binds it varies by version and maker (ROADMAP M3 spike); the telephony callbacks in
 * [CallTracker] cover phones where it isn't bound.
 */
class PairlineInCallService : InCallService() {
    override fun onBind(intent: Intent?): IBinder? {
        AppGraph.calls.onServiceBound(this)
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AppGraph.calls.onServiceUnbound()
        return super.onUnbind(intent)
    }

    override fun onCallAdded(call: Call) {
        AppGraph.calls.onCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        AppGraph.calls.onCallRemoved(call)
    }
}
