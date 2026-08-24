package com.prestondihle.healthtracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

data class TimePoint(val time: Instant, val value: Float)

enum class ChartAxis {
    LEFT,
    RIGHT,
}

/**
 * How a series is drawn.
 *
 * [BAR] is for a quantity accumulated *over* an interval rather than measured
 * *at* an instant. Steps per hour joined into a line would claim a continuous
 * rate that was never measured, and would put a value halfway between two hours
 * at a time when nothing was counted at all; a column says "this much, in this
 * hour", which is all the data actually supports.
 */
enum class SeriesKind {
    LINE,
    BAR,
}

data class ChartSeries(
    val label: String,
    val points: List<TimePoint>,
    val color: Color,
    val axis: ChartAxis = ChartAxis.LEFT,
    /** Off for dense series such as CGM, where dots become a smear. */
    val showPoints: Boolean = true,
    /** Dashed marks a projection rather than something measured. */
    val dashed: Boolean = false,
    val kind: SeriesKind = SeriesKind.LINE,
    /**
     * Break the line where the series stops being measured, instead of joining
     * across the gap. See [SeriesGaps].
     *
     * For measured series only. A modelled curve is a continuous function
     * sampled evenly and has no gaps to find; switching this on for one would
     * only ever misread its own sampling interval.
     */
    val breakOnGaps: Boolean = false,
    /**
     * The interval one bar covers, starting at its point's timestamp.
     *
     * Required in practice for [SeriesKind.BAR], because the gaps between points
     * cannot be trusted to reveal it: an aggregator reports nothing at all for an
     * interval it has no records in, so a night of no walking arrives as a hole
     * rather than as zeroes, and inferring the width from the spacing either side
     * of that hole would draw one bar across the whole night.
     */
    val barWidth: Duration? = null,
    /**
     * A scale of this series' own, used instead of [axis].
     *
     * A plot has room to label two axes, and no more -- but six series can easily
     * carry four units. Anything beyond the second unit is mapped by its own
     * spec and has its range printed in the legend instead of down the side,
     * which keeps every line correctly *shaped* even where there is nowhere left
     * to print its numbers.
     */
    val scale: AxisSpec? = null,
)

/**
 * Fixed bounds for one axis. [min] and [max] are a floor and ceiling, not hard
 * limits -- outliers expand the axis so a 250 mg/dL spike is never clipped off
 * the top of the chart.
 */
data class AxisSpec(
    val min: Float,
    val max: Float,
    val label: String,
    val format: (Float) -> String = { it.toInt().toString() },
    val threshold: Float? = null,
    /**
     * Dashed marks a figure that came from outside -- a published clinical
     * threshold. Solid marks one the reader chose. Keeping them apart matters
     * because the two carry very different authority, and a rule drawn the same
     * way in both cases quietly lends one the weight of the other.
     */
    val thresholdDashed: Boolean = true,
    /**
     * A range shaded behind the data, for a target the series is read against.
     *
     * A band rather than two threshold lines because what is being asked is
     * "was it in range", and a filled area answers that at a glance where two
     * rules leave the reader to work out which side of each one the trace is on.
     * Clipped to the plot, so a target reaching past the axis simply runs to the
     * edge.
     */
    val band: ClosedFloatingPointRange<Float>? = null,
)

/**
 * A vertical rule at one moment, optionally captioned.
 *
 * [label] is drawn at the top of the rule rather than beside it, which is the
 * only place on a full-height line that cannot overlap the data. Solid marks a
 * real instant such as now; [dashed] marks a projected one.
 */
data class ChartMarker(
    val time: Instant,
    val label: String? = null,
    val dashed: Boolean = false,
    /**
     * Drawn as chart furniture -- hairline, in the gridline grey -- instead of in
     * the axis colour at full weight.
     *
     * A marker that shares the data's weight *is* read as data. Full-height
     * near-black rules at every meal, each captioned with that meal's carb
     * grams, read as a carbohydrate spike -- and went on reading as one after the
     * carbohydrate curve was switched off, since the rules never belonged to a
     * series in the first place. Anything that marks context rather than
     * reporting a measurement wants this.
     */
    val subdued: Boolean = false,
)

