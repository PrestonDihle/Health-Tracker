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
import com.prestondihle.healthtracker.data.WaistEntry
import com.prestondihle.healthtracker.data.WeightEntry
import com.prestondihle.healthtracker.data.WeightSubGoal
import com.prestondihle.healthtracker.domain.AftEvent
import com.prestondihle.healthtracker.domain.AftScorecard
import com.prestondihle.healthtracker.domain.AftScoring
import com.prestondihle.healthtracker.domain.RunBreakdown
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
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * How far back the trends charts reach.
 *
 * A week is what a change made on Monday has actually had time to show up in; a
 * quarter is where a body measurement's real slope separates from the noise of
 * daily weighing. The two in between exist because most questions asked here are
 * neither.
 */
enum class TrendsRange(val label: String, val days: Long) {
    WEEK("7 days", 7),
    TWO_WEEKS("14 days", 14),
    MONTH("30 days", 30),
    THREE_MONTHS("90 days", 90),
}

/** Fallback max heart rate when the profile has neither a figure nor an age to derive one from. */
private const val DEFAULT_MAX_HEART_RATE = 190

/**
 * How far back the two-mile projection looks for a qualifying run.
 *
 * A quarter, matching the widest trend window. Long enough that somebody who
 * races rarely still has something to project from, short enough that the figure
 * is about the shape they are in now -- a best from a year ago is not a
 * projection of anything, and would sit on the card looking exactly like one.
 */
private const val PROJECTION_WINDOW_DAYS = 90L

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
        return days.map { DayPoint(it, byDate[it]) }
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
     * rows for a day means none were done, not that the count is unknown.
     */
    fun repSeries(movement: MovementType): List<DayPoint> {
        val byDate =
            exerciseSets
                .filter { it.movement == movement }
                .groupBy { it.timestamp.atZone(zoneId).toLocalDate() }
                .mapValues { (_, sets) -> sets.sumOf { it.reps }.toFloat() }
        return days.map { DayPoint(it, byDate[it] ?: 0f) }
    }

    /**
     * Macro calories per day as protein / carbs / fat stacks, one bar per day.
     *
     * Converted from grams at 4/4/9 kcal so the stack height is total energy and
     * each band is its real share -- fat is barely a third of the grams but
     * often half the calories, which stacking grams would hide.
     */
    val macroBars: List<StackedBar>
        get() {
            val byDate = snapshots.associateBy { it.date }
            return days.map { date ->
                val snapshot = byDate[date]
                StackedBar(
                    date = date,
                    segments =
                        listOf(
                            (snapshot?.proteinGrams ?: 0f) * 4f,
                            (snapshot?.carbGrams ?: 0f) * 4f,
                            (snapshot?.fatGrams ?: 0f) * 9f,
                        ),
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
                    combine(repository.getUserGoals(), repository.getWeightSubGoals()) {
                        goals,
                        subGoals ->
                        goals to subGoals
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
                val start = end.minusDays(selected.days - 1)
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
