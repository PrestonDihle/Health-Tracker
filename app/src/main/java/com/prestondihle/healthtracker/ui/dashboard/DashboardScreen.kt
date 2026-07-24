package com.prestondihle.healthtracker.ui.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.data.CaffeineIntake
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.health.HealthPermissionState
import com.prestondihle.healthtracker.ui.components.AxisSpec
import com.prestondihle.healthtracker.ui.components.CaffeineEntryDialog
import com.prestondihle.healthtracker.ui.components.ChartAxis
import com.prestondihle.healthtracker.ui.components.ChartSeries
import com.prestondihle.healthtracker.ui.components.DualAxisTimeChart
import com.prestondihle.healthtracker.ui.components.InstantPickerDialog
import com.prestondihle.healthtracker.ui.components.IntStepper
import com.prestondihle.healthtracker.ui.components.LabeledSlider
import com.prestondihle.healthtracker.ui.components.ScaleDescriptors
import com.prestondihle.healthtracker.ui.components.Stepper
import com.prestondihle.healthtracker.ui.components.TimePoint
import com.prestondihle.healthtracker.ui.theme.CaffeineSeries
import com.prestondihle.healthtracker.ui.theme.GlucoseSeries
import com.prestondihle.healthtracker.ui.theme.KetoneSeries
import com.prestondihle.healthtracker.ui.theme.Pine
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(viewModel: DashboardViewModel, snackbarHostState: SnackbarHostState) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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

        item {
            FastCard(
                state = state,
                onStart = viewModel::startFast,
                onStop = viewModel::endFast,
                onSetStart = viewModel::setActiveFastStart,
                onStopAt = {
                    viewModel.stopFastAt(it)
                    toast("Fast ended ${it.asShortDateTime(state)}")
                },
                onUpdateLast = { start, end ->
                    viewModel.updateLastFast(start, end)
                    toast("Corrected last fast")
                },
            )
        }

        item { ActivityCard(state = state, onRefresh = viewModel::refreshHealth) }

        item {
            HydrationCard(
                state = state,
                onAdd = {
                    viewModel.addHydration(it)
                    toast("Logged $it ml")
                },
            )
        }

        item {
            CaffeineCard(
                state = state,
                onLog = { mg, at ->
                    viewModel.logCaffeine(mg, at)
                    toast("Logged $mg mg caffeine")
                },
                onUpdate = { intake, mg, at ->
                    viewModel.updateCaffeine(intake, mg, at)
                    toast(if (mg <= 0) "Dose removed" else "Dose updated")
                },
                onDelete = {
                    viewModel.deleteCaffeine(it)
                    toast("Dose removed")
                },
            )
        }

        item {
            MetabolicCard(
                state = state,
                onAddKetone = {
                    viewModel.addKetone(it)
                    toast("Logged %.1f mmol/L".format(it))
                },
                onAddGlucose = {
                    viewModel.addBloodSugar(it)
                    toast("Logged $it mg/dL")
                },
            )
        }

        item { BodyCard(state = state, onWaistChange = viewModel::setWaistCm) }

        item {
            BloodPressureCard(
                state = state,
                onSubmit = { systolic, diastolic ->
                    viewModel.addBloodPressure(systolic, diastolic)
                    toast("Logged $systolic/$diastolic")
                },
            )
        }

        item {
            MoodCard(
                state = state,
                onSubmit = { vibe, energy, focus ->
                    viewModel.submitMood(vibe, energy, focus)
                    toast("Saved vibe $vibe, energy $energy, focus $focus")
                },
            )
        }

        item {
            MovementCard(
                state = state,
                onLog = { movement, reps ->
                    viewModel.logReps(movement, reps)
                    val name = if (movement == MovementType.PUSHUP) "pushups" else "air squats"
                    toast("Logged $reps $name")
                },
            )
        }

        item {
            ReadingCard(
                state = state,
                onLogPages = {
                    viewModel.logPages(it)
                    toast("Logged $it pages")
                },
                onSetPages = viewModel::setPages,
            )
        }

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

private val CardGap = 8.dp
private val CardPadding = 10.dp
private val CardSpacing = 6.dp
private val ChartHeight = 170.dp
private val EmptyChartHeight = 72.dp
private val BarHeight = 5.dp

/** Buttons at stock size waste a lot of vertical space when a card has three of them. */
private val CompactButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

