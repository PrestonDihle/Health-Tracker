package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.MovingAverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.time.LocalDate
import org.junit.Test

/**
 * What the trend line under a daily measurement may and may not do.
 *
 * This is drawn *over* the readings in the same colour family, so the ways it
 * can be wrong are all ways that still look like a plausible trend. It cannot
 * overshoot the readings, or it would claim a weight nobody was ever near; it
 * cannot lead them, or a line that lags by design would be reporting a change
 * before it happened; and it must not draw at all until it has enough behind it
 * to be an average, or it starts life tracing the raw line exactly while a key
 * beside it says "7-day avg".
 */
class MovingAverageTest {

    private val start = LocalDate.of(2026, 3, 1)

    private fun days(vararg values: Float) =
        values.mapIndexed { index, value -> start.plusDays(index.toLong()) to value }

    @Test
    fun `it never leaves the range of the readings it averaged`() {
        // A fortnight of ordinary weighing noise around 198.
        val readings =
            days(199f, 196.5f, 198f, 200f, 197f, 195.5f, 199.5f, 198.5f, 196f, 197.5f)

        val averaged = MovingAverage.trailing(readings)

        val low = readings.minOf { it.second }
        val high = readings.maxOf { it.second }
        assertTrue(averaged.isNotEmpty())
        assertTrue(averaged.all { it.second in low..high })
    }

    @Test
    fun `it does not resample - one point per reading, on that reading's own date`() {
        // Weighed four times across a fortnight, unevenly.
        val readings =
            listOf(
                start to 200f,
                start.plusDays(2) to 199f,
                start.plusDays(3) to 198f,
                start.plusDays(9) to 195f,
            )

        val averaged = MovingAverage.trailing(readings)

        // Never more points than went in, and every date is one that was read.
        assertTrue(averaged.size <= readings.size)
        assertTrue(averaged.all { point -> readings.any { it.first == point.first } })
    }

    @Test
    fun `it says nothing until the window holds enough to be an average`() {
        val readings = days(200f, 199f, 198f, 197f, 196f)

        val averaged = MovingAverage.trailing(readings)

        // The first two days have only themselves and one neighbour behind
        // them. Emitted, they would equal the raw reading and draw a "7-day
        // average" that is one morning.
        assertEquals(3, averaged.size)
        assertEquals(start.plusDays(2), averaged.first().first)
    }

    @Test
    fun `fewer readings than an average needs produces no line at all`() {
        assertTrue(MovingAverage.trailing(days(200f, 199f)).isEmpty())
        assertTrue(MovingAverage.trailing(emptyList()).isEmpty())
    }

    @Test
    fun `it trails rather than leads - a step is followed, never anticipated`() {
        // Flat, then a genuine drop held for a week.
        val readings = days(200f, 200f, 200f, 200f, 200f, 190f, 190f, 190f, 190f, 190f)
        val stepDate = start.plusDays(5)

        val averaged = MovingAverage.trailing(readings).toMap()

        // Nothing before the step has moved off the level that preceded it. A
        // centred kernel would have started bending down days early, reporting
        // a fall on mornings that had not yet weighed one.
        assertEquals(200f, averaged[start.plusDays(3)]!!, 0.001f)
        assertEquals(200f, averaged[start.plusDays(4)]!!, 0.001f)
        // And the step's own day is already partway down rather than all the
        // way: it is one morning inside a week that has not happened yet.
        assertTrue(averaged[stepDate]!! < 200f)
        assertTrue(averaged[stepDate]!! > 190f)
    }

    @Test
    fun `the newest reading counts most`() {
        // Six days at 200 and then one at 190. Weighted by recency the answer
        // sits below a flat mean of the seven, which would be 198.6.
        val readings = days(200f, 200f, 200f, 200f, 200f, 200f, 190f)

        val newest = MovingAverage.trailing(readings).last().second

        assertTrue(newest < 198.6f)
        assertTrue(newest > 190f)
    }

    @Test
    fun `a gap in the weighing does not drag an old reading forward`() {
        // Three mornings, then a fortnight off the scale, then three more at a
        // genuinely lower weight. Weighted by *index* rather than by time, the
        // old 200s would still be neighbours of the new 190s and would hold the
        // line up over a stretch nobody weighed anything at all.
        val readings =
            listOf(
                start to 200f,
                start.plusDays(1) to 200f,
                start.plusDays(2) to 200f,
                start.plusDays(16) to 190f,
                start.plusDays(17) to 190f,
                start.plusDays(18) to 190f,
            )

        val averaged = MovingAverage.trailing(readings).toMap()

        // The far side of the gap is the new weight, with nothing of the old in
        // it: the fortnight put every earlier reading outside the window.
        assertEquals(190f, averaged[start.plusDays(18)]!!, 0.001f)
    }

    @Test
    fun `a window holding one reading after a gap is dropped, not returned raw`() {
        val readings =
            listOf(
                start to 200f,
                start.plusDays(1) to 200f,
                start.plusDays(2) to 200f,
                // Alone in its own window: everything else is a fortnight away.
                start.plusDays(16) to 190f,
            )

        val averaged = MovingAverage.trailing(readings).toMap()

        // Returned, it would be exactly the reading with a key calling it an
        // average -- the `Readiness` baseline refusal, in a different shape.
        assertTrue(averaged[start.plusDays(16)] == null)
    }

    @Test
    fun `a flat run averages to its own level`() {
        val readings = days(180f, 180f, 180f, 180f, 180f, 180f, 180f)

        MovingAverage.trailing(readings).forEach { assertEquals(180f, it.second, 0.001f) }
    }
}
