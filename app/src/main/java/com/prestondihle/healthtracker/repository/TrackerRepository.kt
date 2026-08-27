package com.prestondihle.healthtracker.repository

import com.prestondihle.healthtracker.data.AftAttempt
import com.prestondihle.healthtracker.data.BloodPressureReading
import com.prestondihle.healthtracker.data.BloodSugarReading
import com.prestondihle.healthtracker.data.CaffeineIntake
import com.prestondihle.healthtracker.data.CreatineIntake
import com.prestondihle.healthtracker.data.CsvBackup
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
import com.prestondihle.healthtracker.data.SleepSessionEntry
import com.prestondihle.healthtracker.data.SleepStageEntry
import com.prestondihle.healthtracker.data.StepBucket
import com.prestondihle.healthtracker.data.TrackerDao
import com.prestondihle.healthtracker.data.UserGoals
import com.prestondihle.healthtracker.data.UserSettings
import com.prestondihle.healthtracker.data.WaistEntry
import com.prestondihle.healthtracker.data.WeeklyPerformance
import com.prestondihle.healthtracker.data.Supplement
import com.prestondihle.healthtracker.data.SupplementDose
import com.prestondihle.healthtracker.data.SupplementSlot
import com.prestondihle.healthtracker.data.WeightEntry
import com.prestondihle.healthtracker.data.WeightSubGoal
import com.prestondihle.healthtracker.data.CardOrderEntry
import com.prestondihle.healthtracker.domain.GlucoseGaps
import com.prestondihle.healthtracker.domain.MealDuplicates
import com.prestondihle.healthtracker.domain.RunBreakdown
import com.prestondihle.healthtracker.domain.RunZones
import com.prestondihle.healthtracker.domain.SleepNight
import com.prestondihle.healthtracker.domain.SleepStageInterval
import com.prestondihle.healthtracker.health.HealthDataSource
import com.prestondihle.healthtracker.health.HealthPermissionState
import com.prestondihle.healthtracker.health.HeartRateSample
import com.prestondihle.healthtracker.health.StepSource
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    /**
     * The runs in a window, each broken into minutes per intensity zone.
     *
     * Read live from Health Connect rather than cached: runs are few, and the
     * zones depend on a max heart rate that changes in settings -- recomputing on
     * demand keeps the chart honest with no table to migrate or re-key when that
     * number moves. A run's heart rate is read over its own span, so a run with
     * no trace simply comes back empty rather than wrong.
     */
    suspend fun getRunBreakdowns(
        from: Instant,
        to: Instant,
        maxHeartRate: Int,
    ): List<RunBreakdown> {
        val runs = runCatching { healthDataSource.readRuns(from, to) }.getOrDefault(emptyList())
        return runs
            .map { run ->
                val samples =
                    runCatching { healthDataSource.readHeartRate(run.start, run.end) }
                        .getOrDefault(emptyList())
                        .map { it.time to it.bpm }
                RunZones.breakdown(run.start, run.end, run.distanceMeters, samples, maxHeartRate)
            }
            .sortedBy { it.start }
    }

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

    fun getLatestWeight(): Flow<WeightEntry?> = dao.getLatestWeight()

    fun getWeightSubGoals(): Flow<List<WeightSubGoal>> = dao.getWeightSubGoals()

    suspend fun addWeightSubGoalKg(kg: Float) = dao.insertWeightSubGoal(WeightSubGoal(kg = kg))

    suspend fun deleteWeightSubGoal(subGoal: WeightSubGoal) = dao.deleteWeightSubGoal(subGoal)

    fun getWaist(date: LocalDate): Flow<WaistEntry?> = dao.getWaist(date)

    fun getWaists(start: LocalDate, end: LocalDate): Flow<List<WaistEntry>> =
        dao.getWaists(start, end)

    fun getLatestWaistOnOrBefore(date: LocalDate): Flow<WaistEntry?> =
        dao.getLatestWaistOnOrBefore(date)

    suspend fun setWaistCm(date: LocalDate, cm: Float) = dao.upsertWaist(WaistEntry(date, cm))

    // ----- Supplements -------------------------------------------------------

    /**
     * Writes every table to [destination] as a zip of CSVs.
     *
     * Reads the schema rather than a list of tables, so it goes on covering the
     * whole database as that schema grows. See [CsvBackup].
     */
    suspend fun writeCsvBackup(destination: File) = CsvBackup.writeZip(dao, destination)

    fun getSupplements(): Flow<List<Supplement>> = dao.getSupplements()

    suspend fun addSupplement(name: String, dose: String, slot: SupplementSlot) =
        dao.insertSupplement(Supplement(name = name.trim(), dose = dose.trim(), slot = slot))

    suspend fun updateSupplement(supplement: Supplement) = dao.updateSupplement(supplement)

    /**
     * Removes a supplement and everything logged against it.
     *
     * Both halves, in that order, because there are no foreign keys anywhere in
     * this schema and so nothing cascades on the app's behalf. Leaving the doses
     * would leave rows keyed on an id nothing can resolve -- invisible, and
     * counted by anything that later learns to read the history.
     */
    suspend fun deleteSupplement(supplement: Supplement) {
        dao.deleteDosesOf(supplement.id)
        dao.deleteSupplement(supplement)
    }

    /** Which supplements have been taken on [date], by id. */
    fun getSupplementsTakenOn(date: LocalDate): Flow<Set<Long>> =
        dao.getSupplementDosesOn(date).map { doses -> doses.map { it.supplementId }.toSet() }

    suspend fun setSupplementTaken(supplement: Supplement, date: LocalDate, taken: Boolean) {
        if (taken) dao.insertSupplementDose(SupplementDose(supplement.id, date))
        else dao.deleteSupplementDose(supplement.id, date)
    }

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

    suspend fun updateHydration(entry: HydrationEntry) = dao.updateHydration(entry)

    /**
     * Removes the row outright, unlike a synced meal.
     *
     * Hydration is hand-entered and has no upstream record to arrive again on
     * the next sync, so there is nothing for a hidden flag to keep out and a
     * real delete is the honest one.
     */
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

    /** Readings in the rolling window ending now -- what the Wellness chart shows. */
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

    // ----- Sleep -------------------------------------------------------------

    /**
     * Nights overlapping the window, assembled with their stages.
     *
     * The stages are fetched over the same span and grouped in memory rather than
     * joined, because there are no foreign keys in this schema and a night is at
     * most a few dozen stretches. A stage whose session is not in the window is
     * dropped: it belongs to a night that is not being drawn.
     */
    fun getSleepNightsSince(since: Instant): Flow<List<SleepNight>> =
        combine(
            dao.getSleepSessionsBetween(since.toEpochMilli(), Long.MAX_VALUE),
            dao.getSleepStagesBetween(since.toEpochMilli(), Long.MAX_VALUE),
        ) { sessions, stages ->
            val bySession = stages.groupBy { it.sessionStartMillis }
            sessions.map { session ->
                SleepNight(
                    start = session.start,
                    end = session.end,
                    stages =
                        bySession[session.startMillis]
                            .orEmpty()
                            .map { SleepStageInterval(it.start, it.end, it.stage) },
                )
            }
        }

    /**
     * The most recent night, for the Today card.
     *
     * Its own query rather than the first of [getSleepNightsSince], so the card
     * still has something to show on a morning when the window it would have been
     * found in holds nothing -- a phone that did not sync for a day still knows
     * what the last night it saw was.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getLatestSleepNight(): Flow<SleepNight?> =
        dao.getLatestSleepSession().flatMapLatest { session ->
            if (session == null) flowOf(null)
            else
                dao.getSleepStagesFor(session.startMillis).map { stages ->
                    SleepNight(
                        start = session.start,
                        end = session.end,
                        stages = stages.map { SleepStageInterval(it.start, it.end, it.stage) },
                    )
                }
        }

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
                hours.map { StepBucket(bucketStartMillis = it.hourStart.toEpochMilli(), steps = it.steps) }
            )
        }

        val nights =
            runCatching { healthDataSource.readSleepSessions(from, to) }
                .getOrDefault(emptyList())
        for (night in nights) {
            dao.upsertSleepSessions(
                listOf(
                    SleepSessionEntry(
                        startMillis = night.start.toEpochMilli(),
                        endMillis = night.end.toEpochMilli(),
                        externalId = night.externalId,
                    )
                )
            )
            // Cleared per night before rewriting, which is the [StepBucket]
            // argument in a case where it bites harder: a tracker scores a night
            // when it ends and re-scores it after the morning's processing, and
            // the second scoring routinely has fewer stretches than the first.
            // Upsert alone would leave the stretches that no longer exist in
            // place, drawing a hypnogram that flicks between two readings of the
            // same hour. Scoped to the night rather than to the window so a
            // failed read of one night cannot empty another.
            dao.deleteSleepStagesFor(night.start.toEpochMilli())
            if (night.stages.isNotEmpty()) {
                dao.upsertSleepStages(
                    night.stages.map {
                        SleepStageEntry(
                            sessionStartMillis = night.start.toEpochMilli(),
                            startMillis = it.start.toEpochMilli(),
                            endMillis = it.end.toEpochMilli(),
                            stage = it.stage,
                        )
                    }
                )
            }
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

    // ----- Card order --------------------------------------------------------

    /** The saved card ids for [tab], in order; empty means the tab's built-in order. */
    fun getCardOrder(tab: String): Flow<List<String>> =
        dao.getCardOrder(tab).map { rows -> rows.map { it.cardId } }

    /** Rewrites the whole order for [tab], each id's slot its index in [cardIds]. */
    suspend fun setCardOrder(tab: String, cardIds: List<String>) =
        dao.upsertCardOrder(cardIds.mapIndexed { index, id -> CardOrderEntry(tab, id, index) })

    // ----- Army Fitness Test -------------------------------------------------

    /** Every attempt, oldest first, which is the order the score trend plots. */
    fun getAftAttempts(): Flow<List<AftAttempt>> = dao.getAftAttempts()

    /** The most recent attempt, or null before the first one is logged. */
    fun getLatestAftAttempt(): Flow<AftAttempt?> = dao.getLatestAftAttempt()

    /** Returns the stored id so a just-logged attempt can be added to without a re-query. */
    suspend fun addAftAttempt(attempt: AftAttempt): Long = dao.insertAftAttempt(attempt)

    suspend fun updateAftAttempt(attempt: AftAttempt) = dao.updateAftAttempt(attempt)

    /**
     * Removes the attempt outright.
     *
     * Hand-entered with nothing upstream to re-offer it, so this is a real
     * delete like hydration's and unlike a synced meal's hidden flag.
     */
    suspend fun deleteAftAttempt(attempt: AftAttempt) = dao.deleteAftAttempt(attempt)

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

    /**
     * Asks the source again about the stretches where the blood sugar trace is
     * missing.
     *
     * [syncHealthData] only ever re-reads the day it is given, which is almost
     * always today, so a reading that reaches Health Connect late -- a monitor
     * that was out of Bluetooth range and uploaded hours afterwards -- lands on a
     * day nothing asks about any more. The hole is then permanent, and looks on
     * the chart exactly like a sensor that was genuinely not reporting.
     *
     * Returns how many readings were recovered, which is what lets the caller say
     * so rather than silently changing a chart the reader was looking at.
     *
     * Only the gaps are read, not the window: on a healthy trace this finds
     * nothing to do and costs one local query. It does re-ask about a span that
     * is genuinely empty, on every refresh, forever -- which is the price of not
     * keeping a record of what has already been given up on, and is bounded by
     * [GlucoseGaps.MAX_SPANS] queries against a three-day window.
     */
    suspend fun backfillGlucoseGaps(now: Instant = Instant.now()): Result<Int> = runCatching {
        val from = now.minus(Duration.ofHours(GlucoseGaps.WINDOW_HOURS))
        val cached = dao.getBloodSugarReadingsBetween(from.toEpochMilli(), now.toEpochMilli()).first()
        val gaps = GlucoseGaps.spans(cached.map { it.timestamp }, from, now)
        if (gaps.isEmpty()) return@runCatching 0

        // Every id already held across the whole window, fetched once: a span is
        // padded past the readings that bound it, so each read comes back holding
        // records this has seen before whether or not anything new arrived.
        //
        // Added to as it goes, because the padding means two neighbouring spans
        // can overlap by a few minutes. The unique index would refuse the second
        // copy either way; what this protects is the count, which is reported to
        // the reader and would otherwise claim recoveries that never happened.
        val known =
            dao.getKnownGlucoseExternalIds(from.toEpochMilli(), now.toEpochMilli())
                .toMutableSet()

        var recovered = 0
        for (gap in gaps) {
            val samples =
                runCatching { healthDataSource.readGlucose(gap.from, gap.to) }
                    .getOrDefault(emptyList())
            val fresh =
                samples
                    .filter { it.externalId != null && it.externalId !in known }
                    .map {
                        BloodSugarReading(
                            timestamp = it.time,
                            mgDl = it.mgDl,
                            source = DataSourceEnum.HEALTH_CONNECT,
                            externalId = it.externalId,
                        )
                    }
            if (fresh.isNotEmpty()) {
                dao.insertBloodSugarReadings(fresh)
                known += fresh.mapNotNull { it.externalId }
                recovered += fresh.size
            }
        }
        recovered
    }
}
