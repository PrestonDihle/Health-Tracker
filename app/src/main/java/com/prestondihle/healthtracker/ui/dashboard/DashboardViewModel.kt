package com.prestondihle.healthtracker.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.data.BloodPressureReading
import com.prestondihle.healthtracker.data.BloodSugarReading
import com.prestondihle.healthtracker.data.CaffeineIntake
import com.prestondihle.healthtracker.data.DailyLog
import com.prestondihle.healthtracker.data.FastingPlanDay
import com.prestondihle.healthtracker.data.FastingSession
import com.prestondihle.healthtracker.data.FastingType
import com.prestondihle.healthtracker.data.GripStrengthEntry
import com.prestondihle.healthtracker.data.HealthDaySnapshot
import com.prestondihle.healthtracker.data.KetoneReading
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.data.PlannedExtendedFast
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.data.UserSettings
import com.prestondihle.healthtracker.data.WaistEntry
import com.prestondihle.healthtracker.domain.AdherenceResult
import com.prestondihle.healthtracker.domain.Caffeine
import com.prestondihle.healthtracker.domain.CaffeineDose
import com.prestondihle.healthtracker.domain.FastingAdherence
import com.prestondihle.healthtracker.domain.Glucose
import com.prestondihle.healthtracker.domain.GlucoseSmoothing
import com.prestondihle.healthtracker.health.HealthPermissionState
import com.prestondihle.healthtracker.repository.TrackerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Default waist when nothing has ever been measured: 42 inches. */
private const val DEFAULT_WAIST_CM = 106.68f

/**
 * How far back the glucose and ketone chart looks.
 *
 * Three hours at the short end is one meal's response start to finish. Seventy-
 * two at the long end is the span a fasting experiment is actually judged over:
 * a single day cannot show ketones climbing while glucose settles, because that
 * takes longer than a day to happen.
 */
enum class GlucoseWindow(val label: String, val hours: Long) {
    THREE("3h", 3),
    SIX("6h", 6),
    TWELVE("12h", 12),
    DAY("24h", 24),
    TWO_DAYS("48h", 48),
    THREE_DAYS("72h", 72),
}

/**
 * The widest window on offer, and so how much history the query has to fetch.
 *
 * Fetched once at the widest setting rather than re-queried per selection: the
 * whole span is a few hundred CGM rows, and holding it means switching windows
 * is an instant redraw instead of a database round trip.
 */
private val GLUCOSE_WINDOW_HOURS = GlucoseWindow.entries.maxOf { it.hours }

/**
 * Half the caffeine window, extending equally either side of now.
 *
 * Splitting a 36-hour span down the middle puts *now* at the centre of the plot,
 * so the height of the line at the mid-point is the current level and the two
 * halves read as history and forecast at the same scale. Eighteen hours back
 * reaches last night's last dose; eighteen forward runs past tonight's bedtime
 * and into tomorrow morning.
 */
private const val CAFFEINE_HALF_WINDOW_HOURS = 18L

/**
 * The near-term horizon called out on the chart and in the metrics.
 *
 * Six hours is a little over one half-life, which is the question the chart is
 * most often asked: whether what is in the body now will have cleared enough to
 * matter by this evening.
 */
private const val CAFFEINE_FORECAST_HOURS = 6L

/** Bedtime reference for the evening estimate. */
private val CAFFEINE_EVENING_HOUR = java.time.LocalTime.of(21, 0)

/** Used only when the plan has no fast scheduled near now. */
private const val DEFAULT_GOAL_MINUTES = 16 * 60

