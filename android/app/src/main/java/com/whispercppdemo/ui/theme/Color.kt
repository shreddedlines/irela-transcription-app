package com.whispercppdemo.ui.theme

import androidx.compose.ui.graphics.Color

// Design tokens for the app's colour palette.
//
// The product is dark-only, so a single palette is defined.

val Surface = Color(0xFF11131B)
val SurfaceDim = Color(0xFF11131B)
val SurfaceBright = Color(0xFF373942)
val SurfaceContainerLowest = Color(0xFF0C0E16)
val SurfaceContainerLow = Color(0xFF191B23)
val SurfaceContainer = Color(0xFF1D1F27)
val SurfaceContainerHigh = Color(0xFF282A32)
val SurfaceContainerHighest = Color(0xFF32343D)

val OnSurface = Color(0xFFE1E2ED)
val OnSurfaceVariant = Color(0xFFC3C6D7)
val InverseSurface = Color(0xFFE1E2ED)
val InverseOnSurface = Color(0xFF2E3039)

val Outline = Color(0xFF8D90A0)
val OutlineVariant = Color(0xFF434655)
val SurfaceTint = Color(0xFFB4C5FF)

val Primary = Color(0xFFB4C5FF)
val OnPrimary = Color(0xFF002A78)
val PrimaryContainer = Color(0xFF2563EB)      // "Action Blue"
val OnPrimaryContainer = Color(0xFFEEEFFF)
val InversePrimary = Color(0xFF0053DB)

val Secondary = Color(0xFFB7C8E1)
val OnSecondary = Color(0xFF213145)
val SecondaryContainer = Color(0xFF3A4A5F)
val OnSecondaryContainer = Color(0xFFA9BAD3)

val Tertiary = Color(0xFFFFB596)
val OnTertiary = Color(0xFF581E00)
val TertiaryContainer = Color(0xFFBC4800)
val OnTertiaryContainer = Color(0xFFFFEDE6)

val ErrorColor = Color(0xFFFFB4AB)
val OnErrorColor = Color(0xFF690005)
val ErrorContainer = Color(0xFF93000A)
val OnErrorContainer = Color(0xFFFFDAD6)

val Background = Color(0xFF11131B)
val OnBackground = Color(0xFFE1E2ED)
val SurfaceVariant = Color(0xFF32343D)

/*
 * Concept A (Quiet Instrument) roles that Material 3's ColorScheme does not
 * carry. Values are the approved prototype tokens (docs/prototype/tokens.json);
 * the base palette above already matches them one for one.
 */

/** Stop control and the live recording dot. The app's only saturated red. */
val RecordingRed = Color(0xFFF04438)
val OnRecording = Color(0xFFFFFFFF)
val RecordingRedContainer = Color(0xFF93000A)

/** Muted third text level (captions, metadata). 5.9:1 on the background. */
val TextTertiary = Color(0xFF8D90A0)

/** Failed-row and destructive-button ground. */
val ErrorTint = Color(0xFF2D1517)

/** Warning banners (call active, near the 60-minute limit, offline). */
val Warning = Color(0xFFFFB596)
val WarningContainer = Color(0xFF3B2A20)

/** Periwinkle at 16 / 40 / 55 % for selection, outlines and the eyebrow rule. */
val PrimaryTint = Color(0x29B4C5FF)
val PrimaryOutline = Color(0x66B4C5FF)
val PrimaryMuted = Color(0x8CB4C5FF)

/** The Home audio-to-text mark: arrow stroke and the freshly written line. */
val AccentLine = Color(0xFF4F7DEE)
val AccentFill = Color(0xFF8EA9FA)

/** Snackbar icon on the inverse surface. */
val PrimaryStrong = Color(0xFF2563EB)
