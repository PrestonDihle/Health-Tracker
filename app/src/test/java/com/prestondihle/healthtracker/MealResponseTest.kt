package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.data.DataSourceEnum
import com.prestondihle.healthtracker.data.MealEntry
import com.prestondihle.healthtracker.domain.MealResponses
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scoring what a meal did to the blood sugar, and refusing to when it cannot be
 * said.
 *
 * The refusals carry as much weight as the arithmetic here. Every figure is a
 * comparison of the hours after a meal against the minutes before it, so it is
 * worth exactly what the meal's timestamp is worth -- and a confident number
 * about an hour nobody ate in is worse on screen than a blank.
 */
class MealResponseTest {

    private val mealAt: Instant = Instant.parse("2026-08-20T12:00:00Z")

    /** A trace at [mgDl] every five minutes from [fromMinutes] to [toMinutes]. */
    private fun flat(fromMinutes: Long, toMinutes: Long, mgDl: Int): List<Pair<Instant, Int>> =
        (fromMinutes..toMinutes step 5).map { mealAt.plusSeconds(it * 60) to mgDl }

    /**
     * A trace rising from [baseline] to [peak] by [peakMinutes] and back down by
     * [backMinutes], sampled every five minutes from half an hour before the meal.
     */
    private fun spike(
        baseline: Int,
        peak: Int,
        peakMinutes: Long,
        backMinutes: Long,
        untilMinutes: Long = 180,
    ): List<Pair<Instant, Int>> =
        (-30..untilMinutes step 5).map { minute ->
            val value =
                when {
                    minute <= 0L -> baseline
                    minute <= peakMinutes ->
                        baseline + ((peak - baseline) * minute / peakMinutes).toInt()
                    minute <= backMinutes -> {
                        val fallen = (peak - baseline) * (minute - peakMinutes) /
                            (backMinutes - peakMinutes)
                        peak - fallen.toInt()
                    }
                    else -> baseline
                }
            mealAt.plusSeconds(minute * 60) to value
        }

    /** A five-minute trace around one meal, rising to [peak] at 45 minutes. */
    private fun traceAround(at: Instant, baseline: Int, peak: Int): List<Pair<Instant, Int>> =
        (-30L..180L step 5).map { minute ->
            val value =
                when {
                    minute <= 0L -> baseline
                    minute <= 45L -> baseline + ((peak - baseline) * minute / 45).toInt()
                    minute <= 120L ->
                        peak - ((peak - baseline) * (minute - 45) / 75).toInt()
                    else -> baseline
                }
            at.plusSeconds(minute * 60) to value
        }

    private fun mealEntry(id: Long, at: Instant) =
        MealEntry(
            id = id,
            timestamp = at,
            calories = 500,
            source = DataSourceEnum.HEALTH_CONNECT,
            externalId = "hc-$id",
        )

    @Test
    fun `a spike meal scores higher than a flat one on every figure that matters`() {
        val spikeMeal = MealResponses.score(mealAt, spike(95, 165, 45, 120), hasClockTime = true)
        val flatMeal = MealResponses.score(mealAt, spike(95, 105, 45, 120), hasClockTime = true)

        assertNotNull(spikeMeal)
        assertNotNull(flatMeal)
        // The whole point of the card: these two must not look alike.
        assertTrue(spikeMeal!!.peakRiseMgDl > flatMeal!!.peakRiseMgDl)
        assertTrue(spikeMeal.incrementalAuc > flatMeal.incrementalAuc)
    }

    @Test
    fun `the baseline is the median of the half hour before, not the reading at the meal`() {
        // Five readings at 100 and one compression low at 40, which is exactly the
        // shape a mean would fall for -- it would read the baseline as 90 and
        // report a rise ten points bigger than happened.
        val readings =
            listOf(
                mealAt.minusSeconds(30 * 60) to 100,
                mealAt.minusSeconds(25 * 60) to 100,
                mealAt.minusSeconds(20 * 60) to 40,
                mealAt.minusSeconds(15 * 60) to 100,
                mealAt.minusSeconds(10 * 60) to 100,
                mealAt.minusSeconds(5 * 60) to 100,
            ) + flat(0, 120, 130)

        val response = MealResponses.score(mealAt, readings, hasClockTime = true)

        assertNotNull(response)
        assertEquals(100f, response!!.baselineMgDl, 0.001f)
        assertEquals(30f, response.peakRiseMgDl, 0.001f)
    }

    @Test
    fun `a stamped meal is unscored rather than scored from the stamp`() {
        // The trace is a textbook response. It must still refuse, because the
        // timestamp it would be measured from was never written by anyone.
        assertNull(MealResponses.score(mealAt, spike(95, 165, 45, 120), hasClockTime = false))
    }

    @Test
    fun `a window the sensor barely covered is unscored, not scored small`() {
        // Readings for the first half hour and then nothing: a real peak may have
        // happened at 45 minutes and gone unrecorded. The area computed from what
        // is left is a smaller number about a shorter window, which on screen is
        // indistinguishable from a flatter meal.
        val sparse = flat(-30, 0, 95) + flat(0, 30, 120)

        assertNull(MealResponses.score(mealAt, sparse, hasClockTime = true))
    }

    @Test
    fun `no readings before the meal means no baseline and so no response`() {
        // Starting at five minutes past, so there is genuinely nothing at or
        // before the meal to measure the rise against.
        assertNull(MealResponses.score(mealAt, flat(5, 120, 120), hasClockTime = true))
    }

