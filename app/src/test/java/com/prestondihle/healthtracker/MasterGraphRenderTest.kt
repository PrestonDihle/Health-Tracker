package com.prestondihle.healthtracker

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.data.UserSettings
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.repository.TrackerRepository
import com.prestondihle.healthtracker.ui.master.MasterGraphScreen
import com.prestondihle.healthtracker.ui.master.MasterGraphViewModel
import com.prestondihle.healthtracker.ui.master.MasterRange
import com.prestondihle.healthtracker.ui.master.MasterSeries
import com.prestondihle.healthtracker.ui.theme.HealthTrackerTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the master graph end to end against a real repository.
 *
 * The chart does a lot of arithmetic on lists that can be empty -- axis ranges,
 * absorption sampling, marker placement -- and none of it is reachable from a
 * pure unit test. Composing the screen for real is what catches a divide-by-zero
 * or an empty-list crash before it ships.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class MasterGraphRenderTest {

    /** Long enough for a mock sync and a re-query, short enough to fail rather than hang. */
    private val SETTLE_TIMEOUT_MS = 10_000L

    @get:Rule val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun repository(): TrackerRepository {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        return TrackerRepository(db.trackerDao(), MockHealthDataSource(), ZoneId.of("UTC"))
    }

    private fun renderScreen(seed: suspend (TrackerRepository) -> Unit = {}) {
        val repository = repository()
        runBlocking { seed(repository) }
        val viewModel = MasterGraphViewModel(repository, ZoneId.of("UTC"))
        composeRule.setContent {
            HealthTrackerTheme {
                Surface(modifier = Modifier.fillMaxSize()) { MasterGraphScreen(viewModel) }
            }
        }
        composeRule.waitForIdle()

        // The screen's first sync runs off the composition, so `waitForIdle` can
        // return before it lands. Waiting for the not-connected prompt to go is
        // what makes the difference between a screenshot of the chart and a
        // screenshot of the screen still deciding whether it has any data.
        composeRule.waitUntil(SETTLE_TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Health Connect").fetchSemanticsNodes().isEmpty()
        }
    }

    /** Selects a window and waits for its chip to actually come up selected. */
    private fun chooseRange(range: MasterRange) {
        composeRule.onNodeWithText(range.label).performClick()
        // Same reason: the range drives a database query, and asserting on the
        // chip is the only way to know the redraw has happened. Without it a
        // capture here silently records the *previous* window under the new
        // window's filename.
        composeRule.waitUntil(SETTLE_TIMEOUT_MS) {
            composeRule
                .onAllNodes(hasText(range.label) and isSelected())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `renders with synced meals, heart rate and steps`() {
        renderScreen()

        composeRule.onNodeWithText("Right now").assertIsDisplayed()
        composeRule.onNodeWithText("Food, blood and body").assertIsDisplayed()
        composeRule.onNodeWithText("About the food curves").assertIsDisplayed()

        composeRule.onRoot().captureRoboImage("build/screenshots/master_graph.png")
    }

    @Test
    fun `the range chips are all offered`() {
        renderScreen()

        // Each chip decides how much history the queries pull, so a missing one
        // is a silently unreachable window rather than a visible break. Six of
        // them only fit because the row wraps -- a chip pushed off the edge would
        // show up here as a failed assertion rather than as an invisible option.
        MasterRange.entries.forEach { option ->
            composeRule.onNodeWithText(option.label).assertIsDisplayed()
        }
    }

    @Test
    fun `switching to the widest range does not break the chart`() {
        renderScreen()

        // A week widens the window far past the mock data's edges and pushes the
        // absorption sampling to its coarsest step, which is where an off-by-one
        // in the axis, the bar widths or the curve sampling would surface.
        chooseRange(MasterRange.WEEK)

        composeRule.onNodeWithText("Food, blood and body").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/master_graph_7d.png")
    }

    @Test
    fun `switching to the narrowest range does not break the chart`() {
        renderScreen()

        // Three hours is the other extreme: the finest curve sampling, and few
        // enough glucose readings that the chart switches its dots back on.
        chooseRange(MasterRange.THREE)

        composeRule.onNodeWithText("Food, blood and body").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/master_graph_3h.png")
    }

    /**
     * The smoothed line and the target band, on the one screen that can be
     * scrolled and asserted against.
     *
     * The same two features sit on the dashboard's glucose card, but that screen
     * runs a one-second ticker for the fast timer and so never reaches the idle
     * state the test framework waits on -- see `ScreenRenderTest.dashboard
     * renders`. The drawing code is shared, so covering it here covers both.
     */
    @Test
    fun `a smoothed glucose line and a target band both render`() {
        renderScreen { repository ->
            repository.upsertUserSettings(UserSettings(smoothGlucose = true))
            repository.upsertUserGoals(
                UserGoals(glucoseTargetLowMgDl = 80, glucoseTargetHighMgDl = 130)
            )

            // A noisy trace with one real post-meal rise in it: something for the
            // filter to remove, and something it must leave alone. The rise tops
            // out above the band so the band has a visible edge to be read
            // against rather than swallowing the whole line.
            val now = Instant.now()
            repeat(36) { index ->
                val rise = if (index in 12..22) 45 else 0
                val jitter = if (index % 2 == 0) 6 else -6
                repository.addBloodSugar(
                    mgDl = 95 + rise + jitter,
                    at = now.minus(Duration.ofMinutes((35 - index) * 5L)),
                )
            }
        }

        chooseRange(MasterRange.THREE)

        composeRule.onNodeWithText("Glucose (smoothed)", substring = true).assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/master_graph_smoothed.png")
    }

    @Test
    fun `the chart survives every series being switched off`() {
        renderScreen()

        // Nothing left to draw is the case that used to reach the axis maths with
        // empty lists. It is also what makes the meal rules the only thing on the
        // plot, which is how they came to be read as a data spike.
        //
        // Driven off the switches rather than their captions: the caption is a
        // plain Text beside the control, so clicking it does nothing and the
        // assertion below would pass without a single series being turned off.
        val switches = composeRule.onAllNodes(isToggleable())
        assertEquals(
            MasterSeries.entries.size,
            switches.fetchSemanticsNodes().size,
        )
        MasterSeries.entries.indices.forEach { switches[it].performClick() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("No readings in this window").assertIsDisplayed()
    }
}
