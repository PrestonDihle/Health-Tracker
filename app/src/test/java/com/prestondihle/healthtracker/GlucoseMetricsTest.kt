package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.GlucoseAnalysis
import com.prestondihle.healthtracker.domain.GlucoseMetrics
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CGM summary figures, and the gate that decides whether to report them.
 *
 * Every metric here is a proportion, which means a fragment of a day produces a
 * perfectly well-formed number about a span nobody asked for. That is the
 * failure worth most of this suite: a morning of readings after a sensor change
 * reporting a time-in-range for the whole day, with nothing on screen to say so.
 */
class GlucoseMetricsTest {

    private val start: Instant = Instant.parse("2026-08-27T00:00:00Z")
    private val end: Instant = start.plus(Duration.ofDays(1))

    /** A reading every five minutes across [hours], valued by [mgDl]. */
    private fun trace(hours: Long, fromHour: Long = 0, mgDl: (Int) -> Int): List<Pair<Instant, Int>> {
        val perHour = 12
        return (0 until (hours * perHour).toInt()).map { i ->
            start.plus(Duration.ofHours(fromHour)).plus(Duration.ofMinutes(i * 5L)) to mgDl(i)
        }
    }

    private fun metrics(readings: List<Pair<Instant, Int>>) =
        GlucoseAnalysis.over(readings, start, end, targetLowMgDl = 70, targetHighMgDl = 140)

    @Test
    fun `a flat in-range day is entirely in range`() {
        val result = metrics(trace(24) { 100 })!!

        assertEquals(1f, result.timeInRange, 0.001f)
        assertEquals(0f, result.timeBelowRange, 0.001f)
        assertEquals(0f, result.timeAboveRange, 0.001f)
        assertEquals(100f, result.meanMgDl, 0.001f)
        // No spread at all, so nothing to be unstable about.
        assertEquals(0f, result.standardDeviation, 0.001f)
        assertEquals(0f, result.coefficientOfVariation, 0.001f)
        assertTrue(result.isStable)
    }

    @Test
    fun `readings are split across the band's two edges`() {
        // A quarter below, a quarter above, half inside.
        val result =
            metrics(
                trace(24) { i ->
                    when (i % 4) {
                        0 -> 60
                        1 -> 200
                        else -> 110
                    }
                }
            )!!

        assertEquals(0.5f, result.timeInRange, 0.001f)
        assertEquals(0.25f, result.timeBelowRange, 0.001f)
        assertEquals(0.25f, result.timeAboveRange, 0.001f)
        // The three shares are a partition and must add to one.
        assertEquals(
            1f,
            result.timeInRange + result.timeBelowRange + result.timeAboveRange,
            0.0001f,
        )
    }

    @Test
    fun `the band's own edges count as in range`() {
        // 70 and 140 are the targets, not the first values outside them. An
        // exclusive comparison here would report a reading exactly on target as
        // a excursion.
        val result = metrics(trace(24) { i -> if (i % 2 == 0) 70 else 140 })!!
        assertEquals(1f, result.timeInRange, 0.001f)
    }

    /**
     * The gate, and the reason the whole thing exists.
     *
     * Six hours of readings describe six hours. Reported against a day they are
     * a time-in-range for a quarter of it, wearing the label of the whole.
     */
    @Test
    fun `a fragment of a day reports nothing at all`() {
        assertNull(metrics(trace(hours = 6) { 100 }))
        assertNull(metrics(trace(hours = 16) { 100 }))
    }

    @Test
    fun `coverage is judged on the span the readings occupy`() {
        // Just under seventy per cent of a day is refused; just over is reported.
        assertNull(metrics(trace(hours = 16, fromHour = 0) { 100 }))
        assertEquals(1f, metrics(trace(hours = 17) { 100 })!!.timeInRange, 0.001f)
    }

    /**
     * A sparse trace that still spans the window is reported.
     *
     * The gate is about the span covered, not the number of readings: somebody
     * taking four fingersticks spread across a day has genuinely sampled it, and
     * a count-based gate would be a gate on owning a CGM.
     */
    @Test
    fun `a few readings spread across the day still count`() {
        val readings =
            listOf(
                start.plus(Duration.ofHours(1)) to 95,
                start.plus(Duration.ofHours(8)) to 120,
                start.plus(Duration.ofHours(15)) to 105,
                start.plus(Duration.ofHours(22)) to 90,
            )
        val result = metrics(readings)!!

        assertEquals(4, result.readingCount)
        assertEquals(1f, result.timeInRange, 0.001f)
    }

    @Test
    fun `an empty or single-reading window reports nothing`() {
        assertNull(metrics(emptyList()))
        assertNull(metrics(listOf(start.plus(Duration.ofHours(2)) to 100)))
    }

    @Test
    fun `readings outside the window are not counted`() {
        val inside = trace(24) { 100 }
        val yesterday = listOf(start.minus(Duration.ofHours(3)) to 300)
        val tomorrow = listOf(end.plus(Duration.ofHours(3)) to 300)

        val result = metrics(yesterday + inside + tomorrow)!!

        assertEquals(inside.size, result.readingCount)
        assertEquals(100f, result.meanMgDl, 0.001f)
    }

    /**
     * GMI is the published regression and nothing else.
     *
     * Pinned at a round mean so a transcription slip in either constant shows up
     * rather than hiding inside a plausible-looking percentage.
     */
    @Test
    fun `GMI follows the published regression`() {
        val result = metrics(trace(24) { 100 })!!
        // 3.31 + 0.02392 * 100 = 5.702
        assertEquals(5.702f, result.gmiPercent, 0.0005f)

        val higher = metrics(trace(24) { 154 })!!
        // The mean that lands on roughly a 7% GMI.
        assertEquals(6.994f, higher.gmiPercent, 0.001f)
    }

    @Test
    fun `variability is measured against the mean, not in absolute terms`() {
        // Alternating 80 and 120: mean 100, population SD 20, so CV is 20%.
        val result = metrics(trace(24) { i -> if (i % 2 == 0) 80 else 120 })!!

        assertEquals(100f, result.meanMgDl, 0.001f)
        assertEquals(20f, result.standardDeviation, 0.001f)
        assertEquals(20f, result.coefficientOfVariation, 0.001f)
        assertTrue(result.isStable)
    }

    @Test
    fun `a trace can average well and still be unstable`() {
        // Swinging 55 to 165 averages 110 -- a respectable mean sitting on a CV
        // of fifty per cent. Reporting the mean alone would call this a good day.
        val result = metrics(trace(24) { i -> if (i % 2 == 0) 55 else 165 })!!

        assertEquals(110f, result.meanMgDl, 0.001f)
        assertTrue("CV should exceed the stability ceiling", result.coefficientOfVariation > 36f)
        assertEquals(false, result.isStable)
        // And the mean hides more than the swing: not one reading was in range.
        // Half sat under the floor and half over the ceiling, averaging to a
        // number that never actually occurred.
        assertEquals(0f, result.timeInRange, 0.001f)
        assertEquals(0.5f, result.timeBelowRange, 0.001f)
        assertEquals(0.5f, result.timeAboveRange, 0.001f)
    }

    @Test
    fun `the stability ceiling is the consensus thirty-six per cent`() {
        assertEquals(36f, GlucoseMetrics.STABLE_CV_PERCENT, 0.001f)
    }

    @Test
    fun `a window that ends before it starts reports nothing`() {
        assertNull(
            GlucoseAnalysis.over(trace(24) { 100 }, end, start, targetLowMgDl = 70, targetHighMgDl = 140)
        )
    }
}
