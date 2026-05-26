package com.secondream.cheipgram.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Editorial Dark palette. The `Amber` accent is the historical default and
 * stays as a top-level alias for backwards compatibility with the rest of
 * the UI code, which references Ink.Amber / Ink.OnAmber directly.
 *
 * The accent here is wired up dynamically: see Theme.kt and the
 * AccentPalette object below.
 */
object Ink {
    // Surfaces & ink (dark scheme)
    val Bg = Color(0xFF0E0F12)
    val Surface = Color(0xFF16181C)
    val SurfaceHi = Color(0xFF1E2128)
    val SurfaceLine = Color(0xFF252830)
    val Cream = Color(0xFFECE7DE)
    val Muted = Color(0xFF9A968D)
    val Faint = Color(0xFF5F5C56)

    // Default accent (amber). The active accent is recomputed in Theme.kt
    // based on the user's setting, but these stay as the literal defaults
    // so code that references Ink.Amber directly still compiles.
    val Amber = Color(0xFFD9A85C)
    val AmberDeep = Color(0xFFB78838)
    val OnAmber = Color(0xFF1A1611)

    // Message bubble fills
    val BubbleMine = Color(0xFF2A2620)
    val BubbleTheirs = Color(0xFF1A1D22)

    val Error = Color(0xFFD96459)
    val Online = Color(0xFF7FB069)

    // Light scheme equivalents. Surfaces are warm off-whites rather than
    // pure white so the accent colors still pop.
    val LightBg = Color(0xFFFBF8F2)
    val LightSurface = Color(0xFFF3EFE6)
    val LightSurfaceHi = Color(0xFFE9E3D6)
    val LightSurfaceLine = Color(0xFFD9D2C2)
    val LightInk = Color(0xFF1A1611)
    val LightMuted = Color(0xFF5F5C56)
    val LightFaint = Color(0xFF8A8780)
    val LightBubbleMine = Color(0xFFE5DDC9)
    val LightBubbleTheirs = Color(0xFFF0EBDD)
}

/**
 * Four accent presets exposed in Settings. Each one is a triplet of
 * (primary, primaryDeep, onPrimary), matching the structure of the
 * default Amber accent above.
 */
data class Accent(
    val primary: Color,
    val primaryDeep: Color,
    val onPrimary: Color
)

object AccentPalette {
    val Amber = Accent(
        primary = Color(0xFFD9A85C),
        primaryDeep = Color(0xFFB78838),
        onPrimary = Color(0xFF1A1611)
    )
    val Blue = Accent(
        primary = Color(0xFF6FA8DC),
        primaryDeep = Color(0xFF4881B8),
        onPrimary = Color(0xFF0D1620)
    )
    val Green = Accent(
        primary = Color(0xFF7FB069),
        primaryDeep = Color(0xFF5E8C4C),
        onPrimary = Color(0xFF0F1A0D)
    )
    val Violet = Accent(
        primary = Color(0xFFB29CD9),
        primaryDeep = Color(0xFF8973B8),
        onPrimary = Color(0xFF14101F)
    )
}

/**
 * Concrete fill applied to a chat bubble. We carry both background and the
 * preferred text color so the user can pick contrasting tints without us
 * recomputing the right `onColor` at every call site.
 */
data class BubbleFill(val background: Color, val onBackground: Color)

object BubblePalette {
    val Amber  = BubbleFill(Color(0xFF3D2E18), Color(0xFFF5E7D0))
    val Blue   = BubbleFill(Color(0xFF1B3142), Color(0xFFDCE7EF))
    val Green  = BubbleFill(Color(0xFF1F3324), Color(0xFFDDE9DE))
    val Violet = BubbleFill(Color(0xFF291F3D), Color(0xFFE2DCE9))
    val Rose   = BubbleFill(Color(0xFF3A1F25), Color(0xFFE9DCDE))
}
