package com.prestondihle.healthtracker.ui.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.data.BloodPressureReading
import com.prestondihle.healthtracker.data.DailyLog
import com.prestondihle.healthtracker.data.ExerciseSet
import com.prestondihle.healthtracker.data.GripStrengthEntry
import com.prestondihle.healthtracker.data.HealthDaySnapshot
import com.prestondihle.healthtracker.data.HydrationEntry
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.data.WaistEntry
import com.prestondihle.healthtracker.data.WeightEntry
import com.prestondihle.healthtracker.data.WeightSubGoal
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.ui.components.DayPoint
import com.prestondihle.healthtracker.ui.components.StackedBar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import com.prestondihle.healthtracker.repository.TrackerRepository
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
