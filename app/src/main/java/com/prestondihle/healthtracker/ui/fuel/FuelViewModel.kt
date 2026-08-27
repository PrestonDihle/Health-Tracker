package com.prestondihle.healthtracker.ui.fuel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.data.CaffeineIntake
import com.prestondihle.healthtracker.data.CreatineIntake
import com.prestondihle.healthtracker.data.FastingPlanDay
import com.prestondihle.healthtracker.data.FastingSession
import com.prestondihle.healthtracker.data.FastingType
import com.prestondihle.healthtracker.data.HydrationEntry
import com.prestondihle.healthtracker.data.PlannedExtendedFast
import com.prestondihle.healthtracker.data.Supplement
import com.prestondihle.healthtracker.data.SupplementSlot
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.domain.AdherenceResult
import com.prestondihle.healthtracker.domain.Caffeine
import com.prestondihle.healthtracker.domain.CaffeineDose
import com.prestondihle.healthtracker.domain.FastingAdherence
import com.prestondihle.healthtracker.domain.FastingDay
import com.prestondihle.healthtracker.domain.FastingStatistics
import com.prestondihle.healthtracker.domain.FastingStats
import com.prestondihle.healthtracker.repository.TrackerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** How far back the timeline draws. Two weeks fits a phone without scrolling forever. */
private const val TIMELINE_DAYS = 14L

/** Used only when the plan has no fast scheduled near now. */
private const val DEFAULT_GOAL_MINUTES = 16 * 60

/**
 * How far back a logged drink can still be corrected.
 *
 * A week, which is longer than the day the card totals and deliberately so. The
 * ordinary dose here is 100 ml logged by tapping a button several times in a
 * row, so a stray tap writes something identical to a real entry and is only
 * ever spotted later, from a day's figure looking too high. A list that ended at
 * midnight would offer the correction only while nobody yet knew it was needed.
 */
private const val HYDRATION_EDITABLE_DAYS = 7L

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
private val CAFFEINE_EVENING_HOUR = LocalTime.of(21, 0)

