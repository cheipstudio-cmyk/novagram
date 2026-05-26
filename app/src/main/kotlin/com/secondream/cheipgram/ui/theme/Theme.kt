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
    }
    val accent = when (accentColor) {
        AccentColor.Amber -> AccentPalette.Amber
        AccentColor.Blue -> AccentPalette.Blue
        AccentColor.Green -> AccentPalette.Green
        AccentColor.Violet -> AccentPalette.Violet
    }
    val colorScheme = if (isDark) buildDarkScheme(accent) else buildLightScheme(accent)
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
