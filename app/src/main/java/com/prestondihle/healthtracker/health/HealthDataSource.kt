package com.prestondihle.healthtracker.health

import com.prestondihle.healthtracker.domain.SleepStageInterval
import com.prestondihle.healthtracker.domain.TrainingType
import java.time.Instant
import java.time.LocalDate

/** Whether Health Connect can be used on this device, and if so how far setup has got. */
enum class HealthPermissionState {
    /** Health Connect is not present and cannot be installed (below API 26, or unsupported). */
    UNAVAILABLE,

    /** Present but the provider APK needs updating before the client will talk to it. */
    UPDATE_REQUIRED,

    /** Available, but the user has not granted the permissions this app asks for. */
    NOT_GRANTED,

    /** Available and at least one requested permission is granted. */
    GRANTED,
}

/** A single glucose sample, carrying its Health Connect id so re-syncs can dedupe. */
data class GlucoseSample(val time: Instant, val mgDl: Int, val externalId: String?)

/**
 * One meal as recorded, with the time it was eaten.
 *
 * Every macro is nullable independently: apps routinely log calories without
 * breaking them down, and a meal with only an energy figure is still worth
 * placing on a timeline.
 */
data class MealSample(
    val time: Instant,
    val calories: Int? = null,
    val proteinGrams: Float? = null,
    val carbGrams: Float? = null,
    val fatGrams: Float? = null,
    val name: String? = null,
    val externalId: String? = null,
)

/** One heart rate reading at one instant, before bucketing. */
data class HeartRateSample(val time: Instant, val bpm: Int)

/**
 * One recorded exercise session, with its distance where the source had one.
 *
 * Was `RunSession` and read only running. Widened rather than joined by a second
 * read: the watch writes strength, hiking, walking, cycling and the rest through
 * the same record type, so filtering to one of them at the source meant the app
 * could not see training it was already being told about. The run-specific
 * callers now filter on [type], which is a line of code where a second query
 * would have been a second round trip and a second thing to keep in step.
 */
data class ExerciseSession(
    val start: Instant,
    val end: Instant,
    val type: TrainingType,
    val distanceMeters: Double?,
)

/**
 * One night as recorded, with the stages it was broken into.
 *
 * [start] and [end] are the session's own bounds, kept even though the stages
 * usually span the same time: a writer may bound a night more widely than the
 * part of it it managed to classify, and shrinking the night to fit its stages
 * would quietly redefine what time in bed means.
 *
 * [stages] is empty for a writer that records a session and nothing finer, which
 * is allowed and is not an error -- it simply leaves nothing to draw.
 */
data class SleepSessionSample(
    val start: Instant,
    val end: Instant,
    val stages: List<SleepStageInterval> = emptyList(),
    val externalId: String? = null,
)

/** Steps counted inside one wall-clock hour, keyed by the hour's start. */
data class HourlySteps(val hourStart: Instant, val steps: Int)

/**
 * Steps contributed by one writing app on a given day.
 *
 * [packageName] is the Health Connect data origin; [appLabel] is the installed
 * app's display name where it can be resolved, falling back to the package.
 */
data class StepSource(val packageName: String, val appLabel: String, val steps: Int)

/**
 * Everything read from Health Connect for one calendar day.
 *
 * Every field is nullable: a missing value means "no data or no permission for
 * this type", which is not an error and must not fail the whole sync.
 */
data class HealthDay(
    val date: LocalDate,
    val steps: Int? = null,
    val restingHeartRateBpm: Int? = null,
    val averageHeartRateBpm: Int? = null,
    val sleepMinutes: Int? = null,
    val totalCalories: Int? = null,
    val activeCalories: Int? = null,
    /** Calories eaten, from nutrition records. Not the same as [totalCalories]. */
    val dietaryCalories: Int? = null,
    val proteinGrams: Float? = null,
    val carbGrams: Float? = null,
    val fatGrams: Float? = null,
    /** Latest weigh-in on this date. */
    val weightKg: Float? = null,
    /**
     * Best average mile pace across runs of at least a mile on this date, in
     * seconds. Average pace over the whole run, not a fastest-mile split.
     */
    val bestMileSeconds: Int? = null,
    val glucoseSamples: List<GlucoseSample> = emptyList(),
    /** Mean blood oxygen saturation across the day, where the watch reported any. */
    val spo2Percent: Float? = null,
)

