package com.prestondihle.healthtracker.domain

import com.prestondihle.healthtracker.data.Sex

/**
 * The five events of the Army Fitness Test, in the order they are performed.
 *
 * [higherIsBetter] is what decides which way a threshold is read, and the events
 * genuinely split both ways: more weight and more repetitions are better, a
 * faster sprint-drag-carry and a faster run are better, and a *longer* plank is
 * better even though it is timed. Reading the direction off the unit would get
 * the plank backwards.
 */
enum class AftEvent(
    val abbreviation: String,
    val label: String,
    val higherIsBetter: Boolean,
    val isTimed: Boolean,
) {
    DEADLIFT("MDL", "3-rep max deadlift", higherIsBetter = true, isTimed = false),
    PUSH_UP("HRP", "Hand-release push-ups", higherIsBetter = true, isTimed = false),
    SPRINT_DRAG_CARRY("SDC", "Sprint-drag-carry", higherIsBetter = false, isTimed = true),
    PLANK("PLK", "Plank", higherIsBetter = true, isTimed = true),
    TWO_MILE_RUN("2MR", "Two-mile run", higherIsBetter = false, isTimed = true),
}

/**
 * Which standard a Soldier is held to.
 *
 * The two lanes share every event table and differ only in the total required,
 * plus who reads which column: the combat standard is sex-neutral, and the
 * column it is neutral to is the male one. See [AftTables].
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

/**
 * Scores raw AFT performances against the published conversion tables.
 *
 * A step lookup, never an interpolation. The tables list a minimum performance
 * for each reachable point value and say nothing about what falls between two
 * steps, so a lift of 335 lb earns whatever 330 earns -- interpolating would
 * invent a score of 99 that the Army does not award and that no scorecard could
 * be checked against.
 *
 * Raw values are in the tables' own units: pounds for the deadlift, repetitions
 * for the push-up, whole seconds for the three timed events. The deadlift is
 * stored in kilograms like every other weight here and converted at this
 * boundary rather than inside, so the stored figure stays metric and the scored
 * one stays the number actually on the bar.
 */
object AftScoring {

    /** Every event must reach this, in both lanes, whatever the total. */
    const val MINIMUM_EVENT_SCORE = 60

    /** Five events at 100 apiece. */
    const val MAX_TOTAL = 500

    /**
     * Points for one performance, or null when the profile cannot place it.
     *
     * Null rather than zero, and the distinction is load-bearing: zero is a
     * score somebody earned by trying and failing, and a profile with no sex set
     * has not been tested at all. On the general standard there is no column to
     * read without one. The combat standard has no such gap, since it is
     * sex-neutral -- which means an unset profile scores fine there and only the
     * general lane has to ask.
     */
    fun score(
        event: AftEvent,
        raw: Int,
        ageYears: Int?,
        sex: Sex,
        lane: AftLane = AftLane.GENERAL,
    ): Int? {
        val series = seriesFor(event, ageYears ?: return null, sex, lane) ?: return null
        return pointsIn(series, raw, event.higherIsBetter)
    }

    /**
     * The highest point value whose requirement this performance meets.
     *
     * *Highest*, not first-matching, and that is deliberate. The published run
     * tables carry two one-second inversions where a slower time sits at a
     * higher point value than the row below it -- 2MR female 47-51 lists 21:45
     * at 71 points against 21:40 at 70, and female 52-56 lists 24:01 at 61
     * against 24:00 at 60. Scanning for the best row a Soldier qualifies for
     * reads those the only way that cannot take points away from someone for
     * running faster. It is worth knowing that the second of those straddles the
     * pass mark, so 24:01 scores 61 and passes: that is what the table says, and
     * quietly "fixing" it here would fail a Soldier the Army's own scorecard
     * passes.
     */
    private fun pointsIn(series: IntArray, raw: Int, higherIsBetter: Boolean): Int {
        var best = 0
        var i = 0
        while (i < series.size) {
            val points = series[i]
            val threshold = series[i + 1]
            val met = if (higherIsBetter) raw >= threshold else raw <= threshold
            if (met && points > best) best = points
            i += 2
        }
        return best
    }

    /** The row of tables for this event, or null when the general lane has no column to read. */
    private fun seriesFor(event: AftEvent, ageYears: Int, sex: Sex, lane: AftLane): IntArray? {
        val band = bandIndex(ageYears)
        // The combat standard is sex-neutral and reads the male column whoever
        // is being scored, so it never consults the profile's sex at all.
        val female =
            when {
                lane == AftLane.COMBAT -> false
                sex == Sex.FEMALE -> true
                sex == Sex.MALE -> false
                else -> return null
            }
        return when (event) {
            AftEvent.DEADLIFT -> if (female) AftTables.MDL_F else AftTables.MDL_M
            AftEvent.PUSH_UP -> if (female) AftTables.HRP_F else AftTables.HRP_M
            AftEvent.SPRINT_DRAG_CARRY -> if (female) AftTables.SDC_F else AftTables.SDC_M
            AftEvent.PLANK -> if (female) AftTables.PLK_F else AftTables.PLK_M
            AftEvent.TWO_MILE_RUN -> if (female) AftTables.TWO_MILE_F else AftTables.TWO_MILE_M
        }[band]
    }

    /**
     * Which age band a Soldier falls in.
     *
     * Clamped at both ends rather than refused. The table starts at 17 and ends
     * open at 62, so anyone younger is scored on the youngest band and anyone
     * older on the oldest -- which is what the open-ended top band already means
     * and the only reading the bottom one supports.
     */
    internal fun bandIndex(ageYears: Int): Int {
        val bounds = AftTables.BAND_LOWER_BOUNDS
        var index = 0
        for (i in bounds.indices) if (ageYears >= bounds[i]) index = i
        return index
    }
}

/**
 * One scored attempt: the five events, their points, and what they add up to.
 *
 * Built from whatever events have been logged rather than requiring all five, so
 * a part-finished test day still reports what it knows. [total] is therefore a
 * running figure and [isComplete] says whether it is the real one -- a total of
 * 240 over four events is not a failing 500-point test, it is an unfinished one,
 * and the two must never render the same.
 */
data class AftScorecard(
    val lane: AftLane,
    val scores: Map<AftEvent, Int>,
) {
    /** Points so far. Not a verdict unless [isComplete]. */
    val total: Int
        get() = scores.values.sum()

    val isComplete: Boolean
        get() = AftEvent.entries.all { it in scores }

    /** Events scored below the per-event floor, in test order. */
    val failedEvents: List<AftEvent>
        get() = AftEvent.entries.filter { scores[it]?.let { s -> s < AftScoring.MINIMUM_EVENT_SCORE } == true }

    /**
     * Whether this attempt passes, or null while it is still unfinished.
     *
     * Both conditions have to hold and the per-event one is not implied by the
     * total: 500 points with a 59 in one event is a failure, which is the whole
     * reason the floor is stated separately from the sum.
     */
    val passes: Boolean?
        get() =
            if (!isComplete) null
            else failedEvents.isEmpty() && total >= lane.minimumTotal

    /**
     * The event with the least room above the floor, and how much it has.
     *
     * The weakest event by *margin* rather than by points, because that is the
     * one an extra session moves the verdict on. Null until something is scored.
     */
    val weakestEvent: Pair<AftEvent, Int>?
        get() =
            scores.entries
                .minByOrNull { it.value }
                ?.let { it.key to it.value - AftScoring.MINIMUM_EVENT_SCORE }
}
