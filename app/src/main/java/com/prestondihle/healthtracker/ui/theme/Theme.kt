package com.prestondihle.healthtracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Light-only scheme built from the brand palette.
 *
 * Dynamic color is deliberately absent: on Android 12+ it derives the scheme
 * from the user's wallpaper, which would discard the brand colors entirely.
 */
private val BrandColorScheme =
  lightColorScheme(
    primary = BalticBlue,
    onPrimary = Color.White,
    primaryContainer = YaleBlue,
    onPrimaryContainer = Color.White,
    secondary = OliveBark,
    onSecondary = Color.White,
    secondaryContainer = SurfaceVariantLight,
    onSecondaryContainer = OnSurfaceVariantLight,
    tertiary = YaleBlue,
    onTertiary = Color.White,
    background = AlabasterGrey,
    onBackground = YaleBlue,
    surface = SurfaceLight,
    onSurface = YaleBlue,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = SurfaceVariantLight,
    error = Inferno,
    onError = Color.White,
    errorContainer = Inferno,
    onErrorContainer = Color.White,
  )

@Composable
fun HealthTrackerTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = BrandColorScheme, typography = Typography, content = content)
}
