package com.prestondihle.healthtracker.ui.wellness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.data.BloodPressureReading
import com.prestondihle.healthtracker.data.BloodSugarReading
import com.prestondihle.healthtracker.data.CaffeineIntake
import com.prestondihle.healthtracker.data.CreatineIntake
import com.prestondihle.healthtracker.data.DailyLog
import com.prestondihle.healthtracker.data.FastingPlanDay
import com.prestondihle.healthtracker.data.FastingSession
import com.prestondihle.healthtracker.data.FastingType
import com.prestondihle.healthtracker.data.GripStrengthEntry
import com.prestondihle.healthtracker.data.HealthDaySnapshot
import com.prestondihle.healthtracker.data.HeartRateBucket
import com.prestondihle.healthtracker.data.KetoneReading
import com.prestondihle.healthtracker.data.MealEntry
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.data.PlankSession
import com.prestondihle.healthtracker.data.PlannedExtendedFast
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.data.UserSettings
import com.prestondihle.healthtracker.data.Supplement
import com.prestondihle.healthtracker.data.SupplementSlot
import com.prestondihle.healthtracker.data.WaistEntry
import com.prestondihle.healthtracker.domain.AdherenceResult
import com.prestondihle.healthtracker.domain.Caffeine
import com.prestondihle.healthtracker.domain.CaffeineDose
import com.prestondihle.healthtracker.domain.FastingAdherence
import com.prestondihle.healthtracker.domain.Glucose
import com.prestondihle.healthtracker.domain.GlucoseSmoothing
import com.prestondihle.healthtracker.domain.MealClockTimes
import com.prestondihle.healthtracker.domain.MealDuplicates
import com.prestondihle.healthtracker.domain.MealResponse
import com.prestondihle.healthtracker.domain.MealResponses
import com.prestondihle.healthtracker.domain.SleepNight
import com.prestondihle.healthtracker.domain.UsualIntake
import com.prestondihle.healthtracker.health.HealthPermissionState
import com.prestondihle.healthtracker.repository.TrackerRepository
import com.prestondihle.healthtracker.ui.components.DayPoint
import com.prestondihle.healthtracker.ui.today.MAX_DAY_OFFSET
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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

/**
 * How far back the heart rate under the hypnogram is fetched.
 *
 * Thirty-six hours covers the whole of last night whether the card is read at
 * seven in the morning or eleven at night, without pulling a week of five-minute
 * buckets to draw eight hours of them. The chart clips to the night's own bounds,
 * so the surplus is never seen.
 */
private val SLEEP_HEART_RATE_HISTORY: Duration = Duration.ofHours(36)

/**
 * How far either side of a night's own bounds the heart rate is fetched.
 *
 * A margin rather than an exact clip: the buckets are five minutes wide and are
 * stamped at their start, so the one containing the moment the reader fell
 * asleep begins before the night does and would be dropped by a read that
 * matched the bounds exactly.
 */
private val SLEEP_HEART_RATE_MARGIN: Duration = Duration.ofMinutes(15)

/** Used only when the plan has no fast scheduled near now. */
private const val DEFAULT_GOAL_MINUTES = 16 * 60

/**
 * How many days of daily-log history the Wellness trends read back over.
 *
 * Two weeks matches the Trends screen's default window, so the mood and reading
 * charts shown next to their inputs here read the same as their fuller versions
 * on Activity.
 */
private const val LOG_HISTORY_DAYS = 14L

/**
 * How far back the Log tab's meal list reaches -- a fixed day, not the master
 * chart's window, so the list reads the same however that chart is zoomed.
 */
private const val MEAL_WINDOW_HOURS = 24L

/**
 * How far back meals are *loaded*, as against how far back the list shows them.
 *
 * The stamped-time rule is a repeat detector, so it can only see a repeat that
 * is inside the data it is given -- and the phone's stamp lands on 10:00:00
 * about once a day. Judged over the 24 hours the list displays, the single
 * 10:00 meal in that span has nothing to repeat against and is read as a
 * measurement: the row prints a plausible clock time instead of "set time", and
 * since the response scoring landed it also prints a rise and a return measured
 * from an hour nobody ate in. That is the exact failure the null-for-stamped
 * rule exists to prevent, arriving through the window rather than through the
 * rule.
 *
 * Two weeks holds a dozen or more of them, so the repeat is unmissable. This is
 * the same shape as the absorption curves loading past the left edge: **load
 * wider than you display, because the judgement needs more than the picture
 * does.** The list itself still stops at [MEAL_WINDOW_HOURS], and so does the
 * duplicate count printed under it.
 */
