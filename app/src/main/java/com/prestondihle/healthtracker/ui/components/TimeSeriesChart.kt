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

data class ChartSeries(
    val label: String,
    val points: List<TimePoint>,
    val color: Color,
    val axis: ChartAxis = ChartAxis.LEFT,
    /** Off for dense series such as CGM, where dots become a smear. */
    val showPoints: Boolean = true,
    /** Dashed marks a projection rather than something measured. */
    val dashed: Boolean = false,
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
)

private const val MAX_RENDERED_POINTS = 240
private val AXIS_LABEL_SIZE = 10.sp

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

    val hasData = series.any { it.points.isNotEmpty() }

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
                        series = series,
                        leftAxis = leftAxis.expandedFor(series, ChartAxis.LEFT),
                        rightAxis = rightAxis?.expandedFor(series, ChartAxis.RIGHT),
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

        Legend(series = series, leftAxis = leftAxis, rightAxis = rightAxis)
    }
}

/**
 * Series names with their units, wrapping onto as many rows as it takes.
 *
 * A series drawn against a scale of its own also states that scale's range here,
 * since that is the only place its numbers appear at all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(series: List<ChartSeries>, leftAxis: AxisSpec, rightAxis: AxisSpec?) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        series.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A short stroke rather than a dot, so a dashed projection is
                // identifiable in the legend and not just on the plot.
                Canvas(modifier = Modifier.width(14.dp).height(8.dp)) {
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
                Spacer(Modifier.width(4.dp))
                val scale = item.scale
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
private fun AxisSpec.expandedFor(series: List<ChartSeries>, axis: ChartAxis): AxisSpec =
    expandedFor(series.filter { it.scale == null && it.axis == axis })

private fun AxisSpec.expandedFor(series: List<ChartSeries>): AxisSpec {
    val values = series.flatMap { it.points }.map { it.value }
    if (values.isEmpty()) return this
    val lo = minOf(min, values.min())
    val hi = maxOf(max, values.max())
    return copy(min = lo, max = if (hi > lo) hi else lo + 1f)
}

private fun List<TimePoint>.downsampled(): List<TimePoint> {
    if (size <= MAX_RENDERED_POINTS) return this
    val bucket = ceil(size.toDouble() / MAX_RENDERED_POINTS).toInt()
    return chunked(bucket) { chunk ->
        TimePoint(
            time = chunk[chunk.size / 2].time,
            value = chunk.map { it.value }.average().toFloat(),
        )
    }
}

private fun DrawScope.drawChart(
    series: List<ChartSeries>,
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
            totalHours <= 12 -> 3L to DateTimeFormatter.ofPattern("h a")
            totalHours <= 36 -> 6L to DateTimeFormatter.ofPattern("h a")
            totalHours <= 24 * 4 -> 24L to DateTimeFormatter.ofPattern("EEE")
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
    leftAxis.threshold?.let { drawThreshold(yFor(it, leftAxis), plotLeft, plotRight) }
    rightAxis?.threshold?.let { drawThreshold(yFor(it, rightAxis), plotLeft, plotRight) }

    // Rightmost edge of the last caption drawn, so a later one that would collide
    // with it can be dropped. Meals cluster -- three in an evening is normal --
    // and over a 48-hour window their captions otherwise overprint into a smear.
    // The rules themselves are always drawn; only the text is rationed.
    var lastLabelEnd = Float.NEGATIVE_INFINITY

    for (marker in markers.sortedBy { it.time }) {
        val x = xFor(marker.time)
        if (x !in plotLeft..plotRight) continue
        drawLine(
            color = axisTextColor,
            start = androidx.compose.ui.geometry.Offset(x, plotTop),
            end = androidx.compose.ui.geometry.Offset(x, plotBottom),
            // A solid rule reads as a real instant; the dash says "projected".
            strokeWidth = if (marker.dashed) 1.dp.toPx() else 1.5.dp.toPx(),
            pathEffect =
                if (marker.dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f) else null,
        )

        val caption = marker.label ?: continue
        val laid = textMeasurer.measure(caption, labelStyle)
        // Centred on the rule, but never pushed outside the canvas -- a marker
        // near an edge would otherwise lose half its caption.
        val left = (x - laid.size.width / 2f).coerceIn(0f, size.width - laid.size.width)
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

    for (item in series) {
        val axis =
            item.scale?.expandedFor(listOf(item))
                ?: if (item.axis == ChartAxis.LEFT) leftAxis else rightAxis ?: leftAxis
        val points =
            item.points
                .filter { !it.time.isBefore(windowStart) && !it.time.isAfter(windowEnd) }
                .sortedBy { it.time }
                .downsampled()
        if (points.isEmpty()) continue

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
                        androidx.compose.ui.geometry.Offset(xFor(it.time), yFor(it.value, axis)),
                )
            }
        }
    }
}

private fun DrawScope.drawThreshold(y: Float, left: Float, right: Float) {
    drawLine(
        color = Color(0xFFA30000),
        start = androidx.compose.ui.geometry.Offset(left, y),
        end = androidx.compose.ui.geometry.Offset(right, y),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f),
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
