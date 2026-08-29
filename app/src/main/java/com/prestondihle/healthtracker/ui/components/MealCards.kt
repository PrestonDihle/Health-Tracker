package com.prestondihle.healthtracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prestondihle.healthtracker.data.MealEntry
import com.prestondihle.healthtracker.domain.Macro
import com.prestondihle.healthtracker.domain.MacroAbsorption
import com.prestondihle.healthtracker.domain.MealResponse
import com.prestondihle.healthtracker.domain.MealResponses
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Meal cards.
//
// Both lived on Today, under the food chart. The reference card ("About the
// food curves") moved to the foot of Settings, and the meal list moved to Log,
// so they are shared from here rather than tied to one screen's state -- the
// list takes its meals and a few helpers as parameters, not a whole UiState.
// ---------------------------------------------------------------------------

/** Where the absorption curves come from, since they are the one modelled thing on the chart. */
@Composable
internal fun AbsorptionModelCard() {
    TrackerCard(title = "About the food curves") {
        Text(
            "Health Connect records a meal as one lump of grams at one time. The dashed lines " +
                "spread each meal over the hours it is actually reaching the blood, so the area " +
                "under a curve is the grams eaten and its height is grams per hour arriving.",
            style = MaterialTheme.typography.bodySmall,
        )
        Macro.entries.forEach { macro ->
            val kinetics = MacroAbsorption.kinetics(macro)
            Text(
                "${macro.label}: starts ${kinetics.lag.toMinutes()} min after eating, " +
                    "peaks at ${kinetics.timeToPeak.asPeak()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Peak times are population averages for mixed meals, from published gastric " +
                "emptying, aminoacidaemia and chylomicron studies. Individual digestion varies " +
                "several-fold, so read these as an expectation rather than a measurement.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The last day's meals, each tappable to say when it was eaten.
 *
 * Editable because a nutrition source is free to record only the date. When it
 * does, every meal arrives at one fixed time of day -- so such a meal says so
 * rather than printing a plausible-looking clock time, and one tap fixes it.
 *
 * [meals] and [undatedMeals] are already scoped to the window the caller wants
 * shown (on Log, the last 24 hours), and [hasClockTime] is the caller's own test
 * for a stamped-versus-measured time, so this card carries no window of its own.
 */
@Composable
internal fun MealListCard(
    meals: List<MealEntry>,
    undatedMeals: List<MealEntry>,
    duplicatesCollapsed: Int,
    zoneId: ZoneId,
    now: Instant,
    hasClockTime: (MealEntry) -> Boolean,
    onAdd: (calories: Int, protein: Int, carbs: Int, fat: Int, at: Instant) -> Unit,
    onUpdate: (MealEntry, Int, Int, Int, Int, Instant) -> Unit,
    onDelete: (MealEntry) -> Unit,
    /** The reader's habitual meal times, offered only where the time is stamped. */
    mealPresets: List<LocalTime> = emptyList(),
    /** What each meal did to the blood sugar; absent where it cannot be said. */
    responseFor: (MealEntry) -> MealResponse? = { null },
    /**
     * Whether there is any CGM data at all in the window.
     *
     * Separates "the sensor did not cover this meal" from "there is no CGM here",
     * which is the same distinction the Fuel report card draws. Without it every
     * meal on a phone with no monitor would carry a line explaining an absence
     * the reader already knows about.
     */
    hasGlucose: Boolean = false,
) {
    var editing by remember { mutableStateOf<MealEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf<MealEntry?>(null) }

    TrackerCard(
        title = "Meals, last 24 hours",
        action = {
            TextButton(onClick = { adding = true }, contentPadding = CompactButtonPadding) {
                Text("Log meal")
            }
        },
    ) {
        if (meals.isEmpty()) {
            Text(
                "Nothing eaten in the last 24 hours, or nothing that reached Health Connect. " +
                    "Log a meal to record it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val undated = undatedMeals.size
        if (undated > 0) {
            Text(
                "$undated of these carry a stamped time rather than the one ${
                    if (undated == 1) "it was" else "they were"
                } eaten at. Tap one to say when you actually ate it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        EntryList(entries = meals) { meal ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable { editing = meal }
                        .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        meal.name?.takeIf { it.isNotBlank() } ?: "Meal",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        meal.macroSummary(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    responseSummary(responseFor(meal), hasClockTime(meal), hasGlucose)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val placed = hasClockTime(meal)
                Text(
                    if (placed) {
                        DateTimeFormatter.ofPattern("EEE h:mm a")
                            .format(meal.timestamp.atZone(zoneId))
                    } else {
                        DateTimeFormatter.ofPattern("EEE").format(meal.timestamp.atZone(zoneId)) +
                            " · set time"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (placed) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                )
                // A bin on the row itself. The editor can delete too, but it keeps
                // that button below four steppers and a clock face, which on a
                // phone is off the bottom of the dialog -- a delete nobody can find
                // is not a delete.
                IconButton(onClick = { confirmingDelete = meal }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete meal, ${meal.macroSummary()}",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Said out loud rather than quietly applied: collapsing changes the day's
        // totals, and a figure that moved without explanation is worse than the
        // duplicate it corrected.
        if (duplicatesCollapsed > 0) {
            Text(
                "$duplicatesCollapsed repeated record" +
                    "${if (duplicatesCollapsed == 1) "" else "s"} from the source " +
                    "merged; each meal is counted once.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    editing?.let { meal ->
        MealEntryDialog(
            initial = meal.asDraft(),
            zoneId = zoneId,
            onDismiss = { editing = null },
            onConfirm = {
                onUpdate(meal, it.calories, it.proteinGrams, it.carbGrams, it.fatGrams, it.at)
                editing = null
            },
            isEdit = true,
            // Only where the time is a stamp. On a meal whose clock time was
            // genuinely recorded the chips would offer to replace a measurement
            // with a habit, which is the wrong direction for every other
            // correction in this app.
            presets = if (hasClockTime(meal)) emptyList() else mealPresets,
            now = now,
        )
    }

    // Confirmed, unlike the caffeine bin beside it. A meal carries a whole
    // absorption curve rather than one number, the bins sit in a list people
    // scroll past, and for a synced meal this is one-way: the row is kept hidden
    // precisely so the next sync cannot bring it back.
    confirmingDelete?.let { meal ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Delete this meal?") },
            text = { Text(meal.macroSummary()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(meal)
                        confirmingDelete = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = null }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (adding) {
        MealEntryDialog(
            initial =
                MealDraft(calories = 0, proteinGrams = 0, carbGrams = 0, fatGrams = 0, at = now),
            zoneId = zoneId,
            onDismiss = { adding = false },
            onConfirm = {
                onAdd(it.calories, it.proteinGrams, it.carbGrams, it.fatGrams, it.at)
                adding = false
            },
            // No presets when logging a new meal: it opens at this moment, which
            // is already the answer. The chips are a correction, not a shortcut.
            now = now,
        )
    }
}

/**
 * The blood-sugar line under a meal's macros, or null when there is nothing
 * honest to print.
 *
 * Leads with the rise rather than the area, because the rise is the figure a
 * reader can hold in their head and check against the chart above. The area is
 * what the Fuel ranking sorts on, and it is quoted there where it has other
 * areas to be compared with -- a lone "2,140 mg/dL·min" on a row means nothing
 * to anybody.
 *
 * The return clause is dropped entirely when the sensor stopped inside the
 * three-hour cap. "Still up at 3h" would be a claim about hours nobody measured,
 * which is the same error as joining a line across a gap.
 */
private fun responseSummary(
    response: MealResponse?,
    placed: Boolean,
    hasGlucose: Boolean,
): String? =
    when {
        response != null ->
            buildString {
                append("+${response.peakRiseMgDl.roundToInt()} mg/dL")
                val back = response.returnToBaseline
                when {
                    back != null -> append(" · back in ${back.compact()}")
                    response.observedFor >= MealResponses.RETURN_CAP ->
                        append(" · still up at ${MealResponses.RETURN_CAP.compact()}")
                }
            }
        // A stamped meal already carries "set time" in red on the same row, which
        // states the reason and offers the fix in one. A second line saying it is
        // unmeasured would be the same news, quieter.
        !placed -> null
        hasGlucose -> "no glucose cover"
        else -> null
    }

/** "1h 50m", or "45m" under the hour. */
private fun Duration.compact(): String {
    val minutes = toMinutes()
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
}

private val Macro.label: String
    get() =
        when (this) {
            Macro.PROTEIN -> "Protein"
            Macro.CARB -> "Carbs"
            Macro.FAT -> "Fat"
        }

private fun Duration.asPeak(): String =
    if (toMinutes() < 90) "${toMinutes()} min" else "%.1f h".format(toMinutes() / 60f)

/**
 * A stored meal as the editor's fields.
 *
 * A macro the source never recorded opens at zero, since a stepper has to start
 * somewhere -- but saving then writes that zero as a real figure, which is the
 * honest outcome: the dialog is a statement of what was eaten, and anything left
 * untouched has been confirmed as none.
 */
private fun MealEntry.asDraft() =
    MealDraft(
        calories = calories ?: 0,
        proteinGrams = proteinGrams?.toInt() ?: 0,
        carbGrams = carbGrams?.toInt() ?: 0,
        fatGrams = fatGrams?.toInt() ?: 0,
        at = timestamp,
    )

/** The macros a meal contributed, skipping the ones the logging app left out. */
private fun MealEntry.macroSummary(): String {
    val parts = buildList {
        calories?.let { add("$it kcal") }
        proteinGrams?.let { add("${it.toInt()}g protein") }
        carbGrams?.let { add("${it.toInt()}g carb") }
        fatGrams?.let { add("${it.toInt()}g fat") }
    }
    return if (parts.isEmpty()) "no macros recorded" else parts.joinToString(" · ")
}
