package com.prestondihle.healthtracker.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.data.BloodPressureReading
import com.prestondihle.healthtracker.data.BloodSugarReading
import com.prestondihle.healthtracker.data.DailyLog
import com.prestondihle.healthtracker.data.ExerciseSet
import com.prestondihle.healthtracker.data.HealthDaySnapshot
import com.prestondihle.healthtracker.data.HydrationEntry
import com.prestondihle.healthtracker.data.KetoneReading
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.repository.TrackerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Default waist when nothing has ever been measured: 42 inches. */
private const val DEFAULT_WAIST_CM = 106.68f

data class HistoryUiState(
    val date: LocalDate = LocalDate.now(),
    val log: DailyLog = DailyLog(LocalDate.now()),
    val snapshot: HealthDaySnapshot? = null,
    val waistCm: Float = DEFAULT_WAIST_CM,
    val hydration: List<HydrationEntry> = emptyList(),
    val exerciseSets: List<ExerciseSet> = emptyList(),
    val bloodPressures: List<BloodPressureReading> = emptyList(),
    val glucose: List<BloodSugarReading> = emptyList(),
    val ketones: List<KetoneReading> = emptyList(),
) {
    val hydrationMl: Int
        get() = hydration.sumOf { it.milliliters }

    fun reps(movement: MovementType): Int =
        exerciseSets.filter { it.movement == movement }.sumOf { it.reps }

    val isToday: Boolean
        get() = date == LocalDate.now()
}

/**
 * Backfilling and correcting past days. Wellness covers today; this is for
 * the day you forgot to log.
 */
class HistoryViewModel(private val repository: TrackerRepository) : ViewModel() {

    private val selectedDate = MutableStateFlow(LocalDate.now())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HistoryUiState> =
        selectedDate
            .flatMapLatest { date ->
                combine(
                    combine(
                        repository.getDailyLog(date),
                        repository.getHealthSnapshot(date),
                        repository.getLatestWaistOnOrBefore(date),
                    ) { log, snapshot, waist ->
                        Triple(log, snapshot, waist?.waistCm)
                    },
                    combine(
                        repository.getHydrationForDate(date),
                        repository.getExerciseSetsForDate(date),
                    ) { hydration, sets ->
                        hydration to sets
                    },
                    repository.getBloodPressureForDate(date),
                    repository.getBloodSugarForDate(date),
                    repository.getKetonesForDate(date),
                ) { core, activity, bps, glucose, ketones ->
                    HistoryUiState(
                        date = date,
                        log = core.first ?: DailyLog(date),
                        snapshot = core.second,
                        waistCm = core.third ?: DEFAULT_WAIST_CM,
                        hydration = activity.first,
                        exerciseSets = activity.second,
                        bloodPressures = bps,
                        glucose = glucose,
                        ketones = ketones,
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = HistoryUiState(),
            )

    fun selectDate(date: LocalDate) {
        // Nothing has happened in the future, so refuse to navigate there.
        if (!date.isAfter(LocalDate.now())) selectedDate.value = date
    }

    fun setMood(vibe: Int, energy: Int, focus: Int) {
        updateLog { it.copy(vibe = vibe, energy = energy, focus = focus) }
    }

    fun setPages(pages: Int) {
        updateLog { it.copy(bookPagesRead = pages) }
    }

    fun setSleepQuality(quality: Int) {
        updateLog { it.copy(sleepQuality = quality) }
    }

    private fun updateLog(transform: (DailyLog) -> DailyLog) {
        viewModelScope.launch {
            val date = selectedDate.value
            val current = repository.getDailyLog(date).first() ?: DailyLog(date)
            repository.upsertDailyLog(transform(current))
        }
    }

    fun setWaistCm(cm: Float) {
        viewModelScope.launch { repository.setWaistCm(selectedDate.value, cm) }
    }

    fun syncHealthForDate() {
        viewModelScope.launch { repository.syncHealthData(selectedDate.value) }
    }

    companion object {
        fun provideFactory(repository: TrackerRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HistoryViewModel(repository) as T
            }
    }
}
