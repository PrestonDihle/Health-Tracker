package com.prestondihle.healthtracker.ui.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.data.BloodPressureReading
import com.prestondihle.healthtracker.data.DailyLog
import com.prestondihle.healthtracker.data.ExerciseSet
import com.prestondihle.healthtracker.data.HealthDaySnapshot
import com.prestondihle.healthtracker.data.HydrationEntry
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.data.WaistEntry
import com.prestondihle.healthtracker.data.WeightEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import com.prestondihle.healthtracker.repository.TrackerRepository
import java.time.LocalDate

enum class TrendsRange(val label: String, val days: Long) {
    TWO_WEEKS("14 days", 14),
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
    val hydration: List<HydrationEntry> = emptyList(),
    val exerciseSets: List<ExerciseSet> = emptyList(),
    val bloodPressure: List<BloodPressureReading> = emptyList(),
    val goals: UserGoals = UserGoals(),
) {
    /** Daily rep totals for one movement, ordered oldest first. */
    fun repsByDay(movement: MovementType): List<Pair<LocalDate, Int>> =
        exerciseSets
            .filter { it.movement == movement }
            .groupBy { it.timestamp.atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
            .map { (date, sets) -> date to sets.sumOf { it.reps } }
            .sortedBy { it.first }

    /**
     * Macro calories per day, oldest first, as protein / carbs / fat triples.
     *
     * Converted from grams at 4/4/9 kcal so the stack height is total energy and
     * each band is its real share -- fat is barely a third of the grams but
     * often half the calories, which stacking grams would hide.
     */
    val macroCaloriesByDay: List<Triple<Float, Float, Float>>
        get() =
            snapshots
                .filter { it.proteinGrams != null || it.carbGrams != null || it.fatGrams != null }
                .map {
                    Triple(
                        (it.proteinGrams ?: 0f) * 4f,
                        (it.carbGrams ?: 0f) * 4f,
                        (it.fatGrams ?: 0f) * 9f,
                    )
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

class TrendsViewModel(private val repository: TrackerRepository) : ViewModel() {

    private val range = MutableStateFlow(TrendsRange.TWO_WEEKS)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<TrendsUiState> =
        range
            .flatMapLatest { selected ->
                val end = LocalDate.now()
                val start = end.minusDays(selected.days - 1)

                combine(
                    repository.getDailyLogs(start, end),
                    repository.getHealthSnapshots(start, end),
                    combine(repository.getWeights(start, end), repository.getWaists(start, end)) {
                        weights,
                        waists ->
                        weights to waists
                    },
                    combine(
                        repository.getHydrationBetween(start, end),
                        repository.getExerciseSetsBetween(start, end),
                        repository.getBloodPressureBetween(start, end),
                    ) { hydration, sets, bloodPressure ->
                        Triple(hydration, sets, bloodPressure)
                    },
                    repository.getUserGoals(),
                ) { logs, snapshots, body, activity, goals ->
                    TrendsUiState(
                        range = selected,
                        startDate = start,
                        endDate = end,
                        dailyLogs = logs,
                        snapshots = snapshots,
                        weights = body.first,
                        waists = body.second,
                        hydration = activity.first,
                        exerciseSets = activity.second,
                        bloodPressure = activity.third,
                        goals = goals ?: UserGoals(),
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
