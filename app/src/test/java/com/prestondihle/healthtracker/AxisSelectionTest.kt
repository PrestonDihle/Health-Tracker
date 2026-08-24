package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.ui.components.ChartAxis
import com.prestondihle.healthtracker.ui.master.AxisMetric
import com.prestondihle.healthtracker.ui.master.MAX_LABELLED_AXES
import com.prestondihle.healthtracker.ui.master.MasterGraphUiState
import com.prestondihle.healthtracker.ui.master.MasterSeries
import com.prestondihle.healthtracker.ui.master.metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which unit ends up on which side of the master chart.
 *
 * The plot has two gutters and five units to choose between, so the mapping
 * decides both what is labelled and — because a series either takes a labelled
 * axis or a scale of its own — what every line is drawn against.
 */
class AxisSelectionTest {

    private fun state(vararg labelled: AxisMetric) =
        MasterGraphUiState(labelledAxes = labelled.toList())

    @Test
    fun `the first choice takes the left gutter and the second the right`() {
        val uiState = state(AxisMetric.HEART_RATE, AxisMetric.STEPS)

        assertEquals(ChartAxis.LEFT, uiState.axisFor(AxisMetric.HEART_RATE))
        assertEquals(ChartAxis.RIGHT, uiState.axisFor(AxisMetric.STEPS))
    }

    @Test
    fun `an unlabelled unit is on neither side`() {
        // Not an error: it still plots, to its own range, with the numbers in the
        // legend instead of down the side.
        val uiState = state(AxisMetric.GLUCOSE, AxisMetric.MACROS)

        assertNull(uiState.axisFor(AxisMetric.KETONES))
        assertNull(uiState.axisFor(AxisMetric.STEPS))
    }

    @Test
    fun `the default pairing is glucose against the macro curves`() {
        val uiState = MasterGraphUiState()

        assertEquals(listOf(AxisMetric.GLUCOSE, AxisMetric.MACROS), uiState.labelledAxes)
        assertEquals(MAX_LABELLED_AXES, uiState.labelledAxes.size)
    }

    @Test
    fun `the three macro curves share one unit`() {
        // They are all grams per hour. Drawn against separate scales the
        // comparison between them — which is the whole reason they are on one
        // chart — would mean nothing.
        val macros = listOf(MasterSeries.CARBS, MasterSeries.PROTEIN, MasterSeries.FAT)

        assertEquals(listOf(AxisMetric.MACROS), macros.map { it.metric }.distinct())
    }

    @Test
    fun `every series maps to a unit that can be labelled`() {
        // A series whose metric had no chip would be permanently unlabelled with
        // no way to ask for it, which is a silent hole rather than a visible one.
        MasterSeries.entries.forEach { series ->
            assertEquals(series.metric, AxisMetric.entries.first { it == series.metric })
        }
    }
}
