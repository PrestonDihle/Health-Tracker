package com.prestondihle.healthtracker.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.data.BloodSugarReading
import com.prestondihle.healthtracker.data.CaffeineIntake
import com.prestondihle.healthtracker.data.HealthDaySnapshot
import com.prestondihle.healthtracker.data.HeartRateBucket
import com.prestondihle.healthtracker.data.KetoneReading
import com.prestondihle.healthtracker.data.MealEntry
import com.prestondihle.healthtracker.data.StepBucket
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.domain.Caffeine
import com.prestondihle.healthtracker.domain.CaffeineDose
import com.prestondihle.healthtracker.domain.Glucose
import com.prestondihle.healthtracker.domain.GlucoseSmoothing
import com.prestondihle.healthtracker.domain.Macro
import com.prestondihle.healthtracker.domain.MacroAbsorption
import com.prestondihle.healthtracker.domain.MacroServing
import com.prestondihle.healthtracker.domain.MealDuplicates
import com.prestondihle.healthtracker.domain.SleepNight
import com.prestondihle.healthtracker.health.HealthPermissionState
import com.prestondihle.healthtracker.repository.TrackerRepository
import com.prestondihle.healthtracker.ui.components.ChartAxis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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
 * Eight series on one plot is a lot to read at once, and most questions only
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
    /**
     * Caffeine belongs here for the same reason the macro curves do: it is
     * something taken at a known moment that goes on acting for hours
     * afterwards, and the questions asked of this chart -- why is the heart rate
     * up, why did sleep not come -- are exactly the ones a decay curve beside
     * the rest of the day answers.
     */
    CAFFEINE("Caffeine"),
}

/**
 * A unit that can be printed down the side of the master chart.
 *
 * The plot has two gutters and the series carry six different units, so at most
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
    CAFFEINE("Caffeine"),
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
            MasterSeries.CAFFEINE -> AxisMetric.CAFFEINE
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

/**
 * How far before the window's start to look for a night that reaches into it.
 *
 * Long enough to catch the beginning of any ordinary night from a window opening
 * at the following midday. Without it a 3h window over breakfast would find no
 * night at all -- the sleep that ended an hour before it began started long
 * outside it -- and the shade would vanish on exactly the zoom where it explains
 * most.
 */
private val SLEEP_HISTORY: Duration = Duration.ofHours(18)

