package com.prestondihle.healthtracker.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.pow

/** One caffeine dose: how much, and when it was taken. */
data class CaffeineDose(val time: Instant, val milligrams: Int)

/**
 * Caffeine remaining in the body over time.
 *
 * Elimination is first-order, so each dose decays independently and doses
 * simply add up. That is what makes an afternoon coffee on top of a morning one
 * read so much higher than either alone.
 */
object Caffeine {

    /** Hours for a dose to fall to half. A common adult average; individuals vary widely. */
    const val HALF_LIFE_HOURS = 5.0

    /**
     * Doses older than this contribute under 0.1% of their original amount, so
     * they are not worth loading to draw a curve.
     */
    const val RELEVANT_HISTORY_HOURS = 10L * HALF_LIFE_HOURS.toLong()

    /** Milligrams still present at [at], summed over every dose taken by then. */
    fun levelAt(doses: List<CaffeineDose>, at: Instant): Float =
        doses
            .filter { !it.time.isAfter(at) }
            .sumOf { dose ->
                val hours = Duration.between(dose.time, at).toMillis() / 3_600_000.0
                dose.milligrams * 0.5.pow(hours / HALF_LIFE_HOURS)
            }
            .toFloat()

    /**
     * The level sampled evenly from [from] to [to].
     *
     * Sampling rather than plotting one point per dose is what makes the line
     * curve: the decay between doses is exponential, and joining dose points
     * directly would draw it as a straight ramp. A dose landing between two
     * samples still counts, because each sample sums the whole history.
     */
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
