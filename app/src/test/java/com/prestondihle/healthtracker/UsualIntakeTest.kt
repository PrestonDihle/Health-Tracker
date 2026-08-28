package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.data.SupplementSlot
import com.prestondihle.healthtracker.domain.UsualIntake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.time.Instant
import java.time.LocalTime
import org.junit.Test

/**
 * What the one-tap row on Log is allowed to guess.
 *
 * A wrong suggestion here writes a row into live health data on a single tap,
 * with no dialog in between -- which is what the whole feature is for and also
 * what makes it worth being careful about. The two intakes deliberately read
 * their history differently, and the difference is the substance of this suite.
 */
class UsualIntakeTest {

    private val start = Instant.parse("2026-03-01T08:00:00Z")

    private fun at(hoursIn: Long) = start.plusSeconds(hoursIn * 3600)

    @Test
    fun `caffeine repeats the last dose, not the most common one`() {
        // Twelve mornings at 95 mg, then a switch to a bigger cup this week.
        val history =
            (0L until 12L).map { at(it * 24) to 95 } + listOf(at(300) to 150, at(324) to 150)

        // The count still favours 95 by six to one. Somebody who has changed cup
        // wants the new one on the second day, not once the tally catches up.
        assertEquals(150, UsualIntake.lastDose(history))
    }

    @Test
    fun `water takes the volume used most often, not the last one`() {
        // A 500 ml bottle most days and one odd 250 ml glass, most recently.
        val history = (0L until 8L).map { at(it * 6) to 500 } + listOf(at(60) to 250)

        // Taking the last would offer the glass; the bottle is the habit.
        assertEquals(500, UsualIntake.usualVolume(history))
    }

    @Test
    fun `a mean is never offered, because nobody drinks one`() {
        val history = listOf(at(0) to 500, at(1) to 500, at(2) to 250, at(3) to 250, at(4) to 500)

        // A mean would be 400 -- a quantity with no container behind it.
        assertEquals(500, UsualIntake.usualVolume(history))
    }

    @Test
    fun `a tie between two volumes goes to the one used more recently`() {
        // Three of each. A habit mid-change, and the newer half is what it is
        // changing to.
        val history =
            listOf(at(0) to 350, at(1) to 350, at(2) to 350, at(50) to 700, at(51) to 700, at(52) to 700)

        assertEquals(700, UsualIntake.usualVolume(history))
    }

    @Test
    fun `no history offers nothing rather than a zero`() {
        assertNull(UsualIntake.lastDose(emptyList()))
        assertNull(UsualIntake.usualVolume(emptyList()))
    }

    @Test
    fun `a zero or negative entry is never suggested`() {
        // Rows like this should not exist, but a chip that logs 0 ml on one tap
        // is a row of noise in live data and the guard costs nothing.
        assertNull(UsualIntake.lastDose(listOf(at(0) to 0)))
        assertNull(UsualIntake.usualVolume(listOf(at(0) to 0)))
        assertEquals(500, UsualIntake.usualVolume(listOf(at(0) to 0, at(1) to 500)))
    }

    @Test
    fun `the slot follows the clock`() {
        assertEquals(SupplementSlot.MORNING, UsualIntake.slotAt(LocalTime.of(7, 30)))
        assertEquals(SupplementSlot.MIDDAY, UsualIntake.slotAt(LocalTime.of(13, 0)))
        assertEquals(SupplementSlot.EVENING, UsualIntake.slotAt(LocalTime.of(21, 15)))
    }

    @Test
    fun `the boundaries fall on the side the words mean`() {
        // Noon ends the morning by definition, so 12:00 is already midday; five
        // is where an afternoon dose stops being midday.
        assertEquals(SupplementSlot.MORNING, UsualIntake.slotAt(LocalTime.of(11, 59)))
        assertEquals(SupplementSlot.MIDDAY, UsualIntake.slotAt(LocalTime.NOON))
        assertEquals(SupplementSlot.MIDDAY, UsualIntake.slotAt(LocalTime.of(16, 59)))
        assertEquals(SupplementSlot.EVENING, UsualIntake.slotAt(LocalTime.of(17, 0)))
    }

    @Test
    fun `the small hours are offered the morning's stack`() {
        // Somebody up at two is at the end of a long evening, but they have not
        // taken the coming morning's either, and a chip is declined by ignoring it.
        assertEquals(SupplementSlot.MORNING, UsualIntake.slotAt(LocalTime.of(2, 0)))
    }
}
