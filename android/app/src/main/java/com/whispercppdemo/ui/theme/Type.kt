package com.whispercppdemo.ui.theme

import com.whispercppdemo.R
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * Concept A type: Inter for everything, tabular figures wherever numbers tick
 * (timer, durations). Sizes follow the approved prototype tokens:
 * display 32 · headline 24 · title 20 · transcript 18 · body 16 · label 14 · caption 12.
 *
 * FONT FILES ARE BUNDLED, NEVER DOWNLOADED. Inter 4.1 (SIL OFL 1.1, licence in
 * assets/licenses/Inter-OFL.txt) ships in res/font as four static weights; the
 * app uses no downloadable-font provider and fetches nothing at runtime.
 *
 * Fallback: Inter covers Latin, Greek and Cyrillic only. For every glyph it
 * lacks (Devanagari, Bengali, Tamil, Arabic, CJK, emoji, ...) Android's text
 * stack falls through to the system fallback chain — the device's Noto Sans
 * families — exactly as the prototype's 'Inter', 'Noto Sans', system-ui stack
 * does. Hindi therefore renders in Noto Sans Devanagari inside Inter text.
 */

/** Inter, bundled: Regular 400, Medium 500, SemiBold 600, Bold 700. */
val InterFamily: FontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

/** Tabular figures, so a ticking timer or a duration column never shifts. */
const val TABULAR = "tnum"

fun appTypography(family: FontFamily = FontFamily.Default): Typography {
    fun style(size: Int, line: Double, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.em
    )
    val display = style(32, 40.0, FontWeight.SemiBold, -0.02)
    val headline = style(24, 32.0, FontWeight.SemiBold)
    val title = style(20, 28.0, FontWeight.SemiBold)
    val transcript = style(18, 30.0, FontWeight.Normal)
    val body = style(16, 24.0, FontWeight.Normal)
    val bodySmall = style(14, 20.0, FontWeight.Normal)
    val label = style(14, 20.0, FontWeight.Medium)
    val caption = style(12, 16.0, FontWeight.Medium)
    return Typography(
        displayLarge = display,
        displayMedium = display,
        displaySmall = headline,
        headlineLarge = headline,
        headlineMedium = headline,
        headlineSmall = title,
        titleLarge = title,
        titleMedium = body.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = bodySmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = transcript,
        bodyMedium = body,
        bodySmall = bodySmall,
        labelLarge = label,
        labelMedium = caption,
        labelSmall = caption
    )
}

/** Platform-family scale, for JVM tests that cannot load font resources. */
val Typography = appTypography()
