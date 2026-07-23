package com.prestondihle.healthtracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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

private const val MAX_RENDERED_POINTS = 240
private val AXIS_LABEL_SIZE = 10.sp

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
                    )
                }
            }
        }

        Legend(series = series, leftAxis = leftAxis, rightAxis = rightAxis)
    }
}

@Composable
private fun Legend(series: List<ChartSeries>, leftAxis: AxisSpec, rightAxis: AxisSpec?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        series.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(10.dp)) { drawCircle(item.color) }
                Spacer(Modifier.width(4.dp))
                val unit =
                    if (item.axis == ChartAxis.LEFT) leftAxis.label else rightAxis?.label.orEmpty()
                Text(
                    text = if (unit.isBlank()) item.label else "${item.label} ($unit)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Widens the axis if any of its series fall outside the configured bounds. */
private fun AxisSpec.expandedFor(series: List<ChartSeries>, axis: ChartAxis): AxisSpec {
    val values = series.filter { it.axis == axis }.flatMap { it.points }.map { it.value }
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
) {
    val leftGutter = 36.dp.toPx()
    val rightGutter = if (rightAxis != null) 36.dp.toPx() else 8.dp.toPx()
    val bottomGutter = 18.dp.toPx()
    val topPad = 6.dp.toPx()

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

    // Horizontal gridlines and left-axis labels, four rows.
    val rows = 4
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

    // Time ticks every six hours, anchored to the window end.
    val formatter = DateTimeFormatter.ofPattern("h a")
    val totalHours = Duration.between(windowStart, windowEnd).toHours()
    val tickHours = if (totalHours <= 12) 3L else 6L
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

    for (item in series) {
        val axis = if (item.axis == ChartAxis.LEFT) leftAxis else rightAxis ?: leftAxis
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
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
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
