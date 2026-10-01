package com.whispercppdemo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tonal elevation ramp.
 *
 * These are the M3 `surfaceContainer*` roles, which do NOT exist in the
 * ColorScheme of material3 1.1.1 (they arrived in 1.2.0). Rather than bump the
 * Compose dependency mid-project, they are exposed here as plain tokens.
 */
data class SurfaceTokens(
    val containerLowest: Color = SurfaceContainerLowest,
    val containerLow: Color = SurfaceContainerLow,
    val container: Color = SurfaceContainer,
    val containerHigh: Color = SurfaceContainerHigh,
    val containerHighest: Color = SurfaceContainerHighest,
    val dim: Color = SurfaceDim,
    val bright: Color = SurfaceBright
)

val LocalSurfaceTokens = staticCompositionLocalOf { SurfaceTokens() }

/**
 * Concept A roles with no Material slot, so a screen never reaches for a raw
 * hex value.
 */
@Immutable
data class AppColorTokens(
    val textTertiary: Color = TextTertiary,
    val recording: Color = RecordingRed,
    val onRecording: Color = OnRecording,
    val errorTint: Color = ErrorTint,
    val warning: Color = Warning,
    val warningContainer: Color = WarningContainer,
    val primaryTint: Color = PrimaryTint,
    val primaryOutline: Color = PrimaryOutline,
    val primaryMuted: Color = PrimaryMuted,
    val accentLine: Color = AccentLine,
    val accentFill: Color = AccentFill,
    val primaryStrong: Color = PrimaryStrong
)

val LocalAppColors = staticCompositionLocalOf { AppColorTokens() }

/** Spacing scale -- a strict 4dp grid (prototype tokens.json `space`). */
object Spacing {
    val unit: Dp = 4.dp
    val edgeMargin: Dp = 16.dp
    val gutter: Dp = 12.dp
    val stackSm: Dp = 8.dp
    val stackMd: Dp = 16.dp
    val stackLg: Dp = 24.dp
    val section: Dp = 32.dp

    /** Minimum hit area for any interactive element. */
    val touchTarget: Dp = 48.dp

    /** Content is capped at a readable measure on wide screens. */
    val readableMeasure: Dp = 720.dp
}

/** Component sizes from the prototype component tokens. */
object Sizes {
    val buttonHeight: Dp = 56.dp
    val control: Dp = 56.dp
    val recordControl: Dp = 80.dp
    val iconCircle: Dp = 40.dp
    val iconCircleLarge: Dp = 64.dp
    val topBar: Dp = 64.dp
    val navBar: Dp = 76.dp
}

/**
 * Shape scale: inputs and menus 8dp, banners 12dp, cards and buttons 16dp,
 * dialogs and sheets 28dp.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
