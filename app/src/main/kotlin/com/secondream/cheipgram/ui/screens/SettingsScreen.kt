@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.cheipgram.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import com.secondream.cheipgram.BuildConfig
import com.secondream.cheipgram.R
import com.secondream.cheipgram.settings.AccentColor
import com.secondream.cheipgram.settings.BubbleColor
import com.secondream.cheipgram.settings.AppSettings
import com.secondream.cheipgram.settings.ThemeMode
import com.secondream.cheipgram.td.TdClient
import com.secondream.cheipgram.ui.theme.AccentPalette
import com.secondream.cheipgram.ui.theme.BubblePalette

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appearance by AppSettings.appearance.collectAsState(
        initial = com.secondream.cheipgram.settings.AppearancePrefs()
    )
    val savedThemes by AppSettings.savedThemes.collectAsState(initial = emptyList())
    var showReadDate by remember { mutableStateOf(true) }
    var showThemeBuilder by remember { mutableStateOf(false) }
    var builderTheme by remember { mutableStateOf<com.secondream.cheipgram.settings.SavedTheme?>(null) }
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
                            Icons.AutoMirrored.Outlined.ArrowBack,
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
            // APPEARANCE
            SectionHeader(stringResource(R.string.settings_section_appearance))
            SectionCard {
                ThemeRow(
                    current = appearance.themeMode,
                    onPick = { mode -> scope.launch { AppSettings.setThemeMode(mode) } }
                )
                Divider()
                AccentRow(
                    current = appearance.accentColor,
                    onPick = { color -> scope.launch { AppSettings.setAccentColor(color) } }
                )
            }

            Spacer(Modifier.height(20.dp))

            // TEXT SIZE — global multiplier for every Text in the app.
            SectionHeader(stringResource(R.string.settings_section_text_size))
            SectionCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.settings_text_size_sample),
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
                        // The slider commits the new value to DataStore on
                        // every drag step. DataStore writes are cheap and
                        // the Theme recomposes immediately, so the preview
                        // line above updates as the user scrubs.
                        onValueChange = { v ->
                            scope.launch { AppSettings.setTextScale(v) }
                        },
                        valueRange = 0.85f..1.35f,
                        steps = 9
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // LANGUAGE
            SectionHeader(stringResource(R.string.settings_section_language))
            SectionCard {
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
            }

            Spacer(Modifier.height(20.dp))

            // (Bubble color picker removed — bubbles now derive
            // automatically from the active theme + accent preset to keep
            // the 4×4 combo matrix readable in every mode.)

            // CUSTOM THEME
            SectionHeader(stringResource(R.string.settings_section_custom_theme))
            SectionCard {
                SavedThemesSection(
                    savedThemes = savedThemes,
                    activeId = appearance.activeSavedThemeId,
                    onCreate = {
                        // builderTheme is set later in this function. Read
                        // setBuilderTheme via the parent state setter — we
                        // reach it through showThemeBuilder which is in the
                        // outer SettingsScreen scope.
                        builderTheme = null
                        showThemeBuilder = true
                    },
                    onApply = { theme ->
                        scope.launch { AppSettings.activateSavedTheme(theme.id) }
                    },
                    onEdit = { theme ->
                        builderTheme = theme
                        showThemeBuilder = true
                    },
                    onDelete = { theme ->
                        scope.launch { AppSettings.deleteSavedTheme(theme.id) }
                    },
                    onShare = { theme ->
                        // Build a "synthesized" AppearancePrefs from the theme
                        // (current language + theme mode + saved colors) so
                        // the share payload imports cleanly on any device.
                        val payload = appearance.copy(
                            customAccentArgb = theme.accentArgb,
                            customMyBubbleArgb = theme.myBubbleArgb,
                            customOthersBubbleArgb = theme.othersBubbleArgb,
                            customBgArgb = theme.bgArgb,
                            customInputBarArgb = theme.inputBarArgb,
                            activeSavedThemeId = null  // recipient picks a new id
                        )
                        val json = buildThemeShareJson(payload)
                        val encoded = android.util.Base64.encodeToString(
                            json.toByteArray(Charsets.UTF_8),
                            android.util.Base64.URL_SAFE or
                                android.util.Base64.NO_WRAP or
                                android.util.Base64.NO_PADDING
                        )
                        val deeplink = "cheipgram://theme?data=$encoded"
                        val message = context.getString(R.string.theme_share_body, deeplink)
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, message)
                            putExtra(android.content.Intent.EXTRA_SUBJECT, theme.name)
                        }
                        runCatching {
                            context.startActivity(
                                android.content.Intent.createChooser(
                                    intent,
                                    context.getString(R.string.theme_share_chooser)
                                )
                            )
                        }
                    },
                    onResetToBase = {
                        scope.launch { AppSettings.resetCustomOverrides() }
                    },
                    onPaste = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        val raw = cm?.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                        val parsed = raw?.let { parseThemeJson(it) }
                        if (parsed != null) {
                            // Imported themes are saved as a fresh entry in
                            // the user's library so they can be re-edited
                            // later, not just blasted into the custom slots.
                            val imported = com.secondream.cheipgram.settings.SavedTheme(
                                id = java.util.UUID.randomUUID().toString(),
                                name = context.getString(R.string.theme_imported_default_name),
                                accentArgb = parsed.customAccentArgb ?: 0xFFD9A85C.toInt(),
                                myBubbleArgb = parsed.customMyBubbleArgb ?: 0xFF2A4F7A.toInt(),
                                othersBubbleArgb = parsed.customOthersBubbleArgb ?: 0xFF374151.toInt(),
                                bgArgb = parsed.customBgArgb ?: 0xFF0F1115.toInt(),
                                inputBarArgb = parsed.customInputBarArgb ?: 0xFF1A1D24.toInt()
                            )
                            scope.launch { AppSettings.upsertSavedTheme(imported) }
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.theme_paste_success),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.theme_paste_error),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }

            Spacer(Modifier.height(20.dp))

            // PRIVACY
            SectionHeader(stringResource(R.string.settings_section_privacy))
            SectionCard {
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
            }

            Spacer(Modifier.height(20.dp))

            // INFO
            SectionHeader(stringResource(R.string.settings_section_info))
            SectionCard {
                InfoRow(
                    label = stringResource(R.string.settings_version),
                    value = BuildConfig.VERSION_NAME
                )
                Divider()
                InfoRow(
                    label = stringResource(R.string.settings_build),
                    value = BuildConfig.VERSION_CODE.toString()
                )
            }

            Spacer(Modifier.height(20.dp))

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
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
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
private fun AccentRow(current: AccentColor, onPick: (AccentColor) -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            stringResource(R.string.settings_accent),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentSwatch(
                color = AccentPalette.Amber.primary,
                label = stringResource(R.string.settings_accent_amber),
                selected = current == AccentColor.Amber,
                onClick = { onPick(AccentColor.Amber) }
            )
            Spacer(Modifier.width(14.dp))
            AccentSwatch(
                color = AccentPalette.Blue.primary,
                label = stringResource(R.string.settings_accent_blue),
                selected = current == AccentColor.Blue,
                onClick = { onPick(AccentColor.Blue) }
            )
            Spacer(Modifier.width(14.dp))
            AccentSwatch(
                color = AccentPalette.Green.primary,
                label = stringResource(R.string.settings_accent_green),
                selected = current == AccentColor.Green,
                onClick = { onPick(AccentColor.Green) }
            )
            Spacer(Modifier.width(14.dp))
            AccentSwatch(
                color = AccentPalette.Violet.primary,
                label = stringResource(R.string.settings_accent_violet),
                selected = current == AccentColor.Violet,
                onClick = { onPick(AccentColor.Violet) }
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
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 2.5.dp else 0.dp,
                    color = MaterialTheme.colorScheme.onBackground,
                    shape = CircleShape
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
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

@Composable
private fun LanguageRow(current: String, onPick: (String) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        LANGUAGE_OPTIONS.forEachIndexed { i, opt ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { onPick(opt.tag) })
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
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (i < LANGUAGE_OPTIONS.lastIndex) Divider()
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
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(s.previewBg)
                        .border(
                            width = if (current == s.color) 2.5.dp else (if (s.previewBorder != null) 1.dp else 0.dp),
                            color = if (current == s.color) MaterialTheme.colorScheme.onBackground
                                    else (s.previewBorder ?: Color.Transparent),
                            shape = CircleShape
                        )
                        .clickable { onPick(s.color) },
                    contentAlignment = Alignment.Center
                ) {
                    if (current == s.color) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = if (s.color == BubbleColor.Default)
                                MaterialTheme.colorScheme.onSurface
                            else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, destructive: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun CreditsBlock() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.credits_built_by),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable {
                    runCatching {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://buymeacoffee.com/M12oPyJwty")
                        )
                        context.startActivity(intent)
                    }
                }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text("☕", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.credits_buy_coffee),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
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
    savedThemes: List<com.secondream.cheipgram.settings.SavedTheme>,
    activeId: String?,
    onCreate: () -> Unit,
    onApply: (com.secondream.cheipgram.settings.SavedTheme) -> Unit,
    onEdit: (com.secondream.cheipgram.settings.SavedTheme) -> Unit,
    onDelete: (com.secondream.cheipgram.settings.SavedTheme) -> Unit,
    onShare: (com.secondream.cheipgram.settings.SavedTheme) -> Unit,
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
    theme: com.secondream.cheipgram.settings.SavedTheme,
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
        // Four-color preview strip. Stacked horizontally so the user gets a
        // sense of how the theme will look in chat at a glance.
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (argb in listOf(theme.accentArgb, theme.myBubbleArgb, theme.othersBubbleArgb, theme.bgArgb)) {
                Box(
                    modifier = Modifier
                        .size(width = 10.dp, height = 24.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(argb))
                )
            }
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
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Box {
            androidx.compose.material3.IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.theme_action_apply)) },
                    onClick = { menuOpen = false; onApply() }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.theme_action_edit)) },
                    onClick = { menuOpen = false; onEdit() }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.theme_share)) },
                    onClick = { menuOpen = false; onShare() }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.theme_action_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = { menuOpen = false; onDelete() }
                )
            }
        }
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
internal fun buildThemeShareJson(prefs: com.secondream.cheipgram.settings.AppearancePrefs): String {
    val obj = org.json.JSONObject()
    obj.put("cheipgram_theme_version", 1)
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
 *   2. A bare cheipgram://theme?data=<base64> deeplink, exactly as the
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
internal fun parseThemeJson(raw: String): com.secondream.cheipgram.settings.AppearancePrefs? {
    val trimmed = raw.trim()
    // Try the deeplink path first — a substring match works regardless of
    // surrounding "Apri questo link" wrapper text.
    val deeplinkRegex = Regex("cheipgram://theme\\?data=([A-Za-z0-9_\\-]+)")
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
    if (obj.optInt("cheipgram_theme_version", -1) != 1) return null
    return runCatching {
        com.secondream.cheipgram.settings.AppearancePrefs(
            themeMode = enumValueOfOrNull<com.secondream.cheipgram.settings.ThemeMode>(
                obj.optString("themeMode")
            ) ?: com.secondream.cheipgram.settings.ThemeMode.Dark,
            accentColor = enumValueOfOrNull<com.secondream.cheipgram.settings.AccentColor>(
                obj.optString("accentColor")
            ) ?: com.secondream.cheipgram.settings.AccentColor.Amber,
            languageTag = if (obj.has("languageTag")) obj.getString("languageTag") else "system",
            myBubbleColor = enumValueOfOrNull<com.secondream.cheipgram.settings.BubbleColor>(
                obj.optString("myBubbleColor")
            ) ?: com.secondream.cheipgram.settings.BubbleColor.Default,
            othersBubbleColor = enumValueOfOrNull<com.secondream.cheipgram.settings.BubbleColor>(
                obj.optString("othersBubbleColor")
            ) ?: com.secondream.cheipgram.settings.BubbleColor.Default,
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
    initialTheme: com.secondream.cheipgram.settings.SavedTheme?,
    onDismiss: () -> Unit,
    onSave: (com.secondream.cheipgram.settings.SavedTheme) -> Unit
) {
    var section by remember { mutableStateOf(0) }
    val defaults = listOf(
        0xFFD9A85C.toInt(), // accent
        0xFF2A4F7A.toInt(), // my bubble
        0xFF374151.toInt(), // others bubble
        0xFF0F1115.toInt(), // bg
        0xFF1A1D24.toInt()  // input bar
    )
    // 3-slot color list. The bubble fields stay in SavedTheme for
    // backward compatibility with old saved themes, but the builder no
    // longer exposes them — bubbles derive from theme + accent.
    // Indices: 0 = accent, 1 = bg, 2 = input bar.
    val initials = listOf(
        initialTheme?.accentArgb ?: defaults[0],
        initialTheme?.bgArgb ?: defaults[3],
        initialTheme?.inputBarArgb ?: defaults[4]
    )
    val colors = remember {
        mutableStateListOf<Int>().apply { initials.forEach { add(it) } }
    }
    var name by remember { mutableStateOf(initialTheme?.name ?: "") }

    val sectionTitles = listOf(
        stringResource(R.string.theme_section_accent),
        stringResource(R.string.theme_section_bg),
        stringResource(R.string.theme_section_input_bar)
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
                    com.secondream.cheipgram.ui.components.ColorWheelPicker(
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
                    val theme = com.secondream.cheipgram.settings.SavedTheme(
                        id = initialTheme?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name.trim(),
                        accentArgb = colors[0],
                        // Bubble fields kept on the data class for binary
                        // compatibility with previously-saved JSON, but no
                        // longer editable in the builder. We pass 0 (or the
                        // initial theme's previous value if editing) and
                        // ignore them at render time — bubbleFillFor falls
                        // back to derive-from-accent in that case.
                        myBubbleArgb = initialTheme?.myBubbleArgb ?: 0,
                        othersBubbleArgb = initialTheme?.othersBubbleArgb ?: 0,
                        bgArgb = colors[1],
                        inputBarArgb = colors[2]
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
