package com.prestondihle.healthtracker.ui.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.data.AftAttempt
import com.prestondihle.healthtracker.data.AftLane
import com.prestondihle.healthtracker.data.BloodPressureReading
import com.prestondihle.healthtracker.data.DailyLog
import com.prestondihle.healthtracker.data.ExerciseSet
import com.prestondihle.healthtracker.data.GripStrengthEntry
import com.prestondihle.healthtracker.data.HealthDaySnapshot
import com.prestondihle.healthtracker.data.HydrationEntry
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.data.Sex
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.data.UserSettings
import com.prestondihle.healthtracker.data.WaistEntry
import com.prestondihle.healthtracker.data.WeightEntry
import com.prestondihle.healthtracker.data.WeightSubGoal
import com.prestondihle.healthtracker.domain.AftEvent
import com.prestondihle.healthtracker.domain.AftScorecard
import com.prestondihle.healthtracker.domain.AftScoring
import com.prestondihle.healthtracker.domain.MealClockTimes
import com.prestondihle.healthtracker.domain.MealDuplicates
import com.prestondihle.healthtracker.domain.MealResponses
import com.prestondihle.healthtracker.domain.Readiness
import com.prestondihle.healthtracker.domain.ReadinessFacts
import com.prestondihle.healthtracker.domain.RunBreakdown
import com.prestondihle.healthtracker.domain.ScoredMeal
import com.prestondihle.healthtracker.domain.TrainingVolume
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.ui.components.DayPoint
import com.prestondihle.healthtracker.ui.components.StackedBar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.prestondihle.healthtracker.repository.TrackerRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * How far back the trends charts reach, and how wide one slot is once they do.
 *
 * A week is what a change made on Monday has actually had time to show up in; a
 * quarter is where a body measurement's real slope separates from the noise of
 * daily weighing. The two in between exist because most questions asked here are
 * neither.
 *
 * The two long ranges are [weekly] because a day has stopped being a slot worth
 * drawing at that width: 365 bars across a phone are a third of a pixel each,
 * and a year of daily weights is a band of noise with the trend somewhere inside
 * it. Aggregating there is not a compromise made for the renderer -- it is the
 * only reading the width supports.
 */
enum class TrendsRange(val label: String, val days: Long, val weekly: Boolean = false) {
    WEEK("7 days", 7),
    TWO_WEEKS("14 days", 14),
    MONTH("30 days", 30),
    THREE_MONTHS("90 days", 90),
    SIX_MONTHS("180 days", 180, weekly = true),
    YEAR("365 days", 365, weekly = true),
    ;

    /** How far back the two live-read cards go, which is not always [days]. */
    val cappedDays: Long
        get() = days.coerceAtMost(LIVE_READ_MAX_DAYS)

    /**
     * What one of those cards should call its own window.
     *
     * A card drawing ninety days under a chip that says 365 has to print the
     * ninety. Left with the chip's label it would claim a year it never read,
     * and a chart that simply stops short of the others reads as a card that
     * failed rather than one that was capped on purpose.
     */
    val effectiveLabel: String
        get() = if (cappedDays < days) "$cappedDays days" else label
}

/** Fallback max heart rate when the profile has neither a figure nor an age to derive one from. */
private const val DEFAULT_MAX_HEART_RATE = 190

/**
 * How far back the two-mile projection looks for a qualifying run.
 *
 * A quarter. Long enough that somebody who races rarely still has something to
 * project from, short enough that the figure is about the shape they are in now
 * -- a best from a year ago is not a projection of anything, and would sit on
 * the card looking exactly like one.
 */
private const val PROJECTION_WINDOW_DAYS = 90L

/**
 * The widest window the two live-read cards will open, however wide a range is
 * chosen.
 *
 * A quarter, which is exactly the widest range that existed before 180 and 365
 * were added -- so both cards behave at every old chip precisely as they did,
 * and the two new ones simply do not reach them.
 *
 * They are capped for two different reasons that happen to land on one figure.
 * The runs chart costs a raw heart-rate read *per session* ([getRunBreakdowns]),
 * so a year of running is a hundred and fifty paginated round trips to draw a
 * hundred and fifty bars on a chart a phone is four hundred pixels wide -- the
 * same argument `HEART_RATE_SYNC_HORIZON` already makes about raw samples, and
 * unreadable even if it were free. The meal ranking is cheap per row but reads
 * every glucose sample in its window, which at CGM resolution is a hundred
 * thousand rows pulled into memory to print five lines. And a dinner from last
 * spring is not something to act on, which is the [PROJECTION_WINDOW_DAYS]
 * argument arriving at a different card.
 */
