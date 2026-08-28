package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.Streaks
import org.junit.Assert.assertEquals
import java.time.LocalDate
import org.junit.Test

/**
 * The rule that makes a streak worth showing, now that four of them share it.
 *
 * Lifted out of the fasting screen, which had the only copy. What is being
 * pinned is not the loop -- it is four lines -- but the tolerance for an empty
 * today, which is the part a second implementation gets wrong: counted as a
 * miss, every streak in the app resets to zero overnight and comes back each
 * evening, so the number is wrong for most of the hours anybody reads it.
 */
class StreaksTest {

    private val today = LocalDate.of(2026, 3, 4)

    private fun run(count: Int, endingOn: LocalDate = today) =
        (0 until count).map { endingOn.minusDays(it.toLong()) }.toSet()

    @Test
    fun `an unbroken run up to today counts every day of it`() {
        assertEquals(5, Streaks.current(run(5), today))
    }

    @Test
    fun `today may be empty without breaking it`() {
        // Read at nine in the morning, before the day has had a chance to happen.
        val throughYesterday = run(4, endingOn = today.minusDays(1))

        assertEquals(4, Streaks.current(throughYesterday, today))
    }

    @Test
    fun `an empty yesterday does break it`() {
        // One unfinished day is a day in progress; two is a lapse.
        val throughTheDayBefore = run(4, endingOn = today.minusDays(2))

        assertEquals(0, Streaks.current(throughTheDayBefore, today))
    }

    @Test
    fun `a day in the middle missing ends the count there`() {
        val met = run(3) + today.minusDays(4) + today.minusDays(5)

        // Three back to the hole, and the pair beyond it is a different run.
        assertEquals(3, Streaks.current(met, today))
    }

    @Test
    fun `nothing logged is no streak rather than a crash`() {
        assertEquals(0, Streaks.current(emptySet(), today))
        assertEquals(0, Streaks.best(emptySet()))
    }

    @Test
    fun `the best run is found wherever it sits`() {
        // A six-day run last month, a two-day run now. The current streak is the
        // short one and the best is the old one -- a card showing only "current"
        // would say the reader had never done better than two.
        val old = run(6, endingOn = today.minusDays(30))
        val now = run(2)

        assertEquals(2, Streaks.current(old + now, today))
        assertEquals(6, Streaks.best(old + now))
    }

    @Test
    fun `a single day is a streak of one, not zero`() {
        assertEquals(1, Streaks.current(setOf(today), today))
        assertEquals(1, Streaks.best(setOf(today)))
    }

    @Test
    fun `the best run does not join two runs across a gap`() {
        val first = run(3, endingOn = today.minusDays(10))
        val second = run(3, endingOn = today.minusDays(5))

        // Six dates, two runs of three. Sorted and counted without checking
        // adjacency this reads as six.
        assertEquals(3, Streaks.best(first + second))
    }

    @Test
    fun `a run reaching today is still the best run`() {
        assertEquals(4, Streaks.best(run(4)))
    }
}
