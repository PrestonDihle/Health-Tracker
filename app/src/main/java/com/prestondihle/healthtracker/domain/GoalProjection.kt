package com.prestondihle.healthtracker.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Where the current pace lands, and when.
 *
 * [target] is the mark being aimed at -- the next waypoint if there is one on the
 * way, otherwise the goal itself. [from] is the fitted value today rather than
 * the last reading: the fit is the whole point, and starting the segment at a
 * morning that happened to be high would draw a line whose first point
 * contradicts the trend it is extrapolating.
 */
data class GoalEta(
    val target: Float,
    val reachedOn: LocalDate,
    val from: Float,
    val fromDate: LocalDate,
    /** Change per day in stored units, signed. Negative while losing weight. */
    val perDay: Float,
)

/**
 * A straight line through the last month of weighing, run forward to the next mark.
 *
 * ## Why this is allowed to say nothing
 *
 * Most of this file is refusals, and they are the feature. An ETA is the most
 * confident-sounding thing the app can print -- a specific weight on a specific
 * dated day -- and it is built on the shakiest input any of these charts has: a
 * month of a measurement that moves a pound and a half on water. So it declines
 * wherever the number would be a guess wearing a date:
 *
 * - **Fewer than [MIN_READINGS] in the window.** A line through three mornings
 *   is a line through three mornings, and its slope is whatever the noise did.
 * - **A slope pointing away from the target.** Somebody two pounds up over the
 *   month has an arrival date somewhere in the past or the far future depending
 *   on which side of the goal they are, and neither is a thing to print. Saying
 *   nothing is the honest answer to "when at this pace" when this pace never
 *   arrives.
 * - **A slope so slight the answer runs past [MAX_HORIZON_DAYS].** Divide by a
 *   number near zero and the date goes to the next century. Three years out is
 *   not a plan, and a chart that quotes one has stopped being about anything.
 * - **No goal set at all**, since there is then nothing to be en route to.
 *
 * A made-up ETA is worse than no ETA, because there is no way to look at a date
 * and see how much was behind it.
 */
object GoalProjection {

    /**
     * How much history the fit uses.
     *
     * A month. Shorter and a single bad week sets the slope; longer and a
     * genuine change of approach takes weeks to show up in the date, which is
     * exactly when somebody is looking at it.
     */
    const val FIT_DAYS = 30L

    /**
     * Fewest readings in the window before a line may be drawn through them.
     *
     * Five, which over a month is weighing about weekly -- enough that the slope
     * is describing the month rather than the two mornings at its ends.
     */
    const val MIN_READINGS = 5

    /**
     * Furthest ahead a date may be quoted.
     *
     * Two years. Past that the slope is small enough that the arrival date moves
     * by months on one morning's reading, so the number is precise and unstable
     * at once -- the worst combination for something printed to the day.
     */
    const val MAX_HORIZON_DAYS = 730L

    /**
     * The next mark on the way, or the goal if no waypoint lies between.
     *
     * "Next" is by distance in the direction of the goal, so a ladder of
     * waypoints is climbed one rung at a time and a rung already passed is not
     * offered again -- the reader is told about the mark they are actually
     * approaching, not the one they cleared last month.
     */
    private fun nextMark(from: Float, goal: Float, waypoints: List<Float>): Float? {
        val towardGoal = goal - from
        if (towardGoal == 0f) return null
        return (waypoints + goal)
            .filter { mark -> (mark - from) / towardGoal > 0f }
            .minByOrNull { abs(it - from) }
    }

    /**
     * Least-squares slope and intercept of [points], x measured in days from [origin].
     *
     * Null when every reading is on one day, which has no slope rather than an
     * infinite one.
     */
    private fun fit(points: List<Pair<LocalDate, Float>>, origin: LocalDate): Pair<Float, Float>? {
        val n = points.size
        val xs = points.map { ChronoUnit.DAYS.between(origin, it.first).toDouble() }
        val ys = points.map { it.second.toDouble() }
        val meanX = xs.average()
        val meanY = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        for (index in 0 until n) {
            val dx = xs[index] - meanX
            sxy += dx * (ys[index] - meanY)
            sxx += dx * dx
        }
        if (sxx == 0.0) return null
        val slope = sxy / sxx
        return slope.toFloat() to (meanY - slope * meanX).toFloat()
    }

    /**
     * When the last month's pace reaches the next mark, or null on any refusal.
     *
     * [readings] and [goal] are in whatever unit the caller stores; nothing here
     * is unit-aware, so this works in kilograms and the screen converts. Passing
     * pounds in would give an answer in pounds and the same date.
     */
    fun forGoal(
        readings: List<Pair<LocalDate, Float>>,
        today: LocalDate,
        goal: Float?,
        waypoints: List<Float> = emptyList(),
        fitDays: Long = FIT_DAYS,
    ): GoalEta? {
        if (goal == null) return null

        val window = today.minusDays(fitDays - 1)
        val recent = readings.filter { !it.first.isBefore(window) && !it.first.isAfter(today) }
        if (recent.size < MIN_READINGS) return null

        val (perDay, intercept) = fit(recent, window) ?: return null
        // The fitted value today, not the last reading: a morning that happened
        // to be high would start the segment somewhere the trend does not go.
        val from = perDay * ChronoUnit.DAYS.between(window, today) + intercept

        val target = nextMark(from, goal, waypoints) ?: return null
        val remaining = target - from
        // Moving away from it, or not moving. Either way this pace never arrives.
        if (perDay == 0f || (remaining / perDay) <= 0f) return null

        val days = (remaining / perDay).toDouble().roundToLong()
        if (days <= 0L || days > MAX_HORIZON_DAYS) return null

        return GoalEta(
            target = target,
            reachedOn = today.plusDays(days),
            from = from,
            fromDate = today,
            perDay = perDay,
        )
    }
}
