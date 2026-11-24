package app.pairline.android.privileged

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder

/**
 * Where Pairline's helper hands over its binder (D-054). The manifest protects it with INTERACT_ACROSS_USERS_FULL,
 * a permission only the system and the shell user hold, and [call] also checks the caller is the shell (or root)
 * user. It does nothing else.
 */
class HelperProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != HelperHandoff.METHOD_ATTACH) return null
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != ROOT_UID) throw SecurityException("only Pairline's helper may call this")
        val service = extras?.getBinder(HelperHandoff.KEY_SERVICE) ?: return null
        HelperInbox.deliver(service)
        return Bundle().apply { putBinder(HelperHandoff.KEY_TOKEN, HelperInbox.appToken) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    private companion object {
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
    }
}

/** The helper's binder, held until [PrivilegedManager] picks it up (it can arrive while the app is still starting). */
object HelperInbox {
    /** Lives as long as Pairline's process; the helper watches it to know when Pairline is gone. */
    val appToken: IBinder = Binder()

    private val lock = Any()
    private var pending: IBinder? = null
    private var listener: ((IBinder) -> Unit)? = null

    fun deliver(service: IBinder) {
        val l = synchronized(lock) {
            listener ?: run {
                pending = service
                null
            }
        }
        l?.invoke(service)
    }

    fun listen(l: (IBinder) -> Unit) {
        val waiting = synchronized(lock) {
            listener = l
            pending.also { pending = null }
        }
        waiting?.let(l)
    }
}
