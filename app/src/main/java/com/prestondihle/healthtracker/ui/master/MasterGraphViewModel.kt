package com.prestondihle.healthtracker.ui.master

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.data.BloodSugarReading
import com.prestondihle.healthtracker.data.HeartRateBucket
import com.prestondihle.healthtracker.data.KetoneReading
import com.prestondihle.healthtracker.data.MealEntry
import com.prestondihle.healthtracker.data.StepBucket
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.domain.GlucoseSmoothing
import com.prestondihle.healthtracker.domain.Macro
import com.prestondihle.healthtracker.domain.MacroAbsorption
import com.prestondihle.healthtracker.domain.MacroServing
import com.prestondihle.healthtracker.domain.MealDuplicates
import com.prestondihle.healthtracker.health.HealthPermissionState
import com.prestondihle.healthtracker.repository.TrackerRepository
import com.prestondihle.healthtracker.ui.components.ChartAxis
import kotlinx.coroutines.flow.Flow
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
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * How much of the recent past the master graph covers.
 *
 * The short end is for reading one meal: three hours is roughly a carbohydrate
 * curve start to finish, so the rise and the fall of a single glucose response
 * fill the plot. The long end is for reading a pattern rather than an event --
 * across a week the individual meals are illegible, but a habit is not.
 */
enum class MasterRange(val label: String, val hours: Long) {
    THREE("3h", 3),
    SIX("6h", 6),
    TWELVE("12h", 12),
    DAY("24h", 24),
    TWO_DAYS("48h", 48),
    WEEK("7d", 24 * 7),
}

/**
 * One switchable series on the master graph.
 *
 * Seven series on one plot is a lot to read at once, and most questions only
 * involve two or three of them -- carbs against glucose, or steps against heart
 * rate. Turning the rest off is what makes those comparisons legible.
 */
enum class MasterSeries(val label: String) {
    GLUCOSE("Glucose"),
    CARBS("Carbs"),
    PROTEIN("Protein"),
    FAT("Fat"),
    HEART_RATE("Heart rate"),
    KETONES("Ketones"),
    STEPS("Steps"),
}

/**
 * A unit that can be printed down the side of the master chart.
 *
 * The plot has two gutters and the series carry five different units, so at most
 * two of them can ever have their numbers on screen; the rest are drawn to their
 * own scale with the range quoted in the legend instead. Which two is a reading
 * decision, not a fixed one -- comparing steps against heart rate wants a
 * different pair of axes than comparing carbohydrate against glucose -- so it is
 * left to whoever is reading.
 *
 * Grouped by unit rather than by series: the three macro curves share one g/h
 * scale and would be meaningless drawn against separate ones.
 */
enum class AxisMetric(val label: String) {
    GLUCOSE("Glucose"),
    MACROS("Macros"),
    HEART_RATE("Heart rate"),
    KETONES("Ketones"),
    STEPS("Steps"),
}

/** The unit a series is measured in, and so which axis can carry it. */
val MasterSeries.metric: AxisMetric
    get() =
        when (this) {
            MasterSeries.GLUCOSE -> AxisMetric.GLUCOSE
            MasterSeries.CARBS, MasterSeries.PROTEIN, MasterSeries.FAT -> AxisMetric.MACROS
            MasterSeries.HEART_RATE -> AxisMetric.HEART_RATE
            MasterSeries.KETONES -> AxisMetric.KETONES
            MasterSeries.STEPS -> AxisMetric.STEPS
        }

/** How many units can have their numbers printed at once. */
const val MAX_LABELLED_AXES = 2

/**
 * Roughly how many points an absorption curve is sampled at across the window.
 *
 * The step is derived from this rather than fixed, and then clamped: a fixed ten
 * minutes leaves a three-hour plot drawing a 45-minute carbohydrate peak from
 * four samples, while the same ten minutes across a week is four times more
 * points than the plot can render. One minute is the floor because nothing here
 * moves faster; ten is the ceiling because the curves are smooth and a coarser
 * sample starts to miss peaks rather than merely round them.
 */
private const val CURVE_SAMPLES = 180L
private const val CURVE_STEP_MIN_SECONDS = 60L
private const val CURVE_STEP_MAX_SECONDS = 600L

