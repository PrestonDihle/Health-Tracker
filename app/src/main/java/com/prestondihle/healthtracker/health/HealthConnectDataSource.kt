package com.prestondihle.healthtracker.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Metres in a statute mile. */
private const val METRES_PER_MILE = 1609.344

private const val TAG = "HealthConnect"

/**
 * Reads from Health Connect. This app never writes.
 *
 * Each metric is fetched independently and failures are swallowed to null. A
 * user may grant step access but deny nutrition; that must degrade to a blank
 * macro card rather than an empty dashboard.
 */
class HealthConnectDataSource(
    private val context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : HealthDataSource {

    private val client: HealthConnectClient? by lazy {
        runCatching { HealthConnectClient.getOrCreate(context) }
            .onFailure { Log.w(TAG, "Health Connect client unavailable", it) }
            .getOrNull()
    }

    override fun requiredPermissions(): Set<String> = PERMISSIONS

    override suspend fun permissionState(): HealthPermissionState {
        val status = HealthConnectClient.getSdkStatus(context)
        if (status == HealthConnectClient.SDK_UNAVAILABLE) return HealthPermissionState.UNAVAILABLE
        if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            return HealthPermissionState.UPDATE_REQUIRED
        }
        val active = client ?: return HealthPermissionState.UNAVAILABLE
        val granted =
            runCatching { active.permissionController.getGrantedPermissions() }
                .getOrDefault(emptySet())
        // Partial grants still count: the reads below degrade individually.
        return if (granted.any { it in PERMISSIONS }) HealthPermissionState.GRANTED
        else HealthPermissionState.NOT_GRANTED
    }

    override suspend fun readDay(date: LocalDate): HealthDay {
        val active = client ?: return HealthDay(date)
        val start = date.atStartOfDay(zoneId).toInstant()
        val end = date.plusDays(1).atStartOfDay(zoneId).toInstant()
        val range = TimeRangeFilter.between(start, end)

        val steps = active.aggregateOrNull(StepsRecord.COUNT_TOTAL, range)?.toInt()
        val restingHr = active.aggregateOrNull(RestingHeartRateRecord.BPM_AVG, range)?.toInt()
        val avgHr = active.aggregateOrNull(HeartRateRecord.BPM_AVG, range)?.toInt()
        val sleep = active.aggregateOrNull(SleepSessionRecord.SLEEP_DURATION_TOTAL, range)
        val totalCalories = active.aggregateOrNull(TotalCaloriesBurnedRecord.ENERGY_TOTAL, range)
        val activeCalories =
            active.aggregateOrNull(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, range)
        val protein = active.aggregateOrNull(NutritionRecord.PROTEIN_TOTAL, range)
        val carbs = active.aggregateOrNull(NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL, range)
        val fat = active.aggregateOrNull(NutritionRecord.TOTAL_FAT_TOTAL, range)

        return HealthDay(
            date = date,
            steps = steps,
            restingHeartRateBpm = restingHr,
            averageHeartRateBpm = avgHr,
            sleepMinutes = sleep?.toMinutes()?.toInt(),
            totalCalories = totalCalories?.inKilocalories?.toInt(),
            activeCalories = activeCalories?.inKilocalories?.toInt(),
            proteinGrams = protein?.inGrams?.toFloat(),
            carbGrams = carbs?.inGrams?.toFloat(),
            fatGrams = fat?.inGrams?.toFloat(),
            bestMileSeconds = active.readBestMileSeconds(start, end),
            glucoseSamples = active.readGlucose(range),
        )
    }

    /**
     * Aggregates one metric, returning null instead of throwing when the type is
     * unpermitted or has no data in range.
     */
    private suspend fun <T : Any> HealthConnectClient.aggregateOrNull(
        metric: AggregateMetric<T>,
        range: TimeRangeFilter,
    ): T? =
        runCatching {
                aggregate(AggregateRequest(metrics = setOf(metric), timeRangeFilter = range))[metric]
            }
            .onFailure { Log.d(TAG, "aggregate failed for $metric", it) }
            .getOrNull()

    private suspend fun HealthConnectClient.readGlucose(range: TimeRangeFilter): List<GlucoseSample> =
        runCatching {
                readRecords(
                        ReadRecordsRequest(
                            recordType = BloodGlucoseRecord::class,
                            timeRangeFilter = range,
                        )
                    )
                    .records
                    .map {
                        GlucoseSample(
                            time = it.time,
                            mgDl = it.level.inMilligramsPerDeciliter.toInt(),
                            externalId = it.metadata.id.takeIf { id -> id.isNotBlank() },
                        )
                    }
            }
            .onFailure { Log.d(TAG, "glucose read failed", it) }
            .getOrDefault(emptyList())

    /**
     * Best average mile pace among runs of at least a mile.
     *
     * Health Connect has no mile-split concept, so this divides each qualifying
     * run's elapsed time by its distance and normalises to one mile. On a long
     * run that includes warmup this reads slower than a true mile PR.
     */
    private suspend fun HealthConnectClient.readBestMileSeconds(start: Instant, end: Instant): Int? =
        runCatching {
                val sessions =
                    readRecords(
                            ReadRecordsRequest(
                                recordType = ExerciseSessionRecord::class,
                                timeRangeFilter = TimeRangeFilter.between(start, end),
                            )
                        )
                        .records
                        .filter { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING }

                sessions
                    .mapNotNull { session ->
                        val metres =
                            aggregateOrNull(
                                    DistanceRecord.DISTANCE_TOTAL,
                                    TimeRangeFilter.between(session.startTime, session.endTime),
                                )
                                ?.inMeters ?: return@mapNotNull null
                        if (metres < METRES_PER_MILE) return@mapNotNull null

                        val elapsed =
                            Duration.between(session.startTime, session.endTime).seconds.toDouble()
                        if (elapsed <= 0.0) return@mapNotNull null

                        (elapsed * METRES_PER_MILE / metres).toInt()
                    }
                    .minOrNull()
            }
            .onFailure { Log.d(TAG, "mile pace read failed", it) }
            .getOrNull()

    companion object {
        val PERMISSIONS: Set<String> =
            setOf(
                HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getReadPermission(RestingHeartRateRecord::class),
                HealthPermission.getReadPermission(SleepSessionRecord::class),
                HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
                HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
                HealthPermission.getReadPermission(NutritionRecord::class),
                HealthPermission.getReadPermission(BloodGlucoseRecord::class),
                HealthPermission.getReadPermission(ExerciseSessionRecord::class),
                HealthPermission.getReadPermission(DistanceRecord::class),
                HealthPermission.getReadPermission(SpeedRecord::class),
            )
    }
}
