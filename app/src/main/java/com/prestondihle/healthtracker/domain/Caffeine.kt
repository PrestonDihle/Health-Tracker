package com.prestondihle.healthtracker.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.ln

/** One caffeine dose: how much, and when it was taken. */
data class CaffeineDose(val time: Instant, val milligrams: Int)

/**
 * Caffeine remaining in the body over time.
 *
 * Elimination is first-order, so each dose decays independently and doses
 * simply add up. That is what makes an afternoon coffee on top of a morning one
 * read so much higher than either alone.
 *
 * A dose is not treated as arriving all at once. Nobody downs a coffee in one
 * instant, and a vertical step in the curve implies a precision the logged time
 * does not have. Each dose is instead spread evenly over the [INTAKE_RAMP]
 * ending at its logged time, which rounds the jump into a short climb.
 */
object Caffeine {

    /** Hours for a dose to fall to half. A common adult average; individuals vary widely. */
    const val HALF_LIFE_HOURS = 5.0

    /**
     * How long a dose is assumed to take to drink, ending at its logged time.
     *
     * The dose is consumed at a steady rate across this span rather than in one
     * gulp, so the curve ramps up over half an hour instead of stepping.
     */
    val INTAKE_RAMP: Duration = Duration.ofMinutes(30)

    /**
     * Doses older than this contribute under 0.1% of their original amount, so
     * they are not worth loading to draw a curve.
     */
    const val RELEVANT_HISTORY_HOURS = 10L * HALF_LIFE_HOURS.toLong()

    /** First-order elimination constant, per hour: ln 2 over the half-life. */
    private val K = ln(2.0) / HALF_LIFE_HOURS

    /** Milligrams still present at [at], summed over every dose taken by then. */
    fun levelAt(doses: List<CaffeineDose>, at: Instant): Float =
        doses.sumOf { doseLevel(it, at) }.toFloat()

    /**
     * One distributed dose's contribution at [at].
     *
     * The dose infuses at a constant rate over `[end - ramp, end]`. Integrating
     * that infusion against first-order decay gives, once fully in,
     * `(rate/k)·(e^(-k·tEnd) − e^(-k·tStart))`; partway through the ramp the
     * upper limit is [at] itself, leaving `(rate/k)·(1 − e^(-k·tStart))`. Both
     * collapse to the plain `dose·e^(-k·t)` bolus as the ramp shrinks to zero.
     */
    private fun doseLevel(dose: CaffeineDose, at: Instant): Double {
        val rampHours = INTAKE_RAMP.toMillis() / 3_600_000.0
        val start = dose.time.minus(INTAKE_RAMP)
        // Nothing has been drunk yet.
        if (!at.isAfter(start)) return 0.0

        val rate = dose.milligrams / rampHours // mg per hour
        val hoursSinceStart = Duration.between(start, at).toMillis() / 3_600_000.0

        return if (at.isBefore(dose.time)) {
            // Still drinking: only the portion consumed so far, each bit decayed.
            (rate / K) * (1 - exp(-K * hoursSinceStart))
        } else {
            val hoursSinceEnd = Duration.between(dose.time, at).toMillis() / 3_600_000.0
            (rate / K) * (exp(-K * hoursSinceEnd) - exp(-K * hoursSinceStart))
        }
    }

    /**
     * The level sampled evenly from [from] to [to].
     *
     * Sampling rather than plotting one point per dose is what makes the line
     * curve: the decay between doses is exponential, and joining dose points
     * directly would draw it as a straight ramp. A dose landing between two
     * samples still counts, because each sample sums the whole history.
     */
    /**
     * Whether one more ordinary dose, taken now, would still be over [limitMg] at
     * [bedtime].
     *
     * This is the "last call" question, and it is deliberately about the *next*
     * dose rather than the current level. Told after the fact that bedtime
     * caffeine is now too high, there is nothing to be done about it -- the
     * useful moment is the one before the cup that does it, which is what this
     * finds. Answering it needs no new model: it is [levelAt] with one dose added
     * at the front.
     *
     * False once the projection is already over, because at that point every
     * remaining choice is equally too late and a warning would only be scolding.
     */
    fun lastCallReached(
        doses: List<CaffeineDose>,
        now: Instant,
        bedtime: Instant,
        limitMg: Int,
        nextDoseMg: Int,
    ): Boolean {
        if (!bedtime.isAfter(now)) return false
        val current = levelAt(doses, bedtime)
        if (current > limitMg) return false
        return levelAt(doses + CaffeineDose(now, nextDoseMg), bedtime) > limitMg
    }

    fun curve(
        doses: List<CaffeineDose>,
        from: Instant,
        to: Instant,
        step: Duration = Duration.ofMinutes(10),
    ): List<Pair<Instant, Float>> {
        if (!from.isBefore(to) || step.isZero || step.isNegative) return emptyList()

        val points = mutableListOf<Pair<Instant, Float>>()
        var cursor = from
        while (cursor.isBefore(to)) {
            points.add(cursor to levelAt(doses, cursor))
            cursor = cursor.plus(step)
        }
        // Always finish exactly on the window end so the line reaches the right
        // edge and the last point is the current level.
        points.add(to to levelAt(doses, to))
        return points
    }
}
