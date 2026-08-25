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
// Dark neutrals.
//
// Not the light ones inverted. Cards still sit *above* the background, so on a
// dark surface that means lighter rather than darker -- the same relationship,
// read the other way up. The background is a desaturated blue-black rather than
// a true black, which keeps the brand's cool cast and stops the elevation steps
// between surfaces from banding.
// ---------------------------------------------------------------------------

val BackgroundDark = Color(0xFF12161A)
val SurfaceDarkTone = Color(0xFF1A1F24)
val SurfaceVariantDark = Color(0xFF2A3138)
val OutlineDark = Color(0xFF5A646D)
val OnSurfaceDark = Color(0xFFE3E8EC)
val OnSurfaceVariantDark = Color(0xFFB4BDC5)

val SurfaceContainerLowestDark = Color(0xFF0D1114)
val SurfaceContainerLowDark = Color(0xFF161B20)
val SurfaceContainerDark = Color(0xFF1A1F24)
val SurfaceContainerHighDark = Color(0xFF242A31)
val SurfaceContainerHighestDark = Color(0xFF2E353D)
val SurfaceDimDark = Color(0xFF0D1114)
val SurfaceBrightDark = Color(0xFF343C44)

/** Light enough to carry dark text, for a filled button on a dark surface. */
val BalticLight = Color(0xFF9FC6DF)

/** The tonal fill's dark counterpart: a shade *of* the primary, not a grey. */
val BalticTintDark = Color(0xFF2C4A5E)

// ---------------------------------------------------------------------------
// Chart series colors. Kept here so the dashboard and trends screens cannot
// drift apart on what "glucose" or "ketones" look like.
//
// These are the *light* values. A series colour depends on what it is drawn on,
// so the pair of sets lives in `ChartColors` and the theme picks one; these
// remain the source for the light half of that pair.
// ---------------------------------------------------------------------------

val GlucoseSeries = BalticBlue
val KetoneSeries = OliveBark
val ThresholdLine = Inferno

/**
 * Caffeine decay curve.
 *
 * Yale Blue until this, which is a shade of the same blue the glucose trace is
 * drawn in -- fine on the dashboard, where caffeine has a chart to itself, and
 * not fine on the master graph, where the two are read against each other and
 * cross constantly. Where they crossed there was nothing but the dash pattern
 * left to tell them apart.
 *
 * A deep berry instead. It is the one stretch of the wheel none of the other
 * seven series occupy, so it separates by hue rather than by lightness: the
 * nearest neighbours are the heart-rate brick a long way round one side and the
 * fat curve's purple further round the other. Roast brown would have been the
 * semantic choice for coffee and is exactly the mistake being fixed -- it lands
 * on top of heart rate.
 */
val CaffeineSeries = Color(0xFF9E3A6B)

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

/**
 * Sleep: the hypnogram's trace, and the ground the asleep hours are shaded in.
 *
 * Yale Blue, which is the one brand tone no series on the master graph uses --
 * the fat *stack* takes it on Trends, and that is a different chart. Reusing a
 * brand colour is right here where it was wrong for heart rate: sleep is not
 * competing with the macro curves for a reading, it is the backdrop they are
 * read against, and a shade wants a tone that recedes rather than one picked to
 * stand out.
 *
 * The master graph draws it at [SLEEP_SHADE_ALPHA] rather than in a lighter
 * colour, so the shade is the same blue as the Today card's trace at a weight
 * that cannot be mistaken for a measurement. Anything marking context has to be
 * lighter than the data or it becomes data -- the lesson the meal markers taught.
 */
val SleepSeries = YaleBlue

// ---------------------------------------------------------------------------
// Grip strength. Two lines on one chart that track each other closely, so they
// need the same separation the blood pressure pair does.
// ---------------------------------------------------------------------------

val GripDominantSeries = BalticBlue
val GripNonDominantSeries = OliveBark
