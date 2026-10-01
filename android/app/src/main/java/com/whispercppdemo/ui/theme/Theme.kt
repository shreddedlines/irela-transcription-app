package com.whispercppdemo.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// The design uses a single dark palette and the product is dark-only,
// so there is no light scheme. Dynamic color is deliberately NOT used: on
// Android 12+ it would replace this palette with wallpaper-derived colours and
// the design would not survive contact with the user's home screen.
private val AppColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    inversePrimary = InversePrimary,

    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,

    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,

    error = ErrorColor,
    onError = OnErrorColor,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,

    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceTint = SurfaceTint,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,

    outline = Outline,
    outlineVariant = OutlineVariant
)

@Composable
fun WhisperCppDemoTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Match the canvas rather than tinting the status bar with the
            // accent colour, and use light icons -- the previous code asked for
            // dark status-bar icons on a dark background.
            window.statusBarColor = Background.toArgb()
            window.navigationBarColor = Background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    // Bundled Inter with the system (Noto) fallback for other scripts (Type.kt).
    val typography = remember { appTypography(InterFamily) }

    CompositionLocalProvider(
        LocalSurfaceTokens provides SurfaceTokens(),
        LocalAppColors provides AppColorTokens()
    ) {
        MaterialTheme(
            colorScheme = AppColorScheme,
            typography = typography,
            shapes = AppShapes,
            content = content
        )
    }
}