private const val LIVE_READ_MAX_DAYS = 90L

/**
 * How many meals the biggest-responses ranking lists.
 *
 * Five is a ranking; twenty is the meal list again, sorted differently. The card
 * is answering "what should I look at", and a list long enough to need scrolling
 * has stopped answering it.
 */
private const val RANKED_MEALS = 5

/**
 * The meals that moved the blood sugar most, over the chosen trends window.
 *
 * [mealCount] and [hasAnyReadings] exist to tell the three empty cases apart,
 * which is the same distinction `GlucoseReportState` draws and for the same
 * reason: "nothing eaten in this window", "no CGM here at all" and "meals and
 * readings both present, but none of them lined up well enough to score" want
 * three different sentences, and only one of them is something to fix.
 */
/**
 * This week's training so far, grouped by kind.
 *
 * An empty [volumes] on its own cannot say whether nothing was trained or
 * nothing was granted, and the card does not try to guess -- it says the week
 * has nothing in it yet, which is true either way and is the only claim the data
 * supports.
 */
data class TrainingWeekState(
    val volumes: List<TrainingVolume> = emptyList(),
    val weekStart: LocalDate = LocalDate.now(),
)

data class MealResponseState(
    val ranked: List<ScoredMeal> = emptyList(),
    val mealCount: Int = 0,
    val hasAnyReadings: Boolean = false,
    val range: TrendsRange = TrendsRange.TWO_WEEKS,
    val zoneId: ZoneId = ZoneId.systemDefault(),
)

/**
 * The AFT attempts and the profile they are scored against.
 *
 * Holds the raw attempts and computes every score on read. The profile moves --
 * a birthday changes the age band, the lane is a setting, sex may be filled in
 * after the first attempt was logged -- and a stored score would be a claim
 * about a profile that has since changed, indistinguishable by eye from a
 * current one.
 */
data class AftUiState(
    val attempts: List<AftAttempt> = emptyList(),
    val lane: AftLane = AftLane.GENERAL,
    val ageYears: Int? = null,
    val sex: Sex = Sex.UNSPECIFIED,
    /**
     * Quickest two miles the recent runs imply, or null when none went that far.
     *
     * An average over a whole run rather than a two-mile effort, so it reads
     * slower than a real one and is presented as a projection throughout.
     */
    val projectedTwoMileSeconds: Int? = null,
    val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    /** False when the profile is too thin to place the Soldier on a scale at all. */
    val canScore: Boolean
        get() = AftScoring.canScore(ageYears, sex, lane)

    /**
     * What the recent runs would score on the two-mile event, if run that way.
     *
     * A model, and labelled one wherever it appears. It exists for the months
     * between record tests, when the only evidence available is ordinary runs.
     */
    val projectedTwoMileScore: Int?
        get() =
            projectedTwoMileSeconds?.let {
                AftScoring.score(AftEvent.TWO_MILE_RUN, it, ageYears, sex, lane)
            }

    /** The most recent attempt, which is the one the card leads with. */
    val latest: AftAttempt?
        get() = attempts.lastOrNull()

    fun scorecardFor(attempt: AftAttempt): AftScorecard =
        AftScoring.scorecard(attempt, ageYears, sex, lane)

    val latestScorecard: AftScorecard?
        get() = latest?.let { scorecardFor(it) }

    /** What this event's 60-point row asks for, so an entry stepper can open there. */
    fun minimumFor(event: AftEvent): Int? = AftScoring.minimumFor(event, ageYears, sex, lane)

    /**
     * Total score per attempt, for the trend.
     *
     * Only finished attempts are plotted. A part-logged test day would draw as a
     * collapse in fitness rather than as a test that was not finished, which is
     * exactly the reading a chart cannot argue its way out of.
     */
    val totals: List<Pair<Instant, Float>>
        get() =
            attempts
                .map { it to scorecardFor(it) }
                .filter { (_, card) -> card.isComplete }
                .map { (attempt, card) ->
                    attempt.date.atStartOfDay(zoneId).toInstant() to card.total.toFloat()
                }
}

