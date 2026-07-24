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
    fun `a dose is fully present the moment it is taken`() {
        val doses = listOf(CaffeineDose(noon, 100))
        assertEquals(100f, Caffeine.levelAt(doses, noon), 0.01f)
    }

    @Test
    fun `one half-life halves the dose`() {
        val doses = listOf(CaffeineDose(noon, 200))
        assertEquals(100f, Caffeine.levelAt(doses, noon.plus(hours(5))), 0.01f)
    }

    @Test
    fun `two half-lives quarter the dose`() {
        val doses = listOf(CaffeineDose(noon, 200))
        assertEquals(50f, Caffeine.levelAt(doses, noon.plus(hours(10))), 0.01f)
    }

    @Test
    fun `doses accumulate rather than replacing each other`() {
        // 100 mg at noon has decayed to 50 mg by 17:00, when another 100 lands.
        val doses = listOf(CaffeineDose(noon, 100), CaffeineDose(noon.plus(hours(5)), 100))
        assertEquals(150f, Caffeine.levelAt(doses, noon.plus(hours(5))), 0.01f)
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
