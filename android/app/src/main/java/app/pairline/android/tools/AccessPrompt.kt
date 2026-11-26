package app.pairline.android.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.pairline.android.R
import app.pairline.android.telephony.Perms
import app.pairline.android.ui.LocaleHelper

/**
 * A notification that leads to one of Android's special-access screens, shown when something the user asked for on
 * the iPhone needs it (D-084): exact timing for scheduled messages, usage access for the data meter. Only the user
 * can switch these on, in Android's own Settings.
 */
object AccessPrompt {
    private const val CHANNEL = "access"

    fun show(context: Context, id: Int, title: Int, text: Int, settings: Intent) {
        if (!Perms.allGranted(context, Perms.NOTIFICATIONS)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val r = LocaleHelper.wrap(context).resources
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, r.getString(R.string.access_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            id,
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pairline)
            .setColor(ContextCompat.getColor(context, R.color.brand_blue))
            .setContentTitle(r.getString(title))
            .setContentText(r.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.getString(text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notification)
    }

    const val ID_EXACT_ALARM = 40
    const val ID_USAGE_ACCESS = 41
}
