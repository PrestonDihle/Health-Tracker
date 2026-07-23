package com.prestondihle.healthtracker.ui.fasting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.data.FastingPlanDay
import com.prestondihle.healthtracker.data.FastingType
import com.prestondihle.healthtracker.data.PlannedExtendedFast
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM")

/** Which end of a feeding window an open time picker is editing. */
private data class TimeEdit(val day: DayOfWeek, val editingStart: Boolean, val initial: LocalTime)

@Composable
fun FastingPlanScreen(viewModel: FastingPlanViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var timeEdit by remember { mutableStateOf<TimeEdit?>(null) }
    var addingFast by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item { AdherenceCard(state) }

        item {
            PlanCard(title = "Weekly plan", subtitle = "Times are when eating is allowed") {
                state.orderedDays.forEach { day ->
                    PlanDayRow(
                        day = day,
                        onToggle = { viewModel.setDayEnabled(day.dayOfWeek, it) },
                        onEditStart = {
                            timeEdit = TimeEdit(day.dayOfWeek, true, day.feedingStart)
                        },
                        onEditEnd = { timeEdit = TimeEdit(day.dayOfWeek, false, day.feedingEnd) },
                    )
                    HorizontalDivider()
                }
            }
        }

        item {
            PlanCard(
                title = "Extended fasts",
                subtitle = "These override the weekly plan for the days they cover",
            ) {
                TextButton(onClick = { addingFast = true }) { Text("Schedule an extended fast") }
            }
        }

        items(state.extendedFasts, key = { it.id }) { fast ->
            ExtendedFastRow(fast = fast, onDelete = { viewModel.deleteExtendedFast(fast) })
        }
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
private fun AdherenceCard(state: FastingPlanUiState) {
    PlanCard(title = "Adherence", subtitle = "Week of ${DATE_FORMAT.format(state.weekStart)}") {
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

        if (day.enabled) {
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
                "Unplanned",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        Switch(checked = day.enabled, onCheckedChange = onToggle)
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
private fun PlanCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
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
