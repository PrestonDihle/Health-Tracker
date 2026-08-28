package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.data.ExerciseSet
import com.prestondihle.healthtracker.data.HealthDaySnapshot
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.data.UserSettings
import com.prestondihle.healthtracker.ui.trends.TrendsRange
import com.prestondihle.healthtracker.ui.trends.TrendsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

/**
 * What a chart slot means once a slot has stopped being one day.
 *
 * The 180- and 365-day ranges fold seven days into one point, and the whole
 * question is what that point is. A sum and a mean look equally plausible in a
 * screenshot and disagree by a factor of seven, in a place where the reader has
 * a daily goal line drawn beside the bar to compare it against -- so the wrong
 * one does not look wrong, it looks like a week of extraordinary effort sitting
 * above a target that has quietly become meaningless.
 *
 * The two cases worth the most here are the partial weeks. A year of days back
 * from today starts and ends mid-week whatever day it is run on, and summed,
 * those two buckets are short by however much of the week is missing -- so a
 * year view would open on a cliff at its right-hand edge every day except one,
 * which is the edge the reader is actually looking at.
 */
class TrendsBucketsTest {

    private val zone = ZoneId.of("UTC")

    /** A Wednesday, so every range built from it starts and ends mid-week. */
    private val wednesday = LocalDate.of(2026, 3, 4)

    private fun stateOver(
        range: TrendsRange,
        endDate: LocalDate,
        snapshots: List<HealthDaySnapshot> = emptyList(),
        exerciseSets: List<ExerciseSet> = emptyList(),
        weekStartsOn: DayOfWeek = DayOfWeek.MONDAY,
    ) = TrendsUiState(
        range = range,
        startDate = endDate.minusDays(range.days - 1),
        endDate = endDate,
        snapshots = snapshots,
        exerciseSets = exerciseSets,
        settings = UserSettings(weekStartsOn = weekStartsOn),
        zoneId = zone,
    )

    /** A snapshot's own sync time is irrelevant here; nothing under test reads it. */
    private fun snapshot(
        date: LocalDate,
        steps: Int? = null,
        proteinGrams: Float? = null,
        carbGrams: Float? = null,
        fatGrams: Float? = null,
        dietaryCalories: Int? = null,
        totalCalories: Int? = null,
    ) = HealthDaySnapshot(
        date = date,
        steps = steps,
        proteinGrams = proteinGrams,
        carbGrams = carbGrams,
        fatGrams = fatGrams,
        dietaryCalories = dietaryCalories,
        totalCalories = totalCalories,
        syncedAt = date.atStartOfDay(zone).toInstant(),
    )

    private fun steps(date: LocalDate, count: Int) = snapshot(date, steps = count)

    @Test
    fun `a daily range still draws one slot per day`() {
        val state = stateOver(TrendsRange.MONTH, wednesday)

        assertEquals(30, state.buckets.size)
        assertEquals(state.days, state.buckets)
    }

    @Test
    fun `a weekly range draws one slot per week, named by its first day`() {
        val state = stateOver(TrendsRange.YEAR, wednesday)

        // 365 days back from a Wednesday covers 53 Mondays: 52 whole weeks plus
        // the part-weeks either end.
        assertEquals(53, state.buckets.size)
        assertTrue(state.buckets.all { it.dayOfWeek == DayOfWeek.MONDAY })
        assertEquals(state.buckets.sorted(), state.buckets)
    }

    @Test
    fun `a week is the mean of its days, not their sum`() {
        // Monday to Sunday, one full week inside the range, 10k steps every day.
        val week = (0..6).map { wednesday.minusDays(it.toLong() + 2) }
        val state = stateOver(TrendsRange.YEAR, wednesday, snapshots = week.map { steps(it, 10_000) })

        val bucket = state.snapshotSeries { it.steps?.toFloat() }
            .single { it.date == LocalDate.of(2026, 2, 23) }

        // 10,000 -- the rate that week ran at. Summed it would be 70,000, which
        // on a chart carrying a 10,000-step goal line is seven times its target.
        assertEquals(10_000f, bucket.value!!, 0.01f)
    }

