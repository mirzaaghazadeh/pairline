package app.pairline.android

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Small settings and link facts. Lives in app-private storage, which is excluded from backups
 * (allowBackup="false" and data_extraction_rules.xml). Holds no message content.
 */
class AppPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pairline", Context.MODE_PRIVATE)

    private val _changes = MutableStateFlow(0)

    /** Bumps on every change made through this class, so the UI can recompose. */
    val changes: StateFlow<Int> = _changes

    var setupDone: Boolean
        get() = prefs.getBoolean(SETUP_DONE, false)
        set(value) = edit { putBoolean(SETUP_DONE, value) }

    /** Allow `contacts.add` from the iPhone. Off by default (PRIVACY.md). */
    var contactsAddEnabled: Boolean
        get() = prefs.getBoolean(CONTACTS_ADD, false)
        set(value) = edit { putBoolean(CONTACTS_ADD, value) }

    /** Per-app language tag on Android 11–12 ("" = follow the system). Android 13+ uses LocaleManager. */
    var languageTag: String
        get() = prefs.getString(LANGUAGE, "") ?: ""
        set(value) = edit { putString(LANGUAGE, value) }

    /** The paired iPhone's model name from its `device.info` (never a user-chosen name). */
    var peerName: String?
        get() = prefs.getString(PEER_NAME, null)
        set(value) = edit { putString(PEER_NAME, value) }

    var peerOs: String?
        get() = prefs.getString(PEER_OS, null)
        set(value) = edit { putString(PEER_OS, value) }

    var peerApp: String?
        get() = prefs.getString(PEER_APP, null)
        set(value) = edit { putString(PEER_APP, value) }

    var pairedAt: Long
        get() = prefs.getLong(PAIRED_AT, 0)
        set(value) = edit { putLong(PAIRED_AT, value) }

    var lastSeen: Long
        get() = prefs.getLong(LAST_SEEN, 0)
        set(value) = edit { putLong(LAST_SEEN, value) }

    /** Set once Android has bound our companion InCallService, which proves call control works fully here. */
    var companionBoundAt: Long
        get() = prefs.getLong(COMPANION_BOUND, 0)
        set(value) = edit { putLong(COMPANION_BOUND, value) }

    /** Permissions we've asked for at least once, to tell "never asked" from "don't ask again". */
    fun wasRequested(permission: String): Boolean = prefs.getBoolean("asked:$permission", false)

    fun markRequested(permissions: Collection<String>) = edit {
        permissions.forEach { putBoolean("asked:$it", true) }
    }

    fun clearPeer() = edit {
        remove(PEER_NAME)
        remove(PEER_OS)
        remove(PEER_APP)
        remove(PAIRED_AT)
        remove(LAST_SEEN)
    }

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _changes.value++
    }

    private companion object {
        const val SETUP_DONE = "setup_done"
        const val CONTACTS_ADD = "contacts_add"
        const val LANGUAGE = "language"
        const val PEER_NAME = "peer_name"
        const val PEER_OS = "peer_os"
        const val PEER_APP = "peer_app"
        const val PAIRED_AT = "paired_at"
        const val LAST_SEEN = "last_seen"
        const val COMPANION_BOUND = "companion_bound_at"
    }
}
