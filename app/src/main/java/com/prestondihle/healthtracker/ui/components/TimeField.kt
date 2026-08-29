package com.prestondihle.healthtracker.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The one time control in the app, and deliberately the typed one rather than
 * the clock face.
 *
 * Material's dial is around 250dp tall before its AM/PM column, which is most of
 * a phone's dialog. That cost has already been paid twice here: the meal-time
 * preset chips had to be moved *above* the picker because under it they fell
 * below the fold, and every entry dialog that carries a date row, an amount
 * stepper and a delete button was reduced to scrolling to reach its own Save.
 * [TimeInput] asks the same question in two text fields and about a third of the
 * height, so the rest of a dialog is visible while the time is being set.
 *
 * It is also fewer gestures for the thing this is actually used for. Every
 * caller here is *correcting* a time that is already close to right -- a meal
 * the source stamped at 10:00, a drink logged an hour late -- and the reader
 * arrives knowing the four digits they want. The dial costs two drags and a
 * mode switch to say what typing says outright.
 *
 * Shared rather than copied to the five call sites for [IntakeEntryDialog]'s
 * reason: the traps in a time control are the same everywhere, and five copies
 * are five things to fix each time one of them turns out to be wrong.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactTimeField(state: TimePickerState, modifier: Modifier = Modifier) {
    // Centred rather than left-aligned: TimeInput lays out to its own intrinsic
    // width, and against the full-width steppers and date rows it sits beside, a
    // control hugging the left edge reads as misaligned rather than as compact.
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TimeInput(state = state)
    }
}
