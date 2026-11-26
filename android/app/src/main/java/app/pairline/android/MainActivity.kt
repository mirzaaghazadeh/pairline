package app.pairline.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.pairline.android.link.LinkService
import app.pairline.android.ui.LocaleHelper
import app.pairline.android.ui.PairlineAppUi
import app.pairline.android.ui.UiEvents
import app.pairline.android.ui.theme.PairlineTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not after a rotation or language change: the invitation is already on screen.
        if (savedInstanceState == null) openInvitation(intent)
        setContent {
            PairlineTheme {
                PairlineAppUi()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openInvitation(intent)
    }

    override fun onResume() {
        super.onResume()
        // Permissions and battery settings may have changed in Android's settings.
        UiEvents.bump()
        if (AppGraph.prefs.setupDone) {
            LinkService.start(this, "app opened")
            LinkService.refresh(this)
            AppGraph.updates.checkIfDue("app opened")
        }
        AppGraph.updates.onResume()
    }

    /** `pairline://invite?…` (remote pairing, D-092): shown over everything until the user allows or cancels it. */
    private fun openInvitation(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val data = intent.data ?: return
        if (data.scheme != "pairline" || data.host != "invite") return
        AppGraph.remotePairing.open(intent.dataString ?: return)
    }
}
