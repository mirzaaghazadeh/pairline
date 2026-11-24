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

    /** The Wi-Fi link (M5): listen on the local network for the paired iPhone. On by default (PRIVACY.md). */
    var wifiLinkEnabled: Boolean
        get() = prefs.getBoolean(WIFI_LINK, true)
        set(value) = edit { putBoolean(WIFI_LINK, value) }

    /** The band asked for when the bridge hotspot starts: "auto", "2.4" or "5" (Android 16+ only). */
    var hotspotBand: String
        get() = prefs.getString(HOTSPOT_BAND, "auto") ?: "auto"
        set(value) = edit { putString(HOTSPOT_BAND, value) }

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

    /** "Talk on iPhone" is turned on: relay call audio through Pairline's helper when the iPhone asks (D-047, D-054). */
    var talkOnIphone: Boolean
        get() = prefs.getBoolean(TALK_ON_IPHONE, false)
        set(value) = edit { putBoolean(TALK_ON_IPHONE, value) }

    /** Pairline is paired with this phone's Wireless debugging (D-054); cleared when adbd stops accepting its key. */
    var adbPaired: Boolean
        get() = prefs.getBoolean(ADB_PAIRED, false)
        set(value) = edit { putBoolean(ADB_PAIRED, value) }

    /**
     * The user stopped Pairline (Settings › Stop Pairline): the link service and the helper stay off, also after a
     * reboot or an update, until the user starts it again.
     */
    var bridgeStopped: Boolean
        get() = prefs.getBoolean(BRIDGE_STOPPED, false)
        set(value) = edit { putBoolean(BRIDGE_STOPPED, value) }

    /**
     * The capture source that relays the caller's voice, as learned on this phone: Android 13's interception API
     * (PrivilegedService.SOURCE_INTERCEPT), VOICE_DOWNLINK (3) or VOICE_CALL (4); 0 until something was learned, then
     * the best the phone offers comes first. Changes when a source doesn't open or the iPhone couldn't hear the caller.
     */
    var audioCaptureSource: Int
        get() = prefs.getInt(AUDIO_SOURCE, 0)
        set(value) = edit { putInt(AUDIO_SOURCE, value) }

    /** Mute this phone's own microphone while the iPhone's voice is fed into the call. Flips on "they couldn't hear me". */
    var audioMuteBridgeMic: Boolean
        get() = prefs.getBoolean(AUDIO_MUTE_MIC, true)
        set(value) = edit { putBoolean(AUDIO_MUTE_MIC, value) }

    /** Consecutive calls where the other side couldn't hear the iPhone. After two, talking is reported as unsupported. */
    var audioTalkFailures: Int
        get() = prefs.getInt(AUDIO_TALK_FAILURES, 0)
        set(value) = edit { putInt(AUDIO_TALK_FAILURES, value) }

    /** The user lets the paired iPhone use this phone's internet (M8, D-048). Off by default. */
    var netShareAllowed: Boolean
        get() = prefs.getBoolean(NET_SHARE_ALLOWED, false)
        set(value) = edit { putBoolean(NET_SHARE_ALLOWED, value) }

    /** While sharing, Iranian (.ir) sites go direct instead of through this phone's VPN. */
    var netShareDirectIran: Boolean
        get() = prefs.getBoolean(NET_SHARE_DIRECT_IR, false)
        set(value) = edit { putBoolean(NET_SHARE_DIRECT_IR, value) }

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
        const val WIFI_LINK = "wifi_link"
        const val HOTSPOT_BAND = "hotspot_band"
        const val LANGUAGE = "language"
        const val PEER_NAME = "peer_name"
        const val PEER_OS = "peer_os"
        const val PEER_APP = "peer_app"
        const val PAIRED_AT = "paired_at"
        const val LAST_SEEN = "last_seen"
        const val COMPANION_BOUND = "companion_bound_at"
        const val TALK_ON_IPHONE = "talk_on_iphone"
        const val ADB_PAIRED = "adb_paired"
        const val BRIDGE_STOPPED = "bridge_stopped"
        const val AUDIO_SOURCE = "audio_capture_source"
        const val AUDIO_MUTE_MIC = "audio_mute_bridge_mic"
        const val AUDIO_TALK_FAILURES = "audio_talk_failures"
        const val NET_SHARE_ALLOWED = "net_share_allowed"
        const val NET_SHARE_DIRECT_IR = "net_share_direct_ir"
    }
}
