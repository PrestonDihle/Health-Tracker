package com.prestondihle.healthtracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * One day's value, or null on a day with nothing recorded.
 *
 * Day-indexed charts take these rather than a bare list of numbers so a bar or
 * point can be attributed to a date. Filtering the empty days out instead --
 * which is what these charts used to be fed -- silently shifts every later day
 * one slot left, so a fortnight with two blank days drew the wrong dates against
 * the wrong values.
 */
data class DayPoint(val date: LocalDate, val value: Float?)

/** How a line is stroked, for telling series apart without relying on colour alone. */
enum class LineStyle {
    SOLID,
    DASHED,
    DOTTED,
}

/** One line in a [MultiLineChart]. */
data class LineSeries(
    val label: String,
    val points: List<DayPoint>,
    val color: Color,
    val style: LineStyle = LineStyle.SOLID,
)

private val DATE_LABEL_SIZE = 9.sp
private val DATE_FORMAT = DateTimeFormatter.ofPattern("M/d")

/** Height reserved under the plot for date labels. */
private val DATE_GUTTER = 14.dp

/** Minimum blank space between two date labels before one of them is dropped. */
private val DATE_LABEL_GAP = 6.dp

/**
 * Width reserved at the left for value labels.
 *
 * Fixed rather than measured so every chart on the Trends screen indents its
 * plot by the same amount -- a per-chart width would step each plot in and out
 * depending on how many digits its numbers happened to have.
 */
private val VALUE_GUTTER = 30.dp

/** Roughly how many gridlines to aim for; the exact count follows the chosen step. */
private const val VALUE_TICKS = 4

/** An axis snapped to round numbers, with the interval between its gridlines. */
private data class AxisTicks(val min: Float, val max: Float, val step: Float)

/**
 * Widens an axis to the nearest round numbers.
 *
 * Dividing a raw data span into four gives ticks like 178, 179.4, 180.9, 182.3,
 * which round to a sequence that visibly skips a number. Choosing the interval
 * first -- from the 1, 2, 2.5, 5 series that people actually count in -- and then
 * snapping the bounds outward to multiples of it puts every gridline on a value
 * worth printing.
 *
 * The axis can only grow, never crop, so no reading is ever pushed off the plot.
 */
private fun niceTicks(min: Float, max: Float): AxisTicks {
    val span = (max - min).takeIf { it > 0f } ?: return AxisTicks(min, min + 1f, 1f)
    val rough = span / (VALUE_TICKS - 1)
    val magnitude = 10.0.pow(floor(log10(rough.toDouble())))
    val step =
        when (rough / magnitude) {
            in 0.0..1.0 -> 1.0
            in 1.0..2.0 -> 2.0
            in 2.0..2.5 -> 2.5
            in 2.5..5.0 -> 5.0
            else -> 10.0
        } * magnitude

    return AxisTicks(
        min = (floor(min / step) * step).toFloat(),
        max = (ceil(max / step) * step).toFloat(),
        step = step.toFloat(),
    )
}

/**
 * Value labels and their gridlines down the left of a day-indexed plot.
 *
 * Without these a trends chart shows a shape but no magnitude: the reader can
 * see that steps went up without being able to say from what to what. The
 * gridlines are drawn under the data so a bar sits on top of them.
 *
 * Labels are formatted by size rather than to a fixed precision -- 12500 steps
 * and 1.4 mmol want different treatment, and one rule for both would print
 * either "12500.0" or "1".
 */
private fun DrawScope.drawValueAxis(
    axis: AxisTicks,
    plotWidth: Float,
    plotHeight: Float,
    textMeasurer: TextMeasurer,
    color: Color,
    gridColor: Color,
) {
    if (plotHeight <= 0f || axis.step <= 0f) return
    val style = TextStyle(fontSize = DATE_LABEL_SIZE, color = color)
    val span = axis.max - axis.min
    if (span <= 0f) return

    var value = axis.min
    while (value <= axis.max + axis.step / 100f) {
        val y = plotHeight - ((value - axis.min) / span) * plotHeight

        drawLine(
            color = gridColor,
            start = Offset(VALUE_GUTTER.toPx(), y),
            end = Offset(plotWidth, y),
            strokeWidth = 1f,
        )

        val laid = textMeasurer.measure(formatAxisValue(value, axis.step), style)
        drawText(
            textLayoutResult = laid,
            topLeft =
                Offset(
                    // Right-aligned against the gutter so the digits line up
                    // however many of them there are.
                    x = (VALUE_GUTTER.toPx() - 4.dp.toPx() - laid.size.width).coerceAtLeast(0f),
                    // Nudged onto its line, and kept inside the canvas at the ends.
                    y = (y - laid.size.height / 2f).coerceIn(0f, plotHeight - laid.size.height),
                ),
        )
        value += axis.step
    }
}

