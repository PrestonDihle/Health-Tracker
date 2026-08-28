package com.prestondihle.healthtracker.ui.trends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prestondihle.healthtracker.domain.BodyComposition
import com.prestondihle.healthtracker.domain.RunBreakdown
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.ui.components.AxisRule
import com.prestondihle.healthtracker.ui.components.AxisSpec
import com.prestondihle.healthtracker.ui.components.BarChart
import com.prestondihle.healthtracker.ui.components.ChartSeries
import com.prestondihle.healthtracker.ui.components.DualAxisTimeChart
import com.prestondihle.healthtracker.ui.components.LineChart
import com.prestondihle.healthtracker.ui.components.StackedBar
import com.prestondihle.healthtracker.ui.components.StackedBarChart
import com.prestondihle.healthtracker.ui.components.TimePoint
import com.prestondihle.healthtracker.ui.theme.LocalChartColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------
// Reusable trend cards.
//
// Pulled out of TrendsScreen so the same chart can be shown wherever it belongs
// once the six tabs are filled: the body and vitals trends sit on Wellness next
// to what logs them, and macros sit on Fuel. Each takes only [TrendsUiState],
// so a screen shows one by handing it that screen's trends state -- no per-card
// plumbing at the call site.
// ---------------------------------------------------------------------------

/** A titled, optionally subtitled card wrapping one trend chart. */
@Composable
internal fun TrendCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
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

@Composable
internal fun WaistTrendCard(state: TrendsUiState) {
    // The body composition limit, drawn as a waist rather than as a ratio: at a
    // fixed height the screen is a horizontal line, and a line the tape can be
    // read against is worth more here than a number that has to be divided.
    // Hairline and finer-dashed, like a weight waypoint, because the chosen goal
    // above it is where the reader is going and this is only where the standard
    // stops -- two different kinds of line, and never the same weight.
    val screenLimit = BodyComposition.maxPassingWaistInches(state.settings.heightCm)
    TrendCard(
        title = "Waist",
        subtitle = if (screenLimit == null) "inches" else "inches, limit ${
            Units.formatInches(screenLimit.toFloat())
        }",
    ) {
        LineChart(
            days = state.waistSeries(Units::cmToInches),
            goalLine = state.goals.goalWaistCm?.let { Units.cmToInches(it) },
            subGoalLines = listOfNotNull(screenLimit?.toFloat()),
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
    }
}

@Composable
internal fun WeightTrendCard(state: TrendsUiState) {
    TrendCard(title = "Weight", subtitle = "pounds, Health Connect and manual") {
        LineChart(
            days = state.weightSeries(Units::kgToLbs),
            goalLine = state.goals.goalWeightKg?.let { Units.kgToLbs(it) },
            // Lighter than the goal, because they are on the way to it rather than
            // the point of it. The axis stretches to hold them, so a mark you have
            // not reached is still drawn.
            subGoalLines = state.weightSubGoals.map { Units.kgToLbs(it.kg) },
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
    }
}

@Composable
internal fun BloodPressureTrendCard(state: TrendsUiState) {
    val chartColors = LocalChartColors.current
    TrendCard(title = "Blood pressure", subtitle = "mmHg") {
        // Plotted against real timestamps rather than an index: readings are taken
        // irregularly, and evenly spacing them would imply a cadence not there.
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
            // A rule per line. One alone left the diastolic trace with nothing to
            // be read against, which is half the reading. Dashed, because 120/80 is
            // a published figure rather than one invented here -- adjustable in
            // settings because a clinician may have named different numbers, not
            // because the reader is free to decide what normal is.
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

@Composable
internal fun RestingHeartRateTrendCard(state: TrendsUiState) {
    TrendCard(title = "Resting heart rate", subtitle = "bpm") {
        LineChart(
            days = state.snapshotSeries { it.restingHeartRateBpm?.toFloat() },
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
    }
}

@Composable
internal fun SleepTrendCard(state: TrendsUiState) {
    TrendCard(title = "Sleep", subtitle = "hours") {
        BarChart(
            days = state.snapshotSeries { snap -> snap.sleepMinutes?.let { it / 60f } },
            // In hours, because that is what the bars are. The target is stored in
            // minutes so that seven and a half survives the round trip through the
            // stepper.
            goalLine = state.goals.sleepMinutesGoal?.let { it / 60f },
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
    }
}

/**
 * One stacked bar per run, as tall as the run is long, split by heart-rate zone.
 *
 * Green through red, low effort to high, so a bar reads harder the warmer it is.
 * The height is minutes rather than distance, which is the choice that lets a
 * short hard interval and a long easy one look as different as they felt.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RunsTrendCard(runs: List<RunBreakdown>) {
    val chartColors = LocalChartColors.current
    val zone = ZoneId.systemDefault()
    val legend =
        listOf(
            "Easy" to chartColors.runEasy,
            "Moderate" to chartColors.runModerate,
            "Hard" to chartColors.runHard,
            "Intense" to chartColors.runIntense,
        )
    TrendCard(title = "Runs", subtitle = "minutes, by heart-rate zone") {
        StackedBarChart(
            bars =
                runs.map { StackedBar(date = it.start.atZone(zone).toLocalDate(), segments = it.segments) },
            colors = legend.map { it.second },
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            legend.forEach { (label, color) ->
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

@Composable
internal fun MacrosTrendCard(state: TrendsUiState) {
    val chartColors = LocalChartColors.current
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

/**
 * This week's training so far, one row per kind.
 *
 * Sessions and minutes for everything; distance only where the source recorded
 * one, and a pace only where the activity travels on foot. A strength session
 * genuinely has no distance, so a row reading "0.0 mi" would be a measurement
 * nobody made -- the figure is simply absent instead.
 *
 * Rucks are the reason this card exists. They were previously invisible: the
 * session read filtered to running, so an hour under a loaded pack reached the
 * app as nothing at all while a twenty-minute jog got its own bar on the chart
 * above.
 */
@Composable
internal fun TrainingVolumeCard(state: TrainingWeekState) {
    TrendCard(
        title = "Training this week",
        subtitle = "since ${WEEK_START_FORMAT.format(state.weekStart)}",
    ) {
        if (state.volumes.isEmpty()) {
            Text(
                "No sessions recorded yet this week.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@TrendCard
        }

        state.volumes.forEach { volume ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        volume.type.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "${volume.sessions} session${if (volume.sessions == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Units.formatMinutes(volume.totalMinutes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    val detail = buildList {
                        volume.totalMeters?.let {
                            add("%.1f mi".format(Units.metresToMiles(it)))
                        }
                        volume.paceSecondsPerMile?.let {
                            add("${Units.formatPace(it)}/mi")
                        }
                    }
                    if (detail.isNotEmpty()) {
                        Text(
                            detail.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** `Mon 24 Aug`, naming the week's first day without spending a line on it. */
private val WEEK_START_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM")
