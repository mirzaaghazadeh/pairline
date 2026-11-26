package app.pairline.android.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import app.pairline.android.AppGraph
import app.pairline.android.R
import app.pairline.android.link.LinkService
import app.pairline.android.telephony.Perms
import kotlinx.coroutines.flow.MutableStateFlow

/** Signals the UI uses to re-read things Android doesn't notify us about (permissions, battery exemption). */
object UiEvents {
    /** Bumps when the activity resumes or a permission request returns. */
    val refresh = MutableStateFlow(0)

    fun bump() {
        refresh.value++
    }
}

/** Re-reads on every resume or permission result. */
@Composable
fun refreshTick(): Int {
    val tick by UiEvents.refresh.collectAsState()
    return tick
}

/** The permissions the setup wizard asks for, one group per step. Every one is in docs/PRIVACY.md. */
enum class PermissionGroup(
    val permissions: List<String>,
    val required: Boolean,
    @StringRes val title: Int,
    @StringRes val why: Int,
    @StringRes val detail: Int,
    val icon: ImageVector,
) {
    BLUETOOTH(Perms.BLUETOOTH, true, R.string.perm_bt_title, R.string.perm_bt_why, R.string.perm_bt_detail, PIcons.Bluetooth),
    SMS(Perms.SMS, true, R.string.perm_sms_title, R.string.perm_sms_why, R.string.perm_sms_detail, PIcons.Sms),
    PHONE(Perms.PHONE, true, R.string.perm_phone_title, R.string.perm_phone_why, R.string.perm_phone_detail, PIcons.Phone),
    CALL_LOG(Perms.CALL_LOG, false, R.string.perm_calllog_title, R.string.perm_calllog_why, R.string.perm_calllog_detail, PIcons.History),
    CONTACTS(Perms.CONTACTS, false, R.string.perm_contacts_title, R.string.perm_contacts_why, R.string.perm_contacts_detail, PIcons.Person),
    NOTIFICATIONS(Perms.NOTIFICATIONS, true, R.string.perm_notif_title, R.string.perm_notif_why, R.string.perm_notif_detail, PIcons.Notifications),
    ;

    /** False when this Android version has nothing to ask (e.g. notifications before Android 13). */
    val applicable: Boolean get() = permissions.isNotEmpty()

    companion object {
        val wizard: List<PermissionGroup> get() = entries.filter { it.applicable }
    }
}

enum class GrantState { GRANTED, NOT_ASKED, DENIED, BLOCKED }

fun grantState(activity: Activity, group: PermissionGroup): GrantState {
    val missing = group.permissions.filterNot { Perms.granted(activity, it) }
    if (missing.isEmpty()) return GrantState.GRANTED
    val prefs = AppGraph.prefs
    if (missing.none { prefs.wasRequested(it) }) return GrantState.NOT_ASKED
    // Asked before and Android won't show the dialog again: "Don't ask again", or restricted settings.
    val blocked = missing.any { prefs.wasRequested(it) && !activity.shouldShowRequestPermissionRationale(it) }
    return if (blocked) GrantState.BLOCKED else GrantState.DENIED
}

/**
 * Android 13+ "restricted settings" (D-075): an app installed from a file (not an app store) gets "App was denied
 * access" for SMS and call history until the user allows restricted settings in its App info.
 */
object RestrictedSettings {
    private val RESTRICTED = setOf(
        Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG,
    )

    fun applies(group: PermissionGroup): Boolean = group.permissions.any { it in RESTRICTED }

    /** This copy came from a downloaded or local file, which Android 13+ restricts. Read locally. */
    fun likely(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val source = runCatching { context.packageManager.getInstallSourceInfo(context.packageName).packageSource }
            .getOrNull()
        return source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE || source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
    }

    /** Opens App info with the steps waiting in a notification ([SetupGuide]). */
    fun guide(context: Context) {
        val r = LocaleHelper.wrap(context).resources
        SetupGuide.show(context, r.getString(R.string.restricted_guide_title), r.getString(R.string.restricted_guide_steps))
        SystemActions.openAppSettings(context)
    }
}

/**
 * Returns a function that asks for a group's permissions through Android's dialog, or opens App info when
 * Android won't show the dialog anymore. The link service re-checks afterwards.
 */
@Composable
fun rememberPermissionRequester(onResult: (PermissionGroup) -> Unit = {}): (PermissionGroup) -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current ?: return {}
    val holder = remember { arrayOfNulls<PermissionGroup>(1) }
    ClearSetupGuideOnResume()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        UiEvents.bump()
        LinkService.refresh(context)
        val group = holder[0]
        // Android said "App was denied access" (restricted settings): go straight to App info, steps in hand.
        if (group != null && RestrictedSettings.applies(group) && RestrictedSettings.likely(context) &&
            group.permissions.any { !Perms.granted(context, it) && !activity.shouldShowRequestPermissionRationale(it) }
        ) {
            RestrictedSettings.guide(context)
        }
        group?.let(onResult)
    }
    return remember(launcher) {
        { group ->
            holder[0] = group
            if (grantState(activity, group) == GrantState.BLOCKED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && RestrictedSettings.applies(group)) {
                    RestrictedSettings.guide(context)
                } else {
                    SystemActions.openAppSettings(context)
                }
            } else {
                val missing = group.permissions.filterNot { Perms.granted(context, it) }
                AppGraph.prefs.markRequested(missing)
                if (missing.isEmpty()) onResult(group) else launcher.launch(missing.toTypedArray())
            }
        }
    }
}
