package com.prestondihle.healthtracker

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.prestondihle.healthtracker.data.HealthDaySnapshot
import com.prestondihle.healthtracker.data.Sex
import com.prestondihle.healthtracker.data.ThemeMode
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.data.UserSettings
import com.prestondihle.healthtracker.data.WeightEntry
import com.prestondihle.healthtracker.data.WeightSubGoal
import com.prestondihle.healthtracker.domain.EnergyBalance
import com.prestondihle.healthtracker.domain.ScatterPoint
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.ui.components.chartBounds
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.repository.TrackerRepository
import com.prestondihle.healthtracker.ui.reorder.CardOrderViewModel
import com.prestondihle.healthtracker.ui.theme.HealthTrackerTheme
import com.prestondihle.healthtracker.ui.theme.resolvedDark
import com.prestondihle.healthtracker.ui.components.CardFold
import com.prestondihle.healthtracker.ui.components.DayPoint
import com.prestondihle.healthtracker.ui.components.EntryList
import com.prestondihle.healthtracker.ui.components.LocalCardFold
import com.prestondihle.healthtracker.ui.components.ScatterChart
import com.prestondihle.healthtracker.ui.trends.CompareCard
import com.prestondihle.healthtracker.ui.trends.CompareUiState
import com.prestondihle.healthtracker.ui.trends.ComparableMetric
import com.prestondihle.healthtracker.ui.trends.NetCaloriesTrendCard
import com.prestondihle.healthtracker.ui.trends.TrendsRange
import com.prestondihle.healthtracker.ui.trends.TrendsScreen
import com.prestondihle.healthtracker.ui.trends.TrendsUiState
import com.prestondihle.healthtracker.ui.trends.TrendsViewModel
import com.prestondihle.healthtracker.ui.trends.WeightTrendCard
import com.prestondihle.healthtracker.ui.wellness.MoodTrendCard
import com.prestondihle.healthtracker.ui.wellness.SleepCard
import com.prestondihle.healthtracker.ui.wellness.WellnessScreen
import com.prestondihle.healthtracker.ui.wellness.WellnessUiState
import com.prestondihle.healthtracker.ui.wellness.WellnessViewModel
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    private fun render(dark: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            HealthTrackerTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `trends screen renders`() {
        val repo = seededRepository()
        val vm = TrendsViewModel(repo, zone)
        // Hoisted out of the render lambda: built inside it, a new view model is
        // constructed on every recomposition, each starting its own flows whose
        // emissions cause the next -- an infinite composition loop wearing the
        // not-idle timeout's face. See the note in CLAUDE.md.
        val orderVm = CardOrderViewModel(repo, "activity")
        render { TrendsScreen(vm, orderVm) }
        composeRule.onRoot().captureRoboImage("build/screenshots/trends.png")
    }

    /** A fortnight of daily weighing, drifting down through ordinary noise. */
    private fun weighedFortnight(today: java.time.LocalDate) =
        (0L until 14L).map { back ->
            val lbs = 196f + (back / 14f) * 4f + ((back % 3) - 1) * 0.6f
            WeightEntry(date = today.minusDays(back), weightKg = Units.lbsToKg(lbs))
        }

    private fun weightStateAt(range: TrendsRange, today: java.time.LocalDate) =
        TrendsUiState(
            range = range,
            startDate = today.minusDays(range.days - 1),
            endDate = today,
            weights = weighedFortnight(today),
            goals = UserGoals(goalWeightKg = Units.lbsToKg(185f)),
            settings = UserSettings(),
            zoneId = zone,
        )

    /**
     * The trailing average over the weight, in both schemes.
     *
     * Captured twice because a series colour depends on what it is drawn on, and
     * this one is drawn *over* another line in the same hue -- the case where
     * getting it wrong produces two lines in nearly one colour with a key
     * claiming they are different things. That has happened here once already,
     * to sodium against diastolic, and it was caught on the phone rather than by
     * any test. The dark set collapses hues the light set keeps apart, so a
     * value that separates in one is no evidence at all about the other.
     */
    @Test
    fun `the weight average draws over the readings in both schemes`() {
        val today = java.time.LocalDate.now(zone)
        val state = weightStateAt(TrendsRange.TWO_WEEKS, today)

        // The overlay has something to draw, or the capture below proves nothing.
        val averaged = state.trailingAverage(state.weightSeries(Units::kgToLbs))
        assertTrue(averaged.count { it.value != null } >= 10)

        render { WeightTrendCard(state) }
        composeRule.onNodeWithText("Weight (7-day avg)").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/weight-average-light.png")
    }

    @Test
    fun `the weight average draws over the readings in the dark scheme`() {
        val today = java.time.LocalDate.now(zone)

        render(dark = true) { WeightTrendCard(weightStateAt(TrendsRange.TWO_WEEKS, today)) }

        composeRule.onNodeWithText("Weight (7-day avg)").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/weight-average-dark.png")
    }

    /**
     * The overlay is absent once a slot is a week, and takes its key with it.
     *
     * A weekly bucket is already a mean of seven days, so a seven-day mean of
     * those would be a second smoothing sold as the first. With the points a
     * week apart every window holds exactly one of them, so the average would
     * come back empty either way -- what this pins is that the chart then falls
     * back to a single unkeyed line rather than showing a legend row for a line
     * that is not on the plot.
     */
    @Test
    fun `the weekly ranges carry no moving average and no key for one`() {
        val today = java.time.LocalDate.now(zone)
        val state = weightStateAt(TrendsRange.YEAR, today)

        assertTrue(state.trailingAverage(state.weightSeries(Units::kgToLbs)).isEmpty())

        render { WeightTrendCard(state) }

        composeRule.onNodeWithText("Weight (7-day avg)").assertDoesNotExist()
        composeRule.onRoot().captureRoboImage("build/screenshots/weight-year-no-average.png")
    }

    /**
     * A folded card keeps its title and loses everything else.
     *
     * The title is the whole difficulty of folding here: it is drawn *inside* the
     * card, not by the reorder wrapper, so hiding what the wrapper owns takes the
     * title with it and leaves a row of chevrons over nothing. Composing the card
     * under a fold is the only way to see that the chevron, the title and nothing
     * else survive.
     */
    @Test
    fun `a folded card keeps its title row and drops its body`() {
        val today = java.time.LocalDate.now(zone)
        val state = weightStateAt(TrendsRange.TWO_WEEKS, today)

        render {
            CompositionLocalProvider(
                LocalCardFold provides CardFold(collapsed = true, onToggle = {})
            ) {
                WeightTrendCard(state)
            }
        }

        composeRule.onNodeWithText("Weight").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Expand this card").assertIsDisplayed()
        // The subtitle carries the unit and belongs to the body, so it goes too.
        composeRule.onNodeWithText("pounds, Health Connect and manual").assertDoesNotExist()
        composeRule.onNodeWithText("Weight (7-day avg)").assertDoesNotExist()
        composeRule.onRoot().captureRoboImage("build/screenshots/card-folded.png")
    }

    @Test
    fun `an unfolded card shows the chevron and the body together`() {
        val today = java.time.LocalDate.now(zone)
        val state = weightStateAt(TrendsRange.TWO_WEEKS, today)

        render {
            CompositionLocalProvider(
                LocalCardFold provides CardFold(collapsed = false, onToggle = {})
            ) {
                WeightTrendCard(state)
            }
        }

        // Not asserted on the bare title here: unfolded, the legend carries
        // "Weight" too, so the match is ambiguous by design. The subtitle and the
        // key are what say the body is present.
        composeRule.onNodeWithContentDescription("Collapse this card").assertIsDisplayed()
        composeRule.onNodeWithText("pounds, Health Connect and manual").assertIsDisplayed()
        composeRule.onNodeWithText("Weight (7-day avg)").assertIsDisplayed()
    }

    /**
     * Two metrics of wildly different magnitude, each on its own gutter.
     *
     * Steps against sleep is the pairing that proves the card: five figures
     * against single ones, which on one shared scale draws the sleep line as a
     * flat rule along the floor. If this renders with both traces visibly
     * moving, the two-axis arrangement is doing its job.
     */
    @Test
    fun `the compare card gives each metric a gutter of its own`() {
        val today = java.time.LocalDate.now(zone)
        val days = (0L until 21L).map { today.minusDays(20 - it) }
        // Five figures against single ones, which is the case the two gutters
        // exist for: on one shared scale the sleep trace is a flat rule along the
        // floor and the card says nothing at all.
        val compare =
            CompareUiState(
                first = ComparableMetric.STEPS,
                second = ComparableMetric.SLEEP,
                firstPoints =
                    days.mapIndexed { index, date ->
                        DayPoint(date, 7_000f + (index % 5) * 1_400f)
                    },
                secondPoints =
                    days.mapIndexed { index, date -> DayPoint(date, 6.2f + (index % 4) * 0.5f) },
                startDate = days.first(),
                endDate = today,
                zoneId = zone,
            )

        // Composed on its own, the `SleepCard` pattern: it sits below the fold on
        // a screen that ticks, so it can never be scrolled to -- and composing it
        // directly is the only way its two-gutter arithmetic gets a real layout
        // pass rather than going unexercised entirely.
        render { CompareCard(state = compare, onPick = { _, _ -> }, onLag = {}) }

        composeRule.onNodeWithText("Compare").assertIsDisplayed()
        composeRule.onNodeWithText("Shift Sleep a day later").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/compare-card.png")
    }

    /**
     * The goal projection, drawn past the right-hand edge.
     *
     * This is the one series in the app that occupies dates nobody has lived
     * yet, so it is also the one that can silently put every *reading* over the
     * wrong day: the chart maps a point to an x by its index, and adding future
     * slots to one series without adding them to the others slides them apart.
     * Rendering it is how that is caught, since both versions draw a plausible
     * chart and only one of them is about the right days.
     */
    @Test
    fun `the projection draws forward without shifting the readings`() {
        val today = java.time.LocalDate.now(zone)
        // A month of steady loss, ending well above a goal it is heading for.
        val weights =
            (0L until 30L).map { back ->
                WeightEntry(
                    date = today.minusDays(back),
                    weightKg = Units.lbsToKg(196f + back * 0.4f),
                )
            }
        val state =
            TrendsUiState(
                range = TrendsRange.MONTH,
                startDate = today.minusDays(29),
                endDate = today,
                weights = weights,
                goals = UserGoals(goalWeightKg = Units.lbsToKg(185f)),
                settings = UserSettings(),
                zoneId = zone,
            )

        val eta = state.weightEta
        assertNotNull(eta)
        val (padded, projection) = state.weightProjectionSeries(Units::kgToLbs)!!

        // Same length, so index-to-x lines up; the lead is future-dated and the
        // readings have nothing in it.
        assertEquals(padded.size, projection.size)
        assertEquals(padded.map { it.date }, projection.map { it.date })
        assertTrue(padded.last().date.isAfter(today))
        assertTrue(padded.filter { it.date.isAfter(today) }.all { it.value == null })
        assertTrue(projection.filter { it.date.isAfter(today) }.all { it.value != null })
        // Today carries both: the last reading and the segment's first point.
        assertNotNull(padded.single { it.date == today }.value)
        assertNotNull(projection.single { it.date == today }.value)

        render { WeightTrendCard(state) }

        composeRule.onNodeWithText("At current pace").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/weight-projection.png")
    }

    /**
     * Net calories, on a run of days that are all deficits.
     *
     * The seeding is the case worth capturing rather than an arbitrary one:
     * every point is below zero, so an axis scaled to the readings alone would
     * put the whole trace under a zero line that had been clipped off the top of
     * the plot -- the goal-outside-the-readings failure, on the one chart where
     * the reference is the difference between losing weight and gaining it.
     */
    @Test
    fun `net calories keeps the zero line on the plot when every day is a deficit`() {
        val today = java.time.LocalDate.now(zone)
        val snapshots =
            (0L until 14L).map { back ->
                HealthDaySnapshot(
                    date = today.minusDays(back),
                    dietaryCalories = 1_900 + ((back % 4) * 90).toInt(),
                    totalCalories = 2_600,
                    syncedAt = today.atStartOfDay(zone).toInstant(),
                )
            }
        val state =
            TrendsUiState(
                range = TrendsRange.TWO_WEEKS,
                startDate = today.minusDays(13),
                endDate = today,
                snapshots = snapshots,
                settings = UserSettings(),
                zoneId = zone,
            )

        val drawn = state.netCalorieSeries.mapNotNull { it.value }
        assertTrue(drawn.isNotEmpty())
        assertTrue(drawn.all { it < 0f })
        // Zero is above every reading, so it has to stretch the axis or vanish.
        assertTrue(chartBounds(drawn, marks = listOf(0f)).endInclusive >= 0f)

        render { NetCaloriesTrendCard(state) }

        composeRule.onNodeWithText("Net calories").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/net-calories.png")
    }

    /**
     * A year of weight, with the goal and the whole waypoint ladder on it.
     *
     * Composed directly rather than scrolled to on Wellness, which ticks -- the
     * `SleepCard` pattern, and here it also buys the thing that makes the test
     * worth having: a real layout pass over the widest range the app draws.
     *
     * A year is where this card has the most to get wrong at once. Fifty-odd
     * weekly buckets share the plot with a goal and four waypoints, and
     * `chartBounds` has to hold every one of those rules inside the axis or it
     * is not drawn small -- it is clipped, and a chart missing its goal looks
     * exactly like a chart that never had one. That is the failure this seeds
     * for deliberately: the goal sits eighteen pounds below anything actually
     * weighed, so an axis scaled to the readings alone would lose it.
     */
    @Test
    fun `a year of weight keeps its goal and waypoints on the plot`() {
        val today = java.time.LocalDate.now(zone)
        // A year of weighing, drifting down from about 208 lb to about 196, with
        // gaps: real weeks are not weighed on every day of them.
        val weights =
            (0L until 365L)
                .filter { it % 3L != 0L }
                .map { back ->
                    val day = today.minusDays(back)
                    val lbs = 196f + (back / 365f) * 12f + ((back % 7) - 3) * 0.4f
                    WeightEntry(date = day, weightKg = Units.lbsToKg(lbs))
                }
        val goalKg = Units.lbsToKg(178f)
        val ladder = listOf(200f, 195f, 190f, 185f).map { WeightSubGoal(kg = Units.lbsToKg(it)) }

        val state =
            TrendsUiState(
                range = TrendsRange.YEAR,
                startDate = today.minusDays(TrendsRange.YEAR.days - 1),
                endDate = today,
                weights = weights,
                goals = UserGoals(goalWeightKg = goalKg),
                weightSubGoals = ladder,
                settings = UserSettings(),
                zoneId = zone,
            )

        // The fold happened at all, and did not leave a year drawn as 365 slots.
        assertEquals(53, state.buckets.size)
        val drawn = state.weightSeries(Units::kgToLbs).mapNotNull { it.value }
        assertTrue(drawn.size in 50..53)

        // Every rule the card is about is inside the axis the readings imply,
        // which is the arithmetic the picture below is evidence for.
        val bounds =
            chartBounds(drawn, marks = listOf(Units.kgToLbs(goalKg)) + ladder.map { Units.kgToLbs(it.kg) })
        assertTrue(bounds.start <= Units.kgToLbs(goalKg))
        assertTrue(bounds.endInclusive >= drawn.max())

        render { WeightTrendCard(state) }

        composeRule.onNodeWithText("Weight").assertIsDisplayed()
        // Says a slot is a week, on the card rather than only by the chip: a
        // reader comparing a point against the goal line beside it is otherwise
        // reading an average as a morning.
        composeRule.onNodeWithText("pounds, Health Connect and manual, weekly average").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/weight-year.png")
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
        // Hoisted out of the render lambda: built inside it, a new view model is
        // constructed on every recomposition, each starting its own flows whose
        // emissions cause the next -- an infinite composition loop wearing the
        // not-idle timeout's face. See the note in CLAUDE.md.
        val orderVm = CardOrderViewModel(repo, "activity")
        render { TrendsScreen(vm, orderVm) }

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
        // Hoisted out of the render lambda: built inside it, a new view model is
        // constructed on every recomposition, each starting its own flows whose
        // emissions cause the next -- an infinite composition loop wearing the
        // not-idle timeout's face. See the note in CLAUDE.md.
        val orderVm = CardOrderViewModel(repo, "activity")
        render { TrendsScreen(vm, orderVm) }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Grip strength"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Grip strength").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/trends_grip.png")
    }

    @Test
    fun `every trends range renders`() {
        val repo = seededRepository()
        val vm = TrendsViewModel(repo, zone)
        // Hoisted out of the render lambda: built inside it, a new view model is
        // constructed on every recomposition, each starting its own flows whose
        // emissions cause the next -- an infinite composition loop wearing the
        // not-idle timeout's face. See the note in CLAUDE.md.
        val orderVm = CardOrderViewModel(repo, "activity")
        render { TrendsScreen(vm, orderVm) }

        // Seven days is narrower than the seeded fortnight and ninety is wider
        // than it, so between them these cover both the cropping and the
        // mostly-empty ends of every chart's day-slot arithmetic. The two long
        // ranges add the case where a slot is no longer a day at all: 365 days
        // of a seeded fortnight is fifty-odd weekly buckets with readings in two
        // of them, which is every empty-bucket branch of the fold at once.
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
        // Both hoisted, for the reason above the trends render: a view model
        // built inside the lambda is rebuilt on every recomposition.
        val trendsVm = TrendsViewModel(repo, zone)
        val orderVm = CardOrderViewModel(repo, "wellness")
        render { WellnessScreen(vm, trendsVm, orderVm, SnackbarHostState()) }
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

    /**
     * The entry list's fold, composed on its own.
     *
     * Direct composition for the reason [SleepCard] and [MoodTrendCard] get it:
     * both cards that carry one of these lists live on Fuel, which ticks, so a
     * screen-level test could never scroll down to them. Nothing is lost by
     * skipping the screen -- what is under test is the component's own contract,
     * which is the same wherever it is hung.
     */
    @Test
    fun `a long entry list shows three rows until it is opened`() {
        val entries = (1..9).map { "Entry $it" }
        render { Column { EntryList(entries = entries) { Text(it) } } }

        composeRule.onNodeWithText("Entry 1").assertIsDisplayed()
        composeRule.onNodeWithText("Entry 3").assertIsDisplayed()
        // Absent from the tree, not merely off screen: a row that is composed
        // but scrolled past still costs the height this fold exists to save.
        composeRule.onNodeWithText("Entry 4").assertDoesNotExist()

        // The count rides in the button because it is the figure the reader is
        // deciding on -- a bare "Show all" makes them open it to find out how
        // much they are opening.
        composeRule.onNodeWithText("Show all 9").performClick()
        composeRule.onNodeWithText("Entry 9").assertIsDisplayed()

        // And back, because a fold that only opens is a fold used once.
        composeRule.onNodeWithText("Show fewer").performClick()
        composeRule.onNodeWithText("Entry 4").assertDoesNotExist()
    }

    /**
     * A list already short enough gets no control at all.
     *
     * Absent rather than disabled. These lists sit on the two longest tabs in
     * the app, and a button that cannot do anything is still a line of the card
     * -- which is the cost this whole change is trying to give back.
     */
    @Test
    fun `a short entry list draws no fold control`() {
        render { Column { EntryList(entries = listOf("One", "Two", "Three")) { Text(it) } } }

        composeRule.onNodeWithText("Three").assertIsDisplayed()
        composeRule.onNodeWithText("Show all 3").assertDoesNotExist()
    }

    /**
     * The header stays put while the rows fold away underneath it.
     *
     * The hydration list is the one that needs this: the figure above it is
     * today's and the list is a week's, so "Last 7 days" is what stops the three
     * visible rows being read as today's drinks. Folding it away with them would
     * take the sentence off exactly the screen it is there to correct.
     */
    @Test
    fun `an entry list keeps its header above the fold`() {
        render {
            Column {
                EntryList(entries = (1..9).map { "Entry $it" }, header = "Last 7 days") { Text(it) }
            }
        }

        composeRule.onNodeWithText("Last 7 days").assertIsDisplayed()
        composeRule.onNodeWithText("Entry 4").assertDoesNotExist()
    }

    /**
     * What an unread setting resolves to, which is the whole of the no-flash
     * guarantee.
     *
     * The settings row arrives a frame or two after the window does, so the theme
     * is asked for a scheme before there is a stored answer. If `null` resolved
     * any way other than [ThemeMode.SYSTEM]'s way, every launch would paint one
     * scheme and repaint in the other -- visible, and worst for the reader whose
     * choice differs from their phone, which is the reader this setting exists
     * for.
     */
    @Test
    fun `an unread theme setting resolves the same way system does`() {
        val resolved = mutableMapOf<String, Boolean>()
        render {
            resolved["light"] = ThemeMode.LIGHT.resolvedDark()
            resolved["dark"] = ThemeMode.DARK.resolvedDark()
            resolved["system"] = ThemeMode.SYSTEM.resolvedDark()
            resolved["unread"] = (null as ThemeMode?).resolvedDark()
        }

        // The two explicit modes ignore the phone entirely, which is the point of
        // having them at all.
        assertEquals(false, resolved["light"])
        assertEquals(true, resolved["dark"])
        assertEquals(resolved["system"], resolved["unread"])
    }

    /**
     * The scatter's canvas arithmetic, which only runs under a real layout pass.
     *
     * Three shapes, and the last two are the ones that crash rather than look
     * wrong: an empty list has no min or max to scale against, and a single point
     * gives a span of zero to divide by. Both are reachable on a first run and
     * neither is visible to a pure-JVM test of the fit.
     */
    @Test
    fun `the scatter draws a cloud, a fit and the zero rules`() {
        val points =
            (0..7).map {
                val eaten = 1_800f + it * 200f
                ScatterPoint(
                    date = java.time.LocalDate.of(2026, 1, 1).plusDays(it * 7L),
                    x = eaten,
                    // Crosses zero inside the plotted span, so both zero rules
                    // are on the canvas and the fit runs edge to edge past them.
                    y = (2_800f - eaten) / 7.7f,
                )
            }

        render {
            ScatterChart(
                points = points,
                xLabel = "Calories eaten (kcal)",
                yLabel = "Weight lost (g/day)",
                fit = EnergyBalance.fit(points),
                pointColor = androidx.compose.ui.graphics.Color.Blue,
                fitColor = androidx.compose.ui.graphics.Color.Red,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // The axis names are the only text on this card; the canvas itself is a
        // blank to the tree, which is what the spoken description is for.
        composeRule.onNodeWithText("↑ Weight lost (g/day)").assertIsDisplayed()
        composeRule.onNodeWithText("Calories eaten (kcal) →").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/screenshots/metabolic-scatter.png")
    }

    @Test
    fun `an empty scatter says so instead of dividing by nothing`() {
        render {
            ScatterChart(
                points = emptyList(),
                xLabel = "Calories eaten (kcal)",
                yLabel = "Weight lost (g/day)",
                fit = null,
                pointColor = androidx.compose.ui.graphics.Color.Blue,
                fitColor = androidx.compose.ui.graphics.Color.Red,
                modifier = Modifier.fillMaxSize(),
            )
        }

        composeRule.onNodeWithText("Not enough days with both figures recorded").assertIsDisplayed()
    }

    @Test
    fun `a single point still gets a plot to sit in`() {
        // One point has no span on either axis. Left alone that is a divide by
        // zero in the pixel mapping, which is a crash rather than a bad-looking
        // chart -- and one point is what this card holds after a reader's first
        // week.
        render {
            ScatterChart(
                points =
                    listOf(ScatterPoint(java.time.LocalDate.of(2026, 1, 1), x = 2_400f, y = 60f)),
                xLabel = "Calories eaten (kcal)",
                yLabel = "Weight lost (g/day)",
                // No fit either: one point is below MIN_POINTS, so this is the
                // combination a real first week actually produces.
                fit = null,
                pointColor = androidx.compose.ui.graphics.Color.Blue,
                fitColor = androidx.compose.ui.graphics.Color.Red,
                modifier = Modifier.fillMaxSize(),
            )
        }

        composeRule.onNodeWithText("↑ Weight lost (g/day)").assertIsDisplayed()
    }
}
