package com.prestondihle.healthtracker.domain

import java.time.LocalDate

/**
 * How the morning compares with an ordinary one — as two facts, never a score.
 *
 * The composite "readiness score" every wearable ships is deliberately not built
 * here. Those numbers combine things measured in different units, on different
 * confidences, with weights nobody publishes, and the result cannot be argued
 * with: told "readiness 61" there is nothing to check and nothing to do. Told
 * "resting HR 6 bpm over baseline, 5h 40m sleep" the reader knows which of the
 * two moved, by how much, and whether they agree with it.
 *
 * The two facts are independently absent. A night with no sleep recorded still
 * reports the heart rate, and a phone with thirty days of sleep but no resting
 * heart rate still reports the sleep — one missing half must not blank the other,
 * which a single score could not manage.
 */
data class Readiness(
    /** Today's resting heart rate, as the source recorded it. */
    val restingBpm: Int?,
    /** The trailing median it is being compared against. */
    val baselineBpm: Int?,
    val sleepMinutes: Int?,
    val sleepGoalMinutes: Int?,
) {
    /**
     * Beats above the baseline, or null unless both halves are known.
     *
     * Positive is worse, which is the opposite of most figures on these screens
     * and is why the UI spells out "over" and "under" rather than printing a
     * signed number on its own.
     */
    val restingDeltaBpm: Int?
        get() {
            val today = restingBpm ?: return null
            val baseline = baselineBpm ?: return null
            return today - baseline
        }

    /** Minutes short of the goal, or null unless both halves are known. */
    val sleepDeficitMinutes: Int?
        get() {
            val slept = sleepMinutes ?: return null
            val goal = sleepGoalMinutes ?: return null
            return goal - slept
        }

    /**
     * Whether there is anything at all to say this morning.
     *
     * Keyed on the raw readings, not on the deltas. A resting heart rate with no
     * baseline yet is still a measurement and the card still prints it -- asking
     * about [restingDeltaBpm] here would have the card claim nothing was recorded
     * on exactly the mornings where something was, for the first ten days of use.
     */
    val hasAnything: Boolean
        get() = restingBpm != null || sleepMinutes != null
}

/** Builds [Readiness] out of the cached daily snapshots. */
object ReadinessFacts {

    /**
     * How far back the resting-heart-rate baseline looks.
     *
     * Thirty days is long enough that one hard week does not become the normal
     * it is measured against, and short enough to follow a genuine change in
     * fitness rather than averaging over a season.
     */
    const val BASELINE_DAYS = 30

    /**
     * The fewest days in that window that will produce a baseline.
     *
     * A median of two mornings is not a baseline, it is two mornings — and the
     * comparison it produces would be the most confident-looking thing on the
     * card while resting on the least. Ten is the point where a stray night
     * stops moving the middle value.
     */
    const val MIN_BASELINE_DAYS = 10

    /**
     * Today's two facts against the trailing window.
     *
     * [restingByDay] and [sleepByDay] are read straight off the cached snapshots
     * — this computes nothing that would need a sync, which is the whole point:
     * the line is there the moment the screen opens, from data already on disk.
     *
     * **The baseline excludes today.** Comparing a morning against a median it is
     * itself inside pulls the baseline toward it, so a genuinely high morning
     * reads as less high than it is — and on a short window the effect is big
     * enough to hide the thing the line exists to show.
     */
    fun on(
        today: LocalDate,
        restingByDay: Map<LocalDate, Int>,
        sleepByDay: Map<LocalDate, Int>,
        sleepGoalMinutes: Int?,
    ): Readiness {
        val windowStart = today.minusDays(BASELINE_DAYS.toLong())
        val priorReadings =
            restingByDay
                .filterKeys { it.isBefore(today) && !it.isBefore(windowStart) }
                .values
                .sorted()

        return Readiness(
            restingBpm = restingByDay[today],
            baselineBpm =
                if (priorReadings.size >= MIN_BASELINE_DAYS) medianOf(priorReadings) else null,
            // Last night's sleep is recorded against today's date, the same way
            // the sleep goal and the Wellness chart already treat it.
            sleepMinutes = sleepByDay[today],
            sleepGoalMinutes = sleepGoalMinutes,
        )
    }

    /**
     * Middle value, rounding the two-middle case down.
     *
     * A median rather than a mean because resting heart rate has occasional
     * outliers with nothing to do with fitness -- an illness, a late meal, a
     * night the watch sat badly -- and a mean carries them into the baseline for
     * a month afterwards.
     */
    private fun medianOf(sorted: List<Int>): Int {
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
