package com.prestondihle.healthtracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * How much, and when, for one hand-logged intake -- used to log and to correct.
 *
 * One dialog for caffeine and water rather than one apiece. What differs between
 * them is a title, a step size and a unit; what they share is the part with the
 * traps in it -- clamping a saved time to now, refusing a date in the future,
 * and rebuilding an `Instant` from a picker in the right zone. A second copy of
 * that would be a second thing to fix each time one of them turns out to be
 * wrong, and the copy nobody remembered to fix is the one still shipping.
 *
 * Date is a pair of nudge buttons rather than a full calendar: an entry is
 * nearly always today or last night, and a second nested dialog to say so would
 * cost more taps than the whole entry is worth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntakeEntryDialog(
    /** Shown when logging something new. */
    newTitle: String,
    /** Shown when correcting an existing entry. */
    editTitle: String,
    /** Wording for the delete action, e.g. "Delete this dose". */
    deleteLabel: String,
    initialAmount: Int,
    step: Int,
    range: IntRange,
    /** The unit, and anything else worth saying about the amount as it changes. */
    supporting: (Int) -> String,
    initialTime: Instant,
    onDismiss: () -> Unit,
    onConfirm: (amount: Int, at: Instant) -> Unit,
    zoneId: ZoneId = ZoneId.systemDefault(),
    /** Supplied when correcting an existing entry; absent when logging a new one. */
    onDelete: (() -> Unit)? = null,
) {
    val initialLocal = remember(initialTime, zoneId) { LocalDateTime.ofInstant(initialTime, zoneId) }

    var amount by remember { mutableIntStateOf(initialAmount) }
    var date by remember { mutableStateOf<LocalDate>(initialLocal.toLocalDate()) }
    val timeState =
        rememberTimePickerState(
            initialHour = initialLocal.hour,
            initialMinute = initialLocal.minute,
            is24Hour = false,
        )

    val today = remember(zoneId) { LocalDate.now(zoneId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (onDelete == null) newTitle else editTitle) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IntStepper(
                    label = "Amount",
                    value = amount,
                    onValueChange = { amount = it },
                    step = step,
                    range = range,
                    supportingText = supporting(amount),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { date = date.minusDays(1) }) { Text("< Earlier") }
                    Text(
                        if (date == today) "Today" else DAY_FORMAT.format(date),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // An entry cannot be in the future, so forward stops at today.
                    TextButton(
                        onClick = { date = date.plusDays(1) },
                        enabled = date.isBefore(today),
                    ) {
                        Text("Later >")
                    }
                }

                CompactTimeField(state = timeState, modifier = Modifier.fillMaxWidth())

                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(deleteLabel) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val at =
                        date.atTime(LocalTime.of(timeState.hour, timeState.minute))
                            .atZone(zoneId)
                            .toInstant()
                    onConfirm(amount, minOf(at, Instant.now()))
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
