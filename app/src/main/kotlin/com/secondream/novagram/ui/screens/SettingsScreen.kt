@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.novagram.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.secondream.novagram.ui.components.pressScale
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import com.secondream.novagram.BuildConfig
import com.secondream.novagram.R
import com.secondream.novagram.settings.AccentColor
import com.secondream.novagram.settings.BubbleColor
import com.secondream.novagram.settings.AppSettings
import com.secondream.novagram.settings.ThemeMode
import com.secondream.novagram.td.TdClient
import com.secondream.novagram.ui.theme.AccentPalette
import com.secondream.novagram.ui.theme.BubblePalette

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenChat: (Long) -> Unit = {}) {
    val phos = com.secondream.novagram.ui.icons.PhosphorIcons
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appearance by AppSettings.appearance.collectAsState(
        initial = com.secondream.novagram.settings.AppearancePrefs()
    )
    val savedThemes by AppSettings.savedThemes.collectAsState(initial = emptyList())
    var showReadDate by remember { mutableStateOf(true) }
    var showThemeBuilder by remember { mutableStateOf(false) }
    var blockedOpen by remember { mutableStateOf(false) }
    var proxyOpen by remember { mutableStateOf(false) }
    var builderTheme by remember { mutableStateOf<com.secondream.novagram.settings.SavedTheme?>(null) }
    // Theme the user is sharing into a chat (drives the in-app chat picker).
    var shareThemeTarget by remember { mutableStateOf<com.secondream.novagram.settings.SavedTheme?>(null) }
    // Accordion: at most one section open at a time. null = all collapsed,
    // which is the default on entry.
    var expandedSection by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { showReadDate = TdClient.getReadDatePrivacy() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontStyle = FontStyle.Italic
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            com.secondream.novagram.ui.icons.PhosphorIcons.CaretLeft,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // APPEARANCE — unified theme picker. Eugenio's redesign:
            // the 4 base modes (Sistema/Chiaro/Scuro/AMOLED) and every
            // saved custom theme live in the SAME list. Exactly one row is
            // highlighted at any time. The "Crea nuovo / Incolla" actions
            // sit at the bottom of the list. The accent picker only shows
            // up when no custom theme is active — a custom theme already
            // owns its own accent so showing the global one would be
            // ambiguous.
            CollapsibleSection(stringResource(R.string.settings_section_appearance), subtitle = stringResource(R.string.settings_section_appearance_sub), icon = phos.Image, expanded = expandedSection == "appearance", onToggle = { expandedSection = if (expandedSection == "appearance") null else "appearance" }) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.settings_theme),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        // "Nuovo tema": accent badge + plus, right next to the title.
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable {
                                    builderTheme = null
                                    showThemeBuilder = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                phos.Plus,
                                contentDescription = stringResource(R.string.theme_create_new),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val activeCustomId = appearance.activeSavedThemeId
                    // Base modes — selected only when no custom is active.
                    BaseModeListRow(
                        label = stringResource(R.string.settings_theme_system),
                        selected = activeCustomId == null && appearance.themeMode == ThemeMode.System,
                        onClick = { scope.launch { AppSettings.setThemeMode(ThemeMode.System) } }
                    )
                    BaseModeListRow(
                        label = stringResource(R.string.settings_theme_light),
                        selected = activeCustomId == null && appearance.themeMode == ThemeMode.Light,
                        onClick = { scope.launch { AppSettings.setThemeMode(ThemeMode.Light) } }
                    )
                    BaseModeListRow(
                        label = stringResource(R.string.settings_theme_dark),
                        selected = activeCustomId == null && appearance.themeMode == ThemeMode.Dark,
                        onClick = { scope.launch { AppSettings.setThemeMode(ThemeMode.Dark) } }
                    )
                    BaseModeListRow(
                        label = stringResource(R.string.settings_theme_amoled),
                        selected = activeCustomId == null && appearance.themeMode == ThemeMode.Amoled,
                        onClick = { scope.launch { AppSettings.setThemeMode(ThemeMode.Amoled) } }
                    )
                    // Saved custom themes (if any). Each row uses the
                    // existing SavedThemeRow composable so the swatch
                    // preview + overflow menu stay consistent.
                    if (savedThemes.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (theme in savedThemes) {
                                SavedThemeRow(
                                    theme = theme,
                                    isActive = theme.id == activeCustomId,
                                    onApply = {
                                        scope.launch { AppSettings.activateSavedTheme(theme.id) }
                                    },
                                    onEdit = {
                                        builderTheme = theme
                                        showThemeBuilder = true
                                    },
                                    onShare = {
                                        // Open the in-app chat picker instead of
                                        // the OS share sheet: the user picks a
                                        // chat, we send the theme deeplink there
                                        // and drop straight into that chat. The
                                        // deeplink is built when the chat is
                                        // chosen (see ShareChatPickerSheet below).
                                        shareThemeTarget = theme
                                    },
                                    onDelete = {
                                        scope.launch { AppSettings.deleteSavedTheme(theme.id) }
                                    }
                                )
                            }
                        }
                    }
                }
                // Accent picker only when a base mode is active. A custom
                // theme already carries its own accentArgb so exposing the
                // global preset selector while one is selected would be
                // confusing (the row would visibly do nothing).
                if (appearance.activeSavedThemeId == null) {
                    Divider()
                    AccentRow(
                        current = appearance.accentColor,
                        onPick = { color -> scope.launch { AppSettings.setAccentColor(color) } }
                    )
                }
                Divider()
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_reduce_animations),
                    description = stringResource(R.string.settings_reduce_animations_desc),
                    checked = appearance.reduceAnimations,
                    onToggle = { enabled ->
                        scope.launch { AppSettings.setReduceAnimations(enabled) }
                    }
                )
            }

            Spacer(Modifier.height(10.dp))

            // TEXT SIZE — global multiplier for every Text in the app.
            CollapsibleSection(stringResource(R.string.settings_section_display), subtitle = stringResource(R.string.settings_section_display_sub), icon = phos.FileText, expanded = expandedSection == "textsize", onToggle = { expandedSection = if (expandedSection == "textsize") null else "textsize" }) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Slider 1 — interface + chat list (incl. message previews).
                    // Drives the global typography scale (textScale).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.settings_display_ui),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${(appearance.textScale * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.Slider(
                        value = appearance.textScale,
                        onValueChange = { v ->
                            scope.launch { AppSettings.setTextScale(v) }
                        },
                        valueRange = 0.70f..1.60f,
                        steps = 17
                    )
                    Spacer(Modifier.height(14.dp))
                    // Slider 2 — in-chat message bubble text only (messageScale),
                    // independent of slider 1 so the two never compound.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.settings_display_messages),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${(appearance.messageScale * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.Slider(
                        value = appearance.messageScale,
                        onValueChange = { v ->
                            scope.launch { AppSettings.setMessageScale(v) }
                        },
                        valueRange = 0.70f..1.60f,
                        steps = 17
                    )
                    Spacer(Modifier.height(14.dp))
                    // Slider 3 — vertical spacing BETWEEN message bubbles
                    // (chat density). Lower = tighter (more messages per screen,
                    // Telegram-style); the bubble floors it at 1dp so rows never
                    // touch. 100% = default.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.settings_display_spacing),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${(appearance.messageSpacing * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.Slider(
                        value = appearance.messageSpacing,
                        onValueChange = { v ->
                            scope.launch { AppSettings.setMessageSpacing(v) }
                        },
                        valueRange = 0.0f..2.0f,
                        steps = 19
                    )
                    Spacer(Modifier.height(14.dp))
                    // Slider 4 — line height INSIDE a bubble (height of each
                    // "a capo"): more or less space between the text lines of a
                    // multi-line message. Independent of the bubble-spacing
                    // slider above. 100% = default.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.settings_display_line_spacing),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${(appearance.messageLineSpacing * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.Slider(
                        value = appearance.messageLineSpacing,
                        onValueChange = { v ->
                            scope.launch { AppSettings.setMessageLineSpacing(v) }
                        },
                        valueRange = 0.8f..1.8f,
                        steps = 19
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // LANGUAGE
            CollapsibleSection(stringResource(R.string.settings_section_language), subtitle = stringResource(R.string.settings_section_language_sub), icon = phos.Translate, expanded = expandedSection == "language", onToggle = { expandedSection = if (expandedSection == "language") null else "language" }) {
                LanguageRow(
                    current = appearance.languageTag,
                    onPick = { tag ->
                        scope.launch {
                            AppSettings.setLanguageTag(tag)
                            // The new per-app locale gets picked up by
                            // MainActivity.attachBaseContext on recreate.
                            // AppCompatDelegate.setApplicationLocales is also
                            // kept for API 33+ where the system honours it
                            // even with non-AppCompat activities.
                            val locales = if (tag == "system") {
                                LocaleListCompat.getEmptyLocaleList()
                            } else {
                                LocaleListCompat.forLanguageTags(tag)
                            }
                            AppCompatDelegate.setApplicationLocales(locales)
                            (context as? android.app.Activity)?.recreate()
                        }
                    }
                )
                // Coming soon: a free-translation toggle that auto-translates
                // chats whose messages aren't in the app language. Disabled
                // placeholder for now so the slot is visible.
                Divider()
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_translator_label),
                    description = stringResource(R.string.settings_translator_soon),
                    checked = false,
                    onToggle = {},
                    enabled = false
                )
            }

            Spacer(Modifier.height(10.dp))

            // (Bubble color picker removed — bubbles now derive
            // automatically from the active theme + accent preset to keep
            // the 4×4 combo matrix readable in every mode.)

            // (Custom themes used to live in their own section here. They
            // moved into the unified APPEARANCE list above so the user
            // sees one continuous list of selectable themes, base + saved
            // together, with the Crea/Incolla actions sitting right under
            // them. The single state of truth is `activeSavedThemeId`:
            // null means a base mode row is selected, non-null means a
            // saved-theme row is.)

            // MEDIA — controls how aggressively TDLib pulls media files
            // while the user scrolls a chat. Off = nothing downloads
            // until the user taps the placeholder; useful on metered
            // networks and for "just skimming" workflows.
            CollapsibleSection(stringResource(R.string.settings_section_media), subtitle = stringResource(R.string.settings_section_media_sub), icon = phos.Gear, expanded = expandedSection == "misc", onToggle = { expandedSection = if (expandedSection == "misc") null else "misc" }) {
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_autocaps),
                    description = stringResource(R.string.settings_autocaps_desc),
                    checked = appearance.autoCapitalize,
                    onToggle = { enabled ->
                        scope.launch { AppSettings.setAutoCapitalize(enabled) }
                    }
                )
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_haptics),
                    description = stringResource(R.string.settings_haptics_desc),
                    checked = appearance.hapticsEnabled,
                    onToggle = { enabled ->
                        scope.launch { AppSettings.setHapticsEnabled(enabled) }
                    }
                )
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_download_badge),
                    description = stringResource(R.string.settings_download_badge_desc),
                    checked = appearance.transferBadgeEnabled,
                    onToggle = { enabled ->
                        scope.launch { AppSettings.setTransferBadge(enabled) }
                    }
                )
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_media_autodownload),
                    description = stringResource(R.string.settings_media_autodownload_desc),
                    checked = appearance.autoDownloadMedia,
                    onToggle = { enabled ->
                        scope.launch {
                            AppSettings.setAutoDownloadMedia(enabled)
                            // Mirror the choice into TDLib's own presets.
                            runCatching { com.secondream.novagram.td.TdClient.applyAutoDownloadSetting(enabled) }
                        }
                    }
                )
                ClearAppDataRow()
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_show_all_tab),
                    description = stringResource(R.string.settings_show_all_tab_desc),
                    checked = appearance.showAllTab,
                    onToggle = { enabled ->
                        scope.launch { AppSettings.setShowAllTab(enabled) }
                    }
                )
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_archived_tab),
                    description = stringResource(R.string.settings_archived_tab_desc),
                    checked = appearance.showArchivedTab,
                    onToggle = { enabled ->
                        scope.launch { AppSettings.setShowArchivedTab(enabled) }
                    }
                )
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_swap_swipe_reply),
                    description = stringResource(R.string.settings_swap_swipe_reply_desc),
                    checked = appearance.swapSwipeReply,
                    onToggle = { enabled ->
                        scope.launch { AppSettings.setSwapSwipeReply(enabled) }
                    }
                )
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_lock_saved_top),
                    description = stringResource(R.string.settings_lock_saved_top_desc),
                    checked = appearance.lockSavedToTop,
                    onToggle = { enabled ->
                        scope.launch { AppSettings.setLockSavedToTop(enabled) }
                    }
                )
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_bot_commands),
                    description = stringResource(R.string.settings_bot_commands_desc),
                    checked = appearance.showBotCommandsButton,
                    onToggle = { enabled ->
                        scope.launch { AppSettings.setShowBotCommandsButton(enabled) }
                    }
                )
            }

            Spacer(Modifier.height(10.dp))

            // AI — the user pastes their Anthropic API key here. Without
            // one the AI tile in the message actions sheet stays hidden.
            // Key is stored locally and only ever sent to api.anthropic.com.
            CollapsibleSection(stringResource(R.string.settings_section_ai), subtitle = stringResource(R.string.settings_section_ai_sub), icon = phos.Sparkle, expanded = expandedSection == "ai", onToggle = { expandedSection = if (expandedSection == "ai") null else "ai" }) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header: sparkle icon in an accent circle + title +
                    // a status chip showing whether a key is configured.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                com.secondream.novagram.ui.icons.PhosphorIcons.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_ai_apikey_label),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val configured = appearance.anthropicApiKey != null
                            Text(
                                stringResource(
                                    if (configured) R.string.settings_ai_status_on
                                    else R.string.settings_ai_status_off
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (configured) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.settings_ai_apikey_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    // Create-key link.
                    Text(
                        stringResource(R.string.settings_ai_get_key),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://console.anthropic.com/settings/keys")
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    var apiKeyDraft by remember(appearance.anthropicApiKey) {
                        mutableStateOf(appearance.anthropicApiKey.orEmpty())
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        if (apiKeyDraft.isEmpty()) {
                            Text(
                                "sk-ant-...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = apiKeyDraft,
                            onValueChange = { apiKeyDraft = it },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                MaterialTheme.colorScheme.primary
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Button(
                            onClick = {
                                scope.launch { AppSettings.setAnthropicApiKey(apiKeyDraft) }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.settings_ai_apikey_save))
                        }
                        if (appearance.anthropicApiKey != null) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = {
                                    scope.launch { AppSettings.setAnthropicApiKey("") }
                                    apiKeyDraft = ""
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.settings_ai_apikey_clear))
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Modello AI",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    var aiTier by remember { mutableStateOf(com.secondream.novagram.ai.AiPrefs.getTier(context)) }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        com.secondream.novagram.ai.AiTier.values().forEach { t ->
                            val sel = t == aiTier
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (sel) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable {
                                        aiTier = t
                                        com.secondream.novagram.ai.AiPrefs.setTier(context, t)
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Text(
                                    t.short,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (sel) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Basic è veloce ed economico, Business è il più capace.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Feature gates — both call Anthropic, so they're forced off
                // and non-toggleable until a key is configured.
                val aiKeySet = appearance.anthropicApiKey != null
                Divider()
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_ai_recap_label),
                    description = stringResource(R.string.settings_ai_recap_desc),
                    checked = aiKeySet && appearance.aiRecapEnabled,
                    onToggle = { v -> scope.launch { AppSettings.setAiRecapEnabled(v) } },
                    enabled = aiKeySet
                )
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_ai_messages_label),
                    description = stringResource(R.string.settings_ai_messages_desc),
                    checked = aiKeySet && appearance.aiMessageActionsEnabled,
                    onToggle = { v -> scope.launch { AppSettings.setAiMessageActionsEnabled(v) } },
                    enabled = aiKeySet
                )
            }

            Spacer(Modifier.height(10.dp))

            // PRIVACY
            CollapsibleSection(stringResource(R.string.settings_section_privacy), subtitle = stringResource(R.string.settings_section_privacy_sub), icon = phos.Lock, expanded = expandedSection == "privacy", onToggle = { expandedSection = if (expandedSection == "privacy") null else "privacy" }) {
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_privacy_read_receipts),
                    description = stringResource(R.string.settings_privacy_read_receipts_desc),
                    checked = showReadDate,
                    onToggle = { newValue ->
                        showReadDate = newValue
                        scope.launch {
                            runCatching { TdClient.setReadDatePrivacy(newValue) }
                        }
                    }
                )
                Divider()
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_privacy_last_seen),
                    description = stringResource(R.string.settings_privacy_last_seen_desc),
                    checked = appearance.showLastSeen,
                    onToggle = { enabled ->
                        scope.launch { AppSettings.setShowLastSeen(enabled) }
                    }
                )
                Divider()
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_privacy_typing),
                    description = stringResource(R.string.settings_privacy_typing_desc),
                    checked = appearance.sendTypingStatus,
                    onToggle = { enabled ->
                        scope.launch { AppSettings.setSendTypingStatus(enabled) }
                    }
                )
                Divider()
                PrivacyToggleRow(
                    label = stringResource(R.string.settings_sounds),
                    description = stringResource(R.string.settings_sounds_desc),
                    checked = appearance.messageSounds,
                    onToggle = { enabled ->
                        scope.launch { AppSettings.setMessageSounds(enabled) }
                    }
                )
                Divider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { blockedOpen = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_blocked_users),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            stringResource(R.string.settings_blocked_users_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Divider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { proxyOpen = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_proxy),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            stringResource(R.string.settings_proxy_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // INFO
            CollapsibleSection(stringResource(R.string.settings_section_info), subtitle = stringResource(R.string.settings_section_info_sub), icon = phos.Info, expanded = expandedSection == "info", onToggle = { expandedSection = if (expandedSection == "info") null else "info" }) {
                InfoRow(
                    label = stringResource(R.string.settings_version),
                    value = BuildConfig.VERSION_NAME
                )
                Divider()
                InfoRow(
                    label = stringResource(R.string.settings_build),
                    value = BuildConfig.VERSION_CODE.toString()
                )
                Divider()
                InfoLinksGrid()
            }

            Spacer(Modifier.height(10.dp))

            // CREDITS
            CreditsBlock()

            Spacer(Modifier.height(40.dp))
        }
    }

    if (showThemeBuilder) {
        ThemeBuilderDialog(
            initialTheme = builderTheme,
            onDismiss = { showThemeBuilder = false; builderTheme = null },
            onSave = { theme ->
                scope.launch { AppSettings.upsertSavedTheme(theme) }
                showThemeBuilder = false
                builderTheme = null
            }
        )
    }
    if (blockedOpen) {
        BlockedUsersDialog(onDismiss = { blockedOpen = false })
    }
    if (proxyOpen) {
        ProxyDialog(onDismiss = { proxyOpen = false })
    }
    shareThemeTarget?.let { theme ->
        com.secondream.novagram.ui.components.ShareChatPickerSheet(
            title = stringResource(R.string.theme_share_picker_title),
            onDismiss = { shareThemeTarget = null },
            onPick = { chatId ->
                // Synthesize the share payload from this saved theme so it
                // imports cleanly on the recipient's device, encode it into the
                // nova://theme deeplink, send it to the chosen chat, then drop
                // the user into that chat.
                val payload = appearance.copy(
                    customAccentArgb = theme.accentArgb,
                    customMyBubbleArgb = theme.myBubbleArgb,
                    customOthersBubbleArgb = theme.othersBubbleArgb,
                    customBgArgb = theme.bgArgb,
                    customInputBarArgb = theme.inputBarArgb,
                    activeSavedThemeId = null
                )
                val json = buildThemeShareJson(payload)
                val encoded = android.util.Base64.encodeToString(
                    json.toByteArray(Charsets.UTF_8),
                    android.util.Base64.URL_SAFE or
                        android.util.Base64.NO_WRAP or
                        android.util.Base64.NO_PADDING
                )
                val message = context.getString(
                    R.string.theme_share_body, "nova://theme?data=$encoded"
                )
                scope.launch { runCatching { TdClient.sendText(chatId, message) } }
                shareThemeTarget = null
                onOpenChat(chatId)
            }
        )
    }
}

