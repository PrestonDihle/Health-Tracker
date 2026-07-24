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
import com.prestondihle.healthtracker.data.HealthDaySnapshot
import com.prestondihle.healthtracker.data.HydrationEntry
import com.prestondihle.healthtracker.data.KetoneReading
import com.prestondihle.healthtracker.data.MovementType
import com.prestondihle.healthtracker.data.PlannedExtendedFast
import com.prestondihle.healthtracker.data.RestingHeartRate
import com.prestondihle.healthtracker.data.TrackerDao
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.data.UserSettings
import com.prestondihle.healthtracker.data.WaistEntry
import com.prestondihle.healthtracker.data.WeeklyPerformance
import com.prestondihle.healthtracker.data.WeightEntry
import com.prestondihle.healthtracker.health.HealthDataSource
import com.prestondihle.healthtracker.health.HealthPermissionState
import com.prestondihle.healthtracker.health.StepSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

    suspend fun addKetone(mmolL: Float, at: Instant = Instant.now()) =
        dao.insertKetoneReading(KetoneReading(timestamp = at, mmolL = mmolL))

    suspend fun deleteKetone(reading: KetoneReading) = dao.deleteKetoneReading(reading)

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
