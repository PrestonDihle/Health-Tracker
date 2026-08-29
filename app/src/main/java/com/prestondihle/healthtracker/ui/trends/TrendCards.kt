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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
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
import com.prestondihle.healthtracker.ui.components.CardFoldButton
import com.prestondihle.healthtracker.ui.components.ChartAxis
import com.prestondihle.healthtracker.ui.components.LocalCardFold
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
            val fold = LocalCardFold.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                fold?.let { CardFoldButton(it) }
            }
            if (fold?.collapsed == true) return@Column
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
    val chartColors = LocalChartColors.current
    val plain = state.weightSeries(Units::kgToLbs)
    // The projection extends the slot list, so the readings it is drawn beside
    // have to be the padded copy: the chart maps a point to an x by its index,
    // and two series of different lengths would put every reading under the
    // wrong day.
    val projected = state.weightProjectionSeries(Units::kgToLbs)
    val readings = projected?.first ?: plain
    val eta = state.weightEta

    TrendCard(title = "Weight", subtitle = state.subtitle("pounds, Health Connect and manual")) {
        TrendWithAverage(
            readings = readings,
            average = state.trailingAverage(plain).padTo(readings),
            label = "Weight",
            goalLine = state.goals.goalWeightKg?.let { Units.kgToLbs(it) },
            // Lighter than the goal, because they are on the way to it rather than
            // the point of it. The axis stretches to hold them, so a mark you have
            // not reached is still drawn.
            subGoalLines = state.weightSubGoals.map { Units.kgToLbs(it.kg) },
            extra =
                projected?.second?.let {
                    LineSeries(
                        label = "At current pace",
                        points = it,
                        color = chartColors.threshold,
                        style = LineStyle.DOTTED,
                    )
                },
        )
        if (eta != null) {
            Text(
                "${Units.kgToWholeLbs(eta.target)} lb by ${ETA_DATE_FORMAT.format(eta.reachedOn)} " +
                    "at current pace.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val ETA_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM")

/**
 * Stretches a series onto a longer slot list with nulls.
 *
 * The moving average is computed over the readings alone and knows nothing about
 * the projection's lead, so without this it would be short by the lead and the
 * chart would draw it across the full width with every point over the wrong day.
 */
private fun List<DayPoint>.padTo(slots: List<DayPoint>): List<DayPoint> {
    if (isEmpty() || size >= slots.size) return this
    val known = associate { it.date to it.value }
    return slots.map { DayPoint(it.date, known[it.date]) }
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
    /** An optional third line, already on the readings' slots. */
    extra: LineSeries? = null,
) {
    val chartColors = LocalChartColors.current
    val modifier = Modifier.fillMaxWidth().height(140.dp)
    val hasAverage = average.any { it.value != null }
    if (!hasAverage && extra == null) {
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
            listOfNotNull(
                LineSeries(
                    label = label,
                    points = readings,
                    color = MaterialTheme.colorScheme.primary,
                    style = LineStyle.SOLID,
                ),
                if (!hasAverage) null
                else
                    LineSeries(
                        label = "$label (${MovingAverage.WINDOW_DAYS}-day avg)",
                        points = average,
                        color = chartColors.movingAverage,
                        style = LineStyle.DASHED,
                    ),
                extra,
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

/**
 * Any two daily series on one plot, each with a gutter of its own.
 *
 * No new chart code: `DualAxisTimeChart` has been dual-axis since the master
 * graph needed it, and what is new here is the pairing. A gutter each is what
 * makes the card work at all -- steps run to five figures and sleep to single
 * ones, and on a shared scale the sleep line is a flat rule along the floor.
 *
 * **It draws two lines and claims nothing about them.** There is no correlation
 * figure and no fitted anything, deliberately: a number would turn "these two
 * moved together over three weeks" into a finding, on data with no controls, a
 * sample of one and whatever else was happening those weeks. The reader can see
 * a relationship or fail to, which is the honest limit of what a chart of two
 * daily series supports.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompareCard(
    state: CompareUiState,
    onPick: (ComparableMetric, ComparableMetric) -> Unit,
    onLag: (Boolean) -> Unit,
) {
    val chartColors = LocalChartColors.current
    TrendCard(title = "Compare", subtitle = "any two, one gutter each") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricPicker(
                selected = state.first,
                // A metric cannot be compared with itself: two identical lines
                // teach nothing and the second gutter would repeat the first.
                exclude = state.second,
                onSelect = { onPick(it, state.second) },
            )
            MetricPicker(
                selected = state.second,
                exclude = state.first,
                onSelect = { onPick(state.first, it) },
            )
        }

        if (state.isEmpty) {
            Text(
                "Neither of these has anything logged in this window.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@TrendCard
        }

        DualAxisTimeChart(
            windowStart = state.startDate.atStartOfDay(state.zoneId).toInstant(),
            // The lag shifts a point onto tomorrow, so the window has to reach a
            // day further or the newest point of the second series is clipped --
            // and clipped silently, which on this card would look like a metric
            // that simply stops before the other.
            windowEnd =
                state.endDate.plusDays(if (state.lagSecond) 2 else 1)
                    .atStartOfDay(state.zoneId)
                    .toInstant(),
            series =
                listOfNotNull(
                    state.first.chartSeries(
                        state.firstPoints,
                        chartColors.gripDominant,
                        ChartAxis.LEFT,
                        state.zoneId,
                    ),
                    state.second.chartSeries(
                        state.secondPoints,
                        chartColors.gripNonDominant,
                        ChartAxis.RIGHT,
                        state.zoneId,
                    ),
                ),
            leftAxis = state.first.axis(state.firstPoints, chartColors.gripDominant),
            rightAxis = state.second.axis(state.secondPoints, chartColors.gripNonDominant),
            zoneId = state.zoneId,
            modifier = Modifier.fillMaxWidth().height(180.dp),
            contentDescription =
                "${state.first.label} against ${state.second.label} over the chosen window",
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.lagSecond, onCheckedChange = onLag)
            Spacer(Modifier.width(8.dp))
            Text(
                // Named by what it is for rather than by what it does: "+1 day"
                // says nothing about which way, and the direction is the entire
                // content of the control.
                "Shift ${state.second.label} a day later",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (state.lagSecond) {
            Text(
                "Each ${state.second.label} point is drawn on the following day, so a cause " +
                    "sits under its effect.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricPicker(
    selected: ComparableMetric,
    exclude: ComparableMetric,
    onSelect: (ComparableMetric) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(selected.label) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ComparableMetric.entries
                .filter { it != exclude }
                .forEach { metric ->
                    DropdownMenuItem(
                        text = { Text(metric.label) },
                        onClick = {
                            onSelect(metric)
                            open = false
                        },
                    )
                }
        }
    }
}

/** Day-indexed points at midday, which is where a daily figure belongs on a clock. */
private fun ComparableMetric.chartSeries(
    points: List<DayPoint>,
    color: Color,
    axis: ChartAxis,
    zoneId: ZoneId,
): ChartSeries? {
    val drawn =
        points.mapNotNull { point ->
            point.value?.let { TimePoint(point.date.atTime(12, 0).atZone(zoneId).toInstant(), it) }
        }
    if (drawn.isEmpty()) return null
    // Bare label: the legend appends the unit from the axis this series is read
    // against, so spelling it here gives "Steps (steps) (steps)".
    return ChartSeries(label = label, points = drawn, color = color, axis = axis)
}

/**
 * A gutter scaled to this series alone.
 *
 * Padded a tenth either side so a flat run does not sit exactly on the frame,
 * and floored at zero for the counts, where a negative gridline is a value the
 * metric cannot take. Net calories is the exception and keeps its negatives,
 * since a deficit is the point of it.
 */
private fun ComparableMetric.axis(points: List<DayPoint>, color: Color): AxisSpec {
    val values = points.mapNotNull { it.value }
    val low = values.minOrNull() ?: 0f
    val high = values.maxOrNull() ?: 1f
    val pad = ((high - low) * 0.1f).takeIf { it > 0f } ?: 1f
    val floorAtZero = this != ComparableMetric.NET_CALORIES
    return AxisSpec(
        min = if (floorAtZero) maxOf(0f, low - pad) else low - pad,
        max = high + pad,
        label = unit,
        format = { "%.${decimals}f".format(it) },
        color = color,
    )
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
internal fun RecordsCard(state: RecordsUiState, streaks: StreaksUiState) {
    val records = state.records
    TrendCard(title = "Records and streaks", subtitle = "your own best, and what is running") {
        // A streak with nothing to measure against is dropped, not drawn as a
        // nought: a reader who has never set a protein target has not broken a
        // protein streak.
        val running = streaks.running
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
