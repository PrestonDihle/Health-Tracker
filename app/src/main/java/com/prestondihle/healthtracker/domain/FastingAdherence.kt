package com.prestondihle.healthtracker.domain

import com.prestondihle.healthtracker.data.FastingPlanDay
import com.prestondihle.healthtracker.data.FastingSession
import com.prestondihle.healthtracker.data.PlannedExtendedFast
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Result of scoring a week. [score] is null when nothing was planned in the
 * evaluated window -- distinct from a score of 0, which means time was planned
 * as a fast and none of it was fasted.
 */
data class AdherenceResult(
    val score: Int?,
    val plannedSeconds: Long,
    val fastedSeconds: Long,
    /** The portion of the week actually scored: plan start through now. */
    val evaluated: Interval,
)

/**
 * Scores real fasting against the weekly plan.
 *
 * The measure is elapsed planned fast time that was actually fasted. Future
 * planned time is excluded -- otherwise checking the score on Monday morning
 * would show near zero for a week that is going perfectly.
 */
object FastingAdherence {

    /** Feeding windows, expanded to absolute instants and clipped to [window]. */
    private fun feedingIntervals(
        plan: Map<DayOfWeek, FastingPlanDay>,
        window: Interval,
        zoneId: ZoneId,
    ): Pair<List<Interval>, List<Interval>> {
        val feeding = mutableListOf<Interval>()
        val unplanned = mutableListOf<Interval>()

        // Start a day early so a window that wraps past midnight (e.g. 20:00 to
        // 02:00) still contributes its tail to the first day of the window.
        var date = window.start.atZone(zoneId).toLocalDate().minusDays(1)
        val lastDate = window.end.atZone(zoneId).toLocalDate()

        while (!date.isAfter(lastDate)) {
            val day = plan[date.dayOfWeek]
            if (day == null || !day.enabled) {
                unplanned.add(
                    Interval(
                        date.atStartOfDay(zoneId).toInstant(),
                        date.plusDays(1).atStartOfDay(zoneId).toInstant(),
                    )
                )
            } else {
                feeding.add(feedingWindowOn(date, day.feedingStart, day.feedingEnd, zoneId))
            }
            date = date.plusDays(1)
        }
        return feeding to unplanned
    }

    private fun feedingWindowOn(
        date: LocalDate,
        start: LocalTime,
        end: LocalTime,
        zoneId: ZoneId,
    ): Interval {
        val from = date.atTime(start).atZone(zoneId).toInstant()
        // An end at or before the start means the window runs past midnight.
        val toDate = if (end.isAfter(start)) date else date.plusDays(1)
        return Interval(from, toDate.atTime(end).atZone(zoneId).toInstant())
    }

    /**
     * Time the plan says should be spent fasting within [window].
     *
     * Disabled days contribute nothing in either direction. A planned extended
     * fast overrides both feeding windows and disabled days for the span it
     * covers, so a 48-hour fast is scored even if it crosses a rest day.
     */
    fun plannedFastIntervals(
        plan: List<FastingPlanDay>,
        extendedFasts: List<PlannedExtendedFast>,
        window: Interval,
        zoneId: ZoneId,
    ): List<Interval> {
        val (feeding, unplanned) = feedingIntervals(plan.associateBy { it.dayOfWeek }, window, zoneId)

        val fromDailyPlan =
            listOf(window).minusIntervals(unplanned).minusIntervals(feeding)

        val fromExtended =
            extendedFasts.mapNotNull { Interval(it.startInstant, it.endInstant).clampTo(window) }

        return (fromDailyPlan + fromExtended).normalized()
    }

    /** Logged fasts as intervals. An open session is treated as running until [now]. */
    fun actualFastIntervals(sessions: List<FastingSession>, now: Instant): List<Interval> =
        sessions
            .map { Interval(it.startInstant, it.endInstant ?: now) }
            .filterNot { it.isEmpty }
            .normalized()

    fun score(
        plan: List<FastingPlanDay>,
        extendedFasts: List<PlannedExtendedFast>,
        sessions: List<FastingSession>,
        weekStart: Instant,
        weekEnd: Instant,
        now: Instant,
        zoneId: ZoneId,
    ): AdherenceResult {
        val evaluated = Interval(weekStart, minOf(weekEnd, now))
        if (evaluated.isEmpty) {
            return AdherenceResult(null, 0, 0, Interval(weekStart, weekStart))
        }

        val planned = plannedFastIntervals(plan, extendedFasts, evaluated, zoneId)
        val actual = actualFastIntervals(sessions, now).intersectWith(listOf(evaluated))

        val plannedSeconds = planned.totalSeconds
        val fastedSeconds = planned.intersectWith(actual).totalSeconds

        val score =
            if (plannedSeconds <= 0L) null
            else ((fastedSeconds * 100.0) / plannedSeconds).roundToInt().coerceIn(0, 100)

        return AdherenceResult(score, plannedSeconds, fastedSeconds, evaluated)
    }

    /** A sensible starting plan: 16:8, eating noon to 20:00, every day. */
    fun defaultPlan(): List<FastingPlanDay> =
        DayOfWeek.values().toList().map {
            FastingPlanDay(
                dayOfWeek = it,
                feedingStart = LocalTime.of(12, 0),
                feedingEnd = LocalTime.of(20, 0),
                enabled = true,
            )
        }
}
