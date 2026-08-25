package com.prestondihle.healthtracker.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Every colour a chart draws a line in, as one set that swaps with the theme.
 *
 * A set rather than the loose top-level constants this started as, because a
 * series colour has to depend on what it is drawn *on*. The light palette is
 * mid-toned so it reads against alabaster; the same values on a near-black
 * surface are muddy, and the darkest of them -- Yale Blue, Olive Bark -- vanish
 * outright. There is no single value that works on both, so there are two sets
 * and the theme decides.
 *
 * Passed explicitly rather than read from a composition local at the point of
 * use, because the thing that needs a colour is often not a composable:
 * `MasterSeries.colorIn` is an extension on an enum, and the chart's draw scope
 * is a `DrawScope`. Handing the set down keeps one source for what a series
 * looks like on the plot, on its switch and in the axis gutter.
 */
data class ChartColors(
    val glucose: Color,
    val ketone: Color,
    val caffeine: Color,
    /** Threshold and reference rules, and anything reading as over-goal. */
    val threshold: Color,
    // The stacked macro totals on Trends, which are a different chart from the
    // absorption curves below and deliberately a different set of hues.
    val proteinStack: Color,
    val carbStack: Color,
    val fatStack: Color,
    val systolic: Color,
    val diastolic: Color,
    val vibe: Color,
    val energy: Color,
    val focus: Color,
    val carbAbsorption: Color,
    val proteinAbsorption: Color,
    val fatAbsorption: Color,
    val heartRate: Color,
    val steps: Color,
    val gripDominant: Color,
    val gripNonDominant: Color,
    /** The hypnogram trace, and the ground the master graph shades asleep hours in. */
    val sleep: Color,
)

/** The palette as it has always been: mid-toned, for the alabaster background. */
val LightChartColors =
    ChartColors(
        glucose = GlucoseSeries,
        ketone = KetoneSeries,
        caffeine = CaffeineSeries,
        threshold = ThresholdLine,
        proteinStack = ProteinSeries,
        carbStack = CarbSeries,
        fatStack = FatSeries,
        systolic = SystolicSeries,
        diastolic = DiastolicSeries,
        vibe = VibeSeries,
        energy = EnergySeries,
        focus = FocusSeries,
        carbAbsorption = CarbAbsorptionSeries,
        proteinAbsorption = ProteinAbsorptionSeries,
        fatAbsorption = FatAbsorptionSeries,
        heartRate = HeartRateSeries,
        steps = StepsSeries,
        gripDominant = GripDominantSeries,
        gripNonDominant = GripNonDominantSeries,
        sleep = SleepSeries,
    )

/**
 * The same palette lifted for a dark surface.
 *
 * Each colour keeps its hue and gains lightness, so a line is recognisably the
 * same line between themes -- glucose stays steel blue, caffeine stays berry.
 * What changes is only what has to: a 2dp stroke of `#16425B` on `#12161A` is a
 * line nobody can see.
 *
 * The separations the light set was chosen for are preserved, since they are the
 * whole reason those hues were picked. Caffeine still sits in the stretch of the
 * wheel nothing else occupies, and is still a long way round from the heart-rate
 * brick. The two blues of the macro stack are still kept apart by the olive
 * between them.
 */
val DarkChartColors =
    ChartColors(
        glucose = Color(0xFF6FA8D0),
        ketone = Color(0xFFB9A96A),
        caffeine = Color(0xFFD4739F),
        // Lifted well clear of Inferno: a rule has to read as a rule against a
        // dark ground, and #A30000 there is nearly black.
        threshold = Color(0xFFE06C6C),
        proteinStack = Color(0xFF6FA8D0),
        carbStack = Color(0xFFB9A96A),
        fatStack = Color(0xFF4E86AB),
        systolic = Color(0xFF6FA8D0),
        diastolic = Color(0xFFB9A96A),
        vibe = Color(0xFF6FA8D0),
        energy = Color(0xFFB9A96A),
        focus = Color(0xFF4E86AB),
        carbAbsorption = Color(0xFFE8B54A),
        proteinAbsorption = Color(0xFF5FC0B8),
        fatAbsorption = Color(0xFFA292D8),
        heartRate = Color(0xFFD08A78),
        steps = Color(0xFF8FB3A0),
        gripDominant = Color(0xFF6FA8D0),
        gripNonDominant = Color(0xFFB9A96A),
        // Yale Blue is one of the two tones that vanish outright on a near-black
        // ground, and this one has to work twice over: as a 2dp trace and as a
        // wash at a sixth of its opacity. Lifted far enough that the wash still
        // reads as blue rather than as a slightly paler patch of background.
        sleep = Color(0xFF7FA9C9),
    )

/**
 * The palette in force, for the few places that cannot be handed one.
 *
 * `static` because it changes only when the whole theme does, and a chart redraw
 * on every read of it would be a waste.
 */
val LocalChartColors = staticCompositionLocalOf { LightChartColors }
