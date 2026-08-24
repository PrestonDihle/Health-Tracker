package com.prestondihle.healthtracker.domain

import java.time.Duration
import java.time.Instant

/** A stretch of the window with no blood sugar in it, worth asking the source about again. */
data class GlucoseGap(val from: Instant, val to: Instant) {
    val duration: Duration
        get() = Duration.between(from, to)
}

/**
 * Where the blood sugar trace is missing, so the source can be asked again.
 *
 * ## Why this is needed at all
 *
 * Glucose is cached a calendar day at a time, and only *today* is re-read on an
 * ordinary refresh. That is right for a day that is finished and wrong for one
 * that was never finished properly: a monitor out of Bluetooth range at four in
 * the afternoon writes those readings to Health Connect hours later, by which
 * time the app has stopped asking about that day and the hole is permanent. It
 * shows on the chart as a break in the line, and no amount of pulling to refresh
 * fills it.
 *
 * So the holes themselves become the query. Anywhere the trace stops for longer
 * than it plausibly should, the source is asked about that span again, and the
 * unique index on `externalId` throws away everything that came back already
 * known.
 *
 * ## Why a fixed threshold here, and not the series' own cadence
 *
 * [com.prestondihle.healthtracker.ui.components.SeriesGaps] judges a break
 * against the median spacing of the series, because it is deciding whether to
 * *draw* a line and a fingerstick user's five-hour spacing is not a dropout.
 * This is deciding whether to spend a query, and the two questions have
 * different right answers: a reader taking three fingersticks a day has hours of
 * genuine emptiness that no re-read will ever fill, and judging by their cadence
 * would leave a continuous monitor's four-hour outage looking unremarkable.
 * [MIN_GAP] is set where a continuous monitor is unambiguously not reporting and
 * a manual logger has no readings to be missing anyway.
 */
object GlucoseGaps {

    /**
     * How far back the trace is checked.
     *
     * Three days, matching the widest window the glucose chart offers: a hole
     * outside what can be looked at is not one anybody is going to notice, and
     * Health Connect's own retention makes the far past a poor bet regardless.
     */
    const val WINDOW_HOURS = 72L

    /**
     * How long a hole has to be before it is worth a query.
     *
     * Forty-five minutes is several missed samples for a monitor writing every
     * five, and comfortably longer than the stutter of one going briefly out of
     * range. Shorter than this and every ordinary sensor hiccup would trigger a
     * read that returns nothing.
     */
    val MIN_GAP: Duration = Duration.ofMinutes(45)

    /**
     * Slack added to each end of a span before it is read.
     *
     * A gap is bounded by the readings either side of it, and asking for exactly
     * that span leans on both the source and the cache agreeing to the
     * millisecond about where a reading sits. The padding costs one already-known
     * record per end and removes the question.
     */
    val PADDING: Duration = Duration.ofMinutes(5)

    /**
     * Most separate spans read before the whole lot is swept in one query.
     *
     * A trace shot through with more holes than this is not a trace with a few
     * gaps in it; it is one that was barely recorded, and six round trips to
     * discover that is worse than one wide one that also picks up whatever fell
     * between them.
     */
    const val MAX_SPANS = 6

    /**
     * The spans of `[from, to]` worth re-reading, in time order.
     *
     * [readingTimes] need not be sorted or clipped. Empty when the trace is
     * continuous enough to leave alone -- which is the common case, and the one
     * that has to cost nothing.
     */
    fun spans(readingTimes: List<Instant>, from: Instant, to: Instant): List<GlucoseGap> {
        if (!from.isBefore(to)) return emptyList()

        val inWindow =
            readingTimes.filter { !it.isBefore(from) && !it.isAfter(to) }.sorted()

        // The edges count as much as the middle. A monitor that stopped an hour
        // ago leaves its hole at the right-hand end, where there is no later
        // reading to bound it -- and that is the freshest, most fillable gap
        // there is.
        val edges = listOf(from) + inWindow + listOf(to)
        val gaps =
            edges.zipWithNext { earlier, later -> GlucoseGap(earlier, later) }
                .filter { it.duration >= MIN_GAP }

        if (gaps.isEmpty()) return emptyList()

        val padded =
            if (gaps.size <= MAX_SPANS) gaps
            else listOf(GlucoseGap(gaps.first().from, gaps.last().to))

        return padded.map {
            GlucoseGap(
                from = maxOf(it.from.minus(PADDING), from),
                to = minOf(it.to.plus(PADDING), to),
            )
        }
    }
}
