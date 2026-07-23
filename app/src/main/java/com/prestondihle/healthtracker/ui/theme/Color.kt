package com.prestondihle.healthtracker.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Brand palette. These five are the spec; everything below them is derived.
// ---------------------------------------------------------------------------

/** Primary. */
val BalticBlue = Color(0xFF2F6690)

/** Secondary. */
val OliveBark = Color(0xFF625834)

/** Background. */
val AlabasterGrey = Color(0xFFD9DCD6)

/** Accent, and the darkest brand tone -- used for text on light surfaces. */
val YaleBlue = Color(0xFF16425B)

/** Accent, reserved for errors, over-goal states and threshold lines. */
val Inferno = Color(0xFFA30000)

// ---------------------------------------------------------------------------
// Derived neutrals. Cards sit *above* the alabaster background, so surface is
// lighter than background rather than darker.
// ---------------------------------------------------------------------------

val SurfaceLight = Color(0xFFEFF1EC)
val SurfaceVariantLight = Color(0xFFC8CCC4)
val OutlineLight = Color(0xFFA8ADA2)
val OnSurfaceVariantLight = Color(0xFF4A4F46)

// ---------------------------------------------------------------------------
// Chart series colors. Kept here so the dashboard and trends screens cannot
// drift apart on what "glucose" or "ketones" look like.
// ---------------------------------------------------------------------------

val GlucoseSeries = BalticBlue
val KetoneSeries = OliveBark
val ThresholdLine = Inferno
