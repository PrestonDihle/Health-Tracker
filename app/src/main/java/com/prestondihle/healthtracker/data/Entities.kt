package com.prestondihle.healthtracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.prestondihle.healthtracker.domain.SleepStage
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

enum class FastingType { OMAD, EXTENDED_24, EXTENDED_36, EXTENDED_48, CUSTOM }

enum class DataSourceEnum { MANUAL, HEALTH_CONNECT }

enum class UnitSystemEnum { IMPERIAL, METRIC }

/**
 * Which colour scheme the app draws in.
 *
 * **Three states rather than a boolean, and [SYSTEM] is the point of it.** The
 * app followed the phone with no override at all, on the argument that a per-app
 * switch is a state to get out of step with the phone -- and that argument is
 * only answered by keeping "follow the phone" reachable. A two-state toggle
 * cannot express it: the first tap makes the phone's own setting unreachable for
 * ever, and every later question of "why is this app light" has no way back.
 *
 * The override exists because the charts are the reason. Every series here has
 * two hand-picked values, one per scheme, and the separations they were chosen
 * for differ between them -- so reading a plot in the scheme it looks best in is
 * a real reason to differ from the phone for a few minutes, which is not true of
 * a text app.
 *
 * The home-screen widget is deliberately not covered: it draws through
 * `GlanceTheme` on the launcher's surface, and a widget that disagreed with
 * every other widget beside it would be reading this setting somewhere it does
 * not apply.
 */
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

/** Biological sex, for the metrics that read differently by it. UNSPECIFIED is the unset state. */
enum class Sex { MALE, FEMALE, UNSPECIFIED }

/**
 * Which Army Fitness Test standard a Soldier is held to.
 *
 * Stored rather than derived because nothing else in this app knows the reader's
 * MOS. The two lanes share every event table and differ only in the total
 * required and, for a woman, which column she reads -- see
 * `domain/AftScoring.kt`.
 */
enum class AftLane(val label: String, val minimumTotal: Int) {
    /** Performance-normed by sex and age. Everyone not in a combat specialty. */
    GENERAL("General", minimumTotal = 300),

    /**
     * Sex-neutral, still age-normed.
     *
     * ATP 7-22.01 lists the areas of concentration and MOSs it applies to: 11A,
     * 11B, 11C, 11Z, 12A, 12B, 13A, 13F, 18A, 18B, 18C, 18D, 18E, 18F, 18Z, 19A,
     * 19C, 19D, 19K and 19Z.
     */
    COMBAT("Combat", minimumTotal = 350),
}

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
 * exists so trends and history survive offline and so the screens do not
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
     * [totalCalories], which is energy burned. Kept apart because the day's
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
    /**
     * Mean blood oxygen saturation across the day, as a percentage.
     *
     * A daily average of what is really an overnight measurement: the watch
     * samples it while asleep and rarely otherwise, so this is "last night's
     * SpO2" wearing a date. Averaged rather than stored per sample because
     * nothing here asks *when* — the question is whether the nights are
     * drifting, which one figure a day answers.
     *
     * Nullable and expected to stay null for anyone whose watch does not report
     * it, which is most of them.
     */
    val spo2Percent: Float? = null,
    /**
     * The finer nutrition figures, where the logging app recorded them.
     *
     * **Fiber and sugar are parts of [carbGrams], and saturated fat is part of
     * [fatGrams]** -- they are not a fourth and fifth macro and must never be
     * added to the other three or stacked beside them on a chart, which would
     * count the same grams twice. They are here to be read against the totals,
     * not summed with them.
     *
     * Sodium is stored in milligrams because that is the unit every label and
     * every guideline uses; grams would put every real figure between 0.002 and
     * 0.004 and make the column unreadable in a CSV export.
     */
    val fiberGrams: Float? = null,
    val sugarGrams: Float? = null,
    val saturatedFatGrams: Float? = null,
    val sodiumMg: Float? = null,
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

/** When in the day a supplement is meant to be taken. */
enum class SupplementSlot(val label: String) {
    MORNING("Morning"),
    MIDDAY("Midday"),
    EVENING("Evening"),
}

