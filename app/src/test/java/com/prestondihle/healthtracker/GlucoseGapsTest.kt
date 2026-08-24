package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.GlucoseGaps
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which stretches of a blood sugar trace are worth asking the source about
 * again.
 *
 * Both mistakes are expensive in their own way: missing a real hole leaves the
 * chart permanently wrong about hours that were in fact recorded, and finding a
 * hole in every ordinary pause spends a query on every refresh forever.
 */
class GlucoseGapsTest {

    private val now: Instant = Instant.parse("2026-08-24T12:00:00Z")
    private val from: Instant = now.minus(Duration.ofHours(72))

    /** A reading every five minutes from [start], as a continuous monitor writes them. */
    private fun trace(start: Instant, hours: Long): List<Instant> {
        val count = hours * 12
        return (0 until count).map { start.plusSeconds(it * 300) }
    }

    @Test
    fun `a continuous trace has nothing worth re-reading`() {
        val gaps = GlucoseGaps.spans(trace(from, 72), from, now)

        assertEquals(emptyList<Any>(), gaps)
    }

    @Test
    fun `a missing afternoon is found`() {
        // Twelve hours, then four hours of nothing, then the rest.
        val before = trace(from, 12)
        val after = trace(from.plus(Duration.ofHours(16)), 56)

        val gaps = GlucoseGaps.spans(before + after, from, now)

        assertEquals(1, gaps.size)
        assertTrue(gaps.single().duration >= Duration.ofHours(4))
    }

    @Test
    fun `a monitor that stopped an hour ago leaves a gap at the right-hand edge`() {
        // No later reading bounds this one, and it is the freshest and most
        // fillable hole there is -- so the window end has to count as an edge.
        val gaps = GlucoseGaps.spans(trace(from, 69), from, now)

        assertEquals(1, gaps.size)
        assertEquals(now, gaps.single().to)
    }

    @Test
    fun `a window with no readings at all is one gap covering the lot`() {
        val gaps = GlucoseGaps.spans(emptyList(), from, now)

        assertEquals(1, gaps.size)
        assertEquals(from, gaps.single().from)
        assertEquals(now, gaps.single().to)
    }

    @Test
    fun `an ordinary sensor stutter is left alone`() {
        // Fifteen minutes: three missed samples, and a hole that will still be
        // empty when it is read back. Re-reading this on every refresh is the
        // failure mode the threshold exists to prevent.
        val before = trace(from, 12)
        // Long enough to reach the window end: a trace that stops short leaves a
        // gap at the right-hand edge, which is a real finding and not the one
        // this test is about.
        val after = trace(from.plus(Duration.ofHours(12)).plus(Duration.ofMinutes(15)), 60)

        val gaps = GlucoseGaps.spans(before + after, from, now)

        assertEquals(emptyList<Any>(), gaps)
    }

    @Test
    fun `three fingersticks a day are all gap and are reported as such`() {
        // Deliberately different from SeriesGaps, which judges a break against
        // the series' own cadence and would call this continuous. This is
        // deciding whether to spend a query, not whether to draw a line -- and a
        // reader with three readings a day genuinely does have most of the day
        // missing, so the honest answer is that there is a lot to go and ask
        // about. It costs one sweep per refresh and cannot get the chart wrong.
        val readings =
            (0 until 3).flatMap { day ->
                listOf(8L, 13L, 19L).map {
                    from.plus(Duration.ofHours(day * 24 + it))
                }
            }

        val gaps = GlucoseGaps.spans(readings, from, now)

        assertTrue(gaps.isNotEmpty())
    }

    @Test
    fun `more holes than the cap collapse into one sweep`() {
        // Ten separate outages. Ten round trips to a source that is plainly not
        // recording properly is worse than one wide read that also picks up
        // whatever fell between them.
        val readings =
            (0 until 10).flatMap { block ->
                trace(from.plus(Duration.ofHours(block * 7L)), 5)
            }

        val gaps = GlucoseGaps.spans(readings, from, now)

        assertEquals(1, gaps.size)
    }

    @Test
    fun `spans never reach outside the window`() {
        // The padding is added blind and then clamped: a gap that starts at the
        // window edge must not produce a read that starts before it, since the
        // caller's already-known ids were only gathered inside the window.
        val gaps = GlucoseGaps.spans(trace(from.plus(Duration.ofHours(6)), 66), from, now)

        assertEquals(1, gaps.size)
        assertEquals(from, gaps.single().from)
        assertTrue(!gaps.single().to.isAfter(now))
    }

    @Test
    fun `readings outside the window are ignored rather than bounding it`() {
        // A reading from four days ago says nothing about whether the last three
        // are complete, and letting it bound the first gap would hide a hole at
        // the start of the window behind data that is not in it.
        val stale = listOf(from.minus(Duration.ofHours(10)))

        val gaps = GlucoseGaps.spans(stale + trace(from.plus(Duration.ofHours(6)), 66), from, now)

        assertEquals(from, gaps.single().from)
    }
}
