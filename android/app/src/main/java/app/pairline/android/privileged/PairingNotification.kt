package app.pairline.android.privileged

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.pairline.android.AppGraph
import app.pairline.android.MainActivity
import app.pairline.android.R
import app.pairline.android.privileged.PrivilegedManager.Pairing

/**
 * The pairing code goes into a notification (D-054): Android closes "Pair device with pairing code" when the user
 * leaves that screen, so the code is typed into Pairline's notification from the shade. The notification pops up
 * again when Pairline sees the pairing screen open.
 */
object PairingNotification {
    private const val CHANNEL = "talk_pairing"
    private const val ID = 4703
    private const val ACTION_CODE = "app.pairline.android.action.PAIRING_CODE"
    private const val ACTION_CANCEL = "app.pairline.android.action.PAIRING_CANCEL"
    const val KEY_CODE = "code"

    fun show(context: Context, stage: Pairing) {
        val manager = NotificationManagerCompat.from(context)
        if (stage == Pairing.IDLE) return manager.cancel(ID)
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.pair_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val (title, text) = when (stage) {
            Pairing.WAITING -> R.string.pair_n_waiting_title to R.string.pair_n_waiting_text
            Pairing.FOUND -> R.string.pair_n_found_title to R.string.pair_n_found_text
            Pairing.PAIRING -> R.string.pair_n_pairing_title to R.string.pair_n_pairing_text
            Pairing.WRONG_CODE -> R.string.pair_n_wrong_title to R.string.pair_n_wrong_text
            Pairing.FAILED -> R.string.pair_n_failed_title to R.string.pair_n_failed_text
            Pairing.DONE -> R.string.pair_n_done_title to R.string.pair_n_done_text
            Pairing.IDLE -> return
        }
        val open = PendingIntent.getActivity(
            context, ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pairline)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(text)))
            .setContentIntent(open)
            .setOnlyAlertOnce(stage == Pairing.WAITING || stage == Pairing.PAIRING)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(stage == Pairing.DONE)
            .setOngoing(stage == Pairing.PAIRING)
        if (stage != Pairing.PAIRING && stage != Pairing.DONE) {
            val input = RemoteInput.Builder(KEY_CODE).setLabel(context.getString(R.string.pair_n_code_hint)).build()
            // RemoteInput fills in the typed code, so this intent must be mutable; it's explicit (our receiver only).
            val codeIntent = PendingIntent.getBroadcast(
                context, ID,
                Intent(context, PairingCodeReceiver::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
            )
            builder.addAction(
                NotificationCompat.Action.Builder(0, context.getString(R.string.pair_n_enter), codeIntent)
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .build(),
            )
            val cancelIntent = PendingIntent.getBroadcast(
                context, ID + 1,
                Intent(context, PairingCodeReceiver::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, context.getString(R.string.pair_n_cancel), cancelIntent)
        }
        try {
            manager.notify(ID, builder.build())
        } catch (_: SecurityException) {
            // Notifications are off; the setup screen says so.
        }
    }

    internal fun isCode(intent: Intent) = intent.action == ACTION_CODE
    internal fun isCancel(intent: Intent) = intent.action == ACTION_CANCEL
}

/** Receives the code typed into [PairingNotification] (not exported: only our own PendingIntents reach it). */
class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = AppGraph.privileged
        when {
            PairingNotification.isCancel(intent) -> manager.cancelPairing()
            PairingNotification.isCode(intent) -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotification.KEY_CODE)?.toString().orEmpty()
                val pending = goAsync()
                AppGraph.io.execute {
                    try {
                        manager.submitPairingCode(code)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}

/** Opening Developer options. Pairline only opens the screen; the user changes the settings there (rule 5). */
object DeveloperSettings {
    /** Developer options, scrolled to Wireless debugging where the Settings app supports it. */
    fun wirelessDebugging(): Intent {
        val highlight = "toggle_adb_wireless"
        return Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", highlight)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", highlight) })
    }

    /** About phone, where tapping "Build number" seven times turns Developer options on. */
    fun aboutPhone(): Intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
}
