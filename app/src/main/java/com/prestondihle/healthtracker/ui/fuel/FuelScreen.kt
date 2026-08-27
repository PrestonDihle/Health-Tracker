package com.prestondihle.healthtracker.ui.fuel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.data.CaffeineIntake
import com.prestondihle.healthtracker.data.CreatineIntake
import com.prestondihle.healthtracker.data.FastingPlanDay
import com.prestondihle.healthtracker.data.FastingType
import com.prestondihle.healthtracker.data.PlannedExtendedFast
import com.prestondihle.healthtracker.data.Supplement
import com.prestondihle.healthtracker.data.SupplementSlot
import com.prestondihle.healthtracker.domain.FastingStats
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.ui.components.AxisRule
import com.prestondihle.healthtracker.ui.components.AxisSpec
import com.prestondihle.healthtracker.ui.components.BarHeight
import com.prestondihle.healthtracker.ui.components.CaffeineEntryDialog
import com.prestondihle.healthtracker.ui.components.CardGap
import com.prestondihle.healthtracker.ui.components.ChartAxis
import com.prestondihle.healthtracker.ui.components.ChartMarker
import com.prestondihle.healthtracker.ui.components.ChartSeries
import com.prestondihle.healthtracker.ui.components.CompactButtonPadding
import com.prestondihle.healthtracker.ui.components.DualAxisTimeChart
import com.prestondihle.healthtracker.ui.components.FastingTimeline
import com.prestondihle.healthtracker.ui.components.InlineLogButton
import com.prestondihle.healthtracker.ui.components.InstantPickerDialog
import com.prestondihle.healthtracker.ui.components.IntStepper
import com.prestondihle.healthtracker.ui.components.LogButton
import com.prestondihle.healthtracker.ui.components.Metric
import com.prestondihle.healthtracker.ui.components.ScaleDescriptors
import com.prestondihle.healthtracker.ui.components.Stepper
import com.prestondihle.healthtracker.ui.components.SupplementEntryDialog
import com.prestondihle.healthtracker.ui.components.TimePoint
import com.prestondihle.healthtracker.ui.components.TrackerCard
import com.prestondihle.healthtracker.ui.reorder.CardOrderViewModel
import com.prestondihle.healthtracker.ui.reorder.ReorderableCard
import com.prestondihle.healthtracker.ui.reorder.reorderableCards
import com.prestondihle.healthtracker.ui.theme.LocalChartColors
import com.prestondihle.healthtracker.ui.trends.MacrosTrendCard
import com.prestondihle.healthtracker.ui.trends.TrendsViewModel
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM")

private val ChartHeight = 170.dp

/** A chart with nothing in it gets less room, so an empty card is not mostly blank. */
private val EmptyChartHeight = 72.dp

/** Which end of a feeding window an open time picker is editing. */
private data class TimeEdit(val day: DayOfWeek, val editingStart: Boolean, val initial: LocalTime)

/** Which retroactive edit the picker is currently collecting a time for. */
private enum class FastEdit {
    START_OF_ACTIVE,
    END_OF_ACTIVE,
    START_OF_LAST,
    END_OF_LAST,
}

