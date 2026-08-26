package com.prestondihle.healthtracker.ui.trends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.ui.components.AxisRule
import com.prestondihle.healthtracker.ui.components.AxisSpec
import com.prestondihle.healthtracker.ui.components.BarChart
import com.prestondihle.healthtracker.ui.components.ChartSeries
import com.prestondihle.healthtracker.ui.components.DualAxisTimeChart
import com.prestondihle.healthtracker.ui.components.LineChart
import com.prestondihle.healthtracker.ui.components.LineSeries
import com.prestondihle.healthtracker.ui.theme.LocalChartColors
import com.prestondihle.healthtracker.ui.components.LineStyle
import com.prestondihle.healthtracker.ui.components.MultiLineChart
import com.prestondihle.healthtracker.ui.components.StackedBarChart
import com.prestondihle.healthtracker.ui.components.TimePoint

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

        item {
            TrendCard(title = "Waist", subtitle = "inches") {
                LineChart(
                    days = state.waistSeries(Units::cmToInches),
                    goalLine = state.goals.goalWaistCm?.let { Units.cmToInches(it) },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
        }

        item {
            TrendCard(title = "Weight", subtitle = "pounds, Health Connect and manual") {
                LineChart(
                    days = state.weightSeries(Units::kgToLbs),
                    goalLine = state.goals.goalWeightKg?.let { Units.kgToLbs(it) },
                    // Lighter than the goal, because they are on the way to it
                    // rather than the point of it. The axis stretches to hold
                    // them, so a mark you have not reached is still drawn.
                    subGoalLines = state.weightSubGoals.map { Units.kgToLbs(it.kg) },
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
            TrendCard(title = "Blood pressure", subtitle = "mmHg") {
                // Plotted against real timestamps rather than an index: readings
                // are taken irregularly, and evenly spacing them would imply a
                // cadence that is not there.
                DualAxisTimeChart(
                    windowStart = state.startDate.atStartOfDay(state.zoneId).toInstant(),
                    windowEnd = state.endDate.plusDays(1).atStartOfDay(state.zoneId).toInstant(),
                    zoneId = state.zoneId,
                    series =
                        listOf(
                            ChartSeries(
                                label = "Systolic",
                                points =
                                    state.bloodPressure.map {
                                        TimePoint(it.timestamp, it.systolic.toFloat())
                                    },
                                color = chartColors.systolic,
                            ),
                            ChartSeries(
                                label = "Diastolic",
                                points =
                                    state.bloodPressure.map {
                                        TimePoint(it.timestamp, it.diastolic.toFloat())
                                    },
                                color = chartColors.diastolic,
                            ),
                        ),
                    // A rule per line. One alone left the diastolic trace with
                    // nothing to be read against, which is half the reading.
                    // Dashed, because 120/80 is a published figure rather than
                    // one invented here -- adjustable in settings because a
                    // clinician may have named different numbers, not because
                    // the reader is free to decide what normal is.
                    leftAxis =
                        AxisSpec(
                            min = 60f,
                            max = 140f,
                            label = "mmHg",
                            rules =
                                listOfNotNull(
                                    state.goals.bloodPressureSystolicReference?.let {
                                        AxisRule(it.toFloat())
                                    },
                                    state.goals.bloodPressureDiastolicReference?.let {
                                        AxisRule(it.toFloat())
                                    },
                                ),
                        ),
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
            }
        }

        item {
            TrendCard(title = "Resting heart rate", subtitle = "bpm") {
                LineChart(
                    days = state.snapshotSeries { it.restingHeartRateBpm?.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
        }

        item {
            TrendCard(title = "Sleep", subtitle = "hours") {
                BarChart(
                    days = state.snapshotSeries { snap -> snap.sleepMinutes?.let { it / 60f } },
                    // In hours, because that is what the bars are. The target is
                    // stored in minutes so that seven and a half survives the
                    // round trip through the stepper.
                    goalLine = state.goals.sleepMinutesGoal?.let { it / 60f },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
        }

        item {
            TrendCard(title = "Macros", subtitle = "calories from protein, carbs and fat") {
                StackedBarChart(
                    bars = state.macroBars,
                    colors = listOf(chartColors.proteinStack, chartColors.carbStack, chartColors.fatStack),
                    goalLine = state.goals.dailyCalorieTarget?.toFloat(),
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                            "Protein" to chartColors.proteinStack,
                            "Carbs" to chartColors.carbStack,
                            "Fat" to chartColors.fatStack,
                        )
                        .forEach { (label, color) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Canvas(modifier = Modifier.size(8.dp)) { drawCircle(color) }
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                }
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

        // Vibe/energy/focus and pages-read trends moved to the Wellness screen,
        // where they sit under the sliders and page control that feed them.
    }
}

@Composable
private fun TrendCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}
