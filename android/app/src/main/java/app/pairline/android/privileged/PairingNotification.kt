package app.pairline.android.privileged

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.pairline.android.AppGraph
import app.pairline.android.MainActivity
import app.pairline.android.R
import app.pairline.android.link.LinkService
import app.pairline.android.privileged.PrivilegedManager.Pairing

/**
 * The pairing code goes into a notification (D-054): Android closes "Pair device with pairing code" when the user
 * leaves that screen, so the code is typed into Pairline's notification from the shade. The notification pops up
 * when Pairline sees the pairing screen open, and again a few times while it waits for the code (D-064): a pop-up
 * lasts about 5 seconds, and people reading the code on screen missed it.
 */
object PairingNotification {
    /** High importance (it pops up), without sound or vibration, since it may pop up more than once. */
    private const val CHANNEL = "talk_pairing_code"
    private const val OLD_CHANNEL = "talk_pairing"
    private const val ID = 4703

    /** Its own group, so Android never folds it under the link's notification, which hid "Enter code" (D-064). */
    private const val GROUP = "app.pairline.android.PAIRING"
    private const val ACTION_CODE = "app.pairline.android.action.PAIRING_CODE"
    private const val ACTION_CANCEL = "app.pairline.android.action.PAIRING_CANCEL"
    const val KEY_CODE = "code"
    private const val NUDGE_MS = 8_000L
    private const val MAX_NUDGES = 4

    private val main = Handler(Looper.getMainLooper())
    private var nudges = 0
    private var nudge: Runnable? = null

    fun show(context: Context, stage: Pairing) {
        val app = context.applicationContext
        // While Pairline waits for the code, pop the notification up again a few times; any other stage stops that.
        nudge?.let(main::removeCallbacks)
        nudge = null
        if (stage == Pairing.FOUND) {
            if (nudges < MAX_NUDGES) {
                nudge = Runnable {
                    if (AppGraph.privileged.pairing.value == Pairing.FOUND) {
                        nudges++
                        show(app, Pairing.FOUND)
                    }
                }.also { main.postDelayed(it, NUDGE_MS) }
            }
        } else {
            nudges = 0
        }
        val manager = NotificationManagerCompat.from(app)
        if (stage == Pairing.IDLE) return manager.cancel(ID)
        app.getSystemService(NotificationManager::class.java)?.let { system ->
            system.deleteNotificationChannel(OLD_CHANNEL)
            system.createNotificationChannel(
                NotificationChannel(CHANNEL, app.getString(R.string.pair_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
        val (title, text) = when (stage) {
            Pairing.WAITING -> R.string.pair_n_waiting_title to R.string.pair_n_waiting_text
            // Android pops an update up again only when its text changed, so reminders alternate the wording.
            Pairing.FOUND -> R.string.pair_n_found_title to
                if (nudges % 2 == 1) R.string.pair_n_found_again_text else R.string.pair_n_found_text
            Pairing.PAIRING -> R.string.pair_n_pairing_title to R.string.pair_n_pairing_text
            Pairing.WRONG_CODE -> R.string.pair_n_wrong_title to R.string.pair_n_wrong_text
            Pairing.FAILED -> R.string.pair_n_failed_title to R.string.pair_n_failed_text
            Pairing.DONE -> R.string.pair_n_done_title to R.string.pair_n_done_text
            Pairing.IDLE -> return
        }
        val open = PendingIntent.getActivity(
            app, ID,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pairline)
            .setContentTitle(app.getString(title))
            .setContentText(app.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(app.getString(text)))
            .setContentIntent(open)
            .setGroup(GROUP)
            .setOnlyAlertOnce(stage == Pairing.WAITING || stage == Pairing.PAIRING)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(stage == Pairing.DONE)
            .setOngoing(stage == Pairing.PAIRING)
        if (stage != Pairing.PAIRING && stage != Pairing.DONE) {
            val input = RemoteInput.Builder(KEY_CODE).setLabel(app.getString(R.string.pair_n_code_hint)).build()
            // RemoteInput fills in the typed code, so this intent must be mutable; it's explicit (our receiver only).
            val codeIntent = PendingIntent.getBroadcast(
                app, ID,
                Intent(app, PairingCodeReceiver::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
            )
            builder.addAction(
                NotificationCompat.Action.Builder(0, app.getString(R.string.pair_n_enter), codeIntent)
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .build(),
            )
            val cancelIntent = PendingIntent.getBroadcast(
                app, ID + 1,
                Intent(app, PairingCodeReceiver::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, app.getString(R.string.pair_n_cancel), cancelIntent)
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
                // Pairing takes several seconds: run it in the link's foreground service, which Android keeps alive,
                // not in this receiver, which Android may end (with the app) once it returns.
                if (LinkService.pairWithCode(context, code)) return
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

/**
 * Opening Developer options for the first setup. (Once set up, Pairline turns the debugging settings back on by itself
 * when its helper needs them, D-059.)
 */
object DeveloperSettings {
    /**
     * The Wireless debugging page itself, with "Pair device with pairing code" on it (D-064). The Settings app opens it
     * for the long-press on its Wireless debugging Quick Settings tile (Android 11+). Without it, people land in the
     * long Developer options list and must tap the words "Wireless debugging", not its switch, which turns it off.
     */
    fun wirelessDebuggingPage(): Intent = Intent(TileService.ACTION_QS_TILE_PREFERENCES)
        .setPackage(SETTINGS)
        .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(SETTINGS, "$SETTINGS.development.qstile.DevelopmentTiles\$WirelessDebugging"))

    private const val SETTINGS = "com.android.settings"

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
