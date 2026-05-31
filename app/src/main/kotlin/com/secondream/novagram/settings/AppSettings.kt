package com.secondream.novagram.settings

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
enum class AccentColor { Amber, Blue, Green, Violet, Rose, Cyan, Orange }

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
    val themeMode: ThemeMode = ThemeMode.System,
    val accentColor: AccentColor = AccentColor.Amber,
    val languageTag: String = "system",
    val myBubbleColor: BubbleColor = BubbleColor.Default,
    val othersBubbleColor: BubbleColor = BubbleColor.Default,
    /**
     * Optional ARGB override that wins over `accentColor`. Set when a saved
     * theme is active or while the user is tweaking the builder; null means
     * use the preset chosen in `accentColor`.
     */
    val customAccentArgb: Int? = null,
    val customMyBubbleArgb: Int? = null,
    val customOthersBubbleArgb: Int? = null,
    val customBgArgb: Int? = null,
    /** Optional ARGB override for the message-input bar background. */
    val customInputBarArgb: Int? = null,
    /**
     * Global text scale multiplier applied to MaterialTheme typography
     * before it reaches any composable. 1.0 = system default, range 0.85
     * (smaller) to 1.35 (larger). Saved per-user, never sent to TDLib.
     */
    val textScale: Float = 1.0f,
    /**
     * Id of the saved theme currently applied (so the row gets a checkmark in
     * Settings). null = no saved theme active, the user is on a base theme
     * mode (System/Light/Dark/Amoled) or freely tweaked customs.
     */
    val activeSavedThemeId: String? = null,
    /**
     * When true (default), media in chats (photos, video previews, voice
     * notes, documents) auto-downloads as the user scrolls into view.
     * When false, each piece of media renders as a tap-to-download
     * placeholder showing the file size — useful on metered connections
     * or when the user just wants to scroll fast without TDLib starting
     * dozens of background transfers. Voice notes are still tappable to
     * download on-demand, same flow.
     */
    val autoDownloadMedia: Boolean = true,
    /**
     * Optional Anthropic API key the user provides for the in-chat AI
     * features (summarise/translate/draft-reply on selected messages).
     * Null/blank disables the AI tile in the message actions sheet so
     * the feature is opt-in — users without a key see no AI surface at
     * all. Stored locally only, never leaves the device except as the
     * Authorization header to api.anthropic.com.
     */
    val anthropicApiKey: String? = null,
    /** When true, a 4th "Archiviati" tab appears in the chat list. */
    val showArchivedTab: Boolean = false,
    /**
     * When true, the first tab in the chat list is "Tutto" — a unified
     * view that mixes private chats, groups, channels, and secret chats
     * (excluding archived). Mirrors the official Telegram default. When
     * false the list opens straight on the Chat tab, keeping the
     * category-segmented experience Novagram pioneered.
     */
    val showAllTab: Boolean = true,
    /**
     * When true, swiping LEFT on a message bubble opens the reply
     * composer (mirrors the right-handed convention some users prefer).
     * Default true because we found it more comfortable: it also frees
     * the right-swipe gesture for the chat-pop-back action (see the
     * back-swipe handler in ChatScreen — when this is true the gesture
     * works from anywhere in the chat view, not just the 24dp left edge).
     * Stock-Telegram-Android-style "swipe right to reply" is one toggle
     * away in Settings.
     */
    val swapSwipeReply: Boolean = true,
    /**
     * Whether the user wants their own "last seen" / "online" status to
     * be visible to other Telegram users — drives both the visual UI
     * (green dot on private chat avatars, "online" subtitle in chat
     * header) AND the server-side `UserPrivacySettingShowStatus` rule.
     * Default true so the experience matches stock Telegram out of the
     * box; flip in Settings → Privacy.
     */
    val showLastSeen: Boolean = true,
    /**
     * Whether we announce "user is typing" to peers when the user is
     * composing a message in chat. Drives the
     * SendChatAction(ChatActionTyping) emission from the input field's
     * onValueChange. Default true to match stock Telegram out of the
     * box. When false the user types invisibly — peers see no typing
     * bubble, no avatar pulse, nothing. The flag is purely client-side:
     * Telegram has no server-side privacy rule for typing status, so
     * the only knob is "do we emit the action or not".
     */
    val sendTypingStatus: Boolean = true
)

