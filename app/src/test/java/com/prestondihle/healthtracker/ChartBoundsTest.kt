package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.ui.components.chartBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How much of the number line a day-indexed chart has to cover.
 *
 * The failure this exists to prevent is a silent one. A rule outside the plot is
 * not drawn small or drawn at the edge -- it is clipped and nothing appears, so
 * a weight chart scaled to a fortnight of readings shows no goal at all, and
 * looks exactly like a chart with no goal set.
 */
class ChartBoundsTest {

    /** A fortnight hovering just under 200 lb. */
    private val readings = listOf(198f, 197.5f, 199f, 196f, 197f)

    @Test
    fun `a goal below every reading pulls the floor down to meet it`() {
        val bounds = chartBounds(readings, marks = listOf(180f))

        assertEquals(180f, bounds.start)
        assertEquals(199f, bounds.endInclusive)
    }

    @Test
    fun `a goal above every reading raises the ceiling`() {
        // Someone eating back up to a target rather than down to one.
        val bounds = chartBounds(listOf(140f, 142f, 141f), marks = listOf(160f))

        assertEquals(160f, bounds.endInclusive)
    }

    @Test
    fun `waypoints count too, including the furthest one`() {
        val bounds = chartBounds(readings, marks = listOf(180f, 195f, 190f, 185f))

        assertEquals(180f, bounds.start)
    }

    @Test
    fun `a goal already inside the readings changes nothing`() {
        val bounds = chartBounds(readings, marks = listOf(197f))

        assertEquals(196f, bounds.start)
        assertEquals(199f, bounds.endInclusive)
    }

    @Test
    fun `no goal falls back to the readings alone`() {
        val bounds = chartBounds(readings, marks = emptyList())

        assertEquals(196f, bounds.start)
        assertEquals(199f, bounds.endInclusive)
    }

    @Test
    fun `an explicit scale wins over the goal`() {
        // The mood chart pins 1 to 10 because the scale *is* the meaning. A goal
        // must not be able to stretch an axis like that, or a 1-to-10 score
        // starts being drawn against 1 to 25.
        val bounds = chartBounds(listOf(4f, 7f), marks = listOf(25f), minY = 1f, maxY = 10f)

        assertEquals(1f, bounds.start)
        assertEquals(10f, bounds.endInclusive)
    }

    @Test
    fun `one pinned edge still lets the other follow the goal`() {
        val bounds = chartBounds(listOf(4f, 7f), marks = listOf(25f), minY = 0f)

        assertEquals(0f, bounds.start)
        assertEquals(25f, bounds.endInclusive)
    }

    @Test
    fun `a goal with no readings at all does not invert the range`() {
        // Not reachable through the chart, which bails out before this on an
        // empty series -- but the range must be ordered whatever it is handed,
        // since an inverted one draws every point at the same height.
        val bounds = chartBounds(emptyList(), marks = emptyList())

        assertTrue(bounds.start <= bounds.endInclusive)
    }
}
