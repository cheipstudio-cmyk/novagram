package com.secondream.novagram.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.secondream.novagram.R

private val gfProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

private fun gFamily(name: String, weight: FontWeight, style: FontStyle = FontStyle.Normal) =
    Font(googleFont = GoogleFont(name), fontProvider = gfProvider, weight = weight, style = style)

/**
 * In-chat message-body text scale, provided at the app root from
 * AppSettings.messageScale. Kept separate from the global [textScale] (which
 * scales the whole typography in the theme): message bubbles read THIS and
 * apply it onto the unscaled base size, so the "interface" and "message" text
 * sliders stay independent instead of multiplying together. Default 1f.
 */
val LocalMessageTextScale = androidx.compose.runtime.compositionLocalOf { 1f }
/** In-chat message-body LINE-HEIGHT multiplier (1.0 = default 22/16 ≈ 1.375em),
 *  controlled by the "Interlinea messaggi" slider. Multiplies the natural body
 *  line height, so it scales correctly together with the message-size slider
 *  and stays font-size independent. */
val LocalMessageLineSpacing = androidx.compose.runtime.compositionLocalOf { 1f }

val InstrumentSerif = FontFamily(
    gFamily("Instrument Serif", FontWeight.Normal),
    gFamily("Instrument Serif", FontWeight.Normal, FontStyle.Italic)
)

val DmSans = FontFamily(
    gFamily("DM Sans", FontWeight.Normal),
    gFamily("DM Sans", FontWeight.Medium),
    gFamily("DM Sans", FontWeight.SemiBold),
    gFamily("DM Sans", FontWeight.Bold)
)

val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = InstrumentSerif, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = InstrumentSerif, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.3).sp),
    displaySmall = TextStyle(fontFamily = InstrumentSerif, fontSize = 28.sp, lineHeight = 32.sp),
    headlineLarge = TextStyle(fontFamily = InstrumentSerif, fontSize = 30.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = InstrumentSerif, fontSize = 24.sp, lineHeight = 28.sp),
    headlineSmall = TextStyle(fontFamily = InstrumentSerif, fontSize = 20.sp, lineHeight = 24.sp),
    titleLarge = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = DmSans, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontFamily = DmSans, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodySmall = TextStyle(fontFamily = DmSans, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
)
