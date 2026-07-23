package com.prestondihle.healthtracker.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.ui.components.IntStepper
import com.prestondihle.healthtracker.ui.components.LabeledSlider
import com.prestondihle.healthtracker.ui.components.ScaleDescriptors
import com.prestondihle.healthtracker.ui.components.Stepper
import java.time.format.DateTimeFormatter

private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMM yyyy")

@Composable
fun HistoryScreen(viewModel: HistoryViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.selectDate(state.date.minusDays(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous day")
                }
                Text(
                    DATE_FORMAT.format(state.date),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = { viewModel.selectDate(state.date.plusDays(1)) },
                    enabled = !state.isToday,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next day")
                }
            }
        }

        item {
            HistoryCard(
                title = "From Health Connect",
                action = {
                    IconButton(onClick = viewModel::syncHealthForDate) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Re-sync this day")
                    }
                },
            ) {
                val snapshot = state.snapshot
                if (snapshot == null) {
                    Text(
                        "Nothing synced for this day yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ReadOnlyRow("Steps", snapshot.steps?.toString())
                    ReadOnlyRow("Resting heart rate", snapshot.restingHeartRateBpm?.let { "$it bpm" })
                    ReadOnlyRow("Sleep", snapshot.sleepMinutes?.let { Units.formatMinutes(it) })
                    ReadOnlyRow("Calories", snapshot.totalCalories?.toString())
                    ReadOnlyRow("Protein", snapshot.proteinGrams?.let { "${it.toInt()} g" })
                    ReadOnlyRow("Carbs", snapshot.carbGrams?.let { "${it.toInt()} g" })
                    ReadOnlyRow("Fat", snapshot.fatGrams?.let { "${it.toInt()} g" })
                    ReadOnlyRow("Best mile", snapshot.bestMileSeconds?.let { Units.formatPace(it) })
                }
            }
        }

        item {
            HistoryCard(title = "Logged that day") {
                ReadOnlyRow("Hydration", "${Units.mlToWholeOz(state.hydrationMl)} oz")
                ReadOnlyRow("Pushups", state.reps(MovementType.PUSHUP).toString())
                ReadOnlyRow("Air squats", state.reps(MovementType.AIR_SQUAT).toString())
                ReadOnlyRow(
                    "Blood pressure",
                    state.bloodPressures.lastOrNull()?.let { "${it.systolic}/${it.diastolic}" },
                )
                ReadOnlyRow("Glucose readings", state.glucose.size.toString())
                ReadOnlyRow("Ketone readings", state.ketones.size.toString())
            }
        }

        item {
            HistoryCard(title = "Correct this day") {
                var vibe by remember(state.log.vibe) { mutableIntStateOf(state.log.vibe ?: 5) }
                var energy by remember(state.log.energy) { mutableIntStateOf(state.log.energy ?: 5) }
                var focus by remember(state.log.focus) { mutableIntStateOf(state.log.focus ?: 5) }

                LabeledSlider("Vibe", vibe, {
                    vibe = it
                    viewModel.setMood(it, energy, focus)
                }, ScaleDescriptors.Vibe)
                LabeledSlider("Energy", energy, {
                    energy = it
                    viewModel.setMood(vibe, it, focus)
                }, ScaleDescriptors.Energy)
                LabeledSlider("Focus", focus, {
                    focus = it
                    viewModel.setMood(vibe, energy, it)
                }, ScaleDescriptors.Focus)

                HorizontalDivider()

                IntStepper(
                    label = "Pages read",
                    value = state.log.bookPagesRead ?: 0,
                    onValueChange = viewModel::setPages,
                    range = 0..2_000,
                )

                Stepper(
                    label = "Waist",
                    value = Units.cmToInches(state.waistCm),
                    onValueChange = { viewModel.setWaistCm(Units.inchesToCm(it)) },
                    step = 0.25f,
                    range = 20f..70f,
                    snap = Units::roundToQuarter,
                    valueFormatter = Units::formatInches,
                )
            }
        }
    }
}

@Composable
private fun ReadOnlyRow(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value ?: "--",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color =
                if (value == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun HistoryCard(
    title: String,
    action: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                action?.invoke()
            }
            content()
        }
    }
}
