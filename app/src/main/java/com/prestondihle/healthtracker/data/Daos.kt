package com.prestondihle.healthtracker.data

import android.database.Cursor
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
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

    /**
     * The most recent weight logged, used to seed the waypoint stepper.
     *
     * Manual entries only, unlike the trend, which merges these with the synced
     * snapshot. This is where a control opens rather than a figure anybody
     * reads, so the extra query to find out whether Health Connect happens to
     * hold something a day fresher buys nothing.
     */
    @Query("SELECT * FROM WeightEntry ORDER BY date DESC LIMIT 1")
    fun getLatestWeight(): Flow<WeightEntry?>

    /** Heaviest first, which is the order they are passed on the way down. */
    @Query("SELECT * FROM WeightSubGoal ORDER BY kg DESC")
    fun getWeightSubGoals(): Flow<List<WeightSubGoal>>

    /**
     * IGNORE rather than REPLACE: the unique index means a mark at a weight
     * already staged is the same mark, and replacing it would hand it a new id
     * for no reason.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWeightSubGoal(subGoal: WeightSubGoal)

    @Delete suspend fun deleteWeightSubGoal(subGoal: WeightSubGoal)

    @Query("SELECT * FROM WaistEntry WHERE date = :date")
    fun getWaist(date: LocalDate): Flow<WaistEntry?>

    @Query("SELECT * FROM WaistEntry WHERE date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    fun getWaists(startDate: LocalDate, endDate: LocalDate): Flow<List<WaistEntry>>

    /** Most recent waist measurement at or before [date], used to seed the stepper. */
    @Query("SELECT * FROM WaistEntry WHERE date <= :date ORDER BY date DESC LIMIT 1")
    fun getLatestWaistOnOrBefore(date: LocalDate): Flow<WaistEntry?>

    @Upsert suspend fun upsertWaist(entry: WaistEntry)

    // ----- Supplements -------------------------------------------------------

    /**
     * A raw cursor, for dumping a table to CSV without a method per table.
     *
     * The alternative is fifteen hand-written `SELECT *` queries plus a list of
     * them kept in step by hand -- a backup that silently stops covering a table
     * on the day one is added, and nobody looks at a backup until they need it.
     * Driving the export off `sqlite_master` instead means the file follows the
     * schema on its own.
     *
     * Only ever called with table names read back out of `sqlite_master`, so no
     * caller-supplied string goes anywhere near it.
     *
     * Not `suspend`: Room will not build a suspending `@RawQuery` that returns a
     * `Cursor`, since it cannot know when to close it. The caller does the
     * closing, and moves the whole export onto an IO dispatcher.
     */
    @RawQuery fun rawCursor(query: SupportSQLiteQuery): Cursor

    /**
     * The whole stack, in the order it is taken.
     *
     * Ordered by slot and then by name, and the slot ordering is spelled out
     * rather than left to the column -- sorting the stored text would put
     * evening before midday before morning, which is the day backwards.
     */
    @Query(
        "SELECT * FROM Supplement ORDER BY CASE slot " +
            "WHEN 'MORNING' THEN 0 WHEN 'MIDDAY' THEN 1 ELSE 2 END, name COLLATE NOCASE"
    )
    fun getSupplements(): Flow<List<Supplement>>

    /**
     * IGNORE rather than REPLACE: the unique index means the same name in the
     * same slot is the same entry, and replacing it would hand it a new id --
     * silently orphaning every dose already logged against the old one.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSupplement(supplement: Supplement)

    @Update suspend fun updateSupplement(supplement: Supplement)

    @Delete suspend fun deleteSupplement(supplement: Supplement)

    /** The doses logged against one supplement, for clearing when it is removed. */
    @Query("DELETE FROM SupplementDose WHERE supplementId = :supplementId")
    suspend fun deleteDosesOf(supplementId: Long)

    @Query("SELECT * FROM SupplementDose WHERE date = :date")
    fun getSupplementDosesOn(date: LocalDate): Flow<List<SupplementDose>>

    /** IGNORE, so ticking a box that is already ticked is absorbed rather than thrown. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSupplementDose(dose: SupplementDose)

    @Query("DELETE FROM SupplementDose WHERE supplementId = :supplementId AND date = :date")
    suspend fun deleteSupplementDose(supplementId: Long, date: LocalDate)

    // ----- Grip strength -----------------------------------------------------

    @Query("SELECT * FROM GripStrengthEntry WHERE date = :date")
    fun getGripStrength(date: LocalDate): Flow<GripStrengthEntry?>

    @Query(
        "SELECT * FROM GripStrengthEntry WHERE date BETWEEN :startDate AND :endDate ORDER BY date ASC"
    )
    fun getGripStrengths(startDate: LocalDate, endDate: LocalDate): Flow<List<GripStrengthEntry>>

    /** Most recent measurement at or before [date], for seeding the steppers. */
    @Query("SELECT * FROM GripStrengthEntry WHERE date <= :date ORDER BY date DESC LIMIT 1")
    fun getLatestGripStrengthOnOrBefore(date: LocalDate): Flow<GripStrengthEntry?>

    @Upsert suspend fun upsertGripStrength(entry: GripStrengthEntry)

    // ----- Hydration ---------------------------------------------------------

    @Query("SELECT * FROM HydrationEntry WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC")
    fun getHydrationBetween(start: Long, end: Long): Flow<List<HydrationEntry>>

    @Query("SELECT COALESCE(SUM(milliliters), 0) FROM HydrationEntry WHERE timestamp >= :start AND timestamp < :end")
    fun getHydrationTotalMl(start: Long, end: Long): Flow<Int>

    @Insert suspend fun insertHydration(entry: HydrationEntry)

    @Update suspend fun updateHydration(entry: HydrationEntry)

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

    @Update suspend fun updateCaffeineIntake(intake: CaffeineIntake)

    @Delete suspend fun deleteCaffeineIntake(intake: CaffeineIntake)

    @Query("SELECT * FROM CreatineIntake WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC")
    fun getCreatineIntakesBetween(start: Long, end: Long): Flow<List<CreatineIntake>>

    @Insert suspend fun insertCreatineIntake(intake: CreatineIntake)

    @Delete suspend fun deleteCreatineIntake(intake: CreatineIntake)

    // ----- Fasting: actual sessions -----------------------------------------

    @Query("SELECT * FROM FastingSession WHERE endInstant IS NULL ORDER BY startInstant DESC LIMIT 1")
    fun getActiveFastingSession(): Flow<FastingSession?>

    /** Every session ever logged, for all-time stats such as the longest fast. */
    @Query("SELECT * FROM FastingSession ORDER BY startInstant ASC")
    fun getAllFastingSessions(): Flow<List<FastingSession>>

    /** The fast most recently finished, so a forgotten Stop can be corrected after the fact. */
    @Query(
        "SELECT * FROM FastingSession WHERE endInstant IS NOT NULL ORDER BY endInstant DESC LIMIT 1"
    )
    fun getLastCompletedFastingSession(): Flow<FastingSession?>

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

    // ----- Meals and heart rate time series ----------------------------------

    /**
     * Meals to show. Hidden rows are deletions, kept only so a sync cannot undo
     * them, and must never reach a screen or a curve.
     */
    @Query(
        "SELECT * FROM MealEntry WHERE timestamp >= :start AND timestamp < :end " +
            "AND hidden = 0 ORDER BY timestamp ASC"
    )
    fun getMealsBetween(start: Long, end: Long): Flow<List<MealEntry>>

    /**
     * Meals in a window as a one-shot read, **including hidden ones**.
     *
     * The Flow above is for screens; a sync needs the current contents once, to
     * compare what it is about to write against what is already there. Hidden
     * rows count for that: a meal deleted by hand must also keep out the
     * upstream duplicate of itself arriving later under a different record id.
     */
    @Query("SELECT * FROM MealEntry WHERE timestamp >= :start AND timestamp < :end")
    suspend fun getMealsInRange(start: Long, end: Long): List<MealEntry>

    @Insert suspend fun insertMeal(meal: MealEntry)

    /** Ignores rows whose externalId is already present, so a repeated sync cannot duplicate meals. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMeals(meals: List<MealEntry>)

    /**
     * Rewrites one meal, for correcting a time the source never recorded.
     *
     * Safe against a later sync: [insertMeals] ignores anything whose externalId
     * is already present, so a corrected row is never overwritten by the
     * midnight-stamped original coming round again.
     */
    @Update suspend fun updateMeal(meal: MealEntry)

    @Query("SELECT externalId FROM MealEntry WHERE externalId IS NOT NULL AND timestamp >= :start AND timestamp < :end")
    suspend fun getKnownMealExternalIds(start: Long, end: Long): List<String>

    @Delete suspend fun deleteMeal(meal: MealEntry)

    @Query(
        "SELECT * FROM HeartRateBucket " +
            "WHERE bucketStartMillis >= :start AND bucketStartMillis < :end " +
            "ORDER BY bucketStartMillis ASC"
    )
    fun getHeartRateBucketsBetween(start: Long, end: Long): Flow<List<HeartRateBucket>>

    /** Upsert keyed on the bucket's start time, so re-syncing a window rewrites it in place. */
    @Upsert suspend fun upsertHeartRateBuckets(buckets: List<HeartRateBucket>)

    @Query(
        "SELECT * FROM StepBucket " +
            "WHERE hourStartMillis >= :start AND hourStartMillis < :end " +
            "ORDER BY hourStartMillis ASC"
    )
    fun getStepBucketsBetween(start: Long, end: Long): Flow<List<StepBucket>>

    @Upsert suspend fun upsertStepBuckets(buckets: List<StepBucket>)

    /**
     * Clears a span before it is rewritten.
     *
     * Unlike heart rate, an hour's step count can legitimately fall to zero
     * between syncs -- a source is switched in settings, or a duplicate walk is
     * removed upstream. Upsert alone cannot express that: the stale row for an
     * hour the new read no longer reports would simply survive.
     */
    @Query("DELETE FROM StepBucket WHERE hourStartMillis >= :start AND hourStartMillis < :end")
    suspend fun deleteStepBucketsBetween(start: Long, end: Long)

    // ----- Sleep -------------------------------------------------------------

    /**
     * Nights overlapping a span, rather than starting inside it.
     *
     * A night beginning at 23:00 belongs to a window opening at midnight, and
     * anchoring on the start alone would drop it -- which is exactly the half of
     * the night a morning reader is asking about.
     */
    @Query(
        "SELECT * FROM SleepSessionEntry " +
            "WHERE endMillis > :start AND startMillis < :end " +
            "ORDER BY startMillis ASC"
    )
    fun getSleepSessionsBetween(start: Long, end: Long): Flow<List<SleepSessionEntry>>

    /** The most recently finished night, for the card that says "last night". */
    @Query("SELECT * FROM SleepSessionEntry ORDER BY endMillis DESC LIMIT 1")
    fun getLatestSleepSession(): Flow<SleepSessionEntry?>

    @Query(
        "SELECT * FROM SleepStageEntry " +
            "WHERE endMillis > :start AND startMillis < :end " +
            "ORDER BY startMillis ASC"
    )
    fun getSleepStagesBetween(start: Long, end: Long): Flow<List<SleepStageEntry>>

    @Query(
        "SELECT * FROM SleepStageEntry " +
            "WHERE sessionStartMillis = :sessionStart ORDER BY startMillis ASC"
    )
    fun getSleepStagesFor(sessionStart: Long): Flow<List<SleepStageEntry>>

    @Upsert suspend fun upsertSleepSessions(sessions: List<SleepSessionEntry>)

    @Upsert suspend fun upsertSleepStages(stages: List<SleepStageEntry>)

    /**
     * Clears a night's stages before they are rewritten.
     *
     * Sleep is the one cache whose upstream *revises* itself: a tracker scores a
     * night once when it ends and again after the morning's processing, and the
     * second scoring routinely has fewer stretches than the first. Upsert alone
     * would leave the stretches that no longer exist lying in the table, which
     * draws as a hypnogram flicking between two readings of the same hour.
     */
    @Query("DELETE FROM SleepStageEntry WHERE sessionStartMillis = :sessionStart")
    suspend fun deleteSleepStagesFor(sessionStart: Long)

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

    // ----- Card order --------------------------------------------------------

    @Query("SELECT * FROM CardOrderEntry WHERE tab = :tab ORDER BY position ASC")
    fun getCardOrder(tab: String): Flow<List<CardOrderEntry>>

    @Upsert suspend fun upsertCardOrder(entries: List<CardOrderEntry>)
}
