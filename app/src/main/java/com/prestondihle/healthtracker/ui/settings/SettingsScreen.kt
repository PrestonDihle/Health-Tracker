package com.prestondihle.healthtracker.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.data.AftLane
import com.prestondihle.healthtracker.data.Sex
import com.prestondihle.healthtracker.data.ThemeMode
import com.prestondihle.healthtracker.data.UnitSystemEnum
import kotlin.math.roundToInt
import com.prestondihle.healthtracker.data.WeightSubGoal
import com.prestondihle.healthtracker.domain.AftEvent
import com.prestondihle.healthtracker.domain.AftScoring
import com.prestondihle.healthtracker.domain.Glucose
import com.prestondihle.healthtracker.domain.HeartRate
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.ui.components.AbsorptionModelCard
import com.prestondihle.healthtracker.ui.components.CompactTimeField
import com.prestondihle.healthtracker.ui.components.IntStepper
import com.prestondihle.healthtracker.ui.components.Stepper
import com.prestondihle.healthtracker.ui.reorder.CardOrderViewModel
import com.prestondihle.healthtracker.ui.reorder.ReorderableCard
import com.prestondihle.healthtracker.ui.reorder.reorderableCards
import com.prestondihle.healthtracker.work.CaffeineLastCallWorker
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** The clock face on the meal-time chips, matching the one the meal list prints. */
private val MEAL_PRESET_FORMAT = DateTimeFormatter.ofPattern("h:mm a")

/**
 * Where the bedtime caffeine limit starts once it is switched on.
 *
 * Roughly a third of a cup still circulating -- low enough to be a real
 * constraint on an afternoon coffee, high enough not to fire on a single
 * morning one. It is a starting point and not a recommendation; the stepper
 * beside it is the actual answer.
 */
private const val DEFAULT_BEDTIME_LIMIT_MG = 25

/**
 * Where the heart-rate rule opens once it is switched on.
 *
 * A hundred is the top of the resting range most references quote, so the
 * stepper opens on a figure that means something rather than on the floor of
 * its own range -- the AFT steppers' argument, which open on the 60-point
 * requirement rather than on zero.
 */
private const val DEFAULT_HEART_RATE_RULE_BPM = 100

/**
 * Where the plank goal opens when the profile cannot supply an AFT row.
 *
 * Two minutes: comfortably past the 60-point requirement on every published age
 * band, so it reads as a target rather than as a pass mark, and a round number
 * to move away from rather than a precise-looking one to trust.
 */