data class TodayUiState(
    val range: MasterRange = MasterRange.DAY,
    val now: Instant = Instant.now(),
    val meals: List<MealEntry> = emptyList(),
    val glucose: List<BloodSugarReading> = emptyList(),
    val ketones: List<KetoneReading> = emptyList(),
    val heartRate: List<HeartRateBucket> = emptyList(),
    val steps: List<StepBucket> = emptyList(),
    /**
     * Doses reaching back beyond the window, not only the ones inside it.
     *
     * A coffee drunk before the left edge is still most of the level at the edge,
     * and dropping it would start the curve at zero and draw a climb that never
     * happened.
     */
    val caffeine: List<CaffeineIntake> = emptyList(),
    /**
     * Nights overlapping the window, for the shaded ground rather than a line.
     *
     * Whole nights, including the part before the left edge: a night is clipped
     * when it is drawn, and trimming it here would lose the fact that the sleep
     * on screen began earlier -- which is what the shade running to the edge
     * rather than starting inside it says.
     */
    val sleep: List<SleepNight> = emptyList(),
    /**
     * Today's rolled-up figures, for the totals above the plot.
     *
     * The daily cache rather than the time series: steps, sleep, calories and
     * macros are asked for as a day here, and the series below already answer
     * *when*. Null until the first sync of the day lands.
     */
    val snapshot: HealthDaySnapshot? = null,
    /** Average pace over runs of at least a mile, all-time. Not a personal best. */
    val bestMileSeconds: Int? = null,
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
    /**
     * How far back from [now] the window's right edge has been dragged.
     *
     * Zero is live, and is where every window starts. Anything else is a window
     * the reader has pulled off the clock to look at a particular evening --
     * which is the only way to examine yesterday's lunch at 3h zoom, since every
     * range is otherwise anchored to this moment.
     */
    val panOffset: Duration = Duration.ZERO,
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

    /**
     * The right edge of the plot: [now] while live, earlier once panned.
     *
     * Everything drawn stops here, curves included. A modelled line sampled to
     * `now` on a window that ends before it runs straight off the right-hand
     * side, which is the one place on a chart where a stray line looks most like
     * data.
     */
    val windowEnd: Instant
        get() = now.minus(panOffset)

    val windowStart: Instant
        get() = windowEnd.minus(Duration.ofHours(range.hours))

    /** Whether the window has been dragged off the clock. */
    val isPanned: Boolean
        get() = !panOffset.isZero

    /**
     * The blood sugar trace as it should be drawn: raw, or run through the
     * smoother when the setting is on.
     */
    val glucoseCurve: List<Pair<Instant, Float>>
        get() {
            val raw = glucose.map { it.timestamp to it.mgDl.toFloat() }
            return if (smoothGlucose) GlucoseSmoothing.smooth(raw) else raw
        }

    /**
     * No `glucoseTarget` here, deliberately, though `UserGoals` still holds one
     * and the Today chart still shades it.
     *
     * This plot carries eight series and the band is a backdrop for one of them.
     * Behind carbohydrate curves, step columns and a heart rate trace it stopped
     * reading as the glucose target and started reading as a region of the
     * chart -- and it now has the sleep shade underneath it, which is a second
     * wash saying something else entirely. The reference rule carries the same
     * information here at a weight that cannot be misread.
     */

    /** Floor and ceiling of the glucose axis, from settings. */
    val glucosePlotRange: ClosedFloatingPointRange<Float>
        get() = Glucose.plotRange(goals.glucosePlotMinMgDl, goals.glucosePlotMaxMgDl)

    /** The reader's own rule across the glucose axis, or null when cleared. */
    val glucoseReference: Float?
        get() = goals.glucoseReferenceMgDl?.toFloat()

    /**
     * Caffeine in the body across the window, sampled evenly.
     *
     * Sampled rather than drawn dose to dose for the reason the dashboard does
     * it: the decay between two doses is exponential, and joining the doses
     * themselves would draw it as a straight ramp. The step follows the window
     * for the same reason the absorption curves' does -- a fixed one is either
     * too coarse to show a morning coffee arriving or far more points than a
     * week of plot can render.
     */
    val caffeineCurve: List<Pair<Instant, Float>>
        get() =
            Caffeine.curve(
                doses = caffeine.map { CaffeineDose(it.timestamp, it.milligrams) },
                from = windowStart,
                to = windowEnd,
                step = curveStep,
            )

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
     *
     * Bounded at both ends rather than only at the left, which the right edge
     * being *now* used to make unnecessary. A panned window has time to the right
     * of it, and a list of meals eaten after the chart stops is a list of meals
     * whose rules the reader cannot find.
     */
    val mealsInWindow: List<MealEntry>
        get() =
            distinctMeals
                .filter { it.timestamp in windowStart..windowEnd }
                .sortedByDescending { it.timestamp }

    /**
     * Nights any part of which is on the plot.
     *
     * Overlap rather than containment, which is the same rule the DAO query
     * uses and matters twice as much here: at 3h zoom no night is ever wholly
     * inside the window, so a containment test would shade nothing at all on
     * exactly the zoom level where knowing you were asleep matters most.
     *
     * Bounded by [windowEnd] rather than by `now`, like everything else drawn --
     * a night still running past a panned right edge is clipped there rather
     * than shading past it.
     */
    val sleepInWindow: List<SleepNight>
        get() = sleep.filter { it.end > windowStart && it.start < windowEnd }

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
        MacroAbsorption.curve(servings, macro, windowStart, windowEnd, curveStep)

    /** Sampling interval for the curves, scaled to the window. See [CURVE_SAMPLES]. */
    private val curveStep: Duration
        get() =
            Duration.ofSeconds(
                (range.hours * 3_600 / CURVE_SAMPLES)
                    .coerceIn(CURVE_STEP_MIN_SECONDS, CURVE_STEP_MAX_SECONDS)
            )

    /**
     * Calories eaten so far today. Nothing logged means nothing eaten.
     *
     * Unlike the burn figures this is not a measurement that might simply not
     * have synced yet -- food reaches Health Connect only by being entered by
     * hand, so an absent value and a zero are the same statement.
     */
    val caloriesEaten: Int
        get() = snapshot?.dietaryCalories ?: 0

    /**
     * Calories eaten minus calories burned, or null while the burn is unknown.
     *
     * Negative is a deficit. The burn half keeps its guard: it comes from a watch,
     * so a missing value means "not synced", and standing in a zero would report
     * a surplus the size of the day's food.
     */
    val netCalories: Int?
        get() = snapshot?.totalCalories?.let { caloriesEaten - it }

    val hasAnything: Boolean
        get() =
            meals.isNotEmpty() ||
                glucose.isNotEmpty() ||
                ketones.isNotEmpty() ||
                heartRate.isNotEmpty() ||
                steps.isNotEmpty() ||
                caffeine.isNotEmpty()
}

private data class SeriesBundle(
    val meals: List<MealEntry>,
    val glucose: List<BloodSugarReading>,
    val ketones: List<KetoneReading>,
    val heartRate: List<HeartRateBucket>,
    val steps: List<StepBucket>,
    val caffeine: List<CaffeineIntake>,
    val sleep: List<SleepNight> = emptyList(),
)

