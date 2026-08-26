package com.prestondihle.healthtracker

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.data.DataSourceEnum
import com.prestondihle.healthtracker.data.MealEntry
import com.prestondihle.healthtracker.data.TrackerDao
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.data.UserSettings
import com.prestondihle.healthtracker.domain.SleepStage
import com.prestondihle.healthtracker.domain.SleepStageInterval
import com.prestondihle.healthtracker.health.HealthDataSource
import com.prestondihle.healthtracker.health.MealSample
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.health.SleepSessionSample
import com.prestondihle.healthtracker.repository.TrackerRepository
import com.prestondihle.healthtracker.ui.master.MasterGraphScreen
import com.prestondihle.healthtracker.ui.master.MasterGraphViewModel
import com.prestondihle.healthtracker.ui.master.MasterRange
import com.prestondihle.healthtracker.ui.master.MasterSeries
import com.prestondihle.healthtracker.ui.theme.HealthTrackerTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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

    /**
     * The DAO behind the repository the current test is using.
     *
     * Exposed so a test can plant rows the way a *previous* version of the app
     * left them. Duplicates that are already on disk are the case the read-time
     * collapse exists for, and they cannot be produced through the repository --
     * the sync now rejects them on the way in.
     */
    private lateinit var dao: TrackerDao

    private fun repository(
        dataSource: HealthDataSource = MockHealthDataSource()
    ): TrackerRepository {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.trackerDao()
        return TrackerRepository(dao, dataSource, ZoneId.of("UTC"))
    }

    /**
     * A nutrition source that records the date and nothing finer, and writes each
     * meal twice.
     *
     * Both halves are copied from what a real phone produced: every
     * `NutritionRecord` landing on one fixed time of day, and the same two meals
     * arriving as four records with four distinct Health Connect ids. Delegating
     * the rest of the interface keeps the oddity in the test rather than in the
     * shared mock.
     *
     * Deliberately *not* midnight. Midnight has its own rule, so stamping there
     * would let a broken shared-time-of-day check still pass — the real phone's
     * meals all sat at 10:00, which is exactly the shape that has to be caught on
     * the repeat alone.
     */
    private class DateOnlyDuplicatedMeals(private val delegate: MockHealthDataSource) :
        HealthDataSource by delegate {
        override suspend fun readMeals(from: Instant, to: Instant): List<MealSample> {
            val stamped = stampedTime(to)
            if (stamped.isBefore(from)) return emptyList()
            return (0..1).flatMap { copy ->
                listOf(
                    MealSample(
                        time = stamped,
                        calories = 602,
                        proteinGrams = 30f,
                        carbGrams = 16.5f,
                        fatGrams = 20f,
                        externalId = "duplicated-a-$copy",
                    ),
                    MealSample(
                        time = stamped,
                        calories = 573,
                        proteinGrams = 25f,
                        carbGrams = 9.3f,
                        fatGrams = 18f,
                        externalId = "duplicated-b-$copy",
                    ),
                )
            }
        }
    }

    /**
     * One night placed relative to now, rather than at a fixed hour of the clock.
     *
     * The shared mock seeds every night 23:00 to about 07:50 *in its own zone*,
     * and it keeps `systemDefault()` while the repository and the view model
     * here are both pinned to UTC. West of Greenwich that puts the seeded night
     * at 06:00 to 14:50 UTC, so whether the live 3h window landed inside one was
     * decided by the hour the suite happened to be run at: the negative half of
     * the assertion below held all evening and failed before breakfast. Aligning
     * the zone alone would only move the broken hours onto the evenings this
     * repository is actually worked on.
     *
     * A night that ended [endedHoursAgo] hours ago is inside the day window and
     * outside the last three hours at every hour of every day, which is what
     * makes both directions of the test true whenever it runs.
     */
    private class NightEndedHoursAgo(
        private val delegate: MockHealthDataSource,
        private val endedHoursAgo: Long = 6,
        private val lengthHours: Long = 8,
    ) : HealthDataSource by delegate {
        override suspend fun readSleepSessions(
            from: Instant,
            to: Instant,
        ): List<SleepSessionSample> {
            val end = Instant.now().minus(Duration.ofHours(endedHoursAgo))
            val start = end.minus(Duration.ofHours(lengthHours))
            // The same overlap filter the real source applies, so a window that
            // genuinely predates the night still comes back empty.
            if (!end.isAfter(from) || !start.isBefore(to)) return emptyList()
            val deepFrom = start.plus(Duration.ofHours(3))
            val remFrom = start.plus(Duration.ofHours(5))
            return listOf(
                SleepSessionSample(
                    start = start,
                    end = end,
                    stages =
                        listOf(
                            SleepStageInterval(start, deepFrom, SleepStage.LIGHT),
                            SleepStageInterval(deepFrom, remFrom, SleepStage.DEEP),
                            SleepStageInterval(remFrom, end, SleepStage.REM),
                        ),
                    externalId = "night-ended-${endedHoursAgo}h-ago",
                )
            )
        }
    }

    private fun renderScreen(
        repository: TrackerRepository = repository(),
        seed: suspend (TrackerRepository) -> Unit = {},
    ) {
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
        composeRule.onRoot().captureRoboImage("build/screenshots/master_graph.png")

        // Below the fold once the axis picker was added to the chart card, so
        // this one has to be scrolled to rather than asserted where it opens.
        composeRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasText("About the food curves"))
        composeRule.onNodeWithText("About the food curves").assertIsDisplayed()
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

    /**
     * Caffeine reaches the master chart, and reaches it from before the window.
     *
     * The legend caption is what is asserted rather than the word "Caffeine":
     * that appears three times over on this screen -- on the axis chip, on the
     * series switch and in the legend -- and only the legend quotes the range,
     * which is also the only place a self-scaled series' numbers appear at all.
     */
    @Test
    fun `a caffeine curve renders from doses taken before the window opened`() {
        renderScreen { repository ->
            val now = Instant.now()
            // One this morning and one well before the three-hour window opens.
            // The older dose is most of the level at the left edge, and dropping
            // it would start the line at zero and draw a climb nobody drank.
            repository.addCaffeine(mg = 150, at = now.minus(Duration.ofHours(6)))
            repository.addCaffeine(mg = 90, at = now.minus(Duration.ofMinutes(40)))
        }

        chooseRange(MasterRange.THREE)

        composeRule.onNodeWithText("Caffeine (0-200 mg)", substring = true).assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/master_graph_caffeine.png")
    }

    /**
     * A source that gives dates instead of times, and repeats itself.
     *
     * Exactly what a real phone was producing: the meal list read as several
     * meals all eaten at 1 AM, and every one of them counted more than once.
     * Neither is the app's arithmetic going wrong, so neither can be fixed by
     * changing it -- the screen has to say which meals are merely dated, and stop
     * believing a repeated record.
     */
    @Test
    fun `meals with no clock time are flagged and repeated records merged`() {
        // Both halves of the real situation at once: four rows already on disk
        // that are two meals written twice, and a source that keeps handing over
        // the same four. The sync rejects its copies as already stored, and the
        // four that were there before the fix are collapsed on the way to the
        // screen rather than deleted.
        val stamped = stampedTime(Instant.now())
        renderScreen(repository(DateOnlyDuplicatedMeals(MockHealthDataSource()))) {
            dao.insertMeals(
                (0..1).flatMap { copy ->
                    listOf(
                        MealEntry(
                            timestamp = stamped,
                            calories = 602,
                            proteinGrams = 30f,
                            carbGrams = 16.5f,
                            fatGrams = 20f,
                            source = DataSourceEnum.HEALTH_CONNECT,
                            externalId = "duplicated-a-$copy",
                        ),
                        MealEntry(
                            timestamp = stamped,
                            calories = 573,
                            proteinGrams = 25f,
                            carbGrams = 9.3f,
                            fatGrams = 18f,
                            source = DataSourceEnum.HEALTH_CONNECT,
                            externalId = "duplicated-b-$copy",
                        ),
                    )
                }
            )
        }

        // Waiting on the meal list itself is not possible: it is the last card in
        // a lazy column, so it is not composed until something scrolls to it, and
        // scrolling to a node that does not exist yet throws. The "Last meal"
        // line in the card at the top is driven by the same meals and is on
        // screen from the start, so it is what says the sync has landed.
        composeRule.waitUntil(SETTLE_TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText("Last meal", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // The meal list is the last card on the screen. Scrolling is available
        // here in a way it is not on the dashboard: this screen's ticker is a
        // plain state flow nudged on refresh, not a loop, so it reaches idle.
        //
        // Scrolled to the note at the *foot* of the card rather than its title:
        // stopping at the title leaves the card straddling the bottom edge, and
        // everything below the fold is found but not displayed.
        composeRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasText("merged", substring = true))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Meals in this window").assertIsDisplayed()
        composeRule
            .onNodeWithText("carry a stamped time", substring = true)
            .assertIsDisplayed()
        // Four records in, two meals out, and the screen owns up to the merge
        // rather than quietly halving the day's carbohydrate.
        composeRule
            .onNodeWithText("2 repeated records from the source merged", substring = true)
            .assertIsDisplayed()
        // And the list itself is two rows, not four. The source names none of
        // them, so each falls back to the same placeholder.
        assertEquals(2, composeRule.onAllNodesWithText("Meal").fetchSemanticsNodes().size)

        composeRule.onRoot().captureRoboImage("build/screenshots/master_graph_undated_meals.png")
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
        // The legend rows are clickable rather than toggleable and so are not
        // matched here, which is what keeps this a count of the switches.
        val switches = composeRule.onAllNodes(isToggleable())
        assertEquals(
            MasterSeries.entries.size,
            switches.fetchSemanticsNodes().size,
        )
        // Scrolled to individually rather than clicked where they lie. Eight
        // switches wrap onto three rows and the card no longer fits the screen,
        // so the last row is below the fold -- and a click on an off-screen node
        // is clamped into view and quietly lands on nothing, leaving two series
        // still drawn and this test passing for the wrong reason until the
        // assertion below caught it.
        MasterSeries.entries.indices.forEach { switches[it].performScrollTo().performClick() }
        composeRule.waitForIdle()

        // Back up to the plot, which the scrolling above pushed off the top.
        composeRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasText("Food, blood and body"))
        composeRule.onNodeWithText("No readings in this window").assertIsDisplayed()

        // Every switch survives with nothing drawn, which is what makes the state
        // recoverable. The legend is empty here -- it lists what is on the plot --
        // so the switches are the only way back, and they have to still be there.
        composeRule
            .onAllNodes(isToggleable() and isOff())
            .assertCountEquals(MasterSeries.entries.size)
    }

    /**
     * The legend doubling as the switch for its own line.
     *
     * Caffeine because it is the one series drawn against a scale of its own
     * here, so its caption changes shape between the two states -- with a range
     * quoted while it is on the plot, and the bare name once it is off, because a
     * line that is not drawn has no axis to quote.
     */
    @Test
    fun `tapping a name in the key puts its line away and a switch brings it back`() {
        renderScreen { repository ->
            repository.addCaffeine(mg = 120, at = Instant.now().minus(Duration.ofMinutes(30)))
        }

        // The legend sits at the foot of the chart card, below the fold on a
        // phone. Scrolled to the line that explains it, which is directly under.
        composeRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasText("Tapping a name in the key", substring = true))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Caffeine (", substring = true).assertIsDisplayed()

        composeRule.onNodeWithText("Caffeine (", substring = true).performClick()
        composeRule.waitForIdle()

        // Gone from the key, because a key lists what is on the plot. That is
        // exactly why the tap is only ever one-way, and why the switch row has to
        // exist: there is no row left here to tap a second time.
        composeRule.onAllNodesWithText("Caffeine (", substring = true).assertCountEquals(0)

        // Reached by index rather than by text: a switch carries no text of its
        // own -- its caption is a sibling Text -- and the switches are the only
        // toggleable nodes on the screen, in MasterSeries order.
        composeRule
            .onAllNodes(isToggleable())[MasterSeries.entries.indexOf(MasterSeries.CAFFEINE)]
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        // Back up to the key, which scrolling down to the switch pushed away.
        composeRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasText("Tapping a name in the key", substring = true))
        composeRule.onNodeWithText("Caffeine (", substring = true).assertIsDisplayed()
    }

    /**
     * Tapping the plot to read every line at one moment.
     *
     * The glucose trace is planted flat, so what is asserted does not depend on
     * where in the window the tap lands. The arithmetic that picks the nearest
     * sample is pinned on its own terms; what this covers is that a tap on a
     * Canvas reaches the readout at all, which nothing below the composition can
     * answer.
     */
    @Test
    fun `tapping the plot reads every line at that moment`() {
        renderScreen { repository ->
            val now = Instant.now()
            repeat(30) { index ->
                repository.addBloodSugar(
                    mgDl = 111,
                    at = now.minus(Duration.ofMinutes(index * 5L)),
                )
            }
        }

        chooseRange(MasterRange.THREE)

        composeRule
            .onNodeWithContentDescription(PLOT_DESCRIPTION, substring = true)
            .performTouchInput { click() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Glucose 111", substring = true).assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/master_graph_crosshair.png")

        // And tapping the same place again puts it away, rather than leaving a
        // hairline across the plot with no way off it.
        composeRule
            .onNodeWithContentDescription(PLOT_DESCRIPTION, substring = true)
            .performTouchInput { click() }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Glucose 111", substring = true).assertCountEquals(0)
    }

    /**
     * The asleep hours are shaded, and only where somebody was asleep.
     *
     * Asserted through the plot's spoken description rather than by looking at
     * pixels, which is the only handle there is: the shade is a wash on a canvas
     * with no text in it, and at a tenth opacity it is not something a screenshot
     * comparison would reliably catch either.
     *
     * Both directions matter. A shade that never appears is a feature quietly
     * missing, and one that appears on a window nobody slept through is worse --
     * it would have the reader explaining an evening heart rate by sleep that did
     * not happen. The night is seeded six hours back rather than at an hour of
     * the clock, so the 3h window is the case that must come up empty however
     * late in the day the suite is run -- see [NightEndedHoursAgo].
     */
    @Test
    fun `the night is shaded on a window that covers it and left alone on one that does not`() {
        renderScreen(repository(NightEndedHoursAgo(MockHealthDataSource())))

        chooseRange(MasterRange.DAY)
        // Waited for rather than asserted outright. The night arrives through the
        // screen's own sync, which runs off the composition and lands well after
        // the not-connected prompt has gone -- the same race the range chips are
        // waited on for.
        composeRule.waitUntil(SETTLE_TIMEOUT_MS) {
            composeRule
                .onAllNodesWithContentDescription("Asleep from", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        chooseRange(MasterRange.THREE)
        composeRule
            .onAllNodesWithContentDescription("Asleep from", substring = true)
            .assertCountEquals(0)
    }

    /**
     * Dragging the window off the clock, and getting back to it.
     *
     * The gesture is the risk here rather than the arithmetic: the plot sits
     * inside a scrolling list, and a drag detector that claimed the whole pointer
     * would stop the screen scrolling at all. Only the horizontal one is taken,
     * which is something a swipe can check and a unit test cannot.
     */
    @Test
    fun `dragging the plot sideways moves the window back and says so`() {
        renderScreen()
        chooseRange(MasterRange.THREE)

        composeRule.onAllNodesWithText("Back to now").assertCountEquals(0)

        composeRule
            .onNodeWithContentDescription(PLOT_DESCRIPTION, substring = true)
            .performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Back to now").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/master_graph_panned.png")

        composeRule.onNodeWithText("Back to now").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Back to now").assertCountEquals(0)
    }
}

/** Enough of the plot's screen-reader label to find it by, and no more. */
private const val PLOT_DESCRIPTION = "Food, blood and body plot"

/**
 * The instant a date-only source is pretending a meal happened at.
 *
 * A round hour a couple back: always in the past, inside even the narrowest
 * window on offer, and -- landing on the hour with no seconds -- the shape a
 * stamped time actually has. Top-level because the fake data source is a nested
 * class and cannot reach the test's own members.
 */
private fun stampedTime(reference: Instant): Instant =
    reference.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(2))
