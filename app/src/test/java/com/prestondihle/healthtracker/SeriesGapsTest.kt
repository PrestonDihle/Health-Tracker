package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.ui.components.SeriesGaps
import com.prestondihle.healthtracker.ui.components.TimePoint
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a measured line breaks.
 *
 * The threshold is derived from each series' own cadence rather than fixed, so
 * what has to be pinned is that the same rule serves a monitor writing every
 * five minutes and a person taking three readings a day — the two shapes that
 * both arrive as "blood sugar".
 */
class SeriesGapsTest {

    private val start: Instant = Instant.parse("2026-08-20T06:00:00Z")

    /** Points at the given minute offsets from [start]. */
    private fun at(vararg minutes: Long): List<TimePoint> =
        minutes.map { TimePoint(start.plus(Duration.ofMinutes(it)), 100f) }

    private fun offsets(runs: List<List<TimePoint>>): List<List<Long>> =
        runs.map { run -> run.map { Duration.between(start, it.time).toMinutes() } }

    @Test
    fun `an evenly sampled series is one unbroken run`() {
        val runs = SeriesGaps.segments(at(0, 5, 10, 15, 20, 25))

        assertEquals(1, runs.size)
        assertEquals(6, runs.first().size)
    }

    @Test
    fun `a watch off the wrist overnight breaks the line`() {
        // Five-minute buckets, then eight hours of nothing, then buckets again.
        val points = at(0, 5, 10, 15, 495, 500, 505)

        assertEquals(listOf(listOf(0L, 5, 10, 15), listOf(495L, 500, 505)), offsets(SeriesGaps.segments(points)))
    }

    @Test
    fun `a dropped sample or three does not break it`() {
        // A sensor stuttering is not a sensor that stopped. At five-minute
        // spacing this is a twenty-minute hole, right at the tolerance.
        val runs = SeriesGaps.segments(at(0, 5, 10, 30, 35, 40))

        assertEquals(1, runs.size)
    }

    @Test
    fun `a series read every few hours breaks only when a day goes unlogged`() {
        // Three fingersticks a day. A fixed twenty-minute rule would shatter this
        // into six separate dots; judged against its own five-hour cadence, only
        // the missing day is a gap.
        val hours = listOf(0L, 5, 10, 24, 29, 34).map { it * 60 }
        val withMissedDay = listOf(0L, 5, 10, 58, 63, 68).map { it * 60 }

        assertEquals(1, SeriesGaps.segments(at(*hours.toLongArray())).size)
        assertEquals(2, SeriesGaps.segments(at(*withMissedDay.toLongArray())).size)
    }

    @Test
    fun `an isolated reading is kept as a run of its own`() {
        // Dropping it would delete a measurement for the crime of having no
        // neighbours; the caller draws a single-point run as a dot.
        val runs = SeriesGaps.segments(at(0, 5, 10, 600))

        assertEquals(listOf(listOf(0L, 5, 10), listOf(600L)), offsets(runs))
    }

    @Test
    fun `one enormous gap cannot raise the threshold enough to hide itself`() {
        // The median is used rather than the mean for exactly this: a mean over
        // these gaps is dragged past the size of the gap being looked for.
        val runs = SeriesGaps.segments(at(0, 5, 10, 15, 20, 2000))

        assertEquals(2, runs.size)
    }

    @Test
    fun `too few points to have a cadence are left alone`() {
        assertEquals(emptyList<List<TimePoint>>(), SeriesGaps.segments(emptyList()))
        assertEquals(1, SeriesGaps.segments(at(0)).size)
        assertEquals(1, SeriesGaps.segments(at(0, 900)).size)
    }
}
