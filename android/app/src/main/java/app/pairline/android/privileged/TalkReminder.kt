package app.pairline.android.privileged

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import app.pairline.android.MainActivity
import app.pairline.android.R

/**
 * After a restart Android turns Wireless debugging off, which stops Shizuku, so call audio falls back to the
 * Android phone. With Talk on iPhone on, this tells the user once per boot and opens Shizuku on tap. Pairline never
 * starts Shizuku or turns debugging on itself (AGENTS.md rule 5, D-046).
 */
object TalkReminder {
    private const val CHANNEL = "talk"
    private const val ID = 4702
    private const val PREFS = "talk_reminder"
    private const val SHOWN_BOOT = "shown_boot"

    fun maybeShow(context: Context) {
        val boot = bootCount(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (boot != -1 && prefs.getInt(SHOWN_BOOT, -2) == boot) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.talk_reminder_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = context.packageManager.getLaunchIntentForPackage(PrivilegedManager.SHIZUKU_PACKAGE)
            ?: Intent(context, MainActivity::class.java)
        val tap = PendingIntent.getActivity(
            context,
            ID,
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pairline)
            .setContentTitle(context.getString(R.string.talk_reminder_title))
            .setContentText(context.getString(R.string.talk_reminder_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.talk_reminder_text)))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(ID, notification)
            prefs.edit { putInt(SHOWN_BOOT, boot) }
        } catch (_: SecurityException) {
            // Notifications not allowed.
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID)
    }

    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
}
