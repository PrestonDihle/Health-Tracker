package com.prestondihle.healthtracker.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.data.Supplement
import com.prestondihle.healthtracker.data.mealPresets
import com.prestondihle.healthtracker.domain.FoodLogConfidence
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.ui.components.CardGap
import com.prestondihle.healthtracker.ui.components.MealListCard
import com.prestondihle.healthtracker.ui.components.TrackerCard
import com.prestondihle.healthtracker.ui.reorder.CardOrderViewModel
import com.prestondihle.healthtracker.ui.reorder.ReorderableCard
import com.prestondihle.healthtracker.ui.reorder.reorderableCards
import com.prestondihle.healthtracker.ui.wellness.BloodPressureCard
import com.prestondihle.healthtracker.ui.wellness.BodyCard
import com.prestondihle.healthtracker.ui.wellness.FoodLogConfidenceCard
import com.prestondihle.healthtracker.ui.wellness.GripStrengthCard
import com.prestondihle.healthtracker.ui.wellness.MoodCard
import com.prestondihle.healthtracker.ui.wellness.MovementCard
import com.prestondihle.healthtracker.ui.wellness.ReadingCard
import com.prestondihle.healthtracker.ui.wellness.UsualIntakeState
import com.prestondihle.healthtracker.ui.wellness.WellnessUiState
import com.prestondihle.healthtracker.ui.wellness.WellnessViewModel
import kotlinx.coroutines.launch

/**
 * The logging tab: every hand-entered measurement in one place.
 *
 * These are the controls that used to be scattered down the Wellness screen next
 * to their charts. Wellness now shows the trends; the writing is done here. All
 * of it is manual entry -- nothing on this screen reads Health Connect -- so it
 * shares [WellnessViewModel] but never touches its sync.
 */
@Composable
fun LogScreen(
    viewModel: WellnessViewModel,
    snackbarHostState: SnackbarHostState,
    orderViewModel: CardOrderViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val usual by viewModel.usual.collectAsStateWithLifecycle()
    val savedOrder by orderViewModel.savedOrder.collectAsStateWithLifecycle()
    val collapsedCards by orderViewModel.collapsed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    fun toast(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = CardGap),
        verticalArrangement = Arrangement.spacedBy(CardGap),
        contentPadding = PaddingValues(vertical = CardGap),
    ) {
        // Pinned above the reorderable cards rather than being one of them, and
        // the phone is what settled it: declared first in the list it still
        // appeared *last*, because `effectiveCardOrder` appends ids a saved order
        // has never seen to the bottom -- which is right for a card added in an
        // update and exactly wrong for this one, whose entire value is being the
        // first thing under the thumb. It is a row, not a card: a shortcut to
        // things other tabs own, like the summary strip on Today, and nothing
        // below it writes anything this row does.
        item {
            UsualCard(
                state = state,
                usual = usual,
                onWater = {
                    viewModel.logHydration(it)
                    toast("Logged ${Units.mlToWholeOz(it)} oz")
                },
                onCaffeine = {
                    viewModel.logCaffeine(it)
                    toast("Logged $it mg caffeine")
                },
                onSlot = { supplements ->
                    viewModel.takeSlot(supplements)
                    toast(
                        if (supplements.size == 1) "Took ${supplements.first().name}"
                        else "Took ${supplements.size} supplements"
                    )
                },
            )
        }

        // Declared in their out-of-the-box order; the reader's saved order, if
        // any, is reconciled against this. Meals lead by default: the most-logged
        // thing here, and the card doubles as the last day's meals to check
        // against before adding another.
        reorderableCards(
            cards =
                listOf(
                    ReorderableCard("meals") {
                        // Hoisted out of the row lambda: scoring walks the whole
                        // trace, and read per row it would be walked once per meal
                        // on every recomposition.
                        val responses = state.mealResponses
                        MealListCard(
                            meals = state.mealsInWindow,
                            undatedMeals = state.undatedMealsInWindow,
                            duplicatesCollapsed = state.duplicatesCollapsed,
                            zoneId = state.zoneId,
                            now = state.now,
                            hasClockTime = { state.hasClockTime(it) },
                            onAdd = { calories, protein, carbs, fat, at ->
                                viewModel.addMeal(calories, protein, carbs, fat, at)
                                toast("Logged meal")
                            },
                            onUpdate = { meal, calories, protein, carbs, fat, at ->
                                viewModel.updateMeal(meal, calories, protein, carbs, fat, at)
                                toast("Meal updated")
                            },
                            onDelete = {
                                viewModel.deleteMeal(it)
                                toast("Meal deleted")
                            },
                            mealPresets = state.settings.mealPresets,
                            responseFor = { responses[it.id] },
                            hasGlucose = state.glucose.isNotEmpty(),
                        )
                    },
                    // Directly under the meals, which is the card it is a
                    // judgement about: the question "how well did today get
                    // logged" is only answerable while looking at what was
                    // logged.
                    ReorderableCard("foodLogConfidence") {
                        FoodLogConfidenceCard(
                            state = state,
                            onRate = {
                                viewModel.setFoodLogConfidence(it)
                                toast(
                                    it?.let { score ->
                                        "Food logging: ${FoodLogConfidence.of(score)?.label}"
                                    } ?: "Food logging rating cleared"
                                )
                            },
                        )
                    },
                    ReorderableCard("body") {
                        BodyCard(state = state, onWaistChange = viewModel::setWaistCm)
                    },
                    ReorderableCard("grip") {
                        GripStrengthCard(
                            state = state,
                            onLog = { dominant, lbs ->
                                viewModel.logGripStrengthKg(dominant, Units.lbsToKg(lbs))
                                val hand = if (dominant) "dominant" else "non-dominant"
                                toast("Logged ${lbs.toInt()} lb $hand grip")
                            },
                        )
                    },
                    ReorderableCard("bloodPressure") {
                        BloodPressureCard(
                            state = state,
                            onSubmit = { systolic, diastolic ->
                                viewModel.addBloodPressure(systolic, diastolic)
                                toast("Logged $systolic/$diastolic")
                            },
                        )
                    },
                    ReorderableCard("mood") {
                        MoodCard(
                            state = state,
                            onSubmit = { vibe, energy, focus ->
                                viewModel.submitMood(vibe, energy, focus)
                                toast("Saved vibe $vibe, energy $energy, focus $focus")
                            },
                        )
                    },
                    ReorderableCard("reading") {
                        ReadingCard(
                            state = state,
                            onLogPages = {
                                viewModel.logPages(it)
                                toast("Logged $it pages")
                            },
                            onSetPages = viewModel::setPages,
                        )
                    },
                    ReorderableCard("movement") {
                        MovementCard(
                            state = state,
                            onLog = { movement, reps ->
                                viewModel.logReps(movement, reps)
                                val name =
                                    if (movement == MovementType.PUSHUP) "pushups" else "air squats"
                                toast("Logged $reps $name")
                            },
                        )
                    },
                ),
            savedOrder = savedOrder,
            onMove = orderViewModel::move,
            collapsed = collapsedCards,
            onToggleCollapse = orderViewModel::toggleCollapse,
        )

        item { Spacer(Modifier.height(8.dp)) }
    }
}

