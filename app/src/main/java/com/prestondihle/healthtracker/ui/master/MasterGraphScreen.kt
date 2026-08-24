package com.prestondihle.healthtracker.ui.master

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
import com.prestondihle.healthtracker.health.HealthPermissionState
import com.prestondihle.healthtracker.ui.components.AxisSpec
import com.prestondihle.healthtracker.ui.components.ChartAxis
import com.prestondihle.healthtracker.ui.components.ChartMarker
import com.prestondihle.healthtracker.ui.components.ChartSeries
import com.prestondihle.healthtracker.ui.components.DualAxisTimeChart
import com.prestondihle.healthtracker.ui.components.MealDraft
import com.prestondihle.healthtracker.ui.components.MealEntryDialog
import com.prestondihle.healthtracker.ui.components.SeriesKind
import com.prestondihle.healthtracker.ui.components.TimePoint
import com.prestondihle.healthtracker.ui.theme.CarbAbsorptionSeries
import com.prestondihle.healthtracker.ui.theme.FatAbsorptionSeries
import com.prestondihle.healthtracker.ui.theme.GlucoseSeries
import com.prestondihle.healthtracker.ui.theme.HeartRateSeries
import com.prestondihle.healthtracker.ui.theme.KetoneSeries
import com.prestondihle.healthtracker.ui.theme.ProteinAbsorptionSeries
import com.prestondihle.healthtracker.ui.theme.StepsSeries
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
fun MasterGraphScreen(viewModel: MasterGraphViewModel) {
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
                MasterCard(title = "Health Connect") {
                    Text(
                        "Meals and heart rate come from Health Connect. Connect it on the Today " +
                            "screen to fill this graph in; glucose and ketones logged by hand " +
                            "still appear without it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item { NowCard(state = state, onRefresh = viewModel::refresh) }

        item {
            CombinedChartCard(
                state = state,
                onToggleSeries = viewModel::setSeriesVisible,
                onToggleAxis = viewModel::toggleLabelledAxis,
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
private fun MasterCard(
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

/** The state of play at this instant: what is landing, and what the body is doing. */
@Composable
private fun NowCard(state: MasterGraphUiState, onRefresh: () -> Unit) {
    MasterCard(
        title = "Right now",
        action = {
            IconButton(onClick = onRefresh, enabled = !state.isSyncing) {
                Icon(Icons.Filled.Refresh, contentDescription = "Sync meals and heart rate")
            }
        },
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Metric(
                label = "Carbs in",
                value = state.rateNow(Macro.CARB).asRate(),
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "Protein in",
                value = state.rateNow(Macro.PROTEIN).asRate(),
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "Fat in",
                value = state.rateNow(Macro.FAT).asRate(),
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider()

        Row(modifier = Modifier.fillMaxWidth()) {
            Metric(
                label = "Glucose",
                value = state.latestGlucose?.let { "${it.mgDl}" } ?: "--",
                supporting = state.latestGlucose?.timestamp?.asAgo(state.now),
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "Ketones",
                value = state.latestKetone?.let { Ketones.format(it.ppm) } ?: "--",
                supporting = state.latestKetone?.timestamp?.asAgo(state.now),
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "Heart rate",
                value = state.latestHeartRate?.let { "${it.bpm}" } ?: "--",
                supporting = state.latestHeartRate?.timestamp?.asAgo(state.now),
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "Steps",
                value = state.stepsLastHour?.let { "${it.steps}" } ?: "--",
                // "last hour" rather than an age, because this is a count over an
                // interval and not a reading taken at a moment.
                supporting = state.stepsLastHour?.let { "last hour" },
                modifier = Modifier.weight(1f),
            )
        }

        // How far along the most recent meal is. Carbs are quoted because they
        // are the fastest of the three, so this is the figure that decides
        // whether a glucose reading taken now is still on the way up.
        state.lastMeal?.let { meal ->
            val carbFraction = state.lastMealAbsorbed(Macro.CARB) ?: 0f
            val fatFraction = state.lastMealAbsorbed(Macro.FAT) ?: 0f
            Text(
                "Last meal ${meal.timestamp.asAgo(state.now)}: " +
                    "${(carbFraction * 100).toInt()}% of its carbs and " +
                    "${(fatFraction * 100).toInt()}% of its fat absorbed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The scale one unit is drawn against.
 *
 * One place, whether the unit ends up labelled down the side of the plot or
 * quietly mapped to its own range with the numbers in the legend. Two copies of
 * these bounds would let a series change shape as it moved between axes, which
 * is the one thing switching axes must not do.
 */
private fun MasterGraphUiState.specFor(metric: AxisMetric): AxisSpec =
    when (metric) {
        AxisMetric.GLUCOSE ->
            AxisSpec(
                min = Glucose.PLOT_MIN,
                max = Glucose.PLOT_MAX,
                label = Glucose.UNIT,
                band = glucoseTarget,
            )
        AxisMetric.MACROS -> AxisSpec(min = 0f, max = 40f, label = "g/h")
        AxisMetric.HEART_RATE -> AxisSpec(min = 40f, max = 180f, label = "bpm")
        AxisMetric.KETONES ->
            AxisSpec(
                min = Ketones.PLOT_MIN,
                max = Ketones.PLOT_MAX,
                label = Ketones.UNIT,
                format = Ketones::format,
            )
        AxisMetric.STEPS -> AxisSpec(min = 0f, max = 1_200f, label = "steps/h")
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
private fun MasterGraphUiState.placementOf(metric: AxisMetric): Pair<ChartAxis, AxisSpec?> =
    axisFor(metric)?.let { it to null } ?: (ChartAxis.LEFT to specFor(metric))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CombinedChartCard(
    state: MasterGraphUiState,
    onToggleSeries: (MasterSeries, Boolean) -> Unit,
    onToggleAxis: (AxisMetric) -> Unit,
) {
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
        val (axis, scale) = state.placementOf(key.metric)
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
                color = GlucoseSeries,
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
                color = CarbAbsorptionSeries,
                showPoints = false,
                dashed = true,
            ),
            series(
                key = MasterSeries.PROTEIN,
                label = "Protein",
                points = state.absorptionCurve(Macro.PROTEIN).asPoints(),
                color = ProteinAbsorptionSeries,
                showPoints = false,
                dashed = true,
            ),
            series(
                key = MasterSeries.FAT,
                label = "Fat",
                points = state.absorptionCurve(Macro.FAT).asPoints(),
                color = FatAbsorptionSeries,
                showPoints = false,
                dashed = true,
            ),
            series(
                key = MasterSeries.HEART_RATE,
                label = "Heart rate",
                points = state.heartRate.map { TimePoint(it.timestamp, it.bpm.toFloat()) },
                color = HeartRateSeries,
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
                color = KetoneSeries,
            ),
            // Bars, not a line: a step count belongs to the hour it was
            // accumulated over, and joining the hours would claim a walking rate
            // at instants when nothing was counted.
            series(
                key = MasterSeries.STEPS,
                label = "Steps",
                points = state.steps.map { TimePoint(it.timestamp, it.steps.toFloat()) },
                color = StepsSeries,
                showPoints = false,
                kind = SeriesKind.BAR,
                barWidth = Duration.ofMinutes(StepBucket.BUCKET_MINUTES),
            ),
        )

    MasterCard(title = "Food, blood and body") {
        DualAxisTimeChart(
            windowStart = state.windowStart,
            windowEnd = state.now,
            zoneId = state.zoneId,
            // Filtered rather than drawn-then-hidden, so a switched-off series
            // also stops stretching the axis it shares.
            series = allSeries.filterKeys(state::isVisible).values.toList(),
            // Falls back rather than throwing: the toggle refuses to empty the list,
            // but the field is public and a plot has to be drawn against
            // something regardless of who built the state.
            leftAxis = state.specFor(state.labelledAxes.firstOrNull() ?: AxisMetric.GLUCOSE),
            rightAxis = state.labelledAxes.getOrNull(1)?.let { state.specFor(it) },
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
            modifier = Modifier.fillMaxWidth().height(ChartHeight),
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
private fun AxisPicker(state: MasterGraphUiState, onToggle: (AxisMetric) -> Unit) {
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
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesToggles(
    state: MasterGraphUiState,
    onToggle: (MasterSeries, Boolean) -> Unit,
) {
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
                            checkedThumbColor = series.color,
                            checkedTrackColor = series.color.copy(alpha = 0.4f),
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

/** The colour this series is drawn in, for its switch. */
private val MasterSeries.color: Color
    get() =
        when (this) {
            MasterSeries.GLUCOSE -> GlucoseSeries
            MasterSeries.CARBS -> CarbAbsorptionSeries
            MasterSeries.PROTEIN -> ProteinAbsorptionSeries
            MasterSeries.FAT -> FatAbsorptionSeries
            MasterSeries.HEART_RATE -> HeartRateSeries
            MasterSeries.KETONES -> KetoneSeries
            MasterSeries.STEPS -> StepsSeries
        }

/** Where the absorption curves come from, since they are the one modelled thing here. */
@Composable
private fun AbsorptionModelCard() {
    MasterCard(title = "About the food curves") {
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
    state: MasterGraphUiState,
    onAdd: (calories: Int, protein: Int, carbs: Int, fat: Int, at: Instant) -> Unit,
    onUpdate: (MealEntry, Int, Int, Int, Int, Instant) -> Unit,
    onDelete: (MealEntry) -> Unit,
) {
    var editing by remember { mutableStateOf<MealEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf<MealEntry?>(null) }

    MasterCard(
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

/** `12 g/h`, or a dash while nothing is arriving -- a bare "0" reads as an error. */
private fun Float.asRate(): String = if (this < 0.5f) "--" else "${toInt()} g/h"

private fun Duration.asPeak(): String =
    if (toMinutes() < 90) "${toMinutes()} min" else "%.1f h".format(toMinutes() / 60f)

/** `2h ago`, which is the only thing worth knowing about a reading's age here. */
private fun Instant.asAgo(now: Instant): String {
    val minutes = Duration.between(this, now).toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        else -> "${minutes / 60}h ${minutes % 60}m ago"
    }
}

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
