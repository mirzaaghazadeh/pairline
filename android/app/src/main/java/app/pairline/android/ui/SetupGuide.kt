package app.pairline.android.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pairline.android.MainActivity
import app.pairline.android.R
import app.pairline.android.telephony.Perms

/**
 * The setup guide in place of videos (D-074): when a step sends the user into Android Settings, its exact
 * instructions wait in a notification they can pull down to read there, with a tap back to Pairline. It's cleared as
 * soon as Pairline is on screen again. Nothing but the step's own text.
 */
object SetupGuide {
    private const val CHANNEL = "guide"
    private const val ID = 5
    private const val TIMEOUT_MS = 15 * 60_000L

    fun show(context: Context, title: String, steps: String) {
        if (!Perms.allGranted(context, Perms.NOTIFICATIONS)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val r = LocaleHelper.wrap(context).resources
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, r.getString(R.string.guide_channel), NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) },
        )
        val back = PendingIntent.getActivity(
            context,
            ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = steps + "\n\n" + r.getString(R.string.guide_back)
        nm.notify(
            ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_pairline)
                .setColor(ContextCompat.getColor(context, R.color.brand_blue))
                .setContentTitle(title)
                .setContentText(steps)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(back)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(TIMEOUT_MS)
                .build(),
        )
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}

/** Clears [SetupGuide]'s notification whenever the screen using it comes back (the user returned from Settings). */
@Composable
fun ClearSetupGuideOnResume() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) SetupGuide.clear(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