interface HealthDataSource {
    /** Permission strings this app requests. Passed to the system permission dialog. */
    fun requiredPermissions(): Set<String>

    suspend fun permissionState(): HealthPermissionState

    /**
     * Requested permissions the user has not granted.
     *
     * Needed because [permissionState] reports GRANTED as soon as *any* single
     * permission is held. Without this, a permission added in a later version
     * stays silently denied forever: the app looks connected and that metric
     * just reads blank.
     */
    suspend fun missingPermissions(): Set<String>

    /**
     * Reads one day.
     *
     * [preferredStepsPackage] restricts the step count to a single writing app.
     * Null sums every source, which double-counts when more than one app tracks
     * the same walk.
     */
    suspend fun readDay(date: LocalDate, preferredStepsPackage: String? = null): HealthDay

    /** Per-app step totals for [date], for choosing which source to trust. */
    suspend fun readStepSources(date: LocalDate): List<StepSource>

    /**
     * Meals eaten in an arbitrary window.
     *
     * Takes instants rather than a date because the master graph's window is a
     * rolling span that crosses midnight, and a meal eaten last night is still
     * being absorbed this morning.
     */
    suspend fun readMeals(from: Instant, to: Instant): List<MealSample>

    /** Every heart rate sample in a window, unaggregated. */
    suspend fun readHeartRate(from: Instant, to: Instant): List<HeartRateSample>

    /**
     * Blood sugar in an arbitrary window.
     *
     * [readDay] already returns a day's worth, and that is the right shape for
     * the daily sync. This exists for the other question: filling a hole. A
     * monitor that was out of range writes its readings to Health Connect hours
     * late, by which time nothing re-reads the day they belong to, and asking for
     * the whole of a three-day-old day to recover forty minutes of it is a lot of
     * records to fetch and discard. See [com.prestondihle.healthtracker.domain.GlucoseGaps].
     */
    suspend fun readGlucose(from: Instant, to: Instant): List<GlucoseSample>

    /**
     * Steps in a window, split into wall-clock hours.
     *
     * Aligned to the hour rather than to [from] so the same hour always produces
     * the same bucket, which is what lets a re-sync overwrite rows instead of
     * accumulating shifted duplicates of the same walk.
     *
     * [preferredStepsPackage] pins one writing app, exactly as [readDay] does --
     * hourly bars that summed every source while the daily total trusted one
     * would be two different step counts on two screens.
     */
    suspend fun readStepsByHour(
        from: Instant,
        to: Instant,
        preferredStepsPackage: String? = null,
    ): List<HourlySteps>

    /**
     * Sleep sessions overlapping a window, with their stages.
     *
     * Separate from [readDay]'s `sleepMinutes`, which is the one aggregate
     * Health Connect offers on a sleep session and stays where it is: a daily
     * total is what the trend chart and the goal are read against. This is the
     * other question -- when, and in which stage -- and there is no aggregate for
     * it, so the raw sessions have to be read.
     *
     * The filter is on the session's own span, so a night crossing midnight is
     * returned whole against either day it touches rather than split at the
     * boundary the way an aggregate splits it.
     */
    suspend fun readSleepSessions(from: Instant, to: Instant): List<SleepSessionSample>

    /**
     * Exercise sessions overlapping a window, newest need not be first.
     *
     * Just the session bounds, kind and distance; the heart rate that zones each
     * run is read separately over the session's own span, so nothing here depends
     * on the runner's max heart rate and the zones can be recomputed when it
     * changes.
     */
    suspend fun readExerciseSessions(from: Instant, to: Instant): List<ExerciseSession>
}
