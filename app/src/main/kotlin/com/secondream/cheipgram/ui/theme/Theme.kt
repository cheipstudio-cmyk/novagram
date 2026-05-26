package com.secondream.cheipgram.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val InkDark = darkColorScheme(
    primary = Ink.Amber,
    onPrimary = Ink.OnAmber,
    primaryContainer = Ink.AmberDeep,
    onPrimaryContainer = Ink.OnAmber,
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

@Composable
fun CheipGramTheme(content: @Composable () -> Unit) {
    val colorScheme = InkDark
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
