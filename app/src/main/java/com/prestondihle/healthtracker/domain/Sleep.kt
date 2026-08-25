package com.prestondihle.healthtracker.domain

import com.prestondihle.healthtracker.ui.components.TimePoint
import java.time.Duration
import java.time.Instant

/**
 * A stage of one night, as Health Connect reports it.
 *
 * Five values rather than the four a hypnogram draws, because a writer is
 * allowed to say "asleep" without saying which stage -- and that is a different
 * statement from any of the three named ones. Folding it into [LIGHT] would be
 * the cheapest way to keep the chart continuous and would invent a measurement
 * nobody made; it is kept apart and reported separately instead.
 *
 * Health Connect's `STAGE_TYPE_AWAKE`, `STAGE_TYPE_AWAKE_IN_BED` and
 * `STAGE_TYPE_OUT_OF_BED` all arrive as [AWAKE]. The distinction between them is
 * about where the body was, not whether it was sleeping, and nothing here asks
 * that question.
 */
enum class SleepStage(val label: String) {
    DEEP("Deep"),
    LIGHT("Light"),
    REM("REM"),

    /** Asleep, with no stage recorded. Counted as sleep; never drawn as a stage. */
    ASLEEP("Unstaged"),
    AWAKE("Awake"),
}

/**
 * Where a stage sits on the hypnogram, or null for one with no honest height.
 *
 * Deep at the floor and awake at the ceiling, which is the way every published
 * hypnogram is drawn -- the trace falling means sleep deepening, and a reader who
 * has seen one before does not have to check the axis to know which way is down.
 *
 * [SleepStage.ASLEEP] has no level on purpose. There is no height that means
 * "asleep, stage unknown" without also meaning one of the three named stages, so
 * it is left off the plot and printed in the totals instead.
 */
val SleepStage.level: Float?
    get() =
        when (this) {
            SleepStage.DEEP -> 0f
            SleepStage.LIGHT -> 1f
            SleepStage.REM -> 2f
            SleepStage.AWAKE -> 3f
            SleepStage.ASLEEP -> null
        }

/** One stretch of one stage. Half-open `[start, end)`, as [Interval] is. */
data class SleepStageInterval(val start: Instant, val end: Instant, val stage: SleepStage) {
    val duration: Duration
        get() = Duration.between(start, end)
}

/**
 * One night, and what it was made of.
 *
 * [start] and [end] are the session's own bounds rather than the extremes of its
 * stages: a writer may bound the night more widely than the stages it managed to
 * classify, and the time in bed is the session's answer to give.
 */
data class SleepNight(
    val start: Instant,
    val end: Instant,
    val stages: List<SleepStageInterval>,
) {
    val timeInBed: Duration
        get() = Duration.between(start, end)

    val deep: Duration
        get() = totalOf(SleepStage.DEEP)

    val light: Duration
        get() = totalOf(SleepStage.LIGHT)

    val rem: Duration
        get() = totalOf(SleepStage.REM)

    val awake: Duration
        get() = totalOf(SleepStage.AWAKE)

    /** Asleep with no stage recorded. Zero for any source that stages its nights. */
    val unstaged: Duration
        get() = totalOf(SleepStage.ASLEEP)

    /**
     * Time actually asleep: the three named stages plus anything unstaged.
     *
     * Deliberately not [timeInBed]. A night bounded 23:00 to 07:30 with forty
     * minutes of waking in it is seven fifty asleep, and reporting the eight and
     * a half would be flattering rather than accurate -- which is the whole
     * reason the stages are worth reading at all.
     */
    val totalAsleep: Duration
        get() = deep + light + rem + unstaged

    private fun totalOf(stage: SleepStage): Duration =
        stages.filter { it.stage == stage }.fold(Duration.ZERO) { acc, it -> acc + it.duration }
}

object Sleep {

    /** Floor and ceiling of the hypnogram, which are the outermost stage levels. */
    const val PLOT_MIN = 0f
    const val PLOT_MAX = 3f

    /**
     * Gaps between the axis rules: one fewer than there are stages to name.
     *
     * Fixed rather than fitted to the card's height, because this axis is
     * categorical. Fitted, a 300dp card lands rules at 0.75, 1.5 and 2.25 -- none
     * of which is a stage -- and the two middle stages go unlabelled on a chart
     * whose entire subject is which stage it was. Derived from the levels rather
     * than typed as 3, so adding a stage cannot leave the axis a rule short.
     */
    val PLOT_ROWS: Int = SleepStage.entries.count { it.level != null } - 1

    /**
     * The stage plotted as a step trace.
     *
     * Two points per stretch -- one at each end, both at that stretch's level --
     * so consecutive stretches share an x and the line between them is vertical.
     * That draws a step without the chart needing a step primitive: the join
     * between two points at the same instant *is* the riser.
     *
     * Unstaged stretches contribute nothing, so a source that does not classify
     * its nights draws no trace rather than a flat line at a level it never
     * reported.
     */
    fun hypnogram(stages: List<SleepStageInterval>): List<TimePoint> =
        stages
            .sortedBy { it.start }
            .flatMap { interval ->
                val level = interval.stage.level ?: return@flatMap emptyList()
                listOf(TimePoint(interval.start, level), TimePoint(interval.end, level))
            }

    /** Axis labels for the hypnogram: the stage's name at its own level. */
    fun formatLevel(value: Float): String =
        SleepStage.entries.firstOrNull { it.level == value }?.label ?: ""

    /** `7h 24m`, or `24m` under an hour. The card has no room for a third unit. */
    fun formatDuration(duration: Duration): String {
        val minutes = duration.toMinutes()
        val hours = minutes / 60
        val remainder = minutes % 60
        return if (hours > 0) "${hours}h ${remainder}m" else "${remainder}m"
    }
}
