package com.prestondihle.healthtracker.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.data.UnitSystemEnum
import com.prestondihle.healthtracker.domain.Glucose
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
            SettingsCard(title = "Step source") {
                Text(
                    "Several apps can write steps to Health Connect at once, and their totals " +
                        "get summed — which counts the same walk twice. Pick the one to trust.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val preferred = state.settings.preferredStepsPackage

                StepSourceRow(
                    label = "Sum every source",
                    supporting =
                        if (state.stepSources.size > 1) {
                            "${state.stepSources.sumOf { it.steps }} steps today, combined"
                        } else null,
                    selected = preferred == null,
                    onClick = { viewModel.setPreferredStepsPackage(null) },
                )

                state.stepSources.forEach { source ->
                    StepSourceRow(
                        label = source.appLabel,
                        supporting = "${source.steps} steps today",
                        selected = preferred == source.packageName,
                        onClick = { viewModel.setPreferredStepsPackage(source.packageName) },
                    )
                }

                if (state.stepSources.isEmpty()) {
                    Text(
                        if (state.isLoadingStepSources) "Checking…"
                        else "No step data in Health Connect today.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // A source that has not written anything yet today is invisible
                // here, so this needs to be re-runnable rather than load-once.
                TextButton(onClick = viewModel::refreshStepSources) { Text("Refresh sources") }
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
                    value = Units.mlToWholeOz(state.goals.dailyWaterMlGoal ?: 2957),
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
            SettingsCard(title = "Blood sugar target") {
                Text(
                    "Shaded as a grey band behind the glucose line on the Today and Master " +
                        "screens. A starting range rather than a clinical one — set it to " +
                        "whatever you are actually aiming at.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val low = state.goals.glucoseTargetLowMgDl ?: Glucose.DEFAULT_TARGET_LOW
                val high = state.goals.glucoseTargetHighMgDl ?: Glucose.DEFAULT_TARGET_HIGH

                IntStepper(
                    label = "Low",
                    value = low,
                    // Kept below the high end as it moves, so the pair can never
                    // be dialled into a range that inverts and draws nothing.
                    onValueChange = {
                        viewModel.saveGoals(
                            state.goals.copy(
                                glucoseTargetLowMgDl = it.coerceAtMost(high - 1),
                                glucoseTargetHighMgDl = high,
                            )
                        )
                    },
                    step = 5,
                    range = Glucose.ENTRY_RANGE,
                    valueFormatter = { "$it ${Glucose.UNIT}" },
                )
                HorizontalDivider()
                IntStepper(
                    label = "High",
                    value = high,
                    onValueChange = {
                        viewModel.saveGoals(
                            state.goals.copy(
                                glucoseTargetLowMgDl = low,
                                glucoseTargetHighMgDl = it.coerceAtLeast(low + 1),
                            )
                        )
                    },
                    step = 5,
                    range = Glucose.ENTRY_RANGE,
                    valueFormatter = { "$it ${Glucose.UNIT}" },
                )
                HorizontalDivider()
                IntStepper(
                    label = "Reference line",
                    value = state.goals.glucoseReferenceMgDl ?: Glucose.DEFAULT_REFERENCE,
                    onValueChange = {
                        viewModel.saveGoals(state.goals.copy(glucoseReferenceMgDl = it))
                    },
                    step = 5,
                    range = Glucose.ENTRY_RANGE,
                    supportingText = "solid rule across the Today chart",
                    valueFormatter = { "$it ${Glucose.UNIT}" },
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
private fun StepSourceRow(
    label: String,
    supporting: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
