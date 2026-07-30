package com.prestondihle.healthtracker.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.exp

/** One eaten macro's grams, and when the meal was eaten. */
data class MacroServing(
    val time: Instant,
    val proteinGrams: Float,
    val carbGrams: Float,
    val fatGrams: Float,
)

/** Which nutrient a curve describes. */
enum class Macro {
    PROTEIN,
    CARB,
    FAT,
}

/**
 * How fast each macro reaches the bloodstream after it is eaten.
 *
 * ## Why a model at all
 *
 * Health Connect records a meal as a lump of grams at one timestamp. Drawn as
 * that, a meal is a vertical spike, which says nothing about the hours over
 * which it is actually acting -- and the whole point of putting food on the same
 * axis as glucose, ketones and heart rate is to see the lag between eating and
 * the body's response. So each meal is spread into an appearance curve and the
 * chart plots the *rate* at which each macro is entering circulation.
 *
 * ## The shape
 *
 * The published models of meal nutrient appearance are compartmental: the
 * stomach empties into the gut, the gut absorbs into plasma. Dalla Man's oral
 * glucose model (the basis of the FDA-accepted UVa/Padova simulator) uses two
 * stomach compartments feeding one gut compartment; Hovorka's two-compartment
 * glucose appearance model and Worthington's earlier two-compartment gut model
 * do the same thing with fewer parameters. All of them produce the same family
 * of curve: zero at the moment of eating, a rounded rise, a single peak, and an
 * exponential tail.
 *
 * A chain of `n` serial first-order transfers sharing a rate constant gives
 * exactly that shape in closed form, which is what [appearanceRate] evaluates:
 *
 * ```
 * rate(t) = grams · kⁿ · t^(n−1) · e^(−k·t) / (n−1)!,   k = (n−1) / timeToPeak
 * ```
 *
 * The integral over all time is the grams eaten, so the area under a curve is
 * the meal, and the peak falls at `t = (n−1)/k`. Two parameters per macro then
 * come from the literature -- how long to the peak, and how many transfers -- plus
 * a lag for food sitting in the stomach before any of it has been absorbed.
 *
 * The compartment count is what sets the tail. It matters: a two-compartment fat
 * curve peaking at 3.5 h is still running at a third of its peak ten hours later,
 * which is nothing like the measured return to baseline. More serial steps make
 * the curve tighter around its peak, and fat genuinely has more of them.
 *
 * ## The parameters
 *
 * **Carbohydrate** -- 2 compartments, 15 min lag, peak at 45 min. Plasma glucose
 * does not measurably rise before the 30-minute mark after a mixed meal, and most
 * of the meal-related change over the first 30-60 minutes is the appearance rate
 * rather than disposal; gastric emptying alone accounts for about 35% of the
 * variance in the glycaemic response. This puts the curve essentially finished
 * inside three hours.
 *
 * **Protein** -- 2 compartments, 20 min lag, peak at 90 min. Amino acids from a
 * fast protein appear in plasma within 15 minutes and peak at 45-60; a mixed meal
 * is much slower, with reported times to peak of 90-100 minutes for beef and
 * mycoprotein, ~118 for plant concentrates, and up to 180 minutes when protein is
 * eaten as part of a full meal. Food logged here is meals rather than shakes, so
 * 90 minutes sits in the middle of that range.
 *
 * **Fat** -- 4 compartments, 30 min lag, peak at 3.5 h. Chylomicron triglyceride
 * rises after a lag, peaks at 3-4 hours and returns towards baseline by 6-8. The
 * extra compartments are not a curve-fitting fudge: dietary fat reaches plasma
 * through more sequential steps than a sugar does -- lumen, enterocyte,
 * chylomicron assembly, lymph, thoracic duct -- and four transfers put 95% of the
 * meal absorbed by about 8 hours, where the measurements are. (The small bump
 * some studies see at 10-30 minutes is lipid stored from an *earlier* meal being
 * released, not the meal just eaten, so it is not modelled here.)
 *
 * These are population averages for mixed meals. Individual gastric emptying
 * varies severalfold, and the curve is a plausible expectation rather than a
 * measurement -- which is why the chart draws these dashed.
 *
 * Sources:
 * - Dalla Man et al., *A System Model of Oral Glucose Absorption* (IEEE TBME 2006)
 * - Marathe et al., *Relationships Between Gastric Emptying, Postprandial
 *   Glycemia, and Incretin Hormones*, Diabetes Care 36:1396 (2013)
 * - Trico et al., *Effects of delayed gastric emptying on postprandial glucose
 *   kinetics*, Am J Physiol Endocrinol Metab (2014)
 * - *Postprandial Aminoacidemia Following the Ingestion of Alternative and
 *   Sustainable Proteins*, Nutrients (2025)
 * - Nakajima et al. / Chait et al. on postprandial metabolism of meal
 *   triglyceride and the 3-4 h chylomicron peak
 */
object MacroAbsorption {

    /**
     * One macro's absorption parameters.
     *
     * @param lag food sitting in the stomach before any of it has reached the blood
     * @param timeToPeak from eating to the highest appearance rate
     * @param compartments serial first-order transfers; more makes a tighter tail
     */
    data class Kinetics(
        val lag: Duration,
        val timeToPeak: Duration,
        val compartments: Int,
    )

