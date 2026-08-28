package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.ReadinessFacts
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The morning's two facts, and the several ways each declines to be one.
 *
 * The refusals matter more than the arithmetic. A baseline is the most
 * authoritative-looking thing on the card and the easiest to compute from
 * nothing at all -- a median of two mornings is two mornings, and a comparison
 * against it would read exactly like a comparison against a month.
 */
class ReadinessTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 28)

    /** [days] mornings ending yesterday, all at [bpm]. */
    private fun priorRestingDays(days: Int, bpm: Int): Map<LocalDate, Int> =
        (1..days).associate { today.minusDays(it.toLong()) to bpm }

    @Test
    fun `a morning above the trailing median reports the gap`() {
        val resting = priorRestingDays(20, 54) + (today to 60)

        val readiness =
            ReadinessFacts.on(today, resting, emptyMap(), sleepGoalMinutes = null)

        assertEquals(60, readiness.restingBpm)
        assertEquals(54, readiness.baselineBpm)
        assertEquals(6, readiness.restingDeltaBpm)
    }

    @Test
    fun `the baseline excludes today`() {
        // Nineteen mornings at 50 and one wild one at 90. Included, today would
        // pull its own baseline up and the gap would read smaller than it is --
        // on a short window, small enough to hide the thing the line is for.
        val resting = priorRestingDays(19, 50) + (today to 90)

        val readiness = ReadinessFacts.on(today, resting, emptyMap(), null)

        assertEquals(50, readiness.baselineBpm)
        assertEquals(40, readiness.restingDeltaBpm)
    }

    @Test
    fun `too few mornings produce no baseline rather than a confident one`() {
        val resting = priorRestingDays(ReadinessFacts.MIN_BASELINE_DAYS - 1, 55) + (today to 61)

        val readiness = ReadinessFacts.on(today, resting, emptyMap(), null)

        assertNull(readiness.baselineBpm)
        assertNull(readiness.restingDeltaBpm)
        // The reading itself is still a measurement and still worth printing.
        assertEquals(61, readiness.restingBpm)
        assertTrue(readiness.hasAnything)
    }

    @Test
    fun `mornings older than the window do not count toward the baseline`() {
        // Plenty of history, but all of it outside thirty days.
        val old =
            (40..70).associate { today.minusDays(it.toLong()) to 52 } + (today to 60)

        val readiness = ReadinessFacts.on(today, old, emptyMap(), null)

        assertNull(readiness.baselineBpm)
    }

    @Test
    fun `the baseline is a median, so one bad night does not move it for a month`() {
        // Nineteen at 52 and one illness morning at 90. A mean would read 53.9 and
        // carry that outlier for a month; the median does not notice it.
        val resting =
            priorRestingDays(19, 52) + (today.minusDays(20) to 90) + (today to 55)

        val readiness = ReadinessFacts.on(today, resting, emptyMap(), null)

        assertEquals(52, readiness.baselineBpm)
        assertEquals(3, readiness.restingDeltaBpm)
    }

    @Test
    fun `sleep is reported against the goal, and short is positive`() {
        val readiness =
            ReadinessFacts.on(
                today,
                emptyMap(),
                sleepByDay = mapOf(today to 340),
                sleepGoalMinutes = 480,
            )

        assertEquals(340, readiness.sleepMinutes)
        assertEquals(140, readiness.sleepDeficitMinutes)
    }

    @Test
    fun `sleeping past the goal is a negative deficit, not a clamped zero`() {
        val readiness =
            ReadinessFacts.on(today, emptyMap(), mapOf(today to 520), sleepGoalMinutes = 480)

        assertEquals(-40, readiness.sleepDeficitMinutes)
    }

    @Test
    fun `with no goal set the night is still reported`() {
        val readiness = ReadinessFacts.on(today, emptyMap(), mapOf(today to 400), null)

        assertEquals(400, readiness.sleepMinutes)
        assertNull(readiness.sleepDeficitMinutes)
        assertTrue(readiness.hasAnything)
    }

    @Test
    fun `one missing half does not blank the other`() {
        // The whole argument for two facts rather than a score: a composite would
        // have to either invent the missing half or report nothing.
        val heartOnly =
            ReadinessFacts.on(today, priorRestingDays(20, 54) + (today to 58), emptyMap(), 480)
        assertEquals(4, heartOnly.restingDeltaBpm)
        assertNull(heartOnly.sleepMinutes)
        assertTrue(heartOnly.hasAnything)

        val sleepOnly = ReadinessFacts.on(today, emptyMap(), mapOf(today to 400), 480)
        assertNull(sleepOnly.restingDeltaBpm)
        assertEquals(400, sleepOnly.sleepMinutes)
        assertTrue(sleepOnly.hasAnything)
    }

    @Test
    fun `a morning with nothing recorded says so`() {
        val readiness = ReadinessFacts.on(today, priorRestingDays(20, 54), emptyMap(), 480)

        assertNull(readiness.restingBpm)
        assertNull(readiness.restingDeltaBpm)
        assertFalse(readiness.hasAnything)
    }
}
