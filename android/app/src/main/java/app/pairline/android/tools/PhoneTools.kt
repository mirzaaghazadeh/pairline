package app.pairline.android.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.pairline.android.R
import app.pairline.android.log.Log
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.RequestException
import app.pairline.android.telephony.Perms
import app.pairline.android.ui.LocaleHelper

/**
 * "Find phone" (`phone.find`, D-074): the iPhone makes this phone ring loudly, on the alarm stream at full volume, so
 * it's heard even on silent, for a minute or until stopped from either phone. The alarm volume is put back after.
 */
object FindPhone {
    private const val CHANNEL = "find"
    private const val NOTIFICATION_ID = 3
    const val ACTION_STOP = "app.pairline.android.FIND_STOP"
    private const val RING_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var volumeBefore: Int? = null
    private var timeout: Runnable? = null

    @Synchronized
    fun start(context: Context) {
        val c = context.applicationContext
        if (player != null) return
        val am = c.getSystemService(AudioManager::class.java)
        val uri = RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_RINGTONE)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        try {
            if (am != null) {
                volumeBefore = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            }
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(c, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't ring", e)
            stop(c, "failed")
            throw RequestException(ErrorCode.FAILED, "couldn't play a sound")
        }
        showNotification(c)
        val stopLater = Runnable { stop(c, "time's up") }
        timeout = stopLater
        main.postDelayed(stopLater, RING_MS)
        Log.i(TAG, "ringing for the iPhone")
    }

    @Synchronized
    fun stop(context: Context, why: String) {
        timeout?.let(main::removeCallbacks)
        timeout = null
        val p = player ?: return
        player = null
        runCatching { p.stop() }
        p.release()
        val am = context.getSystemService(AudioManager::class.java)
        volumeBefore?.let { v -> runCatching { am?.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        volumeBefore = null
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        Log.i(TAG, "stopped ringing ($why)")
    }

    private fun showNotification(context: Context) {
        if (!Perms.allGranted(context, Perms.NOTIFICATIONS)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val r = LocaleHelper.wrap(context).resources
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, r.getString(R.string.find_channel), NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) },
        )
        val stop = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            Intent(context, FindPhoneReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pairline)
            .setColor(ContextCompat.getColor(context, R.color.brand_blue))
            .setContentTitle(r.getString(R.string.find_title))
            .setContentText(r.getString(R.string.find_text))
            .setDeleteIntent(stop)
            .addAction(0, r.getString(R.string.find_stop), stop)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    private const val TAG = "find"
}

/** The "Stop" button (or a swipe) on Find phone's notification. Only this app's own notification sends it. */
class FindPhoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == FindPhone.ACTION_STOP) FindPhone.stop(context, "stopped on this phone")
    }
}

/**
 * The ringer mode (`phone.ringer`, D-074): sound, vibrate or silent, switched from the iPhone when the user picks one
 * there. Silent (and leaving it) is Do Not Disturb to Android, which needs the user's "Do Not Disturb access" for
 * Pairline: without it the bridge asks for it in a notification.
 */
object Ringer {
    const val NORMAL = "normal"
    const val VIBRATE = "vibrate"
    const val SILENT = "silent"
    private const val CHANNEL = "find"
    private const val NOTIFICATION_ID = 4

    fun mode(context: Context): String? = when (context.getSystemService(AudioManager::class.java)?.ringerMode) {
        AudioManager.RINGER_MODE_NORMAL -> NORMAL
        AudioManager.RINGER_MODE_VIBRATE -> VIBRATE
        AudioManager.RINGER_MODE_SILENT -> SILENT
        else -> null
    }

    fun set(context: Context, mode: String) {
        val am = context.getSystemService(AudioManager::class.java)
            ?: throw RequestException(ErrorCode.UNSUPPORTED, "no audio service")
        val target = when (mode) {
            NORMAL -> AudioManager.RINGER_MODE_NORMAL
            VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            SILENT -> AudioManager.RINGER_MODE_SILENT
            else -> throw RequestException(ErrorCode.INVALID, "unknown mode")
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        val touchesDnd = target == AudioManager.RINGER_MODE_SILENT || am.ringerMode == AudioManager.RINGER_MODE_SILENT
        if (touchesDnd && nm?.isNotificationPolicyAccessGranted != true) {
            askForAccess(context)
            throw RequestException(ErrorCode.PERMISSION, "needs Do Not Disturb access")
        }
        try {
            am.ringerMode = target
        } catch (e: SecurityException) {
            askForAccess(context)
            throw RequestException(ErrorCode.PERMISSION, "needs Do Not Disturb access")
        }
        Log.i("ringer", "ringer set to $mode from the iPhone")
    }

    /** Points the user at Android's Do Not Disturb access screen, where Pairline Bridge is listed. */
    private fun askForAccess(context: Context) {
        if (!Perms.allGranted(context, Perms.NOTIFICATIONS)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val r = LocaleHelper.wrap(context).resources
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, r.getString(R.string.find_channel), NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) },
        )
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pairline)
            .setColor(ContextCompat.getColor(context, R.color.brand_blue))
            .setContentTitle(r.getString(R.string.ringer_access_title))
            .setContentText(r.getString(R.string.ringer_access_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.getString(R.string.ringer_access_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}