private const val MEAL_STAMP_HISTORY_DAYS = 14L

/**
 * What Log's usual row can offer, or nulls where there is no habit to read.
 *
 * Both are allowed to be absent, and separately: a reader who drinks water and
 * not coffee gets one button, not a broken pair.
 */
data class UsualIntakeState(val lastCaffeineMg: Int? = null, val usualWaterMl: Int? = null)

/**
 * How often the running plank clock is redrawn.
 *
 * A quarter second rather than a full one, because the read-out is the thing the
 * reader is watching: at a one-second tick the digits visibly lag the moment the
 * Stop button is pressed, and a timer whose figure arrives late is a timer that
 * reads as inaccurate whether or not it is. The stored value is still whole
 * seconds -- this only governs the drawing.
 */
private const val PLANK_TICK_MILLIS = 250L

/**
 * How far back the plank card offers a hold for correction.
 *
 * A week, which is `HYDRATION_EDITABLE_DAYS`' figure and its argument: a wrong
 * entry is noticed a day or two later, from a figure that looks too high rather
 * than at the moment it is written. It matters more here than for a drink,
 * because the chart plots each day's **maximum** -- so a hold saved by mistake
 * does not average away with the days around it, it stands as that day's number
 * for ever.
 */
private const val PLANK_EDITABLE_DAYS = 7L

/**
 * Everything the plank card draws: the timer, and the holds it can still correct.
 *
 * Named for the card rather than the timer because it stopped being only a timer
 * when the correction list arrived -- the rule this codebase keeps for
 * `WellnessViewModel` itself: name a thing after what it now is.
 *
 * The timer's middle state is the feature. A hold that went straight to the
 * database on Stop would make every fumbled start, every phone picked up to
 * check the time and every plank abandoned at ten seconds into a row on the
 * chart -- and the chart plots the day's *best*, so a bad row is not merely
 * noise, it is a personal best nobody performed. [heldSeconds] with [running]
 * false is a hold waiting to be kept or thrown away.
 *
 * [recent] is the second half of that same argument, arriving after the fact.
 * Discard covers the mistake caught in the moment; the list covers the one
 * noticed on the chart a day later, which is the only other way a wrong maximum
 * gets in.
 *
 * Held in the view model rather than in the card so it survives a tab switch: a
 * reader who starts a plank and glances at Today would otherwise come back to a
 * stopped clock, which is the one moment this control cannot afford to lose.
 */
data class PlankCardState(
    /** When the current hold began, or null when nothing is running. */
    val startedAt: Instant? = null,
    /** A finished hold awaiting Save or Discard, in seconds. */
    val pendingSeconds: Int? = null,
    val now: Instant = Instant.now(),
    /** The day's longest hold so far, for the read-out under the clock. */
    val bestTodaySeconds: Int? = null,
    /** Holds still open to correction, newest first. See [PLANK_EDITABLE_DAYS]. */
    val recent: List<PlankSession> = emptyList(),
) {
    val running: Boolean
        get() = startedAt != null

    /** Seconds on the clock right now: the live hold, or the one awaiting a decision. */
    val heldSeconds: Int
        get() =
            startedAt?.let { Duration.between(it, now).seconds.coerceAtLeast(0L).toInt() }
                ?: pendingSeconds
                ?: 0

    /** True while a finished hold is on screen with neither button pressed yet. */
    val awaitingDecision: Boolean
        get() = startedAt == null && pendingSeconds != null
}