data class MasterGraphUiState(
    val range: MasterRange = MasterRange.DAY,
    val now: Instant = Instant.now(),
    val meals: List<MealEntry> = emptyList(),
    val glucose: List<BloodSugarReading> = emptyList(),
    val ketones: List<KetoneReading> = emptyList(),
    val heartRate: List<HeartRateBucket> = emptyList(),
    val steps: List<StepBucket> = emptyList(),
    val goals: UserGoals = UserGoals(),
    /** Drawn smoothed only when the user has asked for it in settings. */
    val smoothGlucose: Boolean = false,
    val healthState: HealthPermissionState = HealthPermissionState.NOT_GRANTED,
    val isSyncing: Boolean = false,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    /** Everything starts on; the switches are for narrowing, not for building up. */
    val visibleSeries: Set<MasterSeries> = MasterSeries.entries.toSet(),
    /**
     * Which units have their numbers printed, in order: first left, then right.
     *
     * Ordered rather than a set, because which side a unit lands on is the whole
     * point. Glucose and macros to begin with, which is the pairing the chart was
     * built around.
     */
    val labelledAxes: List<AxisMetric> = listOf(AxisMetric.GLUCOSE, AxisMetric.MACROS),
) {
    fun isVisible(series: MasterSeries): Boolean = series in visibleSeries

    /** Which side [metric] is printed on, or null when it is not printed at all. */
    fun axisFor(metric: AxisMetric): ChartAxis? =
        when (labelledAxes.indexOf(metric)) {
            0 -> ChartAxis.LEFT
            1 -> ChartAxis.RIGHT
            else -> null
        }

    fun isLabelled(metric: AxisMetric): Boolean = metric in labelledAxes

    val windowStart: Instant
        get() = now.minus(Duration.ofHours(range.hours))

    /**
     * The blood sugar trace as it should be drawn: raw, or run through the
     * smoother when the setting is on.
     */
    val glucoseCurve: List<Pair<Instant, Float>>
        get() {
            val raw = glucose.map { it.timestamp to it.mgDl.toFloat() }
            return if (smoothGlucose) GlucoseSmoothing.smooth(raw) else raw
        }

    /** The shaded target, or null while either edge is unset or inverted. */
    val glucoseTarget: ClosedFloatingPointRange<Float>?
        get() {
            val low = goals.glucoseTargetLowMgDl ?: return null
            val high = goals.glucoseTargetHighMgDl ?: return null
            return if (high > low) low.toFloat()..high.toFloat() else null
        }

    /**
     * The meals as they should be counted: one row per meal actually eaten.
     *
     * A source that writes the same meal as several separate records would
     * otherwise be believed, and the day's carbohydrate drawn at three times its
     * real height. Collapsed once here so the curves, the marker rules and the
     * list under the chart all agree.
     */
    private val distinctMeals: List<MealEntry>
        get() = MealDuplicates.collapse(meals.sortedBy { it.id })

    /** How many records the collapse absorbed, so the screen can own up to it. */
    val duplicatesCollapsed: Int
        get() = meals.size - distinctMeals.size

    /**
     * Meals inside the window, newest first.
     *
     * [meals] itself reaches further back than the plot, because a meal eaten
     * before the left edge is still being absorbed inside it -- but only the ones
     * actually visible belong in the list under the chart.
     */
    val mealsInWindow: List<MealEntry>
        get() =
            distinctMeals
                .filter { !it.timestamp.isBefore(windowStart) }
                .sortedByDescending { it.timestamp }

    /**
     * Times of day that are a stamp rather than a measurement.
     *
     * Some nutrition sources record the day and nothing finer, then put every
     * meal at one fixed time when they hand it to Health Connect. Real data from
     * the author's phone had every meal at exactly 10:00:00 local -- including
     * three separate meals on one Tuesday -- and an earlier version of this
     * checked only for midnight, which that shape sails straight past.
     *
     * **A time of day shared to the second by two different meals is the giveaway.**
     * Genuine timestamps land on a different second every time; a source that
     * knows only the date lands on the same one for ever. Midnight joins the set
     * unconditionally, because a lone meal at exactly 00:00:00 is a date too.
     *
     * Computed over the meals actually loaded, which reach a little beyond the
     * plotted window. A narrow window holds too few meals for a repeat to show
     * up, so the flag is quieter at 3h than at 7d -- which is the right way round:
     * 7d is where the history worth correcting is.
     */
    private val stampedTimesOfDay: Set<LocalTime>
        get() =
            distinctMeals
                .groupBy { it.timestamp.atZone(zoneId).toLocalTime() }
                .filterValues { it.size > 1 }
                .keys + LocalTime.MIDNIGHT

    /**
     * Whether a meal carries a real clock time or only the date it was eaten on.
     *
     * An absorption curve anchored to a stamped time describes an hour nobody
     * ate in, so the screen has to be able to say which meals are placed and
     * which are merely dated.
     */
    fun hasClockTime(meal: MealEntry): Boolean =
        meal.timestamp.atZone(zoneId).toLocalTime() !in stampedTimesOfDay

    /** Meals in the window carrying a stamped time rather than a measured one. */
    val undatedMealsInWindow: List<MealEntry>
        get() = mealsInWindow.filterNot(::hasClockTime)

    private val servings: List<MacroServing>
        get() =
            distinctMeals.map {
                MacroServing(
                    time = it.timestamp,
                    proteinGrams = it.proteinGrams ?: 0f,
                    carbGrams = it.carbGrams ?: 0f,
                    fatGrams = it.fatGrams ?: 0f,
                )
            }

    /** Grams per hour of one macro reaching the blood, sampled across the window. */
    fun absorptionCurve(macro: Macro): List<Pair<Instant, Float>> =
        MacroAbsorption.curve(servings, macro, windowStart, now, curveStep)

    /** Sampling interval for the curves, scaled to the window. See [CURVE_SAMPLES]. */
    private val curveStep: Duration
        get() =
            Duration.ofSeconds(
                (range.hours * 3_600 / CURVE_SAMPLES)
                    .coerceIn(CURVE_STEP_MIN_SECONDS, CURVE_STEP_MAX_SECONDS)
            )

    /** What is entering the blood right now, for the read-out above the chart. */
    fun rateNow(macro: Macro): Float = MacroAbsorption.rateAt(servings, macro, now)

    /**
     * The most recent meal that has not finished absorbing, if any.
     *
     * Used to say how far along it is, which is the question the curves are drawn
     * to answer and is otherwise left to eyeballing the slope.
     */
    val lastMeal: MealEntry?
        get() = distinctMeals.maxByOrNull { it.timestamp }

    /** How much of [lastMeal]'s carbohydrate has reached the blood, as a fraction. */
    fun lastMealAbsorbed(macro: Macro): Float? =
        lastMeal?.let { MacroAbsorption.absorbedFraction(macro, it.timestamp, now) }

    val latestGlucose: BloodSugarReading?
        get() = glucose.maxByOrNull { it.timestamp }

    val latestKetone: KetoneReading?
        get() = ketones.maxByOrNull { it.timestamp }

    val latestHeartRate: HeartRateBucket?
        get() = heartRate.maxByOrNull { it.bucketStartMillis }

    /**
     * Steps in the last complete hour, for the read-out above the chart.
     *
     * The hour in progress is deliberately skipped: it is a fraction of an hour's
     * walking quoted as an hourly figure, so it reads as a collapse in activity
     * for fifty-nine minutes out of every sixty.
     */
    val stepsLastHour: StepBucket?
        get() {
            val currentHourStart =
                now.atZone(zoneId).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()
            return steps.filter { it.hourStartMillis < currentHourStart }
                .maxByOrNull { it.hourStartMillis }
        }

    val hasAnything: Boolean
        get() =
            meals.isNotEmpty() ||
                glucose.isNotEmpty() ||
                ketones.isNotEmpty() ||
                heartRate.isNotEmpty() ||
                steps.isNotEmpty()
}