/**
 * Enough precision to tell two neighbouring gridlines apart, and no more.
 *
 * Driven by the step rather than the value, so every label on an axis carries
 * the same number of decimals. Thousands are abbreviated once the step is coarse
 * enough that the dropped digits were zeroes anyway -- 12500 steps reads as
 * "12.5k" without losing anything, while a heart rate never gets the treatment.
 */
private fun formatAxisValue(value: Float, step: Float): String =
    when {
        // Zero is zero on any scale; "0.0k" is just noise.
        value == 0f -> "0"
        step >= 1000f -> "%.0fk".format(value / 1000f)
        step >= 100f && kotlin.math.abs(value) >= 1000f -> "%.1fk".format(value / 1000f)
        step >= 1f -> "%.0f".format(value)
        step >= 0.1f -> "%.1f".format(value)
        else -> "%.2f".format(value)
    }

private fun LineStyle.pathEffect(): PathEffect? =
    when (this) {
        LineStyle.SOLID -> null
        LineStyle.DASHED -> PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
        // A near-zero "on" length with a round cap draws as dots rather than
        // very short dashes, which is what distinguishes it from DASHED.
        LineStyle.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(1f, 10f), 0f)
    }

/**
 * Date labels along the bottom of a day-indexed plot.
 *
 * Labels thin out to whatever fits: a fortnight can carry one per day, ninety
 * days cannot, and drawing them all would leave a smear. Kept anchored to the
 * newest day so the right-hand end -- the one being read -- is always labelled,
 * however the stride falls out.
 */
private fun DrawScope.drawDateAxis(
    dates: List<LocalDate>,
    plotLeft: Float,
    plotWidth: Float,
    plotHeight: Float,
    stepX: Float,
    textMeasurer: TextMeasurer,
    color: Color,
) {
    if (dates.isEmpty()) return
    val style = TextStyle(fontSize = DATE_LABEL_SIZE, color = color)
    val sample = textMeasurer.measure(DATE_FORMAT.format(dates.last()), style)
    val stride =
        ceil((sample.size.width + DATE_LABEL_GAP.toPx()) / stepX).toInt().coerceAtLeast(1)

    for (index in dates.indices.reversed() step stride) {
        val laid = textMeasurer.measure(DATE_FORMAT.format(dates[index]), style)
        val centre = plotLeft + index * stepX + stepX / 2f
        drawText(
            textLayoutResult = laid,
            topLeft =
                Offset(
                    x =
                        (centre - laid.size.width / 2f)
                            .coerceIn(0f, (plotWidth - laid.size.width).coerceAtLeast(0f)),
                    y = plotHeight + 3.dp.toPx(),
                ),
        )
    }
}

