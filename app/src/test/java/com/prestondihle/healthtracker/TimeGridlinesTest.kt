package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.ui.components.TimeGridlines
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the vertical rules land on the master chart.
 *
 * Two separate questions: how far apart they should be, which is about how much
 * of the day is on screen and how wide the screen is, and where exactly they go,
 * which is about the clock.
 */
class TimeGridlinesTest {

    /** A phone's plot area, near enough: a 360dp screen less the two gutters. */
    private val phoneWidth = 300f
    private val minSpacing = 14f

    private val zone: ZoneId = ZoneId.of("America/Denver")

    private fun interval(windowHours: Long, width: Float = phoneWidth): Long =
        TimeGridlines.intervalHours(windowHours, width, minSpacing)

    @Test
    fun `half a day and under gets a rule every hour`() {
        assertEquals(1L, interval(3))
        assertEquals(1L, interval(6))
        assertEquals(1L, interval(12))
    }

    @Test
    fun `a day and over steps out to four hours`() {
        assertEquals(4L, interval(24))
        assertEquals(4L, interval(48))
    }

    @Test
    fun `a week widens rather than drawing forty rules a finger-width apart`() {
        val chosen = interval(24 * 7)

        assertTrue("expected wider than four hours, got $chosen", chosen > 4L)
        assertTrue(phoneWidth * (chosen.toFloat() / (24 * 7)) >= minSpacing)
    }

    @Test
    fun `a wider screen keeps the interval it was asked for`() {
        // The guard is about the screen, not the clock: the same week on a
        // tablet has room for the rules a phone has to thin out.
        assertTrue(interval(24 * 7, width = 1_400f) < interval(24 * 7, width = phoneWidth))
    }

    @Test
    fun `every interval divides a day evenly`() {
        // Otherwise the rules drift through the day and sit in different places
        // on Tuesday than on Monday, which is the opposite of a gridline's job.
        val windows = listOf(3L, 6L, 12L, 24L, 48L, 24L * 7)

        windows.forEach { assertEquals("window $it", 0L, 24L % interval(it)) }
    }

    @Test
    fun `rules sit on the clock, not on the edge of the window`() {
        // The window ends at 2:47, as one anchored to *now* does. A rule there
        // cannot answer "how much of that rise was in the hour after eating".
        val end = Instant.parse("2026-08-24T20:47:00Z")
        val start = end.minus(Duration.ofHours(6))

        val times = TimeGridlines.times(start, end, zone, intervalHours = 1)

        assertTrue(times.isNotEmpty())
        times.forEach { assertEquals(0, it.atZone(zone).minute) }
    }

    @Test
    fun `a four-hour interval lands on hours divisible by four`() {
        val end = Instant.parse("2026-08-24T20:47:00Z")
        val start = end.minus(Duration.ofHours(24))

        val times = TimeGridlines.times(start, end, zone, intervalHours = 4)

        assertTrue(times.isNotEmpty())
        times.forEach { assertEquals(0, it.atZone(zone).hour % 4) }
    }

    @Test
    fun `nothing lands outside the window`() {
        val end = Instant.parse("2026-08-24T20:47:00Z")
        val start = end.minus(Duration.ofHours(6))

        val times = TimeGridlines.times(start, end, zone, intervalHours = 1)

        times.forEach {
            assertTrue(!it.isBefore(start))
            assertTrue(!it.isAfter(end))
        }
    }

    @Test
    fun `a spring-forward day keeps every rule on the clock`() {
        // Denver loses 2 AM on this date. Stepping by a fixed number of hours
        // from the window edge would put every rule after the change an hour off
        // the clock; walking the hours through the zone cannot.
        val start = Instant.parse("2026-03-08T06:00:00Z") // 11 PM the night before
        val end = start.plus(Duration.ofHours(12))

        val times = TimeGridlines.times(start, end, zone, intervalHours = 4)

        assertTrue(times.isNotEmpty())
        times.forEach { assertEquals(0, it.atZone(zone).hour % 4) }
    }

    @Test
    fun `a window with no width has no rules`() {
        val at = Instant.parse("2026-08-24T20:47:00Z")

        assertEquals(emptyList<Instant>(), TimeGridlines.times(at, at, zone, intervalHours = 1))
        assertEquals(0L, TimeGridlines.intervalHours(0, phoneWidth, minSpacing))
    }
}
