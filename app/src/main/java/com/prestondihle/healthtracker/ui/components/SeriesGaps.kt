package com.prestondihle.healthtracker.ui.components

import java.time.Duration

/**
 * Splits a measured series where it stops being measured.
 *
 * ## Why
 *
 * A line joining the last reading before a gap to the first one after it draws a
 * straight run through hours that were never recorded, and draws it in exactly
 * the same ink as the measurements either side. A watch taken off overnight
 * produced an eight-hour diagonal across the middle of the heart rate trace,
 * sloping smoothly between two real readings and implying every value in
 * between. Breaking the line says the honest thing: nothing was recorded here.
 *
 * This is only for *measured* series. A modelled curve — caffeine decay, macro
 * absorption — is a continuous function sampled evenly, so it has no gaps to
 * find and must stay joined.
 *
 * ## Why the threshold comes from the data
 *
 * A fixed one would have to be wrong for somebody. A continuous monitor writes
 * every five minutes, so twenty minutes of silence is a real dropout; a person
 * taking three fingersticks a day is five hours apart when everything is
 * working, and the same twenty-minute rule would shatter their chart into
 * separate dots. Both are "blood sugar" and both arrive in the same series.
 *
 * So the break is judged against the series' own cadence: the median gap between
 * its readings, times [BREAK_MULTIPLE]. A monitor missing four samples in a row
 * breaks; a fingerstick user breaks only when a whole day goes unlogged. The
 * median rather than the mean, because the one enormous gap being looked for
 * would drag a mean up towards itself and hide exactly the thing it is meant to
 * reveal.
 */
internal object SeriesGaps {

    /**
     * How many times its own typical spacing a series may skip before the line
     * breaks.
     *
     * Four tolerates the ordinary stutter of a sensor — a dropped sample or
     * three — while catching the point at which a reader would start guessing at
     * what happened in the middle.
     */
    private const val BREAK_MULTIPLE = 4

    /**
     * [points], in time order, split into runs that were actually continuous.
     *
     * Returns one run when nothing is missing, so a caller can draw the result
     * the same way in either case. A run of a single reading is kept rather than
     * dropped: an isolated measurement is still a measurement, and the caller
     * draws it as a dot.
     */
    fun segments(points: List<TimePoint>): List<List<TimePoint>> {
        if (points.size < 2) return if (points.isEmpty()) emptyList() else listOf(points)

        val gaps = points.zipWithNext { earlier, later ->
            Duration.between(earlier.time, later.time).toMillis()
        }
        val spacing = gaps.filter { it > 0 }.sorted()
        if (spacing.isEmpty()) return listOf(points)
        val limit = spacing[spacing.size / 2] * BREAK_MULTIPLE

        val runs = mutableListOf<List<TimePoint>>()
        var start = 0
        gaps.forEachIndexed { index, gap ->
            if (gap > limit) {
                runs.add(points.subList(start, index + 1))
                start = index + 1
            }
        }
        runs.add(points.subList(start, points.size))
        return runs
    }
}
