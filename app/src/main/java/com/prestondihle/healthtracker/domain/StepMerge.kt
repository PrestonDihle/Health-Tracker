package com.prestondihle.healthtracker.domain

import com.prestondihle.healthtracker.health.HourlySteps
import java.time.Instant

/**
 * Folds several apps' step slices into one series by taking the highest figure
 * for each slice.
 *
 * The two obvious readings of a multi-writer step count are both wrong, and they
 * are wrong in opposite directions:
 *
 * - **Summing** every origin double-counts. A watch and a phone in the same
 *   pocket both record the same walk, and their totals add to nearly twice what
 *   was walked. On the day this was written the combined figure was 13,265
 *   against the watch app's own 12,656, on a day the phone mostly sat on a desk;
 *   carried all day it approaches 2x.
 * - **Pinning** one origin drops whatever only the others saw. Garmin Connect
 *   writes per-minute monitoring steps to Health Connect but writes **no step
 *   records at all for a tracked activity** — a 35-minute run on 31 August 2026
 *   produced an `ExerciseSessionRecord` and not one Garmin-origin step record in
 *   its window, while the phone's own tracking counted roughly 7,600 steps
 *   through it. Pinned to the watch, the app reported 5,607 for a 12,656 day.
 *
 * The maximum is the smallest honest error available from this data. Two origins
 * watching the same legs report near-identical figures for a slice, so taking
 * the larger never double-counts; an origin that saw a stretch nothing else did
 * carries that slice alone, so nothing is dropped.
 *
 * **The known under-read**: a quarter hour in which two origins recorded
 * *different* walking — a phone left on a desk while the watch went to the
 * kitchen, in the same fifteen minutes the phone recorded a corridor — reports
 * the larger rather than the total. That is accepted. Recovering it would mean
 * deciding which overlaps are the same walk and which are not, from two counts
 * and a timestamp, and every rule for doing so double-counts the ordinary case
 * to rescue the rare one.
 *
 * Pure and free of Health Connect: it takes lists of slices, whatever produced
 * them.
 */
object StepMerge {

    /**
     * One slice per instant any origin reported, each holding the highest count
     * reported for it. Sorted by time, which is the order every caller wants and
     * the order a map does not have.
     *
     * A slice reported as zero by one origin and not at all by another still
     * comes back, at zero: an origin saying "no steps here" is a statement, and
     * dropping it would leave the cache unable to overwrite a stale figure for
     * that quarter hour. Health Connect omits a slice it has no records in, so
     * absence and a recorded zero already mean different things upstream.
     */
    fun merge(perOrigin: List<List<HourlySteps>>): List<HourlySteps> {
        val best = HashMap<Instant, Int>()
        for (series in perOrigin) {
            for (slice in series) {
                val current = best[slice.hourStart]
                if (current == null || slice.steps > current) best[slice.hourStart] = slice.steps
            }
        }
        return best.entries.sortedBy { it.key }.map { HourlySteps(it.key, it.value) }
    }
}
