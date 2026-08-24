package com.prestondihle.healthtracker.ui.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.data.CaffeineIntake
import com.prestondihle.healthtracker.data.Supplement
import com.prestondihle.healthtracker.data.SupplementSlot
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.domain.Glucose
import com.prestondihle.healthtracker.domain.Ketones
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.health.HealthPermissionState
import androidx.compose.material3.FilterChip
import com.prestondihle.healthtracker.ui.components.AxisRule
import com.prestondihle.healthtracker.ui.components.AxisSpec
import com.prestondihle.healthtracker.ui.components.CaffeineEntryDialog
import com.prestondihle.healthtracker.ui.components.SupplementEntryDialog
import com.prestondihle.healthtracker.ui.components.ChartAxis
import com.prestondihle.healthtracker.ui.components.ChartMarker
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
import java.time.LocalDate
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
            SupplementsCard(
                state = state,
                onSetTaken = viewModel::setSupplementTaken,
                onAdd = { name, dose, slot ->
                    viewModel.addSupplement(name, dose, slot)
                    toast("Added $name")
                },
                onDelete = {
                    viewModel.deleteSupplement(it)
                    toast("Removed ${it.name}")
                },
            )
        }

        item {
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
        }

        item { BodyCard(state = state, onWaistChange = viewModel::setWaistCm) }

        item {
            GripStrengthCard(
                state = state,
                onLog = { dominant, lbs ->
                    viewModel.logGripStrengthKg(dominant, Units.lbsToKg(lbs))
                    val hand = if (dominant) "dominant" else "non-dominant"
                    toast("Logged ${lbs.toInt()} lb $hand grip")
                },
            )
        }

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

/**
 * A commit action sized to sit inline with a [Stepper]'s arrows.
 *
 * Passed as the stepper's `trailingContent`, this collapses what used to be a
 * whole second row -- a right-aligned "Log X" button -- onto the same line as the
 * value being logged. Filled (primary) where the arrows are tonal, so the button
 * that writes a reading stands apart from the two that only nudge it. Each card
 * reclaims a full button-height row, which is most of the point of the dense
 * layout: more metrics above the fold.
 */
@Composable
private fun InlineLogButton(
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    FilledIconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Filled.Check, contentDescription = contentDescription)
    }
}

/**
 * A compact log action, right-aligned under its inputs.
 *
 * Used where there is no single stepper to sit inline with: the mood card's
 * three sliders, and caffeine, where the button opens a dialog rather than
 * committing a dialled-in value. A small button pinned to the right reads as
 * "commit" without giving a secondary action the weight of a full-width primary
 * button.
 */
@Composable
private fun LogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onClick, enabled = enabled, contentPadding = CompactButtonPadding) {
            Text(text)
        }
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

/** Starting amount for a new dose, matching the usual serving actually drunk. */
private const val DEFAULT_CAFFEINE_MG = 70

/** Null means the dialog is logging a new dose; a value means it is editing that one. */
private sealed interface CaffeineDialog {
    data object New : CaffeineDialog

    data class Edit(val intake: CaffeineIntake) : CaffeineDialog
}

/**
 * The daily stack, grouped by when it is taken.
 *
 * A standing list with a tick per day rather than a dose typed out each morning:
 * a supplement stack is the same every day, and re-entering "Vitamin D3, 5000
 * IU" seven times a week is how a tracker stops being used by Wednesday.
 *
 * Only the slots holding something are drawn. Three empty headings for somebody
 * who takes two things at breakfast is furniture standing where the data should
 * be.
 */