/**
 * Lists the user ids on the main block list and lets you unblock each one in
 * place. Reached from Settings → Privacy → "Utenti bloccati".
 */
@Composable
private fun BlockedUsersDialog(onDismiss: () -> Unit) {
    var users by remember { mutableStateOf<List<org.drinkless.tdlib.TdApi.User>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val idsList = runCatching { TdClient.getBlockedUserIds() }.getOrDefault(emptyList())
        users = idsList.mapNotNull { uid ->
            TdClient.getCachedUser(uid) ?: runCatching { TdClient.getUser(uid) }.getOrNull()
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    stringResource(R.string.settings_blocked_users),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(12.dp))
                val all = users
                when {
                    all == null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp
                            )
                        }
                    }
                    all.isEmpty() -> {
                        Text(
                            stringResource(R.string.blocked_users_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                    else -> {
                        // Search/filter by name or @username.
                        androidx.compose.material3.OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    com.secondream.novagram.ui.icons.PhosphorIcons.MagnifyingGlass,
                                    contentDescription = null
                                )
                            },
                            placeholder = { Text(stringResource(R.string.search_action)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        val filtered = all.filter { u ->
                            if (query.isBlank()) true
                            else {
                                val nm = "${u.firstName} ${u.lastName}".trim()
                                val un = u.usernames?.activeUsernames?.firstOrNull() ?: ""
                                nm.contains(query, ignoreCase = true) ||
                                    un.contains(query, ignoreCase = true)
                            }
                        }
                        if (filtered.isEmpty()) {
                            Text(
                                stringResource(R.string.blocked_users_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier.heightIn(max = 360.dp)
                            ) {
                                items(filtered, key = { it.id }) { u ->
                                    BlockedUserRow(
                                        user = u,
                                        onUnblocked = { users = users?.filter { it.id != u.id } }
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.delete_chat_cancel))
                    }
                }
            }
        }
    }
}

private fun parseMtprotoProxyLink(raw: String): Triple<String, Int, String>? {
    val s = raw.trim()
    val uri = runCatching { android.net.Uri.parse(s) }.getOrNull() ?: return null
    val looksProxy = (uri.scheme == "tg" && uri.host == "proxy") ||
        ((uri.host == "t.me" || uri.host == "telegram.me") && (uri.path ?: "").trim('/') == "proxy")
    if (!looksProxy) return null
    val server = uri.getQueryParameter("server")?.takeIf { it.isNotBlank() } ?: return null
    val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
    val secret = uri.getQueryParameter("secret")?.takeIf { it.isNotBlank() } ?: return null
    return Triple(server, port, secret)
}

@Composable
private fun ProxyDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var proxies by remember { mutableStateOf<List<org.drinkless.tdlib.TdApi.AddedProxy>?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    var linkOrServer by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val invalidMsg = stringResource(R.string.proxy_invalid)

    LaunchedEffect(reloadTick) {
        proxies = runCatching { TdClient.getProxies() }.getOrDefault(emptyList())
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.settings_proxy),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.proxy_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                val list = proxies
                when {
                    list == null -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp
                            )
                        }
                    }
                    list.isEmpty() -> {
                        Text(
                            stringResource(R.string.proxy_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    else -> {
                        list.forEach { added ->
                            val typeLabel = when (added.proxy.type) {
                                is org.drinkless.tdlib.TdApi.ProxyTypeMtproto -> "MTProto"
                                is org.drinkless.tdlib.TdApi.ProxyTypeSocks5 -> "SOCKS5"
                                is org.drinkless.tdlib.TdApi.ProxyTypeHttp -> "HTTP"
                                else -> "Proxy"
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                    .clickable(enabled = !busy) {
                                        busy = true
                                        scope.launch {
                                            if (added.isEnabled) TdClient.disableProxy()
                                            else TdClient.enableProxy(added.id)
                                            reloadTick++
                                            busy = false
                                        }
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                Icon(
                                    if (added.isEnabled)
                                        com.secondream.novagram.ui.icons.PhosphorIcons.Check
                                    else com.secondream.novagram.ui.icons.PhosphorIcons.Lock,
                                    contentDescription = null,
                                    tint = if (added.isEnabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        added.proxy.server + ":" + added.proxy.port,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        if (added.isEnabled)
                                            typeLabel + " · " + stringResource(R.string.proxy_active)
                                        else typeLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                androidx.compose.material3.IconButton(
                                    enabled = !busy,
                                    onClick = {
                                        busy = true
                                        scope.launch {
                                            TdClient.removeProxy(added.id)
                                            reloadTick++
                                            busy = false
                                        }
                                    }
                                ) {
                                    Icon(
                                        com.secondream.novagram.ui.icons.PhosphorIcons.Trash,
                                        contentDescription = stringResource(R.string.proxy_remove),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        if (list.any { it.isEnabled }) {
                            androidx.compose.material3.TextButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        TdClient.disableProxy()
                                        reloadTick++
                                        busy = false
                                    }
                                }
                            ) { Text(stringResource(R.string.proxy_disable)) }
                        }
                    }
                }

                Divider(Modifier.padding(vertical = 12.dp))

                Text(
                    stringResource(R.string.proxy_add),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = linkOrServer,
                    onValueChange = { linkOrServer = it; error = null },
                    singleLine = true,
                    label = { Text(stringResource(R.string.proxy_server_or_link)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = port,
                    onValueChange = { new -> port = new.filter { it.isDigit() }; error = null },
                    singleLine = true,
                    label = { Text(stringResource(R.string.proxy_port)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it; error = null },
                    singleLine = true,
                    label = { Text(stringResource(R.string.proxy_secret)) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.delete_chat_cancel))
                    }
                    Spacer(Modifier.size(8.dp))
                    androidx.compose.material3.Button(
                        enabled = !busy,
                        onClick = {
                            val parsed = parseMtprotoProxyLink(linkOrServer)
                            val srv = parsed?.first ?: linkOrServer.trim()
                            val prt = parsed?.second ?: (port.toIntOrNull() ?: -1)
                            val sec = parsed?.third ?: secret.trim()
                            if (srv.isBlank() || prt !in 1..65535 || sec.isBlank()) {
                                error = invalidMsg
                            } else {
                                error = null
                                busy = true
                                scope.launch {
                                    val res = TdClient.addMtprotoProxy(srv, prt, sec, enable = true)
                                    reloadTick++
                                    busy = false
                                    if (res != null) {
                                        linkOrServer = ""; port = ""; secret = ""
                                    }
                                }
                            }
                        }
                    ) { Text(stringResource(R.string.proxy_add)) }
                }
            }
        }
    }
}

@Composable
private fun BlockedUserRow(user: org.drinkless.tdlib.TdApi.User, onUnblocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    val name = "${user.firstName} ${user.lastName}".trim().takeIf { it.isNotBlank() }
        ?: stringResource(R.string.blocked_user_fallback)
    val username = user.usernames?.activeUsernames?.firstOrNull()?.takeIf { it.isNotBlank() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.secondream.novagram.ui.components.Avatar(
            file = user.profilePhoto?.small,
            fallbackText = name,
            size = 40.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (username != null) {
                Text(
                    "@$username",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        androidx.compose.material3.TextButton(
            onClick = {
                scope.launch {
                    runCatching { TdClient.unblockUser(user.id) }.onSuccess {
                        com.secondream.novagram.ui.components.NovaSnackbar.show(
                            R.string.snack_user_unblocked,
                            com.secondream.novagram.ui.icons.PhosphorIcons.Check
                        )
                        onUnblocked()
                    }
                }
            }
        ) {
            Text(stringResource(R.string.action_unblock_user))
        }
    }
}

/**
 * A settings section with a tappable header that expands/collapses its card
 * with a fluid animation. The caret rotates 0°→180° on toggle. Replaces the
 * plain SectionHeader+SectionCard pair so the long settings page can be
 * folded section-by-section (Eugenio: "bisogna scorrere tanto"). Default
 * expanded so nothing is hidden on first open.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = androidx.compose.animation.core.tween(240),
        label = "sectionCaret"
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        // Each section is its own rounded card with an accent icon, title and
        // a chevron — same tile language as the rest of the app. No ripple
        // (indication = null); the chevron rotation is the affordance.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(cs.surface)
                .border(
                    width = 0.5.dp,
                    color = cs.outline.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(18.dp)
                )
                .clickable(
                    interactionSource = remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    },
                    indication = null,
                    onClick = onToggle
                )
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(cs.primary.copy(alpha = if (expanded) 0.20f else 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
            Icon(
                com.secondream.novagram.ui.icons.PhosphorIcons.CaretDown,
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(rotation)
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.expandVertically(
                animationSpec = androidx.compose.animation.core.tween(240)
            ) + androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(240)
            ),
            exit = androidx.compose.animation.shrinkVertically(
                animationSpec = androidx.compose.animation.core.tween(200)
            ) + androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(140)
            )
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                SectionCard { content() }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 16.dp, bottom = 10.dp, top = 4.dp)
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        content()
    }
}

@Composable
private fun Divider() {
    androidx.compose.material3.HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun ThemeRow(current: ThemeMode, onPick: (ThemeMode) -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            stringResource(R.string.settings_theme),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(10.dp))
        // First row: System / Light
        Row(modifier = Modifier.fillMaxWidth()) {
            SegmentedChip(
                label = stringResource(R.string.settings_theme_system),
                selected = current == ThemeMode.System,
                onClick = { onPick(ThemeMode.System) },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            SegmentedChip(
                label = stringResource(R.string.settings_theme_light),
                selected = current == ThemeMode.Light,
                onClick = { onPick(ThemeMode.Light) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        // Second row: Dark / AMOLED
        Row(modifier = Modifier.fillMaxWidth()) {
            SegmentedChip(
                label = stringResource(R.string.settings_theme_dark),
                selected = current == ThemeMode.Dark,
                onClick = { onPick(ThemeMode.Dark) },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            SegmentedChip(
                label = stringResource(R.string.settings_theme_amoled),
                selected = current == ThemeMode.Amoled,
                onClick = { onPick(ThemeMode.Amoled) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SegmentedChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier = modifier
            .pressScale(interaction, pressedScale = 0.94f)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun AccentRow(current: AccentColor, onPick: (AccentColor) -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            stringResource(R.string.settings_accent),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AccentSwatch(
                color = AccentPalette.Amber.primary,
                label = stringResource(R.string.settings_accent_amber),
                selected = current == AccentColor.Amber,
                onClick = { onPick(AccentColor.Amber) }
            )
            AccentSwatch(
                color = AccentPalette.Blue.primary,
                label = stringResource(R.string.settings_accent_blue),
                selected = current == AccentColor.Blue,
                onClick = { onPick(AccentColor.Blue) }
            )
            AccentSwatch(
                color = AccentPalette.Green.primary,
                label = stringResource(R.string.settings_accent_green),
                selected = current == AccentColor.Green,
                onClick = { onPick(AccentColor.Green) }
            )
            AccentSwatch(
                color = AccentPalette.Violet.primary,
                label = stringResource(R.string.settings_accent_violet),
                selected = current == AccentColor.Violet,
                onClick = { onPick(AccentColor.Violet) }
            )
            AccentSwatch(
                color = AccentPalette.Rose.primary,
                label = stringResource(R.string.settings_accent_rose),
                selected = current == AccentColor.Rose,
                onClick = { onPick(AccentColor.Rose) }
            )
            AccentSwatch(
                color = AccentPalette.Cyan.primary,
                label = stringResource(R.string.settings_accent_cyan),
                selected = current == AccentColor.Cyan,
                onClick = { onPick(AccentColor.Cyan) }
            )
            AccentSwatch(
                color = AccentPalette.Orange.primary,
                label = stringResource(R.string.settings_accent_orange),
                selected = current == AccentColor.Orange,
                onClick = { onPick(AccentColor.Orange) }
            )
        }
    }
}

@Composable
private fun AccentSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(44.dp)
                .pressScale(interaction, pressedScale = 0.90f)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 2.5.dp else 0.dp,
                    color = MaterialTheme.colorScheme.onBackground,
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = interaction,
                    indication = androidx.compose.foundation.LocalIndication.current,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            // Selection shown by the accent ring around the swatch — no tick
            // (a dot would be invisible on the coloured accent swatch).
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class LanguageOption(val tag: String, val labelRes: Int)

private val LANGUAGE_OPTIONS = listOf(
    LanguageOption("system", R.string.settings_language_system),
    LanguageOption("it", R.string.settings_language_it),
    LanguageOption("en", R.string.settings_language_en),
    LanguageOption("es", R.string.settings_language_es),
    LanguageOption("fr", R.string.settings_language_fr),
    LanguageOption("de", R.string.settings_language_de)
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageRow(current: String, onPick: (String) -> Unit) {
    var pickerOpen by remember { mutableStateOf(false) }
    val currentLabel = LANGUAGE_OPTIONS.firstOrNull { it.tag == current }?.labelRes
        ?: R.string.settings_language_system
    // Collapsed card: shows the active language + chevron, tap opens
    // the bottom-sheet picker. Replaces the previous "always-expanded
    // list of 6 rows with checkmark" because at 6 entries it ate too
    // much vertical space in the Settings screen and made the rest of
    // the page scroll past the fold on mid-size devices.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { pickerOpen = true })
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(currentLabel),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            com.secondream.novagram.ui.icons.PhosphorIcons.CaretDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
    }
    if (pickerOpen) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { pickerOpen = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .navigationBarsPadding()
            ) {
                Text(
                    stringResource(R.string.settings_section_language),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                LANGUAGE_OPTIONS.forEach { opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = {
                                onPick(opt.tag)
                                pickerOpen = false
                            })
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(opt.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (current == opt.tag) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private data class BubbleSwatch(val color: BubbleColor, val labelRes: Int, val previewBg: Color, val previewBorder: Color?)

@Composable
private fun BubbleColorRow(
    label: String,
    current: BubbleColor,
    onPick: (BubbleColor) -> Unit
) {
    val swatches = listOf(
        // Default has no fixed fill, use surfaceVariant + outline so the user
        // sees it as "follow theme".
        BubbleSwatch(BubbleColor.Default, R.string.bubble_default,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.outline),
        BubbleSwatch(BubbleColor.Amber,  R.string.bubble_amber,  BubblePalette.Amber.background,  null),
        BubbleSwatch(BubbleColor.Blue,   R.string.bubble_blue,   BubblePalette.Blue.background,   null),
        BubbleSwatch(BubbleColor.Green,  R.string.bubble_green,  BubblePalette.Green.background,  null),
        BubbleSwatch(BubbleColor.Violet, R.string.bubble_violet, BubblePalette.Violet.background, null),
        BubbleSwatch(BubbleColor.Rose,   R.string.bubble_rose,   BubblePalette.Rose.background,   null),
    )
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            swatches.forEachIndexed { idx, s ->
                if (idx > 0) Spacer(Modifier.width(12.dp))
                val interaction = remember(s.color) { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .pressScale(interaction, pressedScale = 0.90f)
                        .clip(CircleShape)
                        .background(s.previewBg)
                        .border(
                            width = if (current == s.color) 2.5.dp else (if (s.previewBorder != null) 1.dp else 0.dp),
                            color = if (current == s.color) MaterialTheme.colorScheme.onBackground
                                    else (s.previewBorder ?: Color.Transparent),
                            shape = CircleShape
                        )
                        .clickable(
                            interactionSource = interaction,
                            indication = androidx.compose.foundation.LocalIndication.current
                        ) { onPick(s.color) },
                    contentAlignment = Alignment.Center
                ) {
                    if (current == s.color) {
                        // Selection shown by the ring around the swatch — no tick.
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, destructive: Boolean, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.97f)
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PrivacyToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val contentAlpha = if (enabled) 1f else 0.45f
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.98f)
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                enabled = enabled
            ) { onToggle(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
            )
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onToggle,
            enabled = enabled
        )
    }
}

/**
 * "Elimina dati app" — wipes all downloaded media + local caches (TDLib files,
 * Coil image cache, app cacheDir) WITHOUT logging the user out. Shows an
 * animated progress bar and counts up the freed space in MB/GB as it goes.
 */
@Composable
private fun ClearAppDataRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cleaning by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var freedBytes by remember { mutableStateOf(0L) }
    var estimateBytes by remember { mutableStateOf(0L) }

    val rawProgress = when {
        done -> 1f
        estimateBytes > 0L -> (freedBytes.toFloat() / estimateBytes.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }
    val animProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = androidx.compose.animation.core.tween(600),
        label = "clearProgress"
    )
    val animBytes by androidx.compose.animation.core.animateFloatAsState(
        targetValue = freedBytes.toFloat(),
        animationSpec = androidx.compose.animation.core.tween(800),
        label = "clearBytes"
    )
    fun fmt(bytes: Float): String {
        val mb = bytes / (1024f * 1024f)
        return if (mb >= 1024f) String.format("%.2f GB", mb / 1024f)
        else String.format("%.1f MB", mb)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !cleaning) {
                cleaning = true
                done = false
                freedBytes = 0L
                estimateBytes = 0L
                scope.launch {
                    estimateBytes = (
                        com.secondream.novagram.td.TdClient.storageFilesSize() +
                            com.secondream.novagram.util.FileUtils.dirSize(ctx.cacheDir)
                        ).coerceAtLeast(1L)
                    // 1) Local caches (Coil + cacheDir) off the main thread.
                    val appFreed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.secondream.novagram.util.FileUtils.clearAppCaches(ctx)
                    }
                    freedBytes += appFreed
                    // 2) TDLib downloads (usually the bulk).
                    freedBytes += com.secondream.novagram.td.TdClient.clearTdlibDownloads()
                    done = true
                    cleaning = false
                    // Show the "Liberati X MB" result briefly — long enough for
                    // the count-up animation (800ms) to finish and be read —
                    // then collapse back to the normal tappable button. Without
                    // this the row stayed stuck on a full/"finished" progress
                    // bar and never returned to button state.
                    kotlinx.coroutines.delay(2500)
                    done = false
                    freedBytes = 0L
                    estimateBytes = 0L
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            com.secondream.novagram.ui.icons.PhosphorIcons.Trash,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.clear_app_data_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (cleaning || done) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { animProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        if (done) R.string.clear_app_data_done
                        else R.string.clear_app_data_cleaning,
                        fmt(animBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    stringResource(R.string.clear_app_data_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InfoLinksGrid() {
    val context = LocalContext.current
    // The four CTAs, now living inside the Informazioni section. The update
    // tile keeps the accent "update available" dot (same signal as the
    // chat-list Settings gear).
    val updateAvailable by com.secondream.novagram.update
        .UpdateChecker.updateAvailable
        .collectAsState()
    val phos = com.secondream.novagram.ui.icons.PhosphorIcons
    val creditTiles = listOf(
        com.secondream.novagram.ui.components.ActionTile(
            label = stringResource(R.string.action_check_updates),
            icon = phos.DownloadSimple,
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(
                                com.secondream.novagram.update.UpdateChecker.RELEASES_PAGE
                            )
                        )
                    )
                }
            }
        ),
        com.secondream.novagram.ui.components.ActionTile(
            label = stringResource(R.string.credits_github),
            icon = phos.Github,
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/cheipstudio-cmyk/novagram")
                        )
                    )
                }
            }
        ),
        com.secondream.novagram.ui.components.ActionTile(
            label = stringResource(R.string.credits_join_group),
            icon = phos.UsersThree,
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://t.me/novagram_messenger")
                        ).setPackage(context.packageName)
                    )
                }.recoverCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://t.me/novagram_messenger")
                        )
                    )
                }
            }
        ),
        com.secondream.novagram.ui.components.ActionTile(
            label = stringResource(R.string.credits_buy_coffee),
            icon = phos.Sparkle,
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://buymeacoffee.com/M12oPyJwty")
                        )
                    )
                }
            }
        )
    )
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        creditTiles.chunked(2).forEachIndexed { rowIdx, rowTiles ->
            if (rowIdx > 0) Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowTiles.forEachIndexed { colIdx, t ->
                    if (rowIdx == 0 && colIdx == 0) {
                        androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                            com.secondream.novagram.ui.components.ActionTileButton(
                                tile = t,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (updateAvailable) {
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .size(9.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(MaterialTheme.colorScheme.error)
                                )
                            }
                        }
                    } else {
                        com.secondream.novagram.ui.components.ActionTileButton(
                            tile = t,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditsBlock() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Nova brand glyph — sits above the credits + CTAs as a quiet
        // signature mark for the page footer. We pick the dark / light
        // PNG variant by whether the current theme background is light
        // or dark, since each PNG is colour-baked: the "dark" file is a
        // dark glyph meant for light surfaces, and vice-versa. Keeping
        // them as raster PNGs (rather than tintable vectors) preserves
        // the gold-foil highlight on the diagonal stroke that's part of
        // the brand identity.
        val isLightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
        val novaIcon = if (isLightTheme) R.drawable.ic_novagram_dark
                       else R.drawable.ic_novagram_light
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(novaIcon),
            contentDescription = "Novagram",
            modifier = Modifier
                .size(64.dp)
                .padding(bottom = 4.dp)
        )
        Text(
            stringResource(R.string.credits_built_by),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.settings_privacy_policy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
            modifier = Modifier.clickable {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://novagram.netlify.app/#privacy")
                        )
                    )
                }
            }
        )
    }
}