private data class SeriesBundle(
    val meals: List<MealEntry>,
    val glucose: List<BloodSugarReading>,
    val ketones: List<KetoneReading>,
    val heartRate: List<HeartRateBucket>,
    val steps: List<StepBucket>,
)

/** Everything that shapes how the series are drawn rather than what is in them. */
private data class PreferenceBundle(
    val isSyncing: Boolean,
    val visibleSeries: Set<MasterSeries>,
    val goals: UserGoals,
    val smoothGlucose: Boolean,
    val labelledAxes: List<AxisMetric>,
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
    private val labelledAxes =
        MutableStateFlow(listOf(AxisMetric.GLUCOSE, AxisMetric.MACROS))

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
                repository.getStepBucketsSince(windowStart),
            ) { meals, glucose, ketones, heartRate, steps ->
                SeriesBundle(meals, glucose, ketones, heartRate, steps)
            }
        }

    /** Bundled because combine's typed overloads stop at five sources. */
    private val preferences: Flow<PreferenceBundle> =
        combine(
            syncing,
            visibleSeries,
            repository.getUserGoals(),
            repository.getUserSettings(),
            labelledAxes,
        ) { isSyncing, visible, goals, settings, axes ->
            PreferenceBundle(
                isSyncing = isSyncing,
                visibleSeries = visible,
                goals = goals ?: UserGoals(),
                smoothGlucose = settings?.smoothGlucose ?: false,
                labelledAxes = axes,
            )
        }

    val uiState: StateFlow<MasterGraphUiState> =
        combine(seriesFlow, range, minuteTicker, healthState, preferences) {
            series,
            selected,
            now,
            permission,
            prefs ->
            MasterGraphUiState(
                range = selected,
                now = now,
                meals = series.meals,
                glucose = series.glucose,
                ketones = series.ketones,
                heartRate = series.heartRate,
                steps = series.steps,
                goals = prefs.goals,
                smoothGlucose = prefs.smoothGlucose,
                healthState = permission,
                isSyncing = prefs.isSyncing,
                zoneId = zoneId,
                visibleSeries = prefs.visibleSeries,
                labelledAxes = prefs.labelledAxes,
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
     * Adds or removes a unit from the labelled axes.
     *
     * Adding a third drops the oldest rather than refusing the tap: a control
     * that silently does nothing reads as broken, and the reader almost always
     * means "show me this one instead". The last one cannot be removed -- the
     * plot has to be drawn against something, and an unlabelled chart is not a
     * state worth being able to reach.
     */
    fun toggleLabelledAxis(metric: AxisMetric) {
        val current = labelledAxes.value
        labelledAxes.value =
            when {
                metric !in current -> (current + metric).takeLast(MAX_LABELLED_AXES)
                current.size > 1 -> current - metric
                else -> current
            }
    }

    /**
     * Records when a meal was actually eaten.
     *
     * The only way to place a meal whose source recorded a date and no time. It
     * re-anchors that meal's absorption curve, which is the whole reason the
     * timestamp matters here rather than being a caption.
     */
    fun setMealTime(meal: MealEntry, at: Instant) {
        viewModelScope.launch { repository.setMealTime(meal, at) }
    }

    /**
     * Logs a meal by hand.
     *
     * A zero is stored as a zero rather than as "not recorded". Everything here
     * was typed deliberately, so an untouched field genuinely means none of that
     * macro -- unlike a synced meal, where a missing figure means the app that
     * wrote it did not break the food down.
     */
    fun addMeal(calories: Int, protein: Int, carbs: Int, fat: Int, at: Instant) {
        viewModelScope.launch {
            repository.addMeal(
                at = at,
                calories = calories,
                proteinGrams = protein.toFloat(),
                carbGrams = carbs.toFloat(),
                fatGrams = fat.toFloat(),
            )
        }
    }

    /** Rewrites a meal's macros and time together. */
    fun updateMeal(meal: MealEntry, calories: Int, protein: Int, carbs: Int, fat: Int, at: Instant) {
        viewModelScope.launch {
            repository.updateMeal(
                meal.copy(
                    timestamp = at,
                    calories = calories,
                    proteinGrams = protein.toFloat(),
                    carbGrams = carbs.toFloat(),
                    fatGrams = fat.toFloat(),
                )
            )
        }
    }

    fun deleteMeal(meal: MealEntry) {
        viewModelScope.launch { repository.deleteMeal(meal) }
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
