package com.example.videomaker.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = SkyPrimaryLight,
    onPrimary = SkyOnPrimaryLight,
    primaryContainer = SkyPrimaryContainerLight,
    onPrimaryContainer = SkyOnPrimaryContainerLight,
    secondary = IrisSecondaryLight,
    onSecondary = IrisOnSecondaryLight,
    secondaryContainer = IrisSecondaryContainerLight,
    onSecondaryContainer = Color(0xFF221552),
    tertiary = AquaTertiaryLight,
    tertiaryContainer = AquaTertiaryContainerLight,
    onTertiaryContainer = Color(0xFF003B35),
    background = BackgroundLight,
    onBackground = Color(0xFF151B2E),
    surface = SurfaceLight,
    onSurface = Color(0xFF171D30),
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = Color(0xFF474D61),
    outline = OutlineLight,
    error = ErrorLight,
    errorContainer = ErrorContainerLight
)

private val DarkColors = darkColorScheme(
    primary = SkyPrimaryDark,
    onPrimary = SkyOnPrimaryDark,
    primaryContainer = SkyPrimaryContainerDark,
    onPrimaryContainer = SkyOnPrimaryContainerDark,
    secondary = IrisSecondaryDark,
    onSecondary = IrisOnSecondaryDark,
    secondaryContainer = IrisSecondaryContainerDark,
    onSecondaryContainer = Color(0xFFE9DDFF),
    tertiary = AquaTertiaryDark,
    tertiaryContainer = AquaTertiaryContainerDark,
    onTertiaryContainer = Color(0xFFB8F2E9),
    background = BackgroundDark,
    onBackground = Color(0xFFE2E7FF),
    surface = SurfaceDark,
    onSurface = Color(0xFFE5E9F8),
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Color(0xFFC5C9D8),
    outline = OutlineDark,
    error = ErrorDark,
    errorContainer = ErrorContainerDark
)

@Composable
fun VideoMakerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = colorScheme.background.copy(alpha = if (darkTheme) 0.78f else 0.92f).toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VideoMakerTypography,
        shapes = VideoMakerShapes,
        content = content
    )
}