    private val PROTEIN =
        Kinetics(lag = Duration.ofMinutes(20), timeToPeak = Duration.ofMinutes(90), compartments = 2)
    private val CARB =
        Kinetics(lag = Duration.ofMinutes(15), timeToPeak = Duration.ofMinutes(45), compartments = 2)
    private val FAT =
        Kinetics(lag = Duration.ofMinutes(30), timeToPeak = Duration.ofMinutes(210), compartments = 4)

    fun kinetics(macro: Macro): Kinetics =
        when (macro) {
            Macro.PROTEIN -> PROTEIN
            Macro.CARB -> CARB
            Macro.FAT -> FAT
        }

    /**
     * How far back a meal still contributes to the curve.
     *
     * Set by fat, the slowest of the three: at ten hours a fat curve is under 5%
     * of its own peak, so meals older than this cannot visibly change the plot
     * but would still have to be loaded to draw it.
     */
    val RELEVANT_HISTORY_HOURS: Long = 10

    private fun MacroServing.grams(macro: Macro): Float =
        when (macro) {
            Macro.PROTEIN -> proteinGrams
            Macro.CARB -> carbGrams
            Macro.FAT -> fatGrams
        }

    /**
     * Grams per hour of [macro] entering circulation at [at], summed over meals.
     *
     * Curves add because each is the appearance from an independent bolus; two
     * meals two hours apart genuinely do overlap in the blood, and that overlap
     * is most of what makes the chart worth reading.
     */
    fun rateAt(servings: List<MacroServing>, macro: Macro, at: Instant): Float {
        val kinetics = kinetics(macro)
        return servings
            .sumOf { appearanceRate(it.grams(macro), it.time, kinetics, at).toDouble() }
            .toFloat()
    }

    /**
     * One meal's contribution, in grams per hour.
     *
     * `kⁿ·t^(n−1)·e^(−k·t)/(n−1)!` is the impulse response of `n` first-order
     * transfers in series sharing a rate constant -- stomach to gut, gut to blood,
     * and for fat the lymphatic steps beyond. It is normalised so that integrating
     * it over all time returns [grams], which is what makes the area under the
     * plotted curve the size of the meal.
     */
    private fun appearanceRate(
        grams: Float,
        eatenAt: Instant,
        kinetics: Kinetics,
        at: Instant,
    ): Float {
        if (grams <= 0f) return 0f
        val start = eatenAt.plus(kinetics.lag)
        // Still in the stomach: nothing has been absorbed yet.
        if (!at.isAfter(start)) return 0f

        val k = kinetics.rateConstant ?: return 0f
        val hours = Duration.between(start, at).toMillis() / 3_600_000.0
        val n = kinetics.compartments

        // kⁿ·t^(n−1)/(n−1)! built up term by term, so nothing large is formed and
        // then divided back down.
        var coefficient = k
        for (i in 1 until n) {
            coefficient *= k * hours / i
        }
        return (grams * coefficient * exp(-k * hours)).toFloat()
    }

    /**
     * The shared transfer rate, in per-hour, or null if the peak is not after the lag.
     *
     * Comes straight from inverting the peak of the impulse response,
     * `t_peak = (n−1)/k`.
     */
    private val Kinetics.rateConstant: Double?
        get() {
            val peakHours = timeToPeak.minus(lag).toMillis() / 3_600_000.0
            if (peakHours <= 0.0 || compartments < 2) return null
            return (compartments - 1) / peakHours
        }

    /**
     * The appearance rate sampled evenly from [from] to [to].
     *
     * Sampled rather than drawn per meal for the same reason the caffeine curve
     * is: the shape between two meals is a curve, not the straight line joining
     * them, and each sample sums every meal's contribution so a meal falling
     * between samples still counts.
     */
    fun curve(
        servings: List<MacroServing>,
        macro: Macro,
        from: Instant,
        to: Instant,
        step: Duration = Duration.ofMinutes(10),
    ): List<Pair<Instant, Float>> {
        if (!from.isBefore(to) || step.isZero || step.isNegative) return emptyList()

        val points = mutableListOf<Pair<Instant, Float>>()
        var cursor = from
        while (cursor.isBefore(to)) {
            points.add(cursor to rateAt(servings, macro, cursor))
            cursor = cursor.plus(step)
        }
        points.add(to to rateAt(servings, macro, to))
        return points
    }

    /**
     * The fraction of a serving of [macro] absorbed by [at], from 0 to 1.
     *
     * The definite integral of [appearanceRate], which for a chain of `n`
     * transfers is `1 − e^(−k·t)·Σ(k·t)ⁱ/i!` over `i = 0..n−1`. Exposed so the
     * screen can say how much of a meal has actually landed rather than only how
     * fast it is landing.
     */
    fun absorbedFraction(macro: Macro, eatenAt: Instant, at: Instant): Float {
        val kinetics = kinetics(macro)
        val start = eatenAt.plus(kinetics.lag)
        if (!at.isAfter(start)) return 0f

        val k = kinetics.rateConstant ?: return 1f
        val hours = Duration.between(start, at).toMillis() / 3_600_000.0
        val kt = k * hours

        var term = 1.0
        var sum = 1.0
        for (i in 1 until kinetics.compartments) {
            term *= kt / i
            sum += term
        }

        return (1.0 - exp(-kt) * sum).toFloat().coerceIn(0f, 1f)
    }
}
