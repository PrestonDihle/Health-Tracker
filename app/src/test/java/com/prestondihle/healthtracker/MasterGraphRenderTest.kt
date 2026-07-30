package com.prestondihle.healthtracker

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.repository.TrackerRepository
import com.prestondihle.healthtracker.ui.master.MasterGraphScreen
import com.prestondihle.healthtracker.ui.master.MasterGraphViewModel
import com.prestondihle.healthtracker.ui.theme.HealthTrackerTheme
import java.time.ZoneId
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

    private fun renderScreen() {
        val viewModel = MasterGraphViewModel(repository(), ZoneId.of("UTC"))
        composeRule.setContent {
            HealthTrackerTheme {
                Surface(modifier = Modifier.fillMaxSize()) { MasterGraphScreen(viewModel) }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `renders with synced meals and heart rate`() {
        renderScreen()

        composeRule.onNodeWithText("Right now").assertIsDisplayed()
        composeRule.onNodeWithText("Food, blood and heart").assertIsDisplayed()
        composeRule.onNodeWithText("About the food curves").assertIsDisplayed()

        composeRule.onRoot().captureRoboImage("build/screenshots/master_graph.png")
    }

    @Test
    fun `the range chips are all offered`() {
        renderScreen()

        // 12h/24h/48h decide how much history the queries pull, so a missing one
        // is a silently unreachable window rather than a visible break.
        listOf("12h", "24h", "48h").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun `switching the range does not break the chart`() {
        renderScreen()

        // 48h widens the window past the mock data's edges, which is where an
        // off-by-one in the axis or sampling would surface.
        composeRule.onNodeWithText("48h").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Food, blood and heart").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/master_graph_48h.png")
    }
}