data class TrendsUiState(
    val range: TrendsRange = TrendsRange.TWO_WEEKS,
    val startDate: LocalDate = LocalDate.now().minusDays(13),
    val endDate: LocalDate = LocalDate.now(),
    val dailyLogs: List<DailyLog> = emptyList(),
    val snapshots: List<HealthDaySnapshot> = emptyList(),
    val weights: List<WeightEntry> = emptyList(),
    val waists: List<WaistEntry> = emptyList(),
    val grips: List<GripStrengthEntry> = emptyList(),
    val hydration: List<HydrationEntry> = emptyList(),
    val exerciseSets: List<ExerciseSet> = emptyList(),
    val bloodPressure: List<BloodPressureReading> = emptyList(),
    val goals: UserGoals = UserGoals(),
    /** Staged weights on the way to the goal, heaviest first. */
    val weightSubGoals: List<WeightSubGoal> = emptyList(),
    /** Carried for the body composition screen, which needs a height. */
    val settings: UserSettings = UserSettings(),
    val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    /**
     * Every date in the range, oldest first, including days with nothing logged.
     *
     * Charts are drawn one slot per day off this list rather than one slot per
     * reading, which is what lets a bar be labelled with the date it belongs to.
     */
    val days: List<LocalDate>
        get() = (0..ChronoUnit.DAYS.between(startDate, endDate)).map { startDate.plusDays(it) }

    /** The week a date belongs to, named by its first day. */
    private fun weekStartOf(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(settings.weekStartsOn))

    /**
     * The slots the charts actually draw: one per day, or one per week when
     * [TrendsRange.weekly].
     *
     * Keyed on `UserSettings.weekStartsOn`, the same setting the blood sugar
     * summary and the training card already read, so every week in the app
     * starts on the same morning.
     *
     * The weeks at both ends are partial -- the range is a count of days back
     * from today and lands wherever it lands -- and that is survivable only
     * because every bucket below is a *mean*. Summed, the newest bucket would
     * shrink through the week and reset every Monday, drawing the reader a
     * collapse on a chart whose whole job is showing whether anything is
     * actually moving.
     */
    val buckets: List<LocalDate>
        get() = if (!range.weekly) days else days.map(::weekStartOf).distinct()

    /**
     * A chart's subtitle, saying what one slot is once it has stopped being a day.
     *
     * Every trend subtitle here already carries the unit, and at these ranges the
     * unit has genuinely changed -- a point is no longer Tuesday's weight but the
     * mean of the week Tuesday was in. Left unsaid, a reader comparing a bar
     * against the goal line beside it would be reading an average as a day, and
     * the chart gives them nothing to notice the difference by.
     */
    fun subtitle(base: String): String = if (range.weekly) "$base, weekly average" else base

    /**
     * Folds one point per day into one point per slot, averaging the week.
     *
     * A week's value is the mean of the days in it that hold one, and that
     * single rule is doing two jobs. For anything with a daily goal -- steps,
     * sleep, calories -- a mean per day is on the same scale the goal line is
     * drawn at, so the existing reference lines stay honest without a second
     * axis or a seven-times-larger target; a weekly *sum* would put the bars a
     * decimal place above their own goal. For anything merely measured --
     * weight, waist, resting heart rate -- the mean is simply what the week
     * weighed, over the mornings somebody stepped on the scale.
     *
     * Days with no reading are left out of the divisor rather than counted as
     * zero, which is ground rule 6 arriving at the arithmetic: a week the watch
     * synced on three days holds three days of evidence, and dividing it by
     * seven would draw a fortnight of illness. A week with nothing at all in it
     * is null for the same reason, and breaks the line rather than touching the
     * floor.
     */
    private fun bucketed(daily: List<DayPoint>): List<DayPoint> {
        if (!range.weekly) return daily
        val byWeek = daily.groupBy { weekStartOf(it.date) }
        return buckets.map { start ->
            val measured = byWeek[start].orEmpty().mapNotNull { it.value }
            DayPoint(start, measured.takeIf { it.isNotEmpty() }?.average()?.toFloat())
        }
    }

    /**
     * One point per day, null on days the row is missing or the field unset.
     *
     * Null means "no reading", which for anything measured -- steps, sleep, heart
     * rate, a mood score -- is the truth and must not be drawn as a zero.
     * Hand-counted totals are the exception and pass zero explicitly.
     */
    private fun <T> series(
        rows: List<T>,
        dateOf: (T) -> LocalDate,
        valueOf: (T) -> Float?,
    ): List<DayPoint> {
        val byDate = rows.associate { dateOf(it) to valueOf(it) }
        return bucketed(days.map { DayPoint(it, byDate[it]) })
    }

    fun snapshotSeries(valueOf: (HealthDaySnapshot) -> Float?): List<DayPoint> =
        series(snapshots, { it.date }, valueOf)

    fun logSeries(valueOf: (DailyLog) -> Float?): List<DayPoint> =
        series(dailyLogs, { it.date }, valueOf)

    /** [convert] receives centimetres, so the inch conversion stays at the display boundary. */
    fun waistSeries(convert: (Float) -> Float): List<DayPoint> =
        series(waists, { it.date }) { convert(it.waistCm) }

    /** [convert] receives kilograms. */
    fun weightSeries(convert: (Float) -> Float): List<DayPoint> =
        series(weightByDay, { it.first }) { convert(it.second) }

    /**
     * One hand's grip per day in pounds, null on days it was not measured.
     *
     * Null rather than zero: grip is measured every few days at most, and a zero
     * would draw as a total loss of strength on every day in between.
     */
    fun gripSeries(dominant: Boolean): List<DayPoint> =
        series(grips, { it.date }) {
            val kg = if (dominant) it.dominantKg else it.nonDominantKg
            kg?.let(Units::kgToLbs)
        }

    /**
     * Daily rep totals for one movement, zero on a day with no logged sets.
     *
     * Zero rather than null because a set is only ever recorded by logging it: no
     * rows for a day means none were done, not that the count is unknown. Those
     * zeroes are readings and count into a weekly mean, which is what makes the
     * bucket "reps per day" rather than "reps per day trained" -- a week with one
     * hard session in it did not average that session's count.
     */
    fun repSeries(movement: MovementType): List<DayPoint> {
        val byDate =
            exerciseSets
                .filter { it.movement == movement }
                .groupBy { it.timestamp.atZone(zoneId).toLocalDate() }
                .mapValues { (_, sets) -> sets.sumOf { it.reps }.toFloat() }
        return bucketed(days.map { DayPoint(it, byDate[it] ?: 0f) })
    }

    /**
     * Macro calories per slot as protein / carbs / fat stacks.
     *
     * Converted from grams at 4/4/9 kcal so the stack height is total energy and
     * each band is its real share -- fat is barely a third of the grams but
     * often half the calories, which stacking grams would hide.
     */
    val macroBars: List<StackedBar>
        get() {
            val byDate = snapshots.associateBy { it.date }
            fun segmentsOn(date: LocalDate): List<Float> {
                val snapshot = byDate[date]
                return listOf(
                    (snapshot?.proteinGrams ?: 0f) * 4f,
                    (snapshot?.carbGrams ?: 0f) * 4f,
                    (snapshot?.fatGrams ?: 0f) * 9f,
                )
            }
            if (!range.weekly) return days.map { StackedBar(it, segmentsOn(it)) }

            // Averaged over the days that recorded food, not over the seven. A
            // day with no nutrition on its snapshot is a day nothing was read
            // from, and it draws as an empty bar daily where that is plainly
            // what it is -- folded into a week at a seventh of its weight it
            // would instead report eating that never stopped and calories that
            // halved, which is the one shape a macro chart must not invent.
            val eaten =
                days
                    .filter { date ->
                        val snapshot = byDate[date]
                        snapshot?.proteinGrams != null ||
                            snapshot?.carbGrams != null ||
                            snapshot?.fatGrams != null
                    }
                    .groupBy(::weekStartOf)
            return buckets.map { start ->
                val dates = eaten[start].orEmpty()
                if (dates.isEmpty()) return@map StackedBar(start, listOf(0f, 0f, 0f))
                val stacks = dates.map(::segmentsOn)
                StackedBar(
                    date = start,
                    segments = List(3) { band -> stacks.sumOf { it[band].toDouble() }.toFloat() / dates.size },
                )
            }
        }

    /**
     * Weight per day in kilograms, oldest first, combining hand-entered values
     * with those synced from Health Connect.
     *
     * A manual entry wins on any day that has both: it was typed deliberately,
     * whereas the synced figure is whatever a scale last broadcast.
     */
    val weightByDay: List<Pair<LocalDate, Float>>
        get() {
            val synced = snapshots.mapNotNull { snap -> snap.weightKg?.let { snap.date to it } }
            val manual = weights.map { it.date to it.weightKg }
            return (synced + manual).toMap().toSortedMap().toList()
        }
}

