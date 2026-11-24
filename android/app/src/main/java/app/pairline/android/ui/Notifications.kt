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
import app.pairline.android.link.TransportKind
import app.pairline.android.link.WifiLinkStatus
import app.pairline.android.telephony.Perms

/** The link service's persistent notification. It never shows message content or numbers. */
object Notifications {
    const val LINK_ID = 1
    private const val LINK_CHANNEL = "link"

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
        val wifiOnly = s.transports == setOf(TransportKind.WIFI)
        // With the Wi-Fi link listening, the iPhone can still reach us when Bluetooth can't.
        val wifiReady = s.wifi == WifiLinkStatus.LISTENING
        return when {
            s.connected -> r.getString(R.string.notif_connected_title, peerName ?: r.getString(R.string.generic_iphone)) to
                r.getString(if (wifiOnly) R.string.notif_connected_text_wifi else R.string.notif_connected_text)
            s.bluetooth == BluetoothStatus.NO_PERMISSION && !wifiReady -> r.getString(R.string.notif_permission_title) to
                r.getString(R.string.notif_permission_text)
            s.bluetooth == BluetoothStatus.OFF && !wifiReady -> r.getString(R.string.notif_bt_off_title) to
                r.getString(R.string.notif_bt_off_text)
            !paired -> r.getString(R.string.notif_not_paired_title) to r.getString(R.string.notif_not_paired_text)
            else -> r.getString(R.string.notif_waiting_title) to
                r.getString(if (wifiReady) R.string.notif_waiting_text_wifi else R.string.notif_waiting_text)
        }
    }
}
