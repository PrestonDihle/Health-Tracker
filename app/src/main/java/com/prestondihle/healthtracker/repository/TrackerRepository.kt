package com.prestondihle.healthtracker.repository

import com.prestondihle.healthtracker.data.BloodPressureReading
import com.prestondihle.healthtracker.data.BloodSugarReading
import com.prestondihle.healthtracker.data.CaffeineIntake
import com.prestondihle.healthtracker.data.CreatineIntake
import com.prestondihle.healthtracker.data.DailyLog
import com.prestondihle.healthtracker.data.DataSourceEnum
import com.prestondihle.healthtracker.data.ExerciseSet
import com.prestondihle.healthtracker.data.FastingPlanDay
import com.prestondihle.healthtracker.data.FastingSession
import com.prestondihle.healthtracker.data.FastingType
import com.prestondihle.healthtracker.data.GripStrengthEntry
import com.prestondihle.healthtracker.data.HealthDaySnapshot
import com.prestondihle.healthtracker.data.HeartRateBucket
import com.prestondihle.healthtracker.data.HydrationEntry
import com.prestondihle.healthtracker.data.KetoneReading
import com.prestondihle.healthtracker.data.MealEntry
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.data.PlannedExtendedFast
import com.prestondihle.healthtracker.data.RestingHeartRate
import com.prestondihle.healthtracker.data.StepBucket
import com.prestondihle.healthtracker.data.TrackerDao
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.data.UserSettings
import com.prestondihle.healthtracker.data.WaistEntry
import com.prestondihle.healthtracker.data.WeeklyPerformance
import com.prestondihle.healthtracker.data.WeightEntry
import com.prestondihle.healthtracker.domain.MealDuplicates
import com.prestondihle.healthtracker.health.HealthDataSource
import com.prestondihle.healthtracker.health.HealthPermissionState
import com.prestondihle.healthtracker.health.HeartRateSample
import com.prestondihle.healthtracker.health.StepSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * How far back one sync will re-read raw heart rate, however wide the window
 * being drawn is. See [TrackerRepository.syncTimeSeries].
 */
private val HEART_RATE_SYNC_HORIZON: Duration = Duration.ofHours(48)

/**
 * Single entry point for data. Owns the conversion between the domain's
 * [Instant]/[LocalDate] vocabulary and the epoch-millisecond bounds the DAO
 * expects, so no caller has to repeat that arithmetic.
 */