class TrendsViewModel(
    private val repository: TrackerRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val range = MutableStateFlow(TrendsRange.TWO_WEEKS)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<TrendsUiState> =
        range
            .flatMapLatest { selected ->
                val end = LocalDate.now(zoneId)
                val start = end.minusDays(selected.days - 1)

                combine(
                    repository.getDailyLogs(start, end),
                    repository.getHealthSnapshots(start, end),
                    combine(
                        repository.getWeights(start, end),
                        repository.getWaists(start, end),
                        repository.getGripStrengths(start, end),
                    ) { weights, waists, grips ->
                        Triple(weights, waists, grips)
                    },
                    combine(
                        repository.getHydrationBetween(start, end),
                        repository.getExerciseSetsBetween(start, end),
                        repository.getBloodPressureBetween(start, end),
                    ) { hydration, sets, bloodPressure ->
                        Triple(hydration, sets, bloodPressure)
                    },
                    // Paired with the goals rather than given a source of its own:
                    // the outer combine's typed overloads stop at five, and a
                    // staged weight is a goal in every sense but the table it
                    // lives in.
                    combine(
                        repository.getUserGoals(),
                        repository.getWeightSubGoals(),
                        repository.getUserSettings(),
                    ) { goals, subGoals, settings ->
                        Triple(goals, subGoals, settings)
                    },
                ) { logs, snapshots, body, activity, targets ->
                    TrendsUiState(
                        range = selected,
                        startDate = start,
                        endDate = end,
                        dailyLogs = logs,
                        snapshots = snapshots,
                        weights = body.first,
                        waists = body.second,
                        grips = body.third,
                        hydration = activity.first,
                        exerciseSets = activity.second,
                        bloodPressure = activity.third,
                        goals = targets.first ?: UserGoals(),
                        weightSubGoals = targets.second,
                        settings = targets.third ?: UserSettings(),
                        zoneId = zoneId,
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = TrendsUiState(),
            )

    /**
     * The runs in the selected window, zoned against the profile's max heart rate.
     *
     * Kept apart from [uiState] because it is fed by a live Health Connect read
     * rather than the cached Room flows the rest of the trends draw from, and
     * re-runs whenever the window or the max heart rate changes -- the latter so
     * editing the figure in Settings re-colours the bars without a resync.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val runs: StateFlow<List<RunBreakdown>> =
        combine(range, repository.getUserSettings()) { selected, settings -> selected to settings }
            .mapLatest { (selected, settings) ->
                val end = LocalDate.now(zoneId)
                val start = end.minusDays(selected.cappedDays - 1)
                val maxHeartRate =
                    settings?.maxHeartRateBpm
                        ?: settings?.ageYears?.let { 220 - it }
                        ?: DEFAULT_MAX_HEART_RATE
                repository.getRunBreakdowns(
                    from = start.atStartOfDay(zoneId).toInstant(),
                    to = end.plusDays(1).atStartOfDay(zoneId).toInstant(),
                    maxHeartRate = maxHeartRate,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    /**
     * Every AFT attempt, scored against the profile as it stands right now.
     *
     * Deliberately outside [uiState] and outside [TrendsRange]. A record test
     * happens twice a year, so a 7-to-90-day window would show one attempt or
     * none -- the question this chart answers is whether the score is moving
     * across tests, and that span is the attempts themselves.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val aft: StateFlow<AftUiState> =
        combine(repository.getAftAttempts(), repository.getUserSettings()) { attempts, settings ->
                attempts to settings
            }
            .mapLatest { (attempts, settings) ->
                val now = Instant.now()
                AftUiState(
                    attempts = attempts,
                    lane = settings?.aftLane ?: AftLane.GENERAL,
                    ageYears = settings?.ageYears,
                    sex = settings?.sex ?: Sex.UNSPECIFIED,
                    projectedTwoMileSeconds =
                        repository.getBestTwoMileSeconds(
                            from = now.minus(Duration.ofDays(PROJECTION_WINDOW_DAYS)),
                            to = now,
                        ),
                    zoneId = zoneId,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AftUiState(zoneId = zoneId),
            )

    /**
     * The meals that moved the blood sugar most over the chosen window.
     *
     * Its own flow rather than a field on [uiState] for a plain reason: that
     * combine is already at the five-source limit its typed overload allows, and
     * pairing meals with glucose to get under it would put two unrelated things
     * in one tuple. It still keys on [range], unlike `aft`, because "which meals
     * spiked me" is exactly the question a window is being chosen for.
     *
     * Duplicate records are collapsed first, the same rule the meal list uses. A
     * source that writes one meal three times would otherwise fill a five-row
     * ranking with three copies of one dinner.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val mealResponses: StateFlow<MealResponseState> =
        range
            .flatMapLatest { selected ->
                val end = LocalDate.now(zoneId)
                val start = end.minusDays(selected.cappedDays - 1)
                combine(
                    repository.getMealsSince(start.atStartOfDay(zoneId).toInstant()),
                    repository.getBloodSugarBetween(start, end),
                ) { meals, readings ->
                    val distinct = MealDuplicates.collapse(meals.sortedBy { it.id })
                    val stamped = MealClockTimes.stampedTimesOfDay(distinct, zoneId)
                    MealResponseState(
                        ranked =
                            MealResponses.rank(
                                meals = distinct,
                                readings = readings.map { it.timestamp to it.mgDl },
                                hasClockTime = {
                                    MealClockTimes.hasClockTime(it, stamped, zoneId)
                                },
                                limit = RANKED_MEALS,
                            ),
                        mealCount = distinct.size,
                        hasAnyReadings = readings.isNotEmpty(),
                        range = selected,
                        zoneId = zoneId,
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = MealResponseState(zoneId = zoneId),
            )

    /**
     * This week's training, grouped by kind.
     *
     * A week rather than [TrendsRange], and outside [uiState] like `aft` for a
     * related reason: the question is "have I trained enough *this week*", which
     * a 90-day window answers by burying. The week's first day comes from
     * `UserSettings.weekStartsOn`, the same setting the blood sugar summary reads,
     * so the two cards never disagree about when the week began.
     *
     * Ends at *now* rather than at the end of the week, so the figure is what has
     * been done rather than what a full week would hold -- the same choice the CGM
     * windows make, and for the same reason.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val training: StateFlow<TrainingWeekState> =
        repository
            .getUserSettings()
            .mapLatest { settings ->
                val weekStart =
                    LocalDate.now(zoneId)
                        .with(
                            TemporalAdjusters.previousOrSame(
                                settings?.weekStartsOn ?: DayOfWeek.MONDAY
                            )
                        )
                TrainingWeekState(
                    volumes =
                        repository.getTrainingVolume(
                            from = weekStart.atStartOfDay(zoneId).toInstant(),
                            to = Instant.now(),
                        ),
                    weekStart = weekStart,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = TrainingWeekState(),
            )

    /**
     * This morning's two facts, against the trailing month.
     *
     * Its own flow and its own fixed window, because the baseline is thirty days
     * whatever the trends chips say -- at the 7-day range there would not be
     * enough history to have a baseline at all, and a line that vanished when the
     * reader changed a chart range would look broken rather than principled.
     *
     * Everything it needs is already cached, so this costs no sync: the snapshots
     * are what the daily read has been writing all along, and the goal is a
     * settings row.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val readiness: StateFlow<Readiness> =
        combine(
                repository.getHealthSnapshots(
                    LocalDate.now(zoneId).minusDays(ReadinessFacts.BASELINE_DAYS.toLong()),
                    LocalDate.now(zoneId),
                ),
                repository.getUserGoals(),
            ) { snapshots, goals ->
                ReadinessFacts.on(
                    today = LocalDate.now(zoneId),
                    restingByDay =
                        snapshots.mapNotNull { s -> s.restingHeartRateBpm?.let { s.date to it } }
                            .toMap(),
                    sleepByDay =
                        snapshots.mapNotNull { s -> s.sleepMinutes?.let { s.date to it } }.toMap(),
                    sleepGoalMinutes = goals?.sleepMinutesGoal,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = Readiness(null, null, null, null),
            )

    fun addAftAttempt(attempt: AftAttempt) {
        viewModelScope.launch { repository.addAftAttempt(attempt) }
    }

    fun updateAftAttempt(attempt: AftAttempt) {
        viewModelScope.launch { repository.updateAftAttempt(attempt) }
    }

    fun deleteAftAttempt(attempt: AftAttempt) {
        viewModelScope.launch { repository.deleteAftAttempt(attempt) }
    }

    fun setRange(selected: TrendsRange) {
        range.value = selected
    }

    companion object {
        fun provideFactory(repository: TrackerRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TrendsViewModel(repository) as T
            }
    }
}
