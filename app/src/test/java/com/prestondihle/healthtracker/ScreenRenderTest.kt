package com.prestondihle.healthtracker

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.data.DailyLog
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.repository.TrackerRepository
import com.prestondihle.healthtracker.ui.dashboard.DashboardScreen
import com.prestondihle.healthtracker.ui.dashboard.DashboardViewModel
import com.prestondihle.healthtracker.ui.theme.HealthTrackerTheme
import com.prestondihle.healthtracker.ui.trends.TrendsScreen
import com.prestondihle.healthtracker.ui.trends.TrendsViewModel
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the dashboard and trends screens against a real repository.
 *
 * Both are dense with charts whose arithmetic only runs when something actually
 * lays them out. Composing them for real is what catches an empty-list crash or
 * a mislabelled axis; the captured images are for reviewing how they look.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class ScreenRenderTest {

    @get:Rule val composeRule = createComposeRule()

    private val zone = ZoneId.of("UTC")

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /**
     * A repository with a fortnight of synced history behind it.
     *
     * The trends charts are all backed by HealthDaySnapshot rows, which only
     * exist once a sync has run -- without this the screen renders empty and
     * proves nothing.
     */
    private fun seededRepository(): TrackerRepository {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val repository = TrackerRepository(db.trackerDao(), MockHealthDataSource(zone), zone)
        runBlocking {
            val today = java.time.LocalDate.now(zone)
            (0L..14L).forEach { offset ->
                val date = today.minusDays(offset)
                repository.syncHealthData(date)
                // The three subjective scores are hand-logged, so no amount of
                // syncing produces them -- without these the combined chart draws
                // "No data yet" and its three line styles go unexercised.
                val seed = kotlin.random.Random(date.toEpochDay())
                repository.upsertDailyLog(
                    DailyLog(
                        date = date,
                        vibe = seed.nextInt(4, 10),
                        energy = seed.nextInt(3, 10),
                        focus = seed.nextInt(2, 9),
                        bookPagesRead = seed.nextInt(0, 40),
                    )
                )
            }
        }
        return repository
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent {
            HealthTrackerTheme { Surface(modifier = Modifier.fillMaxSize()) { content() } }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `trends screen renders`() {
        val vm = TrendsViewModel(seededRepository(), zone)
        render { TrendsScreen(vm) }
        composeRule.onRoot().captureRoboImage("build/screenshots/trends.png")
    }

    @Test
    fun `the combined mood chart renders`() {
        val vm = TrendsViewModel(seededRepository(), zone)
        render { TrendsScreen(vm) }

        // Far enough down a lazy list that it is never composed by default, so
        // it has to be scrolled to before it can be looked at or asserted on.
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText("Vibe, energy and focus")
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Vibe, energy and focus").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/trends_mood.png")
    }

    @Test
    fun `dashboard renders`() {
        val vm = DashboardViewModel(seededRepository(), zone)
        render { DashboardScreen(vm, SnackbarHostState()) }
        composeRule.onRoot().captureRoboImage("build/screenshots/dashboard.png")
    }
}
