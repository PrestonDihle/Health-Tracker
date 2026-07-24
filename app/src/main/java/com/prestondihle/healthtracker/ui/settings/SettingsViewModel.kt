package com.prestondihle.healthtracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.data.UserSettings
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
        stepSources,
        loadingStepSources,
    ) { settings, goals, sources, loading ->
        SettingsUiState(
            settings = settings ?: UserSettings(),
            goals = goals ?: UserGoals(),
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
