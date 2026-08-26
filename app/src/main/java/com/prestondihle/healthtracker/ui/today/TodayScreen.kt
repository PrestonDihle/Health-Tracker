package com.prestondihle.healthtracker.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.data.HeartRateBucket
import com.prestondihle.healthtracker.data.MealEntry
import com.prestondihle.healthtracker.data.StepBucket
import com.prestondihle.healthtracker.domain.Glucose
import com.prestondihle.healthtracker.domain.Macro
import com.prestondihle.healthtracker.domain.Ketones
import com.prestondihle.healthtracker.domain.MacroAbsorption
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.health.HealthPermissionState
import com.prestondihle.healthtracker.ui.components.AxisRule
import com.prestondihle.healthtracker.ui.components.AxisSpec
import com.prestondihle.healthtracker.ui.components.ChartAxis
import com.prestondihle.healthtracker.ui.components.ChartMarker
import com.prestondihle.healthtracker.ui.components.ChartSeries
import com.prestondihle.healthtracker.ui.components.ChartShade
import com.prestondihle.healthtracker.ui.components.DualAxisTimeChart
import com.prestondihle.healthtracker.ui.components.MealDraft
import com.prestondihle.healthtracker.ui.components.MealEntryDialog
import com.prestondihle.healthtracker.ui.components.SeriesKind
import com.prestondihle.healthtracker.ui.components.TimePoint
import com.prestondihle.healthtracker.ui.theme.ChartColors
import com.prestondihle.healthtracker.ui.theme.LocalChartColors
import com.prestondihle.healthtracker.ui.theme.Pine
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

private val CardGap = 10.dp
private val CardPadding = 12.dp
private val ChartHeight = 300.dp

/** Sized to sit on a card's title row without stretching it. */
private val CompactButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

/**
 * Everything on one timeline: the macros of each meal spread into the hours they
 * are actually being absorbed over, drawn against the blood sugar, ketones,
 * heart rate and walking they are meant to explain.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(viewModel: TodayViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = CardGap),
        verticalArrangement = Arrangement.spacedBy(CardGap),
        contentPadding = PaddingValues(vertical = CardGap),
    ) {
        item {
            // Six windows do not fit on one row of a phone, and a row that
            // scrolls sideways hides the widest options behind a gesture nobody
            // knows is there. Wrapping shows all six at once.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MasterRange.entries.forEach { option ->
                    FilterChip(
                        selected = state.range == option,
                        onClick = { viewModel.setRange(option) },
                        label = { Text(option.label) },
                    )
                }
            }
        }

        if (state.healthState != HealthPermissionState.GRANTED) {
            item {
                TodayCard(title = "Health Connect") {
                    Text(
                        "Meals and heart rate come from Health Connect. Connect it on the Today " +
                            "screen to fill this graph in; glucose and ketones logged by hand " +
                            "still appear without it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item { ActivityCard(state = state, onRefresh = viewModel::refresh) }

        item {
            CombinedChartCard(
                state = state,
                onToggleSeries = viewModel::setSeriesVisible,
                onToggleAxis = viewModel::toggleLabelledAxis,
                onPan = viewModel::panBy,
                onBackToNow = viewModel::backToNow,
            )
        }

        item { AbsorptionModelCard() }

        // Shown even when empty: it carries the only way to log a meal by hand,
        // and hiding that behind "there is already a meal here" would make the
        // control appear only once it was least needed.
        item {
            MealListCard(
                state = state,
                onAdd = viewModel::addMeal,
                onUpdate = viewModel::updateMeal,
                onDelete = viewModel::deleteMeal,
            )
        }
    }
}

@Composable
private fun TodayCard(
    title: String,
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                action?.invoke()
            }
            content()
        }
    }
}

/**
 * The day's totals, above the timeline that explains them.
 *
 * Both syncs hang off the one refresh here. The card reads the daily snapshot,
 * which only `syncHealthData` fills, while everything below it is drawn from the
 * time-series caches, which only `syncTimeSeries` writes -- and a screen that
 * ran one of them would show a day's steps over a chart that had never heard of
 * the walk, or the reverse. That is the failure the sleep card already shipped
 * once, reading "No sleep recorded yet" directly under an Activity card
 * displaying the very night it said it did not have.
 */
