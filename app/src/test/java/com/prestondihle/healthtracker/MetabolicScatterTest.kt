package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.data.HealthDaySnapshot
import com.prestondihle.healthtracker.data.WeightEntry
import com.prestondihle.healthtracker.domain.MovingAverage
import com.prestondihle.healthtracker.ui.trends.MetabolicMetric
import com.prestondihle.healthtracker.ui.trends.MetabolicSource
import com.prestondihle.healthtracker.ui.trends.ScatterBucket
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning rows into points, which has three ways of being wrong that all still
 * draw a plausible chart.
 *
 * The sign of a loss, a bucket closed over a gap in the weighing, and a week
 * that is not seven days. None of them throws, none of them looks like an error
 * on screen, and every one of them moves the fitted line and therefore the
 * maintenance figure printed under it.
 */
class MetabolicScatterTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    /** A Thursday, so nothing here accidentally depends on a week boundary. */
    private val start: LocalDate = LocalDate.of(2026, 1, 1)

    private fun snapshot(
        date: LocalDate,
        eaten: Int? = null,
        burned: Int? = null,
        weightKg: Float? = null,
    ) =
        HealthDaySnapshot(
            date = date,
            dietaryCalories = eaten,
            totalCalories = burned,
            weightKg = weightKg,
            syncedAt = Instant.EPOCH,
        )

    /**
     * How much weight history the flow reads before the window it draws.
     *
     * The smoothing is trailing and emits nothing until it has
     * `MovingAverage.MIN_READINGS` behind it, so a window whose weight data
     * starts on its own first day has no smoothed value there -- and the first
     * bucket, which needs one at each end, is dropped. The real flow reads a
     * window's worth of weight earlier for exactly this, and the fixtures here
     * do the same so they are testing the bucketing rather than the warm-up.
     */
    private val leadIn = MovingAverage.WINDOW_DAYS

    /**
     * A month of days, weighed every morning and eating a fixed amount.
     *
     * The weight falls by a flat 30 g a day, which after the seven-day smoothing
     * is still 30 g a day -- a trailing mean of a straight line is that line,
     * lagged. That is what makes this fixture readable: any departure from 30 in
     * the assertions below is the bucketing and not the filter.
     */
    private fun steadyLoss(
        days: Int = 28,
        gramsPerDay: Float = 30f,
        eaten: Int = 2_000,
        withLeadIn: Boolean = true,
    ): MetabolicSource {
        val first = if (withLeadIn) start.minusDays(leadIn) else start
        val total = days + (if (withLeadIn) leadIn.toInt() else 0)
        val snapshots =
            (0 until total).map { i ->
                snapshot(
                    date = first.plusDays(i.toLong()),
                    eaten = eaten,
                    burned = 2_600,
                    weightKg = 80f - (gramsPerDay / 1000f) * i,
                )
            }
        return MetabolicSource(
            snapshots = snapshots,
            weights = emptyList(),
            glucose = emptyList(),
            zoneId = zone,
        )
    }

    /**
     * Why the flow reads weight further back than it plots.
     *
     * Without the lead-in the earliest bucket has no smoothed weight at its left
     * edge and is dropped -- silently, and looking exactly like a window that
     * simply starts later. That is a whole week of evidence missing from a fit
     * with nothing on screen to say so, which on the shortest ranges is a
     * material share of the points.
     */
    @Test
    fun `without a lead-in the earliest bucket is lost`() {
        fun bucketsFrom(withLeadIn: Boolean) =
            steadyLoss(days = 28, withLeadIn = withLeadIn)
                .scatter(
                    xMetric = MetabolicMetric.CALORIES_EATEN,
                    yMetric = MetabolicMetric.WEIGHT_LOST,
                    bucket = ScatterBucket.WEEKLY,
                    start = start,
                    end = start.plusDays(27),
                )

        assertEquals(4, bucketsFrom(withLeadIn = true).size)
        assertEquals(3, bucketsFrom(withLeadIn = false).size)
    }

    @Test
    fun `losing weight is a positive number`() {
        // The axis says "weight lost", so down on the scale has to be up on the
        // chart. Inverted, every point lands in the wrong quadrant and the fitted
        // line's slope flips -- which the maintenance refusal would then catch as
        // "the wrong way", turning a working card into a permanently silent one.
        val points =
            steadyLoss()
                .scatter(
                    xMetric = MetabolicMetric.CALORIES_EATEN,
                    yMetric = MetabolicMetric.WEIGHT_LOST,
                    bucket = ScatterBucket.WEEKLY,
                    start = start,
                    end = start.plusDays(27),
                )

        assertTrue(points.isNotEmpty())
        assertTrue("expected positive, got ${points.map { it.y }}", points.all { it.y > 0f })
    }

    @Test
    fun `gaining weight is a negative number`() {
        val points =
            steadyLoss(gramsPerDay = -25f)
                .scatter(
                    xMetric = MetabolicMetric.CALORIES_EATEN,
                    yMetric = MetabolicMetric.WEIGHT_LOST,
                    bucket = ScatterBucket.WEEKLY,
                    start = start,
                    end = start.plusDays(27),
                )

        assertTrue(points.all { it.y < 0f })
    }

    @Test
    fun `a weekly bucket reports grams per day, not grams per week`() {
        // The two differ by a factor of seven, and both look like a plausible
        // rate of loss on an unlabelled axis. Per day is what lets the daily and
        // weekly views be read against each other at all.
        val points =
            steadyLoss(gramsPerDay = 30f)
                .scatter(
                    xMetric = MetabolicMetric.CALORIES_EATEN,
                    yMetric = MetabolicMetric.WEIGHT_LOST,
                    bucket = ScatterBucket.WEEKLY,
                    start = start,
                    end = start.plusDays(27),
                )

        // Every bucket is the same steady rate, so any of them will do.
        points.forEach { assertEquals(30f, it.y, 1f) }
    }

    @Test
    fun `a week groups seven days`() {
        val points =
            steadyLoss(days = 28)
                .scatter(
                    xMetric = MetabolicMetric.CALORIES_EATEN,
                    yMetric = MetabolicMetric.WEIGHT_LOST,
                    bucket = ScatterBucket.WEEKLY,
                    start = start,
                    end = start.plusDays(27),
                )

        assertEquals(4, points.size)
        assertEquals(start, points.first().date)
        assertEquals(start.plusDays(7), points[1].date)
    }

    @Test
    fun `a bucket with no weight readings is dropped, not plotted at zero`() {
        // The middle week has its calories and no weight at all. Plotted at zero
        // it would sit on the y = 0 rule -- a week that "held steady" and never
        // happened -- and drag the fitted line flat, putting the crossing
        // wherever that week's intake landed.
        //
        // Built with the same lead-in the flow reads, so the only bucket missing
        // here is the one this test is about.
        val first = start.minusDays(leadIn)
        val gapWeek = start.plusDays(7)
        val snapshots =
            (0 until (21 + leadIn.toInt())).map { i ->
                val date = first.plusDays(i.toLong())
                val inGap = !date.isBefore(gapWeek) && date.isBefore(gapWeek.plusDays(7))
                snapshot(
                    date = date,
                    eaten = 2_000,
                    burned = 2_600,
                    weightKg = if (inGap) null else 80f - 0.03f * i,
                )
            }
        val source = MetabolicSource(snapshots, emptyList(), emptyList(), zone)

        val points =
            source.scatter(
                xMetric = MetabolicMetric.CALORIES_EATEN,
                yMetric = MetabolicMetric.WEIGHT_LOST,
                bucket = ScatterBucket.WEEKLY,
                start = start,
                end = start.plusDays(20),
            )

        // The unweighed week is absent rather than present at zero, and the week
        // before it -- fully weighed -- survives.
        assertTrue(points.none { it.date == gapWeek })
        assertTrue(points.any { it.date == start })
        assertTrue(points.none { it.y == 0f })

        // The week *after* the gap is dropped too, and that is right rather than
        // collateral damage. Its left edge is the first morning back on the
        // scale, where the trailing window holds one reading -- below the
        // smoothing's own floor, so there is no smoothed weight to measure from.
        // Closing the bucket over the nearest available day instead would
        // attribute a fortnight of loss to one week, which is the single largest
        // way this chart could lie.
        assertTrue(points.none { it.date == start.plusDays(14) })
    }

    @Test
    fun `net calories needs both halves`() {
        // A day that logged no food is not a day of enormous deficit. This is the
        // net-calorie trend's rule, and it has to hold here too or the two cards
        // disagree about the same day.
        val source =
            MetabolicSource(
                snapshots =
                    listOf(
                        snapshot(start, eaten = 2_000, burned = 2_600),
                        snapshot(start.plusDays(1), eaten = null, burned = 2_600),
                        snapshot(start.plusDays(2), eaten = 2_000, burned = null),
                    ),
                weights = emptyList(),
                glucose = emptyList(),
                zoneId = zone,
            )

        assertEquals(-600f, source.daily(MetabolicMetric.NET_CALORIES, start))
        assertNull(source.daily(MetabolicMetric.NET_CALORIES, start.plusDays(1)))
        assertNull(source.daily(MetabolicMetric.NET_CALORIES, start.plusDays(2)))
    }

    @Test
    fun `a hand-typed weight beats the synced one on the same day`() {
        // TrendsUiState.weightByDay's rule. Two derivations of one morning is how
        // two cards come to disagree about it, and here it would move the y value
        // of a whole bucket.
        val first = start.minusDays(leadIn)
        val span = 14 + leadIn.toInt()
        val snapshots =
            (0 until span).map { i ->
                snapshot(first.plusDays(i.toLong()), eaten = 2_000, weightKg = 90f)
            }
        val manual = (0 until span).map { WeightEntry(first.plusDays(it.toLong()), 80f - 0.03f * it) }
        val source = MetabolicSource(snapshots, manual, emptyList(), zone)

        // The synced column is a flat 90 kg; the manual one falls steadily. A
        // reading of zero loss would mean the sync had won.
        val points =
            source.scatter(
                xMetric = MetabolicMetric.CALORIES_EATEN,
                yMetric = MetabolicMetric.WEIGHT_LOST,
                bucket = ScatterBucket.WEEKLY,
                start = start,
                end = start.plusDays(13),
            )

        assertTrue(points.isNotEmpty())
        assertTrue("got ${points.map { it.y }}", points.all { it.y > 20f })
    }

    @Test
    fun `a metric with nothing recorded produces no points at all`() {
        // Rather than a cloud along one axis. Glucose is the case: a reader with
        // no monitor picks it from the menu and gets an empty plot with a
        // sentence, not a column of points at zero.
        val source = MetabolicSource(
            snapshots =
                (0 until 14 + leadIn.toInt()).map {
                    snapshot(start.minusDays(leadIn).plusDays(it.toLong()), weightKg = 80f)
                },
            weights = emptyList(),
            glucose = emptyList(),
            zoneId = zone,
        )

        val points =
            source.scatter(
                xMetric = MetabolicMetric.AVG_GLUCOSE,
                yMetric = MetabolicMetric.WEIGHT_LOST,
                bucket = ScatterBucket.WEEKLY,
                start = start,
                end = start.plusDays(13),
            )

        assertTrue(points.isEmpty())
    }
}
