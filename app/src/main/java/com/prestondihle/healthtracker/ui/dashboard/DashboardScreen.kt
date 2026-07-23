package com.prestondihle.healthtracker.ui.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.data.FastingType
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.health.HealthPermissionState
import com.prestondihle.healthtracker.ui.components.AxisSpec
import com.prestondihle.healthtracker.ui.components.ChartAxis
import com.prestondihle.healthtracker.ui.components.ChartSeries
import com.prestondihle.healthtracker.ui.components.DualAxisTimeChart
import com.prestondihle.healthtracker.ui.components.IntStepper
import com.prestondihle.healthtracker.ui.components.LabeledSlider
import com.prestondihle.healthtracker.ui.components.ScaleDescriptors
import com.prestondihle.healthtracker.ui.components.Stepper
import com.prestondihle.healthtracker.ui.components.TimePoint
import com.prestondihle.healthtracker.ui.theme.GlucoseSeries
import com.prestondihle.healthtracker.ui.theme.KetoneSeries
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        if (state.healthState != HealthPermissionState.GRANTED) {
            item {
                HealthConnectPrompt(
                    state = state.healthState,
                    onConnect = { permissionLauncher.launch(viewModel.healthPermissions) },
                )
            }
        }

        item { FastCard(state = state, onStart = viewModel::startFast, onEnd = viewModel::endFast) }

        item { ActivityCard(state = state, onRefresh = viewModel::refreshHealth) }

        item {
            HydrationCard(
                state = state,
                onAdd = {
                    viewModel.addHydration(it)
                    toast("Logged ${Units.mlToWholeOz(it)} oz")
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

        item { ReadingCard(state = state, onPagesChange = viewModel::setPages) }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

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
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Text(message, style = MaterialTheme.typography.bodyMedium)
        if (state == HealthPermissionState.NOT_GRANTED) {
            Button(onClick = onConnect) { Text("Connect") }
        }
    }
}

@Composable
private fun FastCard(state: DashboardUiState, onStart: (FastingType) -> Unit, onEnd: () -> Unit) {
    DashboardCard(title = "Fasting") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric(
                label = "Current fast",
                value = state.fastDuration?.let { Units.formatDuration(it) } ?: "Not fasting",
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
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
        }

        if (state.activeFast != null) {
            OutlinedButton(onClick = onEnd) { Text("End fast") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onStart(FastingType.CUSTOM) }) { Text("Start 16h") }
                FilledTonalButton(onClick = { onStart(FastingType.OMAD) }) { Text("OMAD") }
                FilledTonalButton(onClick = { onStart(FastingType.EXTENDED_24) }) { Text("24h") }
            }
        }
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric(label = "Calories", value = snapshot?.totalCalories?.toString() ?: "--")
            Metric(label = "Protein", value = snapshot?.proteinGrams?.let { "${it.toInt()} g" } ?: "--")
            Metric(label = "Carbs", value = snapshot?.carbGrams?.let { "${it.toInt()} g" } ?: "--")
            Metric(label = "Fat", value = snapshot?.fatGrams?.let { "${it.toInt()} g" } ?: "--")
        }

        state.bestMileSeconds?.let {
            HorizontalDivider()
            Metric(
                label = "Best mile",
                value = Units.formatPace(it),
                supporting = "average pace, runs over a mile",
            )
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
            supporting = "goal $goalOz oz",
        )
        LinearProgressIndicator(
            progress = { if (goalMl > 0) (state.hydrationMl.toFloat() / goalMl).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(8, 16, 32).forEach { ounces ->
                FilledTonalButton(onClick = { onAdd(Units.flOzToMl(ounces.toFloat())) }) {
                    Text("+$ounces oz")
                }
            }
        }
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
                        if (state.glucose.isEmpty() && state.ketones.isEmpty()) 96.dp else 220.dp
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
        Button(onClick = { onAddKetone(ketone) }, modifier = Modifier.fillMaxWidth()) {
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
        OutlinedButton(onClick = { onAddGlucose(glucose) }, modifier = Modifier.fillMaxWidth()) {
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
        Button(onClick = { onSubmit(systolic, diastolic) }, modifier = Modifier.fillMaxWidth()) {
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

        Button(onClick = { onSubmit(vibe, energy, focus) }, modifier = Modifier.fillMaxWidth()) {
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
        ) {
            Text("Log air squats")
        }
    }
}

@Composable
private fun ReadingCard(state: DashboardUiState, onPagesChange: (Int) -> Unit) {
    DashboardCard(title = "Reading") {
        IntStepper(
            label = "Pages read today",
            value = state.dailyLog.bookPagesRead ?: 0,
            onValueChange = onPagesChange,
            range = 0..2_000,
            supportingText = state.goals.dailyPagesGoal?.let { "goal $it" },
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun Metric(label: String, value: String, supporting: String? = null) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
