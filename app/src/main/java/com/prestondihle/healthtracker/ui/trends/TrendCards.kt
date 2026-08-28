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
import com.prestondihle.healthtracker.domain.MovingAverage
import com.prestondihle.healthtracker.domain.Readiness
import com.prestondihle.healthtracker.domain.ReadinessFacts
import com.prestondihle.healthtracker.domain.RunBreakdown
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.ui.components.AxisRule
import com.prestondihle.healthtracker.ui.components.AxisSpec
import com.prestondihle.healthtracker.ui.components.BarChart
import com.prestondihle.healthtracker.ui.components.ChartSeries
import com.prestondihle.healthtracker.ui.components.DayPoint
import com.prestondihle.healthtracker.ui.components.DualAxisTimeChart
import com.prestondihle.healthtracker.ui.components.LineChart
import com.prestondihle.healthtracker.ui.components.LineSeries
import com.prestondihle.healthtracker.ui.components.LineStyle
import com.prestondihle.healthtracker.ui.components.MultiLineChart
import com.prestondihle.healthtracker.ui.components.StackedBar
import com.prestondihle.healthtracker.ui.components.StackedBarChart
import com.prestondihle.healthtracker.ui.components.TimePoint
import com.prestondihle.healthtracker.ui.theme.LocalChartColors
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

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
        subtitle = state.subtitle(
            if (screenLimit == null) "inches" else "inches, limit ${
                Units.formatInches(screenLimit.toFloat())
            }"
        ),
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
    val readings = state.weightSeries(Units::kgToLbs)
    TrendCard(title = "Weight", subtitle = state.subtitle("pounds, Health Connect and manual")) {
        TrendWithAverage(
            readings = readings,
            average = state.trailingAverage(readings),
            label = "Weight",
            goalLine = state.goals.goalWeightKg?.let { Units.kgToLbs(it) },
            // Lighter than the goal, because they are on the way to it rather than
            // the point of it. The axis stretches to hold them, so a mark you have
            // not reached is still drawn.
            subGoalLines = state.weightSubGoals.map { Units.kgToLbs(it.kg) },
        )
    }
}

/**
 * A measured daily series with its trailing weekly mean drawn over it.
 *
 * The average is dashed and the readings are solid, which is the rule the whole
 * app is drawn to: a measurement is solid, a model says so. It is a model in the
 * ordinary sense here -- nothing was weighed at that value on that morning --
 * and on a chart where the reader's own goal is *also* dashed, the two are told
 * apart by colour and by the key rather than by stroke alone.
 *
 * Drawn second so it sits over the readings rather than under them, and given
 * the same colour family: it is the same quantity, said more slowly.
 *
 * Falls back to the bare line whenever the average is empty -- at the weekly
 * ranges, and on a window too thin to average -- because `MultiLineChart` shows
 * a key the moment it has more than one series, and a key naming a line nobody
 * can find is worse than no key at all.
 */
@Composable
private fun TrendWithAverage(
    readings: List<DayPoint>,
    average: List<DayPoint>,
    label: String,
    goalLine: Float? = null,
    subGoalLines: List<Float> = emptyList(),
) {
    val chartColors = LocalChartColors.current
    val modifier = Modifier.fillMaxWidth().height(140.dp)
    if (average.none { it.value != null }) {
        LineChart(
            days = readings,
            goalLine = goalLine,
            subGoalLines = subGoalLines,
            modifier = modifier,
        )
        return
    }
    MultiLineChart(
        series =
            listOf(
                LineSeries(
                    label = label,
                    points = readings,
                    color = MaterialTheme.colorScheme.primary,
                    style = LineStyle.SOLID,
                ),
                LineSeries(
                    label = "$label (${MovingAverage.WINDOW_DAYS}-day avg)",
                    points = average,
                    color = chartColors.movingAverage,
                    style = LineStyle.DASHED,
                ),
            ),
        goalLine = goalLine,
        subGoalLines = subGoalLines,
        modifier = modifier,
    )
}

