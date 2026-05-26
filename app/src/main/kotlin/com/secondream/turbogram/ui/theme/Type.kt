package com.secondream.turbogram.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.secondream.turbogram.R

private val gfProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

private fun gFamily(name: String, weight: FontWeight, style: FontStyle = FontStyle.Normal) =
    Font(googleFont = GoogleFont(name), fontProvider = gfProvider, weight = weight, style = style)

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
