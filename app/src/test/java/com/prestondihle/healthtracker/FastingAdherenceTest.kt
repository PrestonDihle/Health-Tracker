package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.data.FastingPlanDay
import com.prestondihle.healthtracker.data.FastingSession
import com.prestondihle.healthtracker.data.FastingType
import com.prestondihle.healthtracker.data.PlannedExtendedFast
import com.prestondihle.healthtracker.domain.FastingAdherence
import com.prestondihle.healthtracker.domain.Interval
import com.prestondihle.healthtracker.domain.totalSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure JVM tests for the adherence maths. Fixed to a Monday so the weekday
 * lookup is unambiguous, and to UTC so the results do not shift with the
 * machine's zone.
 */
class FastingAdherenceTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    /** 2024-01-01 is a Monday. */
    private val monday: LocalDate = LocalDate.of(2024, 1, 1)

    private val weekStart = monday.atStartOfDay(zone).toInstant()
    private val weekEnd = monday.plusWeeks(1).atStartOfDay(zone).toInstant()

    /** Eating noon to 20:00 every day, so 16 fasting hours per day. */
    private fun sixteenEightPlan() =
        DayOfWeek.values().toList().map {
            FastingPlanDay(it, LocalTime.of(12, 0), LocalTime.of(20, 0), hasFeedingWindow = true)
        }

    private fun instantAt(dayOffset: Long, hour: Int) =
        monday.plusDays(dayOffset).atTime(hour, 0).atZone(zone).toInstant()

    @Test
    fun `no fasting logged scores zero`() {
        val result =
            FastingAdherence.score(
                plan = sixteenEightPlan(),
                extendedFasts = emptyList(),
                sessions = emptyList(),
                weekStart = weekStart,
                weekEnd = weekEnd,
                now = instantAt(1, 0),
                zoneId = zone,
            )

        assertEquals(0, result.score)
        assertEquals(0L, result.fastedSeconds)
    }

    @Test
    fun `fasting the whole planned window scores one hundred`() {
        // Monday's planned fast is 00:00-12:00 and 20:00-24:00.
        val sessions =
            listOf(
                FastingSession(
                    startInstant = instantAt(0, 0),
                    goalDurationMinutes = 16 * 60,
                    endInstant = instantAt(0, 12),
                    type = FastingType.CUSTOM,
                )
            )

        val result =
            FastingAdherence.score(
                plan = sixteenEightPlan(),
                extendedFasts = emptyList(),
                sessions = sessions,
                weekStart = weekStart,
                weekEnd = weekEnd,
                // Evaluate only up to noon Monday, when 12h was planned and 12h fasted.
                now = instantAt(0, 12),
                zoneId = zone,
            )

        assertEquals(100, result.score)
    }

    @Test
    fun `eating during a planned fast halves the score`() {
        // Planned fast midnight to noon; fasted only the first six hours.
        val sessions =
            listOf(
                FastingSession(
                    startInstant = instantAt(0, 0),
                    goalDurationMinutes = 16 * 60,
                    endInstant = instantAt(0, 6),
                    type = FastingType.CUSTOM,
                )
            )

        val result =
            FastingAdherence.score(
                plan = sixteenEightPlan(),
                extendedFasts = emptyList(),
                sessions = sessions,
                weekStart = weekStart,
                weekEnd = weekEnd,
                now = instantAt(0, 12),
                zoneId = zone,
            )

        assertEquals(50, result.score)
    }

    @Test
    fun `future planned time is not counted against the score`() {
        // Evaluated at 06:00 Monday: only six planned fasting hours have elapsed,
        // all of them fasted. The rest of the week must not drag this down.
        val sessions =
            listOf(
                FastingSession(
                    startInstant = instantAt(0, 0),
                    goalDurationMinutes = 16 * 60,
                    endInstant = null,
                    type = FastingType.CUSTOM,
                )
            )

        val result =
            FastingAdherence.score(
                plan = sixteenEightPlan(),
                extendedFasts = emptyList(),
                sessions = sessions,
                weekStart = weekStart,
                weekEnd = weekEnd,
                now = instantAt(0, 6),
                zoneId = zone,
            )

        assertEquals(100, result.score)
        assertEquals(6 * 3600L, result.plannedSeconds)
    }

    @Test
    fun `a no-eating day is a full 24 hours of planned fast`() {
        val plan =
            sixteenEightPlan().map {
                if (it.dayOfWeek == DayOfWeek.MONDAY) it.copy(hasFeedingWindow = false) else it
            }

        val planned =
            FastingAdherence.plannedFastIntervals(
                plan = plan,
                extendedFasts = emptyList(),
                window = Interval(weekStart, instantAt(1, 0)),
                zoneId = zone,
            )

        assertEquals(24 * 3600L, planned.totalSeconds)
    }

    @Test
    fun `a day missing from the plan is not scored`() {
        // Defensive: a gap must not fabricate a 24h fast that was never planned.
        val plan = sixteenEightPlan().filterNot { it.dayOfWeek == DayOfWeek.MONDAY }

        val planned =
            FastingAdherence.plannedFastIntervals(
                plan = plan,
                extendedFasts = emptyList(),
                window = Interval(weekStart, instantAt(1, 0)),
                zoneId = zone,
            )

        assertEquals(0L, planned.totalSeconds)
    }

    @Test
    fun `goal length is taken from the planned fast containing now`() {
        // 06:00 Monday sits inside the 20:00 Sun to 12:00 Mon planned fast,
        // which runs 16 hours.
        val minutes =
            FastingAdherence.plannedGoalMinutesAt(
                plan = sixteenEightPlan(),
                extendedFasts = emptyList(),
                now = instantAt(0, 6),
                zoneId = zone,
            )

        assertEquals(16 * 60, minutes)
    }

    @Test
    fun `goal length looks ahead when inside a feeding window`() {
        // 13:00 Monday is mid-feeding, so the next planned fast is 20:00 Mon to
        // 12:00 Tue: 16 hours.
        val minutes =
            FastingAdherence.plannedGoalMinutesAt(
                plan = sixteenEightPlan(),
                extendedFasts = emptyList(),
                now = instantAt(0, 13),
                zoneId = zone,
            )

        assertEquals(16 * 60, minutes)
    }

    @Test
    fun `an extended fast overrides the daily feeding window`() {
        // A 48h fast from Monday midnight covers Monday's and Tuesday's feeding
        // windows, so the whole two days count as planned fasting.
        val extended =
            listOf(
                PlannedExtendedFast(
                    startInstant = instantAt(0, 0),
                    endInstant = instantAt(2, 0),
                    type = FastingType.EXTENDED_48,
                )
            )

        val planned =
            FastingAdherence.plannedFastIntervals(
                plan = sixteenEightPlan(),
                extendedFasts = extended,
                window = Interval(weekStart, instantAt(2, 0)),
                zoneId = zone,
            )

        assertEquals(48 * 3600L, planned.totalSeconds)
    }

    @Test
    fun `a feeding window crossing midnight is handled`() {
        // Eating 20:00 to 02:00 leaves 18 fasting hours per day.
        val plan =
            DayOfWeek.values().toList().map {
                FastingPlanDay(it, LocalTime.of(20, 0), LocalTime.of(2, 0), hasFeedingWindow = true)
            }

        val planned =
            FastingAdherence.plannedFastIntervals(
                plan = plan,
                extendedFasts = emptyList(),
                window = Interval(weekStart, instantAt(1, 0)),
                zoneId = zone,
            )

        assertEquals(18 * 3600L, planned.totalSeconds)
    }

    @Test
    fun `an empty plan yields no score rather than zero`() {
        val result =
            FastingAdherence.score(
                plan = emptyList(),
                extendedFasts = emptyList(),
                sessions = emptyList(),
                weekStart = weekStart,
                weekEnd = weekEnd,
                now = instantAt(1, 0),
                zoneId = zone,
            )

        assertNull(result.score)
    }
}
