package com.secondream.cheipgram.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.secondream.cheipgram.settings.AccentColor
import com.secondream.cheipgram.settings.BubbleColor
import com.secondream.cheipgram.settings.ThemeMode

@Composable
fun CheipGramTheme(
    themeMode: ThemeMode = ThemeMode.Dark,
    accentColor: AccentColor = AccentColor.Amber,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.Amoled -> true
    }
    val accent = when (accentColor) {
        AccentColor.Amber -> AccentPalette.Amber
        AccentColor.Blue -> AccentPalette.Blue
        AccentColor.Green -> AccentPalette.Green
        AccentColor.Violet -> AccentPalette.Violet
    }
    val colorScheme = when {
        themeMode == ThemeMode.Amoled -> buildAmoledScheme(accent)
        isDark -> buildDarkScheme(accent)
        else -> buildLightScheme(accent)
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}

private fun buildDarkScheme(accent: Accent): ColorScheme = darkColorScheme(
    primary = accent.primary,
    onPrimary = accent.onPrimary,
    primaryContainer = accent.primaryDeep,
    onPrimaryContainer = accent.onPrimary,
    secondary = Ink.Cream,
    onSecondary = Ink.Bg,
    background = Ink.Bg,
    onBackground = Ink.Cream,
    surface = Ink.Surface,
    onSurface = Ink.Cream,
    surfaceVariant = Ink.SurfaceHi,
    onSurfaceVariant = Ink.Muted,
    outline = Ink.SurfaceLine,
    outlineVariant = Ink.SurfaceLine,
    error = Ink.Error,
    onError = Ink.Cream,
)

/**
 * True-black AMOLED scheme. Background and surfaces stay at pure black so
 * pixels are switched off on OLED panels; surfaces are differentiated only
 * by their borders/outlines and tiny brightness lifts on hover.
 */
private fun buildAmoledScheme(accent: Accent): ColorScheme = darkColorScheme(
    primary = accent.primary,
    onPrimary = accent.onPrimary,
    primaryContainer = accent.primaryDeep,
    onPrimaryContainer = accent.onPrimary,
    secondary = Ink.Cream,
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Ink.Cream,
    surface = Color.Black,
    onSurface = Ink.Cream,
    surfaceVariant = Color(0xFF0A0A0A),
    onSurfaceVariant = Ink.Muted,
    outline = Color(0xFF1F1F1F),
    outlineVariant = Color(0xFF161616),
    error = Ink.Error,
    onError = Ink.Cream,
)

private fun buildLightScheme(accent: Accent): ColorScheme = lightColorScheme(
    primary = accent.primary,
    onPrimary = accent.onPrimary,
    primaryContainer = accent.primaryDeep,
    onPrimaryContainer = Color.White,
    secondary = Ink.LightInk,
    onSecondary = Ink.LightBg,
    background = Ink.LightBg,
    onBackground = Ink.LightInk,
    surface = Ink.LightSurface,
    onSurface = Ink.LightInk,
    surfaceVariant = Ink.LightSurfaceHi,
    onSurfaceVariant = Ink.LightMuted,
    outline = Ink.LightSurfaceLine,
    outlineVariant = Ink.LightSurfaceLine,
    error = Ink.Error,
    onError = Color.White,
)

/**
 * Resolve the persisted BubbleColor preference into a concrete (background,
 * onBackground) pair. `Default` falls back to whatever the active theme
 * defines via Ink.BubbleMine / Ink.BubbleTheirs, so users who never open
 * Settings keep the previous look.
 */
@Composable
fun bubbleFillFor(color: BubbleColor, isOutgoing: Boolean): BubbleFill {
    return when (color) {
        BubbleColor.Default -> BubbleFill(
            background = if (isOutgoing) Ink.BubbleMine else Ink.BubbleTheirs,
            onBackground = MaterialTheme.colorScheme.onSurface
        )
        BubbleColor.Amber  -> BubblePalette.Amber
        BubbleColor.Blue   -> BubblePalette.Blue
        BubbleColor.Green  -> BubblePalette.Green
        BubbleColor.Violet -> BubblePalette.Violet
        BubbleColor.Rose   -> BubblePalette.Rose
    }
}
