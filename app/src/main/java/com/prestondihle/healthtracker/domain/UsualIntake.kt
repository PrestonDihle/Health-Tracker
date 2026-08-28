package com.prestondihle.healthtracker.domain

import com.prestondihle.healthtracker.data.SupplementSlot
import java.time.Instant
import java.time.LocalTime

/**
 * What the reader usually does, so the common case costs one tap.
 *
 * The widget already proved the shape: water, caffeine and the fast are the
 * three things entered while doing something else, and each of them otherwise
 * costs unlocking the phone, finding a tab and working a stepper. Log should be
 * at least as quick for the same cases, and everything needed to guess them is
 * already on disk -- so none of this is stored, and there is no "favourite" to
 * set, get stale, and then have to be maintained.
 *
 * Every suggestion here is allowed to be absent. A reader with no history has no
 * usual, and a row of buttons offering to log nothing is worse than a row that is
 * not there.
 */
object UsualIntake {

    /**
     * How far back a habit is read from.
     *
     * A month. Long enough that somebody who drinks coffee on weekdays still has
     * a usual on a Monday morning, short enough that a bottle size given up in
     * the spring has stopped being offered.
     */
    const val HISTORY_DAYS = 30L

    /**
     * The most recent dose, which is the one worth repeating.
     *
     * Deliberately the *last* rather than the most common: caffeine is drunk in
     * whatever the current cup is, and somebody who has moved from a 95 mg cup to
     * a 150 mg one wants the new one on the second day, not once the count
     * catches up. Water is the other way round and takes [usualVolume] instead --
     * a bottle is a bottle, and the odd 250 ml glass should not become the
     * suggestion just for being last.
     */
    fun lastDose(entries: List<Pair<Instant, Int>>): Int? =
        entries.maxByOrNull { it.first }?.second?.takeIf { it > 0 }

    /**
     * The volume logged most often, ties going to whichever was used most recently.
     *
     * A mode rather than a mean, because a mean of a 500 ml bottle and a 250 ml
     * glass is 375 ml, which is a quantity the reader has never once drunk and
     * has no container for. The whole point is a button that matches something
     * real.
     *
     * The tie-break is not decoration: two volumes used equally often are a habit
     * mid-change, and the more recent one is the one it is changing to.
     */
    fun usualVolume(entries: List<Pair<Instant, Int>>): Int? {
        val positive = entries.filter { it.second > 0 }
        if (positive.isEmpty()) return null
        return positive
            .groupBy { it.second }
            .entries
            .maxWithOrNull(
                compareBy<Map.Entry<Int, List<Pair<Instant, Int>>>> { it.value.size }
                    .thenBy { group -> group.value.maxOf { it.first } }
            )
            ?.key
    }

    /**
     * Which slot of the stack a time of day belongs to.
     *
     * The plan asked for "take all morning supplements"; this follows the clock
     * instead, because a row offering the morning's pills at nine in the evening
     * is a row nobody taps. The boundaries are where the words stop being true
     * rather than thirds of a day: noon ends the morning by definition, and five
     * is where an afternoon dose stops being midday and starts being evening.
     *
     * Anything before the first boundary is morning, including the small hours --
     * somebody up at two is at the end of a long evening rather than the start of
     * a morning, but they have not taken tomorrow's stack either, and offering it
     * is a tap they can decline.
     */
    fun slotAt(time: LocalTime): SupplementSlot =
        when {
            time < MIDDAY_FROM -> SupplementSlot.MORNING
            time < EVENING_FROM -> SupplementSlot.MIDDAY
            else -> SupplementSlot.EVENING
        }

    private val MIDDAY_FROM: LocalTime = LocalTime.NOON
    private val EVENING_FROM: LocalTime = LocalTime.of(17, 0)
}
