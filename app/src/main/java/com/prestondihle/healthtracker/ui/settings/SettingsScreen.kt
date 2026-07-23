package com.prestondihle.healthtracker.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.data.UnitSystemEnum
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.ui.components.IntStepper
import com.prestondihle.healthtracker.ui.components.Stepper

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            SettingsCard(title = "Units") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = state.settings.unitSystem == UnitSystemEnum.IMPERIAL,
                        onClick = {
                            viewModel.saveSettings(
                                state.settings.copy(unitSystem = UnitSystemEnum.IMPERIAL)
                            )
                        },
                    )
                    Text("Imperial")
                    RadioButton(
                        selected = state.settings.unitSystem == UnitSystemEnum.METRIC,
                        onClick = {
                            viewModel.saveSettings(
                                state.settings.copy(unitSystem = UnitSystemEnum.METRIC)
                            )
                        },
                    )
                    Text("Metric")
                }
                Text(
                    "Values are always stored in metric to match Health Connect; this only " +
                        "changes how they are displayed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsCard(title = "Daily goals") {
                IntStepper(
                    label = "Steps",
                    value = state.goals.dailyStepGoal ?: 10_000,
                    onValueChange = { viewModel.saveGoals(state.goals.copy(dailyStepGoal = it)) },
                    step = 500,
                    range = 0..50_000,
                )
                HorizontalDivider()
                IntStepper(
                    label = "Water",
                    value = Units.mlToFlOz(state.goals.dailyWaterMlGoal ?: 2957).toInt(),
                    onValueChange = {
                        viewModel.saveGoals(
                            state.goals.copy(dailyWaterMlGoal = Units.flOzToMl(it.toFloat()))
                        )
                    },
                    step = 4,
                    range = 0..400,
                    valueFormatter = { "$it oz" },
                )
                HorizontalDivider()
                IntStepper(
                    label = "Pushups",
                    value = state.goals.dailyPushupGoal ?: 100,
                    onValueChange = { viewModel.saveGoals(state.goals.copy(dailyPushupGoal = it)) },
                    step = 10,
                    range = 0..1_000,
                )
                HorizontalDivider()
                IntStepper(
                    label = "Air squats",
                    value = state.goals.dailySquatGoal ?: 100,
                    onValueChange = { viewModel.saveGoals(state.goals.copy(dailySquatGoal = it)) },
                    step = 10,
                    range = 0..1_000,
                )
                HorizontalDivider()
                IntStepper(
                    label = "Pages read",
                    value = state.goals.dailyPagesGoal ?: 25,
                    onValueChange = { viewModel.saveGoals(state.goals.copy(dailyPagesGoal = it)) },
                    step = 5,
                    range = 0..1_000,
                )
                HorizontalDivider()
                IntStepper(
                    label = "Protein",
                    value = state.goals.dailyProteinTarget ?: 160,
                    onValueChange = {
                        viewModel.saveGoals(state.goals.copy(dailyProteinTarget = it))
                    },
                    step = 5,
                    range = 0..500,
                    valueFormatter = { "$it g" },
                )
            }
        }

        item {
            SettingsCard(title = "Body targets") {
                Stepper(
                    label = "Goal waist",
                    value = Units.cmToInches(state.goals.goalWaistCm ?: 96.52f),
                    onValueChange = {
                        viewModel.saveGoals(state.goals.copy(goalWaistCm = Units.inchesToCm(it)))
                    },
                    step = 0.25f,
                    range = 20f..70f,
                    snap = Units::roundToQuarter,
                    valueFormatter = Units::formatInches,
                )
                HorizontalDivider()
                Stepper(
                    label = "Goal weight",
                    value = Units.kgToLbs(state.goals.goalWeightKg ?: 81.65f),
                    onValueChange = {
                        viewModel.saveGoals(state.goals.copy(goalWeightKg = Units.lbsToKg(it)))
                    },
                    step = 1f,
                    range = 80f..400f,
                    valueFormatter = { "${it.toInt()} lb" },
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
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
            content()
        }
    }
}
