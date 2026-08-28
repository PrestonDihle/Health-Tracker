package com.prestondihle.healthtracker.domain

import java.time.LocalDate

/**
 * How many days in a row something has been kept up.
 *
 * Lifted out of [FastingStatistics], which had the only copy and is now one of
 * four callers. What made it worth generalising is not the loop -- that is four
 * lines -- but the rule underneath it, which is the part every second
 * implementation gets wrong.
 *
 * ## Today is allowed to be empty
 *
 * A streak read at nine in the morning is being read before the day has had a
 * chance to happen. Counting today as a miss would reset every streak in the app
 * to zero overnight and restore it each evening, so the number would be wrong
 * for most of the hours anybody looks at it -- and wrong in the direction that
 * makes it useless, since the thing a streak is for is not wanting to break one.
 *
 * An empty *yesterday* still breaks it. That is the line: one unfinished day is
 * a day in progress, two is a lapse.
 *
 * ## The caller decides what counts
 *
 * This takes the set of dates that met whatever the bar was, never the readings
 * themselves. A step goal, a protein target, a full supplement slot and a day
 * with any fasting on it are four different questions and only one of them is a
 * comparison against a number -- so answering "did this day count" belongs with
 * the data, and only the counting belongs here.
 *
 * A day that is *absent* from the set and a day that failed are the same thing
 * to a streak, which is worth stating because it is the one place this file
 * departs from the rest of the app's null-is-not-zero rule. It has to: an
 * unbroken run means every day in it cleared the bar, and a day with no evidence
 * did not clear it. [Streaks] is the wrong place to soften that, since the
 * softening would be invisible on a card that says only a number.
 */
object Streaks {

    /**
     * Days in a row up to and including [today], tolerating an empty today.
     *
     * Zero when yesterday is missing too -- at that point there is no run to be
     * partway through.
     */
    fun current(met: Set<LocalDate>, today: LocalDate): Int {
        var cursor = if (today in met) today else today.minusDays(1)
        var streak = 0
        while (cursor in met) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /**
     * The longest run of consecutive days anywhere in [met].
     *
     * Walks the dates in order rather than every date in some range, so it costs
     * what the reader actually achieved rather than what the window spans -- and
     * needs no window passed in, which is what lets a personal best reach back
     * as far as the table does.
     */
    fun best(met: Set<LocalDate>): Int {
        if (met.isEmpty()) return 0
        val sorted = met.sorted()
        var best = 1
        var run = 1
        for (index in 1 until sorted.size) {
            run = if (sorted[index] == sorted[index - 1].plusDays(1)) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }
}
