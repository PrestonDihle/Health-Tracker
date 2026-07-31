package com.prestondihle.healthtracker.ui.master

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.data.HeartRateBucket
import com.prestondihle.healthtracker.data.MealEntry
import com.prestondihle.healthtracker.domain.Macro
import com.prestondihle.healthtracker.domain.Ketones
import com.prestondihle.healthtracker.domain.MacroAbsorption
import com.prestondihle.healthtracker.health.HealthPermissionState
import com.prestondihle.healthtracker.ui.components.AxisSpec
import com.prestondihle.healthtracker.ui.components.ChartAxis
import com.prestondihle.healthtracker.ui.components.ChartMarker
import com.prestondihle.healthtracker.ui.components.ChartSeries
import com.prestondihle.healthtracker.ui.components.DualAxisTimeChart
import com.prestondihle.healthtracker.ui.components.TimePoint
import com.prestondihle.healthtracker.ui.theme.CarbAbsorptionSeries
import com.prestondihle.healthtracker.ui.theme.FatAbsorptionSeries
import com.prestondihle.healthtracker.ui.theme.GlucoseSeries
import com.prestondihle.healthtracker.ui.theme.HeartRateSeries
import com.prestondihle.healthtracker.ui.theme.KetoneSeries
import com.prestondihle.healthtracker.ui.theme.ProteinAbsorptionSeries
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

private val CardGap = 10.dp
private val CardPadding = 12.dp
private val ChartHeight = 300.dp

/**
 * Everything on one timeline: the macros of each meal spread into the hours they
 * are actually being absorbed over, drawn against the blood sugar, ketones and
 * heart rate they are meant to explain.
 */
@Composable
fun MasterGraphScreen(viewModel: MasterGraphViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = CardGap),
        verticalArrangement = Arrangement.spacedBy(CardGap),
        contentPadding = PaddingValues(vertical = CardGap),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

        item { CombinedChartCard(state, viewModel::setSeriesVisible) }

        item { AbsorptionModelCard() }

        if (state.mealsInWindow.isNotEmpty()) {
            item { MealListCard(state) }
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

@Composable
private fun CombinedChartCard(
    state: MasterGraphUiState,
    onToggleSeries: (MasterSeries, Boolean) -> Unit,
) {
    val allSeries =
        mapOf(
            MasterSeries.GLUCOSE to
                ChartSeries(
                    label = "Glucose",
                    points = state.glucose.map { TimePoint(it.timestamp, it.mgDl.toFloat()) },
                    color = GlucoseSeries,
                    axis = ChartAxis.LEFT,
                    // A CGM writes every few minutes; dots would merge into a band.
                    showPoints = state.glucose.size <= 24,
                ),
            MasterSeries.CARBS to
                ChartSeries(
                    label = "Carbs",
                    points = state.absorptionCurve(Macro.CARB).asPoints(),
                    color = CarbAbsorptionSeries,
                    axis = ChartAxis.RIGHT,
                    showPoints = false,
                    // Dashed throughout: these three are a model of what the food
                    // is doing, not a measurement of it.
                    dashed = true,
                ),
            MasterSeries.PROTEIN to
                ChartSeries(
                    label = "Protein",
                    points = state.absorptionCurve(Macro.PROTEIN).asPoints(),
                    color = ProteinAbsorptionSeries,
                    axis = ChartAxis.RIGHT,
                    showPoints = false,
                    dashed = true,
                ),
            MasterSeries.FAT to
                ChartSeries(
                    label = "Fat",
                    points = state.absorptionCurve(Macro.FAT).asPoints(),
                    color = FatAbsorptionSeries,
                    axis = ChartAxis.RIGHT,
                    showPoints = false,
                    dashed = true,
                ),
            // Heart rate and ketones carry scales of their own: the two drawn axes
            // are already spoken for, and bpm on a mg/dL axis -- or ppm on a g/h
            // one -- would misstate both.
            MasterSeries.HEART_RATE to
                ChartSeries(
                    label = "Heart rate",
                    points = state.heartRate.map { TimePoint(it.timestamp, it.bpm.toFloat()) },
                    color = HeartRateSeries,
                    showPoints = false,
                    scale = AxisSpec(min = 40f, max = 180f, label = "bpm"),
                ),
            MasterSeries.KETONES to
                ChartSeries(
                    label = "Ketones",
                    points = state.ketones.map { TimePoint(it.timestamp, it.ppm) },
                    color = KetoneSeries,
                    scale =
                        AxisSpec(
                            min = Ketones.PLOT_MIN,
                            max = Ketones.PLOT_MAX,
                            label = Ketones.UNIT,
                            format = Ketones::format,
                        ),
                ),
        )

    MasterCard(title = "Food, blood and heart") {
        DualAxisTimeChart(
            windowStart = state.windowStart,
            windowEnd = state.now,
            zoneId = state.zoneId,
            // Filtered rather than drawn-then-hidden, so a switched-off series
            // also stops stretching the axis it shares.
            series = allSeries.filterKeys(state::isVisible).values.toList(),
            leftAxis = AxisSpec(min = 60f, max = 200f, label = "mg/dL"),
            rightAxis = AxisSpec(min = 0f, max = 40f, label = "g/h"),
            // A rule at each meal, so a rise in any of the other lines can be
            // read against the moment the food went in.
            markers =
                state.mealsInWindow.map { meal ->
                    ChartMarker(time = meal.timestamp, label = meal.markerLabel())
                },
            modifier = Modifier.fillMaxWidth().height(ChartHeight),
        )

        HorizontalDivider()

        SeriesToggles(state = state, onToggle = onToggleSeries)
    }
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

@Composable
private fun MealListCard(state: MasterGraphUiState) {
    MasterCard(title = "Meals in this window") {
        state.mealsInWindow.forEach { meal ->
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                Text(
                    DateTimeFormatter.ofPattern("EEE h:mm a")
                        .format(meal.timestamp.atZone(state.zoneId)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

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
