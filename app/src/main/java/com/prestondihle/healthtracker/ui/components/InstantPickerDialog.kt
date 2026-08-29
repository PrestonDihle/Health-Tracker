package com.prestondihle.healthtracker.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Which half of the picker is showing. */
private enum class PickerStep {
    DATE,
    TIME,
}

/**
 * Picks a past date and time, in two steps.
 *
 * Split rather than combined because Material 3 has no single date-and-time
 * control, and stacking both pickers in one dialog overflows a phone screen.
 *
 * Future dates are not selectable: every use of this is correcting something
 * that already happened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstantPickerDialog(
    title: String,
    initial: Instant,
    onDismiss: () -> Unit,
    onConfirm: (Instant) -> Unit,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val initialLocal = remember(initial, zoneId) { LocalDateTime.ofInstant(initial, zoneId) }
    var step by remember { mutableStateOf(PickerStep.DATE) }

    val todayEnd = remember(zoneId) { LocalDate.now(zoneId).plusDays(1).atStartOfDay(zoneId).toInstant() }

    val datePickerState =
        rememberDatePickerState(
            // The date picker works in UTC millis, so the local date has to be
            // reinterpreted at UTC or a late-evening date lands on the next day.
            initialSelectedDateMillis =
                initialLocal.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        utcTimeMillis < todayEnd.toEpochMilli()
                },
        )

    val timePickerState =
        rememberTimePickerState(
            initialHour = initialLocal.hour,
            initialMinute = initialLocal.minute,
            is24Hour = false,
        )

    fun selectedInstant(): Instant {
        val date =
            datePickerState.selectedDateMillis?.let {
                Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
            } ?: initialLocal.toLocalDate()
        return date
            .atTime(LocalTime.of(timePickerState.hour, timePickerState.minute))
            .atZone(zoneId)
            .toInstant()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (step == PickerStep.DATE) "$title — date" else "$title — time") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            ) {
                when (step) {
                    PickerStep.DATE -> DatePicker(state = datePickerState, title = null)
                    PickerStep.TIME ->
                        CompactTimeField(
                            state = timePickerState,
                            modifier = Modifier.fillMaxWidth(),
                        )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (step == PickerStep.DATE) step = PickerStep.TIME
                    else onConfirm(selectedInstant())
                }
            ) {
                Text(if (step == PickerStep.DATE) "Next" else "Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = { if (step == PickerStep.TIME) step = PickerStep.DATE else onDismiss() }
            ) {
                Text(if (step == PickerStep.TIME) "Back" else "Cancel")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
