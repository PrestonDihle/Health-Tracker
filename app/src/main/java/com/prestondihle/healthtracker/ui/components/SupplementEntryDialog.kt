package com.prestondihle.healthtracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.prestondihle.healthtracker.data.SupplementSlot

/**
 * Name, dose and time of day for one supplement.
 *
 * The only free-text entry in the app, and it has to be: every other value here
 * is a quantity with a known unit and gets a stepper, but a supplement's name is
 * whatever is printed on the tub. The dose is text for the same reason -- IU,
 * mcg, capsules and millilitres all appear on the same shelf, and nothing in the
 * app does arithmetic on it.
 *
 * The slot is chips rather than a dropdown: there are exactly three, they all fit
 * on one row, and a dropdown would hide two of the three behind a tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupplementEntryDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, dose: String, slot: SupplementSlot) -> Unit,
    initialSlot: SupplementSlot = SupplementSlot.MORNING,
) {
    var name by remember { mutableStateOf("") }
    var dose by remember { mutableStateOf("") }
    var slot by remember { mutableStateOf(initialSlot) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a supplement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("Vitamin D3") },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Next,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Optional, and says so: plenty of things are simply "one
                // capsule", and demanding a figure for those would have the
                // reader invent one.
                OutlinedTextField(
                    value = dose,
                    onValueChange = { dose = it },
                    label = { Text("Dose (optional)") },
                    placeholder = { Text("5000 IU") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    "When",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SupplementSlot.entries.forEach { option ->
                        FilterChip(
                            selected = slot == option,
                            onClick = { slot = option },
                            label = { Text(option.label) },
                        )
                    }
                }

                Text(
                    "Something taken twice a day is added twice, once for each " +
                        "time -- that is what makes it tickable twice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, dose, slot) },
                // A nameless row is a checkbox nobody can identify. The dose may
                // be blank; the name may not.
                enabled = name.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