/**
 * One entry in the daily stack: what it is, how much, and when.
 *
 * **The dose is free text rather than a number and a unit.** Supplement labels
 * do not agree on one: IU, mcg, mg, grams, capsules, softgels, drops and
 * millilitres all turn up on the same shelf, and half of them are printed per
 * serving rather than per pill. Nothing here does arithmetic on the dose -- it
 * is quoted back exactly as it was typed -- so parsing it could only ever be a
 * way of rejecting something somebody actually takes.
 *
 * Unique on name and slot together. The same thing morning and evening is two
 * rows, which is what makes it tickable twice a day; the same thing twice in one
 * morning is one row, because that is one dose split across two capsules and the
 * label already says so.
 */
@Entity(indices = [Index(value = ["name", "slot"], unique = true)])
data class Supplement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val dose: String,
    val slot: SupplementSlot,
)

/**
 * One supplement taken on one day.
 *
 * The row is the whole fact: present means taken, absent means not. There is
 * deliberately no boolean column, because "not taken" and "not answered yet" are
 * the same state for something that resets at midnight, and a column would force
 * a distinction the data cannot support -- leaving every past day looking
 * actively missed rather than simply over.
 *
 * Keyed on the pair, so ticking the same day twice is absorbed rather than
 * counted twice. Indexed on the date because that is how a day is read; the
 * primary key indexes the pair, which does not answer "what was taken today".
 */
