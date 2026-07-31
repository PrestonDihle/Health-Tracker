package com.prestondihle.healthtracker.ui.master

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.data.BloodSugarReading
import com.prestondihle.healthtracker.data.HeartRateBucket
import com.prestondihle.healthtracker.data.KetoneReading
import com.prestondihle.healthtracker.data.MealEntry
import com.prestondihle.healthtracker.domain.Macro
import com.prestondihle.healthtracker.domain.MacroAbsorption
import com.prestondihle.healthtracker.domain.MacroServing
import com.prestondihle.healthtracker.health.HealthPermissionState
import com.prestondihle.healthtracker.repository.TrackerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** How much of the recent past the master graph covers. */
enum class MasterRange(val label: String, val hours: Long) {
    TWELVE("12h", 12),
    DAY("24h", 24),
    TWO_DAYS("48h", 48),
}

/**
 * One switchable line on the master graph.
 *
 * Six series on one plot is a lot to read at once, and most questions only
 * involve two or three of them -- carbs against glucose, or fat against heart
 * rate. Turning the rest off is what makes those comparisons legible.
 */
enum class MasterSeries(val label: String) {
    GLUCOSE("Glucose"),
    CARBS("Carbs"),
    PROTEIN("Protein"),
    FAT("Fat"),
    HEART_RATE("Heart rate"),
    KETONES("Ketones"),
}

data class MasterGraphUiState(
    val range: MasterRange = MasterRange.DAY,
    val now: Instant = Instant.now(),
    val meals: List<MealEntry> = emptyList(),
    val glucose: List<BloodSugarReading> = emptyList(),
    val ketones: List<KetoneReading> = emptyList(),
    val heartRate: List<HeartRateBucket> = emptyList(),
    val healthState: HealthPermissionState = HealthPermissionState.NOT_GRANTED,
    val isSyncing: Boolean = false,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    /** Everything starts on; the switches are for narrowing, not for building up. */
    val visibleSeries: Set<MasterSeries> = MasterSeries.entries.toSet(),
) {
    fun isVisible(series: MasterSeries): Boolean = series in visibleSeries

    val windowStart: Instant
        get() = now.minus(Duration.ofHours(range.hours))

    /**
     * Meals inside the window, newest first.
     *
     * [meals] itself reaches further back than the plot, because a meal eaten
     * before the left edge is still being absorbed inside it -- but only the ones
     * actually visible belong in the list under the chart.
     */
    val mealsInWindow: List<MealEntry>
        get() = meals.filter { !it.timestamp.isBefore(windowStart) }.sortedByDescending { it.timestamp }

    private val servings: List<MacroServing>
        get() =
            meals.map {
                MacroServing(
                    time = it.timestamp,
                    proteinGrams = it.proteinGrams ?: 0f,
                    carbGrams = it.carbGrams ?: 0f,
                    fatGrams = it.fatGrams ?: 0f,
                )
            }

    /** Grams per hour of one macro reaching the blood, sampled across the window. */
    fun absorptionCurve(macro: Macro): List<Pair<Instant, Float>> =
        MacroAbsorption.curve(servings, macro, windowStart, now)

    /** What is entering the blood right now, for the read-out above the chart. */
    fun rateNow(macro: Macro): Float = MacroAbsorption.rateAt(servings, macro, now)

    /**
     * The most recent meal that has not finished absorbing, if any.
     *
     * Used to say how far along it is, which is the question the curves are drawn
     * to answer and is otherwise left to eyeballing the slope.
     */
    val lastMeal: MealEntry?
        get() = meals.maxByOrNull { it.timestamp }

    /** How much of [lastMeal]'s carbohydrate has reached the blood, as a fraction. */
    fun lastMealAbsorbed(macro: Macro): Float? =
        lastMeal?.let { MacroAbsorption.absorbedFraction(macro, it.timestamp, now) }

    val latestGlucose: BloodSugarReading?
        get() = glucose.maxByOrNull { it.timestamp }

    val latestKetone: KetoneReading?
        get() = ketones.maxByOrNull { it.timestamp }

    val latestHeartRate: HeartRateBucket?
        get() = heartRate.maxByOrNull { it.bucketStartMillis }

    val hasAnything: Boolean
        get() =
            meals.isNotEmpty() ||
                glucose.isNotEmpty() ||
                ketones.isNotEmpty() ||
                heartRate.isNotEmpty()
}

