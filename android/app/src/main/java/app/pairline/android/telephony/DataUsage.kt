package app.pairline.android.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.telephony.TelephonyManager
import app.pairline.android.R
import app.pairline.android.protocol.ErrorCode
import app.pairline.android.protocol.Msg
import app.pairline.android.protocol.RequestException
import app.pairline.android.tools.AccessPrompt
import org.json.JSONObject

/**
 * Mobile data this phone used since a time the iPhone picks (`data.usage`, D-084): Android's own network statistics,
 * read with Usage access, which only the user can give (Settings › Apps › Special access › Usage access). Only the
 * total in bytes goes to the iPhone, never which apps used it.
 */
class DataUsage(private val context: Context, private val sims: SimDirectory) {

    fun accessGranted(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** The `data.usage` reply: mobile bytes (received and sent, every SIM) from [since] until now. */
    fun toMessage(id: Long, since: Long): JSONObject {
        val now = System.currentTimeMillis()
        if (since <= 0 || since > now || now - since > MAX_SPAN_MS) throw RequestException(ErrorCode.INVALID, "bad since")
        if (!accessGranted()) {
            AccessPrompt.show(
                context,
                AccessPrompt.ID_USAGE_ACCESS,
                R.string.usage_access_title,
                R.string.usage_access_text,
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            )
            throw RequestException(ErrorCode.PERMISSION, "usage access not given")
        }
        return JSONObject()
            .put("t", Msg.DATA_USAGE)
            .put("id", id)
            .put("since", since)
            .put("until", now)
            .put("bytes", mobileBytes(since, now))
    }

    /**
     * Android 10+ accepts no subscriber id for mobile and then counts every mobile network. Android 9 needs each
     * SIM's subscriber id, which READ_PHONE_STATE still gives there.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    @Suppress("DEPRECATION")
    private fun mobileBytes(since: Long, until: Long): Long {
        val stats = context.getSystemService(NetworkStatsManager::class.java)
            ?: throw RequestException(ErrorCode.UNSUPPORTED, "no network statistics")
        val subscribers: List<String?> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(null)
        } else {
            if (!Perms.granted(context, Manifest.permission.READ_PHONE_STATE)) {
                throw RequestException(ErrorCode.PERMISSION, "READ_PHONE_STATE not granted")
            }
            val tm = context.getSystemService(TelephonyManager::class.java)
            sims.activeSubscriptions().mapNotNull { info ->
                runCatching { tm?.createForSubscriptionId(info.subscriptionId)?.subscriberId }.getOrNull()
            }.ifEmpty { listOf(null) }
        }
        var total = 0L
        for (subscriber in subscribers) {
            val bucket = try {
                stats.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, subscriber, since, until)
            } catch (e: SecurityException) {
                throw RequestException(ErrorCode.PERMISSION, "usage access not given")
            } catch (e: Exception) {
                throw RequestException(ErrorCode.FAILED, "network statistics unavailable")
            }
            total += bucket.rxBytes.coerceAtLeast(0) + bucket.txBytes.coerceAtLeast(0)
        }
        return total
    }

    private companion object {
        /** A billing month and some: older figures aren't kept by every phone anyway. */
        const val MAX_SPAN_MS = 62L * 24 * 3600 * 1000
    }
}