@Composable
fun FuelScreen(
    viewModel: FuelViewModel,
    snackbarHostState: SnackbarHostState,
    trendsViewModel: TrendsViewModel,
    orderViewModel: CardOrderViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The macro trend belongs with food: it reads from the same source Activity's
    // other trends do, so Fuel need not re-derive it.
    val trends by trendsViewModel.uiState.collectAsStateWithLifecycle()
    val savedOrder by orderViewModel.savedOrder.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var timeEdit by remember { mutableStateOf<TimeEdit?>(null) }
    var addingFast by remember { mutableStateOf(false) }

    fun toast(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = CardGap),
        verticalArrangement = Arrangement.spacedBy(CardGap),
        contentPadding = PaddingValues(vertical = CardGap),
    ) {
        // The whole tab is one reorderable list. The running fast leads by
        // default, because it is the only card counting: everything below it
        // reports a total that will not change until something is logged. The
        // extended-fast list rides inside its own card so it moves as one piece.
        reorderableCards(
            cards =
                listOf(
                    ReorderableCard("fast") {
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
                    },
                    ReorderableCard("adherence") { AdherenceCard(state) },
                    ReorderableCard("fastingPattern") {
                        TrackerCard(
                            title = "Fasting pattern",
                            subtitle = "Last 14 days. Filled is fasted, blank is eating.",
                        ) {
                            FastingTimeline(days = state.timeline, modifier = Modifier.fillMaxWidth())
                        }
                    },
                    ReorderableCard("stats") { StatsCard(state.stats) },
                    ReorderableCard("weeklyPlan") {
                        TrackerCard(
                            title = "Weekly plan",
                            subtitle =
                                "Times are when eating is allowed. Switch a day off for no " +
                                    "eating at all.",
                        ) {
                            state.orderedDays.forEach { day ->
                                PlanDayRow(
                                    day = day,
                                    onToggle = {
                                        viewModel.setHasFeedingWindow(day.dayOfWeek, it)
                                    },
                                    onEditStart = {
                                        timeEdit = TimeEdit(day.dayOfWeek, true, day.feedingStart)
                                    },
                                    onEditEnd = {
                                        timeEdit = TimeEdit(day.dayOfWeek, false, day.feedingEnd)
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    },
                    ReorderableCard("extendedFasts") {
                        TrackerCard(
                            title = "Extended fasts",
                            subtitle = "These override the weekly plan for the days they cover",
                        ) {
                            TextButton(onClick = { addingFast = true }) {
                                Text("Schedule an extended fast")
                            }
                            // Inside the card rather than as loose cards below it,
                            // so scheduling control and its list move together.
                            state.extendedFasts.forEach { fast ->
                                HorizontalDivider()
                                ExtendedFastRow(
                                    fast = fast,
                                    onDelete = { viewModel.deleteExtendedFast(fast) },
                                )
                            }
                        }
                    },
                    ReorderableCard("hydration") {
                        HydrationCard(
                            state = state,
                            onAdd = {
                                viewModel.addHydration(it)
                                toast("Logged $it ml")
                            },
                        )
                    },
                    ReorderableCard("caffeine") {
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
                    },
                    ReorderableCard("creatine") {
                        CreatineCard(
                            state = state,
                            onLog = { grams ->
                                viewModel.logCreatine(grams)
                                toast("Logged $grams g creatine")
                            },
                            onDelete = {
                                viewModel.deleteCreatine(it)
                                toast("Dose removed")
                            },
                        )
                    },
                    ReorderableCard("supplements") {
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
                    },
                    // Calories from protein, carbs and fat over the fortnight,
                    // moved here from Activity.
                    ReorderableCard("macros") { MacrosTrendCard(trends) },
                ),
            savedOrder = savedOrder,
            onMove = orderViewModel::move,
        )
    }

    timeEdit?.let { edit ->
        FeedingTimeDialog(
            initial = edit.initial,
            onDismiss = { timeEdit = null },
            onConfirm = { picked ->
                val day = state.orderedDays.first { it.dayOfWeek == edit.day }
                if (edit.editingStart) {
                    viewModel.setFeedingWindow(edit.day, picked, day.feedingEnd)
                } else {
                    viewModel.setFeedingWindow(edit.day, day.feedingStart, picked)
                }
                timeEdit = null
            },
        )
    }

    if (addingFast) {
        AddExtendedFastDialog(
            onDismiss = { addingFast = false },
            onConfirm = { date, type ->
                viewModel.addExtendedFast(date, type)
                addingFast = false
            },
        )
    }
}

@Composable
private fun AdherenceCard(state: FuelUiState) {
    TrackerCard(title = "Adherence", subtitle = "Week of ${DATE_FORMAT.format(state.weekStart)}") {
        val score = state.adherence?.score
        Text(
            text = score?.let { "$it%" } ?: "--",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        LinearProgressIndicator(
            progress = { (score ?: 0) / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        val planned = state.adherence?.plannedSeconds ?: 0L
        val fasted = state.adherence?.fastedSeconds ?: 0L
        Text(
            text =
                if (planned <= 0L) {
                    "No fasting planned so far this week."
                } else {
                    "${fasted / 3600}h fasted of ${planned / 3600}h planned so far. " +
                        "Time still ahead this week is not counted."
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "About ${state.plannedHoursPerWeek}h of fasting planned per full week.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatsCard(stats: FastingStats) {
    TrackerCard(title = "Fasting stats", subtitle = "Totals count overlapping fasts only once") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric("Today", stats.todaySeconds.asHours())
            Metric("7 days", stats.weekSeconds.asHours())
            Metric("30 days", stats.monthSeconds.asHours())
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric(
                "Longest",
                stats.longestFast?.let { Units.formatDuration(it) } ?: "--",
                stats.longestFastEnded?.let { DATE_FORMAT.format(it.atZone(ZoneId.systemDefault())) },
            )
            Metric(
                "Average",
                if (stats.completedFasts == 0) "--"
                else Units.formatDuration(Duration.ofSeconds(stats.averageFastSeconds)),
                "${stats.completedFasts} finished",
            )
            Metric(
                "Streak",
                "${stats.currentStreakDays}d",
                "best ${stats.bestStreakDays}d",
            )
        }
    }
}

/** Whole hours with an `h`, which is the resolution these totals are read at. */
private fun Long.asHours(): String = "${this / 3600}h"

@Composable
private fun PlanDayRow(
    day: FastingPlanDay,
    onToggle: (Boolean) -> Unit,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Fixed width rather than a weight: three-letter day names never need
        // more, and it leaves the chips enough room to stay on one line.
        Text(
            day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            modifier = Modifier.width(44.dp),
        )

        if (day.hasFeedingWindow) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                TimeChip(time = day.feedingStart, onClick = onEditStart)
                Text("to", style = MaterialTheme.typography.labelSmall)
                TimeChip(time = day.feedingEnd, onClick = onEditEnd)
            }
        } else {
            Text(
                "No eating — full day fast",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
        }

        Switch(
            checked = day.hasFeedingWindow,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics {
                contentDescription =
                    if (day.hasFeedingWindow) "Eating window on" else "No eating this day"
            },
        )
    }
}

/**
 * A tappable time. [softWrap] is off because "12:00 PM" would otherwise break
 * after the minutes and render as two stacked lines in a narrow chip.
 */
@Composable
private fun TimeChip(time: LocalTime, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                TIME_FORMAT.format(time),
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.labelLarge,
            )
        },
    )
}

@Composable
private fun ExtendedFastRow(fast: PlannedExtendedFast, onDelete: () -> Unit) {
    val zone = ZoneId.systemDefault()
    // A row inside the Extended fasts card, no longer its own card: the schedule
    // and its entries are one reorderable unit.
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                fast.type.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "${DATE_FORMAT.format(fast.startInstant.atZone(zone))} to " +
                    DATE_FORMAT.format(fast.endInstant.atZone(zone)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove planned fast")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedingTimeDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val pickerState =
        rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(pickerState.hour, pickerState.minute)) }) {
                Text("Set")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Feeding window") },
        text = { TimePicker(state = pickerState) },
    )
}

