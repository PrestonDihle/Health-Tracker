package com.prestondihle.healthtracker.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.exp

/**
 * Takes the sampling noise out of a blood sugar trace without moving where its
 * peaks are.
 *
 * ## Why
 *
 * A continuous monitor reports interstitial glucose every five minutes, and
 * consecutive samples routinely differ by several mg/dL for reasons that are
 * sensor and not physiology. Drawn raw at the width of a phone screen the trace
 * is a fuzzy band, and the shape actually being looked for -- how far and how
 * fast a meal pushed it -- has to be read through the fuzz.
 *
 * ## The filter
 *
 * A Gaussian-weighted moving average in *time*, not in sample index. Every
 * reading inside [HALF_WIDTH] of the point being computed contributes
 * `e^(−½·(Δt/σ)²)` of its value, and the weights are renormalised to sum to one,
 * so the result is always a weighted mean of real readings and can never leave
 * the range they span.
 *
 * Weighting by time rather than by position is what lets the same filter run
 * over a CGM trace and a handful of hand-typed fingersticks. Index weighting
 * would treat two readings a week apart as neighbours simply because nothing was
 * logged between them, and would average them together.
 *
 * Gaussian rather than a flat boxcar because a boxcar is a poor low-pass filter:
 * its abrupt edges leave visible kinks where a sample enters and leaves the
 * window, which is exactly the sort of artefact this is drawn to remove.
 *
 * ## What it does not do
 *
 * It does not resample, interpolate, or invent points. The output has one value
 * per input reading, at that reading's own timestamp, so the smoothed line still
 * begins and ends where the data does -- the right-hand end of a chart is *now*,
 * and a filter that ran short of it would be quietly reporting the past.
 *
 * A reading with no neighbours inside the window is returned exactly as it was:
 * its own weight is the only one there is. That matters for a manual fingerstick
 * taken hours from anything else, which is a measurement and not a point on a
 * curve.
 *
 * Half a window of smoothing is still a distortion, which is why the setting
 * defaults to off and the chart says so while it is on.
 */
object GlucoseSmoothing {

    /**
     * How far either side a reading is allowed to influence the line.
     *
     * Fifteen minutes is about three CGM samples each way. Sensor noise lives
     * well below that; a post-meal rise takes 45 minutes or more to develop, so
     * it passes through with its height and its timing intact. Widening this
     * much further starts to shave real peaks.
     */
    val HALF_WIDTH: Duration = Duration.ofMinutes(15)

    /**
     * Standard deviation of the kernel, as a fraction of [HALF_WIDTH].
     *
     * Half, which puts the cutoff at two sigma: the weight at the edge of the
     * window is down to 14% of the centre's, so a reading does not drop out of
     * the average from a meaningful height as it leaves.
     */
    private const val SIGMA_FRACTION = 0.5

    /**
     * One reading per input reading, at the same instants, each a weighted mean
     * of its neighbours within [halfWidth].
     *
     * Input is sorted by time first; the sliding window depends on it, and a
     * caller reading from more than one table cannot be assumed to have done it.
     */
    fun smooth(
        samples: List<Pair<Instant, Float>>,
        halfWidth: Duration = HALF_WIDTH,
    ): List<Pair<Instant, Float>> {
        if (samples.size < 3 || halfWidth.isZero || halfWidth.isNegative) return samples

        val sorted = samples.sortedBy { it.first }
        val times = LongArray(sorted.size) { sorted[it].first.toEpochMilli() }
        val cutoff = halfWidth.toMillis()
        val sigma = cutoff * SIGMA_FRACTION

        var low = 0
        var high = 0
        return List(sorted.size) { index ->
            val centre = times[index]
            // Both ends only ever move forward, because `centre` only ever moves
            // forward -- which is what keeps this linear rather than quadratic
            // over the two thousand points a week of CGM comes to.
            while (times[low] < centre - cutoff) low++
            while (high < times.size && times[high] <= centre + cutoff) high++

            var weighted = 0.0
            var totalWeight = 0.0
            for (neighbour in low until high) {
                val offset = (times[neighbour] - centre) / sigma
                val weight = exp(-0.5 * offset * offset)
                weighted += weight * sorted[neighbour].second
                totalWeight += weight
            }

            sorted[index].first to (weighted / totalWeight).toFloat()
        }
    }
}
