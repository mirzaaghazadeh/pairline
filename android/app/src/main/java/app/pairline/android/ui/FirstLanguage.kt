package app.pairline.android.ui

import android.content.Context
import android.content.res.Resources
import android.telephony.TelephonyManager
import app.pairline.android.AppGraph
import app.pairline.android.log.Log
import java.util.Locale

/**
 * The app's language on first open (D-081). Before setup has started, Pairline picks Persian, Arabic or Turkish when
 * the phone's country points to one and the phone itself is in English (or a language Pairline doesn't have). The
 * country comes from the SIM card, else the mobile network, else the time zone, else the phone's region setting: the
 * first one known decides, except that Tehran's time zone always means Persian (D-090). Setup then opens on the
 * language step, so the user sees the choice and can change it. Nothing is sent anywhere; only the chosen language is
 * kept (and logged).
 */
object FirstLanguage {
    private const val TAG = "language"

    /** Countries whose people most likely want one of Pairline's languages other than English. */
    private val LANGUAGE_BY_COUNTRY: Map<String, String> = buildMap {
        listOf("IR", "AF").forEach { put(it, "fa") }
        put("TR", "tr")
        listOf(
            "SA", "AE", "QA", "KW", "BH", "OM", "YE", "IQ", "SY", "JO", "LB", "PS",
            "EG", "LY", "TN", "DZ", "MA", "SD", "MR",
        ).forEach { put(it, "ar") }
    }

    private val OWN = setOf("fa", "ar", "tr")

    /**
     * The language to start in, or null to follow the phone. [countries] are ISO codes in order of trust (SIM,
     * network, time zone, region; blank or null when unknown); [systemLanguage] is the phone's language; [zoneCountry]
     * the time zone's country.
     */
    fun suggest(countries: List<String?>, systemLanguage: String, zoneCountry: String? = null): String? {
        if (systemLanguage.lowercase(Locale.ROOT) in OWN) return null
        // Tehran's time zone is used only in Iran: Persian whatever the SIM says (an Iranian with a foreign SIM).
        if (zoneCountry.equals("IR", ignoreCase = true)) return "fa"
        val country = countries.firstOrNull { !it.isNullOrBlank() && it != "001" } ?: return null
        return LANGUAGE_BY_COUNTRY[country.uppercase(Locale.ROOT)]
    }

    /** Called once at app start: only on a fresh install, before setup, and only if no language was chosen. */
    fun applyIfNeeded(context: Context) {
        val prefs = AppGraph.prefs
        if (prefs.setupDone || prefs.languageGuess != null) return
        if (LocaleHelper.current(context).isNotEmpty()) {
            prefs.languageGuess = ""
            return
        }
        val zone = runCatching { android.icu.util.TimeZone.getRegion(java.util.TimeZone.getDefault().id) }.getOrNull()
        val tag = suggest(countries(context, zone), systemLanguage(), zone).orEmpty()
        prefs.languageGuess = tag
        if (tag.isEmpty()) return
        LocaleHelper.set(context, tag)
        Log.i(TAG, "First open: starting in '$tag' from the phone's country")
    }

    private fun systemLanguage(): String = Resources.getSystem().configuration.locales[0].language

    private fun countries(context: Context, zone: String?): List<String?> {
        val phone = context.getSystemService(TelephonyManager::class.java)
        val sim = runCatching { phone?.simCountryIso }.getOrNull()
        val network = runCatching { phone?.networkCountryIso }.getOrNull()
        val region = Resources.getSystem().configuration.locales[0].country
        return listOf(sim, network, zone, region)
    }
}