@Composable
private fun DashboardCard(
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
            verticalArrangement = Arrangement.spacedBy(CardSpacing),
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

@Composable
private fun HealthConnectPrompt(state: HealthPermissionState, onConnect: () -> Unit) {
    DashboardCard(title = "Health Connect") {
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

/** Which retroactive edit the picker is currently collecting a time for. */
private enum class FastEdit {
    START_OF_ACTIVE,
    END_OF_ACTIVE,
    START_OF_LAST,
    END_OF_LAST,
}

/** Turns `android.permission.health.READ_WEIGHT` into `weight`. */
private fun String.asPermissionLabel(): String =
    substringAfterLast('.').removePrefix("READ_").lowercase().replace('_', ' ')

@Composable
private fun MissingPermissionsPrompt(missing: Set<String>, onGrant: () -> Unit) {
    DashboardCard(title = "Health Connect") {
        Text(
            "Not yet allowed to read: ${missing.joinToString { it.asPermissionLabel() }}. " +
                "Those metrics stay blank until granted.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onGrant, contentPadding = CompactButtonPadding) { Text("Grant") }
    }
}

@Composable
private fun FastCard(
    state: DashboardUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSetStart: (Instant) -> Unit,
    onStopAt: (Instant) -> Unit,
    onUpdateLast: (Instant, Instant) -> Unit,
) {
    var editing by remember { mutableStateOf<FastEdit?>(null) }

    DashboardCard(title = "Fasting") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric(
                label = "Current fast",
                value = state.fastDuration?.let { Units.formatDuration(it) } ?: "Not fasting",
                supporting =
                    state.activeFast?.let {
                        "since ${it.startInstant.asShortDateTime(state)}, goal ${it.goalDurationMinutes / 60}h"
                    },
            )
            Metric(
                label = "Adherence",
                value = state.adherence?.score?.let { "$it%" } ?: "--",
                supporting = if (state.hasPlan) "this week" else "no plan yet",
            )
        }

        state.fastGoalFraction?.let { fraction ->
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(BarHeight),
            )
        }

        // The goal length comes from the weekly plan, so there is nothing to
        // choose here -- just start and stop.
        if (state.activeFast != null) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = CompactButtonPadding,
            ) {
                Text("Stop")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { editing = FastEdit.START_OF_ACTIVE },
                    contentPadding = CompactButtonPadding,
                ) {
                    Text("Edit start")
                }
                TextButton(
                    onClick = { editing = FastEdit.END_OF_ACTIVE },
                    contentPadding = CompactButtonPadding,
                ) {
                    Text("Stop at past time")
                }
            }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = CompactButtonPadding,
            ) {
                Text("Start")
            }
            // Correcting the previous fast is only meaningful once one has
            // finished, and only when nothing is currently running.
            state.lastCompletedFast?.let { last ->
                Text(
                    "Last: ${last.startInstant.asShortDateTime(state)} to " +
                        "${last.endInstant?.asShortDateTime(state) ?: "--"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { editing = FastEdit.START_OF_LAST },
                        contentPadding = CompactButtonPadding,
                    ) {
                        Text("Edit last start")
                    }
                    TextButton(
                        onClick = { editing = FastEdit.END_OF_LAST },
                        contentPadding = CompactButtonPadding,
                    ) {
                        Text("Edit last end")
                    }
                }
            }
        }
    }

    editing?.let { edit ->
        val last = state.lastCompletedFast
        val initial =
            when (edit) {
                FastEdit.START_OF_ACTIVE -> state.activeFast?.startInstant
                FastEdit.END_OF_ACTIVE -> state.now
                FastEdit.START_OF_LAST -> last?.startInstant
                FastEdit.END_OF_LAST -> last?.endInstant
            } ?: state.now

        InstantPickerDialog(
            title =
                when (edit) {
                    FastEdit.START_OF_ACTIVE -> "Fast started"
                    FastEdit.END_OF_ACTIVE -> "Fast ended"
                    FastEdit.START_OF_LAST -> "Last fast started"
                    FastEdit.END_OF_LAST -> "Last fast ended"
                },
            initial = initial,
            zoneId = state.zoneId,
            onDismiss = { editing = null },
            onConfirm = { chosen ->
                when (edit) {
                    FastEdit.START_OF_ACTIVE -> onSetStart(chosen)
                    FastEdit.END_OF_ACTIVE -> onStopAt(chosen)
                    FastEdit.START_OF_LAST ->
                        last?.endInstant?.let { onUpdateLast(chosen, it) }
                    FastEdit.END_OF_LAST -> last?.let { onUpdateLast(it.startInstant, chosen) }
                }
                editing = null
            },
        )
    }
}

