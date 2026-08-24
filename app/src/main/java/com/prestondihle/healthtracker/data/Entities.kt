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

/**
 * A staged weight on the way to [UserGoals.goalWeightKg].
 *
 * A table rather than more columns on `UserGoals` because there is no right
 * number of them: someone with thirty pounds to lose may want one every five,
 * and someone else wants a single halfway mark. A fixed set of columns would
 * have to guess, and guessing wrong means either unused columns or a ceiling
 * nobody can raise.
 *
 * Unique on the weight itself, so adding a mark that already exists is
 * absorbed rather than drawn twice at the same height. Kilograms like every
 * other body measurement, converted at the display boundary -- a second storage
 * unit is how rounding error gets in.
 */
@Entity(indices = [Index(value = ["kg"], unique = true)])
data class WeightSubGoal(@PrimaryKey(autoGenerate = true) val id: Long = 0, val kg: Float)

/** Stored in centimetres; the UI presents inches in quarter-inch steps. */
@Entity
data class WaistEntry(@PrimaryKey val date: LocalDate, val waistCm: Float)

/**
 * One day's dynamometer readings, one column per hand.
 *
 * Stored in kilograms and presented in pounds, like weight. Health Connect has
 * no grip strength record at all, so nothing external forces the unit -- but a
 * second storage unit in the same database is how rounding error gets in, and
 * the display boundary already knows how to convert.
 *
 * Dominant and non-dominant rather than left and right: the pair is read as a
 * ratio (a dominant hand normally squeezes about a tenth harder), and that
 * comparison survives a reader who does not remember which hand this person
 * writes with. Both are nullable so one hand can be logged without the other.
 */
@Entity
data class GripStrengthEntry(
    @PrimaryKey val date: LocalDate,
    val dominantKg: Float? = null,
    val nonDominantKg: Float? = null,
)

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

/**
 * One ketone reading, in parts per million of breath acetone.
 *
 * ppm rather than mmol/L because that is what the meter in use reports. The two
 * are not the same measurement and there is no conversion between them: mmol/L
 * is beta-hydroxybutyrate assayed in blood, ppm is acetone in exhaled breath.
 * They correlate loosely and individually, so a stored number means whichever
 * one the device produced and nothing else.
 *
 * The v4-to-v5 migration therefore renames the column and leaves every value
 * exactly as it was -- the readings were always coming off a ppm meter, only the
 * label was wrong.
 */
@Entity(indices = [Index("timestamp")])
data class KetoneReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Instant,
    val ppm: Float,
)

/**
 * One eaten meal with the time it was eaten.
 *
 * The daily macro totals on [HealthDaySnapshot] cannot answer *when*, and the
 * master graph needs exactly that: an absorption curve has to start somewhere.
 * So nutrition is cached twice over -- rolled up on the snapshot for the day's
 * figures, and kept per-meal here for the timeline. Same cache rules as glucose:
 * safe to delete and re-sync, with the unique index on [externalId] making a
 * repeated sync idempotent while leaving hand-entered rows alone.
 */
@Entity(
    indices = [
        Index("timestamp"),
        Index(value = ["externalId"], unique = true),
    ]
)
data class MealEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Instant,
    val calories: Int? = null,
    val proteinGrams: Float? = null,
    val carbGrams: Float? = null,
    val fatGrams: Float? = null,
    /** Whatever the writing app called it, when it named the meal at all. */
    val name: String? = null,
    val source: DataSourceEnum,
    /** Health Connect's record id, for de-duplicating across syncs. */
    val externalId: String? = null,
    /**
     * Deleted by hand, and kept only so it stays deleted.
     *
     * A synced meal cannot simply be removed: the next sync reads the same
     * record from Health Connect and puts it straight back, because both the
     * `externalId` index and the content check look for rows that are no longer
     * there. Keeping the row and hiding it is what makes the deletion stick --
     * the row is precisely the evidence that this record was already dealt with.
     *
     * Only synced meals need it. A hand-entered meal has no upstream record to
     * come back from, so it is deleted outright.
     */
    val hidden: Boolean = false,
)

/**
 * Heart rate averaged over a fixed bucket of wall-clock time.
 *
 * A watch writes a beat rate every few seconds, which is far more resolution
 * than a chart spanning a day can draw and enough rows per day to make the table
 * the largest thing in the database. Samples are therefore averaged into
 * [BUCKET_MINUTES] windows on the way in.
 *
 * The bucket's start time *is* the primary key, which is what makes a re-sync
 * idempotent without an external id: the same wall-clock window always lands on
 * the same row and simply overwrites it.
 */
