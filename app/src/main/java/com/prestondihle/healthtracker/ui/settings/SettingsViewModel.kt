package com.prestondihle.healthtracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.data.UserSettings
import com.prestondihle.healthtracker.repository.TrackerRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val goals: UserGoals = UserGoals()
)

class SettingsViewModel(private val repository: TrackerRepository) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.getUserSettings(),
        repository.getUserGoals()
    ) { settings, goals ->
        SettingsUiState(
            settings = settings ?: UserSettings(),
            goals = goals ?: UserGoals()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

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
