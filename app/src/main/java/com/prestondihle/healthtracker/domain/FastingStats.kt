package com.prestondihle.healthtracker.domain

import com.prestondihle.healthtracker.data.FastingSession
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One day's fasting, as fractions of the day.
 *
 * Segments are `0f..1f` across midnight-to-midnight rather than instants,
 * because the timeline draws them as proportions of a fixed-width row and would
 * otherwise repeat that arithmetic per frame.
 */
data class FastingDay(
    val date: LocalDate,
    val segments: List<ClosedFloatingPointRange<Float>>,
    val fastedSeconds: Long,
)

/** Headline numbers for the fasting screen. */
data class FastingStats(
    val todaySeconds: Long = 0,
    val weekSeconds: Long = 0,
    val monthSeconds: Long = 0,
    val longestFast: Duration? = null,
    val longestFastEnded: Instant? = null,
    val averageFastSeconds: Long = 0,
    val completedFasts: Int = 0,
    val currentStreakDays: Int = 0,
    val bestStreakDays: Int = 0,
)

/**
 * Aggregates logged fasting sessions into per-day segments and summary figures.
 *
 * Everything here works off [Interval] set algebra rather than summing session
 * durations directly, so two sessions that overlap -- or one left open and
 * restarted -- cannot double-count the same minute.
 */
object FastingStatistics {

    /**
     * Fasted intervals as wall-clock time, with an open session running to [now].
     *
     * Mirrors [FastingAdherence.actualFastIntervals]; kept separate because that
     * one is scoring-specific and this one is used for display over long ranges.
     */
    private fun intervals(sessions: List<FastingSession>, now: Instant): List<Interval> =
        sessions
            .map { Interval(it.startInstant, it.endInstant ?: now) }
            .filterNot { it.isEmpty }
            .normalized()

    /** Seconds fasted within [window], counting overlapping sessions only once. */
    fun fastedSeconds(
        sessions: List<FastingSession>,
        window: Interval,
        now: Instant,
    ): Long = intervals(sessions, now).intersectWith(listOf(window)).totalSeconds

    /**
     * One [FastingDay] per date from [from] to [to] inclusive, oldest first.
     *
     * Days with no fasting are still emitted, so the timeline keeps a continuous
     * row per day rather than silently collapsing gaps.
     */
    fun daysBetween(
        sessions: List<FastingSession>,
        from: LocalDate,
        to: LocalDate,
        zoneId: ZoneId,
        now: Instant,
    ): List<FastingDay> {
        if (from.isAfter(to)) return emptyList()
        val fasted = intervals(sessions, now)

        val days = mutableListOf<FastingDay>()
        var date = from
        while (!date.isAfter(to)) {
            val dayStart = date.atStartOfDay(zoneId).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant()
            val dayWindow = Interval(dayStart, dayEnd)
            val daySeconds = dayWindow.seconds.toFloat()

            val clipped = fasted.mapNotNull { it.clampTo(dayWindow) }
            days.add(
                FastingDay(
                    date = date,
                    segments =
                        clipped.map { part ->
                            val startFraction =
                                Duration.between(dayStart, part.start).seconds / daySeconds
                            val endFraction =
                                Duration.between(dayStart, part.end).seconds / daySeconds
                            startFraction.coerceIn(0f, 1f)..endFraction.coerceIn(0f, 1f)
                        },
                    fastedSeconds = clipped.sumOf { it.seconds },
                )
            )
            date = date.plusDays(1)
        }
        return days
    }

    /**
     * Consecutive days ending at [today] with any fasting logged.
     *
     * Today is allowed to be empty without breaking the streak -- checked in the
     * morning, before the day's fast has been logged, a streak that reset to
     * zero every night would be useless. An empty yesterday still breaks it.
     */
    fun currentStreak(days: List<FastingDay>, today: LocalDate): Int {
        val byDate = days.associateBy { it.date }
        var streak = 0
        var cursor = today

        if ((byDate[today]?.fastedSeconds ?: 0L) <= 0L) cursor = today.minusDays(1)

        while ((byDate[cursor]?.fastedSeconds ?: 0L) > 0L) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /** Longest run of consecutive fasting days anywhere in [days]. */
    fun bestStreak(days: List<FastingDay>): Int {
        var best = 0
        var run = 0
        for (day in days.sortedBy { it.date }) {
            if (day.fastedSeconds > 0L) {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
        }
        return best
    }

    fun summarise(
        sessions: List<FastingSession>,
        days: List<FastingDay>,
        today: LocalDate,
        zoneId: ZoneId,
        now: Instant,
    ): FastingStats {
        val dayStart = today.atStartOfDay(zoneId).toInstant()
        val weekStart = today.minusDays(6).atStartOfDay(zoneId).toInstant()
        val monthStart = today.minusDays(29).atStartOfDay(zoneId).toInstant()
        val endOfToday = today.plusDays(1).atStartOfDay(zoneId).toInstant()

        // Only finished fasts count toward longest and average: one still running
        // would report its length so far and be beaten by itself an hour later.
        val completed = sessions.filter { it.endInstant != null }
        val longest = completed.maxByOrNull { Duration.between(it.startInstant, it.endInstant).seconds }

        return FastingStats(
            todaySeconds = fastedSeconds(sessions, Interval(dayStart, endOfToday), now),
            weekSeconds = fastedSeconds(sessions, Interval(weekStart, endOfToday), now),
            monthSeconds = fastedSeconds(sessions, Interval(monthStart, endOfToday), now),
            longestFast =
                longest?.let { Duration.between(it.startInstant, it.endInstant) },
            longestFastEnded = longest?.endInstant,
            averageFastSeconds =
                if (completed.isEmpty()) 0L
                else
                    completed.sumOf { Duration.between(it.startInstant, it.endInstant).seconds } /
                        completed.size,
            completedFasts = completed.size,
            currentStreakDays = currentStreak(days, today),
            bestStreakDays = bestStreak(days),
        )
    }
}
