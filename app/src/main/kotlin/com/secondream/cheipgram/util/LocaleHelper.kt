package com.secondream.cheipgram.util

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
    fun wrap(ctx: Context, languageTag: String): Context {
        if (languageTag.isBlank() || languageTag == "system") return ctx
        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        val config = Configuration(ctx.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return ctx.createConfigurationContext(config)
    }
}
