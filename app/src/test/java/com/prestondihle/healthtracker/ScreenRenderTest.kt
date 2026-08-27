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
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.prestondihle.healthtracker.data.AftAttempt
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.data.DailyLog
import com.prestondihle.healthtracker.data.Sex
import com.prestondihle.healthtracker.data.UserSettings
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.repository.TrackerRepository
import com.prestondihle.healthtracker.ui.reorder.CardOrderViewModel
import com.prestondihle.healthtracker.ui.theme.HealthTrackerTheme
import com.prestondihle.healthtracker.ui.trends.TrendsRange
import com.prestondihle.healthtracker.ui.trends.TrendsScreen
import com.prestondihle.healthtracker.ui.trends.TrendsViewModel
import com.prestondihle.healthtracker.ui.wellness.MoodTrendCard
import com.prestondihle.healthtracker.ui.wellness.SleepCard
import com.prestondihle.healthtracker.ui.wellness.WellnessScreen
import com.prestondihle.healthtracker.ui.wellness.WellnessUiState
import com.prestondihle.healthtracker.ui.wellness.WellnessViewModel
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Wellness and Activity screens against a real repository.
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
                // Grip is hand-logged too, and every third day rather than daily
                // -- which is what actually exercises the trend chart's gaps.
                if (offset % 3 == 0L) {
                    val dominantKg = Units.lbsToKg(95f + seed.nextInt(-6, 7))
                    repository.setGripStrengthKg(date, dominant = true, kg = dominantKg)
                    repository.setGripStrengthKg(
                        date,
                        dominant = false,
                        kg = dominantKg * 0.9f,
                    )
                }
            }
            // Sleep and heart rate arrive through the time-series sync, not the
            // daily one, so without this the Today sleep card renders its "no
            // sleep recorded" branch and the hypnogram is never composed at all.
            // That is the path worth covering here: the stage trace and the heart
            // rate over it are canvas arithmetic that only runs under a real
            // layout pass, which is precisely what no pure-JVM test reaches.
            repository.syncTimeSeries(
                java.time.Instant.now().minus(java.time.Duration.ofHours(48)),
                java.time.Instant.now(),
            )
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
        val repo = seededRepository()
        val vm = TrendsViewModel(repo, zone)
        render { TrendsScreen(vm, CardOrderViewModel(repo, "activity")) }
        composeRule.onRoot().captureRoboImage("build/screenshots/trends.png")
    }

    /**
     * The mood chart on its own, for the reason the sleep card is.
     *
     * It used to be scrolled to on Activity; it lives on Wellness now, and that
     * screen ticks -- so `performScrollToNode` never gets the idle state it waits
     * on and the card is simply never composed. Rendering it directly is what
     * still puts a real layout pass through the three-series chart, which is the
     * only place its 1-10 axis and its solid/dashed/dotted styles are exercised.
     *
     * Seeded from the same repository the screen reads, so the state under test
     * is the shape the view model would have assembled rather than a hand-built
     * one that cannot go stale in the same ways.
     */
    @Test
    fun `the combined mood chart renders`() {
        val repository = seededRepository()
        val today = java.time.LocalDate.now(zone)
        val start = today.minusDays(13)
        val history = runBlocking { repository.getDailyLogs(start, today).first() }
        // Three line styles are only told apart where all three have something to
        // draw. An empty history renders the "no data" branch and would pass
        // every assertion below while proving none of it.
        assertTrue(history.any { it.vibe != null && it.energy != null && it.focus != null })

        render {
            MoodTrendCard(
                state =
                    WellnessUiState(
                        logHistory = history,
                        historyStart = start,
                        historyEnd = today,
                    )
            )
        }

        composeRule.onNodeWithText("Vibe, energy and focus").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/mood-card.png")
    }

    /**
     * The AFT card, scored and plotted.
     *
     * Activity does not tick, so unlike the mood card this one can be scrolled
     * to for real -- which is worth doing, because the card is the only place
     * the scoring tables meet a layout pass. The seeded profile matters as much
     * as the attempts: without an age and a sex the card renders its "set your
     * profile" branch and every score below it would go unexercised.
     */
    @Test
    fun `the AFT card scores a seeded attempt and plots the trend`() {
        val repo = seededRepository()
        runBlocking {
            repo.upsertUserSettings(UserSettings(ageYears = 24, sex = Sex.MALE))
            // Two finished tests, because the trend needs a second point before
            // it is a trend at all -- one attempt draws nothing worth looking at.
            repo.addAftAttempt(
                AftAttempt(
                    date = java.time.LocalDate.now(zone).minusMonths(6),
                    deadliftKg = Units.lbsToKg(250f),
                    hrpReps = 37,
                    sdcSeconds = 113,
                    plankSeconds = 150,
                    twoMileSeconds = 1028,
                )
            )
            repo.addAftAttempt(
                AftAttempt(
                    date = java.time.LocalDate.now(zone),
                    deadliftKg = Units.lbsToKg(290f),
                    hrpReps = 45,
                    sdcSeconds = 105,
                    plankSeconds = 190,
                    twoMileSeconds = 960,
                )
            )
        }
        val vm = TrendsViewModel(repo, zone)
        render { TrendsScreen(vm, CardOrderViewModel(repo, "activity")) }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Army Fitness Test"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Army Fitness Test").assertIsDisplayed()
        // The verdict, which only appears once all five events are in.
        composeRule.onNodeWithText("Pass").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/aft-card.png")
    }

    @Test
    fun `the grip strength trend renders both hands`() {
        val repo = seededRepository()
        val vm = TrendsViewModel(repo, zone)
        render { TrendsScreen(vm, CardOrderViewModel(repo, "activity")) }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Grip strength"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Grip strength").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/trends_grip.png")
    }

    @Test
    fun `every trends range renders`() {
        val repo = seededRepository()
        val vm = TrendsViewModel(repo, zone)
        render { TrendsScreen(vm, CardOrderViewModel(repo, "activity")) }

        // Seven days is narrower than the seeded fortnight and ninety is wider
        // than it, so between them these cover both the cropping and the
        // mostly-empty ends of every chart's day-slot arithmetic.
        TrendsRange.entries.forEach { option ->
            composeRule.onNodeWithText(option.label).performClick()
            // The range drives a database query, so waiting on the chip coming up
            // selected is what proves the charts were rebuilt -- `waitForIdle`
            // alone returns while the flow is still in flight, and every
            // assertion after it would be about the previous range.
            composeRule.waitUntil(10_000) {
                composeRule
                    .onAllNodes(hasText(option.label) and isSelected())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeRule.onNodeWithText("Steps").assertIsDisplayed()
        }
    }

    /**
     * Wellness is captured where it opens, without scrolling.
     *
     * Not an oversight: `WellnessViewModel` runs a one-second ticker to drive
     * the live fast timer, so the screen never reaches the idle state
     * `performScrollToNode` waits for -- it retries, times out after a minute and
     * throws `AppNotIdleException`. Anything below the fold here has to be
     * asserted somewhere that does not tick; the glucose chart's smoothing and
     * target band are covered on the master graph for exactly that reason.
     */
    @Test
    fun `wellness renders`() {
        val repo = seededRepository()
        val vm = WellnessViewModel(repo, zone)
        render {
            WellnessScreen(
                vm,
                TrendsViewModel(repo, zone),
                CardOrderViewModel(repo, "wellness"),
                SnackbarHostState(),
            )
        }
        composeRule.onRoot().captureRoboImage("build/screenshots/wellness.png")
    }

    /**
     * The sleep card on its own, because Wellness cannot reach it.
     *
     * A LazyColumn composes only what is on screen and this screen cannot be
     * scrolled in a test -- the fast timer's ticker means it never reaches the
     * idle state `performScrollToNode` waits on. The sleep card is the third one
     * down, so through `WellnessScreen` it is never built at all and the
     * hypnogram's canvas arithmetic goes unexercised. Composed directly, a
     * divide-by-zero or an empty-list crash in the plot fails here.
     *
     * Seeded through the real sync rather than by hand, so the shape under test
     * is the shape the app actually stores.
     */
    @Test
    fun `sleep card renders a staged night`() {
        val repository = seededRepository()
        val night = runBlocking { repository.getLatestSleepNight().first() }
        val heartRate = runBlocking {
            repository.getHeartRateSince(java.time.Instant.now().minus(java.time.Duration.ofHours(36)))
                .first()
        }
        // If the mock ever stops producing sleep this fails here rather than
        // silently rendering the empty branch and proving nothing.
        assertNotNull(night)

        render {
            SleepCard(
                state =
                    WellnessUiState(sleep = night, sleepHeartRate = heartRate, zoneId = zone)
            )
        }

        composeRule.onNodeWithText("Asleep").assertIsDisplayed()
        composeRule.onNodeWithText("REM").assertIsDisplayed()
        composeRule.onNodeWithText("Deep").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/sleep-card.png")
    }
}
