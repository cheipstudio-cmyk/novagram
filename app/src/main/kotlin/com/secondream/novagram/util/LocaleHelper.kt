package com.secondream.novagram.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Wraps a Context so that resource lookups use a user-selected locale instead
 * of the device default. This is the per-app locale mechanism Telegram uses,
 * implemented manually because AppCompatDelegate.setApplicationLocales does
 * not propagate to ComponentActivity subclasses (our MainActivity).
 */
object LocaleHelper {
    /** Languages we actually ship strings for. "it" is the default `values`. */
    private val SUPPORTED = setOf("it", "en", "es", "de", "fr")

    fun wrap(ctx: Context, languageTag: String): Context {
        val effective: String = if (languageTag.isNotBlank() && languageTag != "system") {
            // Explicit choice from Settings — honour it as-is.
            languageTag
        } else {
            // "system"/first launch: adopt the device language if we translate
            // it, otherwise fall back to English (NOT the Italian default).
            val deviceLang = (ctx.resources.configuration.locales.get(0)
                ?: Locale.getDefault()).language
            if (deviceLang in SUPPORTED) return ctx  // let Android resolve it normally
            else "en"
        }
        val locale = Locale.forLanguageTag(effective)
        Locale.setDefault(locale)
        val config = Configuration(ctx.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return ctx.createConfigurationContext(config)
    }
}