/**
 * One tap for each of the things done most often, or nothing where there is no
 * habit to repeat.
 *
 * Every chip here is derived from rows already on disk -- no favourite is stored,
 * so there is nothing to set up and nothing to go stale. A chip whose suggestion
 * cannot be read simply does not appear, and when none of them can the card says
 * so in a sentence rather than showing an empty row. That matters on a first run:
 * a strip of dead buttons is a feature that looks broken, where a sentence is a
 * feature that has not started yet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsualCard(
    state: WellnessUiState,
    usual: UsualIntakeState,
    onWater: (Int) -> Unit,
    onCaffeine: (Int) -> Unit,
    onSlot: (List<Supplement>) -> Unit,
) {
    val outstanding = state.outstandingInSlot
    TrackerCard(title = "Usual", subtitle = "one tap for what you log most") {
        if (usual.usualWaterMl == null && usual.lastCaffeineMg == null && outstanding.isEmpty()) {
            Text(
                // Three separate reasons collapse into one sentence deliberately:
                // the reader does not need to be told which of them applies, only
                // that the row fills itself in.
                "Nothing to repeat yet. Log a drink, a coffee or a supplement and " +
                    "the shortcuts appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@TrackerCard
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            usual.usualWaterMl?.let { ml ->
                AssistChip(
                    onClick = { onWater(ml) },
                    label = { Text("${Units.mlToWholeOz(ml)} oz water") },
                )
            }
            usual.lastCaffeineMg?.let { mg ->
                AssistChip(onClick = { onCaffeine(mg) }, label = { Text("$mg mg caffeine") })
            }
            if (outstanding.isNotEmpty()) {
                AssistChip(
                    onClick = { onSlot(outstanding) },
                    // Names the slot and the count, because this is the one chip
                    // that writes several rows at once and the reader should know
                    // how many before tapping rather than after.
                    label = {
                        Text(
                            "${state.currentSupplementSlot.label} stack " +
                                "(${outstanding.size})"
                        )
                    },
                )
            }
        }
    }
}
