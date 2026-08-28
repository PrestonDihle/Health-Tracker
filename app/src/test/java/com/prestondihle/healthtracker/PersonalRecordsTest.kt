package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.data.AftAttempt
import com.prestondihle.healthtracker.data.FastingSession
import com.prestondihle.healthtracker.data.FastingType
import com.prestondihle.healthtracker.data.GripStrengthEntry
import com.prestondihle.healthtracker.domain.PersonalBests
import com.prestondihle.healthtracker.domain.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

/**
 * What may be called a personal record.
 *
 * The failures worth guarding are the ones that put a number on this card that
 * nobody performed: a running fast counted as the longest, a two-mile taken from
 * the projection rather than from a test that happened, or a hand's best filled
 * in from the other hand because the columns are nullable.
 */
class PersonalRecordsTest {

    private val zone = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 3, 4)

    private fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant()

    @Test
    fun `the heaviest grip wins, per hand, with the day it was set`() {
        val grips =
            listOf(
                GripStrengthEntry(date = day.minusDays(10), dominantKg = 60f, nonDominantKg = 58f),
                GripStrengthEntry(date = day.minusDays(3), dominantKg = 64f, nonDominantKg = 55f),
            )

        val records = PersonalBests.from(grips = grips, zoneId = zone)

        assertEquals(64f, records.gripDominant!!.value, 0.01f)
        assertEquals(day.minusDays(3), records.gripDominant!!.date)
        // The other hand's best is on a different day, which is exactly why this
        // is per hand: a best-of-both would report 64 under a label covering both
        // and lose the fact that the weaker hand peaked a week earlier.
        assertEquals(58f, records.gripNonDominant!!.value, 0.01f)
        assertEquals(day.minusDays(10), records.gripNonDominant!!.date)
    }

    @Test
    fun `one hand logged alone does not borrow the other's figure`() {
        val grips = listOf(GripStrengthEntry(date = day, dominantKg = 62f, nonDominantKg = null))

        val records = PersonalBests.from(grips = grips, zoneId = zone)

        assertEquals(62f, records.gripDominant!!.value, 0.01f)
        assertNull(records.gripNonDominant)
    }

    @Test
    fun `the two-mile record is the quickest, not the latest or the largest`() {
        val attempts =
            listOf(
                AftAttempt(date = day.minusDays(200), twoMileSeconds = 1_020),
                AftAttempt(date = day.minusDays(20), twoMileSeconds = 1_140),
            )

        val records = PersonalBests.from(aftAttempts = attempts, zoneId = zone)

        // 17:00 from last year beats 19:00 from last month. Taking a maximum, or
        // the most recent, would report the slower run as the record.
        assertEquals(1_020, records.twoMile!!.value)
        assertEquals(day.minusDays(200), records.twoMile!!.date)
    }

    @Test
    fun `a part-logged test still sets the records it did record`() {
        // A test stopped after the deadlift. The events are nullable precisely
        // because that happens, and a missing two-mile is not a slow one.
        val attempts = listOf(AftAttempt(date = day, deadliftKg = Units.lbsToKg(340f)))

        val records = PersonalBests.from(aftAttempts = attempts, zoneId = zone)

        assertEquals(340, Units.kgToWholeLbs(records.deadlift!!.value))
        assertNull(records.twoMile)
    }

    @Test
    fun `only a finished fast can be the longest`() {
        val finished =
            FastingSession(
                startInstant = at(day.minusDays(5), 20),
                endInstant = at(day.minusDays(4), 14),
                goalDurationMinutes = 1_080,
                type = FastingType.OMAD,
            )
        // Running since yesterday evening. Counted, it would report its length so
        // far, take the record, and beat itself again an hour later -- a personal
        // best that climbs all day and collapses the moment the fast ends.
        val running =
            FastingSession(
                startInstant = at(day.minusDays(1), 18),
                endInstant = null,
                goalDurationMinutes = 1_080,
                type = FastingType.OMAD,
            )

        val records = PersonalBests.from(fasts = listOf(finished, running), zoneId = zone)

        assertEquals(Duration.ofHours(18), records.longestFast!!.value)
    }

    @Test
    fun `a fast is dated by the day it ended`() {
        // Started Friday evening, broken Sunday lunchtime: a Sunday achievement.
        // Dated by its start it would sit before two of the days that earned it.
        val long =
            FastingSession(
                startInstant = at(day.minusDays(2), 19),
                endInstant = at(day, 12),
                goalDurationMinutes = 2_880,
                type = FastingType.EXTENDED_48,
            )

        val records = PersonalBests.from(fasts = listOf(long), zoneId = zone)

        assertEquals(day, records.longestFast!!.date)
    }

    @Test
    fun `the best day in range is the highest of the days that were covered`() {
        val byDay =
            mapOf(
                day.minusDays(3) to 0.81f,
                day.minusDays(2) to 0.93f,
                day.minusDays(1) to 0.77f,
            )

        val records = PersonalBests.from(timeInRangeByDay = byDay, zoneId = zone)

        assertEquals(0.93f, records.timeInRange!!.value, 0.001f)
        assertEquals(day.minusDays(2), records.timeInRange!!.date)
    }

    @Test
    fun `nothing logged is empty rather than a row of zeroes`() {
        val records = PersonalBests.from(zoneId = zone)

        assertTrue(records.isEmpty)
        assertNull(records.gripDominant)
        assertNull(records.longestFast)
        assertNull(records.timeInRange)
    }
}
