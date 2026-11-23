package app.pairline.android.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.net.toUri
import app.pairline.android.R
import app.pairline.android.log.Log
import app.pairline.android.telephony.Perms

/**
 * Opens Android's own screens and dialogs. Pairline never changes a setting itself (AGENTS.md rule 5): every
 * action here only shows the user where to change it.
 */
object SystemActions {
    private const val TAG = "ui"
    const val SOURCE_URL = "https://github.com/mirzaaghazadeh/pairline"

    fun openAppSettings(context: Context) {
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    fun openNotificationSettings(context: Context) {
        start(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Android's own "Let app always run in background?" dialog. The user decides. */
    @SuppressLint("BatteryLife") // The link must survive Doze; the app isn't on Google Play (D-015).
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val ok = start(
            context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
        )
        if (!ok) start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** Android's own "Turn on Bluetooth?" dialog. */
    @SuppressLint("MissingPermission")
    fun requestBluetoothOn(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !Perms.granted(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            openAppSettings(context)
            return
        }
        if (!start(context, Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))) {
            start(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    fun openUrl(context: Context, url: String) {
        start(context, Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    /** Opens the share sheet with exactly [text]; nothing else is attached. */
    fun shareText(context: Context, subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        start(context, Intent.createChooser(send, subject))
    }

    fun copyText(context: Context, label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "couldn't open a settings screen", e)
        false
    }

    internal fun tryComponents(context: Context, components: List<ComponentName>): Boolean =
        components.any { start(context, Intent().setComponent(it)) }
}

/** Phone makers whose battery managers stop background apps unless the user allows them. */
enum class Maker(@StringRes val label: Int, @StringRes val steps: Int) {
    XIAOMI(R.string.maker_xiaomi, R.string.maker_xiaomi_steps),
    SAMSUNG(R.string.maker_samsung, R.string.maker_samsung_steps),
    OPPO(R.string.maker_oppo, R.string.maker_oppo_steps),
    VIVO(R.string.maker_vivo, R.string.maker_vivo_steps),
    HUAWEI(R.string.maker_huawei, R.string.maker_huawei_steps),
    OTHER(R.string.maker_other, R.string.maker_other_steps),
    ;

    /** Settings screens to try, most specific first. These only open a screen; they change nothing. */
    val screens: List<ComponentName>
        get() = when (this) {
            XIAOMI -> listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            )
            OPPO -> listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                ComponentName("com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity"),
                ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            )
            VIVO -> listOf(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            )
            HUAWEI -> listOf(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            )
            SAMSUNG, OTHER -> emptyList()
        }

    /** Opens the maker's background-start screen, or App info (battery settings live there) as a fallback. */
    fun open(context: Context) {
        if (!SystemActions.tryComponents(context, screens)) SystemActions.openAppSettings(context)
    }

    companion object {
        fun detect(manufacturer: String = Build.MANUFACTURER.orEmpty(), brand: String = Build.BRAND.orEmpty()): Maker {
            val m = "$manufacturer $brand".lowercase()
            return when {
                listOf("xiaomi", "redmi", "poco").any { it in m } -> XIAOMI
                "samsung" in m -> SAMSUNG
                listOf("oppo", "realme", "oneplus").any { it in m } -> OPPO
                listOf("vivo", "iqoo").any { it in m } -> VIVO
                listOf("huawei", "honor").any { it in m } -> HUAWEI
                else -> OTHER
            }
        }
    }
}
