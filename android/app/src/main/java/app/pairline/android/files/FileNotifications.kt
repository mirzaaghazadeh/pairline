package app.pairline.android.files

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.pairline.android.AppGraph
import app.pairline.android.R
import app.pairline.android.telephony.Perms

/**
 * One notification per transfer: progress with a Cancel action, then "Sent" or "Received — tap to open". Shows the
 * file's name (on this phone only; names are never logged).
 */
class FileNotifications(private val context: Context) {
    private val lastShown = HashMap<Long, Long>()

    fun createChannel() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notif_channel_files), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.notif_channel_files_desc) },
        )
    }

    fun show(item: TransferItem) {
        if (!Perms.allGranted(context, Perms.NOTIFICATIONS)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val running = item.phase == TransferPhase.WAITING || item.phase == TransferPhase.OFFERED ||
            item.phase == TransferPhase.ACTIVE
        if (running) {
            // Progress updates at most every half second per transfer.
            val now = System.currentTimeMillis()
            synchronized(lastShown) {
                val last = lastShown[item.id] ?: 0
                if (item.phase == TransferPhase.ACTIVE && item.transferred > 0 && now - last < 500) return
                lastShown[item.id] = now
            }
        } else {
            synchronized(lastShown) { lastShown.remove(item.id) }
        }
        val r = context.resources
        val text = when (item.phase) {
            TransferPhase.WAITING -> r.getString(R.string.file_waiting_wifi)
            TransferPhase.OFFERED, TransferPhase.ACTIVE ->
                r.getString(if (item.outgoing) R.string.file_sending else R.string.file_receiving)
            TransferPhase.DONE -> r.getString(
                when {
                    item.outgoing -> R.string.file_sent
                    android.os.Build.VERSION.SDK_INT >= 29 -> R.string.file_received
                    else -> R.string.file_received_legacy
                },
            )
            TransferPhase.CANCELLED -> r.getString(R.string.file_cancelled)
            TransferPhase.FAILED -> listOfNotNull(
                r.getString(if (item.outgoing) R.string.file_failed_send else R.string.file_failed_receive),
                reasonText(item.reason),
            ).joinToString(" ")
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(if (item.outgoing) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setColor(ContextCompat.getColor(context, R.color.brand_blue))
            .setContentTitle(item.name)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(running)
            .setAutoCancel(!running)
        if (running) {
            val indeterminate = item.phase != TransferPhase.ACTIVE || item.size <= 0
            val percent = if (item.size > 0) (item.transferred * 100 / item.size).toInt() else 0
            builder.setProgress(100, percent, indeterminate)
            builder.addAction(0, r.getString(R.string.action_cancel), cancelIntent(item.id))
        }
        if (item.phase == TransferPhase.DONE && !item.outgoing && item.uri != null) {
            builder.setContentIntent(openIntent(item))
        }
        runCatching { nm.notify(TAG_PREFIX, (BASE_ID + item.id).toInt(), builder.build()) }
    }

    private fun reasonText(reason: String?): String? = when (reason) {
        "wifi_lost" -> context.getString(R.string.file_reason_wifi_lost)
        "too_large" -> context.getString(R.string.file_reason_too_large)
        "no_space" -> context.getString(R.string.file_reason_no_space)
        "declined" -> context.getString(R.string.file_reason_declined)
        "busy" -> context.getString(R.string.file_reason_busy)
        "no_answer" -> context.getString(R.string.file_reason_no_answer)
        else -> null
    }

    private fun cancelIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.toInt(),
        Intent(context, FileActionReceiver::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openIntent(item: TransferItem): PendingIntent {
        val uri = item.uri!!
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            item.id.toInt(),
            Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL = "files"
        const val ACTION_CANCEL = "app.pairline.android.action.CANCEL_TRANSFER"
        const val EXTRA_ID = "transfer"
        private const val TAG_PREFIX = "transfer"
        private const val BASE_ID = 1000L
    }
}

/** The notification's Cancel action. Not exported: only our own PendingIntents reach it. */
class FileActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FileNotifications.ACTION_CANCEL) return
        val id = intent.getLongExtra(FileNotifications.EXTRA_ID, -1)
        if (id > 0) AppGraph.files.cancel(id)
    }
}
