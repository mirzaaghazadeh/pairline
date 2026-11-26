package app.pairline.android.telephony

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi
import app.pairline.android.AppGraph
import app.pairline.android.log.Log

/**
 * Pairline as Android's call-screening app (D-085, Android 10+, a role the user gives in a system dialog): a call from
 * a blocked number is turned away before it rings, and with "Silence unknown callers" a call from a number that isn't
 * in the contacts rings neither phone. Without the role, blocked calls are still kept off the iPhone and declined
 * when Pairline can end calls (D-043), but they ring here for a moment first.
 */
object CallScreening {
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun held(context: Context): Boolean {
        if (!supported()) return false
        val roles = context.getSystemService(RoleManager::class.java) ?: return false
        return roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && roles.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    /** Android's own dialog that asks the user to make Pairline Bridge the call-screening app. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun requestIntent(context: Context): Intent? {
        val roles = context.getSystemService(RoleManager::class.java) ?: return null
        if (!roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return null
        return roles.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
    }

    /** What the screening service decided for a recent call, so [CallTracker] keeps it off the iPhone too. */
    private class Silenced(val number: String?, val at: Long)

    @Volatile
    private var lastSilenced: Silenced? = null

    fun recordSilenced(number: String?) {
        lastSilenced = Silenced(number, System.currentTimeMillis())
    }

    /** The screening service silenced this call (same number, or a hidden one, in the last 30 seconds). */
    fun wasSilenced(number: String?): Boolean {
        val s = lastSilenced ?: return false
        if (System.currentTimeMillis() - s.at > 30_000) return false
        if (s.number == null || number == null) return s.number == number || number == null
        return PhoneNumbers.sameNumber(s.number, number)
    }

    /** Whether [number] is one of the bridge phone's contacts (READ_CONTACTS). Hidden numbers aren't. */
    fun inContacts(context: Context, number: String?): Boolean {
        if (number.isNullOrBlank() || !Perms.granted(context, Manifest.permission.READ_CONTACTS)) return false
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
                ?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }
}

/** Android binds this for every incoming call once the user made Pairline Bridge the call-screening app. */
@RequiresApi(Build.VERSION_CODES.Q)
class PairlineScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val allow = CallResponse.Builder().build()
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(details, allow)
            return
        }
        val number = details.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        val blockList = AppGraph.blockList
        val response = when {
            blockList.isBlocked(number) -> {
                Log.i(TAG, "turned away a call from a blocked number")
                CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipNotification(true)
                    .build()
            }
            blockList.silenceUnknown && !CallScreening.inContacts(this, number) -> {
                Log.i(TAG, "silenced a call from an unknown number")
                CallScreening.recordSilenced(number)
                CallResponse.Builder().setSilenceCall(true).build()
            }
            else -> allow
        }
        respondToCall(details, response)
    }

    private companion object {
        const val TAG = "screening"
    }
}
