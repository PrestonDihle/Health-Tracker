package com.prestondihle.healthtracker.ui.wellness

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.data.HealthDaySnapshot
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.domain.BodyComposition
import com.prestondihle.healthtracker.domain.Glucose
import com.prestondihle.healthtracker.domain.Ketones
import com.prestondihle.healthtracker.domain.Sleep
import com.prestondihle.healthtracker.domain.SleepStage
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.health.HealthPermissionState
import androidx.compose.material3.FilterChip
import com.prestondihle.healthtracker.ui.components.AxisRule
import com.prestondihle.healthtracker.ui.components.AxisSpec
import com.prestondihle.healthtracker.ui.components.BarChart
import com.prestondihle.healthtracker.ui.components.BarHeight
import com.prestondihle.healthtracker.ui.components.CardGap
import com.prestondihle.healthtracker.ui.components.ChartAxis
import com.prestondihle.healthtracker.ui.components.ChartSeries
import com.prestondihle.healthtracker.ui.components.CompactButtonPadding
import com.prestondihle.healthtracker.ui.components.DualAxisTimeChart
import com.prestondihle.healthtracker.ui.components.InlineLogButton
import com.prestondihle.healthtracker.ui.components.IntStepper
import com.prestondihle.healthtracker.ui.components.LabeledSlider
import com.prestondihle.healthtracker.ui.components.LineSeries
import com.prestondihle.healthtracker.ui.components.LineStyle
import com.prestondihle.healthtracker.ui.components.LogButton
import com.prestondihle.healthtracker.ui.components.Metric
import com.prestondihle.healthtracker.ui.components.MultiLineChart
import com.prestondihle.healthtracker.ui.components.ScaleDescriptors
import com.prestondihle.healthtracker.ui.components.Stepper
import com.prestondihle.healthtracker.ui.components.TimePoint
import com.prestondihle.healthtracker.ui.components.TrackerCard
import com.prestondihle.healthtracker.ui.reorder.CardOrderViewModel
import com.prestondihle.healthtracker.ui.reorder.ReorderableCard
import com.prestondihle.healthtracker.ui.reorder.reorderableCards
import com.prestondihle.healthtracker.ui.theme.LocalChartColors
import com.prestondihle.healthtracker.ui.theme.Pine
import com.prestondihle.healthtracker.ui.trends.BloodPressureTrendCard
import com.prestondihle.healthtracker.ui.trends.CompareCard
import com.prestondihle.healthtracker.ui.trends.ReadinessCard
import com.prestondihle.healthtracker.ui.trends.RestingHeartRateTrendCard
import com.prestondihle.healthtracker.ui.trends.SleepTrendCard
import com.prestondihle.healthtracker.ui.trends.Spo2TrendCard
import com.prestondihle.healthtracker.ui.trends.TrendsViewModel
import com.prestondihle.healthtracker.ui.trends.WaistTrendCard
import com.prestondihle.healthtracker.ui.trends.WeightTrendCard
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun WellnessScreen(
    viewModel: WellnessViewModel,
    trendsViewModel: TrendsViewModel,
    orderViewModel: CardOrderViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Wellness draws its longer-run charts (waist, weight, blood pressure, resting
    // heart rate, sleep) from the same trends source Activity uses, so they read
    // the same wherever they appear.
    val trends by trendsViewModel.uiState.collectAsStateWithLifecycle()
    // Its own flow because its window is a fixed thirty days: the baseline must
    // not change when the reader moves a chart's range chip.
    val readiness by trendsViewModel.readiness.collectAsStateWithLifecycle()
    val compare by trendsViewModel.compare.collectAsStateWithLifecycle()
    val savedOrder by orderViewModel.savedOrder.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = PermissionController.createRequestPermissionResultContract()
        ) {
            viewModel.refreshHealth()
        }

    fun toast(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = CardGap),
        verticalArrangement = Arrangement.spacedBy(CardGap),
        contentPadding = PaddingValues(vertical = CardGap),
    ) {
        if (state.healthState != HealthPermissionState.GRANTED) {
            item {
                HealthConnectPrompt(
                    state = state.healthState,
                    onConnect = { permissionLauncher.launch(viewModel.healthPermissions) },
                )
            }
        } else if (state.missingPermissions.isNotEmpty()) {
            // Connected, but something added in a later version was never
            // granted. Without this the metric just reads blank forever.
            item {
                MissingPermissionsPrompt(
                    missing = state.missingPermissions,
                    onGrant = { permissionLauncher.launch(state.missingPermissions) },
                )
            }
        }

        // The body and vitals trends read the last fortnight; the controls that
        // log them live on the Log tab. Sleep sits under Activity by default,
        // where the night's duration is already quoted -- the same subject at two
        // resolutions -- but the reader is free to move any of these.
        reorderableCards(
            cards =
                listOf(
                    ReorderableCard("activity") {
                        ActivityCard(state = state, onRefresh = viewModel::refreshHealth)
                    },
                    ReorderableCard("sleep") { SleepCard(state = state) },
                    ReorderableCard("metabolic") {
                        MetabolicCard(
                            state = state,
                            onWindowChange = viewModel::setGlucoseWindow,
                            onSmoothChange = viewModel::setSmoothGlucose,
                            onAddKetone = {
                                viewModel.addKetone(it)
                                toast("Logged ${Ketones.format(it)} ${Ketones.UNIT}")
                            },
                            onAddGlucose = {
                                viewModel.addBloodSugar(it)
                                toast("Logged $it ${Glucose.UNIT}")
                            },
                        )
                    },
                    ReorderableCard("waistTrend") { WaistTrendCard(trends) },
                    ReorderableCard("weightTrend") { WeightTrendCard(trends) },
                    ReorderableCard("bloodPressureTrend") { BloodPressureTrendCard(trends) },
                    ReorderableCard("restingHeartRateTrend") { RestingHeartRateTrendCard(trends) },
                    // Next to the two trends it is drawn from, so the line and the
                    // charts that justify it are read together.
                    ReorderableCard("readiness") { ReadinessCard(readiness) },
                    ReorderableCard("spo2Trend") { Spo2TrendCard(trends) },
                    ReorderableCard("sleepTrend") { SleepTrendCard(trends) },
                    // Below the single-metric trends, because it answers a
                    // question they raise rather than one they answer: each of
                    // those says what one thing did, and this is where two of
                    // them get put side by side.
                    ReorderableCard("compare") {
                        CompareCard(
                            state = compare,
                            onPick = trendsViewModel::setComparison,
                            onLag = trendsViewModel::setComparisonLag,
                        )
                    },
                    ReorderableCard("moodTrend") { MoodTrendCard(state = state) },
                    ReorderableCard("readingTrend") { ReadingTrendCard(state = state) },
                ),
            savedOrder = savedOrder,
            onMove = orderViewModel::move,
        )

        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Layout scale
//
// Deliberately tighter than Material's defaults: this screen is a dense
// read-out of a dozen metrics, and the stock 16dp card padding pushed half of
// them below the fold.
// ---------------------------------------------------------------------------

private val ChartHeight = 170.dp
private val EmptyChartHeight = 72.dp

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

@Composable
private fun HealthConnectPrompt(state: HealthPermissionState, onConnect: () -> Unit) {
    TrackerCard(title = "Health Connect") {
        val message =
            when (state) {
                HealthPermissionState.UNAVAILABLE ->
                    "Health Connect is not available on this device. Steps, heart rate, sleep, " +
                        "calories, macros and mile times will stay empty."
                HealthPermissionState.UPDATE_REQUIRED ->
                    "Health Connect needs updating before it can share data."
                else ->
                    "Connect to pull in steps, heart rate, sleep, calories, macros, glucose and " +
                        "run times. This app only reads; it never writes."
            }
        Text(message, style = MaterialTheme.typography.bodySmall)
        if (state == HealthPermissionState.NOT_GRANTED) {
            Button(onClick = onConnect, contentPadding = CompactButtonPadding) { Text("Connect") }
        }
    }
}

/** Turns `android.permission.health.READ_WEIGHT` into `weight`. */
private fun String.asPermissionLabel(): String =
    substringAfterLast('.').removePrefix("READ_").lowercase().replace('_', ' ')

@Composable
private fun MissingPermissionsPrompt(missing: Set<String>, onGrant: () -> Unit) {
    TrackerCard(title = "Health Connect") {
        Text(
            "Not yet allowed to read: ${missing.joinToString { it.asPermissionLabel() }}. " +
                "Those metrics stay blank until granted.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onGrant, contentPadding = CompactButtonPadding) { Text("Grant") }
    }
}

@Composable
private fun ActivityCard(state: WellnessUiState, onRefresh: () -> Unit) {
    TrackerCard(
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

        // A second row rather than two more cells in the first, which already
        // carries six. Shown only when the logging app recorded any of them: on a
        // source that does not, an extra row of dashes says nothing every day for
        // ever, and every historical day is null by design.
        MacroDetailRow(snapshot)
    }
}

/**
 * Fiber, sugar, saturated fat and sodium, under the macros they are part of.
 *
 * **Not a fourth, fifth and sixth macro.** Fiber and sugar are components of the
 * carbohydrate figure above and saturated fat is part of the fat, so they sit on
 * their own row and are never summed with the three or stacked beside them --
 * doing either counts the same grams twice.
 *
 * The whole row disappears when nothing recorded any of it, rather than showing
 * four dashes. Every day synced before these were read is null, and on a
 * nutrition source that does not report them it always will be.
 */
@Composable
internal fun MacroDetailRow(snapshot: HealthDaySnapshot?) {
    if (snapshot == null) return
    val hasAny =
        snapshot.fiberGrams != null ||
            snapshot.sugarGrams != null ||
            snapshot.saturatedFatGrams != null ||
            snapshot.sodiumMg != null
    if (!hasAny) return

    Row(modifier = Modifier.fillMaxWidth()) {
        Metric(
            label = "Fiber",
            value = snapshot.fiberGrams?.let { "${it.toInt()}g" } ?: "--",
            modifier = Modifier.weight(1f),
        )
        Metric(
            label = "Sugar",
            value = snapshot.sugarGrams?.let { "${it.toInt()}g" } ?: "--",
            // Said out loud, because a reader who has just read "Carbs 180g" will
            // otherwise add these together.
            supporting = "of carbs",
            modifier = Modifier.weight(1f),
        )
        Metric(
            label = "Sat fat",
            value = snapshot.saturatedFatGrams?.let { "${it.toInt()}g" } ?: "--",
            supporting = "of fat",
            modifier = Modifier.weight(1f),
        )
        Metric(
            label = "Sodium",
            value = snapshot.sodiumMg?.let { "${it.toInt()}" } ?: "--",
            supporting = "mg",
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Last night, as a hypnogram with the heart rate under it.
 *
 * The two are drawn together rather than on separate charts because the reason
 * to look at either is the other: a heart rate that stays high through the first
 * two cycles is what a night of little deep sleep looks like from the other
 * side, and on two charts that has to be held in the head across a scroll.
 *
 * Time asleep is given the prominence, not time in bed. They differ by the waking
 * in between, and the difference is the whole reason the stages are worth having
 * -- a night bounded eight and a half hours with forty minutes of waking in it is
 * a seven-fifty night, and reporting the eight and a half would flatter it.
 */
// Internal rather than private so a render test can compose it on its own. This
// screen cannot be scrolled in a test -- the fast timer's ticker means it never
// reaches idle -- and a LazyColumn composes only what is on screen, so the third
// card down is never built at all. The chart inside it is canvas arithmetic that
// only runs under a real layout pass, which is exactly the code no pure-JVM test
// can reach.
@Composable
internal fun SleepCard(state: WellnessUiState) {
    val chartColors = LocalChartColors.current
    val night = state.sleep

    TrackerCard(title = "Sleep") {
        if (night == null) {
            Text(
                "No sleep recorded yet. Nights arrive with the next Health Connect sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@TrackerCard
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric(
                label = "Asleep",
                value = Sleep.formatDuration(night.totalAsleep),
                supporting = "${Sleep.formatDuration(night.timeInBed)} in bed",
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "Start",
                value = night.start.asClockTime(state),
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "End",
                value = night.end.asClockTime(state),
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric(
                label = SleepStage.REM.label,
                value = Sleep.formatDuration(night.rem),
                supporting = night.rem.shareOf(night.totalAsleep),
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = SleepStage.LIGHT.label,
                value = Sleep.formatDuration(night.light),
                supporting = night.light.shareOf(night.totalAsleep),
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = SleepStage.DEEP.label,
                value = Sleep.formatDuration(night.deep),
                supporting = night.deep.shareOf(night.totalAsleep),
                modifier = Modifier.weight(1f),
            )
        }

        DualAxisTimeChart(
            windowStart = night.start,
            windowEnd = night.end,
            zoneId = state.zoneId,
            series =
                listOfNotNull(
                    ChartSeries(
                        label = "Stage",
                        points = Sleep.hypnogram(night.stages),
                        color = chartColors.sleep,
                        axis = ChartAxis.LEFT,
                        // A hypnogram is already two points per stretch; dots on
                        // both ends of every tread doubles the ink and says
                        // nothing the risers do not.
                        showPoints = false,
                    ),
                    // Omitted rather than drawn empty: an axis labelled bpm down
                    // the right-hand side of a plot with no heart rate on it is a
                    // scale for nothing.
                    state.sleepHeartRate
                        .takeIf { it.isNotEmpty() }
                        ?.let { buckets ->
                            ChartSeries(
                                label = "Heart rate",
                                points = buckets.map { TimePoint(it.timestamp, it.bpm.toFloat()) },
                                color = chartColors.heartRate,
                                axis = ChartAxis.RIGHT,
                                showPoints = false,
                                // A watch taken off mid-night leaves a hole, and
                                // a straight run across it in the same ink as the
                                // readings either side is exactly what this chart
                                // must not draw.
                                breakOnGaps = true,
                            )
                        },
                ),
            leftAxis =
                AxisSpec(
                    min = Sleep.PLOT_MIN,
                    max = Sleep.PLOT_MAX,
                    label = "",
                    // Named levels rather than numbers. "3" down the side of a
                    // hypnogram means nothing; "Awake" means the whole of it.
                    format = Sleep::formatLevel,
                    // One rule per stage, exactly. Left to fit the height it
                    // subdivides between them and the middle two go unnamed.
                    rows = Sleep.PLOT_ROWS,
                    color = chartColors.sleep,
                ),
            rightAxis =
                state.sleepHeartRate
                    .takeIf { it.isNotEmpty() }
                    ?.let { AxisSpec(min = 40f, max = 100f, label = "bpm", color = chartColors.heartRate) },
            contentDescription =
                "Last night's sleep stages with heart rate. Tap to read both at one moment.",
            modifier = Modifier.fillMaxWidth().height(ChartHeight),
        )

        // Only when there is some. A source that stages every minute of a night
        // never shows this line, and one that stages none of it shows a chart
        // with no trace on it -- which needs saying, or the card looks broken.
        if (!night.unstaged.isZero) {
            Text(
                "${Sleep.formatDuration(night.unstaged)} recorded as asleep without a stage, " +
                    "counted in the total but not drawn",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** `23:40`. The date is carried by the card, which is always about last night. */
private fun Instant.asClockTime(state: WellnessUiState): String =
    DateTimeFormatter.ofPattern("h:mm a").format(atZone(state.zoneId))

/** `28%`, or blank where the whole is zero and a share would divide by it. */
private fun Duration.shareOf(whole: Duration): String? {
    if (whole.isZero || whole.isNegative) return null
    return "${(toMillis() * 100 / whole.toMillis())}%"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetabolicCard(
    state: WellnessUiState,
    onWindowChange: (GlucoseWindow) -> Unit,
    onSmoothChange: (Boolean) -> Unit,
    onAddKetone: (Float) -> Unit,
    onAddGlucose: (Int) -> Unit,
) {
    val chartColors = LocalChartColors.current

    TrackerCard(title = "Glucose and ketones") {
        // Zooming in is the point: a CGM trace over 24 hours flattens the swing
        // around a single meal into a wiggle, and 3 to 6 hours is where that
        // swing is actually readable. Wrapped because six chips do not fit on one
        // row of a phone.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlucoseWindow.entries.forEach { option ->
                FilterChip(
                    selected = state.glucoseWindow == option,
                    onClick = { onWindowChange(option) },
                    label = { Text(option.label) },
                )
            }
        }

        DualAxisTimeChart(
            windowStart = state.glucoseWindowStart,
            windowEnd = state.now,
            series =
                listOf(
                    ChartSeries(
                        label = if (state.settings.smoothGlucose) "Glucose (smoothed)" else "Glucose",
                        points = state.glucoseCurve.map { TimePoint(it.first, it.second) },
                        color = chartColors.glucose,
                        axis = ChartAxis.LEFT,
                        // A CGM writes every few minutes; dots would merge into a
                        // band. A short window holds few enough to mark, though.
                        showPoints = state.glucose.size <= 24,
                        // A sensor dropout is not a straight line between the
                        // readings either side of it.
                        breakOnGaps = true,
                    ),
                    ChartSeries(
                        label = "Ketones",
                        points = state.ketones.map { TimePoint(it.timestamp, it.ppm) },
                        color = chartColors.ketone,
                        axis = ChartAxis.RIGHT,
                    ),
                ),
            leftAxis =
                AxisSpec(
                    min = state.glucosePlotRange.start,
                    max = state.glucosePlotRange.endInclusive,
                    label = Glucose.UNIT,
                    band = state.glucoseTarget,
                    // Solid, because this is the reader's own line rather than
                    // a published figure -- see AxisRule.dashed.
                    rules =
                        listOfNotNull(state.glucoseReference?.let { AxisRule(it, dashed = false) }),
                ),
            rightAxis =
                AxisSpec(
                    min = Ketones.PLOT_MIN,
                    max = Ketones.PLOT_MAX,
                    label = Ketones.UNIT,
                    format = Ketones::format,
                ),
            // An empty plot does not need a full-height canvas to say so.
            modifier =
                Modifier.fillMaxWidth()
                    .height(
                        if (state.glucose.isEmpty() && state.ketones.isEmpty()) EmptyChartHeight
                        else ChartHeight
                    ),
        )

        // Said out loud, for the reason the master graph owns up to the meal
        // duplicates it merged: the last refresh went back and filled holes in a
        // line that was already on screen, and a trace that grows an hour in it
        // without explanation is harder to trust than one that says where the
        // hour came from.
        if (state.glucoseRecovered > 0) {
            Text(
                "Refilled ${state.glucoseRecovered} " +
                    (if (state.glucoseRecovered == 1) "reading" else "readings") +
                    " the source had written late",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The switch sits on the chart rather than in settings because it is a
        // way of looking at the data, not a fact about it -- and because a line
        // that no longer matches its readings has to be one tap from being
        // turned back into them.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.glucoseTarget != null) {
                    "Grey band is the target set in Settings"
                } else {
                    "Set a target range in Settings"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Smooth",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = state.settings.smoothGlucose,
                onCheckedChange = onSmoothChange,
                modifier = Modifier.scale(0.75f),
            )
        }

        HorizontalDivider()

        var ketone by remember { mutableFloatStateOf(Ketones.DEFAULT_ENTRY) }
        Stepper(
            label = "Ketones",
            value = ketone,
            onValueChange = { ketone = it },
            step = Ketones.ENTRY_STEP,
            range = Ketones.ENTRY_RANGE,
            valueFormatter = Ketones::format,
            supportingText = "${Ketones.UNIT}, breath acetone",
            trailingContent = {
                InlineLogButton(contentDescription = "Log ketones", onClick = { onAddKetone(ketone) })
            },
        )

        HorizontalDivider()

        var glucose by remember { mutableIntStateOf(90) }
        IntStepper(
            label = "Blood sugar",
            value = glucose,
            onValueChange = { glucose = it },
            step = 1,
            range = Glucose.ENTRY_RANGE,
            supportingText = "${Glucose.UNIT}, for manual fingersticks",
            trailingContent = {
                InlineLogButton(
                    contentDescription = "Log blood sugar",
                    onClick = { onAddGlucose(glucose) },
                )
            },
        )
    }
}

@Composable
internal fun BodyCard(state: WellnessUiState, onWaistChange: (Float) -> Unit) {
    TrackerCard(title = "Body") {
        // Held locally and written only on Log, so dialling past the target value
        // does not save a string of measurements that were never taken. Re-seeds
        // whenever the stored value changes underneath it.
        var inches by
            remember(state.waistCm) { mutableFloatStateOf(Units.cmToInches(state.waistCm)) }
        Stepper(
            label = "Waist",
            value = inches,
            onValueChange = { inches = it },
            step = 0.25f,
            range = 20f..70f,
            snap = Units::roundToQuarter,
            valueFormatter = Units::formatInches,
            // Only the placeholder case needs a hint; the step size is self-evident.
            supportingText = if (state.hasWaistMeasurement) null else "default until measured",
            trailingContent = {
                InlineLogButton(
                    contentDescription = "Log waist",
                    onClick = { onWaistChange(Units.inchesToCm(inches)) },
                )
            },
        )

        // Waist is measured every few days at most, so -- unlike blood pressure's
        // "last today" -- the reading that matters is the last one on record,
        // dated. It also confirms the tap landed without a snackbar to chase.
        state.latestWaist?.let { last ->
            Text(
                "Last: ${Units.formatInches(Units.cmToInches(last.waistCm))} on " +
                    last.date.asShortDate(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        WaistToHeightScreen(state)
    }
}

/**
 * The military body composition screen: waist over height, under 0.55.
 *
 * Shown here rather than beside the fitness test because it is a tape
 * measurement and this is the card the tape is entered on. It reads off the
 * measured waist rather than the stepper's current position, so dialling the
 * stepper does not move a verdict about a measurement nobody took.
 *
 * Neither age nor sex enters into it, so unlike the AFT card this has nothing to
 * ask the profile for beyond a height.
 */
@Composable
private fun WaistToHeightScreen(state: WellnessUiState) {
    val heightCm = state.settings.heightCm
    val waistCm = state.latestWaist?.waistCm ?: return
    val ratio = BodyComposition.ratio(waistCm, heightCm)

    HorizontalDivider()

    if (ratio == null) {
        Text(
            "Set your height on the Settings tab for the waist-to-height screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val passes = BodyComposition.passes(ratio)
    val limit = BodyComposition.maxPassingWaistInches(heightCm)
    Text(
        "Waist to height %.3f".format(ratio) + if (passes) " — under 0.55" else " — 0.55 or over",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color =
            if (passes) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
    Text(
        buildString {
            if (limit != null) {
                append("Largest passing waist at your height is ")
                append(Units.formatInches(limit.toFloat()))
                append(". ")
            }
            // Both measurements are floored to the half inch before dividing,
            // which is the standard's own rule and is worth saying: a reader
            // checking the sum on a calculator will otherwise get a different
            // third decimal and assume the app is wrong.
            append("Measured in inches, each rounded down to the nearest ½ inch.")
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Starting point for a grip stepper when nothing has ever been measured, in pounds. */
private const val DEFAULT_GRIP_LBS = 90f

/**
 * A dynamometer reading per hand.
 *
 * Two steppers rather than one with a hand selector: both hands are squeezed in
 * the same sitting, and a selector would make the second reading a mode switch
 * away. Each logs independently, so measuring one hand does not blank the other.
 *
 * The steppers seed from the last measurement rather than from a constant --
 * grip barely moves week to week, so the previous value is nearly always within
 * a couple of presses of the new one.
 */
@Composable
internal fun GripStrengthCard(state: WellnessUiState, onLog: (Boolean, Float) -> Unit) {
    TrackerCard(title = "Grip strength") {
        var dominant by
            remember(state.latestGrip?.dominantKg) {
                mutableFloatStateOf(
                    state.latestGrip?.dominantKg?.let { Units.kgToLbs(it) } ?: DEFAULT_GRIP_LBS
                )
            }
        var nonDominant by
            remember(state.latestGrip?.nonDominantKg) {
                mutableFloatStateOf(
                    state.latestGrip?.nonDominantKg?.let { Units.kgToLbs(it) } ?: DEFAULT_GRIP_LBS
                )
            }

        Stepper(
            label = "Dominant",
            value = dominant,
            onValueChange = { dominant = it },
            step = 1f,
            range = 0f..250f,
            valueFormatter = { "${it.toInt()} lb" },
            trailingContent = {
                InlineLogButton(
                    contentDescription = "Log dominant hand grip",
                    onClick = { onLog(true, dominant) },
                )
            },
        )

        HorizontalDivider()

        // "Other hand" rather than "Non-dominant": a stepper's label shares its
        // row with two arrows, a value and a log button, which on a phone at a
        // large font scale leaves it too narrow for a twelve-character word --
        // it wrapped mid-word into "Non-domina / nt". The full term is still on
        // the summary line below and in the Trends legend, where there is room.
        Stepper(
            label = "Other hand",
            value = nonDominant,
            onValueChange = { nonDominant = it },
            step = 1f,
            range = 0f..250f,
            valueFormatter = { "${it.toInt()} lb" },
            trailingContent = {
                InlineLogButton(
                    contentDescription = "Log non-dominant hand grip",
                    onClick = { onLog(false, nonDominant) },
                )
            },
        )

        // Like waist, this is measured every few days at most, so the reading
        // that matters is the last one on record with the date it was taken --
        // and it doubles as confirmation that the tap landed.
        state.latestGrip?.let { last ->
            val parts = buildList {
                last.dominantKg?.let { add("${Units.kgToLbs(it).toInt()} lb dominant") }
                last.nonDominantKg?.let { add("${Units.kgToLbs(it).toInt()} lb non-dominant") }
            }
            if (parts.isNotEmpty()) {
                Text(
                    (if (state.hasGripToday) "Today: " else "Last: ") +
                        parts.joinToString(" · ") +
                        if (state.hasGripToday) "" else " on ${last.date.asShortDate()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun BloodPressureCard(state: WellnessUiState, onSubmit: (Int, Int) -> Unit) {
    TrackerCard(title = "Blood pressure") {
        var systolic by remember { mutableIntStateOf(120) }
        var diastolic by remember { mutableIntStateOf(80) }

        IntStepper(
            label = "Systolic",
            value = systolic,
            onValueChange = { systolic = it },
            range = 60..250,
        )
        IntStepper(
            label = "Diastolic",
            value = diastolic,
            onValueChange = { diastolic = it },
            range = 30..160,
        )

        // Two steppers commit together, so the action cannot ride inline on
        // either one. Pairing it with the last reading on a single row saves the
        // line a standalone button would have cost.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.latestBloodPressure?.let { "Last today: ${it.systolic}/${it.diastolic}" }
                    ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { onSubmit(systolic, diastolic) },
                contentPadding = CompactButtonPadding,
            ) {
                Text("Log blood pressure")
            }
        }
    }
}

@Composable
internal fun MoodCard(state: WellnessUiState, onSubmit: (Int, Int, Int) -> Unit) {
    TrackerCard(title = "How are you?") {
        var vibe by remember(state.dailyLog.vibe) { mutableIntStateOf(state.dailyLog.vibe ?: 5) }
        var energy by
            remember(state.dailyLog.energy) { mutableIntStateOf(state.dailyLog.energy ?: 5) }
        var focus by remember(state.dailyLog.focus) { mutableIntStateOf(state.dailyLog.focus ?: 5) }

        LabeledSlider("Vibe", vibe, { vibe = it }, ScaleDescriptors.Vibe)
        LabeledSlider("Energy", energy, { energy = it }, ScaleDescriptors.Energy)
        LabeledSlider("Focus", focus, { focus = it }, ScaleDescriptors.Focus)

        LogButton("Submit", onClick = { onSubmit(vibe, energy, focus) })
    }
}

/**
 * The last fortnight of vibe, energy and focus on one chart.
 *
 * One chart rather than three: the scores are submitted together against the
 * same 1-10 scale, and the question asked of them is whether they move together.
 * Moved here from the Activity trends so it reads next to the sliders it plots.
 */
// Internal rather than private for the same reason SleepCard is: this screen
// cannot be scrolled in a test, so a card this far down a LazyColumn is never
// composed at all. Three line styles read against one 1-10 axis is exactly the
// kind of drawing that looks right until one of them stops being distinguishable
// from the others, and nothing outside a real layout pass would notice.
@Composable
internal fun MoodTrendCard(state: WellnessUiState) {
    val chartColors = LocalChartColors.current
    TrackerCard(title = "Vibe, energy and focus", subtitle = "1 to 10") {
        MultiLineChart(
            series =
                listOf(
                    LineSeries(
                        label = "Vibe",
                        points = state.logSeries { it.vibe?.toFloat() },
                        color = chartColors.vibe,
                        style = LineStyle.SOLID,
                    ),
                    LineSeries(
                        label = "Energy",
                        points = state.logSeries { it.energy?.toFloat() },
                        color = chartColors.energy,
                        style = LineStyle.DASHED,
                    ),
                    LineSeries(
                        label = "Focus",
                        points = state.logSeries { it.focus?.toFloat() },
                        color = chartColors.focus,
                        style = LineStyle.DOTTED,
                    ),
                ),
            minY = 1f,
            maxY = 10f,
            modifier = Modifier.fillMaxWidth().height(170.dp),
        )
    }
}

@Composable
internal fun MovementCard(state: WellnessUiState, onLog: (MovementType, Int) -> Unit) {
    TrackerCard(title = "Movement") {
        var pushups by remember { mutableIntStateOf(20) }
        var squats by remember { mutableIntStateOf(20) }

        IntStepper(
            label = "Pushups",
            value = pushups,
            onValueChange = { pushups = it },
            step = 5,
            range = 0..500,
            supportingText = "${state.pushupsToday} today",
            trailingContent = {
                InlineLogButton(
                    contentDescription = "Log pushups",
                    onClick = { onLog(MovementType.PUSHUP, pushups) },
                )
            },
        )

        HorizontalDivider()

        IntStepper(
            label = "Air squats",
            value = squats,
            onValueChange = { squats = it },
            step = 5,
            range = 0..500,
            supportingText = "${state.squatsToday} today",
            trailingContent = {
                InlineLogButton(
                    contentDescription = "Log air squats",
                    onClick = { onLog(MovementType.AIR_SQUAT, squats) },
                )
            },
        )
    }
}

@Composable
internal fun ReadingCard(
    state: WellnessUiState,
    onLogPages: (Int) -> Unit,
    onSetPages: (Int) -> Unit,
) {
    TrackerCard(title = "Reading") {
        val readToday = state.dailyLog.bookPagesRead ?: 0
        val goal = state.goals.dailyPagesGoal

        // Reset clears the day's total, so it belongs beside that total rather
        // than down by the "add more pages" control.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Metric(
                label = "Pages today",
                value = readToday.toString(),
                supporting = goal?.let { "goal $it" },
                modifier = Modifier.weight(1f),
            )
            if (readToday > 0) {
                OutlinedButton(onClick = { onSetPages(0) }, contentPadding = CompactButtonPadding) {
                    Text("Reset")
                }
            }
        }
        if (goal != null && goal > 0) {
            LinearProgressIndicator(
                progress = { (readToday.toFloat() / goal).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(BarHeight),
            )
        }

        // The stepper holds the size of *this* sitting, which Log then adds to
        // the day. Editing the running total directly made every tap a write and
        // left no way to say "I just read 30 more".
        var pages by remember { mutableIntStateOf(10) }
        IntStepper(
            label = "Add pages",
            value = pages,
            onValueChange = { pages = it },
            step = 5,
            range = 0..2_000,
            trailingContent = {
                InlineLogButton(contentDescription = "Log pages", onClick = { onLogPages(pages) })
            },
        )
    }
}

/**
 * Pages read per day over the last fortnight, with the daily goal marked.
 *
 * Zero-height days are real zeros here, not gaps: pages are only ever recorded by
 * logging them. Moved from the Activity trends to sit under the control that adds
 * to today's count.
 */
@Composable
private fun ReadingTrendCard(state: WellnessUiState) {
    TrackerCard(title = "Pages read", subtitle = "per day") {
        BarChart(
            days = state.logSeries { it.bookPagesRead?.toFloat() },
            goalLine = state.goals.dailyPagesGoal?.toFloat(),
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
    }
}

/** `Jul 22`, for a date-keyed reading whose time of day is not recorded. */
private fun LocalDate.asShortDate(): String = DateTimeFormatter.ofPattern("MMM d").format(this)