    @Test
    fun `a week measured on three days is the mean of those three, not of seven`() {
        // The watch synced Monday, Tuesday and Wednesday and then stopped.
        val monday = LocalDate.of(2026, 2, 23)
        val state =
            stateOver(
                TrendsRange.YEAR,
                wednesday,
                snapshots =
                    listOf(
                        steps(monday, 9_000),
                        steps(monday.plusDays(1), 12_000),
                        steps(monday.plusDays(2), 15_000),
                    ),
            )

        val bucket = state.snapshotSeries { it.steps?.toFloat() }.single { it.date == monday }

        // 12,000: the days that hold a reading. Averaged over seven it would
        // read 5,143 and draw four days of illness that never happened.
        assertEquals(12_000f, bucket.value!!, 0.01f)
    }

    @Test
    fun `the newest week is not a collapse just because it is partway through`() {
        // A year ending Wednesday: the last bucket holds Monday, Tuesday and
        // Wednesday only. Walked at exactly the same rate as the week before it,
        // it has to draw at exactly the same height.
        val lastMonday = LocalDate.of(2026, 3, 2)
        val previousMonday = lastMonday.minusDays(7)
        val everyDay =
            ((0..6).map { previousMonday.plusDays(it.toLong()) } +
                    (0..2).map { lastMonday.plusDays(it.toLong()) })
                .map { steps(it, 11_000) }
        val state = stateOver(TrendsRange.YEAR, wednesday, snapshots = everyDay)

        val series = state.snapshotSeries { it.steps?.toFloat() }
        val newest = series.single { it.date == lastMonday }
        val previous = series.single { it.date == previousMonday }

        assertEquals(previous.value!!, newest.value!!, 0.01f)
        assertEquals(11_000f, newest.value!!, 0.01f)
    }

    @Test
    fun `a week with nothing recorded is null rather than zero`() {
        val state = stateOver(TrendsRange.SIX_MONTHS, wednesday)

        val series = state.snapshotSeries { it.steps?.toFloat() }

        assertTrue(series.isNotEmpty())
        // Null breaks the line; zero would draw a fortnight spent motionless.
        assertTrue(series.all { it.value == null })
        assertNull(series.first().value)
    }

    @Test
    fun `the week boundary follows the setting rather than the calendar`() {
        val sundayStart =
            stateOver(TrendsRange.YEAR, wednesday, weekStartsOn = DayOfWeek.SUNDAY)

        assertTrue(sundayStart.buckets.all { it.dayOfWeek == DayOfWeek.SUNDAY })
    }

    @Test
    fun `a day with no sets counts as a zero in the week's mean`() {
        // Pushups are logged by doing them, so a day with no rows is a day of
        // none rather than a day unknown -- and the week's figure is reps per
        // day, not reps per day trained.
        val monday = LocalDate.of(2026, 2, 23)
        val state =
            stateOver(
                TrendsRange.YEAR,
                wednesday,
                exerciseSets =
                    listOf(
                        ExerciseSet(
                            timestamp = monday.atStartOfDay(zone).toInstant(),
                            movement = MovementType.PUSHUP,
                            reps = 70,
                        )
                    ),
            )

        val bucket = state.repSeries(MovementType.PUSHUP).single { it.date == monday }

        // 70 over seven days, not 70 over the one day they were done.
        assertEquals(10f, bucket.value!!, 0.01f)
    }

