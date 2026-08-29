package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.ui.components.ChartAxis
import com.prestondihle.healthtracker.ui.theme.LightChartColors
import com.prestondihle.healthtracker.ui.today.AxisMetric
import com.prestondihle.healthtracker.ui.today.MAX_LABELLED_AXES
import com.prestondihle.healthtracker.ui.today.MasterSeries
import com.prestondihle.healthtracker.ui.today.TodayUiState
import com.prestondihle.healthtracker.ui.today.axisColorFor
import com.prestondihle.healthtracker.ui.today.colorIn
import com.prestondihle.healthtracker.ui.today.metric
import com.prestondihle.healthtracker.ui.today.series
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
        TodayUiState(labelledAxes = labelled.toList())

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
    fun `the default pairing is glucose against heart rate`() {
        val uiState = TodayUiState()

        assertEquals(listOf(AxisMetric.GLUCOSE, AxisMetric.HEART_RATE), uiState.labelledAxes)
        assertEquals(MAX_LABELLED_AXES, uiState.labelledAxes.size)
    }

    @Test
    fun `every labelled axis has a visible line under it by default`() {
        // The pairing used to be glucose against macros, which was right while
        // all eight series were drawn. With the default narrowed to three, a g/h
        // gutter would be printing numbers for three curves that are switched
        // off -- an axis describing nothing on the plot, which is worse than an
        // unlabelled one because it looks like a reading.
        val uiState = TodayUiState()

        uiState.labelledAxes.forEach { metric ->
            assertEquals(
                "$metric is labelled but nothing visible is drawn against it",
                true,
                metric.series.any(uiState::isVisible),
            )
        }
    }

    @Test
    fun `the master graph opens on the day's shape rather than on everything`() {
        // Eight series is a legible chart of nothing in particular. These three
        // carry the two questions the chart is opened for -- why is the heart
        // rate up, and what moved the glucose -- and the switches still reach
        // the rest in one tap.
        assertEquals(
            setOf(MasterSeries.GLUCOSE, MasterSeries.HEART_RATE, MasterSeries.STEPS),
            TodayUiState().visibleSeries,
        )
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
        // Caffeine has to be switched on explicitly now: it is off by default,
        // and an axis whose only series is not drawn correctly has no colour --
        // which is the case the test below this one covers.
        val uiState = TodayUiState(visibleSeries = MasterSeries.entries.toSet())

        assertEquals(MasterSeries.GLUCOSE.colorIn(LightChartColors), uiState.axisColorFor(AxisMetric.GLUCOSE, LightChartColors))
        assertEquals(MasterSeries.CAFFEINE.colorIn(LightChartColors), uiState.axisColorFor(AxisMetric.CAFFEINE, LightChartColors))
    }

    @Test
    fun `an axis shared by several lines stays grey`() {
        // Tinting g/h in the carbohydrate colour would claim the protein and fat
        // curves are read against some other axis. There is no honest colour
        // here, and the ordinary label grey is the honest answer.
        //
        // All three switched on explicitly. Under the default set this would pass
        // with none of them drawn at all, which is a different rule -- and a test
        // passing for the wrong reason stops guarding the one it names.
        val uiState = TodayUiState(visibleSeries = MasterSeries.entries.toSet())

        assertNull(uiState.axisColorFor(AxisMetric.MACROS, LightChartColors))
    }

    @Test
    fun `switching the other macros off hands the axis to the survivor`() {
        val uiState =
            TodayUiState(
                visibleSeries = MasterSeries.entries.toSet() - MasterSeries.PROTEIN -
                    MasterSeries.FAT
            )

        assertEquals(MasterSeries.CARBS.colorIn(LightChartColors), uiState.axisColorFor(AxisMetric.MACROS, LightChartColors))
    }

    @Test
    fun `an axis whose only line is switched off stays grey`() {
        // Nothing is drawn against it, so there is nothing for the numbers to
        // belong to -- and colouring them would point at a line that is not there.
        val uiState =
            TodayUiState(visibleSeries = MasterSeries.entries.toSet() - MasterSeries.GLUCOSE)

        assertNull(uiState.axisColorFor(AxisMetric.GLUCOSE, LightChartColors))
    }

    @Test
    fun `caffeine is a unit of its own`() {
        // Milligrams in the body. Sharing the macro axis would draw 180 mg of
        // caffeine at four times the height of 45 g of carbohydrate arriving.
        assertEquals(AxisMetric.CAFFEINE, MasterSeries.CAFFEINE.metric)
        assertEquals(listOf(MasterSeries.CAFFEINE), AxisMetric.CAFFEINE.series)
    }
}