data class WellnessUiState(
    val today: LocalDate = LocalDate.now(),
    val now: Instant = Instant.now(),
    val activeFast: FastingSession? = null,
    /** Most recently finished fast, so a forgotten Stop can be corrected. */
    val lastCompletedFast: FastingSession? = null,
    val adherence: AdherenceResult? = null,
    val hasPlan: Boolean = false,
    val snapshot: HealthDaySnapshot? = null,
    val bestMileSeconds: Int? = null,
    /**
     * The most recent night, or null before anything has synced.
     *
     * The latest rather than one belonging to [today], because a night is not a
     * day: last night began yesterday and the card is read this morning. Keying
     * it to a date would leave the card blank until the small hours of the next
     * one.
     */
    val sleep: SleepNight? = null,
    /** Buckets spanning the night, for the trace drawn under the hypnogram. */
    val sleepHeartRate: List<HeartRateBucket> = emptyList(),
    /**
     * Which night the card is showing, counted back from the newest, and how
     * many there are to walk through.
     *
     * The count is what tells the back arrow when it has run out. Without it the
     * stepper would keep offering another night and hand back an empty card, and
     * the reader would have no way to tell "nothing recorded" from "nothing
     * left".
     */
    val nightOffset: Int = 0,
    val nightCount: Int = 0,
    /**
     * Which day the activity card is reporting, and how far back that is.
     *
     * Only that card reads it -- [caloriesEaten] and [netCalories] are derived
     * from the same snapshot and are printed nowhere else on this screen. Every
     * other figure here is still today's, which is why this is a field on the
     * state rather than a mode the whole screen is in.
     */
    val activityDay: LocalDate = LocalDate.now(),
    val activityDayOffset: Long = 0L,
    val dailyLog: DailyLog = DailyLog(LocalDate.now()),
    /** Recent daily logs for the mood and reading trends shown next to their inputs. */
    val logHistory: List<DailyLog> = emptyList(),
    val historyStart: LocalDate = LocalDate.now().minusDays(LOG_HISTORY_DAYS - 1),
    val historyEnd: LocalDate = LocalDate.now(),
    val waistCm: Float = DEFAULT_WAIST_CM,
    val hasWaistMeasurement: Boolean = false,
    /** Most recent stored waist measurement, with the date it was taken. */
    val latestWaist: WaistEntry? = null,
    val glucose: List<BloodSugarReading> = emptyList(),
    val ketones: List<KetoneReading> = emptyList(),
    /** Recent meals, for the last-24-hours list on the Log tab. */
    val meals: List<MealEntry> = emptyList(),
    val glucoseWindow: GlucoseWindow = GlucoseWindow.DAY,
    val settings: UserSettings = UserSettings(),
    val caffeine: List<CaffeineIntake> = emptyList(),
    /** Most recent grip measurement on or before today, with the date it was taken. */
    val latestGrip: GripStrengthEntry? = null,
    /** Creatine logged today, newest last. */
    val creatineToday: List<CreatineIntake> = emptyList(),
    /** The standing stack, morning first. */
    val supplements: List<Supplement> = emptyList(),
    /** Ids of the ones already taken today. */
    val supplementsTaken: Set<Long> = emptySet(),
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

    /** Every date in the trend window, oldest first, including days with nothing logged. */
    private val historyDays: List<LocalDate>
        get() = (0..ChronoUnit.DAYS.between(historyStart, historyEnd)).map { historyStart.plusDays(it) }

    /**
     * One point per day over the trend window, null on days the field is unset.
     *
     * The same shape the Trends screen draws from, so the mood and reading charts
     * here match their fuller counterparts on Activity.
     */
    fun logSeries(valueOf: (DailyLog) -> Float?): List<DayPoint> {
        val byDate = logHistory.associate { it.date to valueOf(it) }
        return historyDays.map { DayPoint(it, byDate[it]) }
    }

    /** Left edge of the meal list's fixed 24-hour window. */
    private val mealWindowStart: Instant
        get() = now.minus(Duration.ofHours(MEAL_WINDOW_HOURS))

    /**
     * Meals counted once, collapsing the duplicate records some sources write for
     * one meal -- the same rule the Today chart uses so the two never disagree.
     *
     * Spans everything loaded, which reaches two weeks back rather than one day:
     * this is what the stamped-time rule reads, and it needs the history.
     */
    private val distinctMeals: List<MealEntry>
        get() = MealDuplicates.collapse(meals.sortedBy { it.id })

    /**
     * How many records the collapse absorbed *within the displayed window*, so
     * the list can own up to it.
     *
     * Counted over the window rather than over everything loaded, which are no
     * longer the same span. The sentence under the list explains the figures
     * above it, so a count reaching back a fortnight would claim credit for
     * merges the reader cannot see and make today's total look wrong.
     */
    val duplicatesCollapsed: Int
        get() = meals.count { it.timestamp in mealWindowStart..now } - mealsInWindow.size

    /** Meals eaten in the last 24 hours, newest first. */
    val mealsInWindow: List<MealEntry>
        get() =
            distinctMeals
                .filter { it.timestamp in mealWindowStart..now }
                .sortedByDescending { it.timestamp }

    /**
     * Times of day that are a stamp rather than a measurement.
     *
     * The rule itself lives in [MealClockTimes], because the response scoring on
     * Fuel asks the same question over a different window and the two answers
     * must not be allowed to drift apart.
     */
    private val stampedTimesOfDay: Set<LocalTime>
        get() = MealClockTimes.stampedTimesOfDay(distinctMeals, zoneId)

    /** Whether a meal carries a real clock time or only the date it was eaten on. */
    fun hasClockTime(meal: MealEntry): Boolean =
        MealClockTimes.hasClockTime(meal, stampedTimesOfDay, zoneId)

    /** Meals in the window carrying a stamped time rather than a measured one. */
    val undatedMealsInWindow: List<MealEntry>
        get() = mealsInWindow.filterNot(::hasClockTime)

    /**
     * What each meal in the window did to the blood sugar, keyed by meal id.
     *
     * Absent rather than null-valued for a meal that could not be scored, so a
     * lookup miss is the single "unmeasured" case rather than two.
     *
     * Goes through `rank` with no real limit rather than calling `score` per
     * meal: `rank` sorts the trace once and binary-searches each meal's slice out
     * of it, where a loop would re-sort seventy-two hours of readings for every
     * row on the card.
     */
    val mealResponses: Map<Long, MealResponse>
        get() =
            MealResponses.rank(
                    meals = mealsInWindow,
                    readings = glucose.map { it.timestamp to it.mgDl },
                    hasClockTime = ::hasClockTime,
                    limit = mealsInWindow.size,
                )
                .associate { it.meal.id to it.response }

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

    /**
     * The slot the clock is currently in, so the usual row offers today's next
     * handful rather than always the morning's.
     */
    val currentSupplementSlot: SupplementSlot
        get() = UsualIntake.slotAt(now.atZone(zoneId).toLocalTime())

    /**
     * What is left to take in [currentSupplementSlot].
     *
     * Intersected against the standing list rather than counted off the tick
     * rows, the rule [supplementsTakenCount] already follows: a dose orphaned by
     * a deleted supplement must not make the row disappear as though the slot
     * were done.
     */
    val outstandingInSlot: List<Supplement>
        get() =
            supplements.filter { it.slot == currentSupplementSlot && it.id !in supplementsTaken }

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
    val waist: WaistEntry?,
    val bloodPressures: List<BloodPressureReading>,
    val creatine: List<CreatineIntake>,
    val logHistory: List<DailyLog>,
    val body: BodyBundle,
)

