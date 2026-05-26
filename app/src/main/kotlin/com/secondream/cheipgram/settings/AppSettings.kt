package com.secondream.cheipgram.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "telegram_light_prefs")

data class ApiConfig(val apiId: Int, val apiHash: String)

/** Light, Dark or follow system. Persisted as the lowercase enum name. */
enum class ThemeMode { System, Light, Dark }

/** Accent color preset. Maps to a primary Color in Theme.kt. */
enum class AccentColor { Amber, Blue, Green, Violet }

/**
 * App language preference. "system" means honour the device locale, anything
 * else is a BCP-47 tag we pass to AppCompatDelegate.setApplicationLocales.
 */
data class AppearancePrefs(
    val themeMode: ThemeMode = ThemeMode.Dark,
    val accentColor: AccentColor = AccentColor.Amber,
    val languageTag: String = "system"
)

object AppSettings {
    private lateinit var appContext: Context

    private val API_ID = intPreferencesKey("api_id")
    private val API_HASH = stringPreferencesKey("api_hash")
    private val THEME_MODE = stringPreferencesKey("theme_mode")
    private val ACCENT_COLOR = stringPreferencesKey("accent_color")
    private val LANGUAGE_TAG = stringPreferencesKey("language_tag")

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    val apiConfig: Flow<ApiConfig>
        get() = appContext.dataStore.data.map { prefs ->
            ApiConfig(
                apiId = prefs[API_ID] ?: 0,
                apiHash = prefs[API_HASH] ?: ""
            )
        }

    suspend fun setApiConfig(apiId: Int, apiHash: String) {
        appContext.dataStore.edit { prefs ->
            prefs[API_ID] = apiId
            prefs[API_HASH] = apiHash
        }
    }

    val appearance: Flow<AppearancePrefs>
        get() = appContext.dataStore.data.map { prefs ->
            AppearancePrefs(
                themeMode = parseEnumOrNull<ThemeMode>(prefs[THEME_MODE]) ?: ThemeMode.Dark,
                accentColor = parseEnumOrNull<AccentColor>(prefs[ACCENT_COLOR]) ?: AccentColor.Amber,
                languageTag = prefs[LANGUAGE_TAG] ?: "system"
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        appContext.dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setAccentColor(color: AccentColor) {
        appContext.dataStore.edit { it[ACCENT_COLOR] = color.name }
    }

    suspend fun setLanguageTag(tag: String) {
        appContext.dataStore.edit { it[LANGUAGE_TAG] = tag }
    }

    /**
     * Synchronous helper used at Application startup to apply the saved
     * locale before the first activity is shown. Reads the current snapshot
     * via runBlocking; this is fine because DataStore loads from a memory
     * cache after the first read.
     */
    suspend fun currentLanguageTag(): String = appearance.first().languageTag

    private inline fun <reified E : Enum<E>> parseEnumOrNull(name: String?): E? {
        if (name == null) return null
        return enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
