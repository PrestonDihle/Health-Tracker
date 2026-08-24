package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.ui.components.ChartAxis
import com.prestondihle.healthtracker.ui.master.AxisMetric
import com.prestondihle.healthtracker.ui.master.MAX_LABELLED_AXES
import com.prestondihle.healthtracker.ui.master.MasterGraphUiState
import com.prestondihle.healthtracker.ui.master.MasterSeries
import com.prestondihle.healthtracker.ui.master.axisColorFor
import com.prestondihle.healthtracker.ui.master.color
import com.prestondihle.healthtracker.ui.master.metric
import com.prestondihle.healthtracker.ui.master.series
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

    @Test
    fun `an axis serving one line takes that line's colour`() {
        val uiState = MasterGraphUiState()

        assertEquals(MasterSeries.GLUCOSE.color, uiState.axisColorFor(AxisMetric.GLUCOSE))
        assertEquals(MasterSeries.CAFFEINE.color, uiState.axisColorFor(AxisMetric.CAFFEINE))
    }

    @Test
    fun `an axis shared by several lines stays grey`() {
        // Tinting g/h in the carbohydrate colour would claim the protein and fat
        // curves are read against some other axis. There is no honest colour
        // here, and the ordinary label grey is the honest answer.
        val uiState = MasterGraphUiState()

        assertNull(uiState.axisColorFor(AxisMetric.MACROS))
    }

    @Test
    fun `switching the other macros off hands the axis to the survivor`() {
        val uiState =
            MasterGraphUiState(
                visibleSeries = MasterSeries.entries.toSet() - MasterSeries.PROTEIN -
                    MasterSeries.FAT
            )

        assertEquals(MasterSeries.CARBS.color, uiState.axisColorFor(AxisMetric.MACROS))
    }

    @Test
    fun `an axis whose only line is switched off stays grey`() {
        // Nothing is drawn against it, so there is nothing for the numbers to
        // belong to -- and colouring them would point at a line that is not there.
        val uiState =
            MasterGraphUiState(visibleSeries = MasterSeries.entries.toSet() - MasterSeries.GLUCOSE)

        assertNull(uiState.axisColorFor(AxisMetric.GLUCOSE))
    }

    @Test
    fun `caffeine is a unit of its own`() {
        // Milligrams in the body. Sharing the macro axis would draw 180 mg of
        // caffeine at four times the height of 45 g of carbohydrate arriving.
        assertEquals(AxisMetric.CAFFEINE, MasterSeries.CAFFEINE.metric)
        assertEquals(listOf(MasterSeries.CAFFEINE), AxisMetric.CAFFEINE.series)
    }
}
