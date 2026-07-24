package com.prestondihle.healthtracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

enum class FastingType { OMAD, EXTENDED_24, EXTENDED_36, EXTENDED_48, CUSTOM }

enum class DataSourceEnum { MANUAL, HEALTH_CONNECT }

enum class UnitSystemEnum { IMPERIAL, METRIC }

enum class MovementType { PUSHUP, AIR_SQUAT }

/**
 * Purely manual, one-row-per-day entries.
 *
 * Steps, sleep duration, calories and macros deliberately do *not* live here --
 * they come from Health Connect and are cached in [HealthDaySnapshot]. Keeping
 * them apart avoids two writable sources of truth for the same number.
 */
@Entity
data class DailyLog(
    @PrimaryKey val date: LocalDate,
    val vibe: Int? = null,
    val energy: Int? = null,
    val focus: Int? = null,
    val sleepQuality: Int? = null,
    val bookPagesRead: Int? = null,
)

/**
 * Daily rollup of everything read from Health Connect.
 *
 * This is a cache, not a source of truth: it is safe to delete and re-sync. It
 * exists so trends and history survive offline and so the dashboard does not
 * have to re-query Health Connect on every recomposition.
 */
@Entity
data class HealthDaySnapshot(
    @PrimaryKey val date: LocalDate,
    val steps: Int? = null,
    val restingHeartRateBpm: Int? = null,
    val averageHeartRateBpm: Int? = null,
    val sleepMinutes: Int? = null,
    val totalCalories: Int? = null,
    val activeCalories: Int? = null,
    /**
     * Calories *eaten*, from nutrition records -- not the same number as
     * [totalCalories], which is energy burned. Kept apart because the dashboard
     * shows this one next to the macros, where a burn figure would misread as
     * intake.
     */
    val dietaryCalories: Int? = null,
    val proteinGrams: Float? = null,
    val carbGrams: Float? = null,
    val fatGrams: Float? = null,
    /** Best average mile pace across runs of at least a mile on this date. */
    val bestMileSeconds: Int? = null,
    /**
     * Latest weigh-in read from Health Connect on this date.
     *
     * Weight lives here rather than on [WeightEntry] because that table is the
     * manual record; a synced value must not overwrite something typed by hand.
     * Readers merge the two, preferring the manual entry.
     */
    val weightKg: Float? = null,
    val syncedAt: Instant,
)

@Entity(indices = [Index("timestamp")])
data class BloodPressureReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Instant,
    val systolic: Int,
    val diastolic: Int,
)

@Entity
data class WeightEntry(@PrimaryKey val date: LocalDate, val weightKg: Float)

/** Stored in centimetres; the UI presents inches in quarter-inch steps. */
@Entity
data class WaistEntry(@PrimaryKey val date: LocalDate, val waistCm: Float)

@Entity(indices = [Index("timestamp")])
data class HydrationEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Instant,
    val milliliters: Int,
)

/** One logged set of bodyweight reps, with the time it was performed. */
@Entity(indices = [Index("timestamp")])
data class ExerciseSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Instant,
    val movement: MovementType,
    val reps: Int,
)

@Entity(indices = [Index("timestamp")])
data class CaffeineIntake(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Instant,
    val milligrams: Int,
)

@Entity(indices = [Index("timestamp")])
data class CreatineIntake(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Instant,
    val grams: Int,
)

/** An actually-performed fast. Open-ended while [endInstant] is null. */
@Entity(indices = [Index("startInstant")])
data class FastingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startInstant: Instant,
    val goalDurationMinutes: Int,
    val endInstant: Instant? = null,
    val type: FastingType,
)

/**
 * The recurring weekly plan: one row per weekday describing when eating is
 * *allowed*. Everything outside the feeding window is a planned fast.
 *
 * A window whose end is at or before its start wraps past midnight into the
 * following day (e.g. 20:00 to 02:00).
 */
@Entity
data class FastingPlanDay(
    @PrimaryKey val dayOfWeek: DayOfWeek,
    val feedingStart: LocalTime,
    val feedingEnd: LocalTime,
    /**
     * False means no eating at all that day -- the full 24 hours are a planned
     * fast and the window times are ignored.
     *
     * Mapped to the pre-existing `enabled` column so this rename needs no
     * schema migration and no loss of already-entered plans.
     */
    @ColumnInfo(name = "enabled") val hasFeedingWindow: Boolean = true,
)

/**
 * A one-off multi-day fast. Overrides [FastingPlanDay] for any time it covers,
 * so a planned 48-hour fast is not scored against that weekday's feeding window.
 */
@Entity(indices = [Index("startInstant")])
data class PlannedExtendedFast(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startInstant: Instant,
    val endInstant: Instant,
    val type: FastingType,
    val label: String? = null,
)

@Entity
data class WeeklyPerformance(
    @PrimaryKey val isoWeek: String,
    val fastestMileSeconds: Int? = null,
    val maxBenchKg: Float? = null,
    val maxHexBarDeadliftKg: Float? = null,
)

@Entity(
    indices = [
        Index("timestamp"),
        // SQLite treats NULLs as distinct, so manual readings (externalId null)
        // are unaffected while CGM samples cannot be imported twice.
        Index(value = ["externalId"], unique = true),
    ]
)
data class BloodSugarReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Instant,
    val mgDl: Int,
    val source: DataSourceEnum,
    /**
     * Health Connect's stable record id, when this reading came from a CGM.
     * Used to avoid inserting the same sample twice across syncs.
     */
    val externalId: String? = null,
)

@Entity(indices = [Index("timestamp")])
data class KetoneReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Instant,
    val mmolL: Float,
)

@Entity
data class RestingHeartRate(
    @PrimaryKey val date: LocalDate,
    val bpm: Int,
    val source: DataSourceEnum,
)

@Entity
data class UserGoals(
    @PrimaryKey val id: Int = 1,
    val goalWeightKg: Float? = null,
    val goalWaistCm: Float? = null,
    val dailyPushupGoal: Int? = null,
    val weeklyPushupGoal: Int? = null,
    val dailySquatGoal: Int? = null,
    val weeklySquatGoal: Int? = null,
    val weeklyRunMinutesGoal: Int? = null,
    val dailyStepGoal: Int? = 10_000,
    /** 100 fl oz, rounded to the nearest millilitre. */
    val dailyWaterMlGoal: Int? = 2957,
    val dailyCalorieTarget: Int? = null,
    val dailyProteinTarget: Int? = null,
    val dailyPagesGoal: Int? = null,
)

@Entity
data class UserSettings(
    @PrimaryKey val id: Int = 1,
    val unitSystem: UnitSystemEnum = UnitSystemEnum.IMPERIAL,
    val weekStartsOn: DayOfWeek = DayOfWeek.MONDAY,
    /**
     * Package name of the app whose step records are trusted, or null to sum
     * every source.
     *
     * Several apps commonly write steps to Health Connect at once -- a watch's
     * companion app and the phone's own health app both counting the same walk
     * -- and summing them double-counts. Pinning one source is the only way to
     * match what the watch itself reports.
     */
    val preferredStepsPackage: String? = null,
)
