@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.cheipgram.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
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
    var showReadDate by remember { mutableStateOf(true) }
    var showThemeBuilder by remember { mutableStateOf(false) }
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

            // MESSAGE COLORS
            SectionHeader(stringResource(R.string.settings_section_bubbles))
            SectionCard {
                BubbleColorRow(
                    label = stringResource(R.string.settings_bubble_mine),
                    current = appearance.myBubbleColor,
                    onPick = { c -> scope.launch { AppSettings.setMyBubbleColor(c) } }
                )
                Divider()
                BubbleColorRow(
                    label = stringResource(R.string.settings_bubble_others),
                    current = appearance.othersBubbleColor,
                    onPick = { c -> scope.launch { AppSettings.setOthersBubbleColor(c) } }
                )
            }

            Spacer(Modifier.height(20.dp))

            // CUSTOM THEME
            SectionHeader(stringResource(R.string.settings_section_custom_theme))
            SectionCard {
                CustomThemeRow(
                    customAccent = appearance.customAccentArgb,
                    onOpenBuilder = { showThemeBuilder = true },
                    onClearCustom = {
                        // Clear ALL custom overrides at once — the user
                        // expects "reset" to fully return to the preset
                        // palette, not just the accent.
                        scope.launch {
                            AppSettings.setCustomAccentArgb(null)
                            AppSettings.setCustomMyBubbleArgb(null)
                            AppSettings.setCustomOthersBubbleArgb(null)
                            AppSettings.setCustomBgArgb(null)
                        }
                    },
                    onShare = {
                        // Build the same JSON as before, base64-url-safe
                        // encode it, and wrap it in a cheipgram://theme
                        // deeplink. Tapping the link in any app routes
                        // back into CheipGram and auto-applies — that's
                        // why we share a link instead of raw JSON now.
                        val json = buildThemeShareJson(appearance)
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
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "CheipGram theme")
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
                    onPaste = {
                        // Pull the first text item off the clipboard and try
                        // to parse our theme JSON. Anything that doesn't
                        // match (wrong version, wrong keys, malformed JSON)
                        // shows the "import failed" toast — we never apply
                        // a partial blob, because that would silently leave
                        // half-old/half-new state.
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        val raw = cm?.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                        val parsed = raw?.let { parseThemeJson(it) }
                        if (parsed != null) {
                            scope.launch { AppSettings.applyAppearance(parsed) }
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
            initialAccent = appearance.customAccentArgb,
            initialMyBubble = appearance.customMyBubbleArgb,
            initialOthersBubble = appearance.customOthersBubbleArgb,
            initialBg = appearance.customBgArgb,
            onDismiss = { showThemeBuilder = false },
            onSaveAccent = { argb -> scope.launch { AppSettings.setCustomAccentArgb(argb) } },
            onSaveMyBubble = { argb -> scope.launch { AppSettings.setCustomMyBubbleArgb(argb) } },
            onSaveOthersBubble = { argb -> scope.launch { AppSettings.setCustomOthersBubbleArgb(argb) } },
            onSaveBg = { argb -> scope.launch { AppSettings.setCustomBgArgb(argb) } }
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
@Composable
private fun CustomThemeRow(
    customAccent: Int?,
    onOpenBuilder: () -> Unit,
    onClearCustom: () -> Unit,
    onShare: () -> Unit,
    onPaste: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (customAccent != null) Color(customAccent)
                        else MaterialTheme.colorScheme.primary
                    )
                    .border(
                        width = if (customAccent == null) 1.dp else 0.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.theme_custom_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (customAccent != null) stringResource(R.string.theme_custom_active)
                    else stringResource(R.string.theme_custom_inactive),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeActionButton(
                label = stringResource(R.string.theme_open_builder),
                onClick = onOpenBuilder,
                modifier = Modifier.weight(1f)
            )
            if (customAccent != null) {
                ThemeActionButton(
                    label = stringResource(R.string.theme_reset),
                    onClick = onClearCustom,
                    modifier = Modifier.weight(1f),
                    outline = true
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeActionButton(
                label = stringResource(R.string.theme_share),
                onClick = onShare,
                modifier = Modifier.weight(1f),
                outline = true
            )
            ThemeActionButton(
                label = stringResource(R.string.theme_paste),
                onClick = onPaste,
                modifier = Modifier.weight(1f),
                outline = true
            )
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
 * Dialog with three sliders (R, G, B) and a live preview swatch.
 *
 * Why RGB sliders instead of an HSV color wheel: a color wheel needs a 2D
 * gesture handler and is hard to make accessible at small sizes; three
 * sliders are 60 lines of Compose, work for everyone, and give the user
 * precise control. Initial slider positions come from initialArgb (or
 * a neutral amber if there is no custom override yet).
 */
@Composable
private fun ThemeBuilderDialog(
    initialAccent: Int?,
    initialMyBubble: Int?,
    initialOthersBubble: Int?,
    initialBg: Int?,
    onDismiss: () -> Unit,
    onSaveAccent: (Int?) -> Unit,
    onSaveMyBubble: (Int?) -> Unit,
    onSaveOthersBubble: (Int?) -> Unit,
    onSaveBg: (Int?) -> Unit
) {
    // Section index → which custom color we're editing. We keep one set of
    // R/G/B state per section so flipping back and forth doesn't lose your
    // tweaks. `remember(section)` would reset everything; an explicit
    // 4-entry array keeps continuity.
    var section by remember { mutableStateOf(0) }

    val initials = listOf(
        initialAccent ?: 0xFFD9A85C.toInt(),       // amber default
        initialMyBubble ?: 0xFF2A4F7A.toInt(),     // dark blue default
        initialOthersBubble ?: 0xFF374151.toInt(), // slate default
        initialBg ?: 0xFF0F1115.toInt()            // near-black default
    )
    val reds = remember {
        mutableStateListOf<Float>().apply {
            initials.forEach { add(android.graphics.Color.red(it).toFloat()) }
        }
    }
    val greens = remember {
        mutableStateListOf<Float>().apply {
            initials.forEach { add(android.graphics.Color.green(it).toFloat()) }
        }
    }
    val blues = remember {
        mutableStateListOf<Float>().apply {
            initials.forEach { add(android.graphics.Color.blue(it).toFloat()) }
        }
    }

    val previewArgb = android.graphics.Color.argb(
        255, reds[section].toInt(), greens[section].toInt(), blues[section].toInt()
    )

    val sectionTitles = listOf(
        stringResource(R.string.theme_section_accent),
        stringResource(R.string.theme_section_my_bubble),
        stringResource(R.string.theme_section_others_bubble),
        stringResource(R.string.theme_section_bg)
    )

    fun saveCurrent() {
        when (section) {
            0 -> onSaveAccent(previewArgb)
            1 -> onSaveMyBubble(previewArgb)
            2 -> onSaveOthersBubble(previewArgb)
            3 -> onSaveBg(previewArgb)
        }
    }

    fun resetCurrent() {
        when (section) {
            0 -> onSaveAccent(null)
            1 -> onSaveMyBubble(null)
            2 -> onSaveOthersBubble(null)
            3 -> onSaveBg(null)
        }
        // Snap the sliders back to the default for that section so the
        // dialog reflects the cleared state.
        val def = initials[section]
        reds[section] = android.graphics.Color.red(def).toFloat()
        greens[section] = android.graphics.Color.green(def).toFloat()
        blues[section] = android.graphics.Color.blue(def).toFloat()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_builder_title)) },
        text = {
            Column {
                // Pill tabs for the four sections. Same visual language as
                // the chat list / new chat tabs so the user immediately
                // recognises it as "pick one".
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp)
                ) {
                    sectionTitles.forEachIndexed { i, title ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (section == i) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .clickable { section = i }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (section == i)
                                    MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (section == i) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                // Preview tile. For the bg section we paint a chat-like
                // arrangement (background + a tiny bubble) so the user
                // can gauge contrast between the two; for the others a
                // simple chip is enough.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(previewArgb)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.theme_preview),
                        color = if (Color(previewArgb).luminance() > 0.5f) Color.Black else Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(14.dp))
                ColorSlider("R", reds[section], Color.Red) { reds[section] = it }
                ColorSlider("G", greens[section], Color.Green) { greens[section] = it }
                ColorSlider("B", blues[section], Color.Blue) { blues[section] = it }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { saveCurrent() }) {
                Text(stringResource(R.string.theme_save))
            }
        },
        dismissButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = { resetCurrent() }) {
                    Text(stringResource(R.string.theme_reset))
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.delete_chat_cancel))
                }
            }
        }
    )
}

@Composable
private fun ColorSlider(label: String, value: Float, tint: Color, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint,
             modifier = Modifier.width(24.dp))
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..255f,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = tint, activeTrackColor = tint
            ),
            modifier = Modifier.weight(1f)
        )
        Text(
            value.toInt().toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp).padding(start = 6.dp)
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
