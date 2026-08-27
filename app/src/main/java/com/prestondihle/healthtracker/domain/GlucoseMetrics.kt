package com.prestondihle.healthtracker.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.sqrt

/**
 * The standard summary figures for a stretch of continuous glucose monitoring.
 *
 * Every one of these is a *fraction of a whole*, which is what makes them
 * dangerous to compute carelessly: they read exactly the same whether they came
 * from a full day of sensor data or from the twenty minutes after breakfast, and
 * a fragment almost always flatters. So they are computed only when the readings
 * genuinely cover the window -- see [GlucoseMetrics.over].
 *
 * [timeInRange] is a share of readings rather than of elapsed time. With a sensor
 * writing on a fixed cadence the two are the same thing, and where the cadence
 * breaks the coverage gate has already refused to report at all.
 */
data class GlucoseMetrics(
    val readingCount: Int,
    /** Share of readings inside the target band, 0f..1f. */
    val timeInRange: Float,
    /** Share below the band's floor. */
    val timeBelowRange: Float,
    /** Share above the band's ceiling. */
    val timeAboveRange: Float,
    val meanMgDl: Float,
    /** Population standard deviation of the readings, in mg/dL. */
    val standardDeviation: Float,
) {
    /**
     * Glucose Management Indicator: the HbA1c a mean like this usually goes with.
     *
     * `3.31 + 0.02392 x mean`, the published regression. It is an estimate from a
     * fortnight or so of sensor data and is not a lab result -- close enough to
     * plan against, not close enough to argue with a clinician about, which is
     * why it is presented as a model.
     */
    val gmiPercent: Float
        get() = 3.31f + 0.02392f * meanMgDl

    /**
     * Coefficient of variation: how unstable the trace is, independent of level.
     *
     * Standard deviation over mean, as a percentage. The consensus target is
     * **36% or under** -- above it a trace is called unstable regardless of how
     * good the average looks, which is the whole reason it is reported next to
     * the mean rather than instead of it.
     */
    val coefficientOfVariation: Float
        get() = if (meanMgDl <= 0f) 0f else standardDeviation / meanMgDl * 100f

    val isStable: Boolean
        get() = coefficientOfVariation <= STABLE_CV_PERCENT

    companion object {
        /** The consensus ceiling for a stable trace. */
        const val STABLE_CV_PERCENT = 36f
    }
}

/**
 * Computes [GlucoseMetrics], and refuses to when the data does not support it.
 */
object GlucoseAnalysis {

    /**
     * How much of a window the readings must span before anything is reported.
     *
     * Seventy per cent is the consensus minimum for calling a CGM summary
     * representative, and it is a floor rather than a preference: below it the
     * figures stop describing the window and start describing whichever part of
     * it the sensor happened to be awake for. A morning of readings after a
     * sensor change would otherwise report a time-in-range for the whole day.
     */
    const val MIN_COVERAGE = 0.70f

    /**
     * Metrics for [start] until [end], or null when the readings do not cover it.
     *
     * Null rather than a number computed from what there is. Everything here is a
     * proportion, so a fragment produces a perfectly well-formed figure that is
     * simply about a different span than the one asked for -- and nothing on
     * screen could tell the two apart.
     *
     * Coverage is judged by the **span the readings actually occupy** rather than
     * by counting them against an assumed cadence. Sensors differ, warm up, and
     * are read by hand as well, so a count-based gate would be a gate on the
     * device rather than on the data.
     */
    fun over(
        readings: List<Pair<Instant, Int>>,
        start: Instant,
        end: Instant,
        targetLowMgDl: Int,
        targetHighMgDl: Int,
    ): GlucoseMetrics? {
        val window = Duration.between(start, end).seconds
        if (window <= 0L) return null

        val inWindow =
            readings.filter { (at, _) -> !at.isBefore(start) && at.isBefore(end) }.sortedBy { it.first }
        // Two readings is the fewest that can span anything at all, and a span is
        // what the gate is about.
        if (inWindow.size < 2) return null

        val covered = Duration.between(inWindow.first().first, inWindow.last().first).seconds
        if (covered.toFloat() / window < MIN_COVERAGE) return null

        val values = inWindow.map { it.second }
        val mean = values.average().toFloat()
        // Population rather than sample: these are all the readings there are for
        // the window, not a sample drawn from a larger set of them.
        val variance = values.sumOf { (it - mean).toDouble() * (it - mean) } / values.size
        val below = values.count { it < targetLowMgDl }
        val above = values.count { it > targetHighMgDl }
        val inRange = values.size - below - above

        return GlucoseMetrics(
            readingCount = values.size,
            timeInRange = inRange.toFloat() / values.size,
            timeBelowRange = below.toFloat() / values.size,
            timeAboveRange = above.toFloat() / values.size,
            meanMgDl = mean,
            standardDeviation = sqrt(variance).toFloat(),
        )
    }
}
