package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.data.WeightEntry
import com.prestondihle.healthtracker.data.WeightSubGoal
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.ui.settings.SettingsUiState
import com.prestondihle.healthtracker.ui.settings.WaypointRangeLbs
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the "add a waypoint" stepper opens.
 *
 * It is only a starting value, which is exactly why it is worth pinning: a
 * stepper that opens 55 lb from the answer is not broken in any way a test of
 * behaviour would catch, and is still tapped a hundred times to reach the number
 * that was always going to be chosen.
 */
class WaypointSeedTest {

    private fun state(
        goalLbs: Float? = null,
        latestLbs: Float? = null,
        stagedLbs: List<Float> = emptyList(),
    ) =
        SettingsUiState(
            goals = UserGoals(goalWeightKg = goalLbs?.let(Units::lbsToKg)),
            latestWeight =
                latestLbs?.let {
                    WeightEntry(date = LocalDate.of(2026, 8, 24), weightKg = Units.lbsToKg(it))
                },
            weightSubGoals =
                stagedLbs.mapIndexed { index, lbs ->
                    WeightSubGoal(id = index + 1L, kg = Units.lbsToKg(lbs))
                },
        )

    @Test
    fun `with nothing staged the stepper opens at the current weight`() {
        // The case the seed exists for. A first waypoint goes somewhere between
        // here and the goal, so here is where the control has to start -- opening
        // at the goal is opening at the one weight a waypoint is never set to.
        val seed = state(goalLbs = 225f, latestLbs = 280f).suggestedWaypointLbs

        assertEquals(280f, seed, 0.5f)
    }

    @Test
    fun `with a mark staged the stepper opens midway to the goal`() {
        // The original rule, and still the right one once there is a mark: the
        // next waypoint usually splits what is left.
        val seed = state(goalLbs = 225f, latestLbs = 280f, stagedLbs = listOf(265f))
            .suggestedWaypointLbs

        assertEquals(245f, seed, 0.5f)
    }

    @Test
    fun `the lightest staged mark is the one split, not the newest`() {
        // Waypoints are staged in whatever order they are thought of. Splitting
        // from the most recently added would walk back up the scale as soon as
        // one was added out of order.
        val seed = state(goalLbs = 225f, latestLbs = 280f, stagedLbs = listOf(245f, 265f))
            .suggestedWaypointLbs

        assertEquals(235f, seed, 0.5f)
    }

    @Test
    fun `with no weight ever logged the stepper falls back to the goal`() {
        // Nothing better to offer, and at least it is a number the reader chose.
        val seed = state(goalLbs = 225f).suggestedWaypointLbs

        assertEquals(225f, seed, 0.5f)
    }

    @Test
    fun `with neither a goal nor a weight the stepper opens where the goal control does`() {
        // Two different defaults would have the suggestion disagree with the goal
        // it is supposed to be a step towards.
        val seed = state().suggestedWaypointLbs

        assertEquals(180f, seed, 0.5f)
    }

    @Test
    fun `the seed is never outside what the stepper can reach`() {
        // A weight above the control's ceiling would open it on a value its own
        // arrows cannot return to: the up arrow is already disabled and the down
        // arrow steps to the ceiling, stranding whatever was showing.
        val heavy = state(goalLbs = 225f, latestLbs = 480f).suggestedWaypointLbs

        assertEquals(WaypointRangeLbs.endInclusive, heavy, 0.5f)
        assertTrue(heavy in WaypointRangeLbs)
    }
}
