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
import com.prestondihle.healthtracker.data.HealthDaySnapshot
import com.prestondihle.healthtracker.data.KetoneReading
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.data.PlannedExtendedFast
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.domain.AdherenceResult
import com.prestondihle.healthtracker.domain.Caffeine
import com.prestondihle.healthtracker.domain.CaffeineDose
import com.prestondihle.healthtracker.domain.FastingAdherence
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

private const val GLUCOSE_WINDOW_HOURS = 24L

private const val CAFFEINE_WINDOW_HOURS = 24L

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
    val glucose: List<BloodSugarReading> = emptyList(),
    val ketones: List<KetoneReading> = emptyList(),
    val caffeine: List<CaffeineIntake> = emptyList(),
    val latestBloodPressure: BloodPressureReading? = null,
    val pushupsToday: Int = 0,
    val squatsToday: Int = 0,
    val goals: UserGoals = UserGoals(),
    val healthState: HealthPermissionState = HealthPermissionState.NOT_GRANTED,
    /** Requested Health Connect permissions still denied, if any. */
    val missingPermissions: Set<String> = emptySet(),
    val isSyncing: Boolean = false,
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
     * Calories eaten minus calories burned, or null unless both are known.
     *
     * Negative is a deficit. Falling back to zero for a missing half would show
     * a deficit the size of whichever figure happened to sync, which is worse
     * than showing nothing.
     */
    val netCalories: Int?
        get() {
            val eaten = snapshot?.dietaryCalories ?: return null
            val burned = snapshot?.totalCalories ?: return null
            return eaten - burned
        }

    val glucoseWindowStart: Instant
        get() = now.minus(Duration.ofHours(GLUCOSE_WINDOW_HOURS))

    val caffeineWindowStart: Instant
        get() = now.minus(Duration.ofHours(CAFFEINE_WINDOW_HOURS))

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
    val waistCm: Float?,
    val bloodPressures: List<BloodPressureReading>,
    val pushups: Int,
    val squats: Int,
)

private data class SettingsBundle(
    val goals: UserGoals?,
    val permission: HealthPermissionState,
    val isSyncing: Boolean,
    val missingPermissions: Set<String>,
)

private data class MetabolicBundle(
    val glucose: List<BloodSugarReading>,
    val ketones: List<KetoneReading>,
    val caffeine: List<CaffeineIntake>,
)

class DashboardViewModel(
    private val repository: TrackerRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val healthState = MutableStateFlow(HealthPermissionState.NOT_GRANTED)
    private val syncing = MutableStateFlow(false)
    private val missingPermissions = MutableStateFlow<Set<String>>(emptySet())

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
                ) { pushups, squats -> pushups to squats },
            ) { log, hydration, waist, bps, reps ->
                TodayBundle(log, hydration, waist?.waistCm, bps, reps.first, reps.second)
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
            ) { glucose, ketones, caffeine ->
                MetabolicBundle(glucose, ketones, caffeine)
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
                combine(repository.getUserGoals(), healthState, syncing, missingPermissions) {
                    goals,
                    state,
                    isSyncing,
                    missing ->
                    SettingsBundle(goals, state, isSyncing, missing)
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
                    waistCm = todayBundle.waistCm ?: DEFAULT_WAIST_CM,
                    hasWaistMeasurement = todayBundle.waistCm != null,
                    glucose = metabolicBundle.glucose,
                    ketones = metabolicBundle.ketones,
                    caffeine = metabolicBundle.caffeine,
                    latestBloodPressure = todayBundle.bloodPressures.lastOrNull(),
                    pushupsToday = todayBundle.pushups,
                    squatsToday = todayBundle.squats,
                    goals = settings.goals ?: UserGoals(),
                    healthState = settings.permission,
                    missingPermissions = settings.missingPermissions,
                    isSyncing = settings.isSyncing,
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

    fun addKetone(mmolL: Float) {
        viewModelScope.launch { repository.addKetone(mmolL) }
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
