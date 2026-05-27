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

/** Light, Dark, AMOLED (true black), or follow system. Persisted as the lowercase enum name. */
enum class ThemeMode { System, Light, Dark, Amoled }

/** Accent color preset. Maps to a primary Color in Theme.kt. */
enum class AccentColor { Amber, Blue, Green, Violet }

/**
 * Bubble fill preset. Default = follow the active theme (Ink.BubbleMine/
 * Ink.BubbleTheirs). The other choices tint message bubbles independently
 * from the accent color, so people can mix and match.
 */
enum class BubbleColor { Default, Amber, Blue, Green, Violet, Rose }

/**
 * App language preference. "system" means honour the device locale, anything
 * else is a BCP-47 tag we pass to AppCompatDelegate.setApplicationLocales.
 */
data class AppearancePrefs(
    val themeMode: ThemeMode = ThemeMode.Dark,
    val accentColor: AccentColor = AccentColor.Amber,
    val languageTag: String = "system",
    val myBubbleColor: BubbleColor = BubbleColor.Default,
    val othersBubbleColor: BubbleColor = BubbleColor.Default,
    /**
     * Optional ARGB override that wins over `accentColor`. Set via the theme
     * builder; null means use the preset chosen in `accentColor`.
     */
    val customAccentArgb: Int? = null,
    /** Optional ARGB override for the user's own bubbles. Wins over BubbleColor preset. */
    val customMyBubbleArgb: Int? = null,
    /** Optional ARGB override for other people's bubbles. Wins over BubbleColor preset. */
    val customOthersBubbleArgb: Int? = null,
    /** Optional ARGB override for the chat background. Wins over the active theme. */
    val customBgArgb: Int? = null
)

object AppSettings {
    private lateinit var appContext: Context

    private val API_ID = intPreferencesKey("api_id")
    private val API_HASH = stringPreferencesKey("api_hash")
    private val THEME_MODE = stringPreferencesKey("theme_mode")
    private val ACCENT_COLOR = stringPreferencesKey("accent_color")
    private val LANGUAGE_TAG = stringPreferencesKey("language_tag")
    private val MY_BUBBLE = stringPreferencesKey("my_bubble_color")
    private val OTHERS_BUBBLE = stringPreferencesKey("others_bubble_color")
    private val CUSTOM_ACCENT = intPreferencesKey("custom_accent_argb")
    private val CUSTOM_MY_BUBBLE = intPreferencesKey("custom_my_bubble_argb")
    private val CUSTOM_OTHERS_BUBBLE = intPreferencesKey("custom_others_bubble_argb")
    private val CUSTOM_BG = intPreferencesKey("custom_bg_argb")

    fun init(ctx: Context) {
        // idempotent — Activity.attachBaseContext runs before Application.onCreate
        // when the OS restores the process, so this can be called more than once.
        if (!::appContext.isInitialized) {
            appContext = ctx.applicationContext
        }
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
                languageTag = prefs[LANGUAGE_TAG] ?: "system",
                myBubbleColor = parseEnumOrNull<BubbleColor>(prefs[MY_BUBBLE]) ?: BubbleColor.Default,
                othersBubbleColor = parseEnumOrNull<BubbleColor>(prefs[OTHERS_BUBBLE]) ?: BubbleColor.Default,
                customAccentArgb = prefs[CUSTOM_ACCENT],
                customMyBubbleArgb = prefs[CUSTOM_MY_BUBBLE],
                customOthersBubbleArgb = prefs[CUSTOM_OTHERS_BUBBLE],
                customBgArgb = prefs[CUSTOM_BG]
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

    suspend fun setMyBubbleColor(color: BubbleColor) {
        appContext.dataStore.edit { it[MY_BUBBLE] = color.name }
    }

    suspend fun setOthersBubbleColor(color: BubbleColor) {
        appContext.dataStore.edit { it[OTHERS_BUBBLE] = color.name }
    }

    /**
     * Set the custom accent ARGB override, or pass null to clear it and
     * fall back to the AccentColor preset.
     */
    suspend fun setCustomAccentArgb(argb: Int?) {
        appContext.dataStore.edit {
            if (argb == null) it.remove(CUSTOM_ACCENT) else it[CUSTOM_ACCENT] = argb
        }
    }

    suspend fun setCustomMyBubbleArgb(argb: Int?) {
        appContext.dataStore.edit {
            if (argb == null) it.remove(CUSTOM_MY_BUBBLE) else it[CUSTOM_MY_BUBBLE] = argb
        }
    }

    suspend fun setCustomOthersBubbleArgb(argb: Int?) {
        appContext.dataStore.edit {
            if (argb == null) it.remove(CUSTOM_OTHERS_BUBBLE) else it[CUSTOM_OTHERS_BUBBLE] = argb
        }
    }

    suspend fun setCustomBgArgb(argb: Int?) {
        appContext.dataStore.edit {
            if (argb == null) it.remove(CUSTOM_BG) else it[CUSTOM_BG] = argb
        }
    }

    /**
     * Apply an entire AppearancePrefs in one shot. Used by the "Incolla
     * tema" flow, which receives a deserialized prefs blob and writes
     * everything atomically.
     */
    suspend fun applyAppearance(prefs: AppearancePrefs) {
        appContext.dataStore.edit { e ->
            e[THEME_MODE] = prefs.themeMode.name
            e[ACCENT_COLOR] = prefs.accentColor.name
            e[LANGUAGE_TAG] = prefs.languageTag
            e[MY_BUBBLE] = prefs.myBubbleColor.name
            e[OTHERS_BUBBLE] = prefs.othersBubbleColor.name
            if (prefs.customAccentArgb == null) e.remove(CUSTOM_ACCENT) else e[CUSTOM_ACCENT] = prefs.customAccentArgb
            if (prefs.customMyBubbleArgb == null) e.remove(CUSTOM_MY_BUBBLE) else e[CUSTOM_MY_BUBBLE] = prefs.customMyBubbleArgb
            if (prefs.customOthersBubbleArgb == null) e.remove(CUSTOM_OTHERS_BUBBLE) else e[CUSTOM_OTHERS_BUBBLE] = prefs.customOthersBubbleArgb
            if (prefs.customBgArgb == null) e.remove(CUSTOM_BG) else e[CUSTOM_BG] = prefs.customBgArgb
        }
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
