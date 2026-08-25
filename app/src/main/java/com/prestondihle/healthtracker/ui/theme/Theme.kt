package com.prestondihle.healthtracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * Light scheme built from the brand palette.
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
    secondaryContainer = BalticTint,
    onSecondaryContainer = YaleBlue,
    tertiary = YaleBlue,
    onTertiary = Color.White,
    tertiaryContainer = BalticTint,
    onTertiaryContainer = YaleBlue,
    background = AlabasterGrey,
    onBackground = YaleBlue,
    surface = SurfaceLight,
    onSurface = YaleBlue,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    surfaceDim = SurfaceDim,
    surfaceBright = SurfaceBright,
    inverseSurface = YaleBlue,
    inverseOnSurface = AlabasterGrey,
    inversePrimary = BalticTint,
    outline = OutlineLight,
    outlineVariant = SurfaceVariantLight,
    error = Inferno,
    onError = Color.White,
    errorContainer = Inferno,
    onErrorContainer = Color.White,
  )

/**
 * The same brand, on a dark ground.
 *
 * **Not the light scheme inverted.** The brand's own tones are dark ones -- Yale
 * Blue and Olive Bark exist to be read *on* alabaster -- so using them as
 * foreground colours here would put near-black text on near-black surface. The
 * primary lifts to a tint bright enough to carry dark text, and everything that
 * was a dark ink becomes a light one.
 *
 * Dynamic color stays absent for the same reason it is absent in light: the
 * palette is the brand, not the wallpaper.
 */
private val BrandDarkColorScheme =
  darkColorScheme(
    primary = BalticLight,
    // Dark text on the light primary, which is the whole point of lifting it.
    onPrimary = Color(0xFF10222E),
    primaryContainer = BalticTintDark,
    onPrimaryContainer = BalticLight,
    secondary = Color(0xFFC9B87A),
    onSecondary = Color(0xFF231F0E),
    // Baltic, *not* a tint of the olive secondary. Material draws the selected
    // filter chip and the nav bar's selected pill from secondaryContainer, and
    // in the light scheme those are Baltic Tint -- the brand's selection colour
    // is the blue whatever the secondary happens to be. Deriving this from the
    // olive instead turned every selected chip on the phone a muddy brown.
    secondaryContainer = BalticTintDark,
    onSecondaryContainer = BalticLight,
    tertiary = Color(0xFF9FC6DF),
    onTertiary = Color(0xFF10222E),
    tertiaryContainer = BalticTintDark,
    onTertiaryContainer = BalticLight,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDarkTone,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    surfaceDim = SurfaceDimDark,
    surfaceBright = SurfaceBrightDark,
    inverseSurface = AlabasterGrey,
    inverseOnSurface = YaleBlue,
    inversePrimary = BalticBlue,
    outline = OutlineDark,
    // The gridline grey. It has to be visible against the dark surface without
    // competing with the data drawn over it, which is the same balance the light
    // theme strikes from the other direction.
    outlineVariant = Color(0xFF39424A),
    error = Color(0xFFE06C6C),
    onError = Color(0xFF2B0000),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFF2C4C4),
  )

/**
 * Follows the system setting.
 *
 * No in-app override: a per-app theme switch is a setting to maintain and a
 * state to get out of step with the phone, and nobody wants this app light while
 * everything else is dark.
 */
@Composable
fun HealthTrackerTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
    LocalChartColors provides if (darkTheme) DarkChartColors else LightChartColors
  ) {
    MaterialTheme(
      colorScheme = if (darkTheme) BrandDarkColorScheme else BrandColorScheme,
      typography = Typography,
      content = content,
    )
  }
}