private const val DEFAULT_PLANK_GOAL_SECONDS = 120

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, orderViewModel: CardOrderViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val savedOrder by orderViewModel.savedOrder.collectAsStateWithLifecycle()
    val collapsedCards by orderViewModel.collapsed.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        reorderableCards(
            cards =
                listOf(
                    ReorderableCard("profile") {
                        SettingsCard(title = "You") {
                Text(
                    "Max heart rate zones the runs on the Activity tab. The rest are here for " +
                        "the figures that read off them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val settings = state.settings

                // Every figure here shows a sensible number before one is stored, which
                // makes an unset profile look like a filled one -- and the AFT card
                // sends the reader to this screen precisely because something is
                // missing. Saying so under the value is the difference between a
                // default and a decision.
                val unset = "not set yet -- nudge to save"

                IntStepper(
                    label = "Max heart rate",
                    value = settings.maxHeartRateBpm ?: 190,
                    onValueChange = {
                        viewModel.saveSettings(settings.copy(maxHeartRateBpm = it))
                    },
                    range = 100..230,
                    valueFormatter = { "$it bpm" },
                    supportingText =
                        when {
                            settings.maxHeartRateBpm == null -> unset
                            settings.ageYears != null ->
                                "220 − age ≈ ${220 - settings.ageYears} bpm"
                            else -> "what your runs are zoned against"
                        },
                )
                HorizontalDivider()

                IntStepper(
                    label = "Age",
                    value = settings.ageYears ?: 30,
                    onValueChange = { viewModel.saveSettings(settings.copy(ageYears = it)) },
                    range = 10..120,
                    valueFormatter = { "$it yr" },
                    // The Army Fitness Test cannot pick an age band without this, so
                    // an unset age is the one that actually stops something working.
                    supportingText = if (settings.ageYears == null) unset else "sets your AFT age band",
                )
                HorizontalDivider()

                // Stored in cm to match Health Connect; stepped in whole inches or
                // centimetres depending on the unit setting above.
                if (settings.unitSystem == UnitSystemEnum.IMPERIAL) {
                    IntStepper(
                        label = "Height",
                        value = settings.heightCm?.let { Units.cmToInches(it).roundToInt() } ?: 68,
                        onValueChange = {
                            viewModel.saveSettings(
                                settings.copy(heightCm = Units.inchesToCm(it.toFloat()))
                            )
                        },
                        range = 36..96,
                        valueFormatter = { "${it / 12}' ${it % 12}\"" },
                        supportingText = if (settings.heightCm == null) unset else null,
                    )
                } else {
                    IntStepper(
                        label = "Height",
                        value = settings.heightCm?.roundToInt() ?: 173,
                        onValueChange = {
                            viewModel.saveSettings(settings.copy(heightCm = it.toFloat()))
                        },
                        range = 90..250,
                        valueFormatter = { "$it cm" },
                        supportingText = if (settings.heightCm == null) unset else null,
                    )
                }
                HorizontalDivider()

                Text("Sex", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                            Sex.MALE to "Male",
                            Sex.FEMALE to "Female",
                            Sex.UNSPECIFIED to "Prefer not to say",
                        )
                        .forEach { (option, label) ->
                            FilterChip(
                                selected = settings.sex == option,
                                onClick = { viewModel.saveSettings(settings.copy(sex = option)) },
                                label = { Text(label) },
                            )
                        }
                }

                HorizontalDivider()

                Text("Fitness test standard", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Combat specialties are scored sex-neutral against the male column " +
                        "and need 350 rather than 300 overall. Past tests re-score the " +
                        "moment this changes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AftLane.entries.forEach { option ->
                        FilterChip(
                            selected = settings.aftLane == option,
                            onClick = { viewModel.saveSettings(settings.copy(aftLane = option)) },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
                    },
                    ReorderableCard("units") {
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
                    },
                    ReorderableCard("theme") {
                        SettingsCard(title = "Theme") {
                            // Chips rather than a switch, because the choice is
                            // genuinely three-way. A switch would have to mean
                            // "override the phone: yes/no" or "dark: yes/no", and
                            // the second of those makes "follow the phone"
                            // unreachable after the first tap.
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ThemeMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = state.settings.themeMode == mode,
                                        onClick = {
                                            viewModel.saveSettings(
                                                state.settings.copy(themeMode = mode)
                                            )
                                        },
                                        label = { Text(mode.label) },
                                    )
                                }
                            }
                            Text(
                                "System follows your phone, which is where this started and " +
                                    "is still the default. The override is here because the " +
                                    "charts are: every line has a colour picked separately for " +
                                    "each scheme, and reading a plot in the one it looks best " +
                                    "in is worth a tap. The home-screen widget is not covered " +
                                    "— it sits on the launcher and follows the phone with " +
                                    "every other widget beside it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    ReorderableCard("stepSource") {
                        SettingsCard(title = "Step source") {
                Text(
                    "Several apps can write steps to Health Connect at once. Adding them up " +
                        "counts the same walk twice; trusting only one loses whatever that " +
                        "one did not see — a watch records no steps at all for a tracked " +
                        "run, and the phone in your pocket does.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val preferred = state.settings.preferredStepsPackage

                StepSourceRow(
                    label = "Merged (recommended)",
                    supporting =
                        listOfNotNull(
                                state.mergedSteps?.let { "$it steps today" },
                                "highest of your sources for each quarter-hour — " +
                                    "avoids double counting",
                            )
                            .joinToString(" · "),
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
                    },
                    ReorderableCard("dailyGoals") {
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
                // A hold rather than a daily total, unlike every other goal in
                // this card, and the label says so: three one-minute planks are
                // not a three-minute plank, and the chart plots the day's longest.
                //
                // Off until it is set. The figure worth aiming at is the reader's
                // own AFT row, which needs an age and a sex this app may not have
                // been told -- so it prints theirs where it can rather than
                // seeding a guess, which is the same trade the heart-rate rule
                // makes.
                run {
                    val plankGoal = state.goals.plankHoldSecondsGoal
                    val settings = state.settings
                    val pass =
                        AftScoring.minimumFor(
                            AftEvent.PLANK,
                            settings.ageYears,
                            settings.sex,
                            settings.aftLane,
                        )
                    IntStepper(
                        label = "Plank hold",
                        // Opens on the reader's own pass mark where it is known,
                        // which is AftCard's argument for its steppers: the 60-
                        // point row is the figure being aimed at, and zero is a
                        // long way from anywhere useful on a timed event.
                        value = plankGoal ?: pass ?: DEFAULT_PLANK_GOAL_SECONDS,
                        onValueChange = {
                            viewModel.saveGoals(state.goals.copy(plankHoldSecondsGoal = it))
                        },
                        step = 5,
                        range = 0..600,
                        supportingText =
                            when {
                                plankGoal == null -> "off -- nudge to set a target"
                                pass != null -> "your AFT 60-point row is ${Units.formatHold(pass)}"
                                else -> "longest hold per day, on the Activity chart"
                            },
                        valueFormatter = { Units.formatHold(it) },
                    )
                    if (plankGoal != null) {
                        TextButton(
                            onClick = {
                                viewModel.saveGoals(
                                    state.goals.copy(plankHoldSecondsGoal = null)
                                )
                            }
                        ) {
                            Text("Remove the plank goal")
                        }
                    }
                }
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
                    },
                    ReorderableCard("glucoseTarget") {
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
                    },
                    ReorderableCard("glucoseChart") {
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
                    },
                    ReorderableCard("heartRateChart") {
                        SettingsCard(title = "Heart rate chart") {
                            Text(
                                "The heart-rate axis on the Today graph, in the same shape as " +
                                    "the blood sugar one above. Neither figure clips anything " +
                                    "— a reading outside them still widens the axis to fit.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            val hrMin = state.goals.heartRatePlotMinBpm ?: HeartRate.PLOT_MIN.toInt()
                            val hrMax = state.goals.heartRatePlotMaxBpm ?: HeartRate.PLOT_MAX.toInt()

                            IntStepper(
                                label = "Chart floor",
                                value = hrMin,
                                // Held apart as they move, the glucose bounds'
                                // rule: an axis whose floor has passed its
                                // ceiling draws every reading at one height or
                                // upside down, and the stepper is the only place
                                // that can be prevented cheaply.
                                onValueChange = {
                                    viewModel.saveGoals(
                                        state.goals.copy(
                                            heartRatePlotMinBpm =
                                                it.coerceAtMost(hrMax - HeartRate.MIN_PLOT_SPAN),
                                            heartRatePlotMaxBpm = hrMax,
                                        )
                                    )
                                },
                                step = 5,
                                range = HeartRate.ENTRY_RANGE,
                                valueFormatter = { "$it ${HeartRate.UNIT}" },
                            )
                            HorizontalDivider()
                            IntStepper(
                                label = "Chart ceiling",
                                value = hrMax,
                                onValueChange = {
                                    viewModel.saveGoals(
                                        state.goals.copy(
                                            heartRatePlotMinBpm = hrMin,
                                            heartRatePlotMaxBpm =
                                                it.coerceAtLeast(hrMin + HeartRate.MIN_PLOT_SPAN),
                                        )
                                    )
                                },
                                step = 5,
                                range = HeartRate.ENTRY_RANGE,
                                valueFormatter = { "$it ${HeartRate.UNIT}" },
                            )
                            HorizontalDivider()

                            // Off until it is set, unlike the blood sugar rule
                            // beside it. Nothing was ever drawn on this axis, so
                            // there is no line for an unset value to remove --
                            // and a seeded one would have appeared on the chart
                            // of every reader who never asked for it.
                            val hrRule = state.goals.heartRateReferenceBpm
                            IntStepper(
                                label = "Reference line",
                                value = hrRule ?: DEFAULT_HEART_RATE_RULE_BPM,
                                onValueChange = {
                                    viewModel.saveGoals(
                                        state.goals.copy(heartRateReferenceBpm = it)
                                    )
                                },
                                step = 5,
                                range = HeartRate.ENTRY_RANGE,
                                supportingText =
                                    if (hrRule == null) "off — nudge to draw it"
                                    // Their own zone boundaries, where the
                                    // profile knows them, because "where should
                                    // this line go" is the question the stepper
                                    // cannot answer on its own.
                                    else
                                        state.settings.maxHeartRateBpm?.let { max ->
                                            "easy below ${(max * 0.6f).roundToInt()}, " +
                                                "hard above ${(max * 0.75f).roundToInt()}"
                                        } ?: "solid rule across the Today graph",
                                valueFormatter = { "$it ${HeartRate.UNIT}" },
                            )
                            if (hrRule != null) {
                                TextButton(
                                    onClick = {
                                        viewModel.saveGoals(
                                            state.goals.copy(heartRateReferenceBpm = null)
                                        )
                                    }
                                ) {
                                    Text("Remove the line")
                                }
                            }
                        }
                    },
                    ReorderableCard("bloodPressureReference") {
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
                    },
                    ReorderableCard("bodyTargets") {
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
                    },
                    ReorderableCard("weightWaypoints") {
                        WeightSubGoalsCard(
                subGoals = state.weightSubGoals,
                suggestedLbs = state.suggestedWaypointLbs,
                onAdd = viewModel::addWeightSubGoalLbs,
                onDelete = viewModel::deleteWeightSubGoal,
            )
                    },
                    ReorderableCard("caffeineLastCall") {
                        val context = LocalContext.current
                        SettingsCard(title = "Caffeine last call") {
                Text(
                    "Warns when one more cup would leave you over this much " +
                        "caffeine at 9 PM. The warning is about the next dose " +
                        "rather than the one already drunk, because that is the " +
                        "only one still worth a decision.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val limit = state.goals.caffeineBedtimeLimitMg
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = limit != null,
                        onCheckedChange = { on ->
                            viewModel.saveGoals(
                                state.goals.copy(
                                    // Off is null rather than zero: zero is a
                                    // limit nothing can satisfy, and would warn
                                    // every hour forever.
                                    caffeineBedtimeLimitMg = if (on) DEFAULT_BEDTIME_LIMIT_MG else null
                                )
                            )
                            // Answer straight away rather than at the top of the
                            // next hour. See CaffeineLastCallWorker.checkNow.
                            if (on) CaffeineLastCallWorker.checkNow(context)
                        },
                    )
                    Text("Warn me", style = MaterialTheme.typography.bodyMedium)
                }
                if (limit != null) {
                    IntStepper(
                        label = "At 9 PM, keep under",
                        value = limit,
                        onValueChange = {
                            viewModel.saveGoals(state.goals.copy(caffeineBedtimeLimitMg = it))
                            CaffeineLastCallWorker.checkNow(context)
                        },
                        step = 5,
                        range = 5..300,
                        valueFormatter = { "$it mg" },
                    )
                }
            }
                    },
                    ReorderableCard("mealTimes") {
                        MealPresetCard(
                            breakfast = state.settings.mealPresetBreakfast,
                            lunch = state.settings.mealPresetLunch,
                            dinner = state.settings.mealPresetDinner,
                            onChange = { breakfast, lunch, dinner ->
                                viewModel.saveSettings(
                                    state.settings.copy(
                                        mealPresetBreakfast = breakfast,
                                        mealPresetLunch = lunch,
                                        mealPresetDinner = dinner,
                                    )
                                )
                            },
                        )
                    },
                    ReorderableCard("backup") {
                        BackupCard(
                            isExporting = state.isExporting,
                            onExport = viewModel::exportBackup,
                        )
                    },
                    // Reference for the food-absorption curves on the Today chart.
                    // It explains a model rather than setting anything, so it sits
                    // at the foot of Settings by default.
                    ReorderableCard("foodCurves") { AbsorptionModelCard() },
                ),
            savedOrder = savedOrder,
            onMove = orderViewModel::move,
            collapsed = collapsedCards,
            onToggleCollapse = orderViewModel::toggleCollapse,
        )
    }
}

/**
 * The three times offered as one-tap chips when a stamped meal is corrected.
 *
 * Here rather than buried in the meal dialog because the point of the presets is
 * that they are already right: a reader who has to fix them at the moment they
 * are using them has been given four taps to save two. Setting them is a rare
 * act, correcting a stamped meal is a daily one, and the two belong on different
 * screens for that reason.
 *
 * Labelled breakfast/lunch/dinner although nothing downstream reads the names --
 * the chips on the meal dialog show times alone. The labels are here so the
 * three fields can be told apart while they are being set, which is the only
 * place the distinction does any work.
 */
@Composable
private fun MealPresetCard(
    breakfast: LocalTime,
    lunch: LocalTime,
    dinner: LocalTime,
    onChange: (LocalTime, LocalTime, LocalTime) -> Unit,
) {
    // Which of the three is being edited, or null while none is.
    var editing by remember { mutableStateOf<MealSlot?>(null) }

    SettingsCard(title = "Meal times") {
        Text(
            "A nutrition source that records only the date stamps every meal at one time of " +
                "day. These three are offered as one-tap fixes on such a meal, so set them to " +
                "when you actually eat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MealSlot.entries.forEach { slot ->
            val time = slot.from(breakfast, lunch, dinner)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(slot.label, style = MaterialTheme.typography.bodyMedium)
                AssistChip(
                    onClick = { editing = slot },
                    label = {
                        Text(
                            MEAL_PRESET_FORMAT.format(time),
                            maxLines = 1,
                            // "12:00 PM" otherwise breaks after the minutes and
                            // renders as two stacked lines in a narrow chip.
                            softWrap = false,
                        )
                    },
                )
            }
        }
    }

    editing?.let { slot ->
        MealPresetDialog(
            slot = slot,
            initial = slot.from(breakfast, lunch, dinner),
            onDismiss = { editing = null },
            onConfirm = { picked ->
                onChange(
                    if (slot == MealSlot.BREAKFAST) picked else breakfast,
                    if (slot == MealSlot.LUNCH) picked else lunch,
                    if (slot == MealSlot.DINNER) picked else dinner,
                )
                editing = null
            },
        )
    }
}

/** Which of the three preset fields a row edits. */
private enum class MealSlot(val label: String) {
    BREAKFAST("Breakfast"),
    LUNCH("Lunch"),
    DINNER("Dinner");

    fun from(breakfast: LocalTime, lunch: LocalTime, dinner: LocalTime): LocalTime =
        when (this) {
            BREAKFAST -> breakfast
            LUNCH -> lunch
            DINNER -> dinner
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealPresetDialog(
    slot: MealSlot,
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val pickerState =
        rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(slot.label) },
        text = { CompactTimeField(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(pickerState.hour, pickerState.minute)) }) {
                Text("Set")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/**
 * Getting the data off the phone.
 *
 * Everything this app knows lives in one SQLite file in one app's private
 * storage: an uninstall, a lost handset or a corrupted page takes fasting
 * history, hand-typed weights and waists, blood sugar and the supplement stack
 * with it, and none of that exists anywhere else. The share sheet is the whole
 * feature -- where the file goes afterwards is the reader's business, and the
 * app deliberately has no opinion and no network permission to have one with.
 *
 * A zip of CSVs rather than a copy of the database, because the point is that it
 * can be opened by something that is not this app, on a day this app may no
 * longer install.
 */
@Composable
private fun BackupCard(isExporting: Boolean, onExport: (File, (Throwable?) -> Unit) -> Unit) {
    val context = LocalContext.current

    SettingsCard(title = "Backup") {
        Text(
            "Writes every table to a zip of CSV files and hands it to the share " +
                "sheet. Nothing leaves the phone unless you send it somewhere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TextButton(
            enabled = !isExporting,
            onClick = {
                // Named for the day it was taken, so a folder of them reads as a
                // history rather than as one file repeatedly overwritten.
                val stamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                val destination = File(context.cacheDir, "exports/health-tracker-$stamp.zip")
                onExport(destination) { failure ->
                    if (failure != null) {
                        Toast.makeText(
                                context,
                                "Export failed: ${failure.message ?: "unknown error"}",
                                Toast.LENGTH_LONG,
                            )
                            .show()
                        return@onExport
                    }
                    val uri =
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            destination,
                        )
                    val share =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, destination.name)
                            // Read access to this one file for this one share,
                            // granted to whichever app the sheet lands on.
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    context.startActivity(Intent.createChooser(share, "Share backup"))
                }
            },
        ) {
            Text(if (isExporting) "Exporting…" else "Export a backup")
        }
    }
}

/**
 * Staged weights on the way to the goal.
 *
 * A list rather than a fixed set of steppers, because there is no right number
 * of them: thirty pounds to lose may want one every five, or a single halfway
 * mark, and the app is in no position to choose.
 *
 * Where the add stepper opens is [SettingsUiState.suggestedWaypointLbs] -- the
 * current weight before anything is staged, the midpoint to the goal after --
 * which saves dialling the whole way from wherever the control would otherwise
 * have started.
 */
@Composable
private fun WeightSubGoalsCard(
    subGoals: List<WeightSubGoal>,
    suggestedLbs: Float,
    onAdd: (Float) -> Unit,
    onDelete: (WeightSubGoal) -> Unit,
) {
    // Keyed on the suggestion, so staging a mark re-seeds the stepper for the
    // next one instead of leaving it on the value just added.
    var pending by remember(suggestedLbs) { mutableFloatStateOf(suggestedLbs) }

    SettingsCard(title = "Weight waypoints") {
        Text(
            "Marks on the way to the goal weight, drawn as faint rules across the Weight " +
                "chart on Trends. Lighter than the goal itself, because they are steps " +
                "towards it rather than the point of it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        subGoals.forEach { subGoal ->
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${Units.kgToLbs(subGoal.kg).toInt()} lb",
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = { onDelete(subGoal) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription =
                            "Remove ${Units.kgToLbs(subGoal.kg).toInt()} lb waypoint",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        HorizontalDivider()
        Stepper(
            label = "Add a waypoint",
            value = pending,
            onValueChange = { pending = it },
            step = 1f,
            range = WaypointRangeLbs,
            valueFormatter = { "${it.toInt()} lb" },
        )
        // Deliberately a separate action rather than saving as the stepper
        // moves: every tap on the way from 180 to 195 would otherwise stage a
        // mark, and the reader would be deleting fourteen of them.
        TextButton(onClick = { onAdd(pending) }) { Text("Add ${pending.toInt()} lb") }
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
