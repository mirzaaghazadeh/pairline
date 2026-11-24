package app.pairline.android.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permissions, grouped the way the setup wizard asks for them. Every one is in docs/PRIVACY.md. */
object Perms {
    val BLUETOOTH: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyList() // BLUETOOTH and BLUETOOTH_ADMIN are granted at install on Android 11
    }

    val SMS = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)

    val PHONE = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
    )

    /** Reading for history and caller numbers; writing only to delete calls the user deletes on the iPhone (D-043). */
    val CALL_LOG = listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)

    val CONTACTS = listOf(Manifest.permission.READ_CONTACTS)

    val WRITE_CONTACTS = listOf(Manifest.permission.WRITE_CONTACTS)

    val NOTIFICATIONS: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyList()
    }

    /** Every runtime permission the app may ask for. */
    val ALL: List<String> = BLUETOOTH + SMS + PHONE + CALL_LOG + CONTACTS + WRITE_CONTACTS + NOTIFICATIONS

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun allGranted(context: Context, permissions: Collection<String>): Boolean =
        permissions.all { granted(context, it) }

    fun grantedSet(context: Context): Set<String> = ALL.filterTo(HashSet()) { granted(context, it) }
}
