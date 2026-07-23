package com.prestondihle.healthtracker.domain

import java.time.Duration
import java.time.Instant

/**
 * A half-open time range, `[start, end)`.
 *
 * Half-open matters: a feeding window ending at 20:00 and a fast starting at
 * 20:00 must not be treated as overlapping by one instant.
 */
data class Interval(val start: Instant, val end: Instant) {
    val isEmpty: Boolean
        get() = !start.isBefore(end)

    val seconds: Long
        get() = if (isEmpty) 0L else Duration.between(start, end).seconds

    fun clampTo(bounds: Interval): Interval? {
        val s = maxOf(start, bounds.start)
        val e = minOf(end, bounds.end)
        return if (s.isBefore(e)) Interval(s, e) else null
    }
}

/** Sorts, drops empties, and merges anything overlapping or touching. */
fun List<Interval>.normalized(): List<Interval> {
    val sorted = filterNot { it.isEmpty }.sortedBy { it.start }
    if (sorted.isEmpty()) return emptyList()

    val merged = mutableListOf(sorted.first())
    for (next in sorted.drop(1)) {
        val last = merged.last()
        if (!next.start.isAfter(last.end)) {
            merged[merged.lastIndex] = Interval(last.start, maxOf(last.end, next.end))
        } else {
            merged.add(next)
        }
    }
    return merged
}

val List<Interval>.totalSeconds: Long
    get() = normalized().sumOf { it.seconds }

/** Set intersection, by a linear sweep over both normalized lists. */
fun List<Interval>.intersectWith(other: List<Interval>): List<Interval> {
    val a = normalized()
    val b = other.normalized()
    val out = mutableListOf<Interval>()

    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        val start = maxOf(a[i].start, b[j].start)
        val end = minOf(a[i].end, b[j].end)
        if (start.isBefore(end)) out.add(Interval(start, end))
        if (a[i].end.isBefore(b[j].end)) i++ else j++
    }
    return out
}

/** Set difference: everything in this list not covered by [other]. */
fun List<Interval>.minusIntervals(other: List<Interval>): List<Interval> {
    val cuts = other.normalized()
    if (cuts.isEmpty()) return normalized()

    val out = mutableListOf<Interval>()
    for (span in normalized()) {
        var cursor = span.start
        for (cut in cuts) {
            if (!cut.end.isAfter(cursor)) continue
            if (!cut.start.isBefore(span.end)) break
            if (cut.start.isAfter(cursor)) out.add(Interval(cursor, minOf(cut.start, span.end)))
            cursor = maxOf(cursor, cut.end)
            if (!cursor.isBefore(span.end)) break
        }
        if (cursor.isBefore(span.end)) out.add(Interval(cursor, span.end))
    }
    return out
}
