package com.prestondihle.healthtracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.data.UserSettings
import com.prestondihle.healthtracker.data.WeightEntry
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
import java.io.File
import java.time.LocalDate

/**
 * Where the goal-weight stepper opens when no goal has been set.
 *
 * The same figure the goal stepper itself falls back to, in pounds rather than
 * the kilograms it is stored in -- two different defaults would have the
 * waypoint suggestion disagree with the goal it is measured against.
 */
private const val DEFAULT_GOAL_WEIGHT_LBS = 180f

/**
 * What the waypoint stepper will accept.
 *
 * Shared with the control itself rather than repeated, because a seed outside
 * the range would open the stepper on a value its own arrows cannot return to.
 */
internal val WaypointRangeLbs = 80f..400f

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val goals: UserGoals = UserGoals(),
    /** Staged weights on the way to the goal, heaviest first. */
    val weightSubGoals: List<WeightSubGoal> = emptyList(),
    /** The last weight logged by hand, which is where the waypoint stepper opens. */
    val latestWeight: WeightEntry? = null,
    /** Today's per-app step totals, loaded on demand. */
    val stepSources: List<StepSource> = emptyList(),
    /**
     * What today comes to once the sources are merged, which is what clearing
     * the pin produces.
     *
     * Shown beside the per-app figures rather than left to be inferred: the
     * whole decision this card asks for is between one app's number and the
     * merged one, and only one of the two used to be on screen.
     */
    val mergedSteps: Int? = null,
    val isLoadingStepSources: Boolean = false,
    /** True while a backup is being written, so the button cannot be pressed twice. */
    val isExporting: Boolean = false,
) {
    /** The goal in the unit every weight control on this screen is dialled in. */
    val goalWeightLbs: Float?
        get() = goals.goalWeightKg?.let(Units::kgToLbs)

    /**
     * Where the "add a waypoint" stepper opens.
     *
     * With nothing staged this is the weight the reader is actually at, not the
     * goal. Opening at the goal was 55 lb from where the first waypoint was
     * going to be dialled to -- a waypoint is a mark on the way, so the way is
     * where the control has to start.
     *
     * Once a mark exists the next one usually goes halfway between the lightest
     * of them and the goal, which is what the original rule got right and is
     * kept. The goal is the last resort, for a reader who has staged nothing and
     * logged no weight: it is at least a number they chose.
     */
    val suggestedWaypointLbs: Float
        get() {
            val goal = goalWeightLbs ?: DEFAULT_GOAL_WEIGHT_LBS
            val lightestStaged = weightSubGoals.minOfOrNull { Units.kgToLbs(it.kg) }
            val seed =
                if (lightestStaged != null) (lightestStaged + goal) / 2f
                else latestWeight?.let { Units.kgToLbs(it.weightKg) } ?: goal
            return seed.coerceIn(WaypointRangeLbs)
        }
}

/** Bundled because combine's typed overloads stop at five sources. */
private data class StepSourceBundle(
    val sources: List<StepSource>,
    val merged: Int?,
    val isLoading: Boolean,
    val isExporting: Boolean,
)

class SettingsViewModel(private val repository: TrackerRepository) : ViewModel() {

    private val stepSources = MutableStateFlow<List<StepSource>>(emptyList())
    private val mergedSteps = MutableStateFlow<Int?>(null)
    private val loadingStepSources = MutableStateFlow(false)

    private val exporting = MutableStateFlow(false)

    private val stepSourceState =
        combine(stepSources, mergedSteps, loadingStepSources, exporting) {
            sources,
            merged,
            loading,
            isExporting ->
            StepSourceBundle(sources, merged, loading, isExporting)
        }

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.getUserSettings(),
        repository.getUserGoals(),
        repository.getWeightSubGoals(),
        repository.getLatestWeight(),
        stepSourceState,
    ) { settings, goals, subGoals, latestWeight, steps ->
        SettingsUiState(
            settings = settings ?: UserSettings(),
            goals = goals ?: UserGoals(),
            weightSubGoals = subGoals,
            latestWeight = latestWeight,
            stepSources = steps.sources,
            mergedSteps = steps.merged,
            isLoadingStepSources = steps.isLoading,
            isExporting = steps.isExporting,
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
            val today = LocalDate.now()
            stepSources.value = repository.stepSources(today)
            mergedSteps.value = repository.mergedSteps(today)
            loadingStepSources.value = false
        }
    }

    /**
     * Pins the app whose steps are trusted, or clears the pin back to merged.
     *
     * Re-syncs immediately: the cached snapshot *and the day's step buckets*
     * still hold the figures the old preference produced, and leaving them there
     * would make the setting look broken until the next manual refresh. Since
     * the day sync now rewrites the buckets too, one call fixes the card and the
     * chart together.
     */
    fun setPreferredStepsPackage(packageName: String?) {
        viewModelScope.launch {
            repository.upsertUserSettings(
                uiState.value.settings.copy(preferredStepsPackage = packageName)
            )
            repository.syncHealthData(LocalDate.now())
        }
    }

    /**
     * Writes a backup to [destination] and reports what happened.
     *
     * The failure is handed back rather than swallowed. A backup that quietly
     * did not happen is the worst possible outcome here -- worse than no button
     * at all, because it is believed.
     */
    fun exportBackup(destination: File, onFinished: (Throwable?) -> Unit) {
        if (exporting.value) return
        viewModelScope.launch {
            exporting.value = true
            val failure = runCatching { repository.writeCsvBackup(destination) }.exceptionOrNull()
            exporting.value = false
            onFinished(failure)
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
