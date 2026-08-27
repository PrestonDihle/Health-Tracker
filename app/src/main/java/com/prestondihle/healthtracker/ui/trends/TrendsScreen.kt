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
import com.prestondihle.healthtracker.ui.reorder.CardOrderViewModel
import com.prestondihle.healthtracker.ui.reorder.ReorderableCard
import com.prestondihle.healthtracker.ui.reorder.reorderableCards

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrendsScreen(viewModel: TrendsViewModel, orderViewModel: CardOrderViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    val aft by viewModel.aft.collectAsStateWithLifecycle()
    val savedOrder by orderViewModel.savedOrder.collectAsStateWithLifecycle()
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

        // Activity carries the movement trends. Waist, weight, blood pressure,
        // resting heart rate and sleep moved to Wellness; macros to Fuel;
        // vibe/energy/focus and pages-read to Wellness. TrendCard and the moved
        // cards live in TrendCards.kt so their new homes share them.
        reorderableCards(
            cards =
                listOf(
                    ReorderableCard("steps") {
                        TrendCard(title = "Steps", subtitle = "from Health Connect") {
                            BarChart(
                                days = state.snapshotSeries { it.steps?.toFloat() },
                                goalLine = state.goals.dailyStepGoal?.toFloat(),
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                            )
                        }
                    },
                    // Runs, each bar a session split by heart-rate zone; read live
                    // and zoned against the max heart rate in Settings.
                    ReorderableCard("runs") { RunsTrendCard(runs) },
                    // The AFT sits on Activity because it is the one thing here
                    // that is a test rather than a trend -- and its score moves
                    // on the same training the rest of this tab plots.
                    ReorderableCard("aft") {
                        AftCard(
                            state = aft,
                            onSave = {
                                if (it.id == 0L) viewModel.addAftAttempt(it)
                                else viewModel.updateAftAttempt(it)
                            },
                            onDelete = viewModel::deleteAftAttempt,
                        )
                    },
                    // Both hands on one chart, because the comparison between them
                    // is the point: a gap that widens over months says something
                    // neither line says on its own.
                    ReorderableCard("grip") {
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
                    },
                    ReorderableCard("pushups") {
                        TrendCard(title = "Pushups", subtitle = "reps per day") {
                            BarChart(
                                days = state.repSeries(MovementType.PUSHUP),
                                goalLine = state.goals.dailyPushupGoal?.toFloat(),
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                            )
                        }
                    },
                    ReorderableCard("airSquats") {
                        TrendCard(title = "Air squats", subtitle = "reps per day") {
                            BarChart(
                                days = state.repSeries(MovementType.AIR_SQUAT),
                                goalLine = state.goals.dailySquatGoal?.toFloat(),
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                            )
                        }
                    },
                ),
            savedOrder = savedOrder,
            onMove = orderViewModel::move,
        )
    }
}
