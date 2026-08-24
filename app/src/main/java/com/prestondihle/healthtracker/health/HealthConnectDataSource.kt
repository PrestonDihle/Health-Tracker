package com.prestondihle.healthtracker.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass

/** Metres in a statute mile. */
private const val METRES_PER_MILE = 1609.344

private const val TAG = "HealthConnect"

/**
 * Health Connect attributes the phone's own step tracking to a synthetic origin
 * with a random suffix, so this is matched by prefix rather than equality.
 */
private const val HEALTH_CONNECT_PHONE_PREFIX = "com.android.healthconnect.phone"

/** Package segments that identify no vendor, skipped when deriving a name. */
private val GENERIC_PACKAGE_SEGMENTS =
    setOf("com", "org", "net", "io", "android", "apps", "app", "mobile", "google")

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

    override suspend fun missingPermissions(): Set<String> {
        val active = client ?: return emptySet()
        val granted =
            runCatching { active.permissionController.getGrantedPermissions() }
                .getOrDefault(emptySet())
        return PERMISSIONS - granted
    }

    override suspend fun readDay(date: LocalDate, preferredStepsPackage: String?): HealthDay {
        val active = client ?: return HealthDay(date)
        val start = date.atStartOfDay(zoneId).toInstant()
        val end = date.plusDays(1).atStartOfDay(zoneId).toInstant()
        val range = TimeRangeFilter.between(start, end)

        val restingHr = active.aggregateOrNull(RestingHeartRateRecord.BPM_AVG, range)?.toInt()
        val avgHr = active.aggregateOrNull(HeartRateRecord.BPM_AVG, range)?.toInt()
        val sleep = active.aggregateOrNull(SleepSessionRecord.SLEEP_DURATION_TOTAL, range)
        val totalCalories = active.aggregateOrNull(TotalCaloriesBurnedRecord.ENERGY_TOTAL, range)
        val activeCalories =
            active.aggregateOrNull(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, range)
        val dietaryCalories = active.aggregateOrNull(NutritionRecord.ENERGY_TOTAL, range)
        val protein = active.aggregateOrNull(NutritionRecord.PROTEIN_TOTAL, range)
        val carbs = active.aggregateOrNull(NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL, range)
        val fat = active.aggregateOrNull(NutritionRecord.TOTAL_FAT_TOTAL, range)

        return HealthDay(
            date = date,
            steps = active.readSteps(range, preferredStepsPackage),
            restingHeartRateBpm = restingHr,
            averageHeartRateBpm = avgHr,
            sleepMinutes = sleep?.toMinutes()?.toInt(),
            totalCalories = totalCalories?.inKilocalories?.toInt(),
            activeCalories = activeCalories?.inKilocalories?.toInt(),
            dietaryCalories = dietaryCalories?.inKilocalories?.toInt(),
            proteinGrams = protein?.inGrams?.toFloat(),
            carbGrams = carbs?.inGrams?.toFloat(),
            fatGrams = fat?.inGrams?.toFloat(),
            weightKg = active.readLatestWeightKg(range),
            bestMileSeconds = active.readBestMileSeconds(start, end),
            glucoseSamples = active.readGlucose(range),
        )
    }

    override suspend fun readStepSources(date: LocalDate): List<StepSource> {
        val active = client ?: return emptyList()
        val range =
            TimeRangeFilter.between(
                date.atStartOfDay(zoneId).toInstant(),
                date.plusDays(1).atStartOfDay(zoneId).toInstant(),
            )
        return active
            .stepsByPackage(range)
            .map { (packageName, steps) -> StepSource(packageName, appLabelFor(packageName), steps) }
            .sortedByDescending { it.steps }
    }

    /**
     * Steps grouped by the app that wrote them.
     *
     * Done with one aggregate per contributing source rather than by reading raw
     * [StepsRecord]s. Raw reads are not survivable here: the client validates
     * every record as it converts it, a zero-count step record is rejected with
     * `count must not be less than 1`, and one such record from any installed
     * app throws away the entire page. Aggregates never construct records, so
     * they are immune. The contributing packages come from the combined
     * aggregate's own [androidx.health.connect.client.aggregate.AggregationResult.dataOrigins].
     */
    private suspend fun HealthConnectClient.stepsByPackage(
        range: TimeRangeFilter
    ): Map<String, Int> {
        val combined = aggregateResultOrNull(StepsRecord.COUNT_TOTAL, range) ?: return emptyMap()
        val origins = combined.dataOrigins.map { it.packageName }.filter { it.isNotBlank() }

        // A single source needs no second round trip: the combined total is it.
        if (origins.size <= 1) {
            val total = combined[StepsRecord.COUNT_TOTAL]?.toInt() ?: return emptyMap()
            return origins.firstOrNull()?.let { mapOf(it to total) } ?: emptyMap()
        }

        return origins
            .mapNotNull { packageName ->
                val perSource =
                    aggregateResultOrNull(
                        StepsRecord.COUNT_TOTAL,
                        range,
                        setOf(DataOrigin(packageName)),
                    )
                perSource?.get(StepsRecord.COUNT_TOTAL)?.toInt()?.let { packageName to it }
            }
            .toMap()
    }

    private suspend fun HealthConnectClient.readSteps(
        range: TimeRangeFilter,
        preferredPackage: String?,
    ): Int? {
        if (preferredPackage != null) {
            val pinned =
                aggregateResultOrNull(
                        StepsRecord.COUNT_TOTAL,
                        range,
                        setOf(DataOrigin(preferredPackage)),
                    )
                    ?.get(StepsRecord.COUNT_TOTAL)
                    ?.toInt()
            // A pinned app that wrote nothing today falls through to the
            // combined total rather than reporting a flat zero.
            if (pinned != null && pinned > 0) return pinned
        }
        return aggregateOrNull(StepsRecord.COUNT_TOTAL, range)?.toInt()
    }

    /**
     * Reads every page of a record type.
     *
     * A single day of step records from a watch runs to hundreds of entries, and
     * Health Connect returns them a page at a time -- taking only the first page
     * would silently undercount.
     */
    private suspend fun <T : Record> HealthConnectClient.readAllRecords(
        type: KClass<T>,
        range: TimeRangeFilter,
    ): List<T> =
        runCatching {
                val all = mutableListOf<T>()
                var pageToken: String? = null
                do {
                    val response =
                        readRecords(
                            ReadRecordsRequest(
                                recordType = type,
                                timeRangeFilter = range,
                                pageToken = pageToken,
                            )
                        )
                    all += response.records
                    pageToken = response.pageToken
                } while (pageToken != null)
                all
            }
            .onFailure { Log.d(TAG, "record read failed for ${type.simpleName}", it) }
            .getOrDefault(emptyList())

    /**
     * Meals in a window, each with the time it was eaten.
     *
     * A [NutritionRecord] is an interval, not an instant. Its start is when eating
     * began, which is the reference an absorption curve is anchored to -- so the
     * end time is dropped rather than averaged in.
     */
    override suspend fun readMeals(from: Instant, to: Instant): List<MealSample> {
        val active = client ?: return emptyList()
        return active
            .readAllRecords(NutritionRecord::class, TimeRangeFilter.between(from, to))
            .map { record ->
                MealSample(
                    time = record.startTime,
                    calories = record.energy?.inKilocalories?.toInt(),
                    proteinGrams = record.protein?.inGrams?.toFloat(),
                    carbGrams = record.totalCarbohydrate?.inGrams?.toFloat(),
                    fatGrams = record.totalFat?.inGrams?.toFloat(),
                    name = record.name,
                    externalId = record.metadata.id.takeIf { it.isNotBlank() },
                )
            }
    }

    /**
     * Every heart rate sample in a window.
     *
     * One [HeartRateRecord] holds many samples -- a watch writes a batch covering
     * minutes at a time -- so the records are flattened to their samples. The
     * caller does the averaging; this returns the full resolution it was given.
     */
    override suspend fun readHeartRate(from: Instant, to: Instant): List<HeartRateSample> {
        val active = client ?: return emptyList()
        return active
            .readAllRecords(HeartRateRecord::class, TimeRangeFilter.between(from, to))
            .flatMap { record ->
                record.samples.map { HeartRateSample(it.time, it.beatsPerMinute.toInt()) }
            }
            // A batch can straddle the window edge, so its samples are filtered
            // individually rather than trusting the record's own bounds.
            .filter { !it.time.isBefore(from) && it.time.isBefore(to) }
            .sortedBy { it.time }
    }

    /**
     * Blood sugar in a window, using the same reader the daily sync does.
     *
     * The whole point of going back for a gap is that the source has since
     * written records the cache has never seen, so this deliberately shares
     * [readGlucose]'s ordinary path rather than doing anything cleverer: what
     * comes back is whatever is there now, and the caller's `externalId` index
     * discards the part it already holds.
     */
    override suspend fun readGlucose(from: Instant, to: Instant): List<GlucoseSample> {
        val active = client ?: return emptyList()
        return active.readGlucose(TimeRangeFilter.between(from, to)).sortedBy { it.time }
    }

    /**
     * Steps split into wall-clock hours.
     *
     * Aggregated per slice rather than read as raw [StepsRecord]s for the same
     * reason [stepsByPackage] is: the client validates every record it converts,
     * and one zero-count record written by any installed app throws away the
     * whole page. Aggregates never construct records.
     *
     * The window's start is snapped down to the hour in the local zone before
     * slicing, because the slicer counts forward from whatever instant it is
     * given -- a sync begun at 14:37 would otherwise produce buckets running
     * :37 to :37, which no later sync would line up with.
     */
    override suspend fun readStepsByHour(
        from: Instant,
        to: Instant,
        preferredStepsPackage: String?,
    ): List<HourlySteps> {
        val active = client ?: return emptyList()
        val alignedFrom = from.atZone(zoneId).truncatedTo(ChronoUnit.HOURS).toInstant()
        if (!alignedFrom.isBefore(to)) return emptyList()

        if (preferredStepsPackage != null) {
            val pinned =
                active.hourlySteps(alignedFrom, to, setOf(DataOrigin(preferredStepsPackage)))
            // A pinned app that wrote nothing across the whole window falls back
            // to the combined total, matching how the daily count behaves.
            if (pinned.any { it.steps > 0 }) return pinned
        }
        return active.hourlySteps(alignedFrom, to, emptySet())
    }

    private suspend fun HealthConnectClient.hourlySteps(
        from: Instant,
        to: Instant,
        dataOriginFilter: Set<DataOrigin>,
    ): List<HourlySteps> =
        runCatching {
                aggregateGroupByDuration(
                        AggregateGroupByDurationRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(from, to),
                            timeRangeSlicer = Duration.ofHours(1),
                            dataOriginFilter = dataOriginFilter,
                        )
                    )
                    .mapNotNull { slice ->
                        slice.result[StepsRecord.COUNT_TOTAL]?.let {
                            HourlySteps(slice.startTime, it.toInt())
                        }
                    }
            }
            .onFailure { Log.d(TAG, "hourly step read failed", it) }
            .getOrDefault(emptyList())

    /** The day's last weigh-in, which is the one a scale-synced app means by "weight". */
    private suspend fun HealthConnectClient.readLatestWeightKg(range: TimeRangeFilter): Float? =
        readAllRecords(WeightRecord::class, range).maxByOrNull { it.time }?.weight?.inKilograms
            ?.toFloat()

    /**
     * A readable name for a writing app.
     *
     * Falls back through three stages because none of them is reliable alone:
     * the platform label needs the app to be visible under package visibility,
     * and Health Connect's own phone-tracked steps come from a synthetic origin
     * with a random suffix that is not an installed package at all.
     */
    private fun appLabelFor(packageName: String): String {
        if (packageName.startsWith(HEALTH_CONNECT_PHONE_PREFIX)) return "This phone"

        val label =
            runCatching {
                    val pm = context.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                }
                .getOrNull()
        if (!label.isNullOrBlank()) return label

        // Last resort: the vendor segment of the package, which is nearly always
        // the brand -- com.garmin.android.apps.connectmobile becomes "Garmin".
        return packageName
            .split('.')
            .firstOrNull { it !in GENERIC_PACKAGE_SEGMENTS }
            ?.replaceFirstChar { it.uppercase() } ?: packageName
    }

    /**
     * Aggregates one metric, returning null instead of throwing when the type is
     * unpermitted or has no data in range.
     */
    private suspend fun <T : Any> HealthConnectClient.aggregateOrNull(
        metric: AggregateMetric<T>,
        range: TimeRangeFilter,
    ): T? = aggregateResultOrNull(metric, range)?.get(metric)

    /**
     * The whole aggregation result, which unlike [aggregateOrNull] also carries
     * the set of apps that contributed to it.
     */
    private suspend fun HealthConnectClient.aggregateResultOrNull(
        metric: AggregateMetric<*>,
        range: TimeRangeFilter,
        dataOriginFilter: Set<DataOrigin> = emptySet(),
    ): AggregationResult? =
        runCatching {
                aggregate(
                    AggregateRequest(
                        metrics = setOf(metric),
                        timeRangeFilter = range,
                        dataOriginFilter = dataOriginFilter,
                    )
                )
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
                HealthPermission.getReadPermission(WeightRecord::class),
            )
    }
}
