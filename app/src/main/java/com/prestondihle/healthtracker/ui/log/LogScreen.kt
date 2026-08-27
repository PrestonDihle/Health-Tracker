package com.prestondihle.healthtracker.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.data.mealPresets
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.ui.components.CardGap
import com.prestondihle.healthtracker.ui.components.MealListCard
import com.prestondihle.healthtracker.ui.reorder.CardOrderViewModel
import com.prestondihle.healthtracker.ui.reorder.ReorderableCard
import com.prestondihle.healthtracker.ui.reorder.reorderableCards
import com.prestondihle.healthtracker.ui.wellness.BloodPressureCard
import com.prestondihle.healthtracker.ui.wellness.BodyCard
import com.prestondihle.healthtracker.ui.wellness.GripStrengthCard
import com.prestondihle.healthtracker.ui.wellness.MoodCard
import com.prestondihle.healthtracker.ui.wellness.MovementCard
import com.prestondihle.healthtracker.ui.wellness.ReadingCard
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
    val savedOrder by orderViewModel.savedOrder.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    fun toast(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = CardGap),
        verticalArrangement = Arrangement.spacedBy(CardGap),
        contentPadding = PaddingValues(vertical = CardGap),
    ) {
        // Declared in their out-of-the-box order; the reader's saved order, if
        // any, is reconciled against this. Meals lead by default: the most-logged
        // thing here, and the card doubles as the last day's meals to check
        // against before adding another.
        reorderableCards(
            cards =
                listOf(
                    ReorderableCard("meals") {
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
        )

        item { Spacer(Modifier.height(8.dp)) }
    }
}
