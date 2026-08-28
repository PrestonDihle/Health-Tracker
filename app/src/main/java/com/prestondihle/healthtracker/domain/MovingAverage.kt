package com.prestondihle.healthtracker.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp

/**
 * The trend under a daily measurement, drawn as a trailing weekly mean.
 *
 * ## Why
 *
 * A weight read every morning moves a pound and a half on water, glycogen and
 * what time you last ate, which is several times the change a week of real
 * effort produces. Drawn raw, the line asks to be read at its last point and
 * that point is mostly noise -- two mornings a week apart can disagree in the
 * opposite direction to the month they sit in. Resting heart rate is the same
 * shape of problem with a different cause.
 *
 * ## Trailing, not centred
 *
 * This is the one place it deliberately parts company with [GlucoseSmoothing],
 * whose kernel is symmetric about the point it computes. A trailing mean uses
 * only readings at or before its own date, which costs it a lag of a few days
 * and buys two things worth more than the lag.
 *
 * A past point never changes. A centred mean revises last Tuesday every time a
 * new morning is logged, so a reader who noticed the line turn upward can come
 * back a week later to find it never did -- and nothing on the chart would say
 * it had moved. And the newest point means what it appears to mean: it is the
 * figure you would have had that morning, not one that quietly borrows from days
 * that had not happened yet.
 *
 * ## Weighted by time, for [GlucoseSmoothing]'s reason
 *
 * These are daily series with holes in them -- nobody weighs themselves every
 * day -- so "the last seven readings" is not "the last seven days", and on a
 * sparse stretch it would reach back a fortnight while claiming a week.
 * Weighting by how many days ago a reading was makes the window mean what it
 * says however few readings fall inside it.
 *
 * Gaussian rather than flat for the same reason the glucose filter is: a boxcar
 * drops a reading from full weight to nothing the day it ages out, and that
 * shows up as a kink in a line whose whole job is to be free of them.
 *
 * ## What it does not do
 *
 * It never overshoots. Every output is a weighted mean of real readings with the
 * weights renormalised to sum to one, so the line cannot leave the range of what
 * was actually measured -- the property that lets it be drawn over the readings
 * rather than beside them.
 *
 * It does not resample or invent points: one output per input reading, at that
 * reading's own date. A day nobody weighed in on gets no averaged point either,
 * and the chart joins across it exactly as it joins the raw line.
 *
 * And it says nothing until it has something to say. A window holding one
 * reading would return that reading, drawing a "7-day average" that is one
 * morning and tracing the raw line exactly -- the [Readiness] baseline rule,
 * which refuses a median of two mornings rather than printing a confident-
 * looking one. Below [MIN_READINGS] the point is dropped and the line starts
 * where it becomes true.
 */
object MovingAverage {

    /**
     * How many days the window spans, counting the point's own day.
     *
     * Seven, so the figure means what the label on the series says it does: a
     * reader checking by hand counts today and the six days behind it.
     */
    const val WINDOW_DAYS = 7L

    /**
     * Standard deviation of the kernel, as a fraction of the window.
     *
     * Half, which is [GlucoseSmoothing]'s ratio and puts the oldest day in range
     * at about a fifth of the newest's weight -- present, and plainly no longer
     * the point.
     */
    private const val SIGMA_FRACTION = 0.5

    /**
     * Fewest readings in a window that can still be called an average.
     *
     * Three, the figure [GlucoseSmoothing] already refuses to filter below. Two
     * points are a pair of measurements and a line drawn through them is not a
     * trend.
     */
    const val MIN_READINGS = 3

    /**
     * One trailing mean per input reading, at that reading's own date.
     *
     * Input is sorted by date first: the sliding window depends on it, and a
     * caller merging a manual table with a synced one cannot be assumed to have
     * done it. Dates are expected to be distinct -- the day-indexed series here
     * are built one value per day -- but a repeat is harmless, contributing its
     * own weight like any other reading.
     */
    fun trailing(
        readings: List<Pair<LocalDate, Float>>,
        windowDays: Long = WINDOW_DAYS,
    ): List<Pair<LocalDate, Float>> {
        if (readings.size < MIN_READINGS || windowDays <= 0) return emptyList()

        val sorted = readings.sortedBy { it.first }
        val sigma = windowDays * SIGMA_FRACTION
        // The oldest day still inside a window that counts the point's own day.
        val oldest = windowDays - 1

        var low = 0
        return buildList {
            sorted.indices.forEach { index ->
                val date = sorted[index].first
                // Only ever moves forward, because `date` does -- which keeps
                // this linear over a year of daily readings rather than
                // quadratic.
                while (ChronoUnit.DAYS.between(sorted[low].first, date) > oldest) low++

                var weighted = 0.0
                var totalWeight = 0.0
                var counted = 0
                for (neighbour in low..index) {
                    val age = ChronoUnit.DAYS.between(sorted[neighbour].first, date).toDouble()
                    val offset = age / sigma
                    val weight = exp(-0.5 * offset * offset)
                    weighted += weight * sorted[neighbour].second
                    totalWeight += weight
                    counted++
                }

                if (counted >= MIN_READINGS && totalWeight > 0.0) {
                    add(date to (weighted / totalWeight).toFloat())
                }
            }
        }
    }
}
