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
                Text(
                    "Each of these is drawn as a dashed rule across its chart on the Trends " +
                        "screen, so a day can be read against what you were aiming at rather " +
                        "than only against the days either side of it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IntStepper(
                    label = "Steps",
                    value = state.goals.dailyStepGoal ?: 10_000,
                    onValueChange = { viewModel.saveGoals(state.goals.copy(dailyStepGoal = it)) },
                    step = 500,
                    range = 0..50_000,
                )
                HorizontalDivider()
                // The stacked macro bars total the day's calories, so this rule
                // is read against the top of the stack rather than any one band.
                IntStepper(
                    label = "Calories",
                    value = state.goals.dailyCalorieTarget ?: 2_200,
                    onValueChange = {
                        viewModel.saveGoals(state.goals.copy(dailyCalorieTarget = it))
                    },
                    step = 50,
                    range = 0..8_000,
                    supportingText = "rule across the Macros chart",
                    valueFormatter = { "$it kcal" },
                )
                HorizontalDivider()
                // Stored and stepped in minutes, shown as hours and minutes: a
                // whole-hour stepper cannot say seven and a half, and a decimal
                // one asks the reader to convert 7.5 back into a bedtime.
                IntStepper(
                    label = "Sleep",
                    value = state.goals.sleepMinutesGoal ?: 480,
                    onValueChange = {
                        viewModel.saveGoals(state.goals.copy(sleepMinutesGoal = it))
                    },
                    step = 15,
                    range = 0..(16 * 60),
                    valueFormatter = { "${it / 60}h ${(it % 60).toString().padStart(2, '0')}m" },
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
                    supportingText = "solid rule across the Today and Master charts",
                    valueFormatter = { "$it ${Glucose.UNIT}" },
                )
            }
        }

        item {
            SettingsCard(title = "Blood sugar chart") {
                Text(
                    "How much of the plot the ordinary range gets. A trace that lives between " +
                        "80 and 120 is a flat line on a wide axis and a legible swing on a " +
                        "narrow one. Neither figure clips anything — a reading outside them " +
                        "still widens the axis to fit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val plotMin = state.goals.glucosePlotMinMgDl ?: Glucose.PLOT_MIN.toInt()
                val plotMax = state.goals.glucosePlotMaxMgDl ?: Glucose.PLOT_MAX.toInt()

                IntStepper(
                    label = "Chart floor",
                    value = plotMin,
                    // Held apart as they move, exactly as the target band's two
                    // edges are: an axis whose floor has passed its ceiling draws
                    // every reading at the same height or upside down, and the
                    // stepper is the only place that can be prevented cheaply.
                    onValueChange = {
                        viewModel.saveGoals(
                            state.goals.copy(
                                glucosePlotMinMgDl =
                                    it.coerceAtMost(plotMax - Glucose.MIN_PLOT_SPAN),
                                glucosePlotMaxMgDl = plotMax,
                            )
                        )
                    },
                    step = 5,
                    range = Glucose.ENTRY_RANGE,
                    valueFormatter = { "$it ${Glucose.UNIT}" },
                )
                HorizontalDivider()
                IntStepper(
                    label = "Chart ceiling",
                    value = plotMax,
                    onValueChange = {
                        viewModel.saveGoals(
                            state.goals.copy(
                                glucosePlotMinMgDl = plotMin,
                                glucosePlotMaxMgDl =
                                    it.coerceAtLeast(plotMin + Glucose.MIN_PLOT_SPAN),
                            )
                        )
                    },
                    step = 5,
                    range = Glucose.ENTRY_RANGE,
                    valueFormatter = { "$it ${Glucose.UNIT}" },
                )
            }
        }

        item {
            SettingsCard(title = "Blood pressure reference") {
                Text(
                    "Two dashed rules across the blood pressure chart on Trends, one per line. " +
                        "Seeded at the published 120/80 — change them if you have been given " +
                        "different numbers to aim at.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                IntStepper(
                    label = "Systolic",
                    value = state.goals.bloodPressureSystolicReference ?: 120,
                    onValueChange = {
                        viewModel.saveGoals(
                            state.goals.copy(bloodPressureSystolicReference = it)
                        )
                    },
                    step = 5,
                    range = 70..200,
                    valueFormatter = { "$it mmHg" },
                )
                HorizontalDivider()
                IntStepper(
                    label = "Diastolic",
                    value = state.goals.bloodPressureDiastolicReference ?: 80,
                    onValueChange = {
                        viewModel.saveGoals(
                            state.goals.copy(bloodPressureDiastolicReference = it)
                        )
                    },
                    step = 5,
                    range = 40..140,
                    valueFormatter = { "$it mmHg" },
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
