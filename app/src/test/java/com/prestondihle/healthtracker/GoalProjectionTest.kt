package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.GoalProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.LocalDate
import org.junit.Test

/**
 * When the app is allowed to name a date, and when it must not.
 *
 * The refusals matter more than the arithmetic here, and it is worth being
 * explicit about why: an ETA is the most confident-sounding thing this app
 * prints -- a specific weight on a specific dated day -- and it is fitted to the
 * shakiest input any chart here carries, a month of a measurement that moves a
 * pound and a half on water alone. A wrong slope does not look wrong. It looks
 * like a plan.
 */
class GoalProjectionTest {

    private val today = LocalDate.of(2026, 3, 4)

    /** A month of weighing on a straight line, [perDay] lb a day, ending today. */
    private fun losing(from: Float, perDay: Float, count: Int = 30) =
        (0 until count).map { back ->
            today.minusDays(back.toLong()) to (from + perDay * back)
        }

    @Test
    fun `a steady loss reaches the goal on a date the arithmetic agrees with`() {
        // 200 lb today, falling half a pound a day, goal 185. Thirty days out.
        val eta =
            GoalProjection.forGoal(
                readings = losing(from = 200f, perDay = 0.5f),
                today = today,
                goal = 185f,
            )!!

        assertEquals(185f, eta.target, 0.01f)
        assertEquals(200f, eta.from, 0.05f)
        assertTrue(eta.perDay < 0f)
        assertEquals(today.plusDays(30), eta.reachedOn)
    }

    @Test
    fun `the next waypoint is aimed at before the goal beyond it`() {
        // The reader is told about the rung they are approaching, not the one at
        // the top of the ladder -- which is both sooner and the thing they can
        // act on.
        val eta =
            GoalProjection.forGoal(
                readings = losing(from = 200f, perDay = 0.5f),
                today = today,
                goal = 180f,
                waypoints = listOf(195f, 190f, 185f),
            )!!

        assertEquals(195f, eta.target, 0.01f)
        assertEquals(today.plusDays(10), eta.reachedOn)
    }

    @Test
    fun `a waypoint already passed is not offered again`() {
        val eta =
            GoalProjection.forGoal(
                readings = losing(from = 192f, perDay = 0.5f),
                today = today,
                goal = 180f,
                // 195 is behind them now.
                waypoints = listOf(195f, 190f, 185f),
            )!!

        assertEquals(190f, eta.target, 0.01f)
    }

    @Test
    fun `a slope pointing away from the goal says nothing`() {
        // Two pounds up over the month, with a goal below. This pace never
        // arrives, and the honest answer to "when at this pace" is silence
        // rather than a date somewhere in the past.
        val gaining = losing(from = 200f, perDay = -0.07f)

        assertNull(GoalProjection.forGoal(gaining, today, goal = 185f))
    }

    @Test
    fun `too few readings says nothing`() {
        // Four mornings in the window. A line through four mornings has whatever
        // slope the noise gave it.
        val sparse =
            listOf(
                today.minusDays(24) to 200f,
                today.minusDays(16) to 199f,
                today.minusDays(8) to 198f,
                today to 197f,
            )

        assertNull(GoalProjection.forGoal(sparse, today, goal = 185f))
        // A fifth reading is the whole difference.
        assertTrue(
            GoalProjection.forGoal(sparse + (today.minusDays(4) to 197.5f), today, goal = 185f) !=
                null
        )
    }

    @Test
    fun `a slope too slight to arrive inside two years says nothing`() {
        // Losing about an ounce a week. Divide by a number that near zero and the
        // date lands in the next decade -- precise and meaningless at once.
        val crawling = losing(from = 200f, perDay = 0.002f)

        assertNull(GoalProjection.forGoal(crawling, today, goal = 185f))
    }

    @Test
    fun `no goal means nothing to be en route to`() {
        assertNull(GoalProjection.forGoal(losing(200f, 0.5f), today, goal = null))
    }

    @Test
    fun `readings older than the fit window are not in the fit`() {
        // A steep loss last winter, flat for the last month. The fit must see the
        // flat month only, or it would promise a rate that stopped weeks ago.
        val ancient = (60..120).map { today.minusDays(it.toLong()) to (260f - (120 - it) * 0.9f) }
        val flat = (0 until 30).map { today.minusDays(it.toLong()) to 200f }

        assertNull(GoalProjection.forGoal(ancient + flat, today, goal = 185f))
    }

    @Test
    fun `the segment starts from the fitted value, not from the last morning`() {
        // A clean line, with today's reading three pounds high on water. Started
        // at the reading, the drawn segment's first point would sit above every
        // other point of the trend it claims to extend.
        val noisyToday = losing(from = 200f, perDay = 0.5f).drop(1) + (today to 203f)

        val eta = GoalProjection.forGoal(noisyToday, today, goal = 185f)!!

        assertTrue(eta.from < 202f)
        assertTrue(eta.from > 199f)
    }
}
