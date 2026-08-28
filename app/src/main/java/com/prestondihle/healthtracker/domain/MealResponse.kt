package com.prestondihle.healthtracker.domain

import com.prestondihle.healthtracker.data.MealEntry
import java.time.Duration
import java.time.Instant

/**
 * What one meal did to the blood sugar.
 *
 * Four figures rather than one, for the reason the CGM card shows four: each
 * hides what the others show. A meal can peak high and clear fast, or barely
 * peak and sit there for three hours, and those are different meals -- the
 * second is invisible to a peak-rise reading and obvious in the area.
 *
 * Every field is computed at read time from rows already on disk. Nothing here
 * is stored, because nothing here is expensive and a stored response is one that
 * has to be invalidated when a meal's time is corrected -- which, on this app's
 * data, is a thing that happens routinely.
 */
data class MealResponse(
    /** Median of the readings in the half hour before the meal, mg/dL. */
    val baselineMgDl: Float,
    /** The highest reading inside the response window. */
    val peakMgDl: Float,
    val timeToPeak: Duration,
    /**
     * Trapezoidal area above baseline across the response window, in
     * mg/dL x minutes.
     *
     * The **incremental** area: only what stands above the baseline counts, so a
     * meal is scored on what it added rather than on how high the trace already
     * was. Without that, eating at 140 would outscore the same meal eaten at 90
     * purely for having started higher.
     */
    val incrementalAuc: Float,
    /** How long until the trace came back to baseline, or null if it had not. */
    val returnToBaseline: Duration?,
    /**
     * How far past the meal the readings actually reach, capped at the search
     * window.
     *
     * Sits beside [returnToBaseline] so a null there can be read correctly. "It
     * had not come back after three hours" and "the sensor stopped after forty
     * minutes" are different statements, and with only the null they would print
     * the same sentence.
     */
    val observedFor: Duration,
    val readingCount: Int,
) {
    /** How far the trace rose above where it started. */
    val peakRiseMgDl: Float
        get() = peakMgDl - baselineMgDl
}

/** One meal and what it did, for the ranking. */
data class ScoredMeal(val meal: MealEntry, val response: MealResponse)

/**
 * Scores a meal against the glucose trace around it, or declines to.
 *
 * The declining is the substance. A response is a comparison of the hours after
 * a meal against the minutes before it, so it is worth exactly as much as the
 * timestamp it is measured from -- and this app's nutrition source routinely
 * supplies a date with a fixed time of day stamped on it. Scoring one of those
 * would produce a confident figure about an hour nobody ate in.
 */
object MealResponses {

    /**
     * The half hour before the meal, which the baseline is taken from.
     *
     * Long enough to hold several readings on any CGM cadence, short enough that
     * it is still pre-prandial -- an hour back reaches into the tail of whatever
     * was eaten before.
     */
    val BASELINE_WINDOW: Duration = Duration.ofMinutes(30)

    /**
     * The window the peak and the area are measured over.
     *
     * Two hours is the standard post-prandial window and is where the published
     * glycaemic-response literature draws its line, which matters here because
     * the figure is only useful if it can be compared with something.
     */
    val RESPONSE_WINDOW: Duration = Duration.ofHours(2)

    /**
     * How far out the return to baseline is looked for before giving up.
     *
     * Capped rather than open-ended: a trace wanders, and the first dip to
     * baseline six hours later has far more to do with the next meal, a walk or
     * the evening than with this one. Past three hours the question stops being
     * about the meal.
     */
    val RETURN_CAP: Duration = Duration.ofHours(3)

