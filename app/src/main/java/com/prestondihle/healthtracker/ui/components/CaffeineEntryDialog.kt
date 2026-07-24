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
import androidx.compose.material3.TimePicker
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
 * Amount and time for one caffeine dose, used for both logging and correcting.
 *
 * Date is a pair of nudge buttons rather than a full calendar: a dose is nearly
 * always today or last night, and a second nested dialog to say so would cost
 * more taps than the whole entry is worth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaffeineEntryDialog(
    initialMilligrams: Int,
    initialTime: Instant,
    onDismiss: () -> Unit,
    onConfirm: (milligrams: Int, at: Instant) -> Unit,
    zoneId: ZoneId = ZoneId.systemDefault(),
    /** Supplied when correcting an existing dose; absent when logging a new one. */
    onDelete: (() -> Unit)? = null,
) {
    val initialLocal = remember(initialTime, zoneId) { LocalDateTime.ofInstant(initialTime, zoneId) }

    var milligrams by remember { mutableIntStateOf(initialMilligrams) }
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
        title = { Text(if (onDelete == null) "Log caffeine" else "Edit dose") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IntStepper(
                    label = "Amount",
                    value = milligrams,
                    onValueChange = { milligrams = it },
                    step = 5,
                    range = 0..1_000,
                    supportingText = "mg",
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
                    // A dose cannot be in the future, so forward stops at today.
                    TextButton(
                        onClick = { date = date.plusDays(1) },
                        enabled = date.isBefore(today),
                    ) {
                        Text("Later >")
                    }
                }

                TimePicker(state = timeState, modifier = Modifier.fillMaxWidth())

                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete this dose") }
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
                    onConfirm(milligrams, minOf(at, Instant.now()))
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
