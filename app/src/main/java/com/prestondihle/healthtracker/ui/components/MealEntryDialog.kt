package com.prestondihle.healthtracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
private val PRESET_FORMAT = DateTimeFormatter.ofPattern("h:mm a")

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
 * [IntakeEntryDialog] -- a meal being corrected is nearly always today or
 * yesterday, and a nested dialog to say so would cost more taps than the whole
 * entry is worth.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MealEntryDialog(
    initial: MealDraft,
    onDismiss: () -> Unit,
    onConfirm: (MealDraft) -> Unit,
    zoneId: ZoneId = ZoneId.systemDefault(),
    /** Supplied when correcting an existing meal; absent when logging a new one. */
    /** Titles the dialog "Edit meal" rather than "Log meal"; deleting lives on the row. */
    isEdit: Boolean = false,
    /**
     * The reader's habitual meal times, offered as one-tap chips above the clock.
     *
     * Empty for every meal whose time is already a measurement -- the chips are
     * a correction for a stamped time, and on a meal that has a real one they
     * would offer to overwrite it with a guess.
     */
    presets: List<LocalTime> = emptyList(),
    /**
     * This moment, injected for the same reason the view models take a `ZoneId`:
     * it is what decides which presets are still ahead of the reader, and a
     * dialog reading the wall clock can only be tested at the hours it happens
     * to agree with. The caller's is live -- the meal list ticks once a second.
     */
    now: Instant = Instant.now(),
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

    // `atZone().toLocalDate()`, never `LocalDate.ofInstant` -- that one is API 34
    // against a minSdk of 26 and is the crash task 0.2 removed from this tree.
    val today = remember(now, zoneId) { now.atZone(zoneId).toLocalDate() }
    val fromMacros =
        protein * KCAL_PER_PROTEIN_GRAM + carbs * KCAL_PER_CARB_GRAM + fat * KCAL_PER_FAT_GRAM

    // Shared by the Save button and the preset chips, so a chip writes exactly
    // what saving at that time would have written -- including the future clamp.
    val confirmAt: (LocalTime) -> Unit = { time ->
        val at = date.atTime(time).atZone(zoneId).toInstant()
        onConfirm(
            MealDraft(
                calories = calories,
                proteinGrams = protein,
                carbGrams = carbs,
                fatGrams = fat,
                // Clamped for the same reason the picker's forward button stops:
                // a meal in the future would start an absorption curve that has
                // not happened.
                at = minOf(at, now),
            )
        )
    }

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

                // Above the clock rather than below it. A stamped meal is opened
                // *because* the time is wrong, so the one-tap answer has to be
                // the first thing in reach; under a 250dp clock face it would be
                // below the fold of the dialog, which is where the series
                // switches were when the legend briefly replaced them.
                if (presets.isNotEmpty()) {
                    Text(
                        "Ate it at",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEach { preset ->
                            // A preset landing after now is disabled rather than
                            // silently clamped back to this moment. Tapping
                            // "6:30 PM" at two in the afternoon and getting a meal
                            // logged at 14:00 is a wrong write that looks like a
                            // right one -- the same failure as the mistyped
                            // hydration row, and the reason the date's forward
                            // button stops at today rather than correcting itself.
                            val notYet =
                                date.atTime(preset).atZone(zoneId).toInstant().isAfter(now)
                            AssistChip(
                                onClick = { confirmAt(preset) },
                                enabled = !notYet,
                                label = {
                                    Text(
                                        PRESET_FORMAT.format(preset),
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                },
                            )
                        }
                    }
                    Text(
                        "One tap saves the meal at that time. The clock below sets any other.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                CompactTimeField(state = timeState, modifier = Modifier.fillMaxWidth())

            }
        },
        confirmButton = {
            TextButton(onClick = { confirmAt(LocalTime.of(timeState.hour, timeState.minute)) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
