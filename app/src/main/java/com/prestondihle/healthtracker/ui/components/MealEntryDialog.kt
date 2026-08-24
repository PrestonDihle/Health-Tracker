package com.prestondihle.healthtracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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

/** Atwater factors: the energy a gram of each macro carries. */
private const val KCAL_PER_PROTEIN_GRAM = 4
private const val KCAL_PER_CARB_GRAM = 4
private const val KCAL_PER_FAT_GRAM = 9

/** What one meal contributed, as typed. */
data class MealDraft(
    val calories: Int,
    val proteinGrams: Int,
    val carbGrams: Int,
    val fatGrams: Int,
    val at: Instant,
)

/**
 * Energy and macros for one meal, used both for logging and for correcting.
 *
 * Exists because a nutrition source can be wrong in ways nothing here can fix:
 * it may stamp every meal at the same time of day, write one meal several times,
 * or record a meal that was never eaten. The absorption curves are only worth
 * reading if the meals underneath them are, so the meals have to be editable.
 *
 * Date is a pair of nudge buttons rather than a calendar, matching
 * [CaffeineEntryDialog] -- a meal being corrected is nearly always today or
 * yesterday, and a nested dialog to say so would cost more taps than the whole
 * entry is worth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealEntryDialog(
    initial: MealDraft,
    onDismiss: () -> Unit,
    onConfirm: (MealDraft) -> Unit,
    zoneId: ZoneId = ZoneId.systemDefault(),
    /** Supplied when correcting an existing meal; absent when logging a new one. */
    /** Titles the dialog "Edit meal" rather than "Log meal"; deleting lives on the row. */
    isEdit: Boolean = false,
) {
    val initialLocal = remember(initial.at, zoneId) { LocalDateTime.ofInstant(initial.at, zoneId) }

    var calories by remember { mutableIntStateOf(initial.calories) }
    var protein by remember { mutableIntStateOf(initial.proteinGrams) }
    var carbs by remember { mutableIntStateOf(initial.carbGrams) }
    var fat by remember { mutableIntStateOf(initial.fatGrams) }
    var date by remember { mutableStateOf<LocalDate>(initialLocal.toLocalDate()) }
    val timeState =
        rememberTimePickerState(
            initialHour = initialLocal.hour,
            initialMinute = initialLocal.minute,
            is24Hour = false,
        )

    val today = remember(zoneId) { LocalDate.now(zoneId) }
    val fromMacros =
        protein * KCAL_PER_PROTEIN_GRAM + carbs * KCAL_PER_CARB_GRAM + fat * KCAL_PER_FAT_GRAM

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Edit meal" else "Log meal") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IntStepper(
                    label = "Calories",
                    value = calories,
                    onValueChange = { calories = it },
                    step = 10,
                    range = 0..10_000,
                    // Shown, never substituted: the macros imply an energy figure
                    // and the label that came with the meal may disagree with it.
                    // Which of the two is right is not this dialog's to decide.
                    supportingText =
                        if (fromMacros > 0) "kcal · macros come to $fromMacros" else "kcal",
                    valueFormatter = { "$it" },
                )

                HorizontalDivider()

                IntStepper(
                    label = "Protein",
                    value = protein,
                    onValueChange = { protein = it },
                    range = 0..500,
                    valueFormatter = { "$it g" },
                )
                IntStepper(
                    label = "Carbs",
                    value = carbs,
                    onValueChange = { carbs = it },
                    range = 0..500,
                    valueFormatter = { "$it g" },
                )
                IntStepper(
                    label = "Fat",
                    value = fat,
                    onValueChange = { fat = it },
                    range = 0..500,
                    valueFormatter = { "$it g" },
                )

                HorizontalDivider()

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
                    // A meal cannot be eaten in the future, so forward stops today.
                    TextButton(
                        onClick = { date = date.plusDays(1) },
                        enabled = date.isBefore(today),
                    ) {
                        Text("Later >")
                    }
                }

                TimePicker(state = timeState, modifier = Modifier.fillMaxWidth())

            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val at =
                        date.atTime(LocalTime.of(timeState.hour, timeState.minute))
                            .atZone(zoneId)
                            .toInstant()
                    onConfirm(
                        MealDraft(
                            calories = calories,
                            proteinGrams = protein,
                            carbGrams = carbs,
                            fatGrams = fat,
                            // Clamped for the same reason the picker's forward
                            // button stops: a meal in the future would start an
                            // absorption curve that has not happened.
                            at = minOf(at, Instant.now()),
                        )
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