@Composable
private fun ActivityCard(state: DashboardUiState, onRefresh: () -> Unit) {
    DashboardCard(
        title = "Activity",
        action = {
            IconButton(onClick = onRefresh, enabled = !state.isSyncing) {
                Icon(Icons.Filled.Refresh, contentDescription = "Sync Health Connect")
            }
        },
    ) {
        val snapshot = state.snapshot
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric(
                label = "Steps",
                value = snapshot?.steps?.toString() ?: "--",
                supporting = state.goals.dailyStepGoal?.let { "goal $it" },
            )
            Metric(label = "Resting HR", value = snapshot?.restingHeartRateBpm?.let { "$it bpm" } ?: "--")
            Metric(
                label = "Sleep",
                value = snapshot?.sleepMinutes?.let { Units.formatMinutes(it) } ?: "--",
            )
        }

        HorizontalDivider()

        // Energy in, energy out, and the difference between them.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric(
                label = "Eaten",
                value = snapshot?.dietaryCalories?.toString() ?: "--",
                supporting = "kcal",
            )
            Metric(
                label = "Burned",
                value = snapshot?.totalCalories?.toString() ?: "--",
                supporting = snapshot?.activeCalories?.let { "$it active" } ?: "kcal",
            )

            val net = state.netCalories
            Metric(
                label = "Net",
                // Only meaningful with both halves; one alone would read as a
                // deficit the size of whichever number happens to exist.
                value = net?.let { if (it > 0) "+$it" else it.toString() } ?: "--",
                supporting =
                    when {
                        net == null -> "needs both"
                        net > 0 -> "surplus"
                        net < 0 -> "deficit"
                        else -> "even"
                    },
                valueColor =
                    when {
                        net == null || net == 0 -> null
                        net > 0 -> MaterialTheme.colorScheme.error
                        else -> Pine
                    },
            )
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric(label = "Protein", value = snapshot?.proteinGrams?.let { "${it.toInt()} g" } ?: "--")
            Metric(label = "Carbs", value = snapshot?.carbGrams?.let { "${it.toInt()} g" } ?: "--")
            Metric(label = "Fat", value = snapshot?.fatGrams?.let { "${it.toInt()} g" } ?: "--")
            state.bestMileSeconds?.let {
                Metric(label = "Best mile", value = Units.formatPace(it), supporting = "avg pace")
            }
        }
    }
}

@Composable
private fun HydrationCard(state: DashboardUiState, onAdd: (Int) -> Unit) {
    DashboardCard(title = "Hydration") {
        val goalMl = state.goals.dailyWaterMlGoal ?: 2957
        val oz = Units.mlToWholeOz(state.hydrationMl)
        val goalOz = Units.mlToWholeOz(goalMl)

        Metric(
            label = "Today",
            value = "$oz oz",
            supporting = "${state.hydrationMl} ml, goal $goalOz oz",
        )
        LinearProgressIndicator(
            progress = { if (goalMl > 0) (state.hydrationMl.toFloat() / goalMl).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(BarHeight),
        )
        // Two sizes only, one imperial and one metric, matching the two vessels
        // actually drunk from. A row of five buttons was more choice than the
        // decision deserves.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(
                onClick = { onAdd(Units.flOzToMl(4f)) },
                contentPadding = CompactButtonPadding,
            ) {
                Text("+4 oz")
            }
            FilledTonalButton(onClick = { onAdd(100) }, contentPadding = CompactButtonPadding) {
                Text("+100 ml")
            }
        }
    }
}

/** Null means the dialog is logging a new dose; a value means it is editing that one. */
private sealed interface CaffeineDialog {
    data object New : CaffeineDialog

    data class Edit(val intake: CaffeineIntake) : CaffeineDialog
}

