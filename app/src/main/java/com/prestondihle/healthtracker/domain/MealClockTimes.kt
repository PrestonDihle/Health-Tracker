package com.prestondihle.healthtracker.domain

import com.prestondihle.healthtracker.data.MealEntry
import java.time.LocalTime
import java.time.ZoneId

/**
 * Telling a meal time that was measured from one that was merely stamped.
 *
 * A nutrition source is free to record only the date, and when it does it lands
 * every meal on one fixed time of day -- the author's own data carries
 * twenty-five meals at exactly 10:00:00, three of them on a single Tuesday. A
 * genuine timestamp never repeats to the second; a source that knows only the
 * date repeats one for ever. **The repeat is the signal, and the particular hour
 * is not** -- narrowing this to a midnight check was the first attempt and the
 * phone's 10:00 stamp sails straight past it.
 *
 * Extracted here because two screens now ask the question and the answer must
 * not be allowed to differ between them: the meal list decides whether to offer
 * a correction, and the response scoring decides whether a meal can be scored at
 * all. Two copies of this rule would eventually disagree, and the disagreement
 * would look like a scoring bug rather than a definition drifting.
 */
object MealClockTimes {

    /**
     * The times of day, among [meals], that are a stamp rather than a measurement.
     *
     * Midnight joins unconditionally, since a lone meal at exactly `00:00:00` is
     * a date rather than a time somebody ate at.
     *
     * Judged across every meal handed in, not per day -- the stamp repeats across
     * days as readily as within one, and over a wider set it is easier to spot,
     * not harder. The cost is that two genuinely different meals eaten at the
     * same second are called stamped, which is an accepted false positive: it
     * grows with the window, and it fails in the safe direction. A meal wrongly
     * called stamped is offered a correction it does not need and goes unscored;
     * a meal wrongly called measured is scored against an hour nobody ate in.
     */
    fun stampedTimesOfDay(meals: List<MealEntry>, zoneId: ZoneId): Set<LocalTime> =
        meals
            .groupBy { it.timestamp.atZone(zoneId).toLocalTime() }
            .filterValues { it.size > 1 }
            .keys + LocalTime.MIDNIGHT

    /** Whether [meal] carries a real clock time, given [stamped] from above. */
    fun hasClockTime(meal: MealEntry, stamped: Set<LocalTime>, zoneId: ZoneId): Boolean =
        meal.timestamp.atZone(zoneId).toLocalTime() !in stamped
}