/** Everything that shapes how the series are drawn rather than what is in them. */
private data class PreferenceBundle(
    val isSyncing: Boolean,
    val visibleSeries: Set<MasterSeries>,
    val goals: UserGoals,
    val smoothGlucose: Boolean,
    val labelledAxes: List<AxisMetric>,
    val snapshot: HealthDaySnapshot?,
    val bestMileSeconds: Int?,
)

/**
 * The day rolled up, which no series on the plot can answer.
 *
 * Bundled separately and folded in below because `combine` takes five sources at
 * a time, and these two are the only ones here asked for as a *day* rather than
 * as a span.
 */
private data class DailyBundle(val snapshot: HealthDaySnapshot?, val bestMileSeconds: Int?)

/** Which slice of the timeline is on screen: how wide, and how far back. */
private data class WindowBundle(val range: MasterRange, val panOffset: Duration)

/**
 * How near live a drag has to land before the window snaps back to it.
 *
 * A window three minutes short of now looks exactly like a live one and is not,
 * which is the worst of both -- so a small drag either moves the window
 * somewhere worth being or returns it to following the clock.
 */
internal val PAN_SNAP: Duration = Duration.ofMinutes(2)

/**
 * Where a drag of [delta] leaves a window currently offset by [current].
 *
 * Separated out because it is the whole of the rule and it is arithmetic: never
 * past now, since there is nothing to the right of it and a window with its right
 * edge in the future is a plot with a blank third; and back to live once it is
 * within [PAN_SNAP].
 */
internal fun pannedTo(current: Duration, delta: Duration): Duration {
    val next = current.plus(delta)
    return if (next <= PAN_SNAP) Duration.ZERO else next
}

/**
 * Everything that happens on one timeline: what was eaten, how it is being
 * absorbed, and how blood sugar, ketones and heart rate moved in response.
 */