@Entity
data class HeartRateBucket(
    @PrimaryKey val bucketStartMillis: Long,
    val bpm: Int,
    /** How many raw samples the average came from, so a thin bucket can be spotted. */
    val sampleCount: Int,
) {
    val timestamp: Instant
        get() = Instant.ofEpochMilli(bucketStartMillis)

    companion object {
        /**
         * Five minutes is fine enough to show a heart rate responding to a meal
         * and coarse enough that a day is under 300 rows.
         */
        const val BUCKET_MINUTES = 5L
    }
}

/**
 * Steps taken inside one wall-clock hour.
 *
 * The daily total on [HealthDaySnapshot] cannot say *when* the walking happened,
 * and "when" is the only question the master graph asks. Same cache rules as
 * [HeartRateBucket] and the same reason for keying on the bucket's start rather
 * than an external id: the same hour always lands on the same row, so a re-sync
 * that overlaps a window rewrites it instead of laying a second, offset copy of
 * the same day beside it.
 *
 * An hour is the resolution a step count is actually meaningful at. Five-minute
 * step buckets are mostly zeroes with occasional spikes, which draws as noise
 * rather than as activity.
 */
@Entity
data class StepBucket(@PrimaryKey val hourStartMillis: Long, val steps: Int) {
    val timestamp: Instant
        get() = Instant.ofEpochMilli(hourStartMillis)

    companion object {
        const val BUCKET_MINUTES = 60L
    }
}

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
    /**
     * The blood sugar band shaded on the glucose charts, in mg/dL.
     *
     * Two columns rather than one range so either edge can be cleared on its
     * own; the band is drawn only when both are set and the low is below the
     * high. The seeded 70-140 is the non-diabetic fasting-to-postprandial span
     * most continuous monitors quote, and is a starting point rather than
     * clinical advice -- which is why it is editable at all.
     */
    val glucoseTargetLowMgDl: Int? = 70,
    val glucoseTargetHighMgDl: Int? = 140,

    /**
     * A single value marked with a solid rule across the glucose chart.
     *
     * Separate from the band and drawn differently on purpose: a band answers
     * "was it in range", which is a region; a line answers "was it above or
     * below", which is a threshold. Solid rather than the dashed rule the blood
     * pressure chart uses -- that one marks a published clinical figure, this
     * one is wherever the reader has decided to draw it, and the two should not
     * look alike.
     *
     * 100 mg/dL to start: near the middle of the seeded target, so it reads as
     * a centre line rather than a limit.
     */
    val glucoseReferenceMgDl: Int? = 100,

    /**
     * Floor and ceiling of the glucose plot, in mg/dL.
     *
     * A floor and ceiling rather than hard limits -- both charts still widen to
     * fit an outlier, so narrowing these can never clip a reading off the top.
     * What it does change is how much of the plot the ordinary range gets: a
     * trace that lives between 80 and 120 is a flat line on a 60-180 axis and a
     * legible swing on a 70-130 one, and which of those is right depends on
     * whose blood sugar it is. Seeded with the 60-180 both charts were fixed at.
     */
    val glucosePlotMinMgDl: Int? = 60,
    val glucosePlotMaxMgDl: Int? = 180,

    /**
     * Rules across the blood pressure chart, in mmHg.
     *
     * Two, because a blood pressure reading is two numbers and a single rule can
     * only ever be read against one of them -- the chart drew 120 alone, which
     * left the diastolic line with nothing to be read against at all.
     *
     * Seeded at 120/80 and drawn dashed, unlike the glucose reference: these
     * start as the published clinical figure and are adjustable because a
     * clinician may have named different ones, not because they are the
     * reader's own invention.
     */
    val bloodPressureSystolicReference: Int? = 120,
    val bloodPressureDiastolicReference: Int? = 80,

    /**
     * A nightly sleep target, in minutes.
     *
     * Stored in minutes because the snapshot is, and converted for display, so
     * that a target of seven and a half hours survives the round trip. 480 to
     * start -- eight hours is the figure most people are measuring themselves
     * against whether or not it is the right one for them.
     */
    val sleepMinutesGoal: Int? = 480,
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
    /**
     * Whether the blood sugar line is drawn smoothed.
     *
     * Off by default. Every other line on these charts is either a measurement
     * or is dashed to say it is a model, and a solid line that quietly differs
     * from the readings under it would break that rule without announcing it.
     * Turning it on is a deliberate act, and the chart says so while it is on.
     */
    val smoothGlucose: Boolean = false,
)
