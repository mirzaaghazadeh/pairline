package app.pairline.android

import android.content.Context
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
        setContent {
            PairlineTheme {
                PairlineAppUi()
            }
        }
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
}