private const val MAX_RENDERED_POINTS = 240
private val AXIS_LABEL_SIZE = 10.sp

/** Bars sit behind the lines, so they have to be readable *through*. */
private const val BAR_ALPHA = 0.45f

/** Light enough that a gridline still shows through the target band. */
private const val BAND_ALPHA = 0.16f

/** Room above the plot for marker captions; without it they would clip. */
private val MARKER_LABEL_HEIGHT = 16.dp

/** Clear space demanded between two marker captions before the later one is dropped. */
private val MARKER_LABEL_GAP = 6.dp

/** Least vertical room a gridline row may have before rows are dropped. */
private val MIN_ROW_SPACING = 28.dp

/**
 * Time-indexed line chart with an independent scale on each side.
 *
 * Points are placed by timestamp rather than by index, so a dense CGM trace and
 * a handful of ketone readings can share one x-axis without the sparse series
 * being stretched across the full width.
 */
@Composable
fun DualAxisTimeChart(
    windowStart: Instant,
    windowEnd: Instant,
    series: List<ChartSeries>,
    leftAxis: AxisSpec,
    modifier: Modifier = Modifier,
    rightAxis: AxisSpec? = null,
    zoneId: ZoneId = ZoneId.systemDefault(),
    /** Vertical rules, used to separate measured past from projected future. */
    markers: List<ChartMarker> = emptyList(),
) {
    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Clipped to the window once, here, rather than again per use. Every axis is
    // then scaled to what is actually on the plot: a series is routinely queried
    // wider than it is drawn -- meals reach back an absorption window, a bucket
    // survives from a previous sync -- and letting a point that is not on the
    // chart set the chart's ceiling flattens every point that is.
    val drawn = series.map { DrawnSeries(it, it.points.inWindow(windowStart, windowEnd)) }
    val hasData = drawn.any { it.points.isNotEmpty() }

    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (!hasData) {
                Text(
                    "No readings in this window",
                    style = MaterialTheme.typography.bodyMedium,
                    color = axisTextColor,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawChart(
                        series = drawn,
                        leftAxis = leftAxis.expandedFor(drawn, ChartAxis.LEFT),
                        rightAxis = rightAxis?.expandedFor(drawn, ChartAxis.RIGHT),
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        textMeasurer = textMeasurer,
                        gridColor = gridColor,
                        axisTextColor = axisTextColor,
                        zoneId = zoneId,
                        markers = markers,
                    )
                }
            }
        }

        Legend(series = drawn, leftAxis = leftAxis, rightAxis = rightAxis)
    }
}

/**
 * A series paired with the part of it that falls inside the plotted window.
 *
 * Both halves are needed: the spec says how to draw, and the clipped points are
 * what every scale, legend caption and empty check has to be computed from.
 */
private data class DrawnSeries(val spec: ChartSeries, val points: List<TimePoint>)

/**
 * Series names with their units, wrapping onto as many rows as it takes.
 *
 * A series drawn against a scale of its own also states that scale's range here,
 * since that is the only place its numbers appear at all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(series: List<DrawnSeries>, leftAxis: AxisSpec, rightAxis: AxisSpec?) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        series.forEach { drawn ->
            val item = drawn.spec
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A short stroke rather than a dot, so a dashed projection is
                // identifiable in the legend and not just on the plot. A bar
                // series gets a block instead, at the same wash it is drawn in.
                Canvas(modifier = Modifier.width(14.dp).height(8.dp)) {
                    if (item.kind == SeriesKind.BAR) {
                        drawRect(color = item.color.copy(alpha = BAR_ALPHA))
                    } else {
                        drawLine(
                            color = item.color,
                            start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
                            end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            pathEffect =
                                if (item.dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)
                                else null,
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                // Expanded exactly as the canvas expands it. Quoting the
                // configured range instead would print a ceiling the plot is not
                // using the moment anything exceeds it -- and this is the only
                // place a self-scaled series' numbers appear at all, so the
                // caption *is* its axis.
                val scale = item.scale?.expandedFor(drawn.points)
                val caption =
                    when {
                        scale != null ->
                            "${item.label} (${scale.format(scale.min)}-" +
                                "${scale.format(scale.max)} ${scale.label})"
                        item.axis == ChartAxis.LEFT && leftAxis.label.isNotBlank() ->
                            "${item.label} (${leftAxis.label})"
                        item.axis == ChartAxis.RIGHT && !rightAxis?.label.isNullOrBlank() ->
                            "${item.label} (${rightAxis?.label})"
                        else -> item.label
                    }
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Widens the axis if any of its series fall outside the configured bounds.
 *
 * Series carrying a [ChartSeries.scale] of their own are excluded: they are not
 * drawn against this axis, so letting a 180 bpm trace stretch the glucose scale
 * would flatten the very line the axis exists to label.
 */
