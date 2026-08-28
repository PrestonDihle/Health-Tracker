package com.prestondihle.healthtracker.domain

import java.time.Duration
import java.time.Instant

/**
 * The kinds of training this app groups sessions into.
 *
 * Deliberately fewer categories than Health Connect has exercise types. The
 * watch can name eighty-odd activities and this reader does a handful of them;
 * a card with twenty rows of one session each is a list of records, not a
 * picture of a week. Anything unmapped lands in [OTHER] rather than being
 * dropped, because a session that happened and is not shown is worse than one
 * shown under a vague name.
 *
 * [onFoot] decides whether a pace is worth printing. Minutes per mile is how
 * walking, running and rucking are actually compared; quoting it for cycling
 * would invite reading a 4:00 "pace" as a run, and for swimming the distance is
 * in a pool and the unit is wrong twice over. Those types still show distance --
 * it is only the derived figure that is withheld.
 */
enum class TrainingType(val label: String, val onFoot: Boolean) {
    RUN("Runs", onFoot = true),
    /**
     * Hiking, which on this watch is what a ruck is logged as.
     *
     * There is no rucking exercise type in Health Connect and no way to know
     * from the record whether weight was carried, so the app cannot tell a ruck
     * from a hill walk and does not pretend to. Naming it "Rucks" is the
     * author's own reading of their own data rather than something the source
     * said.
     */
    RUCK("Rucks", onFoot = true),
    WALK("Walks", onFoot = true),
    STRENGTH("Strength", onFoot = false),
    CYCLE("Cycling", onFoot = false),
    SWIM("Swimming", onFoot = false),
    HIIT("HIIT", onFoot = false),
    OTHER("Other", onFoot = false),
}

/** One recorded session, already mapped out of whatever the source called it. */
data class TrainingSession(
    val type: TrainingType,
    val start: Instant,
    val end: Instant,
    /** Null where the source recorded no distance, which is normal for strength. */
    val distanceMeters: Double?,
)

/**
 * A week's worth of one kind of training.
 *
 * [totalMeters] is null rather than zero when nothing in the group recorded a
 * distance -- ground rule 6, and it matters here because a strength session
 * genuinely has no distance while a walk with a dropped GPS lock has an unknown
 * one. Rendering either as "0.0 mi" would be a measurement nobody made.
 */
data class TrainingVolume(
    val type: TrainingType,
    val sessions: Int,
    val totalMinutes: Int,
    val totalMeters: Double?,
) {
    /**
     * Average pace across the group, in seconds per mile, or null.
     *
     * An average over the group's whole time and whole distance, not a mean of
     * per-session paces -- a twenty-minute stroll would otherwise weigh as much
     * as a two-hour ruck in the figure that is meant to describe the ruck.
     *
     * Withheld entirely off foot, and withheld when the distance is unknown or
     * zero: dividing by a distance nobody recorded produces a confident number
     * about nothing.
     */
    val paceSecondsPerMile: Int?
        get() {
            if (!type.onFoot) return null
            val metres = totalMeters ?: return null
            if (metres <= 0.0 || totalMinutes <= 0) return null
            return (totalMinutes * 60.0 * RunPace.METRES_PER_MILE / metres).toInt()
        }
}

/** Groups sessions into per-type volumes. */
object TrainingVolumes {

    /**
     * One row per kind of training present, longest first.
     *
     * Sorted by time rather than by the enum's own order, because the question
     * the card answers is "where did this week go" and the answer should be at
     * the top. A fixed order would put an incidental twenty-minute walk above an
     * hour of strength work for no reason a reader could see.
     *
     * Types with no sessions are absent rather than listed at zero. A week with
     * no swimming in it is not a week that swam zero miles, and eight rows of
     * nothing would bury the three that happened.
     */
    fun over(sessions: List<TrainingSession>): List<TrainingVolume> =
        sessions
            .groupBy { it.type }
            .map { (type, group) ->
                TrainingVolume(
                    type = type,
                    sessions = group.size,
                    totalMinutes =
                        group.sumOf { Duration.between(it.start, it.end).toMinutes() }.toInt(),
                    // Summed only over the sessions that recorded one, and null
                    // when that is none of them -- so a group is never credited
                    // with a distance because one of its five sessions had a GPS.
                    totalMeters =
                        group.mapNotNull { it.distanceMeters }.takeIf { it.isNotEmpty() }?.sum(),
                )
            }
            .sortedWith(compareByDescending<TrainingVolume> { it.totalMinutes }.thenBy { it.type })
}
