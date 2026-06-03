package com.secondream.novagram.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.secondream.novagram.settings.AccentColor
import com.secondream.novagram.settings.BubbleColor
import com.secondream.novagram.settings.ThemeMode

@Composable
fun NovaTheme(
    themeMode: ThemeMode = ThemeMode.Dark,
    accentColor: AccentColor = AccentColor.Amber,
    customAccentArgb: Int? = null,
    customBgArgb: Int? = null,
    /**
     * Global text size multiplier (1.0 = default). Applied by re-emitting
     * every Typography role with its fontSize multiplied; the value comes
     * from the user's slider in Settings.
     */
    textScale: Float = 1.0f,
    /** In-chat message-body text multiplier (1.0 = default), independent of [textScale]. */
    messageScale: Float = 1.0f,
    /** In-chat message-body line-height multiplier (1.0 = default), independent of [messageScale]. */
    messageLineSpacing: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.Amoled -> true
    }
    val baseAccent = when (accentColor) {
        AccentColor.Amber -> AccentPalette.Amber
        AccentColor.Blue -> AccentPalette.Blue
        AccentColor.Green -> AccentPalette.Green
        AccentColor.Violet -> AccentPalette.Violet
        AccentColor.Rose -> AccentPalette.Rose
        AccentColor.Cyan -> AccentPalette.Cyan
        AccentColor.Orange -> AccentPalette.Orange
    }
    // Custom accent overrides the preset primary. We keep `primaryDeep` and
    // `onPrimary` derived: a perceptually-darker shade for primaryDeep, and
    // black/white for onPrimary based on the accent's luminance. This keeps
    // the API surface small (one ARGB int) while still producing readable
    // schemes for any color the user picks.
    val accent = if (customAccentArgb != null) {
        // Tone the picked accent for the active theme so the same colour
        // reads well in every mode. Eugenio's mental model: "if I pick
        // orange, on white I want a slightly darker orange (better
        // contrast); on AMOLED I want a brighter, punchier orange."
        // We map the raw RGB toward black on light themes and toward
        // white on AMOLED — keeping the original on standard dark.
        val raw = Color(customAccentArgb)
        val toned = when {
            themeMode == ThemeMode.Amoled -> {
                // Mix 25% toward white so the colour pops against pure
                // black. coerceIn guards against fp drift at the edges.
                Color(
                    red = (raw.red + (1f - raw.red) * 0.25f).coerceIn(0f, 1f),
                    green = (raw.green + (1f - raw.green) * 0.25f).coerceIn(0f, 1f),
                    blue = (raw.blue + (1f - raw.blue) * 0.25f).coerceIn(0f, 1f),
                    alpha = 1f
                )
            }
            !isDark -> {
                // Multiply 0.82 toward black so the colour reads with
                // enough contrast against a light/white background. Plain
                // orange #FFA500 on white looks washed; 0.82×→ #D18700
                // is rich without going muddy.
                Color(
                    red = (raw.red * 0.82f).coerceIn(0f, 1f),
                    green = (raw.green * 0.82f).coerceIn(0f, 1f),
                    blue = (raw.blue * 0.82f).coerceIn(0f, 1f),
                    alpha = 1f
                )
            }
            else -> raw
        }
        val luminance = toned.luminance()
        Accent(
            primary = toned,
            primaryDeep = Color(
                red = (toned.red * 0.7f).coerceIn(0f, 1f),
                green = (toned.green * 0.7f).coerceIn(0f, 1f),
                blue = (toned.blue * 0.7f).coerceIn(0f, 1f),
                alpha = 1f
            ),
            onPrimary = if (luminance > 0.5f) Color.Black else Color.White
        )
    } else baseAccent
    val baseScheme = when {
        themeMode == ThemeMode.Amoled -> buildAmoledScheme(accent)
        isDark -> buildDarkScheme(accent)
        else -> buildLightScheme(accent)
    }
    // Custom background overrides the chat surface. We recompute onBackground
    // from luminance so text stays readable; surface is left at the theme's
    // default so cards/menus retain their look — only the "behind chat" color
    // changes. This is what most theme-import flows in other clients do.
    val colorScheme = if (customBgArgb != null) {
        val bg = Color(customBgArgb)
        val bgLum = bg.luminance()
        val isLightBg = bgLum > 0.5f
        // PERFECT UNIFORMITY: surface = bg exactly so settings cards,
        // action sheets, the input bar background, and every other
        // surface-tinted component reads as one continuous canvas with
        // the user's chosen background. The user explicitly asked for
        // "uniforme" — any shift makes cards look like patches over
        // the bg, breaking the cohesion of a custom theme.
        //
        // surfaceVariant gets a very subtle nudge (smaller than before)
        // so nested elements that use it (input field bg, chips,
        // dividers) remain discernible from cards without standing
        // out. The shift direction follows bg luminance: lighter bg
        // → darken variant slightly; darker bg → lighten slightly.
        val onBg = if (isLightBg) Color.Black else Color.White
        val variantShift = if (isLightBg) -0.05f else 0.06f
        fun adjust(c: Color, delta: Float): Color {
            val r = (c.red + delta).coerceIn(0f, 1f)
            val g = (c.green + delta).coerceIn(0f, 1f)
            val b = (c.blue + delta).coerceIn(0f, 1f)
            return Color(r, g, b, c.alpha)
        }
        val surfaceVariant = adjust(bg, variantShift)
        baseScheme.copy(
            background = bg,
            onBackground = onBg,
            surface = bg,
            onSurface = onBg,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onBg.copy(alpha = 0.75f)
        )
    } else baseScheme
    // Tracks the *effective* background so the system bars match what's
    // actually painted underneath them, custom or not.
    val effectiveIsLight = colorScheme.background.luminance() > 0.5f
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = effectiveIsLight
            controller.isAppearanceLightNavigationBars = effectiveIsLight
        }
    }
    // Re-emit AppTypography with every TextStyle's fontSize multiplied by
    // textScale. We do this once at theme level so every Text in the app
    // honors the user's preference, no per-component code change needed.
    val scaledTypography = remember(textScale) {
        if (textScale == 1.0f) AppTypography
        else AppTypography.copy(
            displayLarge = AppTypography.displayLarge.copy(fontSize = AppTypography.displayLarge.fontSize * textScale),
            displayMedium = AppTypography.displayMedium.copy(fontSize = AppTypography.displayMedium.fontSize * textScale),
            displaySmall = AppTypography.displaySmall.copy(fontSize = AppTypography.displaySmall.fontSize * textScale),
            headlineLarge = AppTypography.headlineLarge.copy(fontSize = AppTypography.headlineLarge.fontSize * textScale),
            headlineMedium = AppTypography.headlineMedium.copy(fontSize = AppTypography.headlineMedium.fontSize * textScale),
            headlineSmall = AppTypography.headlineSmall.copy(fontSize = AppTypography.headlineSmall.fontSize * textScale),
            titleLarge = AppTypography.titleLarge.copy(fontSize = AppTypography.titleLarge.fontSize * textScale),
            titleMedium = AppTypography.titleMedium.copy(fontSize = AppTypography.titleMedium.fontSize * textScale),
            titleSmall = AppTypography.titleSmall.copy(fontSize = AppTypography.titleSmall.fontSize * textScale),
            bodyLarge = AppTypography.bodyLarge.copy(fontSize = AppTypography.bodyLarge.fontSize * textScale),
            bodyMedium = AppTypography.bodyMedium.copy(fontSize = AppTypography.bodyMedium.fontSize * textScale),
            bodySmall = AppTypography.bodySmall.copy(fontSize = AppTypography.bodySmall.fontSize * textScale),
            labelLarge = AppTypography.labelLarge.copy(fontSize = AppTypography.labelLarge.fontSize * textScale),
            labelMedium = AppTypography.labelMedium.copy(fontSize = AppTypography.labelMedium.fontSize * textScale),
            labelSmall = AppTypography.labelSmall.copy(fontSize = AppTypography.labelSmall.fontSize * textScale)
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = scaledTypography
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalMessageTextScale provides messageScale,
            LocalMessageLineSpacing provides messageLineSpacing,
            content = content
        )
    }
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
 * defines via Ink.BubbleMine / Ink.BubbleTheirs in dark themes, or to the
 * lighter beige equivalents in the light theme, so users who never open
 * Settings keep the readable look in either mode.
 */
