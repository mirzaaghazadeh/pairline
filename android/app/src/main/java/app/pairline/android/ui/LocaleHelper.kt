package app.pairline.android.ui

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import app.pairline.android.AppGraph
import java.util.Locale

/**
 * Per-app language (D-019). Android 13+ stores it in the system (LocaleManager, also shown in Android's own
 * app-language settings through locales_config.xml). Android 11–12 has no such setting, so we keep the choice
 * in our preferences and apply it to each Activity and Service context.
 */
object LocaleHelper {
    /** Language tags offered in Settings; "" follows the system. */
    val SUPPORTED = listOf("", "en", "fa", "ar", "tr")

    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            return if (locales == null || locales.isEmpty) "" else locales[0].language
        }
        return AppGraph.prefs.languageTag
    }

    /** Applies a language. On Android 13+ the system recreates activities itself; below that, the caller does. */
    fun set(context: Context, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            AppGraph.prefs.languageTag = tag
        }
    }

    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = runCatching { base.getSharedPreferences("pairline", Context.MODE_PRIVATE).getString("language", "") }
            .getOrNull().orEmpty()
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