data class FuelUiState(
    val days: List<FastingPlanDay> = emptyList(),
    val extendedFasts: List<PlannedExtendedFast> = emptyList(),
    val adherence: AdherenceResult? = null,
    val weekStart: LocalDate = LocalDate.now(),
    val timeline: List<FastingDay> = emptyList(),
    val stats: FastingStats = FastingStats(),
    val today: LocalDate = LocalDate.now(),
    val now: Instant = Instant.now(),
    val activeFast: FastingSession? = null,
    /** Most recently finished fast, so a forgotten Stop can be corrected. */
    val lastCompletedFast: FastingSession? = null,
    val hasPlan: Boolean = false,
    /**
     * Water entry by entry over the last [HYDRATION_EDITABLE_DAYS] days, oldest first.
     *
     * Wider than the day the card totals, because the entry worth removing is
     * rarely noticed on the day it was written -- a stray tap logs 100 ml, which
     * is also the ordinary dose here, and it is indistinguishable from a real one
     * until the day's figure is looked at afterwards. A list that stopped at
     * midnight would be a correction that expires overnight.
     */
    val hydration: List<HydrationEntry> = emptyList(),
    /**
     * Doses reaching back beyond the plotted window.
     *
     * A coffee drunk before the left edge is still most of the level at the
     * edge, and dropping it would start the curve at zero and draw a climb that
     * never happened.
     */
    val caffeine: List<CaffeineIntake> = emptyList(),
    /** Creatine logged today, newest last. */
    val creatineToday: List<CreatineIntake> = emptyList(),
    /** The standing stack, morning first. */
    val supplements: List<Supplement> = emptyList(),
    /** Ids of the ones already taken today. */
    val supplementsTaken: Set<Long> = emptySet(),
    val goals: UserGoals = UserGoals(),
    val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    /** Plan rows in weekday order, filling gaps so all seven always render. */
    val orderedDays: List<FastingPlanDay>
        get() {
            val byDay = days.associateBy { it.dayOfWeek }
            return DayOfWeek.values().toList().map {
                byDay[it]
                    ?: FastingPlanDay(
                        it,
                        LocalTime.of(12, 0),
                        LocalTime.of(20, 0),
                        hasFeedingWindow = true,
                    )
            }
        }

    /** Planned fasting hours per week, for the summary line. */
    val plannedHoursPerWeek: Int
        get() =
            orderedDays
                .sumOf { day ->
                    // A no-eating day is a full 24 hours of planned fast.
                    if (!day.hasFeedingWindow) 24L * 60
                    else 24L * 60 - feedingMinutes(day)
                }
                .toInt() / 60

    private fun feedingMinutes(day: FastingPlanDay): Long =
        if (day.feedingEnd.isAfter(day.feedingStart)) {
            Duration.between(day.feedingStart, day.feedingEnd).toMinutes()
        } else {
            // Window wraps past midnight.
            24L * 60 - Duration.between(day.feedingEnd, day.feedingStart).toMinutes()
        }

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
     * Creatine taken today, in grams.
     *
     * Summed rather than stored, like every other daily total here -- doses are
     * the record, and a running column would be a second place for the same
     * number to live and disagree.
     */
    val creatineTodayGrams: Int
        get() = creatineToday.sumOf { it.grams }

    /**
     * How many of the standing stack have been ticked today.
     *
     * Intersected rather than counting the tick rows, so a dose left over from a
     * supplement that has since been removed cannot report more taken than there
     * are things to take.
     */
    val supplementsTakenCount: Int
        get() = supplements.count { it.id in supplementsTaken }

    val caffeineWindowStart: Instant
        get() = now.minus(Duration.ofHours(CAFFEINE_HALF_WINDOW_HOURS))

    /** Right edge of the caffeine chart, the same distance ahead as the start is behind. */
    val caffeineWindowEnd: Instant
        get() = now.plus(Duration.ofHours(CAFFEINE_HALF_WINDOW_HOURS))

    private val caffeineDoses: List<CaffeineDose>
        get() = caffeine.map { CaffeineDose(it.timestamp, it.milligrams) }

    /**
     * Millilitres drunk since midnight, which is what the goal is read against.
     *
     * Derived from the listed rows rather than queried as its own `SUM`, which
     * is what it used to be. Two reads of one table can disagree -- and the
     * moment an entry became deletable, a headline that had not caught up with
     * the list under it would be the first thing anybody noticed and the last
     * thing they trusted. Filtered to today because the list is deliberately
     * wider than the day: the same shape as [caffeineTodayMg].
     */
    val hydrationMl: Int
        get() {
            val midnight = today.atStartOfDay(zoneId).toInstant()
            return hydration.filter { !it.timestamp.isBefore(midnight) }.sumOf { it.milliliters }
        }

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

class FuelViewModel(
    private val repository: TrackerRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private fun weekBounds(date: LocalDate): Pair<Instant, Instant> {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return start.atStartOfDay(zoneId).toInstant() to
            start.plusWeeks(1).atStartOfDay(zoneId).toInstant()
    }

    private val today: LocalDate
        get() = LocalDate.now(zoneId)

    /**
     * Drives the fast timer. One second is fine for a clock read-out; adherence
     * is recomputed on the same tick because the interval maths is trivial next
     * to the recomposition it feeds.
     *
     * This is the flow that stops the tab reaching idle, which is why nothing
     * below the fold here can be asserted on in a render test -- see the
     * dashboard's note, which this inherited along with the fast card.
     */
    private val ticker: Flow<Instant> = flow {
        while (true) {
            emit(Instant.now())
            delay(1_000)
        }
    }

    /** What has been taken today, all of it keyed on the same date. */
    private val intake: Flow<IntakeBundle>
        get() {
            val date = today
            return combine(
                repository.getHydrationBetween(date.minusDays(HYDRATION_EDITABLE_DAYS - 1), date),
                repository.getCreatineForDate(date),
                repository.getSupplements(),
                repository.getSupplementsTakenOn(date),
                ::IntakeBundle,
            )
        }

    private val live: Flow<LiveBundle>
        get() {
            // Caffeine reaches further back than it plots: a dose from before the
            // window is still decaying inside it, and dropping it would start the
            // curve at the wrong height.
            val caffeineSince =
                Instant.now().minus(Duration.ofHours(Caffeine.RELEVANT_HISTORY_HOURS))
            return combine(
                repository.getActiveFastingSession(),
                repository.getLastCompletedFastingSession(),
                repository.getCaffeineSince(caffeineSince),
                repository.getUserGoals(),
            ) { active, lastCompleted, caffeine, goals ->
                LiveBundle(active, lastCompleted, caffeine, goals ?: UserGoals())
            }
        }

    val uiState: StateFlow<FuelUiState> =
        run {
            val today = LocalDate.now(zoneId)
            val (weekStart, weekEnd) = weekBounds(today)
            // Look four weeks ahead so upcoming extended fasts are visible and editable.
            val horizonEnd = weekEnd.plusSeconds(21 * 24 * 3600)

            val planned: Flow<PlanBundle> =
                combine(
                    repository.getFastingPlan(),
                    repository.getPlannedExtendedFasts(weekStart, horizonEnd),
                    repository.getFastingSessionsOverlapping(weekStart, weekEnd),
                    // Stats such as the longest fast are all-time, so this cannot be
                    // scoped to the week the adherence score uses.
                    repository.getAllFastingSessions(),
                    ::PlanBundle,
                )

            combine(planned, intake, live, ticker) { fasting, taken, running, now ->
                val plan = fasting.plan
                val extended = fasting.extended
                val weekSessions = fasting.weekSessions
                val allSessions = fasting.allSessions
                val timeline =
                    FastingStatistics.daysBetween(
                        sessions = allSessions,
                        from = today.minusDays(TIMELINE_DAYS - 1),
                        to = today,
                        zoneId = zoneId,
                        now = now,
                    )
                // Streaks look further back than the timeline draws, otherwise a
                // 20-day run would report as 14.
                val streakWindow =
                    FastingStatistics.daysBetween(
                        sessions = allSessions,
                        from = today.minusDays(364),
                        to = today,
                        zoneId = zoneId,
                        now = now,
                    )

                FuelUiState(
                    days = plan,
                    extendedFasts = extended,
                    adherence =
                        FastingAdherence.score(
                            plan = plan,
                            extendedFasts = extended,
                            sessions = weekSessions,
                            weekStart = weekStart,
                            weekEnd = weekEnd,
                            now = now,
                            zoneId = zoneId,
                        ),
                    weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                    timeline = timeline,
                    stats =
                        FastingStatistics.summarise(
                            sessions = allSessions,
                            days = streakWindow,
                            today = today,
                            zoneId = zoneId,
                            now = now,
                        ),
                    today = today,
                    now = now,
                    activeFast = running.active,
                    lastCompletedFast = running.lastCompleted,
                    hasPlan = plan.isNotEmpty(),
                    hydration = taken.hydration,
                    caffeine = running.caffeine,
                    creatineToday = taken.creatine,
                    supplements = taken.supplements,
                    supplementsTaken = taken.supplementsTaken,
                    goals = running.goals,
                    zoneId = zoneId,
                )
            }
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = FuelUiState(),
            )

    init {
        viewModelScope.launch {
            if (repository.getFastingPlan().first().isEmpty()) {
                repository.upsertFastingPlan(FastingAdherence.defaultPlan())
            }
        }
    }

    fun setFeedingWindow(day: DayOfWeek, start: LocalTime, end: LocalTime) {
        viewModelScope.launch {
            val existing = uiState.value.orderedDays.first { it.dayOfWeek == day }
            repository.upsertFastingPlanDay(
                existing.copy(feedingStart = start, feedingEnd = end)
            )
        }
    }

    /** Off means no eating that day: the full 24 hours become a planned fast. */
    fun setHasFeedingWindow(day: DayOfWeek, hasWindow: Boolean) {
        viewModelScope.launch {
            val existing = uiState.value.orderedDays.first { it.dayOfWeek == day }
            repository.upsertFastingPlanDay(existing.copy(hasFeedingWindow = hasWindow))
        }
    }

    fun addExtendedFast(startDate: LocalDate, type: FastingType) {
        val hours =
            when (type) {
                FastingType.EXTENDED_24 -> 24L
                FastingType.EXTENDED_36 -> 36L
                FastingType.EXTENDED_48 -> 48L
                else -> 24L
            }
        val start = startDate.atStartOfDay(zoneId).toInstant()
        viewModelScope.launch {
            repository.addPlannedExtendedFast(
                PlannedExtendedFast(
                    startInstant = start,
                    endInstant = start.plusSeconds(hours * 3600),
                    type = type,
                )
            )
        }
    }

    fun deleteExtendedFast(fast: PlannedExtendedFast) {
        viewModelScope.launch { repository.deletePlannedExtendedFast(fast) }
    }

    /**
     * Starts a fast at the length the plan says applies right now.
     *
     * Falls back to 16 hours only when the plan has nothing scheduled nearby.
     */
    fun startFast() {
        viewModelScope.launch {
            val plan = repository.getFastingPlan().first()
            val now = Instant.now()
            val extended =
                repository
                    .getPlannedExtendedFasts(
                        now.minus(Duration.ofDays(2)),
                        now.plus(Duration.ofDays(3)),
                    )
                    .first()

            val goalMinutes =
                FastingAdherence.plannedGoalMinutesAt(plan, extended, now, zoneId)
                    ?: DEFAULT_GOAL_MINUTES
            repository.startFast(FastingAdherence.typeForMinutes(goalMinutes), goalMinutes, now)
        }
    }

    fun endFast() {
        viewModelScope.launch { uiState.value.activeFast?.let { repository.endFast(it) } }
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
            repository.updateFastingSession(active.copy(startInstant = minOf(start, Instant.now())))
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

    fun addHydration(milliliters: Int) {
        viewModelScope.launch { repository.addHydration(milliliters) }
    }

    /** Corrects a logged drink. A zero amount deletes it, matching the caffeine rule. */
    fun updateHydration(entry: HydrationEntry, milliliters: Int, at: Instant) {
        viewModelScope.launch {
            if (milliliters <= 0) repository.deleteHydration(entry)
            else repository.updateHydration(entry.copy(milliliters = milliliters, timestamp = at))
        }
    }

    fun deleteHydration(entry: HydrationEntry) {
        viewModelScope.launch { repository.deleteHydration(entry) }
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

    fun logCreatine(grams: Int, at: Instant = Instant.now()) {
        if (grams <= 0) return
        viewModelScope.launch { repository.addCreatine(grams, at) }
    }

    fun deleteCreatine(intake: CreatineIntake) {
        viewModelScope.launch { repository.deleteCreatine(intake) }
    }

    fun addSupplement(name: String, dose: String, slot: SupplementSlot) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.addSupplement(name, dose, slot) }
    }

    fun deleteSupplement(supplement: Supplement) {
        viewModelScope.launch { repository.deleteSupplement(supplement) }
    }

    /** Ticks or unticks one supplement for today. */
    fun setSupplementTaken(supplement: Supplement, taken: Boolean) {
        viewModelScope.launch { repository.setSupplementTaken(supplement, today, taken) }
    }

    private fun Instant.coerceAtLeastInstant(floor: Instant): Instant =
        if (isBefore(floor)) floor else this

    companion object {
        fun provideFactory(repository: TrackerRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    FuelViewModel(repository) as T
            }
    }
}

/** The fasting plan and the sessions scored against it. */
private data class PlanBundle(
    val plan: List<FastingPlanDay>,
    val extended: List<PlannedExtendedFast>,
    val weekSessions: List<FastingSession>,
    val allSessions: List<FastingSession>,
)

/** What has gone in today. */
private data class IntakeBundle(
    val hydration: List<HydrationEntry>,
    val creatine: List<CreatineIntake>,
    val supplements: List<Supplement>,
    val supplementsTaken: Set<Long>,
)

/** The things whose answer depends on the current moment rather than on the day. */
private data class LiveBundle(
    val active: FastingSession?,
    val lastCompleted: FastingSession?,
    val caffeine: List<CaffeineIntake>,
    val goals: UserGoals,
)
