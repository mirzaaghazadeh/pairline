package app.pairline.android.ui.talk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.pairline.android.AppGraph
import app.pairline.android.MainActivity
import app.pairline.android.R
import app.pairline.android.link.LinkService
import app.pairline.android.log.Log
import app.pairline.android.ui.LocaleHelper
import app.pairline.android.ui.Route
import app.pairline.android.ui.UiEvents

/**
 * `talk.setup` from the iPhone (D-107): its user wants calls on the iPhone. Turns the Talk on iPhone switch on (the
 * user asking, from their other phone) and shows the setup screen: at once while Pairline is on screen, otherwise
 * through a notification that opens it. Changes no system setting (AGENTS.md rule 5): the one-time steps there are the
 * user's.
 */
object TalkPrompt {
    const val EXTRA_OPEN = "app.pairline.android.OPEN"
    const val OPEN_TALK = "talk"
    private const val CHANNEL = "talk"
    private const val ID = 4703

    /** False on Android 10 or older, where Talk on iPhone can't work (D-056). Any thread. */
    fun request(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            AppGraph.prefs.talkOnIphone = true
            AppGraph.privileged.refresh()
            LinkService.refresh(app)
            if (UiEvents.resumed) {
                UiEvents.open.value = Route.Talk
            } else {
                show(app)
            }
        }
        return true
    }

    private fun show(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Log.i("talk", "notifications are off; Talk on iPhone's setup waits on the home screen")
            return
        }
        val r = LocaleHelper.wrap(context).resources
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, r.getString(R.string.talk_reminder_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            ID,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN, OPEN_TALK)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = r.getString(R.string.talk_setup_n_text)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pairline)
            .setColor(ContextCompat.getColor(context, R.color.brand_blue))
            .setContentTitle(r.getString(R.string.talk_setup_n_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(ID, notification)
        } catch (_: SecurityException) {
            // Notifications not allowed after all.
        }
    }

    /** The setup screen opened (or the helper runs): the notification has done its job. */
    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID)
    }
}
