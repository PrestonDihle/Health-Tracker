package com.prestondihle.healthtracker.domain

import java.time.Duration
import java.time.Instant

/** A run's effort band, by heart rate as a fraction of the runner's maximum. */
enum class RunZone {
    EASY,
    MODERATE,
    HARD,
    INTENSE,
}

/**
 * One run reduced to how long it spent in each intensity zone.
 *
 * The minutes are what the stacked bar is built from -- one segment per zone,
 * the whole bar as tall as the run is long. [distanceMeters] rides along for a
 * caption; it does not size the bar.
 */
data class RunBreakdown(
    val start: Instant,
    val distanceMeters: Double?,
    val easyMinutes: Float,
    val moderateMinutes: Float,
    val hardMinutes: Float,
    val intenseMinutes: Float,
) {
    /** Easy, Moderate, Hard, Intense -- the order the stack is drawn in. */
    val segments: List<Float>
        get() = listOf(easyMinutes, moderateMinutes, hardMinutes, intenseMinutes)

    val totalMinutes: Float
        get() = easyMinutes + moderateMinutes + hardMinutes + intenseMinutes
}

object RunZones {
    /**
     * A gap between samples longer than this is treated as missing data, not as
     * time held at the last reading -- a watch that paused for a red light should
     * not credit five minutes of "Easy" to the zone it happened to stop in.
     */
    private val MAX_SAMPLE_GAP: Duration = Duration.ofMinutes(3)

    /**
     * The zone a reading falls in, as a fraction of max heart rate.
     *
     * Easy below 60%, Moderate to 75%, Hard to 90%, Intense at or above it --
     * the boundaries closed at the bottom so a reading exactly on one lands in
     * the harder zone.
     */
    fun zoneFor(bpm: Int, maxHeartRate: Int): RunZone {
        val ratio = if (maxHeartRate > 0) bpm.toFloat() / maxHeartRate else 0f
        return when {
            ratio < 0.60f -> RunZone.EASY
            ratio < 0.75f -> RunZone.MODERATE
            ratio < 0.90f -> RunZone.HARD
            else -> RunZone.INTENSE
        }
    }

    /**
     * Minutes spent in each zone across a run's heart-rate [samples].
     *
     * Each sample holds its zone until the next one, capped at [MAX_SAMPLE_GAP]
     * so a dropout does not inflate a zone; the last sample runs to [runEnd]. The
     * totals therefore come out at roughly the run's length whenever the trace is
     * unbroken, which is what lets the bar's height read as duration.
     */
    fun breakdown(
        start: Instant,
        runEnd: Instant,
        distanceMeters: Double?,
        samples: List<Pair<Instant, Int>>,
        maxHeartRate: Int,
    ): RunBreakdown {
        val sorted = samples.sortedBy { it.first }
        val minutes = FloatArray(RunZone.entries.size)
        for (i in sorted.indices) {
            val (time, bpm) = sorted[i]
            val until = sorted.getOrNull(i + 1)?.first ?: runEnd
            val gap = Duration.between(time, until)
            if (gap.isNegative || gap.isZero) continue
            val held = minOf(gap, MAX_SAMPLE_GAP)
            minutes[zoneFor(bpm, maxHeartRate).ordinal] += held.seconds / 60f
        }
        return RunBreakdown(
            start = start,
            distanceMeters = distanceMeters,
            easyMinutes = minutes[RunZone.EASY.ordinal],
            moderateMinutes = minutes[RunZone.MODERATE.ordinal],
            hardMinutes = minutes[RunZone.HARD.ordinal],
            intenseMinutes = minutes[RunZone.INTENSE.ordinal],
        )
    }
}
