package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.Caffeine
import com.prestondihle.healthtracker.domain.CaffeineDose
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaffeineTest {

    private val noon: Instant = Instant.parse("2026-07-24T12:00:00Z")

    private fun hours(h: Long) = Duration.ofHours(h)

    @Test
    fun `a dose reads just under its full amount at its logged time`() {
        // The dose is drunk over the 30 min ending now, so the earliest sips
        // have already begun to decay -- about 96.6% remains, not the whole 100.
        val doses = listOf(CaffeineDose(noon, 100))
        assertEquals(96.61f, Caffeine.levelAt(doses, noon), 0.05f)
    }

    @Test
    fun `nothing is present before the intake ramp begins`() {
        // Logged at noon, so drinking starts at 11:30. At 11:29 there is nothing.
        val doses = listOf(CaffeineDose(noon, 100))
        assertEquals(0f, Caffeine.levelAt(doses, noon.minus(Duration.ofMinutes(31))), 0.01f)
    }

    @Test
    fun `the level climbs across the intake ramp`() {
        val doses = listOf(CaffeineDose(noon, 100))
        val atStart = Caffeine.levelAt(doses, noon.minus(Duration.ofMinutes(30)))
        val midRamp = Caffeine.levelAt(doses, noon.minus(Duration.ofMinutes(15)))
        val atEnd = Caffeine.levelAt(doses, noon)

        assertEquals(0f, atStart, 0.01f)
        assertTrue("should be rising through the ramp", atStart < midRamp && midRamp < atEnd)
    }

    @Test
    fun `a fully absorbed dose still halves every five hours`() {
        // Once the ramp is done the shape is pure exponential, so the ratio
        // across one half-life is exactly one half whatever the intake model.
        val doses = listOf(CaffeineDose(noon, 200))
        val atEnd = Caffeine.levelAt(doses, noon)
        val fiveHoursLater = Caffeine.levelAt(doses, noon.plus(hours(5)))

        assertEquals(0.5f, fiveHoursLater / atEnd, 0.001f)
    }

    @Test
    fun `two half-lives quarter the post-ramp level`() {
        val doses = listOf(CaffeineDose(noon, 200))
        val atEnd = Caffeine.levelAt(doses, noon)
        val tenHoursLater = Caffeine.levelAt(doses, noon.plus(hours(10)))

        assertEquals(0.25f, tenHoursLater / atEnd, 0.001f)
    }

    @Test
    fun `doses accumulate rather than replacing each other`() {
        // The noon dose has decayed to ~48 mg by 17:00, when a fresh ~96.6 lands.
        val doses = listOf(CaffeineDose(noon, 100), CaffeineDose(noon.plus(hours(5)), 100))
        assertEquals(144.91f, Caffeine.levelAt(doses, noon.plus(hours(5))), 0.05f)
    }

    @Test
    fun `a dose in the future does not count yet`() {
        val doses = listOf(CaffeineDose(noon.plus(hours(1)), 100))
        assertEquals(0f, Caffeine.levelAt(doses, noon), 0.01f)
    }

    @Test
    fun `no doses means no caffeine`() {
        assertEquals(0f, Caffeine.levelAt(emptyList(), noon), 0.01f)
    }

    @Test
    fun `the curve ends exactly on the window end`() {
        val doses = listOf(CaffeineDose(noon, 100))
        val curve =
            Caffeine.curve(
                doses,
                from = noon,
                to = noon.plus(hours(6)),
                step = Duration.ofMinutes(45),
            )
        assertEquals(noon.plus(hours(6)), curve.last().first)
    }

    @Test
    fun `the curve decays monotonically after the last dose`() {
        val doses = listOf(CaffeineDose(noon, 150))
        val curve = Caffeine.curve(doses, from = noon, to = noon.plus(hours(12)))

        val values = curve.map { it.second }
        assertTrue(
            "expected a strictly falling curve after the dose",
            values.zipWithNext().all { (a, b) -> b < a },
        )
    }

    @Test
    fun `the curve rises at a dose taken mid-window`() {
        val doses = listOf(CaffeineDose(noon.plus(hours(3)), 100))
        val curve = Caffeine.curve(doses, from = noon, to = noon.plus(hours(6)))

        val beforeDose = curve.first { it.first == noon.plus(hours(2)) }.second
        val afterDose = curve.first { it.first == noon.plus(hours(4)) }.second
        assertTrue("dose should lift the curve", afterDose > beforeDose)
    }

    @Test
    fun `an inverted window yields no points`() {
        val doses = listOf(CaffeineDose(noon, 100))
        assertTrue(Caffeine.curve(doses, from = noon, to = noon.minus(hours(1))).isEmpty())
    }
}
