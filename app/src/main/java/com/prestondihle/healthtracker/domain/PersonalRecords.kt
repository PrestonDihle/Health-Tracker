package com.prestondihle.healthtracker.domain

import com.prestondihle.healthtracker.data.AftAttempt
import com.prestondihle.healthtracker.data.FastingSession
import com.prestondihle.healthtracker.data.GripStrengthEntry
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One personal best, and the day it was set. */
data class Record<T>(val value: T, val date: LocalDate)

/**
 * The best of each thing, ever.
 *
 * Every figure is a real performance on a real day, which is the whole rule this
 * file is written around. Nothing here is projected, averaged, smoothed or
 * scaled: the two-mile is one that was actually run in a recorded test rather
 * than [RunPace]'s projection from ordinary runs, because a card headed
 * *Personal records* is the one place a model would be read as an achievement.
 * That projection has its own home on the AFT card, where it says twice that it
 * is a projection.
 *
 * Each record carries the date it was set. A best with no date is a claim with
 * nothing behind it, and on a card meant to be motivating the difference between
 * a grip figure from last week and one from two years ago is most of the
 * information.
 *
 * Nulls throughout, and they are all the same statement: nothing has been logged
 * that could set this record. A zero would read as a performance.
 */
data class PersonalRecords(
    /** Best single-hand grip, in kilograms like every other weight here. */
    val gripDominant: Record<Float>? = null,
    val gripNonDominant: Record<Float>? = null,
    /** Quickest recorded two-mile, in seconds -- lower is better. */
    val twoMile: Record<Int>? = null,
    /** Heaviest AFT deadlift, in kilograms. The event is a three-rep max. */
    val deadlift: Record<Float>? = null,
    /** Longest fast that actually finished. */
    val longestFast: Record<Duration>? = null,
    /** Best day's time in range, 0f..1f, over days the sensor properly covered. */
    val timeInRange: Record<Float>? = null,
) {
    /** True while nothing at all has been recorded, so the card can say so once. */
    val isEmpty: Boolean
        get() =
            gripDominant == null &&
                gripNonDominant == null &&
                twoMile == null &&
                deadlift == null &&
                longestFast == null &&
                timeInRange == null
}

object PersonalBests {

    /**
     * The heaviest grip logged for one hand.
     *
     * Per hand rather than best-of-both, because the columns are nullable
     * precisely so one hand can be logged without blanking the other -- and a
     * combined figure would quietly report the dominant hand's number under a
     * label covering both.
     */
    private fun bestGrip(
        entries: List<GripStrengthEntry>,
        of: (GripStrengthEntry) -> Float?,
    ): Record<Float>? =
        entries
            .mapNotNull { entry -> of(entry)?.let { Record(it, entry.date) } }
            .maxByOrNull { it.value }

    /**
     * Every record, computed from rows already on disk.
     *
     * Nothing is stored. These are a scan of a few hundred rows and a stored best
     * is a claim that has to be invalidated every time a row is edited or
     * deleted -- which on this data happens routinely, since meals, hydration and
     * fasts are all correctable. It is the argument [AftScoring] makes about
     * never storing a score, arriving at a different table.
     */
    fun from(
        grips: List<GripStrengthEntry> = emptyList(),
        aftAttempts: List<AftAttempt> = emptyList(),
        fasts: List<FastingSession> = emptyList(),
        /** One entry per day the sensor covered properly, as a 0f..1f share. */
        timeInRangeByDay: Map<LocalDate, Float> = emptyMap(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): PersonalRecords {
        // Only finished fasts, for the reason `FastingStats` gives: one still
        // running reports its length so far and would beat itself an hour later,
        // so the record would climb all day and reset when the fast ended.
        val longest =
            fasts
                .filter { it.endInstant != null }
                .maxByOrNull { Duration.between(it.startInstant, it.endInstant).seconds }

        return PersonalRecords(
            gripDominant = bestGrip(grips) { it.dominantKg },
            gripNonDominant = bestGrip(grips) { it.nonDominantKg },
            // Quickest, so this is the one record that takes a minimum.
            twoMile =
                aftAttempts
                    .mapNotNull { attempt -> attempt.twoMileSeconds?.let { Record(it, attempt.date) } }
                    .minByOrNull { it.value },
            deadlift =
                aftAttempts
                    .mapNotNull { attempt -> attempt.deadliftKg?.let { Record(it, attempt.date) } }
                    .maxByOrNull { it.value },
            longestFast =
                longest?.let {
                    Record(
                        Duration.between(it.startInstant, it.endInstant),
                        endedOn(it.endInstant!!, zoneId),
                    )
                },
            timeInRange =
                timeInRangeByDay.entries.maxByOrNull { it.value }?.let { Record(it.value, it.key) },
        )
    }

    /**
     * A fast is dated by the day it *ended*, which is the day it was achieved.
     *
     * A 48-hour fast started on Friday is a Sunday achievement, and dating it
     * Friday would put the record before two of the days that earned it.
     */
    private fun endedOn(end: Instant, zoneId: ZoneId): LocalDate =
        end.atZone(zoneId).toLocalDate()
}