/** Bundled because combine's typed overloads stop at five sources. */
private data class BodyBundle(
    val pushups: Int,
    val squats: Int,
    val grip: GripStrengthEntry?,
    val supplements: List<Supplement>,
    val supplementsTaken: Set<Long>,
)

private data class SettingsBundle(
    val goals: UserGoals?,
    val permission: HealthPermissionState,
    val isSyncing: Boolean,
    val missingPermissions: Set<String>,
    val glucoseRecovered: Int,
)

/**
 * Everything read from Health Connect's cache, including last night.
 *
 * The heart rate buckets ride along here rather than in [MetabolicBundle]
 * because on this screen they exist for one purpose: to be drawn under the
 * hypnogram. Nothing else on Today plots a heart rate.
 */
private data class HealthBundle(
    val snapshot: HealthDaySnapshot?,
    val bestMileSeconds: Int?,
    val sleep: SleepNight?,
    val heartRate: List<HeartRateBucket>,
    val nightOffset: Int,
    val nightCount: Int,
    val day: LocalDate,
    val dayOffset: Long,
)

private data class MetabolicBundle(
    val glucose: List<BloodSugarReading>,
    val ketones: List<KetoneReading>,
    val caffeine: List<CaffeineIntake>,
    val meals: List<MealEntry>,
    val glucoseWindow: GlucoseWindow,
    val settings: UserSettings,
)