/**
 * A named bundle of color overrides the user has saved. Shows up in
 * Settings under "Temi personalizzati" as a row separate from the
 * System/Light/Dark/Amoled base modes. Applying one sets every custom*Argb
 * field on AppearancePrefs at once and also stores `activeSavedThemeId`.
 *
 * Persisted as a JSON array in a single DataStore string key — simpler than
 * a separate keyed schema and roundtrips cleanly with the share/import flow.
 */
data class SavedTheme(
    val id: String,
    val name: String,
    val accentArgb: Int,
    val myBubbleArgb: Int,
    val othersBubbleArgb: Int,
    val bgArgb: Int,
    val inputBarArgb: Int,
    /** Whether this theme is built on a LIGHT base. Applying it sets the
     *  app's themeMode to Light/Dark so surfaces (cards, menus) match —
     *  otherwise a light-bg theme over a dark base mode produced dark
     *  cards under a white background. */
    val isLight: Boolean = false
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
    private val CUSTOM_INPUT_BAR = intPreferencesKey("custom_input_bar_argb")
    private val ACTIVE_SAVED_THEME_ID = stringPreferencesKey("active_saved_theme_id")
    private val SAVED_THEMES_JSON = stringPreferencesKey("saved_themes_json")
    private val TEXT_SCALE = androidx.datastore.preferences.core.floatPreferencesKey("text_scale")
    private val AUTO_DOWNLOAD_MEDIA = androidx.datastore.preferences.core.booleanPreferencesKey("auto_download_media")
    private val ANTHROPIC_API_KEY = androidx.datastore.preferences.core.stringPreferencesKey("anthropic_api_key")
    private val SHOW_ARCHIVED_TAB = androidx.datastore.preferences.core.booleanPreferencesKey("show_archived_tab")
    private val SHOW_LAST_SEEN = androidx.datastore.preferences.core.booleanPreferencesKey("show_last_seen")
    private val SHOW_ALL_TAB = androidx.datastore.preferences.core.booleanPreferencesKey("show_all_tab")
    private val SWAP_SWIPE_REPLY = androidx.datastore.preferences.core.booleanPreferencesKey("swap_swipe_reply")
    private val SEND_TYPING_STATUS = androidx.datastore.preferences.core.booleanPreferencesKey("send_typing_status")

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
                themeMode = parseEnumOrNull<ThemeMode>(prefs[THEME_MODE]) ?: ThemeMode.System,
                accentColor = parseEnumOrNull<AccentColor>(prefs[ACCENT_COLOR]) ?: AccentColor.Amber,
                languageTag = prefs[LANGUAGE_TAG] ?: "system",
                myBubbleColor = parseEnumOrNull<BubbleColor>(prefs[MY_BUBBLE]) ?: BubbleColor.Default,
                othersBubbleColor = parseEnumOrNull<BubbleColor>(prefs[OTHERS_BUBBLE]) ?: BubbleColor.Default,
                customAccentArgb = prefs[CUSTOM_ACCENT],
                customMyBubbleArgb = prefs[CUSTOM_MY_BUBBLE],
                customOthersBubbleArgb = prefs[CUSTOM_OTHERS_BUBBLE],
                customBgArgb = prefs[CUSTOM_BG],
                customInputBarArgb = prefs[CUSTOM_INPUT_BAR],
                activeSavedThemeId = prefs[ACTIVE_SAVED_THEME_ID],
                textScale = (prefs[TEXT_SCALE] ?: 1.0f).coerceIn(0.85f, 1.35f),
                autoDownloadMedia = prefs[AUTO_DOWNLOAD_MEDIA] ?: true,
                anthropicApiKey = prefs[ANTHROPIC_API_KEY]?.takeIf { it.isNotBlank() },
                showArchivedTab = prefs[SHOW_ARCHIVED_TAB] ?: false,
                showAllTab = prefs[SHOW_ALL_TAB] ?: true,
                swapSwipeReply = prefs[SWAP_SWIPE_REPLY] ?: true,
                showLastSeen = prefs[SHOW_LAST_SEEN] ?: true,
                sendTypingStatus = prefs[SEND_TYPING_STATUS] ?: true
            )
        }

    suspend fun setShowArchivedTab(enabled: Boolean) {
        appContext.dataStore.edit { it[SHOW_ARCHIVED_TAB] = enabled }
    }

    suspend fun setShowAllTab(enabled: Boolean) {
        appContext.dataStore.edit { it[SHOW_ALL_TAB] = enabled }
    }

    suspend fun setSwapSwipeReply(enabled: Boolean) {
        appContext.dataStore.edit { it[SWAP_SWIPE_REPLY] = enabled }
    }

    suspend fun setSendTypingStatus(enabled: Boolean) {
        appContext.dataStore.edit { it[SEND_TYPING_STATUS] = enabled }
    }

    suspend fun setShowLastSeen(enabled: Boolean) {
        appContext.dataStore.edit { it[SHOW_LAST_SEEN] = enabled }
        // Forward the same boolean to TDLib as a privacy rule so the
        // server actually hides the status from peers — without this the
        // toggle would only affect our own client-side display.
        runCatching {
            com.secondream.novagram.td.TdClient.setShowStatusVisibility(enabled)
        }
    }

    suspend fun setAutoDownloadMedia(enabled: Boolean) {
        appContext.dataStore.edit { it[AUTO_DOWNLOAD_MEDIA] = enabled }
    }

    /**
     * Store (or clear) the user's Anthropic API key. Passing a blank
     * string deletes the entry so the AI tile disappears from the
     * actions sheet — easier than a separate "disable" toggle.
     */
    suspend fun setAnthropicApiKey(key: String) {
        appContext.dataStore.edit { e ->
            if (key.isBlank()) e.remove(ANTHROPIC_API_KEY)
            else e[ANTHROPIC_API_KEY] = key.trim()
        }
    }

    /**
     * The list of user-defined saved themes. Persisted as a JSON array under
     * a single key so the schema can evolve without DataStore migrations.
     */
    val savedThemes: Flow<List<SavedTheme>>
        get() = appContext.dataStore.data.map { prefs ->
            parseSavedThemes(prefs[SAVED_THEMES_JSON])
        }

    private fun parseSavedThemes(raw: String?): List<SavedTheme> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(SavedTheme(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        accentArgb = o.getInt("accent"),
                        myBubbleArgb = o.getInt("myBubble"),
                        othersBubbleArgb = o.getInt("othersBubble"),
                        bgArgb = o.getInt("bg"),
                        inputBarArgb = o.optInt("inputBar", o.getInt("bg")),
                        isLight = o.optBoolean("isLight", false)
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeSavedThemes(list: List<SavedTheme>): String {
        val arr = org.json.JSONArray()
        for (t in list) {
            val o = org.json.JSONObject()
            o.put("id", t.id)
            o.put("name", t.name)
            o.put("accent", t.accentArgb)
            o.put("myBubble", t.myBubbleArgb)
            o.put("othersBubble", t.othersBubbleArgb)
            o.put("bg", t.bgArgb)
            o.put("inputBar", t.inputBarArgb)
            o.put("isLight", t.isLight)
            arr.put(o)
        }
        return arr.toString()
    }

    /**
     * Insert or update a saved theme. If the id already exists in the list
     * it's replaced in-place (preserves ordering); otherwise it's appended.
     * Also makes that theme the active one and writes its colors into the
     * custom* slots so it takes effect immediately.
     */
    suspend fun upsertSavedTheme(theme: SavedTheme) {
        appContext.dataStore.edit { e ->
            val current = parseSavedThemes(e[SAVED_THEMES_JSON])
            val idx = current.indexOfFirst { it.id == theme.id }
            val updated = if (idx >= 0) current.toMutableList().also { it[idx] = theme }
                          else current + theme
            e[SAVED_THEMES_JSON] = encodeSavedThemes(updated)
            e[ACTIVE_SAVED_THEME_ID] = theme.id
            e[CUSTOM_ACCENT] = theme.accentArgb
            // Bubble overrides only apply if the saved theme actually has
            // them set (legacy themes from when the builder exposed those
            // sections). New themes pass 0 here → we leave the override
            // cleared so bubbles derive from theme + accent.
            if (theme.myBubbleArgb != 0) e[CUSTOM_MY_BUBBLE] = theme.myBubbleArgb
            else e.remove(CUSTOM_MY_BUBBLE)
            if (theme.othersBubbleArgb != 0) e[CUSTOM_OTHERS_BUBBLE] = theme.othersBubbleArgb
            else e.remove(CUSTOM_OTHERS_BUBBLE)
            e[CUSTOM_BG] = theme.bgArgb
            e[CUSTOM_INPUT_BAR] = theme.inputBarArgb
            // Align base mode with the theme so surfaces match (light theme
            // → light cards). Fixes the "white bg + dark cards" mix.
            e[THEME_MODE] = if (theme.isLight) ThemeMode.Light.name else ThemeMode.Dark.name
        }
    }

    /**
     * Append a saved theme to the list WITHOUT activating it. Used by the
     * "Import from clipboard" flow so importing a theme doesn't yank the
     * user off their current one — the imported theme appears in the
     * saved-themes row and the user can tap to activate when ready. If an
     * id collision happens (re-importing the same theme) we treat it as
     * an in-place update so duplicates don't pile up.
     */
    suspend fun addSavedTheme(theme: SavedTheme) {
        appContext.dataStore.edit { e ->
            val current = parseSavedThemes(e[SAVED_THEMES_JSON])
            val idx = current.indexOfFirst { it.id == theme.id }
            val updated = if (idx >= 0) current.toMutableList().also { it[idx] = theme }
                          else current + theme
            e[SAVED_THEMES_JSON] = encodeSavedThemes(updated)
        }
    }

    /**
     * Wrap an [AppearancePrefs] (produced by parseThemeJson when the user
     * pastes a theme JSON, taps a theme-share card in chat, or follows a
     * `nova://theme?data=...` deep link) into a [SavedTheme] entry and
     * append it to the saved-themes list. Does NOT activate it: the
     * imported theme appears as a tappable row in Settings → Temi salvati
     * and the user picks when to switch.
     *
     * Single canonical entry point so the three import paths (Settings
     * paste button, in-chat theme card, deep link) all share the same
     * semantics. Returns the generated id so the caller can show a
     * confirmation toast like "Tema X aggiunto ai tuoi temi".
     */
    suspend fun importAppearanceAsSavedTheme(
        appearance: AppearancePrefs,
        baseName: String
    ): String {
        // Use the imported bg luminance to set isLight so when the user
        // later activates the theme, [activateSavedTheme] picks the
        // right base mode (light vs dark) — otherwise a pastel-light bg
        // ends up under a dark base scheme and the cards look wrong.
        val bgArgb = appearance.customBgArgb ?: 0xFF0F1115.toInt()
        val bgIsLight = androidx.compose.ui.graphics.Color(bgArgb).luminance() > 0.5f
        // A tiny timestamp suffix so back-to-back imports show up as
        // distinct rows instead of "Tema importato" repeated. Locale
        // aware so the format matches what the user expects to read.
        val stamp = java.text.SimpleDateFormat(
            "HH:mm",
            java.util.Locale.getDefault()
        ).format(java.util.Date())
        val theme = SavedTheme(
            id = java.util.UUID.randomUUID().toString(),
            name = "$baseName · $stamp",
            accentArgb = appearance.customAccentArgb ?: 0xFFD9A85C.toInt(),
            myBubbleArgb = appearance.customMyBubbleArgb ?: 0xFF2A4F7A.toInt(),
            othersBubbleArgb = appearance.customOthersBubbleArgb ?: 0xFF374151.toInt(),
            bgArgb = bgArgb,
            inputBarArgb = appearance.customInputBarArgb ?: 0xFF1A1D24.toInt(),
            isLight = bgIsLight
        )
        addSavedTheme(theme)
        return theme.id
    }

    /** Drop the saved theme by id, also clearing custom* if it was active. */
    suspend fun deleteSavedTheme(id: String) {
        appContext.dataStore.edit { e ->
            val current = parseSavedThemes(e[SAVED_THEMES_JSON])
            val updated = current.filterNot { it.id == id }
            e[SAVED_THEMES_JSON] = encodeSavedThemes(updated)
            if (e[ACTIVE_SAVED_THEME_ID] == id) {
                e.remove(ACTIVE_SAVED_THEME_ID)
                e.remove(CUSTOM_ACCENT)
                e.remove(CUSTOM_MY_BUBBLE)
                e.remove(CUSTOM_OTHERS_BUBBLE)
                e.remove(CUSTOM_BG)
                e.remove(CUSTOM_INPUT_BAR)
            }
        }
    }

    /** Mark a saved theme as the active one and load its colors. */
    suspend fun activateSavedTheme(id: String) {
        appContext.dataStore.edit { e ->
            val theme = parseSavedThemes(e[SAVED_THEMES_JSON]).firstOrNull { it.id == id }
                ?: return@edit
            e[ACTIVE_SAVED_THEME_ID] = theme.id
            e[CUSTOM_ACCENT] = theme.accentArgb
            if (theme.myBubbleArgb != 0) e[CUSTOM_MY_BUBBLE] = theme.myBubbleArgb
            else e.remove(CUSTOM_MY_BUBBLE)
            if (theme.othersBubbleArgb != 0) e[CUSTOM_OTHERS_BUBBLE] = theme.othersBubbleArgb
            else e.remove(CUSTOM_OTHERS_BUBBLE)
            e[CUSTOM_BG] = theme.bgArgb
            e[CUSTOM_INPUT_BAR] = theme.inputBarArgb
            e[THEME_MODE] = if (theme.isLight) ThemeMode.Light.name else ThemeMode.Dark.name
        }
    }

    /**
     * Reset to the active base theme (Sistema / Chiaro / Scuro / AMOLED):
     * drop every custom* override and the active saved theme marker. The
     * base mode + preset accent + preset bubble stays as it was.
     */
    suspend fun resetCustomOverrides() {
        appContext.dataStore.edit { e ->
            e.remove(CUSTOM_ACCENT)
            e.remove(CUSTOM_MY_BUBBLE)
            e.remove(CUSTOM_OTHERS_BUBBLE)
            e.remove(CUSTOM_BG)
            e.remove(CUSTOM_INPUT_BAR)
            e.remove(ACTIVE_SAVED_THEME_ID)
        }
    }

    suspend fun setCustomInputBarArgb(argb: Int?) {
        appContext.dataStore.edit {
            if (argb == null) it.remove(CUSTOM_INPUT_BAR) else it[CUSTOM_INPUT_BAR] = argb
        }
    }

    /**
     * Global text scale multiplier. Clamped to [0.85, 1.35] before writing
     * so a malformed input can't make the UI unreadable. Applied at the
     * theme level so every Text/Material composable honors it.
     */
    suspend fun setTextScale(scale: Float) {
        val clamped = scale.coerceIn(0.85f, 1.35f)
        appContext.dataStore.edit { it[TEXT_SCALE] = clamped }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        // Selecting one of the 4 base modes is treated as "reset to the
        // matrix": we drop every custom override and detach the active
        // saved theme so the chosen mode × accent preset gives a clean
        // combo. Without this clearing step, a previously-applied saved
        // theme keeps its customAccentArgb in place and the base mode
        // appears to do nothing — exactly the bug you hit.
        appContext.dataStore.edit { e ->
            e[THEME_MODE] = mode.name
            e.remove(CUSTOM_ACCENT)
            e.remove(CUSTOM_MY_BUBBLE)
            e.remove(CUSTOM_OTHERS_BUBBLE)
            e.remove(CUSTOM_BG)
            e.remove(CUSTOM_INPUT_BAR)
            e.remove(ACTIVE_SAVED_THEME_ID)
        }
        // NOTE: We deliberately do NOT call IconAliasManager.apply here
        // anymore. PackageManager.setComponentEnabledSetting can kill
        // the process when the currently-active LAUNCHER alias is the
        // one being disabled — DONT_KILL_APP is documented as a hint,
        // not a guarantee, and certain OEMs ignore it entirely. The
        // idempotency check from v0.10.43 cut down the cases but did
        // not eliminate them. The launcher icon variant now updates
        // only at App.onCreate (process start), so a theme change
        // during the session updates the in-app colors instantly via
        // Compose recompose, but the launcher icon catches up on the
        // next app start. This is an acceptable trade-off: the
        // launcher icon is what the user sees ON THE HOME SCREEN, only
        // visible when the app is closed.
    }

    suspend fun setAccentColor(color: AccentColor) {
        // Same logic as setThemeMode: tapping a preset accent swatch must
        // release the customAccentArgb override, otherwise the preset is
        // saved but invisible (the override wins on every read).
        appContext.dataStore.edit { e ->
            e[ACCENT_COLOR] = color.name
            e.remove(CUSTOM_ACCENT)
            e.remove(CUSTOM_MY_BUBBLE)
            e.remove(CUSTOM_OTHERS_BUBBLE)
            e.remove(CUSTOM_BG)
            e.remove(CUSTOM_INPUT_BAR)
            e.remove(ACTIVE_SAVED_THEME_ID)
        }
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
            if (prefs.customInputBarArgb == null) e.remove(CUSTOM_INPUT_BAR) else e[CUSTOM_INPUT_BAR] = prefs.customInputBarArgb
            if (prefs.activeSavedThemeId == null) e.remove(ACTIVE_SAVED_THEME_ID) else e[ACTIVE_SAVED_THEME_ID] = prefs.activeSavedThemeId
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