@Composable
private fun EmptyPlot(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text("No data yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Several day-indexed lines on one scale, told apart by stroke pattern.
 *
 * Missing days are joined across rather than broken at, and only real readings
 * get a dot. A series measured every few days would otherwise render as a
 * scatter of isolated points with no trend visible at all; the dots are what
 * still distinguishes a reading from the interpolation between two.
 */
@Composable
fun MultiLineChart(
    series: List<LineSeries>,
    modifier: Modifier = Modifier,
    goalLine: Float? = null,
    goalColor: Color = MaterialTheme.colorScheme.error,
    minY: Float? = null,
    maxY: Float? = null,
    showLegend: Boolean = true,
) {
    val textMeasurer = rememberTextMeasurer()
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    val dates = series.firstOrNull()?.points?.map { it.date }.orEmpty()
    val values = series.flatMap { line -> line.points.mapNotNull { it.value } }
    if (values.isEmpty()) {
        EmptyPlot(modifier)
        return
    }

    // Snapped so the gridlines land on round numbers; the data is mapped against
    // the snapped bounds too, or the lines would not sit on their own gridlines.
    val axis = niceTicks(minY ?: values.min(), maxY ?: values.max())
    val minVal = axis.min
    val range = if (axis.max - axis.min == 0f) 1f else axis.max - axis.min

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val plotHeight = size.height - DATE_GUTTER.toPx()
            if (plotHeight <= 0f) return@Canvas
            val plotLeft = VALUE_GUTTER.toPx()
            val plotWidth = size.width - plotLeft
            if (plotWidth <= 0f) return@Canvas
            val stepX = plotWidth / dates.size.coerceAtLeast(1)

            fun mapY(value: Float): Float = plotHeight - ((value - minVal) / range) * plotHeight

            // Points sit at the centre of their day's slot, matching where the
            // bar charts put theirs so a line and a bar chart of the same range
            // line up column for column.
            fun mapX(index: Int): Float = plotLeft + index * stepX + stepX / 2f

            drawValueAxis(
                axis = axis,
                plotWidth = size.width,
                plotHeight = plotHeight,
                textMeasurer = textMeasurer,
                color = axisTextColor,
                gridColor = gridColor,
            )

            goalLine?.let {
                val y = mapY(it)
                if (y in 0f..plotHeight) {
                    drawLine(
                        color = goalColor,
                        start = Offset(plotLeft, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f),
                    )
                }
            }

            for (line in series) {
                val recorded =
                    line.points.mapIndexedNotNull { index, point ->
                        point.value?.let { index to it }
                    }
                if (recorded.isEmpty()) continue

                if (recorded.size > 1) {
                    val path = Path()
                    recorded.forEachIndexed { position, (index, value) ->
                        val x = mapX(index)
                        val y = mapY(value)
                        if (position == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = line.color,
                        style =
                            Stroke(
                                width = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                                pathEffect = line.style.pathEffect(),
                            ),
                    )
                }

                recorded.forEach { (index, value) ->
                    drawCircle(
                        color = line.color,
                        radius = 3.dp.toPx(),
                        center = Offset(mapX(index), mapY(value)),
                    )
                }
            }

            drawDateAxis(
                dates = dates,
                plotLeft = plotLeft,
                plotWidth = size.width,
                plotHeight = plotHeight,
                stepX = stepX,
                textMeasurer = textMeasurer,
                color = axisTextColor,
            )
        }

        if (showLegend && series.size > 1) LineLegend(series)
    }
}

/**
 * Swatches drawn in each series' own stroke pattern.
 *
 * A dot would say nothing about which line is which when the lines are
 * distinguished by dash pattern, so the swatch is a short length of the line
 * itself.
 */
@Composable
private fun LineLegend(series: List<LineSeries>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        series.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.width(18.dp).height(8.dp)) {
                    drawLine(
                        color = line.color,
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = line.style.pathEffect(),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    line.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A single day-indexed line. */
@Composable
fun LineChart(
    days: List<DayPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    goalLine: Float? = null,
    goalColor: Color = MaterialTheme.colorScheme.error,
    minY: Float? = null,
    maxY: Float? = null,
) {
    MultiLineChart(
        series = listOf(LineSeries(label = "", points = days, color = lineColor)),
        modifier = modifier,
        goalLine = goalLine,
        goalColor = goalColor,
        minY = minY,
        maxY = maxY,
        showLegend = false,
    )
}

/** One bar's segments, bottom to top, in the same order as the series colors. */
data class StackedBar(val date: LocalDate, val segments: List<Float>)

/**
 * Bars split into stacked segments sharing one scale.
 *
 * Segment order is fixed across bars so a band stays in the same place from day
 * to day; a bar with fewer values than there are colors simply stops early
 * rather than shifting the ones above it.
 */
@Composable
fun StackedBarChart(
    bars: List<StackedBar>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    goalLine: Float? = null,
    goalColor: Color = MaterialTheme.colorScheme.error,
) {
    val textMeasurer = rememberTextMeasurer()
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    val totals = bars.map { bar -> bar.segments.sum() }
    if (bars.isEmpty() || totals.all { it <= 0f }) {
        EmptyPlot(modifier)
        return
    }

    val axis = niceTicks(0f, maxOf(totals.max(), goalLine ?: 0f).takeIf { it > 0f } ?: 1f)
    val maxVal = axis.max

    Canvas(modifier = modifier) {
        val plotHeight = size.height - DATE_GUTTER.toPx()
        if (plotHeight <= 0f) return@Canvas
        val plotLeft = VALUE_GUTTER.toPx()
        val plotWidth = size.width - plotLeft
        if (plotWidth <= 0f) return@Canvas
        val stepX = plotWidth / bars.size
        val barWidth = (stepX * 0.6f).coerceAtMost(48.dp.toPx())

        fun mapY(value: Float): Float = plotHeight - (value / maxVal) * plotHeight

        drawValueAxis(
            axis = axis,
            plotWidth = size.width,
            plotHeight = plotHeight,
            textMeasurer = textMeasurer,
            color = axisTextColor,
            gridColor = gridColor,
        )

        goalLine?.let {
            val y = mapY(it)
            if (y in 0f..plotHeight) {
                drawLine(
                    color = goalColor,
                    start = Offset(plotLeft, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f),
                )
            }
        }

        bars.forEachIndexed { index, bar ->
            val startX = plotLeft + index * stepX + (stepX - barWidth) / 2
            // Stack upward from the baseline, tracking the running height so
            // each segment sits on the one below it.
            var runningValue = 0f
            bar.segments.forEachIndexed { segmentIndex, value ->
                if (value <= 0f) return@forEachIndexed
                val bottom = mapY(runningValue)
                val top = mapY(runningValue + value)
                drawRect(
                    color = colors.getOrElse(segmentIndex) { colors.last() },
                    topLeft = Offset(startX, top),
                    size = Size(barWidth, bottom - top),
                )
                runningValue += value
            }
        }

        drawDateAxis(
            dates = bars.map { it.date },
            plotLeft = plotLeft,
            plotWidth = size.width,
            plotHeight = plotHeight,
            stepX = stepX,
            textMeasurer = textMeasurer,
            color = axisTextColor,
        )
    }
}

@Composable
fun BarChart(
    days: List<DayPoint>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    goalLine: Float? = null,
    goalColor: Color = MaterialTheme.colorScheme.error,
    minY: Float? = null,
    maxY: Float? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    val values = days.mapNotNull { it.value }
    if (values.isEmpty()) {
        EmptyPlot(modifier)
        return
    }

    // Bars start at 0 unless told otherwise, then both ends snap to round numbers.
    val axis = niceTicks(minY ?: 0f, maxOf(maxY ?: values.max(), goalLine ?: Float.MIN_VALUE))
    val minVal = axis.min
    val range = if (axis.max - axis.min == 0f) 1f else axis.max - axis.min

    Canvas(modifier = modifier) {
        val plotHeight = size.height - DATE_GUTTER.toPx()
        if (plotHeight <= 0f) return@Canvas
        val plotLeft = VALUE_GUTTER.toPx()
        val plotWidth = size.width - plotLeft
        if (plotWidth <= 0f) return@Canvas
        val stepX = plotWidth / days.size
        // Proportional to the slot, but capped: one or two days of data would
        // otherwise draw a bar half the chart wide, reading as a solid block
        // rather than a bar. The cap keeps sparse data looking like a column.
        val barWidth = (stepX * 0.6f).coerceAtMost(48.dp.toPx())

        fun mapY(value: Float): Float = plotHeight - ((value - minVal) / range) * plotHeight

        drawValueAxis(
            axis = axis,
            plotWidth = size.width,
            plotHeight = plotHeight,
            textMeasurer = textMeasurer,
            color = axisTextColor,
            gridColor = gridColor,
        )

        goalLine?.let {
            val y = mapY(it)
            if (y in 0f..plotHeight) {
                drawLine(
                    color = goalColor,
                    start = Offset(plotLeft, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f),
                )
            }
        }

        days.forEachIndexed { index, day ->
            // A day with nothing recorded leaves its slot blank rather than
            // drawing a zero bar: "not logged" and "logged none" are different
            // statements, and the date axis keeps the gap attributable.
            val value = day.value ?: return@forEachIndexed
            val y = mapY(value)
            val startX = plotLeft + index * stepX + (stepX - barWidth) / 2
            drawRect(
                color = barColor,
                topLeft = Offset(startX, y),
                size = Size(barWidth, plotHeight - y),
            )
        }

        drawDateAxis(
            dates = days.map { it.date },
            plotLeft = plotLeft,
            plotWidth = size.width,
            plotHeight = plotHeight,
            stepX = stepX,
            textMeasurer = textMeasurer,
            color = axisTextColor,
        )
    }
}