    @Test
    fun `macros average over the days that recorded food`() {
        // Two days logged out of the week. The mean is what those two days ate;
        // spread across seven it would report a week of eating a third as much,
        // on a card whose bars are read against a daily calorie target.
        val monday = LocalDate.of(2026, 2, 23)
        val state =
            stateOver(
                TrendsRange.YEAR,
                wednesday,
                snapshots =
                    listOf(
                        snapshot(monday, proteinGrams = 100f, carbGrams = 200f, fatGrams = 50f),
                        snapshot(
                            monday.plusDays(1),
                            proteinGrams = 200f,
                            carbGrams = 100f,
                            fatGrams = 30f,
                        ),
                        // Synced, but nothing eaten was recorded: not a fast, no evidence.
                        snapshot(monday.plusDays(2), steps = 8_000),
                    ),
            )

        val bar = state.macroBars.single { it.date == monday }

        assertEquals(150f * 4f, bar.segments[0], 0.01f)
        assertEquals(150f * 4f, bar.segments[1], 0.01f)
        assertEquals(40f * 9f, bar.segments[2], 0.01f)
    }

    @Test
    fun `net calories needs both halves, unlike the day card`() {
        val monday = LocalDate.of(2026, 2, 23)
        val state =
            stateOver(
                TrendsRange.MONTH,
                wednesday,
                snapshots =
                    listOf(
                        // Both recorded: a real 400-calorie deficit.
                        snapshot(monday, dietaryCalories = 2_000, totalCalories = 2_400),
                        // Burn synced, nothing logged eaten. The day card would
                        // call this a 2,400 deficit, and on today so far it is
                        // right to -- a fasted morning has eaten nothing. On a
                        // finished day it means the day was not tracked, and a
                        // fast that never happened is the worst thing this chart
                        // could draw.
                        snapshot(monday.plusDays(1), totalCalories = 2_400),
                        // Food logged, watch not synced: unknown either way.
                        snapshot(monday.plusDays(2), dietaryCalories = 2_000),
                    ),
            )

        val series = state.netCalorieSeries.associate { it.date to it.value }

        assertEquals(-400f, series[monday]!!, 0.01f)
        assertNull(series[monday.plusDays(1)])
        assertNull(series[monday.plusDays(2)])
    }

    @Test
    fun `the trailing average is absent once a slot is a week`() {
        val state = stateOver(TrendsRange.YEAR, wednesday)

        // Points a week apart put every window's own point alone in it, so the
        // average would refuse them one at a time anyway -- returning nothing
        // outright is what stops the chart keying a line it does not draw.
        assertTrue(state.trailingAverage(state.snapshotSeries { it.steps?.toFloat() }).isEmpty())
    }

    @Test
    fun `the trailing average keeps the raw series' slots`() {
        val state =
            stateOver(
                TrendsRange.MONTH,
                wednesday,
                snapshots = (0L until 20L).map { steps(wednesday.minusDays(it), 9_000 + it.toInt()) },
            )

        val readings = state.snapshotSeries { it.steps?.toFloat() }
        val averaged = state.trailingAverage(readings)

        // One slot each, in step. The chart maps a point to an x by its index,
        // so a shorter list would draw the average across the full width with
        // every point of it over the wrong day.
        assertEquals(readings.size, averaged.size)
        assertEquals(readings.map { it.date }, averaged.map { it.date })
        assertTrue(averaged.any { it.value != null })
    }

    @Test
    fun `the live-read cards stop at a quarter and say the window they read`() {
        // The runs chart costs a raw heart-rate read per session and the meal
        // ranking reads every glucose sample in its window, so neither follows
        // the two long chips. What matters on screen is that they stop claiming
        // to: a card drawing ninety days under a label saying 365 is the same
        // silent wrongness a clipped goal line was.
        assertEquals(90L, TrendsRange.YEAR.cappedDays)
        assertEquals(90L, TrendsRange.SIX_MONTHS.cappedDays)
        assertEquals("90 days", TrendsRange.YEAR.effectiveLabel)

        // Every range that existed before is untouched, window and label alike.
        listOf(
                TrendsRange.WEEK,
                TrendsRange.TWO_WEEKS,
                TrendsRange.MONTH,
                TrendsRange.THREE_MONTHS,
            )
            .forEach {
                assertEquals(it.days, it.cappedDays)
                assertEquals(it.label, it.effectiveLabel)
            }
    }
}