@Composable
private fun SupplementsCard(
    state: DashboardUiState,
    onSetTaken: (Supplement, Boolean) -> Unit,
    onAdd: (String, String, SupplementSlot) -> Unit,
    onDelete: (Supplement) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Supplement?>(null) }

    DashboardCard(
        title = "Supplements",
        action = {
            IconButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add a supplement")
            }
        },
    ) {
        if (state.supplements.isEmpty()) {
            Text(
                "Nothing in the stack yet. Add what you take and when, and it " +
                    "comes back every day with a box to tick.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Counted over the whole stack rather than per slot: the question at
            // the top of a card is "am I done", and the evening's two are not a
            // separate question from the morning's five.
            Text(
                "${state.supplementsTakenCount} of ${state.supplements.size} taken today",
                style = MaterialTheme.typography.bodyMedium,
            )

            SupplementSlot.entries.forEach { slot ->
                val inSlot = state.supplements.filter { it.slot == slot }
                if (inSlot.isEmpty()) return@forEach

                HorizontalDivider()
                Text(
                    slot.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                inSlot.forEach { supplement ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = supplement.id in state.supplementsTaken,
                            onCheckedChange = { onSetTaken(supplement, it) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(supplement.name, style = MaterialTheme.typography.bodyMedium)
                            // Only where there is one. A blank line under the
                            // name reads as a missing value rather than as a
                            // supplement with no figure worth quoting.
                            if (supplement.dose.isNotBlank()) {
                                Text(
                                    supplement.dose,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { pendingDelete = supplement }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Remove ${supplement.name}",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        SupplementEntryDialog(
            onDismiss = { adding = false },
            onConfirm = { name, dose, slot ->
                onAdd(name, dose, slot)
                adding = false
            },
        )
    }

    // Confirmed rather than immediate, because removing one takes its history
    // with it -- unlike every other delete on this screen, which loses a single
    // reading.
    pendingDelete?.let { supplement ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${supplement.name}?") },
            text = {
                Text(
                    "This also clears the record of the days it was taken.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(supplement)
                        pendingDelete = null
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Keep") } },
        )
    }
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
        // Taken today leads: it is the only figure here that is a plain fact
        // rather than a model output, and it is what a daily limit is read
        // against. The three to its right are all the same decay curve sampled
        // at now, at the six-hour horizon, and at bedtime.
        Row(modifier = Modifier.fillMaxWidth()) {
            Metric(
                label = "Taken today",
                value = "${state.caffeineTodayMg} mg",
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "In body now",
                value = "${state.caffeineNowMg.toInt()} mg",
                supporting = "5h half-life",
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "In ${state.caffeineForecastHours}h",
                value = "${state.caffeineForecastEndMg.toInt()} mg",
                supporting = "if nothing more",
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = "At 9 PM",
                value = "${state.caffeineEveningMg.toInt()} mg",
                supporting = state.caffeineEveningTime.asEveningSupporting(state),
                modifier = Modifier.weight(1f),
            )
        }

        DualAxisTimeChart(
            windowStart = state.caffeineWindowStart,
            windowEnd = state.caffeineWindowEnd,
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
                    ),
                    ChartSeries(
                        label = "Projected",
                        points = state.caffeineForecast.map { TimePoint(it.first, it.second) },
                        color = CaffeineSeries,
                        axis = ChartAxis.LEFT,
                        showPoints = false,
                        // Dashed so a projection is never mistaken for a reading.
                        dashed = true,
                    ),
                ),
            leftAxis = AxisSpec(min = 0f, max = 200f, label = "mg"),
            markers =
                listOf(
                    // Now sits at the centre of the window, so this rule also
                    // divides the measured half of the chart from the projected one.
                    ChartMarker(
                        time = state.now,
                        label = "${state.caffeineNowMg.toInt()} mg now",
                    ),
                    ChartMarker(
                        time = state.caffeineForecastTime,
                        label = "${state.caffeineForecastEndMg.toInt()} mg",
                        dashed = true,
                    ),
                ),
            modifier =
                Modifier.fillMaxWidth()
                    .height(if (state.caffeine.isEmpty()) EmptyChartHeight else ChartHeight),
        )

        HorizontalDivider()

        LogButton("Log caffeine", onClick = { dialog = CaffeineDialog.New })

        // Recent doses, newest first, each tappable to fix an amount or a time
        // that was guessed at when logged.
        val recent =
            state.caffeine
                .filter { !it.timestamp.isBefore(state.caffeineEditableFrom) }
                .sortedByDescending { it.timestamp }

        if (recent.isNotEmpty()) {
            recent.forEach { intake ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Tapping the row edits; the bin deletes outright, so removing
                    // a mis-logged dose does not mean opening a dialog first.
                    Row(
                        modifier =
                            Modifier.weight(1f)
                                .clickable { dialog = CaffeineDialog.Edit(intake) }
                                .padding(vertical = 4.dp),
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
                    IconButton(
                        onClick = { onDelete(intake) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription =
                                "Delete ${intake.milligrams} mg at " +
                                    intake.timestamp.asShortDateTime(state),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }

    dialog?.let { open ->
        val editing = (open as? CaffeineDialog.Edit)?.intake
        CaffeineEntryDialog(
            initialMilligrams = editing?.milligrams ?: DEFAULT_CAFFEINE_MG,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetabolicCard(
    state: DashboardUiState,
    onWindowChange: (GlucoseWindow) -> Unit,
    onSmoothChange: (Boolean) -> Unit,
    onAddKetone: (Float) -> Unit,
    onAddGlucose: (Int) -> Unit,
) {
    DashboardCard(title = "Glucose and ketones") {
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
                        color = GlucoseSeries,
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
                        color = KetoneSeries,
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
private fun BodyCard(state: DashboardUiState, onWaistChange: (Float) -> Unit) {
    DashboardCard(title = "Body") {
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
    }
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
private fun GripStrengthCard(state: DashboardUiState, onLog: (Boolean, Float) -> Unit) {
    DashboardCard(title = "Grip strength") {
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
private fun MoodCard(state: DashboardUiState, onSubmit: (Int, Int, Int) -> Unit) {
    DashboardCard(title = "How are you?") {
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
private fun ReadingCard(
    state: DashboardUiState,
    onLogPages: (Int) -> Unit,
    onSetPages: (Int) -> Unit,
) {
    DashboardCard(title = "Reading") {
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

/** `Mon 14:05`, for showing when a fast started without spending a whole line on it. */
private fun Instant.asShortDateTime(state: DashboardUiState): String =
    DateTimeFormatter.ofPattern("EEE h:mm a").format(atZone(state.zoneId))

/** `Jul 22`, for a date-keyed reading whose time of day is not recorded. */
private fun LocalDate.asShortDate(): String = DateTimeFormatter.ofPattern("MMM d").format(this)

/** Says which 9 PM the evening estimate means, since it rolls over once tonight's has passed. */
private fun Instant.asEveningSupporting(state: DashboardUiState): String =
    if (atZone(state.zoneId).toLocalDate() == state.today) "tonight" else "tomorrow"

// ---------------------------------------------------------------------------

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