data class DashboardUiState(
    val today: LocalDate = LocalDate.now(),
    val now: Instant = Instant.now(),
    val activeFast: FastingSession? = null,
    /** Most recently finished fast, so a forgotten Stop can be corrected. */
    val lastCompletedFast: FastingSession? = null,
    val adherence: AdherenceResult? = null,
    val hasPlan: Boolean = false,
    val snapshot: HealthDaySnapshot? = null,
    val bestMileSeconds: Int? = null,
    val hydrationMl: Int = 0,
    val dailyLog: DailyLog = DailyLog(LocalDate.now()),
    val waistCm: Float = DEFAULT_WAIST_CM,
    val hasWaistMeasurement: Boolean = false,
    /** Most recent stored waist measurement, with the date it was taken. */
    val latestWaist: WaistEntry? = null,
    val glucose: List<BloodSugarReading> = emptyList(),
    val ketones: List<KetoneReading> = emptyList(),
    val glucoseWindow: GlucoseWindow = GlucoseWindow.DAY,
    val settings: UserSettings = UserSettings(),
    val caffeine: List<CaffeineIntake> = emptyList(),
    /** Most recent grip measurement on or before today, with the date it was taken. */
    val latestGrip: GripStrengthEntry? = null,
    val latestBloodPressure: BloodPressureReading? = null,
    val pushupsToday: Int = 0,
    val squatsToday: Int = 0,
    val goals: UserGoals = UserGoals(),
    val healthState: HealthPermissionState = HealthPermissionState.NOT_GRANTED,
    /** Requested Health Connect permissions still denied, if any. */
    val missingPermissions: Set<String> = emptySet(),
    val isSyncing: Boolean = false,
    /** Readings the last refresh recovered from holes in the trace; 0 when it found none. */
    val glucoseRecovered: Int = 0,
    val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    /** How long the current fast has been running, or null when not fasting. */
    val fastDuration: Duration?
        get() = activeFast?.let { Duration.between(it.startInstant, now) }

    val fastGoalFraction: Float?
        get() =
            activeFast?.let {
                if (it.goalDurationMinutes <= 0) null
                else
                    (Duration.between(it.startInstant, now).toMinutes().toFloat() /
                            it.goalDurationMinutes)
                        .coerceIn(0f, 1f)
            }

    /**
     * Calories eaten so far today. Nothing logged means nothing eaten.
     *
     * Unlike the burn figures this is not a measurement that might simply not
     * have synced yet -- food reaches Health Connect only by being entered by
     * hand, so an absent value and a zero are the same statement. Treating it as
     * null instead left the differential blank for the whole of a fasted morning,
     * which is exactly when it is worth reading.
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

    val glucoseWindowStart: Instant
        get() = now.minus(Duration.ofHours(glucoseWindow.hours))

    /** The blood sugar trace as drawn: raw, or run through the smoother. */
    val glucoseCurve: List<Pair<Instant, Float>>
        get() {
            val raw = glucose.map { it.timestamp to it.mgDl.toFloat() }
            return if (settings.smoothGlucose) GlucoseSmoothing.smooth(raw) else raw
        }

    /** The shaded target from settings, or null while either edge is unset or inverted. */
    val glucoseTarget: ClosedFloatingPointRange<Float>?
        get() {
            val low = goals.glucoseTargetLowMgDl ?: return null
            val high = goals.glucoseTargetHighMgDl ?: return null
            return if (high > low) low.toFloat()..high.toFloat() else null
        }

    /**
     * The solid rule across the glucose chart, or null when cleared.
     *
     * Kept apart from [glucoseTarget]: the band says which region was wanted,
     * this says which side of one number the trace is on, and a reader wants
     * either or both.
     */
    val glucoseReference: Float?
        get() = goals.glucoseReferenceMgDl?.toFloat()

    /** Floor and ceiling of the glucose axis, from settings. */
    val glucosePlotRange: ClosedFloatingPointRange<Float>
        get() = Glucose.plotRange(goals.glucosePlotMinMgDl, goals.glucosePlotMaxMgDl)

    /** True only when the most recent grip measurement is today's. */
    val hasGripToday: Boolean
        get() = latestGrip?.date == today

    val caffeineWindowStart: Instant
        get() = now.minus(Duration.ofHours(CAFFEINE_HALF_WINDOW_HOURS))

    /** Right edge of the caffeine chart, the same distance ahead as the start is behind. */
    val caffeineWindowEnd: Instant
        get() = now.plus(Duration.ofHours(CAFFEINE_HALF_WINDOW_HOURS))

    private val caffeineDoses: List<CaffeineDose>
        get() = caffeine.map { CaffeineDose(it.timestamp, it.milligrams) }

    /** Milligrams still in the body right now. */
    val caffeineNowMg: Float
        get() = Caffeine.levelAt(caffeineDoses, now)

    /** Milligrams taken since midnight, which is the number a daily limit is set against. */
    val caffeineTodayMg: Int
        get() {
            val midnight = today.atStartOfDay(zoneId).toInstant()
            return caffeine.filter { !it.timestamp.isBefore(midnight) }.sumOf { it.milligrams }
        }

    val caffeineCurve: List<Pair<Instant, Float>>
        get() = Caffeine.curve(caffeineDoses, caffeineWindowStart, now)

    /**
     * The curve continued past now, assuming nothing more is drunk.
     *
     * Shares its first point with the end of [caffeineCurve], so the measured
     * line and the projection join rather than showing a step between them.
     */
    val caffeineForecast: List<Pair<Instant, Float>>
        get() = Caffeine.curve(caffeineDoses, now, caffeineWindowEnd)

    /** The moment the near-term estimate is quoted for. */
    val caffeineForecastTime: Instant
        get() = now.plus(Duration.ofHours(CAFFEINE_FORECAST_HOURS))

    /** Projected milligrams still present [CAFFEINE_FORECAST_HOURS] from now. */
    val caffeineForecastEndMg: Float
        get() = Caffeine.levelAt(caffeineDoses, caffeineForecastTime)

    val caffeineForecastHours: Long
        get() = CAFFEINE_FORECAST_HOURS

    /**
     * The next 9 PM, which is the bedtime the evening estimate is quoted for.
     *
     * Rolls to tomorrow once tonight's has passed, so after 9 PM the figure
     * answers "what about tomorrow night" rather than restating the present.
     */
    val caffeineEveningTime: Instant
        get() {
            val tonight = today.atTime(CAFFEINE_EVENING_HOUR).atZone(zoneId).toInstant()
            return if (tonight.isAfter(now)) tonight
            else today.plusDays(1).atTime(CAFFEINE_EVENING_HOUR).atZone(zoneId).toInstant()
        }

    /** Projected milligrams still present at [caffeineEveningTime]. */
    val caffeineEveningMg: Float
        get() = Caffeine.levelAt(caffeineDoses, caffeineEveningTime)

    /**
     * Earliest dose still offered for correction: the left edge of the plot, or
     * midnight when that is earlier.
     *
     * Late at night midnight is further back than the window reaches, and a dose
     * counted in [caffeineTodayMg] that cannot be edited is a figure with no way
     * to fix it.
     */
    val caffeineEditableFrom: Instant
        get() = minOf(caffeineWindowStart, today.atStartOfDay(zoneId).toInstant())
}