private fun AxisSpec.expandedFor(series: List<DrawnSeries>, axis: ChartAxis): AxisSpec =
    expandedFor(
        series.filter { it.spec.scale == null && it.spec.axis == axis }.flatMap { it.points }
    )

private fun AxisSpec.expandedFor(points: List<TimePoint>): AxisSpec {
    val values = points.map { it.value }
    if (values.isEmpty()) return this
    val lo = minOf(min, values.min())
    val hi = maxOf(max, values.max())
    return copy(min = lo, max = if (hi > lo) hi else lo + 1f)
}

private fun List<TimePoint>.inWindow(start: Instant, end: Instant): List<TimePoint> =
    filter { !it.time.isBefore(start) && !it.time.isAfter(end) }.sortedBy { it.time }

/** How many points have to be merged into one before the plot can draw them all. */
private fun List<TimePoint>.downsampleFactor(): Int =
    if (size <= MAX_RENDERED_POINTS) 1
    else ceil(size.toDouble() / MAX_RENDERED_POINTS).toInt()

private fun List<TimePoint>.downsampled(bucket: Int): List<TimePoint> {
    if (bucket <= 1) return this
    return chunked(bucket) { chunk ->
        TimePoint(
            time = chunk[chunk.size / 2].time,
            value = chunk.map { it.value }.average().toFloat(),
        )
    }
}

/**
 * A last-resort bar width for a series that did not declare one.
 *
 * The most common gap rather than the mean, so a series that is merely missing
 * an interval or two still yields the interval it was sampled at instead of an
 * average pulled wide by the hole.
 */
private fun List<TimePoint>.inferredBarWidth(): Duration {
    val gaps =
        zipWithNext { earlier, later ->
            Duration.between(earlier.time, later.time).toMillis()
        }
            .filter { it > 0 }
    if (gaps.isEmpty()) return Duration.ofHours(1)
    return Duration.ofMillis(gaps.groupingBy { it }.eachCount().maxBy { it.value }.key)
}

/**
 * A grey wash between two values on one axis.
 *
 * Translucent rather than a flat grey so the gridlines stay visible through it
 * -- the band says which region is wanted, and still has to be read against the
 * numbers down the side.
 */
private fun DrawScope.drawBand(
    range: ClosedFloatingPointRange<Float>,
    color: Color,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    yFor: (Float) -> Float,
) {
    if (range.endInclusive <= range.start) return
    val high = yFor(range.endInclusive).coerceIn(top, bottom)
    val low = yFor(range.start).coerceIn(top, bottom)
    if (low - high <= 0f) return
    drawRect(
        color = color.copy(alpha = BAND_ALPHA),
        topLeft = androidx.compose.ui.geometry.Offset(left, high),
        size = androidx.compose.ui.geometry.Size(right - left, low - high),
    )
}