class TrackerRepository(
    private val dao: TrackerDao,
    private val healthDataSource: HealthDataSource,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {

    private fun startOfDayMillis(date: LocalDate): Long =
        date.atStartOfDay(zoneId).toInstant().toEpochMilli()

    private fun endOfDayMillis(date: LocalDate): Long =
        date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

    // ----- Daily log ---------------------------------------------------------

    fun getDailyLog(date: LocalDate): Flow<DailyLog?> = dao.getDailyLog(date)

    fun getDailyLogs(start: LocalDate, end: LocalDate): Flow<List<DailyLog>> =
        dao.getDailyLogs(start, end)

    suspend fun upsertDailyLog(log: DailyLog) = dao.upsertDailyLog(log)

    // ----- Health Connect cache ---------------------------------------------

    fun getHealthSnapshot(date: LocalDate): Flow<HealthDaySnapshot?> = dao.getHealthSnapshot(date)

    fun getHealthSnapshots(start: LocalDate, end: LocalDate): Flow<List<HealthDaySnapshot>> =
        dao.getHealthSnapshots(start, end)

    fun getBestMileSecondsAllTime(): Flow<Int?> = dao.getBestMileSecondsAllTime()

    // ----- Blood pressure ----------------------------------------------------

    fun getBloodPressureForDate(date: LocalDate): Flow<List<BloodPressureReading>> =
        dao.getBloodPressureReadingsBetween(startOfDayMillis(date), endOfDayMillis(date))

    fun getBloodPressureBetween(start: LocalDate, end: LocalDate): Flow<List<BloodPressureReading>> =
        dao.getBloodPressureReadingsBetween(startOfDayMillis(start), endOfDayMillis(end))

    suspend fun addBloodPressure(systolic: Int, diastolic: Int, at: Instant = Instant.now()) =
        dao.insertBloodPressureReading(
            BloodPressureReading(timestamp = at, systolic = systolic, diastolic = diastolic)
        )

    suspend fun deleteBloodPressure(reading: BloodPressureReading) =
        dao.deleteBloodPressureReading(reading)

    // ----- Weight and waist --------------------------------------------------

    fun getWeight(date: LocalDate): Flow<WeightEntry?> = dao.getWeight(date)

    fun getWeights(start: LocalDate, end: LocalDate): Flow<List<WeightEntry>> =
        dao.getWeights(start, end)

    suspend fun setWeightKg(date: LocalDate, kg: Float) = dao.upsertWeight(WeightEntry(date, kg))

    fun getWaist(date: LocalDate): Flow<WaistEntry?> = dao.getWaist(date)

    fun getWaists(start: LocalDate, end: LocalDate): Flow<List<WaistEntry>> =
        dao.getWaists(start, end)

    fun getLatestWaistOnOrBefore(date: LocalDate): Flow<WaistEntry?> =
        dao.getLatestWaistOnOrBefore(date)

    suspend fun setWaistCm(date: LocalDate, cm: Float) = dao.upsertWaist(WaistEntry(date, cm))

    // ----- Grip strength -----------------------------------------------------

    fun getGripStrength(date: LocalDate): Flow<GripStrengthEntry?> = dao.getGripStrength(date)

    fun getGripStrengths(start: LocalDate, end: LocalDate): Flow<List<GripStrengthEntry>> =
        dao.getGripStrengths(start, end)

    fun getLatestGripStrengthOnOrBefore(date: LocalDate): Flow<GripStrengthEntry?> =
        dao.getLatestGripStrengthOnOrBefore(date)

    /**
     * Records one hand's reading for [date], leaving the other hand alone.
     *
     * Read-modify-write rather than a plain upsert: the two hands are squeezed
     * one at a time, and writing a whole row from the value of the hand just
     * measured would blank the one measured a minute earlier.
     */
    suspend fun setGripStrengthKg(date: LocalDate, dominant: Boolean, kg: Float) {
        val current = dao.getGripStrength(date).first() ?: GripStrengthEntry(date)
        dao.upsertGripStrength(
            if (dominant) current.copy(dominantKg = kg) else current.copy(nonDominantKg = kg)
        )
    }

    // ----- Hydration ---------------------------------------------------------

    fun getHydrationForDate(date: LocalDate): Flow<List<HydrationEntry>> =
        dao.getHydrationBetween(startOfDayMillis(date), endOfDayMillis(date))

    fun getHydrationTotalMl(date: LocalDate): Flow<Int> =
        dao.getHydrationTotalMl(startOfDayMillis(date), endOfDayMillis(date))

    fun getHydrationBetween(start: LocalDate, end: LocalDate): Flow<List<HydrationEntry>> =
        dao.getHydrationBetween(startOfDayMillis(start), endOfDayMillis(end))

    suspend fun addHydration(milliliters: Int, at: Instant = Instant.now()) =
        dao.insertHydration(HydrationEntry(timestamp = at, milliliters = milliliters))

    suspend fun deleteHydration(entry: HydrationEntry) = dao.deleteHydration(entry)

    // ----- Bodyweight exercise ----------------------------------------------

    fun getExerciseSetsForDate(date: LocalDate): Flow<List<ExerciseSet>> =
        dao.getExerciseSetsBetween(startOfDayMillis(date), endOfDayMillis(date))

    fun getExerciseSetsBetween(start: LocalDate, end: LocalDate): Flow<List<ExerciseSet>> =
        dao.getExerciseSetsBetween(startOfDayMillis(start), endOfDayMillis(end))

    fun getRepTotalForDate(movement: MovementType, date: LocalDate): Flow<Int> =
        dao.getRepTotal(movement, startOfDayMillis(date), endOfDayMillis(date))

    suspend fun addExerciseSet(movement: MovementType, reps: Int, at: Instant = Instant.now()) =
        dao.insertExerciseSet(ExerciseSet(timestamp = at, movement = movement, reps = reps))

    suspend fun deleteExerciseSet(set: ExerciseSet) = dao.deleteExerciseSet(set)

    // ----- Supplements -------------------------------------------------------

    fun getCaffeineForDate(date: LocalDate): Flow<List<CaffeineIntake>> =
        dao.getCaffeineIntakesBetween(startOfDayMillis(date), endOfDayMillis(date))

    /**
     * Caffeine logged since [since], for the decay curve.
     *
     * The curve needs doses from *before* the plotted window, since one taken
     * last night is still in the body this morning.
     */
    fun getCaffeineSince(since: Instant): Flow<List<CaffeineIntake>> =
        dao.getCaffeineIntakesBetween(since.toEpochMilli(), Long.MAX_VALUE)

    suspend fun addCaffeine(mg: Int, at: Instant = Instant.now()) =
        dao.insertCaffeineIntake(CaffeineIntake(timestamp = at, milligrams = mg))

    suspend fun updateCaffeine(intake: CaffeineIntake) = dao.updateCaffeineIntake(intake)

    suspend fun deleteCaffeine(intake: CaffeineIntake) = dao.deleteCaffeineIntake(intake)

    fun getCreatineForDate(date: LocalDate): Flow<List<CreatineIntake>> =
        dao.getCreatineIntakesBetween(startOfDayMillis(date), endOfDayMillis(date))

    suspend fun addCreatine(grams: Int, at: Instant = Instant.now()) =
        dao.insertCreatineIntake(CreatineIntake(timestamp = at, grams = grams))

    suspend fun deleteCreatine(intake: CreatineIntake) = dao.deleteCreatineIntake(intake)

    // ----- Fasting: actual sessions -----------------------------------------

    fun getActiveFastingSession(): Flow<FastingSession?> = dao.getActiveFastingSession()

    fun getLastCompletedFastingSession(): Flow<FastingSession?> =
        dao.getLastCompletedFastingSession()

    fun getAllFastingSessions(): Flow<List<FastingSession>> = dao.getAllFastingSessions()

    fun getFastingSessionsOverlapping(start: Instant, end: Instant): Flow<List<FastingSession>> =
        dao.getFastingSessionsOverlapping(start.toEpochMilli(), end.toEpochMilli())

    fun getFastingSessionsBetweenDates(
        start: LocalDate,
        end: LocalDate,
    ): Flow<List<FastingSession>> =
        dao.getFastingSessionsOverlapping(startOfDayMillis(start), endOfDayMillis(end))

    suspend fun startFast(type: FastingType, goalMinutes: Int, at: Instant = Instant.now()) =
        dao.insertFastingSession(
            FastingSession(startInstant = at, goalDurationMinutes = goalMinutes, type = type)
        )

    suspend fun endFast(session: FastingSession, at: Instant = Instant.now()) =
        dao.updateFastingSession(session.copy(endInstant = at))

    /** Rewrites a session wholesale, for correcting times logged late or not at all. */
    suspend fun updateFastingSession(session: FastingSession) = dao.updateFastingSession(session)

    suspend fun deleteFastingSession(session: FastingSession) = dao.deleteFastingSession(session)

    // ----- Fasting: the plan -------------------------------------------------

    fun getFastingPlan(): Flow<List<FastingPlanDay>> = dao.getFastingPlan()

    suspend fun upsertFastingPlanDay(day: FastingPlanDay) = dao.upsertFastingPlanDay(day)

    suspend fun upsertFastingPlan(days: List<FastingPlanDay>) = dao.upsertFastingPlan(days)

    fun getPlannedExtendedFasts(start: Instant, end: Instant): Flow<List<PlannedExtendedFast>> =
        dao.getPlannedExtendedFastsOverlapping(start.toEpochMilli(), end.toEpochMilli())

    suspend fun addPlannedExtendedFast(fast: PlannedExtendedFast) =
        dao.insertPlannedExtendedFast(fast)

    suspend fun deletePlannedExtendedFast(fast: PlannedExtendedFast) =
        dao.deletePlannedExtendedFast(fast)

    // ----- Weekly performance ------------------------------------------------

    fun getWeeklyPerformance(isoWeek: String): Flow<WeeklyPerformance?> =
        dao.getWeeklyPerformance(isoWeek)

    fun getAllWeeklyPerformances(): Flow<List<WeeklyPerformance>> = dao.getAllWeeklyPerformances()

    suspend fun upsertWeeklyPerformance(performance: WeeklyPerformance) =
        dao.upsertWeeklyPerformance(performance)

    // ----- Blood sugar and ketones ------------------------------------------

    /** Readings in the rolling window ending now -- what the dashboard chart shows. */
    fun getBloodSugarSince(since: Instant): Flow<List<BloodSugarReading>> =
        dao.getBloodSugarReadingsBetween(since.toEpochMilli(), Long.MAX_VALUE)

    fun getBloodSugarForDate(date: LocalDate): Flow<List<BloodSugarReading>> =
        dao.getBloodSugarReadingsBetween(startOfDayMillis(date), endOfDayMillis(date))

    fun getBloodSugarBetween(start: LocalDate, end: LocalDate): Flow<List<BloodSugarReading>> =
        dao.getBloodSugarReadingsBetween(startOfDayMillis(start), endOfDayMillis(end))

    suspend fun addBloodSugar(mgDl: Int, at: Instant = Instant.now()) =
        dao.insertBloodSugarReading(
            BloodSugarReading(timestamp = at, mgDl = mgDl, source = DataSourceEnum.MANUAL)
        )

    suspend fun deleteBloodSugar(reading: BloodSugarReading) = dao.deleteBloodSugarReading(reading)

    fun getKetonesSince(since: Instant): Flow<List<KetoneReading>> =
        dao.getKetoneReadingsBetween(since.toEpochMilli(), Long.MAX_VALUE)

    fun getKetonesForDate(date: LocalDate): Flow<List<KetoneReading>> =
        dao.getKetoneReadingsBetween(startOfDayMillis(date), endOfDayMillis(date))

    fun getKetonesBetween(start: LocalDate, end: LocalDate): Flow<List<KetoneReading>> =
        dao.getKetoneReadingsBetween(startOfDayMillis(start), endOfDayMillis(end))

    suspend fun addKetone(ppm: Float, at: Instant = Instant.now()) =
        dao.insertKetoneReading(KetoneReading(timestamp = at, ppm = ppm))

    suspend fun deleteKetone(reading: KetoneReading) = dao.deleteKetoneReading(reading)

    // ----- Meals and heart rate time series ----------------------------------

    /**
     * Meals eaten since [since], for the absorption curves.
     *
     * Like the caffeine curve, this reaches back further than the plotted window:
     * a meal eaten before the left edge is still being absorbed inside it.
     */
    fun getMealsSince(since: Instant): Flow<List<MealEntry>> =
        dao.getMealsBetween(since.toEpochMilli(), Long.MAX_VALUE)

    fun getHeartRateSince(since: Instant): Flow<List<HeartRateBucket>> =
        dao.getHeartRateBucketsBetween(since.toEpochMilli(), Long.MAX_VALUE)

    fun getStepBucketsSince(since: Instant): Flow<List<StepBucket>> =
        dao.getStepBucketsBetween(since.toEpochMilli(), Long.MAX_VALUE)

    /**
     * Moves a meal to when it was actually eaten.
     *
     * Needed because a nutrition source is free to record only the date: every
     * meal then arrives stamped midnight, and an absorption curve anchored there
     * describes a night nobody ate through. The correction survives re-syncing
     * -- meals are only ever inserted, never updated, and the unique index on
     * `externalId` makes the original a no-op the next time round.
     */
    suspend fun setMealTime(meal: MealEntry, at: Instant) =
        dao.updateMeal(meal.copy(timestamp = at))

    /** Rewrites a meal's macros and time together, for correcting one by hand. */
    suspend fun updateMeal(meal: MealEntry) = dao.updateMeal(meal)

    /**
     * Records a meal that was not synced from anywhere.
     *
     * Manual entries carry no `externalId`, which is what keeps them clear of
     * the sync's de-duplication: SQLite treats NULLs as distinct, so no number
     * of Health Connect records can collide with one.
     */
    suspend fun addMeal(
        at: Instant,
        calories: Int?,
        proteinGrams: Float?,
        carbGrams: Float?,
        fatGrams: Float?,
        name: String? = null,
    ) =
        dao.insertMeal(
            MealEntry(
                timestamp = at,
                calories = calories,
                proteinGrams = proteinGrams,
                carbGrams = carbGrams,
                fatGrams = fatGrams,
                name = name,
                source = DataSourceEnum.MANUAL,
            )
        )

    /**
     * Removes a meal, in whichever of the two senses actually makes it stay gone.
     *
     * A hand-entered meal has no upstream record, so the row goes. A synced one
     * is only hidden: deleting the row outright would leave the next sync
     * reading the same record out of Health Connect and inserting it again --
     * both the `externalId` index and the content check look for rows that would
     * no longer be there. The kept row is the evidence that this record has
     * already been dealt with.
     */
    suspend fun deleteMeal(meal: MealEntry) {
        if (meal.externalId == null) dao.deleteMeal(meal)
        else dao.updateMeal(meal.copy(hidden = true))
    }

    /**
     * Pulls the meal and heart rate time series for an arbitrary window into the
     * local cache.
     *
     * Separate from [syncHealthData] because that one is keyed to a calendar day
     * and these two are read over a rolling span that crosses midnight. Both
     * halves degrade independently: no nutrition permission must still leave the
     * heart rate trace populated.
     */
    suspend fun syncTimeSeries(from: Instant, to: Instant): Result<Unit> = runCatching {
        val meals = runCatching { healthDataSource.readMeals(from, to) }.getOrDefault(emptyList())
        if (meals.isNotEmpty()) {
            val known = dao.getKnownMealExternalIds(from.toEpochMilli(), to.toEpochMilli()).toSet()
            val fresh =
                meals
                    .filter { it.externalId !in known }
                    .map {
                        MealEntry(
                            timestamp = it.time,
                            calories = it.calories,
                            proteinGrams = it.proteinGrams,
                            carbGrams = it.carbGrams,
                            fatGrams = it.fatGrams,
                            name = it.name,
                            source = DataSourceEnum.HEALTH_CONNECT,
                            externalId = it.externalId,
                        )
                    }
            // A second filter, on content rather than on id: the index catches
            // the same *record* arriving twice, but not a source that writes one
            // meal as several records with a stable id each. See [MealDuplicates].
            val stored = dao.getMealsInRange(from.toEpochMilli(), to.toEpochMilli())
            val unseen = MealDuplicates.notAlreadyStored(fresh, stored)
            if (unseen.isNotEmpty()) dao.insertMeals(unseen)
        }

        // Heart rate is read as raw samples, and a watch writes one every few
        // seconds -- a week of them is hundreds of thousands of records to fetch
        // and throw away, since they are averaged into five-minute buckets on
        // arrival anyway. Only the recent end is re-read; the buckets already
        // cached by earlier syncs are what fills a wider window in, which is also
        // why this caps the sync and not the chart.
        val heartRateFrom = maxOf(from, to.minus(HEART_RATE_SYNC_HORIZON))
        val samples =
            runCatching { healthDataSource.readHeartRate(heartRateFrom, to) }
                .getOrDefault(emptyList())
        if (samples.isNotEmpty()) dao.upsertHeartRateBuckets(samples.bucketed())

        val preferredSteps = dao.getUserSettings().first()?.preferredStepsPackage
        val hours =
            runCatching { healthDataSource.readStepsByHour(from, to, preferredSteps) }
                .getOrDefault(emptyList())
        if (hours.isNotEmpty()) {
            // Cleared first because an hour can genuinely drop to zero between
            // syncs -- a pinned source changed, a duplicate walk deleted upstream
            // -- and an upsert has no way to say "this hour no longer exists".
            // Bounded by what was actually read so a failed read leaves the cache
            // as it was rather than emptying it, and by the extremes rather than
            // the ends of the list, so nothing outside the read span can be
            // deleted whatever order the source returned.
            dao.deleteStepBucketsBetween(
                hours.minOf { it.hourStart.toEpochMilli() },
                hours.maxOf { it.hourStart.toEpochMilli() } + 1,
            )
            dao.upsertStepBuckets(
                hours.map { StepBucket(hourStartMillis = it.hourStart.toEpochMilli(), steps = it.steps) }
            )
        }
    }

    /**
     * Averages raw heart rate samples into fixed wall-clock buckets.
     *
     * Bucketing on absolute epoch millis rather than on offsets from [from] is
     * what makes the result stable across syncs: the same minute always lands in
     * the same bucket, so an overlapping re-sync overwrites rows instead of
     * producing a second, slightly-shifted copy of the same trace.
     */
    private fun List<HeartRateSample>.bucketed(): List<HeartRateBucket> {
        val bucketMillis = HeartRateBucket.BUCKET_MINUTES * 60_000
        return groupBy { it.time.toEpochMilli() / bucketMillis * bucketMillis }
            .map { (start, samples) ->
                HeartRateBucket(
                    bucketStartMillis = start,
                    bpm = samples.map { it.bpm }.average().toInt(),
                    sampleCount = samples.size,
                )
            }
            .sortedBy { it.bucketStartMillis }
    }

    // ----- Resting heart rate ------------------------------------------------

    fun getRestingHeartRate(date: LocalDate): Flow<RestingHeartRate?> =
        dao.getRestingHeartRate(date)

    fun getRestingHeartRates(start: LocalDate, end: LocalDate): Flow<List<RestingHeartRate>> =
        dao.getRestingHeartRates(start, end)

    suspend fun setRestingHeartRate(date: LocalDate, bpm: Int, source: DataSourceEnum) =
        dao.upsertRestingHeartRate(RestingHeartRate(date, bpm, source))

    // ----- Goals and settings ------------------------------------------------

    fun getUserGoals(): Flow<UserGoals?> = dao.getUserGoals()

    suspend fun upsertUserGoals(goals: UserGoals) = dao.upsertUserGoals(goals)

    fun getUserSettings(): Flow<UserSettings?> = dao.getUserSettings()

    suspend fun upsertUserSettings(settings: UserSettings) = dao.upsertUserSettings(settings)

    // ----- Health Connect sync ----------------------------------------------

    suspend fun healthPermissionState(): HealthPermissionState = healthDataSource.permissionState()

    fun healthPermissions(): Set<String> = healthDataSource.requiredPermissions()

    suspend fun missingHealthPermissions(): Set<String> = healthDataSource.missingPermissions()

    /** Per-app step totals for [date], for the source picker in settings. */
    suspend fun stepSources(date: LocalDate): List<StepSource> =
        healthDataSource.readStepSources(date)

    /**
     * Pulls [date] from Health Connect into the local cache.
     *
     * Glucose samples are inserted rather than cached on the snapshot because
     * they are a time series, not a daily total. Duplicate CGM samples are
     * rejected by the unique index on `externalId`.
     */
    suspend fun syncHealthData(date: LocalDate): Result<Unit> = runCatching {
        val preferredSteps = dao.getUserSettings().first()?.preferredStepsPackage
        val day = healthDataSource.readDay(date, preferredSteps)

        dao.upsertHealthSnapshot(
            HealthDaySnapshot(
                date = date,
                steps = day.steps,
                restingHeartRateBpm = day.restingHeartRateBpm,
                averageHeartRateBpm = day.averageHeartRateBpm,
                sleepMinutes = day.sleepMinutes,
                totalCalories = day.totalCalories,
                activeCalories = day.activeCalories,
                dietaryCalories = day.dietaryCalories,
                proteinGrams = day.proteinGrams,
                carbGrams = day.carbGrams,
                fatGrams = day.fatGrams,
                bestMileSeconds = day.bestMileSeconds,
                weightKg = day.weightKg,
                syncedAt = Instant.now(),
            )
        )

        day.restingHeartRateBpm?.let {
            dao.upsertRestingHeartRate(RestingHeartRate(date, it, DataSourceEnum.HEALTH_CONNECT))
        }

        if (day.glucoseSamples.isNotEmpty()) {
            val known =
                dao.getKnownGlucoseExternalIds(startOfDayMillis(date), endOfDayMillis(date)).toSet()
            val fresh =
                day.glucoseSamples
                    .filter { it.externalId !in known }
                    .map {
                        BloodSugarReading(
                            timestamp = it.time,
                            mgDl = it.mgDl,
                            source = DataSourceEnum.HEALTH_CONNECT,
                            externalId = it.externalId,
                        )
                    }
            if (fresh.isNotEmpty()) dao.insertBloodSugarReadings(fresh)
        }
    }
}