@Composable
private fun CaffeineCard(
    state: DashboardUiState,
    onLog: (Int, Instant) -> Unit,
    onUpdate: (CaffeineIntake, Int, Instant) -> Unit,
    onDelete: (CaffeineIntake) -> Unit,
) {
    var dialog by remember { mutableStateOf<CaffeineDialog?>(null) }

    DashboardCard(title = "Caffeine") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric(
                label = "In body now",
                value = "${state.caffeineNowMg.toInt()} mg",
                supporting = "5h half-life",
            )
            Metric(label = "Taken today", value = "${state.caffeineTodayMg} mg")
        }

        DualAxisTimeChart(
            windowStart = state.caffeineWindowStart,
            windowEnd = state.now,
            series =
                listOf(
                    ChartSeries(
                        label = "Caffeine",
                        points = state.caffeineCurve.map { TimePoint(it.first, it.second) },
                        color = CaffeineSeries,
                        axis = ChartAxis.LEFT,
                        // The curve is a dense sampling of a continuous function,
                        // so dots would obscure the shape they are drawn from.
                        showPoints = false,
                    )
                ),
            leftAxis = AxisSpec(min = 0f, max = 200f, label = "mg"),
            modifier =
                Modifier.fillMaxWidth()
                    .height(if (state.caffeine.isEmpty()) EmptyChartHeight else ChartHeight),
        )

        HorizontalDivider()

        Button(
            onClick = { dialog = CaffeineDialog.New },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = CompactButtonPadding,
        ) {
            Text("Log caffeine")
        }

        // Doses inside the plotted window, newest first, each tappable to fix
        // an amount or a time that was guessed at when logged.
        val recent =
            state.caffeine
                .filter { !it.timestamp.isBefore(state.caffeineWindowStart) }
                .sortedByDescending { it.timestamp }

        if (recent.isNotEmpty()) {
            recent.forEach { intake ->
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable { dialog = CaffeineDialog.Edit(intake) }
                            .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${intake.milligrams} mg",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        intake.timestamp.asShortDateTime(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    dialog?.let { open ->
        val editing = (open as? CaffeineDialog.Edit)?.intake
        CaffeineEntryDialog(
            initialMilligrams = editing?.milligrams ?: 95,
            initialTime = editing?.timestamp ?: state.now,
            zoneId = state.zoneId,
            onDismiss = { dialog = null },
            onConfirm = { mg, at ->
                if (editing == null) onLog(mg, at) else onUpdate(editing, mg, at)
                dialog = null
            },
            onDelete =
                editing?.let {
                    {
                        onDelete(it)
                        dialog = null
                    }
                },
        )
    }
}

@Composable
private fun MetabolicCard(
    state: DashboardUiState,
    onAddKetone: (Float) -> Unit,
    onAddGlucose: (Int) -> Unit,
) {
    DashboardCard(title = "Glucose and ketones") {
        DualAxisTimeChart(
            windowStart = state.glucoseWindowStart,
            windowEnd = state.now,
            series =
                listOf(
                    ChartSeries(
                        label = "Glucose",
                        points = state.glucose.map { TimePoint(it.timestamp, it.mgDl.toFloat()) },
                        color = GlucoseSeries,
                        axis = ChartAxis.LEFT,
                        // A CGM writes every few minutes; dots would merge into a band.
                        showPoints = state.glucose.size <= 24,
                    ),
                    ChartSeries(
                        label = "Ketones",
                        points = state.ketones.map { TimePoint(it.timestamp, it.mmolL) },
                        color = KetoneSeries,
                        axis = ChartAxis.RIGHT,
                    ),
                ),
            leftAxis = AxisSpec(min = 60f, max = 200f, label = "mg/dL"),
            rightAxis =
                AxisSpec(min = 0f, max = 5f, label = "mmol/L", format = { "%.1f".format(it) }),
            // An empty plot does not need a full-height canvas to say so.
            modifier =
                Modifier.fillMaxWidth()
                    .height(
                        if (state.glucose.isEmpty() && state.ketones.isEmpty()) EmptyChartHeight
                        else ChartHeight
                    ),
        )

        HorizontalDivider()

        var ketone by remember { mutableFloatStateOf(1.0f) }
        Stepper(
            label = "Ketones",
            value = ketone,
            onValueChange = { ketone = it },
            step = 0.1f,
            range = 0f..10f,
            valueFormatter = { "%.1f".format(it) },
            supportingText = "mmol/L",
        )
        Button(
            onClick = { onAddKetone(ketone) },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = CompactButtonPadding,
        ) {
            Text("Log ketones")
        }

        var glucose by remember { mutableIntStateOf(90) }
        IntStepper(
            label = "Blood sugar",
            value = glucose,
            onValueChange = { glucose = it },
            step = 1,
            range = 20..500,
            supportingText = "mg/dL, for manual fingersticks",
        )
        OutlinedButton(
            onClick = { onAddGlucose(glucose) },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = CompactButtonPadding,
        ) {
            Text("Log blood sugar")
        }
    }
}

