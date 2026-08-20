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

/**
 * Favourable counterpart to [Inferno], for a calorie deficit.
 *
 * The brand palette has no green, and the differential needs one: red alone
 * cannot express a two-directional value. Desaturated and dark to sit with the
 * other four rather than glowing next to them.
 */
val Pine = Color(0xFF356B3F)

// ---------------------------------------------------------------------------
// Derived neutrals. Cards sit *above* the alabaster background, so surface is
// lighter than background rather than darker.
// ---------------------------------------------------------------------------

val SurfaceLight = Color(0xFFEFF1EC)
val SurfaceVariantLight = Color(0xFFC8CCC4)
val OutlineLight = Color(0xFFA8ADA2)
val OnSurfaceVariantLight = Color(0xFF4A4F46)

// Material 3 draws the navigation bar, menus and sheets from the
// surfaceContainer family. Leaving these unset falls back to the baseline
// purple scheme, which is why the nav bar rendered lavender.
val SurfaceContainerLowest = Color(0xFFFFFFFF)
val SurfaceContainerLow = Color(0xFFF5F7F2)
val SurfaceContainer = Color(0xFFEFF1EC)
val SurfaceContainerHigh = Color(0xFFE4E7DF)
val SurfaceContainerHighest = Color(0xFFD9DCD6)
val SurfaceDim = Color(0xFFC9CDC4)
val SurfaceBright = Color(0xFFF7F9F4)

/**
 * Tonal fill for buttons, stepper arrows and the nav bar's selected pill.
 *
 * A tint of the primary rather than a neutral: grey tonal buttons read as
 * disabled next to a filled one.
 */
val BalticTint = Color(0xFFC5D8E6)

// ---------------------------------------------------------------------------
// Chart series colors. Kept here so the dashboard and trends screens cannot
// drift apart on what "glucose" or "ketones" look like.
// ---------------------------------------------------------------------------

val GlucoseSeries = BalticBlue
val KetoneSeries = OliveBark
val ThresholdLine = Inferno

/** Caffeine decay curve. Yale Blue keeps it in-palette and distinct from glucose. */
val CaffeineSeries = YaleBlue

/**
 * Macro stack colors.
 *
 * Ordered so the two blues are never adjacent in the stack -- olive sits
 * between them, which is what keeps the bands readable without inventing a
 * fourth hue.
 */
val ProteinSeries = BalticBlue
val CarbSeries = OliveBark
val FatSeries = YaleBlue

/** Blood pressure. Two lines on one axis, so they need clearly separate hues. */
val SystolicSeries = BalticBlue
val DiastolicSeries = OliveBark

/**
 * The three subjective 1-10 scores, sharing one chart.
 *
 * Colour alone cannot carry three lines that cross constantly at this scale, so
 * each also gets its own stroke pattern; the hues only have to stay
 * distinguishable, not do the work by themselves.
 */
val VibeSeries = BalticBlue
val EnergySeries = OliveBark
val FocusSeries = YaleBlue

// ---------------------------------------------------------------------------
// Master graph. Six series on one plot, so the three macro absorption curves
// have to be told apart from each other *and* from the measured signals they
// are drawn against.
// ---------------------------------------------------------------------------

/**
 * Modelled macro appearance in the bloodstream.
 *
 * These deliberately leave the brand palette. Reusing the macro stack's hues put
 * protein on the same Baltic Blue as glucose and carbs on the same Olive Bark as
 * ketones -- six lines rendering in three colours, with only the dash pattern
 * left to separate a modelled curve from the measured signal it crosses. The
 * three below are instead picked to be distinct from each other *and* from the
 * three measured series, while staying at the palette's muted saturation so the
 * plot does not turn into a highlighter set.
 *
 * They are still a visible family -- all mid-toned, all dashed on the chart --
 * which is the distinction that actually matters: modelled, not measured.
 */
val CarbAbsorptionSeries = Color(0xFFC98A17)
val ProteinAbsorptionSeries = Color(0xFF2E8B84)
val FatAbsorptionSeries = Color(0xFF6F5AA6)

/**
 * Heart rate. Outside the brand palette for the same reason: it is the one
 * master-graph series with no relative in the other charts, and a brand colour
 * would have it read as a macro curve.
 */
val HeartRateSeries = Color(0xFF8C4A3C)

/**
 * Steps per hour, drawn as columns behind everything else.
 *
 * A slate green-grey: it is the only series on the plot that is a block of fill
 * rather than a stroke, so it has to sit back far enough not to compete with six
 * lines drawn over it while still being legible on its own at 45% alpha.
 */
val StepsSeries = Color(0xFF5F7A6B)

// ---------------------------------------------------------------------------
// Grip strength. Two lines on one chart that track each other closely, so they
// need the same separation the blood pressure pair does.
// ---------------------------------------------------------------------------

val GripDominantSeries = BalticBlue
val GripNonDominantSeries = OliveBark