private data class SeriesBundle(
    val meals: List<MealEntry>,
    val glucose: List<BloodSugarReading>,
    val ketones: List<KetoneReading>,
    val heartRate: List<HeartRateBucket>,
)

/**
 * Everything that happens on one timeline: what was eaten, how it is being
 * absorbed, and how blood sugar, ketones and heart rate moved in response.
 */
class MasterGraphViewModel(
    private val repository: TrackerRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val range = MutableStateFlow(MasterRange.DAY)
    private val healthState = MutableStateFlow(HealthPermissionState.NOT_GRANTED)
    private val syncing = MutableStateFlow(false)
    private val visibleSeries = MutableStateFlow(MasterSeries.entries.toSet())

    /**
     * Recomputed on a coarse tick rather than every second.
     *
     * Unlike the fast timer this drives no clock read-out; a minute is finer than
     * any of these curves visibly moves, and a one-second tick would rebuild six
     * sampled series sixty times as often for no visible difference.
     */
    private val minuteTicker = MutableStateFlow(Instant.now())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val seriesFlow =
        range.flatMapLatest { selected ->
            val now = Instant.now()
            val windowStart = now.minus(Duration.ofHours(selected.hours))
            // Meals reach back a further absorption window: one eaten before the
            // left edge is still contributing a curve inside it, and dropping it
            // would start the line at the wrong height.
            val mealsSince =
                windowStart.minus(Duration.ofHours(MacroAbsorption.RELEVANT_HISTORY_HOURS))
            combine(
                repository.getMealsSince(mealsSince),
                repository.getBloodSugarSince(windowStart),
                repository.getKetonesSince(windowStart),
                repository.getHeartRateSince(windowStart),
            ) { meals, glucose, ketones, heartRate ->
                SeriesBundle(meals, glucose, ketones, heartRate)
            }
        }

    val uiState: StateFlow<MasterGraphUiState> =
        combine(
                seriesFlow,
                range,
                minuteTicker,
                healthState,
                // Paired up because combine's typed overloads stop at five sources.
                combine(syncing, visibleSeries) { isSyncing, visible -> isSyncing to visible },
            ) { series, selected, now, permission, syncAndVisible ->
                val (isSyncing, visible) = syncAndVisible
                MasterGraphUiState(
                    range = selected,
                    now = now,
                    meals = series.meals,
                    glucose = series.glucose,
                    ketones = series.ketones,
                    heartRate = series.heartRate,
                    healthState = permission,
                    isSyncing = isSyncing,
                    zoneId = zoneId,
                    visibleSeries = visible,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = MasterGraphUiState(zoneId = zoneId),
            )

    init {
        refresh()
    }

    fun setRange(selected: MasterRange) {
        range.value = selected
        refresh()
    }

    fun setSeriesVisible(series: MasterSeries, visible: Boolean) {
        visibleSeries.value =
            if (visible) visibleSeries.value + series else visibleSeries.value - series
    }

    /**
     * Pulls the window's meals and heart rate from Health Connect.
     *
     * Reaches back one absorption window beyond the plot for the same reason the
     * query does: a meal from before the left edge still shapes the curve inside
     * it, and it has to be in the cache to be read at all.
     */
    fun refresh() {
        viewModelScope.launch {
            minuteTicker.value = Instant.now()
            healthState.value = repository.healthPermissionState()
            if (healthState.value != HealthPermissionState.GRANTED) return@launch

            val now = Instant.now()
            val from =
                now.minus(Duration.ofHours(range.value.hours + MacroAbsorption.RELEVANT_HISTORY_HOURS))
            syncing.value = true
            repository.syncTimeSeries(from, now)
            syncing.value = false
        }
    }

    companion object {
        fun provideFactory(repository: TrackerRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MasterGraphViewModel(repository) as T
            }
    }
}