private data class FastingBundle(
    val active: FastingSession?,
    val lastCompleted: FastingSession?,
    val sessions: List<FastingSession>,
    val plan: List<FastingPlanDay>,
    val extended: List<PlannedExtendedFast>,
)

private data class TodayBundle(
    val log: DailyLog?,
    val hydrationMl: Int,
    val waist: WaistEntry?,
    val bloodPressures: List<BloodPressureReading>,
    val pushups: Int,
    val squats: Int,
    val grip: GripStrengthEntry?,
)

private data class SettingsBundle(
    val goals: UserGoals?,
    val permission: HealthPermissionState,
    val isSyncing: Boolean,
    val missingPermissions: Set<String>,
    val glucoseRecovered: Int,
)

private data class MetabolicBundle(
    val glucose: List<BloodSugarReading>,
    val ketones: List<KetoneReading>,
    val caffeine: List<CaffeineIntake>,
    val glucoseWindow: GlucoseWindow,
    val settings: UserSettings,
)

class DashboardViewModel(
    private val repository: TrackerRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val healthState = MutableStateFlow(HealthPermissionState.NOT_GRANTED)
    private val syncing = MutableStateFlow(false)
    private val missingPermissions = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Readings the last refresh went back and recovered from gaps in the trace.
     *
     * Reported rather than absorbed quietly, for the reason the duplicate
     * collapse is: the chart is being looked at while this runs, and a line that
     * grows a new hour in it without explanation is harder to trust than one that
     * says where the hour came from.
     */
    private val glucoseRecovered = MutableStateFlow(0)
    private val glucoseWindow = MutableStateFlow(GlucoseWindow.DAY)

    /**
     * Drives the fast timer. One second is fine for a clock read-out; adherence
     * is recomputed on the same tick because the interval maths is trivial next
     * to the recomposition it feeds.
     */
    private val ticker: Flow<Instant> = flow {
        while (true) {
            emit(Instant.now())
            delay(1_000)
        }
    }

    private val today: LocalDate
        get() = LocalDate.now(zoneId)

    /** Passed straight to the Health Connect permission dialog. */
    val healthPermissions: Set<String>
        get() = repository.healthPermissions()

    private val fasting: Flow<FastingBundle>
        get() {
            val (weekStart, weekEnd) = weekBounds(today)
            return combine(
                repository.getActiveFastingSession(),
                repository.getLastCompletedFastingSession(),
                repository.getFastingSessionsOverlapping(weekStart, weekEnd),
                repository.getFastingPlan(),
                repository.getPlannedExtendedFasts(weekStart, weekEnd),
            ) { active, lastCompleted, sessions, plan, extended ->
                FastingBundle(active, lastCompleted, sessions, plan, extended)
            }
        }

    private val todayData: Flow<TodayBundle>
        get() {
            val date = today
            return combine(
                repository.getDailyLog(date),
                repository.getHydrationTotalMl(date),
                repository.getLatestWaistOnOrBefore(date),
                repository.getBloodPressureForDate(date),
                combine(
                    repository.getRepTotalForDate(MovementType.PUSHUP, date),
                    repository.getRepTotalForDate(MovementType.AIR_SQUAT, date),
                    repository.getLatestGripStrengthOnOrBefore(date),
                ) { pushups, squats, grip -> Triple(pushups, squats, grip) },
            ) { log, hydration, waist, bps, body ->
                TodayBundle(log, hydration, waist, bps, body.first, body.second, body.third)
            }
        }

    private val metabolic: Flow<MetabolicBundle>
        get() {
            val now = Instant.now()
            val since = now.minus(Duration.ofHours(GLUCOSE_WINDOW_HOURS))
            // Caffeine reaches further back than it plots: a dose from before the
            // window is still decaying inside it, and dropping it would start the
            // curve at the wrong height.
            val caffeineSince = now.minus(Duration.ofHours(Caffeine.RELEVANT_HISTORY_HOURS))
            return combine(
                repository.getBloodSugarSince(since),
                repository.getKetonesSince(since),
                repository.getCaffeineSince(caffeineSince),
                glucoseWindow,
                repository.getUserSettings(),
            ) { glucose, ketones, caffeine, window, settings ->
                // The query always covers the widest window; narrowing is left to
                // the chart, which clips to its own bounds. Switching windows is
                // then a redraw rather than a re-query.
                MetabolicBundle(glucose, ketones, caffeine, window, settings ?: UserSettings())
            }
        }

    private val healthData: Flow<Pair<HealthDaySnapshot?, Int?>>
        get() =
            combine(repository.getHealthSnapshot(today), repository.getBestMileSecondsAllTime()) {
                snapshot,
                bestMile ->
                snapshot to bestMile
            }

    val uiState: StateFlow<DashboardUiState> =
        combine(
                combine(fasting, ticker) { bundle, now -> bundle to now },
                todayData,
                metabolic,
                healthData,
                combine(
                    repository.getUserGoals(),
                    healthState,
                    syncing,
                    missingPermissions,
                    glucoseRecovered,
                ) { goals, state, isSyncing, missing, recovered ->
                    SettingsBundle(goals, state, isSyncing, missing, recovered)
                },
            ) { fastingAndNow, todayBundle, metabolicBundle, health, settings ->
                val (fastingBundle, now) = fastingAndNow
                val date = today
                val (weekStart, weekEnd) = weekBounds(date)

                DashboardUiState(
                    today = date,
                    now = now,
                    zoneId = zoneId,
                    activeFast = fastingBundle.active,
                    lastCompletedFast = fastingBundle.lastCompleted,
                    adherence =
                        FastingAdherence.score(
                            plan = fastingBundle.plan,
                            extendedFasts = fastingBundle.extended,
                            sessions = fastingBundle.sessions,
                            weekStart = weekStart,
                            weekEnd = weekEnd,
                            now = now,
                            zoneId = zoneId,
                        ),
                    hasPlan = fastingBundle.plan.isNotEmpty(),
                    snapshot = health.first,
                    bestMileSeconds = health.second,
                    hydrationMl = todayBundle.hydrationMl,
                    dailyLog = todayBundle.log ?: DailyLog(date),
                    waistCm = todayBundle.waist?.waistCm ?: DEFAULT_WAIST_CM,
                    hasWaistMeasurement = todayBundle.waist != null,
                    latestWaist = todayBundle.waist,
                    glucose = metabolicBundle.glucose,
                    ketones = metabolicBundle.ketones,
                    glucoseWindow = metabolicBundle.glucoseWindow,
                    settings = metabolicBundle.settings,
                    caffeine = metabolicBundle.caffeine,
                    latestGrip = todayBundle.grip,
                    latestBloodPressure = todayBundle.bloodPressures.lastOrNull(),
                    pushupsToday = todayBundle.pushups,
                    squatsToday = todayBundle.squats,
                    goals = settings.goals ?: UserGoals(),
                    healthState = settings.permission,
                    missingPermissions = settings.missingPermissions,
                    isSyncing = settings.isSyncing,
                    glucoseRecovered = settings.glucoseRecovered,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = DashboardUiState(),
            )

    init {
        seedPlanIfMissing()
        refreshHealth()
    }

    /** Week runs Monday to Monday, matching the default in user settings. */
    private fun weekBounds(date: LocalDate): Pair<Instant, Instant> {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return start.atStartOfDay(zoneId).toInstant() to
            start.plusWeeks(1).atStartOfDay(zoneId).toInstant()
    }

    /** Writes a 16:8 starting plan the first time the app runs, so adherence has a baseline. */
    private fun seedPlanIfMissing() {
        viewModelScope.launch {
            if (repository.getFastingPlan().first().isEmpty()) {
                repository.upsertFastingPlan(FastingAdherence.defaultPlan())
            }
        }
    }

    fun refreshHealth() {
        viewModelScope.launch {
            healthState.value = repository.healthPermissionState()
            missingPermissions.value = repository.missingHealthPermissions()
            if (healthState.value == HealthPermissionState.GRANTED) {
                syncing.value = true
                repository.syncHealthData(today)
                // After the day sync rather than before it: today's hole is the
                // one the ordinary sync is most likely to have just filled, and
                // going first would spend a query rediscovering that.
                glucoseRecovered.value =
                    repository.backfillGlucoseGaps().getOrDefault(0)
                syncing.value = false
            }
        }
    }

    fun addHydration(milliliters: Int) {
        viewModelScope.launch { repository.addHydration(milliliters) }
    }

    /** Adds to today's running total rather than replacing it, so several sittings accumulate. */
    fun logPages(pages: Int) {
        if (pages <= 0) return
        viewModelScope.launch {
            val current = repository.getDailyLog(today).first() ?: DailyLog(today)
            repository.upsertDailyLog(
                current.copy(bookPagesRead = (current.bookPagesRead ?: 0) + pages)
            )
        }
    }

    /** Overwrites the day's page count, for correcting a mis-logged total. */
    fun setPages(pages: Int) {
        viewModelScope.launch {
            val current = repository.getDailyLog(today).first() ?: DailyLog(today)
            repository.upsertDailyLog(current.copy(bookPagesRead = pages.coerceAtLeast(0)))
        }
    }

    fun logCaffeine(milligrams: Int, at: Instant = Instant.now()) {
        if (milligrams <= 0) return
        viewModelScope.launch { repository.addCaffeine(milligrams, at) }
    }

    /** Corrects a logged dose. A zero amount deletes it, which is the only way to undo a mistake. */
    fun updateCaffeine(intake: CaffeineIntake, milligrams: Int, at: Instant) {
        viewModelScope.launch {
            if (milligrams <= 0) repository.deleteCaffeine(intake)
            else repository.updateCaffeine(intake.copy(milligrams = milligrams, timestamp = at))
        }
    }

    fun deleteCaffeine(intake: CaffeineIntake) {
        viewModelScope.launch { repository.deleteCaffeine(intake) }
    }

    fun submitMood(vibe: Int, energy: Int, focus: Int) {
        viewModelScope.launch {
            val current = repository.getDailyLog(today).first() ?: DailyLog(today)
            repository.upsertDailyLog(current.copy(vibe = vibe, energy = energy, focus = focus))
        }
    }

    fun setWaistCm(cm: Float) {
        viewModelScope.launch { repository.setWaistCm(today, cm) }
    }

    fun addBloodPressure(systolic: Int, diastolic: Int) {
        viewModelScope.launch { repository.addBloodPressure(systolic, diastolic) }
    }

    fun setGlucoseWindow(window: GlucoseWindow) {
        glucoseWindow.value = window
    }

    /**
     * Turns the blood sugar smoothing on or off.
     *
     * Written to settings rather than held in the ViewModel so the master graph
     * draws the same line as this one -- two screens disagreeing about whether a
     * trace is filtered would be worse than either choice.
     */
    fun setSmoothGlucose(smooth: Boolean) {
        viewModelScope.launch {
            val current = repository.getUserSettings().first() ?: UserSettings()
            repository.upsertUserSettings(current.copy(smoothGlucose = smooth))
        }
    }

    /** Records one hand's grip for today, leaving the other hand's reading alone. */
    fun logGripStrengthKg(dominant: Boolean, kg: Float) {
        viewModelScope.launch { repository.setGripStrengthKg(today, dominant, kg) }
    }

    fun addKetone(ppm: Float) {
        viewModelScope.launch { repository.addKetone(ppm) }
    }

    fun addBloodSugar(mgDl: Int) {
        viewModelScope.launch { repository.addBloodSugar(mgDl) }
    }

    fun logReps(movement: MovementType, reps: Int) {
        if (reps <= 0) return
        viewModelScope.launch { repository.addExerciseSet(movement, reps) }
    }

    /**
     * Starts a fast whose goal comes from the weekly plan rather than from a
     * choice made here -- the Fasting screen is where the schedule is set.
     *
     * Falls back to 16 hours only when the plan has nothing scheduled nearby.
     */
    fun startFast() {
        viewModelScope.launch {
            val plan = repository.getFastingPlan().first()
            val now = Instant.now()
            val extended =
                repository
                    .getPlannedExtendedFasts(now.minus(Duration.ofDays(2)), now.plus(Duration.ofDays(3)))
                    .first()

            val goalMinutes =
                FastingAdherence.plannedGoalMinutesAt(plan, extended, now, zoneId)
                    ?: DEFAULT_GOAL_MINUTES
            repository.startFast(FastingAdherence.typeForMinutes(goalMinutes), goalMinutes, now)
        }
    }

    fun endFast() {
        viewModelScope.launch {
            uiState.value.activeFast?.let { repository.endFast(it) }
        }
    }

    /**
     * Moves the running fast's start time, for a fast begun before it was logged.
     *
     * Clamped to now: a start in the future would produce a negative duration
     * and score as if the fast had not begun.
     */
    fun setActiveFastStart(start: Instant) {
        viewModelScope.launch {
            val active = uiState.value.activeFast ?: return@launch
            repository.updateFastingSession(
                active.copy(startInstant = minOf(start, Instant.now()))
            )
        }
    }

    /** Ends the running fast at a chosen past time, for a Stop that was forgotten. */
    fun stopFastAt(end: Instant) {
        viewModelScope.launch {
            val active = uiState.value.activeFast ?: return@launch
            // An end before the start would invert the interval, which the
            // adherence maths treats as empty rather than rejecting.
            val safeEnd = minOf(end, Instant.now()).coerceAtLeastInstant(active.startInstant)
            repository.updateFastingSession(active.copy(endInstant = safeEnd))
        }
    }

    /** Corrects both ends of the most recently finished fast. */
    fun updateLastFast(start: Instant, end: Instant) {
        viewModelScope.launch {
            val last = uiState.value.lastCompletedFast ?: return@launch
            if (!start.isBefore(end)) return@launch
            repository.updateFastingSession(
                last.copy(startInstant = start, endInstant = minOf(end, Instant.now()))
            )
        }
    }

    private fun Instant.coerceAtLeastInstant(floor: Instant): Instant =
        if (isBefore(floor)) floor else this

    companion object {
        fun provideFactory(repository: TrackerRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DashboardViewModel(repository) as T
            }
    }
}