    /**
     * The response to a meal eaten at [mealAt], or null when it cannot be said.
     *
     * [hasClockTime] is the caller's own stamped-versus-measured judgement, the
     * same one the meal list draws its "set time" flag from. A stamped meal is
     * unscored outright -- not scored from the stamp, and not scored from a
     * guessed mealtime. Null here means unmeasured, and the screen must say so
     * rather than printing a zero.
     *
     * [readings] may be wider than any window used here; it is filtered per
     * window rather than assumed to be scoped.
     */
    fun score(
        mealAt: Instant,
        readings: List<Pair<Instant, Int>>,
        hasClockTime: Boolean,
    ): MealResponse? {
        if (!hasClockTime) return null

        val sorted = readings.sortedBy { it.first }

        // The reading taken exactly at the meal counts as the last pre-meal value
        // *and* as the first point of the response. That is not double-counting:
        // it is the level the meal started from, which is what both halves are
        // about, and a trapezoid has to start somewhere.
        val before =
            sorted.filter { (at, _) ->
                !at.isBefore(mealAt.minus(BASELINE_WINDOW)) && !at.isAfter(mealAt)
            }
        if (before.isEmpty()) return null
        // Median, not mean. A compression low or one stray fingerstick in the half
        // hour before eating would drag a mean down and inflate everything
        // measured against it at once -- the rise, the area and the apparent
        // return. Six readings shrug off one outlier; a mean does not.
        val baseline = medianOf(before.map { it.second.toFloat() })

        val windowEnd = mealAt.plus(RESPONSE_WINDOW)
        val after =
            sorted.filter { (at, _) -> !at.isBefore(mealAt) && !at.isAfter(windowEnd) }
        // Two readings is the fewest that can span anything, and a span is what
        // the coverage gate is about.
        if (after.size < 2) return null

        // 2.1's gate, applied to this window. A meal whose first reading arrives
        // forty minutes late has had its peak missed, and the area computed from
        // what is left is a smaller number about a shorter window -- which reads
        // on screen exactly like a flatter meal.
        val covered = Duration.between(after.first().first, after.last().first).seconds
        if (covered.toFloat() / RESPONSE_WINDOW.seconds < GlucoseAnalysis.MIN_COVERAGE) return null

        val peak = after.maxBy { it.second }

        // Each point clamped up to the baseline before the trapezoid, so a dip
        // below it contributes nothing rather than cancelling out a rise
        // elsewhere. A meal that spikes and then undershoots has still spiked.
        var auc = 0f
        for (i in 0 until after.size - 1) {
            val (atA, rawA) = after[i]
            val (atB, rawB) = after[i + 1]
            val a = (rawA - baseline).coerceAtLeast(0f)
            val b = (rawB - baseline).coerceAtLeast(0f)
            val minutes = Duration.between(atA, atB).seconds / 60f
            auc += (a + b) / 2f * minutes
        }

        // Looked for after the peak rather than from the meal: a trace that opens
        // a shade under its own baseline would otherwise "return" before it had
        // gone anywhere.
        val cap = mealAt.plus(RETURN_CAP)
        val toCap = sorted.filter { (at, _) -> !at.isBefore(mealAt) && !at.isAfter(cap) }
        val returned =
            toCap.firstOrNull { (at, mgDl) -> at.isAfter(peak.first) && mgDl <= baseline }

        return MealResponse(
            baselineMgDl = baseline,
            peakMgDl = peak.second.toFloat(),
            timeToPeak = Duration.between(mealAt, peak.first),
            incrementalAuc = auc,
            returnToBaseline = returned?.let { Duration.between(mealAt, it.first) },
            observedFor =
                toCap.lastOrNull()?.let { Duration.between(mealAt, it.first) } ?: Duration.ZERO,
            readingCount = after.size,
        )
    }

    /**
     * The meals that moved the blood sugar most, biggest first.
     *
     * Ranked on [MealResponse.incrementalAuc] rather than on the peak rise,
     * because the area is the figure that answers "how much did this meal do".
     * A sharp spike that clears in forty minutes and a modest rise that sits
     * there for two hours can carry the same peak, and the second is the one a
     * reader is usually trying to find. The peak is shown beside it rather than
     * ranked on, so the two are never confused.
     *
     * Unscored meals are absent rather than sorted to the bottom: a ranking is a
     * list of things that were measured, and "no CGM cover" is not a small
     * response.
     *
     * The trace is sliced per meal by binary search rather than filtered, which
     * is what makes this usable over a ninety-day window -- a scan per meal over
     * three months of five-minute readings is tens of millions of comparisons on
     * every recomposition.
     */
    fun rank(
        meals: List<MealEntry>,
        readings: List<Pair<Instant, Int>>,
        hasClockTime: (MealEntry) -> Boolean,
        limit: Int,
    ): List<ScoredMeal> {
        if (meals.isEmpty() || readings.isEmpty()) return emptyList()
        val sorted = readings.sortedBy { it.first }

        return meals
            .mapNotNull { meal ->
                val slice =
                    sliceBetween(
                        sorted,
                        meal.timestamp.minus(BASELINE_WINDOW),
                        meal.timestamp.plus(RETURN_CAP),
                    )
                score(meal.timestamp, slice, hasClockTime(meal))?.let { ScoredMeal(meal, it) }
            }
            .sortedByDescending { it.response.incrementalAuc }
            .take(limit)
    }

    /** The readings from [from] to [to] inclusive, off a list already in order. */
    private fun sliceBetween(
        sorted: List<Pair<Instant, Int>>,
        from: Instant,
        to: Instant,
    ): List<Pair<Instant, Int>> {
        val lo = firstAtOrAfter(sorted, from)
        val hi = firstAtOrAfter(sorted, to.plusNanos(1))
        return if (lo >= hi) emptyList() else sorted.subList(lo, hi)
    }

    /** Index of the first reading at or after [at], or the list's size. */
    private fun firstAtOrAfter(sorted: List<Pair<Instant, Int>>, at: Instant): Int {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low + high) / 2
            if (sorted[mid].first.isBefore(at)) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     * Middle value, averaging the two middles on an even count.
     *
     * Private and local rather than a shared utility: this is the only median in
     * the app, and the one place it is used has an argument for it written above
     * the call.
     */
    private fun medianOf(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }
}