@Composable
private fun AddExtendedFastDialog(
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, FastingType) -> Unit,
) {
    var offsetDays by remember { mutableStateOf(0L) }
    var type by remember { mutableStateOf(FastingType.EXTENDED_24) }
    val date = LocalDate.now().plusDays(offsetDays)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(date, type) }) { Text("Schedule") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Schedule extended fast") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Starts ${DATE_FORMAT.format(date)} at midnight")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { offsetDays = (offsetDays - 1).coerceAtLeast(0) }) {
                        Text("Earlier")
                    }
                    TextButton(onClick = { offsetDays += 1 }) { Text("Later") }
                }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                            FastingType.EXTENDED_24 to "24h",
                            FastingType.EXTENDED_36 to "36h",
                            FastingType.EXTENDED_48 to "48h",
                        )
                        .forEach { (option, label) ->
                            AssistChip(onClick = { type = option }, label = { Text(label) })
                        }
                }
                Text(
                    "Selected: ${type.name.substringAfter('_')}h",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
    )
}

@Composable
private fun FastCard(
    state: FuelUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSetStart: (Instant) -> Unit,
    onStopAt: (Instant) -> Unit,
    onUpdateLast: (Instant, Instant) -> Unit,
) {
    var editing by remember { mutableStateOf<FastEdit?>(null) }

    TrackerCard(title = "Fasting") {
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
private fun HydrationCard(state: FuelUiState, onAdd: (Int) -> Unit) {
    TrackerCard(title = "Hydration") {
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

/** The maintenance dose, and so the only button most days need. */
private const val CREATINE_DOSE_G = 5

/**
 * Creatine taken today.
 *
 * The table, the DAO and the repository methods for this have existed since the
 * first commit and nothing was ever wired to them -- the feature was in the
 * database and not on the phone. A running total with an undo rather than a
 * tick, because unlike a supplement the interesting question is *how much*: a
 * loading week is four doses a day and a maintenance week is one.
 */
@Composable
private fun CreatineCard(
    state: FuelUiState,
    onLog: (Int) -> Unit,
    onDelete: (CreatineIntake) -> Unit,
) {
    TrackerCard(title = "Creatine") {
        Metric(
            label = "Today",
            value = "${state.creatineTodayGrams} g",
            supporting =
                when (state.creatineToday.size) {
                    0 -> null
                    1 -> "1 dose"
                    else -> "${state.creatineToday.size} doses"
                },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(
                onClick = { onLog(CREATINE_DOSE_G) },
                contentPadding = CompactButtonPadding,
            ) {
                Text("+$CREATINE_DOSE_G g")
            }
            FilledTonalButton(onClick = { onLog(1) }, contentPadding = CompactButtonPadding) {
                Text("+1 g")
            }
        }

        // Today's doses, newest first, each removable. A mistyped scoop is
        // otherwise stuck in the total until midnight with no way to take it
        // back.
        state.creatineToday.reversed().forEach { intake ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${intake.grams} g", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        intake.timestamp.asShortDateTime(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = { onDelete(intake) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remove ${intake.grams} g dose",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
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
    state: FuelUiState,
    onSetTaken: (Supplement, Boolean) -> Unit,
    onAdd: (String, String, SupplementSlot) -> Unit,
    onDelete: (Supplement) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Supplement?>(null) }

    TrackerCard(
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
    state: FuelUiState,
    onLog: (Int, Instant) -> Unit,
    onUpdate: (CaffeineIntake, Int, Instant) -> Unit,
    onDelete: (CaffeineIntake) -> Unit,
) {
    val chartColors = LocalChartColors.current
    var dialog by remember { mutableStateOf<CaffeineDialog?>(null) }

    TrackerCard(title = "Caffeine") {
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
                        color = chartColors.caffeine,
                        axis = ChartAxis.LEFT,
                        // The curve is a dense sampling of a continuous function,
                        // so dots would obscure the shape they are drawn from.
                        showPoints = false,
                    ),
                    ChartSeries(
                        label = "Projected",
                        points = state.caffeineForecast.map { TimePoint(it.first, it.second) },
                        color = chartColors.caffeine,
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



/** `Mon 14:05`, for showing when a fast started without spending a whole line on it. */
private fun Instant.asShortDateTime(state: FuelUiState): String =
    DateTimeFormatter.ofPattern("EEE h:mm a").format(atZone(state.zoneId))


/** Says which 9 PM the evening estimate means, since it rolls over once tonight's has passed. */
private fun Instant.asEveningSupporting(state: FuelUiState): String =
    if (atZone(state.zoneId).toLocalDate() == state.today) "tonight" else "tomorrow"