@Composable
fun bubbleFillFor(color: BubbleColor, isOutgoing: Boolean, customArgb: Int? = null): BubbleFill {
    // Custom ARGB override beats every preset: derive a readable onBackground
    // from the perceived luminance of the chosen color. This keeps the
    // bubble legible whether the user picked a dark navy or a pastel mint.
    if (customArgb != null) {
        val bg = Color(customArgb)
        return BubbleFill(
            background = bg,
            onBackground = if (bg.luminance() > 0.5f) Color.Black else Color.White
        )
    }
    // Detect light mode by looking at the colorScheme's background luminance.
    // We deliberately don't read isSystemInDarkTheme() because the user could
    // have forced Light/Dark/AMOLED via Settings.
    val cs = MaterialTheme.colorScheme
    val isLight = cs.background.luminance() > 0.5f
    return when (color) {
        BubbleColor.Default -> {
            // Default bubbles now follow the user's chosen accent so picking
            // e.g. Rose tints the chat instead of leaving it generic beige.
            // We blend the accent (cs.primary) at low alpha over the surface
            // for outgoing bubbles, and keep the incoming side neutral so
            // there's still a clear "me vs them" distinction. compositeOver
            // produces a readable tint at any accent without needing per-
            // accent BubbleFill entries.
            val accent = cs.primary
            if (isLight) {
                if (isOutgoing) {
                    BubbleFill(
                        background = accent.copy(alpha = 0.22f)
                            .compositeOver(androidx.compose.ui.graphics.Color.White),
                        onBackground = Ink.LightInk
                    )
                } else {
                    BubbleFill(
                        background = Ink.LightBubbleTheirs,
                        onBackground = Ink.LightInk
                    )
                }
            } else {
                if (isOutgoing) {
                    BubbleFill(
                        background = accent.copy(alpha = 0.30f)
                            .compositeOver(Ink.SurfaceHi),
                        onBackground = cs.onSurface
                    )
                } else {
                    BubbleFill(
                        background = Ink.BubbleTheirs,
                        onBackground = cs.onSurface
                    )
                }
            }
        }
        BubbleColor.Amber  -> BubblePalette.Amber
        BubbleColor.Blue   -> BubblePalette.Blue
        BubbleColor.Green  -> BubblePalette.Green
        BubbleColor.Violet -> BubblePalette.Violet
        BubbleColor.Rose   -> BubblePalette.Rose
    }
}
