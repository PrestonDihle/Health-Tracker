package com.prestondihle.healthtracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.data.UserSettings
import com.prestondihle.healthtracker.data.WeightSubGoal
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.health.StepSource
import com.prestondihle.healthtracker.repository.TrackerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val goals: UserGoals = UserGoals(),
    /** Staged weights on the way to the goal, heaviest first. */
    val weightSubGoals: List<WeightSubGoal> = emptyList(),
    /** Today's per-app step totals, loaded on demand. */
    val stepSources: List<StepSource> = emptyList(),
    val isLoadingStepSources: Boolean = false,
)

class SettingsViewModel(private val repository: TrackerRepository) : ViewModel() {

    private val stepSources = MutableStateFlow<List<StepSource>>(emptyList())
    private val loadingStepSources = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.getUserSettings(),
        repository.getUserGoals(),
        repository.getWeightSubGoals(),
        stepSources,
        loadingStepSources,
    ) { settings, goals, subGoals, sources, loading ->
        SettingsUiState(
            settings = settings ?: UserSettings(),
            goals = goals ?: UserGoals(),
            weightSubGoals = subGoals,
            stepSources = sources,
            isLoadingStepSources = loading,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    init {
        refreshStepSources()
    }

    /** Re-reads which apps wrote steps today, so a newly installed tracker shows up. */
    fun refreshStepSources() {
        viewModelScope.launch {
            loadingStepSources.value = true
            stepSources.value = repository.stepSources(LocalDate.now())
            loadingStepSources.value = false
        }
    }

    /**
     * Pins the app whose steps are trusted, or clears the pin to sum every source.
     *
     * Re-syncs immediately: the cached snapshot still holds the step count from
     * the old preference, and leaving it there would make the setting look
     * broken until the next manual refresh.
     */
    fun setPreferredStepsPackage(packageName: String?) {
        viewModelScope.launch {
            repository.upsertUserSettings(
                uiState.value.settings.copy(preferredStepsPackage = packageName)
            )
            repository.syncHealthData(LocalDate.now())
        }
    }

    fun saveSettings(settings: UserSettings) {
        viewModelScope.launch {
            repository.upsertUserSettings(settings)
        }
    }

    fun saveGoals(goals: UserGoals) {
        viewModelScope.launch {
            repository.upsertUserGoals(goals)
        }
    }

    /**
     * Stages a weight on the way to the goal.
     *
     * Takes pounds because that is what the stepper shows, and converts here so
     * the display boundary stays in one place. Adding one that already exists is
     * absorbed by the unique index rather than drawing two rules at the same
     * height.
     */
    fun addWeightSubGoalLbs(lbs: Float) {
        viewModelScope.launch { repository.addWeightSubGoalKg(Units.lbsToKg(lbs)) }
    }

    fun deleteWeightSubGoal(subGoal: WeightSubGoal) {
        viewModelScope.launch { repository.deleteWeightSubGoal(subGoal) }
    }

    companion object {
        fun provideFactory(repository: TrackerRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(repository) as T
                }
            }
    }
}
