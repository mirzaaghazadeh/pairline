package app.pairline.android.mirror

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import app.pairline.android.AppGraph
import app.pairline.android.log.Log
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.putNullable
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Android binds this once the user gives Pairline Bridge notification access (Settings › Notifications › Device &
 * app notifications). It only passes what apps post to [NotificationMirror], which mirrors the apps the user chose.
 */
class MirrorService : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        AppGraph.router.statusChanged()
    }

    override fun onListenerDisconnected() {
        instance = null
        AppGraph.router.statusChanged()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = NotificationMirror.posted(this, sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = NotificationMirror.removed(sbn)

    companion object {
        @Volatile
        var instance: MirrorService? = null
    }
}

/**
 * Notifications from apps the user chose (WhatsApp, a bank, …), mirrored to the paired iPhone (`notif.posted`,
 * D-077): the app's name, the title and the text, over the encrypted link only, never logged. When one goes away here
 * the iPhone removes it too, and dismissing it on the iPhone clears it here (`notif.dismiss`). Ongoing notifications
 * (music, navigation, downloads), group summaries and Pairline's own are left out, and so are calls and texts, which
 * Pairline already relays.
 */
object NotificationMirror {
    /** Keys sent to the iPhone, so only those are followed by `notif.removed`. */
    private val sent = ConcurrentHashMap<String, Long>()
    private val labels = ConcurrentHashMap<String, String>()

    private const val MAX_TITLE = 200
    private const val MAX_TEXT = 1_000
    private const val MAX_TRACKED = 500

    fun accessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Android's screen where the user gives notification access. */
    fun accessSettings(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun posted(context: Context, sbn: StatusBarNotification) {
        if (sbn.packageName !in AppGraph.prefs.mirrorApps || !worthMirroring(context, sbn)) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.take(MAX_TITLE)
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim()?.take(MAX_TEXT)
        if (title.isNullOrEmpty() && text.isNullOrEmpty()) return
        if (!AppGraph.link.isConnected) return
        AppGraph.link.send(
            JSONObject()
                .put("t", Msg.NOTIF_POSTED)
                .put("key", sbn.key)
                .put("app", sbn.packageName)
                .put("appName", label(context, sbn.packageName))
                .putNullable("title", title)
                .putNullable("text", text)
                .put("time", sbn.postTime),
        )
        if (sent.size > MAX_TRACKED) sent.clear()
        sent[sbn.key] = sbn.postTime
        Log.i(TAG, "mirrored a notification")
    }

    fun removed(sbn: StatusBarNotification) {
        if (sent.remove(sbn.key) == null || !AppGraph.link.isConnected) return
        AppGraph.link.send(JSONObject().put("t", Msg.NOTIF_REMOVED).put("key", sbn.key))
    }

    /** The user dismissed it on the iPhone: clear it here too. Only keys Pairline mirrored. */
    fun dismiss(key: String): Boolean {
        if (!sent.containsKey(key)) return false
        val service = MirrorService.instance ?: return false
        runCatching { service.cancelNotification(key) }.onFailure { return false }
        return true
    }

    private fun worthMirroring(context: Context, sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.packageName == context.packageName) return false
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        return n.category !in SKIPPED_CATEGORIES
    }

    private val SKIPPED_CATEGORIES = setOf(
        // "missed_call" is Notification.CATEGORY_MISSED_CALL (Android 12+); older phones just never send it.
        Notification.CATEGORY_CALL, "missed_call", Notification.CATEGORY_TRANSPORT,
        Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM,
        Notification.CATEGORY_NAVIGATION,
    )

    @Suppress("DEPRECATION") // the flags overload is Android 13+
    fun label(context: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private const val TAG = "mirror"
}
