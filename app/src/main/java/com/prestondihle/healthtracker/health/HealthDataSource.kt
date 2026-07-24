package com.prestondihle.healthtracker.health

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
}