@Composable
private fun ActivityCard(state: TodayUiState, onRefresh: () -> Unit) {
    TodayCard(
        title = "Activity",
        action = {
            IconButton(onClick = onRefresh, enabled = !state.isSyncing) {
                Icon(Icons.Filled.Refresh, contentDescription = "Sync Health Connect")
            }
        },
    ) {
        val snapshot = state.snapshot
        Row(modifier = Modifier.fillMaxWidth()) {
            Metric(
                label = "Steps",
                value = snapshot?.steps?.toString() ?: "--",
                supporting = state.goals.dailyStepGoal?.let { "goal $it" },
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "Resting HR",
                value = snapshot?.restingHeartRateBpm?.let { "$it bpm" } ?: "--",
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "Sleep",
                value = snapshot?.sleepMinutes?.let { Units.formatMinutes(it) } ?: "--",
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "Best mile",
                value = state.bestMileSeconds?.let { Units.formatPace(it) } ?: "--",
                supporting = "avg pace",
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider()

        // Energy in, energy out, the difference, and where it came from -- all
        // one row so intake, expenditure and composition read together. Six
        // columns leaves no room for supporting text; the labels carry it.
        Row(modifier = Modifier.fillMaxWidth()) {
            Metric(
                label = "Eaten",
                // Zero rather than "--": food is hand-entered, so nothing logged
                // means nothing eaten, and the net figure below depends on it.
                value = state.caloriesEaten.toString(),
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "Burned",
                value = snapshot?.totalCalories?.toString() ?: "--",
                modifier = Modifier.weight(1f),
            )

            val net = state.netCalories
            Metric(
                label = "Net",
                // Blank only while the burn has not synced; a missing burn would
                // read as a surplus the size of the day's food.
                value = net?.let { if (it > 0) "+$it" else it.toString() } ?: "--",
                valueColor =
                    when {
                        net == null || net == 0 -> null
                        net > 0 -> MaterialTheme.colorScheme.error
                        else -> Pine
                    },
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "Protein",
                value = snapshot?.proteinGrams?.let { "${it.toInt()}g" } ?: "--",
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "Carbs",
                value = snapshot?.carbGrams?.let { "${it.toInt()}g" } ?: "--",
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "Fat",
                value = snapshot?.fatGrams?.let { "${it.toInt()}g" } ?: "--",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The lines one unit is carried by.
 *
 * Every series of that unit, whether or not it is currently switched on. The
 * visible ones are what decides the axis colour: see [axisColorFor].
 */
internal val AxisMetric.series: List<MasterSeries>
    get() = MasterSeries.entries.filter { it.metric == this }

/**
 * The colour this unit's numbers are printed in, or null for the ordinary grey.
 *
 * A colour only where the axis is serving exactly one line that is actually
 * drawn -- then the numbers unambiguously belong to it, which is the whole point
 * on a plot carrying six units. Where several lines share the unit there is no
 * honest answer: tinting mg/h in the carbohydrate colour would claim the
 * protein and fat curves are read against some other axis. Switching two of the
 * three off resolves it, and the axis takes the survivor's colour.
 */
internal fun TodayUiState.axisColorFor(metric: AxisMetric, colors: ChartColors): Color? =
    metric.series.filter(::isVisible).singleOrNull()?.colorIn(colors)

/**
 * The scale one unit is drawn against.
 *
 * One place, whether the unit ends up labelled down the side of the plot or
 * quietly mapped to its own range with the numbers in the legend. Two copies of
 * these bounds would let a series change shape as it moved between axes, which
 * is the one thing switching axes must not do.
 */
private fun TodayUiState.specFor(metric: AxisMetric, colors: ChartColors): AxisSpec =
    when (metric) {
        // No target band here, unlike the Today chart, which keeps one.
        //
        // The band is a backdrop for *one* series and this plot carries eight.
        // Shaded across the full width behind carbohydrate curves, step columns
        // and a heart rate trace, it stopped reading as "the glucose target" and
        // started reading as a region of the chart -- and with the sleep shade
        // now laid down underneath, two overlapping washes left the ground
        // saying two things at once. The reference rule stays: a single line at
        // one value cannot be mistaken for a region, and it is the part that
        // answers "above or below" on a plot this busy.
        AxisMetric.GLUCOSE ->
            AxisSpec(
                min = glucosePlotRange.start,
                max = glucosePlotRange.endInclusive,
                label = Glucose.UNIT,
                // Solid: the reader put this one wherever they wanted it. The
                // same rule the Today chart draws, so the two agree.
                rules = listOfNotNull(glucoseReference?.let { AxisRule(it, dashed = false) }),
                color = axisColorFor(metric, colors),
            )
        AxisMetric.MACROS ->
            AxisSpec(min = 0f, max = 40f, label = "g/h", color = axisColorFor(metric, colors))
        AxisMetric.HEART_RATE ->
            AxisSpec(min = 40f, max = 180f, label = "bpm", color = axisColorFor(metric, colors))
        AxisMetric.KETONES ->
            AxisSpec(
                min = Ketones.PLOT_MIN,
                max = Ketones.PLOT_MAX,
                label = Ketones.UNIT,
                format = Ketones::format,
                color = axisColorFor(metric, colors),
            )
        AxisMetric.STEPS ->
            AxisSpec(min = 0f, max = 1_200f, label = "steps/h", color = axisColorFor(metric, colors))
        // 200 mg is around two strong coffees still in the body at once, which
        // is where the dashboard's caffeine chart tops out; both plot the same
        // quantity and a reader moving between them should not have to re-learn
        // the height of a line.
        AxisMetric.CAFFEINE ->
            AxisSpec(min = 0f, max = 200f, label = "mg", color = axisColorFor(metric, colors))
    }

/**
 * Where a series is drawn: against a labelled side, or against a scale of its
 * own.
 *
 * Exactly one of the two is ever meaningful. [ChartSeries.scale] overrides
 * [ChartSeries.axis], so a unit that has been given a gutter must pass null
 * here, or it would go on being drawn to its private range while the numbers
 * printed beside it described something else.
 */
private fun TodayUiState.placementOf(
    metric: AxisMetric,
    colors: ChartColors,
): Pair<ChartAxis, AxisSpec?> =
    axisFor(metric)?.let { it to null } ?: (ChartAxis.LEFT to specFor(metric, colors))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CombinedChartCard(
    state: TodayUiState,
    onToggleSeries: (MasterSeries, Boolean) -> Unit,
    onToggleAxis: (AxisMetric) -> Unit,
    onPan: (Duration) -> Unit,
    onBackToNow: () -> Unit,
) {
    val chartColors = LocalChartColors.current

    fun series(
        key: MasterSeries,
        label: String,
        points: List<TimePoint>,
        color: Color,
        showPoints: Boolean = true,
        dashed: Boolean = false,
        breakOnGaps: Boolean = false,
        kind: SeriesKind = SeriesKind.LINE,
        barWidth: Duration? = null,
    ): Pair<MasterSeries, ChartSeries> {
        val (axis, scale) = state.placementOf(key.metric, chartColors)
        return key to
            ChartSeries(
                label = label,
                points = points,
                color = color,
                axis = axis,
                scale = scale,
                showPoints = showPoints,
                dashed = dashed,
                breakOnGaps = breakOnGaps,
                kind = kind,
                barWidth = barWidth,
            )
    }

    val allSeries =
        mapOf(
            series(
                key = MasterSeries.GLUCOSE,
                label = if (state.smoothGlucose) "Glucose (smoothed)" else "Glucose",
                points = state.glucoseCurve.map { TimePoint(it.first, it.second) },
                color = chartColors.glucose,
                // A CGM writes every few minutes; dots would merge into a band.
                showPoints = state.glucose.size <= 24,
                breakOnGaps = true,
            ),
            // Dashed throughout: the three macro curves are a model of what the
            // food is doing, not a measurement of it.
            series(
                key = MasterSeries.CARBS,
                label = "Carbs",
                points = state.absorptionCurve(Macro.CARB).asPoints(),
                color = chartColors.carbAbsorption,
                showPoints = false,
                dashed = true,
            ),
            series(
                key = MasterSeries.PROTEIN,
                label = "Protein",
                points = state.absorptionCurve(Macro.PROTEIN).asPoints(),
                color = chartColors.proteinAbsorption,
                showPoints = false,
                dashed = true,
            ),
            series(
                key = MasterSeries.FAT,
                label = "Fat",
                points = state.absorptionCurve(Macro.FAT).asPoints(),
                color = chartColors.fatAbsorption,
                showPoints = false,
                dashed = true,
            ),
            series(
                key = MasterSeries.HEART_RATE,
                label = "Heart rate",
                points = state.heartRate.map { TimePoint(it.timestamp, it.bpm.toFloat()) },
                color = chartColors.heartRate,
                showPoints = false,
                // A watch off the wrist leaves hours unrecorded, and joining
                // across them drew a smooth diagonal through the night that
                // looked exactly like a measurement.
                breakOnGaps = true,
            ),
            series(
                key = MasterSeries.KETONES,
                label = "Ketones",
                points = state.ketones.map { TimePoint(it.timestamp, it.ppm) },
                color = chartColors.ketone,
            ),
            // Bars, not a line: a step count belongs to the hour it was
            // accumulated over, and joining the hours would claim a walking rate
            // at instants when nothing was counted.
            series(
                key = MasterSeries.STEPS,
                label = "Steps",
                points = state.steps.map { TimePoint(it.timestamp, it.steps.toFloat()) },
                color = chartColors.steps,
                showPoints = false,
                kind = SeriesKind.BAR,
                barWidth = Duration.ofMinutes(StepBucket.BUCKET_MINUTES),
            ),
            // Dashed, alongside the macro curves and for the same reason: what is
            // measured is the dose and the minute it was drunk, and everything
            // between two doses is a half-life model of what became of it.
            series(
                key = MasterSeries.CAFFEINE,
                label = "Caffeine",
                points = state.caffeineCurve.asPoints(),
                color = chartColors.caffeine,
                showPoints = false,
                dashed = true,
            ),
        )

    TodayCard(title = "Food, blood and body") {
        // A window dragged off the clock has to say so. Everything else on this
        // screen -- the range chips, the "Right now" card above -- reads as live,
        // and a plot of last Tuesday under all of it looks exactly like a plot of
        // this afternoon.
        if (state.isPanned) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Weighted, so the chip is measured at its own width first and
                // the label takes what is left. Sharing the row evenly squeezed
                // "Back to now" onto two lines on a real phone, which reads as a
                // broken control rather than a compact one.
                Text(
                    state.windowLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                AssistChip(
                    onClick = onBackToNow,
                    label = { Text("Back to now", maxLines = 1) },
                )
            }
        }

        DualAxisTimeChart(
            windowStart = state.windowStart,
            windowEnd = state.windowEnd,
            zoneId = state.zoneId,
            // Filtered rather than drawn-then-hidden, so a switched-off series
            // also stops stretching the axis it shares.
            series = allSeries.filterKeys(state::isVisible).values.toList(),
            // A shortcut for putting a line away without leaving the plot. The
            // switches below are the control proper -- this only ever hides,
            // because a legend lists what is drawn and the row is gone the
            // moment it is off.
            //
            // Matched on the label the series was actually built with, which is
            // how "Glucose (smoothed)" still finds its own series.
            onSeriesTap = { label ->
                allSeries.entries.firstOrNull { it.value.label == label }?.key?.let {
                    onToggleSeries(it, false)
                }
            },
            // Falls back rather than throwing: the toggle refuses to empty the list,
            // but the field is public and a plot has to be drawn against
            // something regardless of who built the state.
            leftAxis = state.specFor(state.labelledAxes.firstOrNull() ?: AxisMetric.GLUCOSE, chartColors),
            rightAxis = state.labelledAxes.getOrNull(1)?.let { state.specFor(it, chartColors) },
            // A rule at each meal, so a rise in any of the other lines can be
            // read against the moment the food went in. Subdued, because at full
            // weight these were read as a carbohydrate spike -- and went on being
            // read as one after the carbohydrate line was switched off.
            markers =
                state.mealsInWindow.map { meal ->
                    ChartMarker(
                        time = meal.timestamp,
                        label = meal.markerLabel(),
                        subdued = true,
                    )
                },
            // The hours asleep, shaded rather than drawn as a ninth line. Sleep
            // is not a quantity to read off an axis here; it is the answer to
            // "why" for most of what the other lines do overnight -- the heart
            // rate floor, the flat glucose, the steps that stop. A line would
            // need a scale and a legend row to say something a change of ground
            // says at a glance.
            shades =
                state.sleepInWindow.map {
                    ChartShade(
                        start = it.start,
                        end = it.end,
                        color = chartColors.sleep,
                        label = "Asleep",
                    )
                },
            // This is the plot whose whole purpose is reading one series against
            // another in time, and every such question is asked in hours: did
            // the heart rate climb before the coffee or after it.
            verticalGridlines = true,
            onPan = onPan,
            contentDescription =
                "Food, blood and body plot. Tap to read every line at one moment, " +
                    "drag sideways to move back through time.",
            modifier = Modifier.fillMaxWidth().height(ChartHeight),
        )

        Text(
            "Tap the plot to read every line at one moment, or drag sideways to " +
                "go back through the day. Tapping a name in the key puts that " +
                "line away.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        AxisPicker(state = state, onToggle = onToggleAxis)

        HorizontalDivider()

        SeriesToggles(state = state, onToggle = onToggleSeries)
    }
}

/**
 * Which units get their numbers down the sides.
 *
 * Chips rather than switches, to keep them apart from the series toggles below:
 * these do not decide what is drawn, only what is labelled. A line whose unit is
 * unlabelled is still on the plot and still the right shape, with its range
 * printed in the legend -- which is the sentence the caption has to get across,
 * because otherwise unselecting a unit looks like it deleted the data.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AxisPicker(state: TodayUiState, onToggle: (AxisMetric) -> Unit) {
    Text(
        "Axis units",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AxisMetric.entries.forEach { metric ->
            FilterChip(
                selected = state.isLabelled(metric),
                onClick = { onToggle(metric) },
                label = { Text(metric.label) },
            )
        }
    }
    Text(
        "Up to two at a time, left then right. The rest still plot to their own " +
            "range, quoted in the legend.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A switch per line, coloured to match it.
 *
 * The swatch is what ties a row to its line -- the labels alone would mean
 * re-reading the legend to work out which switch does what.
 *
 * This row was briefly replaced by tapping names in the legend, which reads well
 * and hides badly: the legend sits at the foot of a 300dp card, the line
 * explaining that it had become the switch fell below the fold on a phone, and
 * the result was a chart with no visible way to choose what it drew. A control
 * has to be visible from where the reader is standing. The legend tap stays as
 * the shortcut it always should have been.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesToggles(
    state: TodayUiState,
    onToggle: (MasterSeries, Boolean) -> Unit,
) {
    val chartColors = LocalChartColors.current

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MasterSeries.entries.forEach { series ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Switch(
                    checked = state.isVisible(series),
                    onCheckedChange = { onToggle(series, it) },
                    colors =
                        SwitchDefaults.colors(
                            checkedThumbColor = series.colorIn(chartColors),
                            checkedTrackColor = series.colorIn(chartColors).copy(alpha = 0.4f),
                        ),
                    modifier = Modifier.scale(0.75f),
                )
                Text(
                    series.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The colour this series is drawn in — on the plot, in the key, on its switch
 * and in the axis gutter.
 *
 * Takes the palette rather than reading one, because this is an extension on an
 * enum and not a composable: the caller is the one standing inside a theme. Four
 * uses, one source, so a line cannot be one colour on the plot and another on
 * the control that turns it off.
 */
internal fun MasterSeries.colorIn(colors: ChartColors): Color =
    when (this) {
        MasterSeries.GLUCOSE -> colors.glucose
        MasterSeries.CARBS -> colors.carbAbsorption
        MasterSeries.PROTEIN -> colors.proteinAbsorption
        MasterSeries.FAT -> colors.fatAbsorption
        MasterSeries.HEART_RATE -> colors.heartRate
        MasterSeries.KETONES -> colors.ketone
        MasterSeries.STEPS -> colors.steps
        MasterSeries.CAFFEINE -> colors.caffeine
    }

/** Where the absorption curves come from, since they are the one modelled thing here. */
@Composable
private fun AbsorptionModelCard() {
    TodayCard(title = "About the food curves") {
        Text(
            "Health Connect records a meal as one lump of grams at one time. The dashed lines " +
                "spread each meal over the hours it is actually reaching the blood, so the area " +
                "under a curve is the grams eaten and its height is grams per hour arriving.",
            style = MaterialTheme.typography.bodySmall,
        )
        Macro.entries.forEach { macro ->
            val kinetics = MacroAbsorption.kinetics(macro)
            Text(
                "${macro.label}: starts ${kinetics.lag.toMinutes()} min after eating, " +
                    "peaks at ${kinetics.timeToPeak.asPeak()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Peak times are population averages for mixed meals, from published gastric " +
                "emptying, aminoacidaemia and chylomicron studies. Individual digestion varies " +
                "several-fold, so read these as an expectation rather than a measurement.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The window's meals, each tappable to say when it was eaten.
 *
 * Editable because a nutrition source is free to record only the date. When it
 * does, every meal arrives at one fixed time of day and the absorption curves
 * are anchored to an hour nobody ate in -- so such a meal says so rather than
 * printing a plausible-looking clock time, and one tap fixes it.
 */
@Composable
private fun MealListCard(
    state: TodayUiState,
    onAdd: (calories: Int, protein: Int, carbs: Int, fat: Int, at: Instant) -> Unit,
    onUpdate: (MealEntry, Int, Int, Int, Int, Instant) -> Unit,
    onDelete: (MealEntry) -> Unit,
) {
    var editing by remember { mutableStateOf<MealEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf<MealEntry?>(null) }

    TodayCard(
        title = "Meals in this window",
        action = {
            TextButton(onClick = { adding = true }, contentPadding = CompactButtonPadding) {
                Text("Log meal")
            }
        },
    ) {
        if (state.mealsInWindow.isEmpty()) {
            Text(
                "Nothing eaten in this window, or nothing that reached Health Connect. " +
                    "Log a meal to put it on the chart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val undated = state.undatedMealsInWindow.size
        if (undated > 0) {
            Text(
                "$undated of these carry a stamped time rather than the one ${
                    if (undated == 1) "it was" else "they were"
                } eaten at, so ${
                    if (undated == 1) "its curve sits" else "their curves sit"
                } in the wrong hour. Tap one to say when you actually ate it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.mealsInWindow.forEach { meal ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable { editing = meal }
                        .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        meal.name?.takeIf { it.isNotBlank() } ?: "Meal",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        meal.macroSummary(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val placed = state.hasClockTime(meal)
                Text(
                    if (placed) {
                        DateTimeFormatter.ofPattern("EEE h:mm a")
                            .format(meal.timestamp.atZone(state.zoneId))
                    } else {
                        DateTimeFormatter.ofPattern("EEE").format(
                            meal.timestamp.atZone(state.zoneId)
                        ) + " · set time"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (placed) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                )
                // A bin on the row itself. The editor can delete too, but it
                // keeps that button below four steppers and a clock face, which
                // on a phone is off the bottom of the dialog -- a delete nobody
                // can find is not a delete.
                IconButton(
                    onClick = { confirmingDelete = meal },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete meal, ${meal.macroSummary()}",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Said out loud rather than quietly applied: collapsing changes the day's
        // totals, and a figure that moved without explanation is worse than the
        // duplicate it corrected.
        if (state.duplicatesCollapsed > 0) {
            Text(
                "${state.duplicatesCollapsed} repeated record" +
                    "${if (state.duplicatesCollapsed == 1) "" else "s"} from the source " +
                    "merged; each meal is counted once.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    editing?.let { meal ->
        MealEntryDialog(
            initial = meal.asDraft(),
            zoneId = state.zoneId,
            onDismiss = { editing = null },
            onConfirm = {
                onUpdate(meal, it.calories, it.proteinGrams, it.carbGrams, it.fatGrams, it.at)
                editing = null
            },
            isEdit = true,
        )
    }


    // Confirmed, unlike the caffeine bin beside it. A meal carries a whole
    // absorption curve rather than one number, the bins sit in a list people
    // scroll past, and for a synced meal this is one-way: the row is kept hidden
    // precisely so the next sync cannot bring it back.
    confirmingDelete?.let { meal ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Delete this meal?") },
            text = { Text(meal.macroSummary()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(meal)
                        confirmingDelete = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text("Cancel") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
    if (adding) {
        MealEntryDialog(
            initial = MealDraft(calories = 0, proteinGrams = 0, carbGrams = 0, fatGrams = 0, at = state.now),
            zoneId = state.zoneId,
            onDismiss = { adding = false },
            onConfirm = {
                onAdd(it.calories, it.proteinGrams, it.carbGrams, it.fatGrams, it.at)
                adding = false
            },
        )
    }
}

/**
 * A stored meal as the editor's fields.
 *
 * A macro the source never recorded opens at zero, since a stepper has to start
 * somewhere -- but saving then writes that zero as a real figure, which is the
 * honest outcome: the dialog is a statement of what was eaten, and anything left
 * untouched has been confirmed as none.
 */
private fun MealEntry.asDraft() =
    MealDraft(
        calories = calories ?: 0,
        proteinGrams = proteinGrams?.toInt() ?: 0,
        carbGrams = carbGrams?.toInt() ?: 0,
        fatGrams = fatGrams?.toInt() ?: 0,
        at = timestamp,
    )

// ---------------------------------------------------------------------------

private val Macro.label: String
    get() =
        when (this) {
            Macro.PROTEIN -> "Protein"
            Macro.CARB -> "Carbs"
            Macro.FAT -> "Fat"
        }

private fun List<Pair<Instant, Float>>.asPoints(): List<TimePoint> =
    map { TimePoint(it.first, it.second) }

/** Clock times on the panned-window banner. */
private val PannedTimeFormat = DateTimeFormatter.ofPattern("h:mm a")

/** The same with the day, for a window that begins and ends on one date. */
private val PannedDayFormat = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")

/**
 * Both ends of a window that straddles midnight.
 *
 * No weekday on this one. Two of them plus two dates and two clock times is
 * more line than a phone has beside a chip, and the date is the half that
 * settles which day it was -- the weekday only ever restates it.
 */
private val PannedSpanFormat = DateTimeFormatter.ofPattern("d MMM h:mm a")

/**
 * The stretch of time on the plot, spelled out.
 *
 * Shown only while the window is panned, where it is the answer to the one
 * question the chart can no longer be assumed to answer. The day is printed once
 * where both edges fall on it and twice where they do not -- a window straddling
 * midnight that named only its start would put the small hours on the wrong
 * date.
 */
private fun TodayUiState.windowLabel(): String {
    val start = windowStart.atZone(zoneId)
    val end = windowEnd.atZone(zoneId)
    return if (start.toLocalDate() == end.toLocalDate()) {
        "${PannedDayFormat.format(start)} - ${PannedTimeFormat.format(end)}"
    } else {
        "${PannedSpanFormat.format(start)} - ${PannedSpanFormat.format(end)}"
    }
}

private fun Duration.asPeak(): String =
    if (toMinutes() < 90) "${toMinutes()} min" else "%.1f h".format(toMinutes() / 60f)

/** The macros a meal contributed, skipping the ones the logging app left out. */
private fun MealEntry.macroSummary(): String {
    val parts = buildList {
        calories?.let { add("$it kcal") }
        proteinGrams?.let { add("${it.toInt()}g protein") }
        carbGrams?.let { add("${it.toInt()}g carb") }
        fatGrams?.let { add("${it.toInt()}g fat") }
    }
    return if (parts.isEmpty()) "no macros recorded" else parts.joinToString(" · ")
}

/**
 * A meal's marker caption: its carbs, or its calories when the macros are absent.
 *
 * Carbohydrate rather than the meal's name, because what the rule is there to
 * explain is the glucose rise beside it.
 */
private fun MealEntry.markerLabel(): String? =
    carbGrams?.let { "${it.toInt()}g C" } ?: calories?.let { "$it kcal" }

@Composable
private fun Metric(
    label: String,
    value: String,
    supporting: String? = null,
    /** Null keeps the default text colour; set only where the value itself carries meaning. */
    valueColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: Color.Unspecified,
            maxLines = 1,
        )
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
