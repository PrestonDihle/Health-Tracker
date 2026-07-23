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
    val proteinGrams: Float? = null,
    val carbGrams: Float? = null,
    val fatGrams: Float? = null,
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

    suspend fun readDay(date: LocalDate): HealthDay
}