@Entity(primaryKeys = ["supplementId", "date"], indices = [Index("date")])
data class SupplementDose(
    val supplementId: Long,
    val date: LocalDate,
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

/**
 * One Army Fitness Test, as far as it got.
 *
 * **Every event is nullable**, which is the point of the row rather than a
 * convenience: a test day is five events over two hours and a Soldier may log
 * them as they finish, or stop after three. A missing event is not a zero -- a
 * zero is a performance -- so the scorecard reports what it has and declines to
 * pass or fail an attempt that is not finished.
 *
 * Keyed on a generated id rather than on [date], because a retest is a second
 * attempt and not a correction of the first. Dating it would silently overwrite
 * whichever came earlier, which is the one thing a record of progress must not
 * do.
 *
 * The deadlift is stored in kilograms like every other weight here, though the
 * event and its scoring table are both in pounds. Two storage units is how
 * rounding error gets into a number that has to match a printed scorecard, so
 * the conversion happens once at the display and scoring boundary through
 * [com.prestondihle.healthtracker.domain.Units]. The timed events store whole
 * seconds, which is the resolution the tables are published at.
 */
@Entity(indices = [Index("date")])
data class AftAttempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val deadliftKg: Float? = null,
    val hrpReps: Int? = null,
    val sdcSeconds: Int? = null,
    val plankSeconds: Int? = null,
    val twoMileSeconds: Int? = null,
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
    /**
     * The finer nutrition figures for this one meal.
     *
     * Per-meal as well as per-day, which is not redundancy: a daily fiber total
     * cannot say *which* meal carried it, and the question this exists for is
     * whether the meals with fiber in them are the ones with the flatter glucose
     * response. That comparison needs both numbers on the same row.
     *
     * Parts of [carbGrams] and [fatGrams] rather than additions to them -- see
     * the note on `HealthDaySnapshot`.
     */
    val fiberGrams: Float? = null,
    val sugarGrams: Float? = null,
    val saturatedFatGrams: Float? = null,
    val sodiumMg: Float? = null,
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
 * Stored at fifteen minutes -- the finest the master graph ever draws. Wider
 * bars (thirty minutes, an hour) are summed up from these at display time as the
 * window widens, so one cached resolution feeds every zoom. The column keeps its
 * original name to stay migration-free; the property does not, because a
 * fifteen-minute bucket keyed by `hourStartMillis` reads as a bug.
 */
/**
 * A saved slot for one card within one tab, so a chosen order survives a restart.
 *
 * Keyed by (tab, cardId): each card has at most one stored position, and cards a
 * stored order never mentions -- one added in a later version -- fall back to
 * their built-in place after the ones it does.
 */
@Entity(primaryKeys = ["tab", "cardId"])
data class CardOrderEntry(
    val tab: String,
    val cardId: String,
    val position: Int,
    /**
     * Whether the card is folded to its title row.
     *
     * On the same row as the position rather than in a table of its own, because
     * the two are one fact about one card on one tab and would otherwise need
     * keeping in step across two writes. It does mean **both must be written
     * together**: the repository rewrites whole rows, so a reorder that carried
     * only positions would silently unfold every card on the tab.
     */
    val collapsed: Boolean = false,
)

@Entity
data class StepBucket(
    @PrimaryKey @ColumnInfo(name = "hourStartMillis") val bucketStartMillis: Long,
    val steps: Int,
) {
    val timestamp: Instant
        get() = Instant.ofEpochMilli(bucketStartMillis)

    companion object {
        /** The stored (finest) bucket. Display buckets are multiples of this. */
        const val BUCKET_MINUTES = 15L
    }
}

/**
 * One night, cached from Health Connect.
 *
 * Kept even though [HealthDaySnapshot.sleepMinutes] already holds a duration,
 * and for the same reason [StepBucket] is kept beside the daily step total: a
 * daily figure cannot say *when*. The snapshot's minutes are what the trend
 * chart and the sleep goal are read against; these are what the hypnogram is
 * drawn from.
 *
 * Keyed on the start rather than on [externalId] so a re-sync overwrites the
 * night it already holds. The id is carried anyway, because a source that
 * re-scores a night -- which is what a sleep tracker does the following morning
 * -- keeps the id and moves the bounds, and that is worth being able to see.
 *
 * There is no `hidden` column here, unlike [MealEntry]. A meal is deletable
 * because a source can record one that was never eaten and nothing else can
 * correct it; a night is not something the reader is in a position to say did
 * not happen.
 */
@Entity(indices = [Index("startMillis")])
data class SleepSessionEntry(
    @PrimaryKey val startMillis: Long,
    val endMillis: Long,
    val externalId: String? = null,
) {
    val start: Instant
        get() = Instant.ofEpochMilli(startMillis)

    val end: Instant
        get() = Instant.ofEpochMilli(endMillis)
}

/**
 * One stretch of one stage within a night.
 *
 * The primary key is the pair of session and start, not the start alone: two
 * sessions may legitimately overlap where a watch and a phone both recorded the
 * same night, and keying on the start alone would silently let one overwrite
 * stretches of the other.
 *
 * [sessionStartMillis] points at [SleepSessionEntry.startMillis] rather than
 * being a foreign key, because there are no foreign keys anywhere in this schema
 * -- which is why the repository deletes a night's stages itself.
 */
@Entity(
    primaryKeys = ["sessionStartMillis", "startMillis"],
    indices = [Index("startMillis"), Index("sessionStartMillis")],
)
data class SleepStageEntry(
    val sessionStartMillis: Long,
    val startMillis: Long,
    val endMillis: Long,
    val stage: SleepStage,
) {
    val start: Instant
        get() = Instant.ofEpochMilli(startMillis)

    val end: Instant
        get() = Instant.ofEpochMilli(endMillis)
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
     * Floor and ceiling of the heart-rate axis on the master graph, in bpm.
     *
     * [glucosePlotMinMgDl]'s counterpart, and seeded with the **exact figures
     * that axis was hard-coded at** (40 and 180) for `MIGRATION_8_9`'s reason:
     * turning a constant into a setting must not change what an existing
     * reader's chart looks like.
     */
    val heartRatePlotMinBpm: Int? = 40,
    val heartRatePlotMaxBpm: Int? = 180,

    /**
     * A single value marked with a solid rule across the heart-rate axis, or
     * null to draw nothing.
     *
     * Solid like [glucoseReferenceMgDl] and for the same reason -- the reader
     * decided where it goes, where the blood pressure rules are published
     * figures and are dashed to say so.
     *
     * **Null rather than seeded, which is the opposite of the glucose
     * reference**, and the question `MIGRATION_11_12` settles decides it: ask
     * what a NULL does on screen. The glucose rule was already being drawn at
     * 100 before it was a setting, so a NULL there would have removed a line.
     * Nothing is drawn on the heart-rate axis today, so NULL is the true
     * statement about every existing reader, and a seeded value would put a rule
     * on their chart that they never asked for.
     *
     * There is deliberately **no target band to go with it** -- see
     * [com.prestondihle.healthtracker.domain.HeartRate].
     */
    val heartRateReferenceBpm: Int? = null,

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

    /**
     * Caffeine still in the body at bedtime that the reader is willing to accept,
     * in milligrams, or null to say nothing about it.
     *
     * Null rather than a seeded figure, unlike almost every other goal here.
     * The others describe something already being drawn, so a blank one would
     * change a chart; this one drives a notification, and a default would mean
     * an upgrading user is interrupted by something they never asked for. It
     * starts silent and is switched on deliberately.
     */
    val caffeineBedtimeLimitMg: Int? = null,
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
    /**
     * Personal profile, for the metrics computed against the body rather than a
     * goal: max heart rate drives the run-intensity zones, and age, sex and
     * height are here for the figures that will read off them.
     *
     * Max heart rate is entered rather than derived: 220 minus age is only a
     * population average, and anyone who has seen their own on a hard effort
     * knows it better than the formula does. All nullable/UNSPECIFIED until set,
     * so an upgrading user is never shown a made-up profile.
     */
    val maxHeartRateBpm: Int? = null,
    val ageYears: Int? = null,
    val sex: Sex = Sex.UNSPECIFIED,
    val heightCm: Float? = null,
    /**
     * Which Army Fitness Test standard to score against.
     *
     * Defaults to the general standard, which is the one most Soldiers are held
     * to and the safer of the two to guess wrong: it scores a combat-MOS Soldier
     * a little generously on the total, where defaulting the other way would
     * tell everyone else they had failed a test they passed. Not nullable,
     * because there is no such thing as being on neither standard.
     */
    val aftLane: AftLane = AftLane.GENERAL,
    /**
     * The three times offered as one-tap chips when a stamped meal is given a
     * real one.
     *
     * A nutrition source that records only the date stamps every meal at one
     * fixed time of day, and the correction is nearly always the same shape:
     * this was breakfast, this was lunch, this was dinner. Naming those three
     * turns the fix into a single tap, where the clock face costs a drag and a
     * confirm to say something the reader knew before they opened it.
     *
     * Editable because they are the reader's own meal times rather than a
     * universal 0630/1200/1830. A preset that is never the right answer is
     * worse than no preset: it still has to be read before it can be rejected.
     *
     * `NOT NULL` with seeded defaults, the shape `sex` and `aftLane` use. No
     * state here means "no preset" -- the chips are the whole feature, and a
     * null would draw one with no time on it.
     */
    val mealPresetBreakfast: LocalTime = LocalTime.of(6, 30),
    val mealPresetLunch: LocalTime = LocalTime.of(12, 0),
    val mealPresetDinner: LocalTime = LocalTime.of(18, 30),
    /**
     * Light, dark, or whatever the phone is set to.
     *
     * `NOT NULL` with a seeded `'SYSTEM'`, the shape [sex], [aftLane] and the
     * meal presets use, and for the [mealPresetBreakfast] reason rather than the
     * caffeine-limit one: this drives something drawn on screen from the first
     * frame, so a NULL would have to be read as *something* anyway and the only
     * honest reading is the behaviour that shipped before the column existed.
     * Seeded that way, an upgrading reader sees precisely nothing change.
     */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

/**
 * The three meal presets as a chip row: through the day, and each time once.
 *
 * An extension rather than a property on the entity, so Room has no column to
 * ask about. Sorted because the three are edited in separate fields and nothing
 * stops a night-shift reader putting "breakfast" at 22:00 -- a row running
 * backwards would be read as a mistake in the app rather than in the settings.
 * Distinct because two chips at the same time are one chip drawn twice.
 */
val UserSettings.mealPresets: List<LocalTime>
    get() = listOf(mealPresetBreakfast, mealPresetLunch, mealPresetDinner).distinct().sorted()
