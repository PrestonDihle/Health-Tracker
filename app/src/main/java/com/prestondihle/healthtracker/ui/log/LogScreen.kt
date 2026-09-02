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
import com.prestondihle.healthtracker.ui.fuel.QUICK_CAFFEINE_MG
import com.prestondihle.healthtracker.ui.reorder.CardOrderViewModel
import com.prestondihle.healthtracker.ui.reorder.ReorderableCard
import com.prestondihle.healthtracker.ui.reorder.reorderableCards
import com.prestondihle.healthtracker.ui.wellness.BloodPressureCard
import com.prestondihle.healthtracker.ui.wellness.BodyCard
import com.prestondihle.healthtracker.ui.wellness.FoodLogConfidenceCard
import com.prestondihle.healthtracker.ui.wellness.GripStrengthCard
import com.prestondihle.healthtracker.ui.wellness.MoodCard
import com.prestondihle.healthtracker.ui.wellness.MovementCard
import com.prestondihle.healthtracker.ui.wellness.PlankCard
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
    // Its own flow, and it only ticks while a plank is actually running -- see
    // WellnessViewModel.plank. A ticker that ran unconditionally would make this
    // the third screen in the app that never reaches idle in a test.
    val plank by viewModel.plank.collectAsStateWithLifecycle()
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
                    // Both units, for the same reason the Hydration list carries
                    // both: half the chips above are labelled in millilitres now,
                    // and an ounces-only confirmation answered a tap on "100 ml"
                    // with "Logged 3 oz", which reads as having logged the wrong
                    // thing rather than as the same drink in the other unit.
                    toast("Logged $it ml · ${Units.mlToWholeOz(it)} oz")
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
                    // Beside the rep counters, which is the other bodyweight work
                    // logged here -- and directly after them rather than before,
                    // because those are typed in a second and this one holds the
                    // reader's attention for two minutes.
                    ReorderableCard("plank") {
                        PlankCard(
                            plank = plank,
                            goalSeconds = state.goals.plankHoldSecondsGoal,
                            zoneId = state.zoneId,
                            onStart = viewModel::startPlank,
                            onStop = viewModel::stopPlank,
                            onSave = {
                                val held = plank.pendingSeconds
                                viewModel.savePlank()
                                held?.let { toast("Logged a ${Units.formatHold(it)} plank") }
                            },
                            onDiscard = {
                                viewModel.discardPlank()
                                toast("Plank discarded")
                            },
                            onUpdate = { session, seconds, at ->
                                viewModel.updatePlank(session, seconds, at)
                                toast("Hold corrected to ${Units.formatHold(seconds)}")
                            },
                            onDelete = { session ->
                                viewModel.deletePlank(session)
                                toast("Deleted the ${Units.formatHold(session.seconds)} hold")
                            },
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
 * One tap for each of the things logged most often.
 *
 * Two kinds of shortcut share the card. The fixed sizes -- the same four drinks
 * the Hydration card offers and the same tablet the Caffeine card offers -- do
 * not depend on history, so they work on a first run and on a day nothing has
 * been logged yet, which is what makes this row worth pinning above everything
 * else. The last caffeine dose is the one chip still read from disk, because a
 * cup is whatever the current cup is and no fixed button can guess it; it drops
 * out when it is already the tablet, so the row never offers 35 mg twice.
 *
 * A row per substance rather than one strip of everything. Mixed together the
 * unit was left doing all the separating -- "500 ml" beside "35 mg" reads as a
 * list of amounts rather than as two different things -- and a reader after
 * water now looks along one line instead of at alternate chips.
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
        QUICK_WATER.forEach { row ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (amount, ml) ->
                    // Every chip still names its substance, unlike the Hydration
                    // card's bare "+4 oz": that card's title said water, where
                    // three different things are logged from this one and an
                    // amount alone would not say which a tap writes. Abbreviated
                    // because naming it four times over cost more width than the
                    // chips had to spare, and H2O against mg is as easy to tell
                    // apart at a glance as the whole word was.
                    AssistChip(onClick = { onWater(ml) }, label = { Text("$amount H2O") })
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The habit first, the tablet after it: the dose actually drunk last
            // is the likelier tap, and appending rather than inserting leaves the
            // tablet in the same place whether or not there is a history to read.
            usual.lastCaffeineMg?.takeIf { it != QUICK_CAFFEINE_MG }?.let { mg ->
                AssistChip(onClick = { onCaffeine(mg) }, label = { Text("$mg mg CAF") })
            }
            AssistChip(
                onClick = { onCaffeine(QUICK_CAFFEINE_MG) },
                label = { Text("$QUICK_CAFFEINE_MG mg CAF") },
            )
        }

        if (outstanding.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

/**
 * The drinks offered as one tap: a row of ounces, then a row of millilitres.
 *
 * The same four sizes the Hydration card writes, chosen there for reasons that
 * hold just as well a tab away: a sip taken with a tablet is a real entry that
 * used to be rounded up to four ounces or left out, and 500 ml is the bottle,
 * which used to cost five taps of a hundred.
 *
 * Grouped into rows here rather than left to wrap. Splitting by unit is the
 * split the Hydration card already makes, and for the same reason: a reader
 * after ounces reads along one row instead of at alternate chips. The phone is
 * what made it a rule rather than a preference -- left as one list of four, and
 * before the labels were shortened, these came out three-and-one with the last
 * chip stranded on a line of its own looking like a mistake. Shorter labels
 * would fit four across now, but the grouping is the point and the pairing
 * survives a longer word being put back. Each row is still a [FlowRow], so a
 * screen too narrow for two chips wraps rather than squeezing them past the
 * point of being readable or hittable.
 *
 * The label is carried beside the amount rather than derived from it, because
 * these are stored as millilitres either way and rendering 1 oz back out of 30
 * ml would print "1 oz" only by luck of the rounding. Which unit a chip shows is
 * a question of the number the reader has in mind, not of what lands on disk.
 */
private val QUICK_WATER: List<List<Pair<String, Int>>> =
    listOf(
        listOf("1 oz" to Units.flOzToMl(1f), "4 oz" to Units.flOzToMl(4f)),
        listOf("100 ml" to 100, "500 ml" to 500),
    )
