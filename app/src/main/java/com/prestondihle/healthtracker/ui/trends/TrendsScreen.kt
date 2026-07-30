package com.prestondihle.healthtracker.ui.trends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.prestondihle.healthtracker.ui.components.AxisSpec
import com.prestondihle.healthtracker.ui.components.BarChart
import com.prestondihle.healthtracker.ui.components.ChartSeries
import com.prestondihle.healthtracker.ui.components.DualAxisTimeChart
import com.prestondihle.healthtracker.ui.components.LineChart
import com.prestondihle.healthtracker.ui.components.LineSeries
import com.prestondihle.healthtracker.ui.components.LineStyle
import com.prestondihle.healthtracker.ui.components.MultiLineChart
import com.prestondihle.healthtracker.ui.components.StackedBarChart
import com.prestondihle.healthtracker.ui.components.TimePoint
import com.prestondihle.healthtracker.ui.theme.CarbSeries
import com.prestondihle.healthtracker.ui.theme.DiastolicSeries
import com.prestondihle.healthtracker.ui.theme.EnergySeries
import com.prestondihle.healthtracker.ui.theme.FatSeries
import com.prestondihle.healthtracker.ui.theme.FocusSeries
import com.prestondihle.healthtracker.ui.theme.ProteinSeries
import com.prestondihle.healthtracker.ui.theme.SystolicSeries
import com.prestondihle.healthtracker.ui.theme.VibeSeries

@Composable
fun TrendsScreen(viewModel: TrendsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                color = SystolicSeries,
                            ),
                            ChartSeries(
                                label = "Diastolic",
                                points =
                                    state.bloodPressure.map {
                                        TimePoint(it.timestamp, it.diastolic.toFloat())
                                    },
                                color = DiastolicSeries,
                            ),
                        ),
                    // 120/80 is the reference both lines are read against.
                    leftAxis = AxisSpec(min = 60f, max = 140f, label = "mmHg", threshold = 120f),
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
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
        }

        item {
            TrendCard(title = "Macros", subtitle = "calories from protein, carbs and fat") {
                StackedBarChart(
                    bars = state.macroBars,
                    colors = listOf(ProteinSeries, CarbSeries, FatSeries),
                    goalLine = state.goals.dailyCalorieTarget?.toFloat(),
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                            "Protein" to ProteinSeries,
                            "Carbs" to CarbSeries,
                            "Fat" to FatSeries,
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

        // One chart rather than three. The three scores are submitted together
        // against the same 1-10 scale, and the question actually asked of them is
        // whether they move together -- which stacked separately took three
        // screenfuls of scrolling to answer.
        item {
            TrendCard(title = "Vibe, energy and focus", subtitle = "1 to 10") {
                MultiLineChart(
                    series =
                        listOf(
                            LineSeries(
                                label = "Vibe",
                                points = state.logSeries { it.vibe?.toFloat() },
                                color = VibeSeries,
                                style = LineStyle.SOLID,
                            ),
                            LineSeries(
                                label = "Energy",
                                points = state.logSeries { it.energy?.toFloat() },
                                color = EnergySeries,
                                style = LineStyle.DASHED,
                            ),
                            LineSeries(
                                label = "Focus",
                                points = state.logSeries { it.focus?.toFloat() },
                                color = FocusSeries,
                                style = LineStyle.DOTTED,
                            ),
                        ),
                    minY = 1f,
                    maxY = 10f,
                    modifier = Modifier.fillMaxWidth().height(170.dp),
                )
            }
        }

        item {
            TrendCard(title = "Pages read", subtitle = "per day") {
                BarChart(
                    days = state.logSeries { it.bookPagesRead?.toFloat() },
                    goalLine = state.goals.dailyPagesGoal?.toFloat(),
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
        }
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