/**
 * Row inside the "Tema personalizzato" section of Settings. Shows a swatch
 * with the active custom accent (if any) plus three actions: open the
 * builder (sliders + preview), clear the override and go back to the
 * preset palette, and share the current theme JSON via Intent.ACTION_SEND.
 */
/**
 * The "Temi personalizzati" section. Lists the user's saved themes,
 * exposes a "+ Crea tema personalizzato" entry, and offers reset + paste
 * actions. Per-theme overflow menu (Applica/Modifica/Condividi/Elimina)
 * lives on each row.
 */
@Composable
private fun SavedThemesSection(
    savedThemes: List<com.secondream.novagram.settings.SavedTheme>,
    activeId: String?,
    onCreate: () -> Unit,
    onApply: (com.secondream.novagram.settings.SavedTheme) -> Unit,
    onEdit: (com.secondream.novagram.settings.SavedTheme) -> Unit,
    onDelete: (com.secondream.novagram.settings.SavedTheme) -> Unit,
    onShare: (com.secondream.novagram.settings.SavedTheme) -> Unit,
    onResetToBase: () -> Unit,
    onPaste: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            stringResource(R.string.theme_custom_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            stringResource(R.string.theme_custom_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        if (savedThemes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.theme_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (theme in savedThemes) {
                    SavedThemeRow(
                        theme = theme,
                        isActive = theme.id == activeId,
                        onApply = { onApply(theme) },
                        onEdit = { onEdit(theme) },
                        onShare = { onShare(theme) },
                        onDelete = { onDelete(theme) }
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeActionButton(
                label = stringResource(R.string.theme_create_new),
                onClick = onCreate,
                modifier = Modifier.weight(1f)
            )
            ThemeActionButton(
                label = stringResource(R.string.theme_paste),
                onClick = onPaste,
                modifier = Modifier.weight(1f),
                outline = true
            )
        }
        if (activeId != null) {
            Spacer(Modifier.height(8.dp))
            ThemeActionButton(
                label = stringResource(R.string.theme_reset_to_base),
                onClick = onResetToBase,
                modifier = Modifier.fillMaxWidth(),
                outline = true
            )
        }
    }
}

/**
 * One row inside SavedThemesSection. Shows a 4-color preview chip strip
 * (accent / my bubble / others bubble / bg), the theme's name, and a "..."
 * overflow menu with the four per-theme actions. Tap the row body to
 * apply the theme directly.
 */
@Composable
private fun SavedThemeRow(
    theme: com.secondream.novagram.settings.SavedTheme,
    isActive: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .clickable(onClick = onApply)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Two-colour swatch — accent + background — fused into a single rounded
        // pill with NO gap between them (Eugenio: "i due colori sfondo e accent
        // devono essere attaccati, non avere spazio").
        Row(modifier = Modifier.clip(RoundedCornerShape(6.dp))) {
            Box(
                modifier = Modifier
                    .size(width = 16.dp, height = 26.dp)
                    .background(Color(theme.accentArgb))
            )
            Box(
                modifier = Modifier
                    .size(width = 16.dp, height = 26.dp)
                    .background(Color(theme.bgArgb))
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            theme.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isActive) {
            // Accent dot marks the active theme (replaces the old ✓ tick).
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(6.dp))
        }
        androidx.compose.material3.IconButton(onClick = { menuOpen = true }) {
            Icon(
                com.secondream.novagram.ui.icons.PhosphorIcons.DotsThreeVertical,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    // Actions live in the app's standard tile bottom-sheet (same look as the
    // chat/member action sheets) instead of a stock dropdown menu.
    if (menuOpen) {
        com.secondream.novagram.ui.components.ActionBottomSheet(
            title = theme.name,
            onDismiss = { menuOpen = false },
            tiles = listOf(
                com.secondream.novagram.ui.components.ActionTile(
                    label = stringResource(R.string.theme_action_apply),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.Check,
                    onClick = { menuOpen = false; onApply() }
                ),
                com.secondream.novagram.ui.components.ActionTile(
                    label = stringResource(R.string.theme_action_edit),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.PencilSimple,
                    onClick = { menuOpen = false; onEdit() }
                ),
                com.secondream.novagram.ui.components.ActionTile(
                    label = stringResource(R.string.theme_share),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.PaperPlaneRight,
                    onClick = { menuOpen = false; onShare() }
                ),
                com.secondream.novagram.ui.components.ActionTile(
                    label = stringResource(R.string.theme_action_delete),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.Trash,
                    onClick = { menuOpen = false; onDelete() },
                    destructive = true
                )
            )
        )
    }
}

@Composable
private fun ThemeActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outline: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .then(
                if (outline) Modifier.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(24.dp)
                ) else Modifier.background(MaterialTheme.colorScheme.primary)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (outline) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Serialize the user's appearance preferences to a JSON string suitable
 * for sharing. We only ship fields that are device-portable — language
 * tag is included so importing on another device picks up the same
 * locale preference too.
 *
 * org.json is used instead of kotlinx.serialization because the schema
 * is six lines long and pulling in a Serializable annotation just for
 * this would be overkill.
 */
internal fun buildThemeShareJson(prefs: com.secondream.novagram.settings.AppearancePrefs): String {
    val obj = org.json.JSONObject()
    obj.put("novagram_theme_version", 1)
    obj.put("themeMode", prefs.themeMode.name)
    obj.put("accentColor", prefs.accentColor.name)
    obj.put("myBubbleColor", prefs.myBubbleColor.name)
    obj.put("othersBubbleColor", prefs.othersBubbleColor.name)
    obj.put("languageTag", prefs.languageTag)
    prefs.customAccentArgb?.let { obj.put("customAccentArgb", it) }
    prefs.customMyBubbleArgb?.let { obj.put("customMyBubbleArgb", it) }
    prefs.customOthersBubbleArgb?.let { obj.put("customOthersBubbleArgb", it) }
    prefs.customBgArgb?.let { obj.put("customBgArgb", it) }
    return obj.toString(2)
}

/**
 * Parse a theme JSON blob produced by buildThemeShareJson.
 *
 * Accepts three input shapes for convenience:
 *   1. Raw JSON: starts with `{`. Used to be the share format in 0.5.0/0.5.1.
 *   2. A bare nova://theme?data=<base64> deeplink, exactly as the
 *      share button produces it now.
 *   3. Any text that *contains* the deeplink anywhere (e.g. the localised
 *      share body "Apri questo link per applicare il tema:\n<link>"). The
 *      regex below extracts the first deeplink it finds and decodes from
 *      there.
 *
 * Returns null if nothing matches — we explicitly check the version marker
 * before doing anything else, so a random copy-pasted JSON object doesn't
 * accidentally rewrite the user's appearance. Unknown enum names fall back
 * to current defaults rather than crashing.
 */
internal fun parseThemeJson(raw: String): com.secondream.novagram.settings.AppearancePrefs? {
    val trimmed = raw.trim()
    // Try the deeplink path first — a substring match works regardless of
    // surrounding "Apri questo link" wrapper text.
    val deeplinkRegex = Regex("nova://theme\\?data=([A-Za-z0-9_\\-]+)")
    val match = deeplinkRegex.find(trimmed)
    val jsonString = if (match != null) {
        runCatching {
            val encoded = match.groupValues[1]
            val bytes = android.util.Base64.decode(
                encoded,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            String(bytes, Charsets.UTF_8)
        }.getOrNull() ?: return null
    } else {
        // Not a deeplink — assume raw JSON.
        trimmed
    }
    val obj = runCatching { org.json.JSONObject(jsonString) }.getOrNull() ?: return null
    if (obj.optInt("novagram_theme_version", -1) != 1) return null
    return runCatching {
        com.secondream.novagram.settings.AppearancePrefs(
            themeMode = enumValueOfOrNull<com.secondream.novagram.settings.ThemeMode>(
                obj.optString("themeMode")
            ) ?: com.secondream.novagram.settings.ThemeMode.Dark,
            accentColor = enumValueOfOrNull<com.secondream.novagram.settings.AccentColor>(
                obj.optString("accentColor")
            ) ?: com.secondream.novagram.settings.AccentColor.Amber,
            languageTag = if (obj.has("languageTag")) obj.getString("languageTag") else "system",
            myBubbleColor = enumValueOfOrNull<com.secondream.novagram.settings.BubbleColor>(
                obj.optString("myBubbleColor")
            ) ?: com.secondream.novagram.settings.BubbleColor.Default,
            othersBubbleColor = enumValueOfOrNull<com.secondream.novagram.settings.BubbleColor>(
                obj.optString("othersBubbleColor")
            ) ?: com.secondream.novagram.settings.BubbleColor.Default,
            customAccentArgb = if (obj.has("customAccentArgb")) obj.getInt("customAccentArgb") else null,
            customMyBubbleArgb = if (obj.has("customMyBubbleArgb")) obj.getInt("customMyBubbleArgb") else null,
            customOthersBubbleArgb = if (obj.has("customOthersBubbleArgb")) obj.getInt("customOthersBubbleArgb") else null,
            customBgArgb = if (obj.has("customBgArgb")) obj.getInt("customBgArgb") else null
        )
    }.getOrNull()
}

private inline fun <reified E : Enum<E>> enumValueOfOrNull(name: String?): E? {
    if (name.isNullOrBlank()) return null
    return runCatching { enumValueOf<E>(name) }.getOrNull()
}

/**
 * Theme builder dialog. 5 sections (accent, my-bubble, others-bubble, bg,
 * input bar) selectable via pill tabs, each editing one color via the
 * HSV ColorWheelPicker. Name field is required — saved themes can't be
 * unnamed. On save we hand back a SavedTheme to the parent.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ThemeBuilderDialog(
    initialTheme: com.secondream.novagram.settings.SavedTheme?,
    onDismiss: () -> Unit,
    onSave: (com.secondream.novagram.settings.SavedTheme) -> Unit
) {
    var section by remember { mutableStateOf(0) }
    val defaults = listOf(
        0xFFD9A85C.toInt(), // accent
        0xFF2A4F7A.toInt(), // my bubble (legacy, not editable)
        0xFF374151.toInt(), // others bubble (legacy, not editable)
        0xFF0F1115.toInt(), // bg
        0xFF1A1D24.toInt()  // input bar (legacy, derived from bg)
    )
    // 2-slot color list. The bubble + input-bar fields stay in
    // SavedTheme for backward compatibility with older saved JSON,
    // but the builder no longer exposes them — bubbles derive from
    // theme + accent, and the input bar now always paints with the
    // background colour (per user request: one less knob, surfaces
    // stay coherent).
    // Indices: 0 = accent, 1 = bg.
    val initials = listOf(
        initialTheme?.accentArgb ?: defaults[0],
        initialTheme?.bgArgb ?: defaults[3]
    )
    val colors = remember {
        mutableStateListOf<Int>().apply { initials.forEach { add(it) } }
    }
    var name by remember { mutableStateOf(initialTheme?.name ?: "") }

    val sectionTitles = listOf(
        stringResource(R.string.theme_section_accent),
        stringResource(R.string.theme_section_bg)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_builder_title)) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.theme_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    sectionTitles.forEachIndexed { i, title ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (section == i) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .clickable { section = i }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (section == i)
                                    MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (section == i) FontWeight.SemiBold else FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // HSV wheel — keyed on section so it resets to the stored
                // color when the user switches tabs.
                key(section) {
                    com.secondream.novagram.ui.components.ColorWheelPicker(
                        initialArgb = colors[section],
                        onColorChanged = { argb -> colors[section] = argb }
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    if (name.isBlank()) return@TextButton
                    // isLight and inputBarArgb were user-editable knobs;
                    // now they're derived from the background:
                    //   - isLight = bg has high luminance → light foundation
                    //   - inputBarArgb = bgArgb  (keeps surfaces coherent)
                    // The fields still exist on SavedTheme so old saved
                    // JSON keeps deserialising; we just write the derived
                    // values instead of asking the user.
                    val derivedLight = Color(colors[1]).luminance() > 0.5f
                    val theme = com.secondream.novagram.settings.SavedTheme(
                        id = initialTheme?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name.trim(),
                        accentArgb = colors[0],
                        myBubbleArgb = initialTheme?.myBubbleArgb ?: 0,
                        othersBubbleArgb = initialTheme?.othersBubbleArgb ?: 0,
                        bgArgb = colors[1],
                        inputBarArgb = colors[1],
                        isLight = derivedLight
                    )
                    onSave(theme)
                },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.theme_save))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.delete_chat_cancel))
            }
        }
    )
}

/**
 * One row in the unified theme list — used for the 4 base modes
 * (Sistema/Chiaro/Scuro/AMOLED). Visually it's the same shape as
 * [SavedThemeRow] so the eye reads the whole list as one continuous set
 * of selectable themes, without a visual seam between "built-in" and
 * "custom". A check icon on the right marks the currently active row.
 *
 * Selected state is computed in the parent and passed in — the parent
 * already knows whether to highlight a base row (no custom active) or a
 * saved-theme row (custom active), so this composable just renders.
 */
@androidx.compose.runtime.Composable
private fun BaseModeListRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
