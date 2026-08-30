package com.prestondihle.healthtracker.domain

import java.time.LocalDate

/** One plotted pair, with the day (or the week's first day) it came from. */
data class ScatterPoint(val date: LocalDate, val x: Float, val y: Float)

/**
 * A least-squares line through a scatter, and how well it fits.
 *
 * [rSquared] is carried alongside rather than folded into the decision to return
 * a fit at all, because the two questions are different: whether a line can be
 * computed, and whether it should be believed. The line is worth drawing on a
 * loose cloud -- it is what makes the looseness visible -- while the number read
 * off it is not.
 */
data class LinearFit(
    val slope: Float,
    /** Where the line crosses x = 0. */
    val intercept: Float,
    val rSquared: Float,
    val n: Int,
) {
    /**
     * Where the line crosses y = 0, or null on a slope too flat to cross
     * anywhere meaningful.
     *
     * This is the figure the whole card exists for when y is weight change: the
     * x value at which the weight holds.
     */
    val xIntercept: Float?
        get() = if (slope == 0f || !slope.isFinite()) null else -intercept / slope
}

/**
 * Fitting weight change against what might explain it.
 *
 * **The x-intercept is the point.** With grams lost per day on y and calories
 * eaten on x, the line crosses zero at the intake where the weight holds --
 * which is that person's maintenance, measured from their own data rather than
 * predicted from a population equation. Against *net* calories it answers a
 * different and equally useful question: a watch whose burn estimate were
 * perfect would put that crossing at zero, so wherever it actually lands is how
 * far the watch is out.
 *
 * **It is maintenance, not basal metabolic rate, and the card says so.** BMR is
 * what a body burns at complete rest; this includes every step walked and every
 * session trained during the days that were measured. The two are different
 * numbers -- typically by hundreds of calories -- and quoting one under the
 * other's name would be the most confidently wrong figure in the app.
 *
 * Most of this file is refusals, for `GoalProjection`'s reason: a maintenance
 * figure is a specific, actionable-looking number fitted to self-reported food
 * logging and a bathroom scale, and **a wrong one does not look wrong -- it
 * looks like a plan.**
 */
object EnergyBalance {

    /**
     * Energy in a kilogram of body fat.
     *
     * 7,700 kcal, the figure behind the familiar 3,500-per-pound rule. It is a
     * round approximation and is used here only to *sanity-check* a fitted
     * slope, never to compute one: the whole point of fitting a line is to
     * measure this reader's own exchange rate rather than to assume the
     * textbook's.
     */
    const val KCAL_PER_KG = 7700f

    /**
     * Fewest points that may be fitted.
     *
     * Five, matching `GoalProjection.MIN_READINGS`. Two points always fit a line
     * perfectly and tell you nothing; four weeks of data through a quantity this
     * noisy will produce a confident slope out of almost any accident.
     */
    const val MIN_POINTS = 5

    /**
     * How well the line has to fit before its crossing is quoted.
     *
     * A third of the variance explained. Low as correlations go, and deliberately
     * so -- daily energy balance against a bathroom scale is a noisy measurement
     * of a real relationship, and demanding a tight fit would refuse every honest
     * dataset. Below this the line is still drawn, because seeing the scatter is
     * how a reader learns not to trust it; only the *number* is withheld.
     */
    const val MIN_R_SQUARED = 0.33f

    /**
     * The range a maintenance figure has to land in to be printed.
     *
     * Outside 1,000 to 6,000 kcal the fit has found something other than energy
     * balance -- a fortnight of illness, a scale that changed, a month of
     * untracked weekends. The arithmetic is still valid and the answer is still
     * wrong, which is exactly the case a bound catches and a correlation
     * threshold does not.
     */
    val PLAUSIBLE_MAINTENANCE = 1_000f..6_000f

    /**
     * Least squares through the points, or null where a line means nothing.
     *
     * Refuses on too few points and on **zero variance in x** -- a column of
     * points at one intake has no slope, and the arithmetic divides by zero to
     * get there rather than saying so.
     */
    fun fit(points: List<ScatterPoint>): LinearFit? {
        if (points.size < MIN_POINTS) return null
        val n = points.size
        val meanX = points.map { it.x }.average().toFloat()
        val meanY = points.map { it.y }.average().toFloat()

        var sxy = 0f
        var sxx = 0f
        var syy = 0f
        points.forEach {
            val dx = it.x - meanX
            val dy = it.y - meanY
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        if (sxx <= 0f) return null

        val slope = sxy / sxx
        val intercept = meanY - slope * meanX
        // A y with no spread at all is a horizontal line the points sit exactly
        // on. Reported as a perfect fit rather than as 0/0, which is what the
        // ratio would otherwise be -- and it is perfect, it just explains
        // nothing, which the slope of zero already says.
        val rSquared = if (syy <= 0f) 1f else (sxy * sxy) / (sxx * syy)

        return LinearFit(
            slope = slope,
            intercept = intercept,
            rSquared = rSquared.coerceIn(0f, 1f),
            n = n,
        )
    }

    /**
     * The intake at which the weight holds, or null when it should not be quoted.
     *
     * Four refusals, and each one catches a different way of being confidently
     * wrong:
     *
     * - **No fit**, from too few points or a column of them at one intake.
     * - **A slope pointing the wrong way.** More food has to mean less weight
     *   lost. A positive slope says this reader lost more the more they ate,
     *   which is not a discovery about their metabolism -- it is a window in
     *   which something else moved, and its crossing would be a maintenance
     *   figure below everything they actually ate.
     * - **A fit too loose to read a number off.** The line still gets drawn; the
     *   figure does not.
     * - **A crossing outside [PLAUSIBLE_MAINTENANCE].** Arithmetically fine, and
     *   about something other than energy balance.
     *
     * [caloriesOnX] is what makes the second refusal safe to apply: it is only
     * true that more food means less loss when x is *food*. Paired with a heart
     * rate or a glucose average the sign carries no such promise, and this
     * returns null for those outright -- a crossing has a meaning worth printing
     * only where x is an intake.
     */
    fun maintenanceCalories(fit: LinearFit?, caloriesOnX: Boolean): Int? {
        if (!caloriesOnX) return null
        val line = fit ?: return null
        if (line.slope >= 0f) return null
        if (line.rSquared < MIN_R_SQUARED) return null
        val crossing = line.xIntercept ?: return null
        if (!crossing.isFinite() || crossing !in PLAUSIBLE_MAINTENANCE) return null
        return crossing.toInt()
    }
}