@Composable
private fun BodyCard(state: DashboardUiState, onWaistChange: (Float) -> Unit) {
    DashboardCard(title = "Body") {
        val inches = Units.cmToInches(state.waistCm)
        Stepper(
            label = "Waist",
            value = inches,
            onValueChange = { onWaistChange(Units.inchesToCm(it)) },
            step = 0.25f,
            range = 20f..70f,
            snap = Units::roundToQuarter,
            valueFormatter = Units::formatInches,
            supportingText =
                if (state.hasWaistMeasurement) "quarter-inch steps" else "default until measured",
        )
    }
}

@Composable
private fun BloodPressureCard(state: DashboardUiState, onSubmit: (Int, Int) -> Unit) {
    DashboardCard(title = "Blood pressure") {
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
        Button(
            onClick = { onSubmit(systolic, diastolic) },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = CompactButtonPadding,
        ) {
            Text("Log blood pressure")
        }

        state.latestBloodPressure?.let {
            Text(
                "Last today: ${it.systolic}/${it.diastolic}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MoodCard(state: DashboardUiState, onSubmit: (Int, Int, Int) -> Unit) {
    DashboardCard(title = "How are you?") {
        var vibe by remember(state.dailyLog.vibe) { mutableIntStateOf(state.dailyLog.vibe ?: 5) }
        var energy by
            remember(state.dailyLog.energy) { mutableIntStateOf(state.dailyLog.energy ?: 5) }
        var focus by remember(state.dailyLog.focus) { mutableIntStateOf(state.dailyLog.focus ?: 5) }

        LabeledSlider("Vibe", vibe, { vibe = it }, ScaleDescriptors.Vibe)
        LabeledSlider("Energy", energy, { energy = it }, ScaleDescriptors.Energy)
        LabeledSlider("Focus", focus, { focus = it }, ScaleDescriptors.Focus)

        Button(
            onClick = { onSubmit(vibe, energy, focus) },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = CompactButtonPadding,
        ) {
            Text("Submit")
        }
    }
}

@Composable
private fun MovementCard(state: DashboardUiState, onLog: (MovementType, Int) -> Unit) {
    DashboardCard(title = "Movement") {
        var pushups by remember { mutableIntStateOf(20) }
        var squats by remember { mutableIntStateOf(20) }

        IntStepper(
            label = "Pushups",
            value = pushups,
            onValueChange = { pushups = it },
            step = 5,
            range = 0..500,
            supportingText = "${state.pushupsToday} today",
        )
        Button(
            onClick = { onLog(MovementType.PUSHUP, pushups) },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = CompactButtonPadding,
        ) {
            Text("Log pushups")
        }

        HorizontalDivider()

        IntStepper(
            label = "Air squats",
            value = squats,
            onValueChange = { squats = it },
            step = 5,
            range = 0..500,
            supportingText = "${state.squatsToday} today",
        )
        Button(
            onClick = { onLog(MovementType.AIR_SQUAT, squats) },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = CompactButtonPadding,
        ) {
            Text("Log air squats")
        }
    }
}

@Composable
private fun ReadingCard(
    state: DashboardUiState,
    onLogPages: (Int) -> Unit,
    onSetPages: (Int) -> Unit,
) {
    DashboardCard(title = "Reading") {
        val readToday = state.dailyLog.bookPagesRead ?: 0
        val goal = state.goals.dailyPagesGoal

        Metric(
            label = "Pages today",
            value = readToday.toString(),
            supporting = goal?.let { "goal $it" },
        )
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
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onLogPages(pages) },
                modifier = Modifier.weight(1f),
                contentPadding = CompactButtonPadding,
            ) {
                Text("Log pages")
            }
            if (readToday > 0) {
                OutlinedButton(
                    onClick = { onSetPages(0) },
                    contentPadding = CompactButtonPadding,
                ) {
                    Text("Reset")
                }
            }
        }
    }
}

/** `Mon 14:05`, for showing when a fast started without spending a whole line on it. */
private fun Instant.asShortDateTime(state: DashboardUiState): String =
    DateTimeFormatter.ofPattern("EEE h:mm a").format(atZone(state.zoneId))

// ---------------------------------------------------------------------------

@Composable
private fun Metric(
    label: String,
    value: String,
    supporting: String? = null,
    /** Null keeps the default text colour; set only where the value itself carries meaning. */
    valueColor: Color? = null,
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: Color.Unspecified,
        )
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