class WellnessViewModel(
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
     * How many nights back the sleep card is showing, zero being the newest.
     *
     * Nights, not days. The card walks the rows the watch actually wrote, so a
     * weekend the watch was on the charger costs no taps -- where a walk by date
     * would spend two of them on empty cards to get past it.
     */
    private val nightOffset = MutableStateFlow(0)

    /**
     * How many days back the activity card is showing, zero being today.
     *
     * Its own, and not shared with the Today tab's: the two are separate cards on
     * separate screens, and a tab switch that silently moved the other one is a
     * card whose heading changed while nobody was looking at it.
     */
    private val dayOffset = MutableStateFlow(0L)

    /** The read fired by a tap on the day stepper, cancelled by the next tap. */
    private var daySyncJob: Job? = null

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
                repository.getLatestWaistOnOrBefore(date),
                combine(
                    repository.getBloodPressureForDate(date),
                    repository.getCreatineForDate(date),
                    repository.getDailyLogs(date.minusDays(LOG_HISTORY_DAYS - 1), date),
                ) { bps, creatine, logHistory -> Triple(bps, creatine, logHistory) },
                combine(
                    repository.getRepTotalForDate(MovementType.PUSHUP, date),
                    repository.getRepTotalForDate(MovementType.AIR_SQUAT, date),
                    repository.getLatestGripStrengthOnOrBefore(date),
                    repository.getSupplements(),
                    repository.getSupplementsTakenOn(date),
                ) { pushups, squats, grip, supplements, taken ->
                    BodyBundle(pushups, squats, grip, supplements, taken)
                },
            ) { log, waist, bpCreatineHistory, body ->
                TodayBundle(
                    log,
                    waist,
                    bpCreatineHistory.first,
                    bpCreatineHistory.second,
                    bpCreatineHistory.third,
                    body,
                )
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
            // Wider than the list shows, so the stamped-time rule has enough
            // meals to spot a repeat in. See MEAL_STAMP_HISTORY_DAYS.
            val mealsSince = now.minus(Duration.ofDays(MEAL_STAMP_HISTORY_DAYS))
            return combine(
                repository.getBloodSugarSince(since),
                repository.getKetonesSince(since),
                // Paired because combine's typed overloads stop at five sources and
                // the meal list rides on this same food-and-blood bundle.
                combine(
                    repository.getCaffeineSince(caffeineSince),
                    repository.getMealsSince(mealsSince),
                ) { caffeine, meals -> caffeine to meals },
                glucoseWindow,
                repository.getUserSettings(),
            ) { glucose, ketones, caffeineMeals, window, settings ->
                // The query always covers the widest window; narrowing is left to
                // the chart, which clips to its own bounds. Switching windows is
                // then a redraw rather than a re-query.
                MetabolicBundle(
                    glucose,
                    ketones,
                    caffeineMeals.first,
                    caffeineMeals.second,
                    window,
                    settings ?: UserSettings(),
                )
            }
        }

    /**
     * The night on screen, the day on screen, and the heart rate drawn under the
     * hypnogram.
     *
     * Nested rather than one `combine` because the heart-rate window depends on
     * which night was found: a fixed span back from now is right for last night
     * and useless for one three weeks ago, where it would fetch every bucket
     * since to draw eight hours of them and clip the rest away.
     *
     * The two offsets are separate and stay that way. A night is not a day here
     * -- the sleep card walks the rows the watch wrote and the activity card
     * walks the calendar -- and one control moving both would leave the reader
     * with no way to compare a night against the day that followed it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val healthData: Flow<HealthBundle> =
        combine(nightOffset, dayOffset, ::Pair).flatMapLatest { (back, daysBack) ->
            repository.getSleepNight(back).flatMapLatest { night ->
                // A night's own bounds, padded, when there is a night; otherwise
                // the old fixed span, which is what an empty card asks for.
                val heartRate =
                    if (night == null) {
                        repository.getHeartRateSince(Instant.now().minus(SLEEP_HEART_RATE_HISTORY))
                    } else {
                        repository.getHeartRateBetween(
                            night.start.minus(SLEEP_HEART_RATE_MARGIN),
                            night.end.plus(SLEEP_HEART_RATE_MARGIN),
                        )
                    }
                val day = today.minusDays(daysBack)
                combine(
                    repository.getHealthSnapshot(day),
                    repository.getBestMileSecondsAllTime(),
                    heartRate,
                    repository.countSleepNights(),
                ) { snapshot, bestMile, buckets, nights ->
                    HealthBundle(snapshot, bestMile, night, buckets, back, nights, day, daysBack)
                }
            }
        }

    val uiState: StateFlow<WellnessUiState> =
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

                WellnessUiState(
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
                    snapshot = health.snapshot,
                    bestMileSeconds = health.bestMileSeconds,
                    sleep = health.sleep,
                    sleepHeartRate = health.heartRate,
                    nightOffset = health.nightOffset,
                    nightCount = health.nightCount,
                    activityDay = health.day,
                    activityDayOffset = health.dayOffset,
                    dailyLog = todayBundle.log ?: DailyLog(date),
                    logHistory = todayBundle.logHistory,
                    historyStart = date.minusDays(LOG_HISTORY_DAYS - 1),
                    historyEnd = date,
                    waistCm = todayBundle.waist?.waistCm ?: DEFAULT_WAIST_CM,
                    hasWaistMeasurement = todayBundle.waist != null,
                    latestWaist = todayBundle.waist,
                    glucose = metabolicBundle.glucose,
                    ketones = metabolicBundle.ketones,
                    meals = metabolicBundle.meals,
                    glucoseWindow = metabolicBundle.glucoseWindow,
                    settings = metabolicBundle.settings,
                    caffeine = metabolicBundle.caffeine,
                    latestGrip = todayBundle.body.grip,
                    creatineToday = todayBundle.creatine,
                    supplements = todayBundle.body.supplements,
                    supplementsTaken = todayBundle.body.supplementsTaken,
                    latestBloodPressure = todayBundle.bloodPressures.lastOrNull(),
                    pushupsToday = todayBundle.body.pushups,
                    squatsToday = todayBundle.body.squats,
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
                initialValue = WellnessUiState(),
            )

    /**
     * The one-tap suggestions on Log, derived from a month of recent entries.
     *
     * Its own flow rather than two more sources on [uiState], for two reasons.
     * That combine is already at its typed limit, and -- more to the point -- the
     * windows are wrong: [uiState] loads caffeine over a few hours because the
     * decay curve needs no more, and a habit read from that window would vanish
     * the moment the reader had not had a coffee since breakfast. This is the
     * load-wider-than-you-display rule with the two spans genuinely far apart.
     *
     * Hydration is read only here. Nothing else on Log or Wellness draws it --
     * the card and its correction list are on Fuel -- so this is the whole reason
     * the table is queried on this screen at all.
     */
    val usual: StateFlow<UsualIntakeState> =
        combine(
                repository.getCaffeineSince(
                    Instant.now().minus(Duration.ofDays(UsualIntake.HISTORY_DAYS))
                ),
                repository.getHydrationBetween(
                    LocalDate.now(zoneId).minusDays(UsualIntake.HISTORY_DAYS - 1),
                    LocalDate.now(zoneId),
                ),
            ) { caffeine, hydration ->
                UsualIntakeState(
                    lastCaffeineMg =
                        UsualIntake.lastDose(caffeine.map { it.timestamp to it.milligrams }),
                    usualWaterMl =
                        UsualIntake.usualVolume(
                            hydration.map { it.timestamp to it.milliliters }
                        ),
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = UsualIntakeState(),
            )

    private val plankStartedAt = MutableStateFlow<Instant?>(null)
    private val plankPendingSeconds = MutableStateFlow<Int?>(null)

    /**
     * The plank timer, ticking **only while a plank is running**.
     *
     * That conditional tick is the whole reason this is not folded into
     * [uiState]. A ticker that ran unconditionally would put a third permanently
     * un-idle screen in this app, and this file's own history says what that
     * costs: a screen that never reaches idle cannot be scrolled in a test, which
     * is why the sleep and mood cards have to be composed on their own. Here the
     * flow emits once and stops whenever no plank is in progress, so Log stays
     * idle for every test that is not deliberately holding one -- and none is.
     *
     * `flatMapLatest` rather than a `while` loop guarded by a flag: starting a
     * second plank cancels the first ticker rather than leaving it running
     * behind the new one, which is a leak a flag would not prevent.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val plank: StateFlow<PlankCardState> =
        combine(
                plankStartedAt.flatMapLatest { started ->
                    if (started == null) flowOf(Instant.now())
                    else flow {
                        while (true) {
                            emit(Instant.now())
                            delay(PLANK_TICK_MILLIS)
                        }
                    }
                },
                plankStartedAt,
                plankPendingSeconds,
                repository.getBestPlankSecondsForDate(LocalDate.now(zoneId)),
                repository.getPlanksBetween(
                    LocalDate.now(zoneId).minusDays(PLANK_EDITABLE_DAYS - 1),
                    LocalDate.now(zoneId),
                ),
            ) { now, started, pending, bestToday, recent ->
                PlankCardState(
                    startedAt = started,
                    pendingSeconds = pending,
                    now = now,
                    bestTodaySeconds = bestToday,
                    // Newest first, like every other correction list here: the
                    // row most likely to be wrong is the one just written.
                    recent = recent.sortedByDescending { it.timestamp },
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = PlankCardState(),
            )

    /** Begins a hold, discarding any decision still on screen. */
    fun startPlank() {
        plankPendingSeconds.value = null
        plankStartedAt.value = Instant.now()
    }

    /**
     * Stops the clock and offers the hold, rather than writing it.
     *
     * Nothing reaches the database here. The decision the reader is about to make
     * is the point of the whole control -- see [PlankCardState].
     *
     * A hold of zero seconds is dropped outright rather than offered: that is a
     * Start immediately followed by a Stop, which is a mis-tap in both plausible
     * readings, and offering to save it would put a dialog in front of somebody
     * who has already told us twice that they are not planking.
     */
    fun stopPlank() {
        val started = plankStartedAt.value ?: return
        val held = Duration.between(started, Instant.now()).seconds.coerceAtLeast(0L).toInt()
        plankStartedAt.value = null
        plankPendingSeconds.value = held.takeIf { it > 0 }
    }

    /** Keeps the hold waiting on screen, timestamped at the moment it is saved. */
    fun savePlank() {
        val seconds = plankPendingSeconds.value ?: return
        plankPendingSeconds.value = null
        viewModelScope.launch { repository.addPlank(seconds) }
    }

    /** Throws the hold away. Nothing was ever written, so there is nothing to undo. */
    fun discardPlank() {
        plankPendingSeconds.value = null
    }

    /**
     * Corrects a hold already on disk -- its length, its time, or both.
     *
     * The counterpart to Discard rather than a duplicate of it: Discard catches
     * the mistake noticed in the moment, this catches the one noticed on the
     * chart a day later. Both exist because the trend plots each day's
     * *maximum*, so a wrong hold does not average away with its neighbours.
     */
    fun updatePlank(session: PlankSession, seconds: Int, at: Instant) {
        viewModelScope.launch { repository.updatePlank(session, seconds, at) }
    }

    /**
     * Removes a hold outright.
     *
     * Deleted for real rather than hidden, which is `HydrationEntry`'s rule and
     * its reason: a plank is hand-timed end to end, so there is no upstream
     * record to arrive again on the next sync and nothing for a hidden flag to
     * keep out. A `hidden` column here would only leave the row to be counted by
     * something that forgot to filter.
     */
    fun deletePlank(session: PlankSession) {
        viewModelScope.launch { repository.deletePlank(session) }
    }

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
                // The sleep card needs this and the day sync cannot give it to
                // it. `syncHealthData` fills the snapshot's `sleepMinutes` --
                // one number, which is what Activity shows -- while the stages
                // and the heart rate drawn under them live in the time-series
                // caches, and those are only ever written by `syncTimeSeries`.
                // Without this the card sits on "No sleep recorded yet" until
                // the reader happens to open the Master screen, on a phone whose
                // Activity card is displaying the very night it says it has not
                // got.
                //
                // Over the same span the card queries. Syncing a narrower window
                // than the chart reads would leave the earlier hours of a night
                // permanently blank rather than merely late.
                repository.syncTimeSeries(
                    Instant.now().minus(SLEEP_HEART_RATE_HISTORY),
                    Instant.now(),
                )
                glucoseRecovered.value =
                    repository.backfillGlucoseGaps().getOrDefault(0)
                // The days that ended while nobody was looking. Wellness plots
                // more of the daily snapshot than any other tab -- weight,
                // waist, resting heart rate, sleep hours, blood oxygen -- so a
                // day frozen at the last moment the app was open on it is a
                // wrong point on five charts here. Reported on Today's Activity
                // card rather than twice.
                repository.resyncFinishedDays(today)
                syncing.value = false
            }
        }
    }

    /**
     * Walks the sleep card back a night, or forward when [delta] is negative.
     *
     * No sync of its own, unlike the Activity card's stepper: nights come from
     * the time-series cache rather than a per-day read, so there is no "read that
     * one night" to spend -- what is held is what the last [syncTimeSeries] over
     * that stretch left, and a night older than that window is not fetchable one
     * at a time.
     */
    fun stepNight(delta: Int) {
        val furthest = (nightCount() - 1).coerceAtLeast(0)
        nightOffset.value = (nightOffset.value + delta).coerceIn(0, furthest)
    }

    /**
     * Walks the activity card back a day, or forward when [delta] is negative.
     *
     * The same bounds as Today's, and for the same reasons: forward stops at
     * today because there is nothing after it to report, back stops at a year
     * because that is the widest range any chart in the app draws.
     */
    fun stepActivityDay(delta: Long) {
        val next = (dayOffset.value + delta).coerceIn(0L, MAX_DAY_OFFSET)
        if (next == dayOffset.value) return
        dayOffset.value = next
        daySyncJob?.cancel()
        daySyncJob =
            viewModelScope.launch {
                if (healthState.value != HealthPermissionState.GRANTED) return@launch
                val day = today.minusDays(dayOffset.value)
                // Today belongs to refreshHealth, which syncs the series with it.
                if (day == today) return@launch
                repository.syncFinishedDay(day)
            }
    }

    /** Puts the activity card back on today. */
    fun backToToday() {
        dayOffset.value = 0L
    }

    /** Puts the card back on the newest night. */
    fun backToLastNight() {
        nightOffset.value = 0
    }

    /**
     * How many nights are held, read off the state the card is already showing.
     *
     * Off [uiState] rather than a second query: the count is already assembled
     * there for the card to disable its arrow with, and a second read would be a
     * second answer to keep in step. Only reachable from a tap, which cannot
     * happen while nothing is subscribed.
     */
    private fun nightCount(): Int = uiState.value.nightCount


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

    /**
     * Adds an entry to the standing stack.
     *
     * A blank name is dropped rather than stored: a row with nothing to read is
     * a checkbox nobody can identify, and the dialog cannot always stop one
     * arriving. An empty dose is allowed through -- plenty of things are "one
     * capsule" and saying so adds nothing.
     */
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

    fun logCreatine(grams: Int, at: Instant = Instant.now()) {
        if (grams <= 0) return
        viewModelScope.launch { repository.addCreatine(grams, at) }
    }

    fun deleteCreatine(intake: CreatineIntake) {
        viewModelScope.launch { repository.deleteCreatine(intake) }
    }

    fun logCaffeine(milligrams: Int, at: Instant = Instant.now()) {
        if (milligrams <= 0) return
        viewModelScope.launch { repository.addCaffeine(milligrams, at) }
    }

    /**
     * Logs a drink. Only Log's usual row writes hydration from this view model.
     *
     * A second writer of the same table alongside `FuelViewModel`, which is safe
     * for the reason the repository exists: both go through the one entry point,
     * so the flows behind Fuel's card and its correction list update either way.
     * The alternative was hoisting `FuelViewModel` so Log could share it, and
     * that would have carried its one-second fast ticker onto Log -- which is the
     * thing that makes a screen unscrollable in tests, three cards deep already.
     */
    fun logHydration(milliliters: Int, at: Instant = Instant.now()) {
        if (milliliters <= 0) return
        viewModelScope.launch { repository.addHydration(milliliters, at) }
    }

    /** Ticks everything still outstanding in one slot, in one write per supplement. */
    fun takeSlot(supplements: List<Supplement>, date: LocalDate = LocalDate.now(zoneId)) {
        viewModelScope.launch {
            supplements.forEach { repository.setSupplementTaken(it, date, true) }
        }
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

    /**
     * Scores how well today's food was logged, or clears the score.
     *
     * Clearable, unlike the mood sliders, because null and 1 mean genuinely
     * different things here -- *nobody was asked* against *there is nothing worth
     * reading* -- and a rating tapped by accident on the tab where everything
     * else is a logging button would otherwise be stuck as a number the reader
     * did not mean. The mood sliders have no equivalent: they submit a value the
     * reader dragged to, and there is no way to drag to "unrated".
     */
    fun setFoodLogConfidence(score: Int?) {
        viewModelScope.launch {
            val current = repository.getDailyLog(today).first() ?: DailyLog(today)
            repository.upsertDailyLog(current.copy(foodLogConfidence = score))
        }
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

    /**
     * Logs a meal by hand.
     *
     * A zero is stored as a zero rather than as "not recorded": everything here
     * was typed deliberately, so an untouched field means none of that macro --
     * unlike a synced meal, where a missing figure means the source did not break
     * the food down.
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
                    WellnessViewModel(repository) as T
            }
    }
}
