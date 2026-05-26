@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.cheipgram.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    var confirmLogout by remember { mutableStateOf(false) }

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
                            val locales = if (tag == "system") {
                                LocaleListCompat.getEmptyLocaleList()
                            } else {
                                LocaleListCompat.forLanguageTags(tag)
                            }
                            AppCompatDelegate.setApplicationLocales(locales)
                            // MainActivity extends ComponentActivity, not
                            // AppCompatActivity. setApplicationLocales stores
                            // the preference but does NOT trigger a recreate on
                            // its own (AppCompat only auto-recreates its own
                            // base classes). Recreate manually so the new
                            // strings.xml takes effect on the visible screen.
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

            // ACCOUNT
            SectionHeader(stringResource(R.string.settings_section_account))
            SectionCard {
                ActionRow(
                    label = stringResource(R.string.action_logout),
                    destructive = true,
                    onClick = { confirmLogout = true }
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

            Spacer(Modifier.height(40.dp))
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.settings_logout_confirm_title)) },
            text = { Text(stringResource(R.string.settings_logout_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    scope.launch { TdClient.logOut() }
                }) {
                    Text(
                        stringResource(R.string.settings_logout_confirm_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) {
                    Text(stringResource(R.string.settings_logout_cancel))
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp, top = 4.dp)
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(16.dp)
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