private fun DrawScope.drawChart(
    series: List<DrawnSeries>,
    leftAxis: AxisSpec,
    rightAxis: AxisSpec?,
    windowStart: Instant,
    windowEnd: Instant,
    textMeasurer: TextMeasurer,
    gridColor: Color,
    axisTextColor: Color,
    zoneId: ZoneId,
    markers: List<ChartMarker> = emptyList(),
) {
    val leftGutter = 36.dp.toPx()
    val rightGutter = if (rightAxis != null) 36.dp.toPx() else 8.dp.toPx()
    val bottomGutter = 18.dp.toPx()
    val topPad = if (markers.any { it.label != null }) MARKER_LABEL_HEIGHT.toPx() else 6.dp.toPx()

    val plotLeft = leftGutter
    val plotRight = size.width - rightGutter
    val plotTop = topPad
    val plotBottom = size.height - bottomGutter
    val plotWidth = plotRight - plotLeft
    val plotHeight = plotBottom - plotTop
    if (plotWidth <= 0f || plotHeight <= 0f) return

    val windowMillis = (windowEnd.toEpochMilli() - windowStart.toEpochMilli()).toFloat()
    if (windowMillis <= 0f) return

    val labelStyle = TextStyle(fontSize = AXIS_LABEL_SIZE, color = axisTextColor)

    fun xFor(time: Instant): Float =
        plotLeft + ((time.toEpochMilli() - windowStart.toEpochMilli()) / windowMillis) * plotWidth

    fun yFor(value: Float, axis: AxisSpec): Float {
        val range = (axis.max - axis.min).takeIf { it != 0f } ?: 1f
        return plotBottom - ((value - axis.min) / range) * plotHeight
    }

    // Target bands, underneath everything: they are the backdrop a trace is read
    // against, and drawn over the gridlines they would hide them.
    leftAxis.band?.let {
        drawBand(it, axisTextColor, plotLeft, plotRight, plotTop, plotBottom) { v ->
            yFor(v, leftAxis)
        }
    }
    rightAxis?.band?.let {
        drawBand(it, axisTextColor, plotLeft, plotRight, plotTop, plotBottom) { v ->
            yFor(v, rightAxis)
        }
    }

    // Horizontal gridlines and axis labels.
    //
    // The row count follows the height available rather than being fixed: the
    // dashboard drops its charts to a stub when there is nothing logged, and five
    // rows of labels in 72dp overprint into an unreadable stack. Two gaps is the
    // floor, which still gives a min, a max and a midpoint.
    val rows = (plotHeight / MIN_ROW_SPACING.toPx()).toInt().coerceIn(2, 4)
    for (i in 0..rows) {
        val fraction = i.toFloat() / rows
        val y = plotBottom - fraction * plotHeight
        drawLine(
            color = gridColor,
            start = androidx.compose.ui.geometry.Offset(plotLeft, y),
            end = androidx.compose.ui.geometry.Offset(plotRight, y),
            strokeWidth = 1f,
        )
        val value = leftAxis.min + fraction * (leftAxis.max - leftAxis.min)
        val laid = textMeasurer.measure(leftAxis.format(value), labelStyle)
        drawText(
            textLayoutResult = laid,
            topLeft =
                androidx.compose.ui.geometry.Offset(
                    x = plotLeft - laid.size.width - 4.dp.toPx(),
                    y = y - laid.size.height / 2f,
                ),
        )

        if (rightAxis != null) {
            val rightValue = rightAxis.min + fraction * (rightAxis.max - rightAxis.min)
            val rightLaid = textMeasurer.measure(rightAxis.format(rightValue), labelStyle)
            drawText(
                textLayoutResult = rightLaid,
                topLeft =
                    androidx.compose.ui.geometry.Offset(
                        x = plotRight + 4.dp.toPx(),
                        y = y - rightLaid.size.height / 2f,
                    ),
            )
        }
    }

    // Tick spacing scales with the window, anchored to its end.
    //
    // A fixed six-hour tick reads well across a day and turns into an illegible
    // smear across a fortnight -- fifty-odd labels drawn on top of each other.
    // Both the interval and the format have to widen together: "3 PM" means
    // nothing on a chart spanning three months.
    val totalHours = Duration.between(windowStart, windowEnd).toHours()
    val (tickHours, formatter) =
        when {
            // Ticks step back from *now*, so they rarely land on a round hour.
            // Everything wider than this rounds the label to the hour and accepts
            // being up to half an hour out, which no reading is read that finely
            // against -- but across three hours it is a tenth of the plot, so the
            // shortest window is the one that has to print minutes.
            totalHours <= 4 -> 1L to DateTimeFormatter.ofPattern("h:mm a")
            totalHours <= 12 -> 3L to DateTimeFormatter.ofPattern("h a")
            totalHours <= 36 -> 6L to DateTimeFormatter.ofPattern("h a")
            // A weekday name per day up to a week and a bit: eight of them still
            // fit across a phone, and a week thinned to every third day would
            // label three columns out of seven.
            totalHours <= 24 * 8 -> 24L to DateTimeFormatter.ofPattern("EEE")
            totalHours <= 24 * 20 -> 24L * 3 to DateTimeFormatter.ofPattern("d MMM")
            else -> 24L * 14 to DateTimeFormatter.ofPattern("d MMM")
        }
    var tick = windowEnd
    while (!tick.isBefore(windowStart)) {
        val x = xFor(tick)
        if (x >= plotLeft && x <= plotRight) {
            val label = formatter.format(tick.atZone(zoneId))
            val laid = textMeasurer.measure(label, labelStyle)
            drawText(
                textLayoutResult = laid,
                topLeft =
                    androidx.compose.ui.geometry.Offset(
                        x = (x - laid.size.width / 2f).coerceIn(0f, size.width - laid.size.width),
                        y = plotBottom + 3.dp.toPx(),
                    ),
            )
        }
        tick = tick.minus(Duration.ofHours(tickHours))
    }

    // Threshold lines, drawn under the data.
    leftAxis.threshold?.let { drawThreshold(yFor(it, leftAxis), plotLeft, plotRight, leftAxis.thresholdDashed) }
    rightAxis?.threshold?.let { drawThreshold(yFor(it, rightAxis), plotLeft, plotRight, rightAxis.thresholdDashed) }

    // Bars before rules and lines. A column is a block of ink the width of a
    // whole hour; anything drawn under one is simply gone.
    for (drawn in series.filter { it.spec.kind == SeriesKind.BAR }) {
        val item = drawn.spec
        val inWindow = drawn.points
        if (inWindow.isEmpty()) continue
        val axis =
            item.scale?.expandedFor(inWindow)
                ?: if (item.axis == ChartAxis.LEFT) leftAxis else rightAxis ?: leftAxis

        // Summed rather than averaged when there are too many to draw: these are
        // counts accumulated over an interval, so merging two hours means adding
        // them. Averaging would halve a fortnight of steps.
        val factor = inWindow.downsampleFactor()
        val bars =
            if (factor == 1) inWindow
            else
                inWindow.chunked(factor) { chunk ->
                    TimePoint(chunk.first().time, chunk.sumOf { it.value.toDouble() }.toFloat())
                }

        val spanMillis = (item.barWidth ?: inWindow.inferredBarWidth()).toMillis() * factor
        val baseline = yFor(axis.min, axis)
        for (bar in bars) {
            if (bar.value <= 0f) continue
            val left = xFor(bar.time).coerceIn(plotLeft, plotRight)
            val right =
                xFor(bar.time.plusMillis(spanMillis)).coerceIn(plotLeft, plotRight)
            // Hairline floor: an hour with a handful of steps still has to be
            // visible as something other than an empty slot.
            val width = (right - left - 1f).coerceAtLeast(1f)
            val top = yFor(bar.value, axis).coerceIn(plotTop, baseline)
            drawRect(
                color = item.color.copy(alpha = BAR_ALPHA),
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(width, (baseline - top).coerceAtLeast(1f)),
            )
        }
    }

    // Rightmost edge of the last caption drawn, so a later one that would collide
    // with it can be dropped. Meals cluster -- three in an evening is normal --
    // and over a 48-hour window their captions otherwise overprint into a smear.
    // The rules themselves are always drawn; only the text is rationed.
    var lastLabelEnd = Float.NEGATIVE_INFINITY

    for (marker in markers.sortedBy { it.time }) {
        val x = xFor(marker.time)
        if (x !in plotLeft..plotRight) continue
        drawLine(
            // Subdued rules take the gridline grey, which is what stops a
            // reference line from being read as a reading.
            color = if (marker.subdued) gridColor else axisTextColor,
            start = androidx.compose.ui.geometry.Offset(x, plotTop),
            end = androidx.compose.ui.geometry.Offset(x, plotBottom),
            // A solid rule reads as a real instant; the dash says "projected".
            strokeWidth =
                when {
                    marker.subdued -> 1f
                    marker.dashed -> 1.dp.toPx()
                    else -> 1.5.dp.toPx()
                },
            pathEffect =
                if (marker.dashed || marker.subdued) {
                    PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                } else null,
        )

        val caption = marker.label ?: continue
        val laid = textMeasurer.measure(caption, labelStyle)
        // Centred on the rule, but kept inside the plot -- a marker near the left
        // edge would otherwise slide over the axis numbers, which sit in the
        // gutter at the same height.
        val left =
            (x - laid.size.width / 2f)
                .coerceIn(plotLeft, (size.width - laid.size.width).coerceAtLeast(plotLeft))
        if (left < lastLabelEnd) continue

        drawText(
            textLayoutResult = laid,
            topLeft =
                androidx.compose.ui.geometry.Offset(
                    x = left,
                    y = (plotTop - laid.size.height).coerceAtLeast(0f),
                ),
        )
        lastLabelEnd = left + laid.size.width + MARKER_LABEL_GAP.toPx()
    }

    for (drawn in series.filter { it.spec.kind == SeriesKind.LINE }) {
        val item = drawn.spec
        if (drawn.points.isEmpty()) continue
        val axis =
            item.scale?.expandedFor(drawn.points)
                ?: if (item.axis == ChartAxis.LEFT) leftAxis else rightAxis ?: leftAxis

        // Split before thinning, so the split is judged on the cadence the series
        // was actually recorded at. Downsampling first would widen every spacing
        // by the same factor and leave a real dropout looking ordinary.
        //
        // The thinning factor still comes from the whole series rather than each
        // run, or a trace broken into many runs would be drawn at many different
        // resolutions.
        val bucket = drawn.points.downsampleFactor()
        val runs =
            if (item.breakOnGaps) SeriesGaps.segments(drawn.points) else listOf(drawn.points)

        for (run in runs) {
            val points = run.downsampled(bucket)
            if (points.isEmpty()) continue

            // A run of one is an isolated reading with nothing to join it to.
            // Drawn as a dot rather than dropped: it was still measured.
            if (points.size == 1) {
                drawCircle(
                    color = item.color,
                    radius = 4.dp.toPx(),
                    center =
                        androidx.compose.ui.geometry.Offset(
                            xFor(points.first().time),
                            yFor(points.first().value, axis),
                        ),
                )
                continue
            }

            val path = Path()
            points.forEachIndexed { index, point ->
                val x = xFor(point.time)
                val y = yFor(point.value, axis)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = item.color,
                style =
                    Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect =
                            if (item.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                            else null,
                    ),
            )

            if (item.showPoints) {
                points.forEach {
                    drawCircle(
                        color = item.color,
                        radius = 3.dp.toPx(),
                        center =
                            androidx.compose.ui.geometry.Offset(
                                xFor(it.time),
                                yFor(it.value, axis),
                            ),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawThreshold(y: Float, left: Float, right: Float, dashed: Boolean) {
    drawLine(
        color = Color(0xFFA30000),
        start = androidx.compose.ui.geometry.Offset(left, y),
        end = androidx.compose.ui.geometry.Offset(right, y),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f) else null,
    )
}

/** Convenience wrapper for a chart with a single series and no right axis. */
@Composable
fun SingleSeriesTimeChart(
    windowStart: Instant,
    windowEnd: Instant,
    series: ChartSeries,
    axis: AxisSpec,
    modifier: Modifier = Modifier,
) {
    DualAxisTimeChart(
        windowStart = windowStart,
        windowEnd = windowEnd,
        series = listOf(series),
        leftAxis = axis,
        modifier = modifier.height(180.dp),
    )
}
