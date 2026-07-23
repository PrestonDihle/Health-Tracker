package com.prestondihle.healthtracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Timestamp parameters are epoch milliseconds rather than [java.time.Instant].
 * Room's type converters do not apply to query arguments used in range
 * comparisons, so callers convert before querying.
 */
@Dao
interface TrackerDao {

    // ----- Daily log ---------------------------------------------------------

    @Query("SELECT * FROM DailyLog WHERE date = :date")
    fun getDailyLog(date: LocalDate): Flow<DailyLog?>

    @Query("SELECT * FROM DailyLog WHERE date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    fun getDailyLogs(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyLog>>

    @Upsert suspend fun upsertDailyLog(log: DailyLog)

    // ----- Health Connect snapshot cache -------------------------------------

    @Query("SELECT * FROM HealthDaySnapshot WHERE date = :date")
    fun getHealthSnapshot(date: LocalDate): Flow<HealthDaySnapshot?>

    @Query("SELECT * FROM HealthDaySnapshot WHERE date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    fun getHealthSnapshots(startDate: LocalDate, endDate: LocalDate): Flow<List<HealthDaySnapshot>>

    @Query("SELECT MIN(bestMileSeconds) FROM HealthDaySnapshot WHERE bestMileSeconds IS NOT NULL")
    fun getBestMileSecondsAllTime(): Flow<Int?>

    @Upsert suspend fun upsertHealthSnapshot(snapshot: HealthDaySnapshot)

    // ----- Blood pressure ----------------------------------------------------

    @Query("SELECT * FROM BloodPressureReading WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC")
    fun getBloodPressureReadingsBetween(start: Long, end: Long): Flow<List<BloodPressureReading>>

    @Insert suspend fun insertBloodPressureReading(reading: BloodPressureReading)

    @Delete suspend fun deleteBloodPressureReading(reading: BloodPressureReading)

    // ----- Weight and waist --------------------------------------------------

    @Query("SELECT * FROM WeightEntry WHERE date = :date")
    fun getWeight(date: LocalDate): Flow<WeightEntry?>

    @Query("SELECT * FROM WeightEntry WHERE date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    fun getWeights(startDate: LocalDate, endDate: LocalDate): Flow<List<WeightEntry>>

    @Upsert suspend fun upsertWeight(entry: WeightEntry)

    @Query("SELECT * FROM WaistEntry WHERE date = :date")
    fun getWaist(date: LocalDate): Flow<WaistEntry?>

    @Query("SELECT * FROM WaistEntry WHERE date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    fun getWaists(startDate: LocalDate, endDate: LocalDate): Flow<List<WaistEntry>>

    /** Most recent waist measurement at or before [date], used to seed the stepper. */
    @Query("SELECT * FROM WaistEntry WHERE date <= :date ORDER BY date DESC LIMIT 1")
    fun getLatestWaistOnOrBefore(date: LocalDate): Flow<WaistEntry?>

    @Upsert suspend fun upsertWaist(entry: WaistEntry)

    // ----- Hydration ---------------------------------------------------------

    @Query("SELECT * FROM HydrationEntry WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC")
    fun getHydrationBetween(start: Long, end: Long): Flow<List<HydrationEntry>>

    @Query("SELECT COALESCE(SUM(milliliters), 0) FROM HydrationEntry WHERE timestamp >= :start AND timestamp < :end")
    fun getHydrationTotalMl(start: Long, end: Long): Flow<Int>

    @Insert suspend fun insertHydration(entry: HydrationEntry)

    @Delete suspend fun deleteHydration(entry: HydrationEntry)

    // ----- Bodyweight exercise sets -----------------------------------------

    @Query("SELECT * FROM ExerciseSet WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC")
    fun getExerciseSetsBetween(start: Long, end: Long): Flow<List<ExerciseSet>>

    @Query(
        "SELECT COALESCE(SUM(reps), 0) FROM ExerciseSet " +
            "WHERE movement = :movement AND timestamp >= :start AND timestamp < :end"
    )
    fun getRepTotal(movement: MovementType, start: Long, end: Long): Flow<Int>

    @Insert suspend fun insertExerciseSet(set: ExerciseSet)

    @Delete suspend fun deleteExerciseSet(set: ExerciseSet)

    // ----- Supplements -------------------------------------------------------

    @Query("SELECT * FROM CaffeineIntake WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC")
    fun getCaffeineIntakesBetween(start: Long, end: Long): Flow<List<CaffeineIntake>>

    @Insert suspend fun insertCaffeineIntake(intake: CaffeineIntake)

    @Delete suspend fun deleteCaffeineIntake(intake: CaffeineIntake)

    @Query("SELECT * FROM CreatineIntake WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC")
    fun getCreatineIntakesBetween(start: Long, end: Long): Flow<List<CreatineIntake>>

    @Insert suspend fun insertCreatineIntake(intake: CreatineIntake)

    @Delete suspend fun deleteCreatineIntake(intake: CreatineIntake)

    // ----- Fasting: actual sessions -----------------------------------------

    @Query("SELECT * FROM FastingSession WHERE endInstant IS NULL ORDER BY startInstant DESC LIMIT 1")
    fun getActiveFastingSession(): Flow<FastingSession?>

    /**
     * Every session overlapping the window, including one still open. Adherence
     * needs sessions that *straddle* the window edges, not just those contained
     * within it, so this cannot filter on containment.
     */
    @Query(
        "SELECT * FROM FastingSession " +
            "WHERE startInstant < :end AND (endInstant IS NULL OR endInstant > :start) " +
            "ORDER BY startInstant ASC"
    )
    fun getFastingSessionsOverlapping(start: Long, end: Long): Flow<List<FastingSession>>

    @Insert suspend fun insertFastingSession(session: FastingSession)

    @Update suspend fun updateFastingSession(session: FastingSession)

    @Delete suspend fun deleteFastingSession(session: FastingSession)

    // ----- Fasting: the plan -------------------------------------------------

    @Query("SELECT * FROM FastingPlanDay")
    fun getFastingPlan(): Flow<List<FastingPlanDay>>

    @Upsert suspend fun upsertFastingPlanDay(day: FastingPlanDay)

    @Upsert suspend fun upsertFastingPlan(days: List<FastingPlanDay>)

    @Query(
        "SELECT * FROM PlannedExtendedFast " +
            "WHERE startInstant < :end AND endInstant > :start " +
            "ORDER BY startInstant ASC"
    )
    fun getPlannedExtendedFastsOverlapping(start: Long, end: Long): Flow<List<PlannedExtendedFast>>

    @Insert suspend fun insertPlannedExtendedFast(fast: PlannedExtendedFast)

    @Delete suspend fun deletePlannedExtendedFast(fast: PlannedExtendedFast)

    // ----- Weekly performance ------------------------------------------------

    @Query("SELECT * FROM WeeklyPerformance WHERE isoWeek = :isoWeek")
    fun getWeeklyPerformance(isoWeek: String): Flow<WeeklyPerformance?>

    @Query("SELECT * FROM WeeklyPerformance ORDER BY isoWeek ASC")
    fun getAllWeeklyPerformances(): Flow<List<WeeklyPerformance>>

    @Upsert suspend fun upsertWeeklyPerformance(performance: WeeklyPerformance)

    // ----- Blood sugar and ketones ------------------------------------------

    @Query("SELECT * FROM BloodSugarReading WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC")
    fun getBloodSugarReadingsBetween(start: Long, end: Long): Flow<List<BloodSugarReading>>

    @Insert suspend fun insertBloodSugarReading(reading: BloodSugarReading)

    /**
     * Ignores rows whose [BloodSugarReading.externalId] is already present, so a
     * repeated Health Connect sync cannot duplicate CGM samples.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBloodSugarReadings(readings: List<BloodSugarReading>)

    @Query("SELECT externalId FROM BloodSugarReading WHERE externalId IS NOT NULL AND timestamp >= :start AND timestamp < :end")
    suspend fun getKnownGlucoseExternalIds(start: Long, end: Long): List<String>

    @Delete suspend fun deleteBloodSugarReading(reading: BloodSugarReading)

    @Query("SELECT * FROM KetoneReading WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC")
    fun getKetoneReadingsBetween(start: Long, end: Long): Flow<List<KetoneReading>>

    @Insert suspend fun insertKetoneReading(reading: KetoneReading)

    @Delete suspend fun deleteKetoneReading(reading: KetoneReading)

    // ----- Resting heart rate ------------------------------------------------

    @Query("SELECT * FROM RestingHeartRate WHERE date = :date")
    fun getRestingHeartRate(date: LocalDate): Flow<RestingHeartRate?>

    @Query("SELECT * FROM RestingHeartRate WHERE date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    fun getRestingHeartRates(startDate: LocalDate, endDate: LocalDate): Flow<List<RestingHeartRate>>

    @Upsert suspend fun upsertRestingHeartRate(rate: RestingHeartRate)

    // ----- Goals and settings ------------------------------------------------

    @Query("SELECT * FROM UserGoals WHERE id = 1")
    fun getUserGoals(): Flow<UserGoals?>

    @Upsert suspend fun upsertUserGoals(goals: UserGoals)

    @Query("SELECT * FROM UserSettings WHERE id = 1")
    fun getUserSettings(): Flow<UserSettings?>

    @Upsert suspend fun upsertUserSettings(settings: UserSettings)
}
