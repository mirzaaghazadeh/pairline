package app.pairline.android

import android.app.Application
import app.pairline.android.files.FileNotifications
import app.pairline.android.link.LinkService
import app.pairline.android.log.Log
import app.pairline.android.ui.Notifications

class PairlineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.init(java.io.File(noBackupFilesDir, "logs"), logcat = BuildConfig.DEBUG)
        // A crash goes into the local log first, so the user can see and share it from diagnostics (rule 9).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { Log.crash(thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
        AppGraph.init(this)
        Notifications.createChannels(this)
        FileNotifications(this).createChannel()
        Log.i("app", "Pairline ${BuildConfig.VERSION_NAME} started on Android ${android.os.Build.VERSION.SDK_INT}")
        // Only listens for Shizuku (if the user has it); starts nothing privileged by itself (D-046).
        AppGraph.privileged.start()
        if (AppGraph.prefs.setupDone) LinkService.start(this, "app start")
    }
}
