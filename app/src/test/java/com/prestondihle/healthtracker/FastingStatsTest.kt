package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.data.FastingSession
import com.prestondihle.healthtracker.data.FastingType
import com.prestondihle.healthtracker.domain.FastingStatistics
import com.prestondihle.healthtracker.domain.Interval
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastingStatsTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 7, 24)
    private val now: Instant = today.atTime(18, 0).atZone(zone).toInstant()

    private fun session(start: Instant, end: Instant?) =
        FastingSession(
            startInstant = start,
            goalDurationMinutes = 16 * 60,
            endInstant = end,
            type = FastingType.CUSTOM,
        )

    private fun at(date: LocalDate, hour: Int): Instant =
        date.atTime(hour, 0).atZone(zone).toInstant()

    @Test
    fun `a fast inside one day counts its whole length`() {
        val sessions = listOf(session(at(today, 2), at(today, 10)))
        val window = Interval(at(today, 0), at(today.plusDays(1), 0))

        assertEquals(8 * 3600L, FastingStatistics.fastedSeconds(sessions, window, now))
    }

    @Test
    fun `overlapping sessions are not counted twice`() {
        // Two sessions covering 02:00-10:00 and 08:00-12:00 overlap by two hours.
        val sessions =
            listOf(session(at(today, 2), at(today, 10)), session(at(today, 8), at(today, 12)))
        val window = Interval(at(today, 0), at(today.plusDays(1), 0))

        assertEquals(10 * 3600L, FastingStatistics.fastedSeconds(sessions, window, now))
    }

    @Test
    fun `an open session is measured up to now`() {
        val sessions = listOf(session(at(today, 12), null))
        val window = Interval(at(today, 0), at(today.plusDays(1), 0))

        // now is 18:00, so six hours so far.
        assertEquals(6 * 3600L, FastingStatistics.fastedSeconds(sessions, window, now))
    }

    @Test
    fun `a fast crossing midnight is split across both days`() {
        val yesterday = today.minusDays(1)
        val sessions = listOf(session(at(yesterday, 20), at(today, 4)))

        val days = FastingStatistics.daysBetween(sessions, yesterday, today, zone, now)

        assertEquals(4 * 3600L, days.first { it.date == yesterday }.fastedSeconds)
        assertEquals(4 * 3600L, days.first { it.date == today }.fastedSeconds)
    }

    @Test
    fun `segments are expressed as fractions of the day`() {
        // 06:00 to 12:00 is a quarter of the way in, ending at the halfway point.
        val sessions = listOf(session(at(today, 6), at(today, 12)))
        val day = FastingStatistics.daysBetween(sessions, today, today, zone, now).single()

        val segment = day.segments.single()
        assertEquals(0.25f, segment.start, 0.001f)
        assertEquals(0.5f, segment.endInclusive, 0.001f)
    }

    @Test
    fun `days with no fasting are still emitted`() {
        val days = FastingStatistics.daysBetween(emptyList(), today.minusDays(2), today, zone, now)

        assertEquals(3, days.size)
        assertTrue(days.all { it.fastedSeconds == 0L && it.segments.isEmpty() })
    }

    @Test
    fun `an empty today does not break the streak`() {
        // Fasted the three days before today, nothing logged yet today.
        val sessions =
            (1..3).map { session(at(today.minusDays(it.toLong()), 2), at(today.minusDays(it.toLong()), 10)) }
        val days = FastingStatistics.daysBetween(sessions, today.minusDays(5), today, zone, now)

        assertEquals(3, FastingStatistics.currentStreak(days, today))
    }

    @Test
    fun `an empty yesterday does break the streak`() {
        val sessions = listOf(session(at(today, 2), at(today, 10)))
        val days = FastingStatistics.daysBetween(sessions, today.minusDays(5), today, zone, now)

        assertEquals(1, FastingStatistics.currentStreak(days, today))
    }

    @Test
    fun `best streak finds the longest run anywhere in range`() {
        // Days 8,7,6 fasted, day 5 missed, days 3,2 fasted.
        val fastedOffsets = listOf(8L, 7L, 6L, 3L, 2L)
        val sessions =
            fastedOffsets.map { session(at(today.minusDays(it), 2), at(today.minusDays(it), 10)) }
        val days = FastingStatistics.daysBetween(sessions, today.minusDays(10), today, zone, now)

        assertEquals(3, FastingStatistics.bestStreak(days))
    }

    @Test
    fun `longest and average ignore a fast still running`() {
        val sessions =
            listOf(
                session(at(today.minusDays(2), 0), at(today.minusDays(2), 20)), // 20h
                session(at(today.minusDays(1), 0), at(today.minusDays(1), 10)), // 10h
                session(at(today, 12), null), // open, must not count
            )
        val days = FastingStatistics.daysBetween(sessions, today.minusDays(7), today, zone, now)
        val stats = FastingStatistics.summarise(sessions, days, today, zone, now)

        assertEquals(Duration.ofHours(20), stats.longestFast)
        assertEquals(2, stats.completedFasts)
        assertEquals(15 * 3600L, stats.averageFastSeconds)
    }

    @Test
    fun `no completed fasts leaves longest unset rather than zero`() {
        val sessions = listOf(session(at(today, 12), null))
        val days = FastingStatistics.daysBetween(sessions, today.minusDays(7), today, zone, now)
        val stats = FastingStatistics.summarise(sessions, days, today, zone, now)

        assertNull(stats.longestFast)
        assertEquals(0, stats.completedFasts)
    }

    @Test
    fun `week and month totals cover their whole span`() {
        // One 8-hour fast every day for the last 10 days.
        val sessions =
            (0L..9L).map { session(at(today.minusDays(it), 2), at(today.minusDays(it), 10)) }
        val days = FastingStatistics.daysBetween(sessions, today.minusDays(30), today, zone, now)
        val stats = FastingStatistics.summarise(sessions, days, today, zone, now)

        assertEquals(8 * 3600L, stats.todaySeconds)
        assertEquals(7 * 8 * 3600L, stats.weekSeconds)
        assertEquals(10 * 8 * 3600L, stats.monthSeconds)
    }
}