    @Test
    fun `a reading taken exactly at the meal is the baseline`() {
        // The level the meal started from, which is what a baseline is. It is also
        // the first point of the trapezoid -- the two uses are the same reading
        // doing the same job, not a figure counted twice.
        val response =
            MealResponses.score(mealAt, flat(0, 120, 110), hasClockTime = true)

        assertNotNull(response)
        assertEquals(110f, response!!.baselineMgDl, 0.001f)
        assertEquals(0f, response.incrementalAuc, 0.001f)
    }

    @Test
    fun `the area counts only what stood above the baseline`() {
        // A trace that sits exactly at baseline for the whole window added nothing,
        // however high the baseline itself was.
        val level = MealResponses.score(mealAt, spike(140, 140, 45, 120), hasClockTime = true)

        assertNotNull(level)
        assertEquals(0f, level!!.incrementalAuc, 0.001f)
        assertEquals(0f, level.peakRiseMgDl, 0.001f)
    }

    @Test
    fun `eating high does not outscore the same meal eaten low`() {
        // The same thirty-point rise from two different starting levels. An
        // absolute area would score the second far higher for nothing the meal did.
        val fromNinety = MealResponses.score(mealAt, spike(90, 120, 45, 120), hasClockTime = true)
        val fromOneForty =
            MealResponses.score(mealAt, spike(140, 170, 45, 120), hasClockTime = true)

        assertNotNull(fromNinety)
        assertNotNull(fromOneForty)
        assertEquals(fromNinety!!.incrementalAuc, fromOneForty!!.incrementalAuc, 1f)
        assertEquals(fromNinety.peakRiseMgDl, fromOneForty.peakRiseMgDl, 0.001f)
    }

    @Test
    fun `a dip below baseline does not cancel out the rise before it`() {
        // Up thirty for an hour, then under baseline for the second hour. Signed
        // area would net these off and report a meal that did nothing.
        val readings =
            flat(-30, 0, 100) +
                (5..60 step 5).map { mealAt.plusSeconds(it * 60L) to 130 } +
                (65..120 step 5).map { mealAt.plusSeconds(it * 60L) to 70 }

        val response = MealResponses.score(mealAt, readings, hasClockTime = true)

        assertNotNull(response)
        assertTrue("area was ${response!!.incrementalAuc}", response.incrementalAuc > 1_000f)
    }

    @Test
    fun `the return to baseline is found after the peak and capped at three hours`() {
        val response = MealResponses.score(mealAt, spike(95, 165, 45, 120), hasClockTime = true)

        assertNotNull(response)
        assertEquals(Duration.ofMinutes(45), response!!.timeToPeak)
        assertEquals(Duration.ofMinutes(120), response.returnToBaseline)
    }

    @Test
    fun `a trace still elevated at the cap reports no return, and says how far it looked`() {
        // Never comes back inside three hours. `returnToBaseline` is null, and
        // `observedFor` is what stops that null being read as a dead sensor.
        val stubborn = flat(-30, 0, 95) + flat(0, 180, 150)

        val response = MealResponses.score(mealAt, stubborn, hasClockTime = true)

        assertNotNull(response)
        assertNull(response!!.returnToBaseline)
        assertEquals(MealResponses.RETURN_CAP, response.observedFor)
    }

    @Test
    fun `the ranking puts the biggest area first and leaves out what it could not score`() {
        val quiet = mealAt
        val big = mealAt.plusSeconds(6 * 3600)
        val stamped = mealAt.plusSeconds(12 * 3600)

        // One modest meal, one large one, and one whose time is a stamp. The trace
        // covers all three windows; only the stamp is the reason the third fails.
        val readings =
            traceAround(quiet, baseline = 95, peak = 110) +
                traceAround(big, baseline = 95, peak = 170) +
                traceAround(stamped, baseline = 95, peak = 160)

        val meals =
            listOf(
                mealEntry(1, quiet),
                mealEntry(2, big),
                mealEntry(3, stamped),
            )

        val ranked =
            MealResponses.rank(
                meals = meals,
                readings = readings,
                hasClockTime = { it.id != 3L },
                limit = 5,
            )

        assertEquals(2, ranked.size)
        assertEquals(2L, ranked[0].meal.id)
        assertEquals(1L, ranked[1].meal.id)
    }

    @Test
    fun `the ranking honours its limit`() {
        val meals = (1..5).map { mealEntry(it.toLong(), mealAt.plusSeconds(it * 6L * 3600)) }
        val readings =
            meals.flatMap { traceAround(it.timestamp, baseline = 95, peak = 100 + it.id.toInt() * 10) }

        val ranked =
            MealResponses.rank(meals, readings, hasClockTime = { true }, limit = 2)

        assertEquals(2, ranked.size)
        // Biggest first, so the two largest survive the cut.
        assertEquals(5L, ranked[0].meal.id)
        assertEquals(4L, ranked[1].meal.id)
    }

    @Test
    fun `a sensor that stopped early is told apart from a trace that stayed high`() {
        // Covered enough of the two-hour window to be scored, then nothing. The
        // response is real; the silence past it is not evidence of anything, and
        // `observedFor` is what lets the screen avoid claiming otherwise.
        val stopped = flat(-30, 0, 95) + flat(0, 125, 150)

        val response = MealResponses.score(mealAt, stopped, hasClockTime = true)

        assertNotNull(response)
        assertNull(response!!.returnToBaseline)
        assertTrue(response.observedFor < MealResponses.RETURN_CAP)
    }
}
