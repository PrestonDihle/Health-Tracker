package com.prestondihle.healthtracker.ui.trends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.ui.components.BarChart
import com.prestondihle.healthtracker.ui.components.LineSeries
import com.prestondihle.healthtracker.ui.theme.LocalChartColors
import com.prestondihle.healthtracker.ui.components.LineStyle
import com.prestondihle.healthtracker.ui.components.MultiLineChart

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrendsScreen(viewModel: TrendsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val chartColors = LocalChartColors.current

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            // Four spelled-out labels overrun a phone's width; wrapping keeps
            // them all visible rather than clipping the longest.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrendsRange.entries.forEach { option ->
                    FilterChip(
                        selected = state.range == option,
                        onClick = { viewModel.setRange(option) },
                        label = { Text(option.label) },
                    )
                }
            }
        }

        item {
            TrendCard(title = "Steps", subtitle = "from Health Connect") {
                BarChart(
                    days = state.snapshotSeries { it.steps?.toFloat() },
                    goalLine = state.goals.dailyStepGoal?.toFloat(),
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
        }

        // Both hands on one chart, because the comparison between them is the
        // point: a gap that widens over months says something neither line says
        // on its own.
        item {
            TrendCard(title = "Grip strength", subtitle = "pounds") {
                MultiLineChart(
                    series =
                        listOf(
                            LineSeries(
                                label = "Dominant",
                                points = state.gripSeries(dominant = true),
                                color = chartColors.gripDominant,
                                style = LineStyle.SOLID,
                            ),
                            LineSeries(
                                label = "Non-dominant",
                                points = state.gripSeries(dominant = false),
                                color = chartColors.gripNonDominant,
                                style = LineStyle.DASHED,
                            ),
                        ),
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
        }

        item {
            TrendCard(title = "Pushups", subtitle = "reps per day") {
                BarChart(
                    days = state.repSeries(MovementType.PUSHUP),
                    goalLine = state.goals.dailyPushupGoal?.toFloat(),
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
        }

        item {
            TrendCard(title = "Air squats", subtitle = "reps per day") {
                BarChart(
                    days = state.repSeries(MovementType.AIR_SQUAT),
                    goalLine = state.goals.dailySquatGoal?.toFloat(),
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
        }

        // Activity now carries the movement trends only. Waist, weight, blood
        // pressure, resting heart rate and sleep moved to Wellness; macros to
        // Fuel; vibe/energy/focus and pages-read to Wellness. TrendCard and the
        // moved cards live in TrendCards.kt so their new homes share them.
    }
}
