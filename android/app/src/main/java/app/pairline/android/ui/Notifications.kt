package app.pairline.android.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.pairline.android.MainActivity
import app.pairline.android.R
import app.pairline.android.link.BluetoothStatus
import app.pairline.android.link.LinkSnapshot
import app.pairline.android.telephony.Perms

/** The link service's persistent notification and the update notice. They never show message content or numbers. */
object Notifications {
    const val LINK_ID = 1
    const val UPDATE_ID = 2
    private const val LINK_CHANNEL = "link"
    private const val UPDATE_CHANNEL = "updates"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            LINK_CHANNEL,
            context.getString(R.string.notif_channel_link),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_link_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
        nm.createNotificationChannel(
            NotificationChannel(
                UPDATE_CHANNEL,
                context.getString(R.string.notif_channel_updates),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notif_channel_updates_desc) },
        )
    }

    /** "Pairline Bridge 1.2.0 is available": opens the app, where the user decides whether to update. */
    fun showUpdate(context: Context, version: String) {
        if (!Perms.allGranted(context, Perms.NOTIFICATIONS)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val r = LocaleHelper.wrap(context).resources
        val open = PendingIntent.getActivity(
            context,
            UPDATE_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pairline)
            .setColor(ContextCompat.getColor(context, R.color.brand_blue))
            .setContentTitle(r.getString(R.string.update_available_title, version))
            .setContentText(r.getString(R.string.update_notif_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        nm.notify(UPDATE_ID, notification)
    }

    fun linkNotification(context: Context, s: LinkSnapshot, paired: Boolean, peerName: String?): Notification {
        val (title, text) = describe(context, s, paired, peerName)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, LINK_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pairline)
            .setColor(ContextCompat.getColor(context, R.color.brand_blue))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun updateLink(context: Context, s: LinkSnapshot, paired: Boolean, peerName: String?) {
        if (!Perms.allGranted(context, Perms.NOTIFICATIONS)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(LINK_ID, linkNotification(context, s, paired, peerName))
    }

    private fun describe(context: Context, s: LinkSnapshot, paired: Boolean, peerName: String?): Pair<String, String> {
        val r = context.resources
        return when {
            s.connected -> r.getString(R.string.notif_connected_title, peerName ?: r.getString(R.string.generic_iphone)) to
                r.getString(R.string.notif_connected_text)
            s.bluetooth == BluetoothStatus.NO_PERMISSION -> r.getString(R.string.notif_permission_title) to
                r.getString(R.string.notif_permission_text)
            s.bluetooth == BluetoothStatus.OFF -> r.getString(R.string.notif_bt_off_title) to
                r.getString(R.string.notif_bt_off_text)
            !paired -> r.getString(R.string.notif_not_paired_title) to r.getString(R.string.notif_not_paired_text)
            else -> r.getString(R.string.notif_waiting_title) to r.getString(R.string.notif_waiting_text)
        }
    }
}