@Composable
internal fun BloodPressureTrendCard(state: TrendsUiState) {
    val chartColors = LocalChartColors.current
    // Daily sodium, plotted at midday of each day it was recorded. Optional in the
    // literal sense: absent entirely on a source that does not report it, and on
    // every day synced before the app read it, so the chart is unchanged for
    // anyone it has nothing to say to.
    val sodium =
        state.snapshotSeries { it.sodiumMg }
            .mapNotNull { point ->
                // Days with no figure are dropped rather than plotted at zero: a
                // day nobody logged food on did not contain no salt.
                point.value?.let {
                    TimePoint(point.date.atTime(12, 0).atZone(state.zoneId).toInstant(), it)
                }
            }
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
                ) +
                    // Its own scale rather than the mmHg axis: milligrams of salt
                    // and millimetres of mercury share nothing but a chart, and
                    // drawing sodium against the pressure gutter would put a
                    // 2,400 mg day somewhere off the top of the plot.
                    listOfNotNull(
                        sodium
                            .takeIf { it.isNotEmpty() }
                            ?.let {
                                ChartSeries(
                                    label = "Sodium (mg)",
                                    points = it,
                                    color = chartColors.sodium,
                                    scale = AxisSpec(min = 0f, max = 4_000f, label = "mg"),
                                )
                            }
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
        if (sodium.isNotEmpty()) {
            Text(
                "Sodium is drawn as context, not as a cause: it is a daily total against readings " +
                    "taken at a moment, and one day's salt does not move one morning's pressure.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun RestingHeartRateTrendCard(state: TrendsUiState) {
    val readings = state.snapshotSeries { it.restingHeartRateBpm?.toFloat() }
    TrendCard(title = "Resting heart rate", subtitle = state.subtitle("bpm")) {
        TrendWithAverage(
            readings = readings,
            average = state.trailingAverage(readings),
            label = "Resting HR",
        )
    }
}

@Composable
internal fun SleepTrendCard(state: TrendsUiState) {
    TrendCard(title = "Sleep", subtitle = state.subtitle("hours")) {
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
internal fun RunsTrendCard(runs: List<RunBreakdown>, range: TrendsRange) {
    val chartColors = LocalChartColors.current
    val zone = ZoneId.systemDefault()
    val legend =
        listOf(
            "Easy" to chartColors.runEasy,
            "Moderate" to chartColors.runModerate,
            "Hard" to chartColors.runHard,
            "Intense" to chartColors.runIntense,
        )
    // Never bucketed and never widened past a quarter: a bar here is one session
    // rather than one day, so there is nothing to average into a week, and the
    // window is stated because at the two long chips it is narrower than the one
    // the reader tapped.
    TrendCard(
        title = "Runs",
        subtitle = "minutes, by heart-rate zone, last ${range.effectiveLabel}",
    ) {
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

private val RECORD_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy")

/**
 * The best of each thing, and how many days each habit has run.
 *
 * Every figure carries the date it was set, because a best with no date is a
 * claim with nothing behind it -- and on a card meant to be worth glancing at,
 * the difference between a grip figure from last week and one from two summers
 * ago is most of what it has to say.
 *
 * Records nobody has set yet are left out rather than shown blank. A column of
 * em dashes is a list of things the reader has failed to do, which is the
 * opposite of what a personal-bests card is for; the empty case says so once, in
 * a sentence, and stops.
 */
@Composable
internal fun RecordsCard(state: RecordsUiState) {
    val records = state.records
    TrendCard(title = "Records and streaks", subtitle = "your own best, and what is running") {
        val streaks =
            listOf(
                "Step goal" to state.stepStreak,
                "Protein" to state.proteinStreak,
                "Supplements" to state.supplementStreak,
            )
        // A streak with nothing to measure against is dropped, not drawn as a
        // nought: a reader who has never set a protein target has not broken a
        // protein streak.
        val running = streaks.filter { it.second.available }
        if (running.isNotEmpty()) {
            running.forEach { (label, streak) ->
                RecordRow(
                    label = label,
                    value = if (streak.current == 1) "1 day" else "${streak.current} days",
                    note = if (streak.best > streak.current) "best ${streak.best}" else "best ever",
                )
            }
        }

        if (records.isEmpty) {
            Text(
                if (running.isEmpty())
                    "Nothing logged yet that could set a record. Grip, a fitness test, a " +
                        "finished fast or a covered day of blood sugar will each fill a row."
                else "No personal bests yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@TrendCard
        }

        records.gripDominant?.let {
            RecordRow("Grip, dominant", "${Units.kgToWholeLbs(it.value)} lb", on = it.date)
        }
        records.gripNonDominant?.let {
            RecordRow("Grip, other hand", "${Units.kgToWholeLbs(it.value)} lb", on = it.date)
        }
        // The two-mile from a recorded test, never the projection the AFT card
        // prints: on a card headed with the word "records", a model would be read
        // as something that was actually run.
        records.twoMile?.let { RecordRow("Two mile", Units.formatPace(it.value), on = it.date) }
        records.deadlift?.let {
            RecordRow("Deadlift, 3RM", "${Units.kgToWholeLbs(it.value)} lb", on = it.date)
        }
        records.longestFast?.let {
            RecordRow("Longest fast", Units.formatDuration(it.value), on = it.date)
        }
        records.timeInRange?.let {
            RecordRow(
                label = "Best day in range",
                value = "${(it.value * 100).roundToInt()}%",
                on = it.date,
                // Said out loud because it is the one record here that cannot
                // reach the whole archive, and a "best ever" that quietly means
                // "best this year" is the kind of claim this app does not make.
                // Printed in days like the Runs card's own cap rather than as
                // "1 year", which is both stilted and wrong the moment the
                // constant moves.
                note = "last ${state.historyDays} days",
            )
        }
    }
}

@Composable
private fun RecordRow(label: String, value: String, on: LocalDate? = null, note: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val caption = note ?: on?.let { RECORD_DATE_FORMAT.format(it) }
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What the day had left over: calories eaten minus calories burned.
 *
 * The other half of the macros card above it. That one is what went in, and on
 * its own it cannot say whether it was a lot -- two thousand calories is a
 * deficit on a day with a long ruck in it and a surplus on a day at a desk.
 *
 * The rule at zero is not a target and is not drawn as one. It is where eating
 * and burning met, so the caption names the sides rather than leaving a bare
 * line in the goal's colour to be read as "aim for nothing". It is passed as the
 * goal purely because that is the mechanism that folds a mark into the axis --
 * a run of deficit days scaled to themselves would put every point below a zero
 * that had been clipped off the top, which is the failure `chartBounds` exists
 * for and looks exactly like a chart with no reference at all.
 */
@Composable
internal fun NetCaloriesTrendCard(state: TrendsUiState) {
    val readings = state.netCalorieSeries
    TrendCard(
        title = "Net calories",
        subtitle = state.subtitle("eaten minus burned; below the line is a deficit"),
    ) {
        TrendWithAverage(
            readings = readings,
            average = state.trailingAverage(readings),
            label = "Net",
            goalLine = 0f,
        )
    }
}

@Composable
internal fun MacrosTrendCard(state: TrendsUiState) {
    val chartColors = LocalChartColors.current
    TrendCard(
        title = "Macros",
        subtitle = state.subtitle("calories from protein, carbs and fat"),
    ) {
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

/**
 * Blood oxygen saturation, night by night.
 *
 * Bounded 90-100 rather than 0-100 for the reason the glucose plot stops at 180:
 * a reading below ninety is a medical event and not a trend, so nine tenths of a
 * full-range axis is space no point ever occupies, and spending it flattens the
 * two or three points that actually move. The chart still widens to fit an
 * outlier, so a genuine 88 plots -- it is simply not budgeted for.
 */
@Composable
internal fun Spo2TrendCard(state: TrendsUiState) {
    TrendCard(title = "Blood oxygen", subtitle = state.subtitle("% saturation, overnight average")) {
        LineChart(
            days = state.snapshotSeries { it.spo2Percent },
            modifier = Modifier.fillMaxWidth().height(140.dp),
            minY = 90f,
            maxY = 100f,
        )
    }
}

/**
 * The morning's two facts, side by side and never combined.
 *
 * Deliberately not a readiness *score*. A composite number blends things measured
 * in different units on different confidences with weights nobody publishes, and
 * cannot be argued with -- told "readiness 61" there is nothing to check. Told
 * the heart rate is six over its own baseline and the night was five-forty, the
 * reader knows which half moved and whether they believe it.
 *
 * Model-labelled, because a trailing median is a model: it is a claim about what
 * ordinary looks like for this reader, assembled from their own past mornings
 * rather than measured this morning.
 */
@Composable
internal fun ReadinessCard(readiness: Readiness) {
    TrendCard(title = "This morning", subtitle = "against your own last 30 days") {
        if (!readiness.hasAnything) {
            Text(
                "Nothing recorded for this morning yet. A resting heart rate needs a night's " +
                    "wear, and the baseline needs ${ReadinessFacts.MIN_BASELINE_DAYS} " +
                    "mornings before it means anything.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@TrendCard
        }

        val delta = readiness.restingDeltaBpm
        when {
            delta != null ->
                Text(
                    when {
                        delta > 0 -> "Resting heart rate ${delta} bpm over baseline."
                        delta < 0 -> "Resting heart rate ${-delta} bpm under baseline."
                        else -> "Resting heart rate on baseline."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    // Only the wrong direction is coloured. A green line every
                    // ordinary morning turns the colour into decoration, and then
                    // the one morning it means something reads as decoration too.
                    color =
                        if (delta > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                )
            // A reading with nothing to compare it against is still worth printing:
            // it is a measurement, and the baseline is what is missing.
            readiness.restingBpm != null ->
                Text(
                    "Resting heart rate ${readiness.restingBpm} bpm. " +
                        "Not enough mornings yet for a baseline.",
                    style = MaterialTheme.typography.bodyMedium,
                )
        }

        readiness.sleepMinutes?.let { slept ->
            val short = readiness.sleepDeficitMinutes
            Text(
                buildString {
                    append(Units.formatMinutes(slept))
                    append(" asleep")
                    when {
                        short == null -> append(".")
                        short > 0 -> append(", ${Units.formatMinutes(short)} under goal.")
                        else -> append(", goal met.")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (short != null && short > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
        }

        Text(
            "Two facts rather than a readiness score: a single blended number cannot say which " +
                "half of it moved. The baseline is the median of your own last " +
                "${ReadinessFacts.BASELINE_DAYS} days, excluding today.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