class TodayViewModel(
    private val repository: TrackerRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val range = MutableStateFlow(MasterRange.DAY)
    private val healthState = MutableStateFlow(HealthPermissionState.NOT_GRANTED)
    private val syncing = MutableStateFlow(false)
    private val visibleSeries = MutableStateFlow(MasterSeries.entries.toSet())
    private val labelledAxes =
        MutableStateFlow(listOf(AxisMetric.GLUCOSE, AxisMetric.MACROS))
    private val panOffset = MutableStateFlow(Duration.ZERO)

    /**
     * Recomputed on a coarse tick rather than every second.
     *
     * Unlike the fast timer this drives no clock read-out; a minute is finer than
     * any of these curves visibly moves, and a one-second tick would rebuild six
     * sampled series sixty times as often for no visible difference.
     */
    private val minuteTicker = MutableStateFlow(Instant.now())

    private val window: Flow<WindowBundle> = combine(range, panOffset, ::WindowBundle)

    /**
     * How far back the queries reach, snapped down to the hour.
     *
     * The pan moves continuously under a finger, and every emission here tears
     * down six Room subscriptions and opens six more -- once a frame, if the raw
     * offset were the key. Snapping is what makes that once an hour panned
     * instead, and it is safe because every query below is open-ended forward: an
     * anchor an hour early loads a superset of what the window needs, never a
     * subset.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val queryAnchor: Flow<Instant> =
        window
            .map { (selected, offset) ->
                Instant.now()
                    .minus(offset)
                    .minus(Duration.ofHours(selected.hours))
                    .truncatedTo(ChronoUnit.HOURS)
            }
            .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val seriesFlow =
        queryAnchor.flatMapLatest { windowStart ->
            // Meals reach back a further absorption window: one eaten before the
            // left edge is still contributing a curve inside it, and dropping it
            // would start the line at the wrong height.
            val mealsSince =
                windowStart.minus(Duration.ofHours(MacroAbsorption.RELEVANT_HISTORY_HOURS))
            // Caffeine reaches back on the same principle as the meals, over its
            // own history: a dose older than this is under a thousandth of what
            // was drunk and cannot move the line it would be loaded to draw.
            val caffeineSince =
                windowStart.minus(Duration.ofHours(Caffeine.RELEVANT_HISTORY_HOURS))
            // Sleep reaches back a night, for the reason the meals reach back an
            // absorption window: a night beginning before the left edge is still
            // being slept through inside it, and one anchored at the window start
            // would shade only the hours after midnight on a morning window.
            val sleepSince = windowStart.minus(SLEEP_HISTORY)
            // Nested rather than one call: combine's typed overloads stop at five
            // sources, and this is the seventh.
            combine(
                combine(
                    repository.getMealsSince(mealsSince),
                    repository.getBloodSugarSince(windowStart),
                    repository.getKetonesSince(windowStart),
                    repository.getHeartRateSince(windowStart),
                    repository.getStepBucketsSince(windowStart),
                ) { meals, glucose, ketones, heartRate, steps ->
                    SeriesBundle(meals, glucose, ketones, heartRate, steps, emptyList())
                },
                repository.getCaffeineSince(caffeineSince),
                repository.getSleepNightsSince(sleepSince),
            ) { bundle, caffeine, sleep ->
                bundle.copy(caffeine = caffeine, sleep = sleep)
            }
        }

    /** Bundled because combine's typed overloads stop at five sources. */
    private val today: LocalDate
        get() = LocalDate.now(zoneId)

    private val daily: Flow<DailyBundle> =
        combine(
            repository.getHealthSnapshot(today),
            repository.getBestMileSecondsAllTime(),
            ::DailyBundle,
        )

    private val preferences: Flow<PreferenceBundle> =
        combine(
            syncing,
            visibleSeries,
            labelledAxes,
            combine(repository.getUserGoals(), repository.getUserSettings(), ::Pair),
            daily,
        ) { isSyncing, visible, axes, (goals, settings), day ->
            PreferenceBundle(
                isSyncing = isSyncing,
                visibleSeries = visible,
                goals = goals ?: UserGoals(),
                smoothGlucose = settings?.smoothGlucose ?: false,
                labelledAxes = axes,
                snapshot = day.snapshot,
                bestMileSeconds = day.bestMileSeconds,
            )
        }

    val uiState: StateFlow<TodayUiState> =
        combine(seriesFlow, window, minuteTicker, healthState, preferences) {
            series,
            viewed,
            now,
            permission,
            prefs ->
            TodayUiState(
                range = viewed.range,
                panOffset = viewed.panOffset,
                now = now,
                meals = series.meals,
                glucose = series.glucose,
                ketones = series.ketones,
                heartRate = series.heartRate,
                steps = series.steps,
                caffeine = series.caffeine,
                sleep = series.sleep,
                snapshot = prefs.snapshot,
                bestMileSeconds = prefs.bestMileSeconds,
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
                initialValue = TodayUiState(zoneId = zoneId),
            )

    init {
        refresh()
    }

    fun setRange(selected: MasterRange) {
        range.value = selected
        refresh()
    }

    /**
     * Drags the window back through time, or forward when [delta] is negative.
     *
     * No sync of its own. Panning is a look at history already on disk, and
     * firing a Health Connect read per frame of a drag would be several hundred
     * of them for one gesture; the refresh button covers whatever window is being
     * shown when it is pressed.
     */
    fun panBy(delta: Duration) {
        panOffset.value = pannedTo(panOffset.value, delta)
    }

    /** Puts the window back on the clock. */
    fun backToNow() {
        panOffset.value = Duration.ZERO
    }

    /**
     * Moves `now` on, leaving a panned window where it was put.
     *
     * The clock advancing is what makes a live window follow the day, and it must
     * not also drag a panned one along behind it: the reader went back to a
     * particular evening and that evening does not move. Growing the offset by
     * exactly what the clock gained leaves `windowEnd` where it was.
     */
    private fun advanceNow() {
        val next = Instant.now()
        if (!panOffset.value.isZero) {
            panOffset.value = panOffset.value.plus(Duration.between(minuteTicker.value, next))
        }
        minuteTicker.value = next
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
            advanceNow()
            healthState.value = repository.healthPermissionState()
            if (healthState.value != HealthPermissionState.GRANTED) return@launch

            val now = minuteTicker.value
            // The window on screen, not the last day. Refreshing a chart panned
            // back to Tuesday and syncing today instead would leave the button
            // looking broken and Tuesday still full of holes.
            val to = now.minus(panOffset.value)
            val from =
                to.minus(Duration.ofHours(range.value.hours + MacroAbsorption.RELEVANT_HISTORY_HOURS))
            syncing.value = true
            repository.syncTimeSeries(from, to)
            // The totals card above the plot reads the daily snapshot, and
            // `syncTimeSeries` does not write it: steps, sleep, calories and
            // macros are rolled up a day at a time by `syncHealthData` alone.
            // Syncing only the series would leave that card blank over a chart
            // drawn from the very walk it could not report -- which is the shape
            // the sleep card already shipped in once, the other way round.
            repository.syncHealthData(today)
            // Glucose is not part of either sync -- it is cached a calendar
            // day at a time -- so a hole in the trace this chart is drawing is
            // only ever filled by going back for it deliberately. Anchored at now
            // rather than at the window: the backfill covers a fixed 72 hours,
            // which is as far back as a monitor's late writes are worth chasing.
            repository.backfillGlucoseGaps(now)
            syncing.value = false
        }
    }

    companion object {
        fun provideFactory(repository: TrackerRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TodayViewModel(repository) as T
            }
    }
}
