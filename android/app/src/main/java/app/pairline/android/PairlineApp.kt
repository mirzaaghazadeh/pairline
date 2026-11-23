package app.pairline.android

import android.app.Application
import app.pairline.android.link.LinkService
import app.pairline.android.log.Log
import app.pairline.android.ui.Notifications

class PairlineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        Log.init(java.io.File(noBackupFilesDir, "logs"), logcat = BuildConfig.DEBUG)
        Notifications.createChannels(this)
        Log.i("app", "Pairline ${BuildConfig.VERSION_NAME} started on Android ${android.os.Build.VERSION.SDK_INT}")
        if (AppGraph.prefs.setupDone) LinkService.start(this, "app start")
    }
}
