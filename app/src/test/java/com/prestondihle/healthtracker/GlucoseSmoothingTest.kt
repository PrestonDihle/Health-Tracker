package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.GlucoseSmoothing
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The smoother is the one thing on the glucose chart that draws a line the
 * readings do not literally contain, so what it may and may not do to them is
 * worth pinning down.
 */
class GlucoseSmoothingTest {

    private val start: Instant = Instant.parse("2026-08-20T06:00:00Z")

    /** A CGM-shaped trace: one sample every five minutes. */
    private fun trace(vararg values: Float): List<Pair<Instant, Float>> =
        values.mapIndexed { index, value -> start.plus(Duration.ofMinutes(index * 5L)) to value }

    @Test
    fun `every reading keeps its own timestamp`() {
        // The right-hand end of the chart is now. A filter that dropped or moved
        // the last point would be quietly drawing the past as the present.
        val raw = trace(90f, 94f, 91f, 130f, 128f, 110f, 98f, 92f)

        val smoothed = GlucoseSmoothing.smooth(raw)

        assertEquals(raw.size, smoothed.size)
        assertEquals(raw.map { it.first }, smoothed.map { it.first })
    }

    @Test
    fun `a flat trace is left flat`() {
        val smoothed = GlucoseSmoothing.smooth(trace(100f, 100f, 100f, 100f, 100f, 100f))

        smoothed.forEach { assertEquals(100f, it.second, 0.001f) }
    }

    @Test
    fun `a single-sample spike is pulled down towards its neighbours`() {
        val raw = trace(90f, 90f, 90f, 150f, 90f, 90f, 90f)

        val smoothed = GlucoseSmoothing.smooth(raw)

        // The peak survives as a peak -- it is still the highest point, and still
        // in the same slot -- but a lone sample 60 above its neighbours is sensor
        // noise, and most of it should be gone.
        val peak = smoothed[3].second
        assertTrue("expected the spike to be blunted, got $peak", peak < 120f)
        assertTrue("expected the spike to stay a peak, got $peak", peak > smoothed[2].second)
        assertEquals(3, smoothed.indices.maxBy { smoothed[it].second })
    }

    @Test
    fun `no smoothed value escapes the range of the readings`() {
        // A weighted mean of real readings cannot leave their span. Overshoot is
        // how a smoother invents a high that never happened.
        val raw = trace(88f, 96f, 142f, 151f, 133f, 104f, 91f, 87f, 90f, 95f)

        val smoothed = GlucoseSmoothing.smooth(raw)

        val low = raw.minOf { it.second }
        val high = raw.maxOf { it.second }
        smoothed.forEach {
            assertTrue("${it.second} below $low", it.second >= low - 0.001f)
            assertTrue("${it.second} above $high", it.second <= high + 0.001f)
        }
    }

    @Test
    fun `readings hours apart are each left exactly as measured`() {
        // Hand-typed fingersticks, not a trace. Nothing is inside anything else's
        // window, so every point is its own only neighbour and averaging must be
        // a no-op -- an index-weighted filter would have blended them.
        val raw =
            listOf(
                start to 96f,
                start.plus(Duration.ofHours(5)) to 142f,
                start.plus(Duration.ofHours(11)) to 88f,
            )

        val smoothed = GlucoseSmoothing.smooth(raw)

        assertEquals(raw, smoothed)
    }

    @Test
    fun `a rise is not delayed`() {
        // A centred kernel moves a peak's height, never its timing. A one-sided
        // running mean would drag this to the right and misreport when the meal
        // landed.
        //
        // Symmetric about its peak, so the shape itself cannot favour either
        // side: any lean in the output is the filter's, and shows up both as a
        // moved maximum and as the two flanks no longer matching.
        val raw = trace(90f, 95f, 105f, 120f, 140f, 120f, 105f, 95f, 90f)

        val smoothed = GlucoseSmoothing.smooth(raw)

        assertEquals(4, smoothed.indices.maxBy { smoothed[it].second })
        assertEquals(smoothed[3].second, smoothed[5].second, 0.001f)
        assertEquals(smoothed[2].second, smoothed[6].second, 0.001f)
    }

    @Test
    fun `too few readings to filter are returned untouched`() {
        val raw = trace(101f, 99f)

        assertEquals(raw, GlucoseSmoothing.smooth(raw))
        assertEquals(emptyList<Pair<Instant, Float>>(), GlucoseSmoothing.smooth(emptyList()))
    }

    @Test
    fun `unsorted input is put in order rather than mis-filtered`() {
        val ordered = trace(90f, 95f, 130f, 128f, 100f, 92f)

        val smoothed = GlucoseSmoothing.smooth(ordered.reversed())

        assertEquals(ordered.map { it.first }, smoothed.map { it.first })
        assertEquals(
            GlucoseSmoothing.smooth(ordered).map { it.second },
            smoothed.map { it.second },
        )
    }
}
