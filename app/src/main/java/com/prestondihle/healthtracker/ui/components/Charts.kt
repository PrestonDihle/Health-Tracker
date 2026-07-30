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
        val centre = index * stepX + stepX / 2f
        drawText(
            textLayoutResult = laid,
            topLeft =
                Offset(
                    x = (centre - laid.size.width / 2f).coerceIn(0f, plotWidth - laid.size.width),
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

    val dates = series.firstOrNull()?.points?.map { it.date }.orEmpty()
    val values = series.flatMap { line -> line.points.mapNotNull { it.value } }
    if (values.isEmpty()) {
        EmptyPlot(modifier)
        return
    }

    val minVal = minY ?: values.min()
    val maxVal = maxY ?: values.max()
    val range = if (maxVal - minVal == 0f) 1f else maxVal - minVal

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val plotHeight = size.height - DATE_GUTTER.toPx()
            if (plotHeight <= 0f) return@Canvas
            val stepX = size.width / dates.size.coerceAtLeast(1)

            fun mapY(value: Float): Float = plotHeight - ((value - minVal) / range) * plotHeight

            // Points sit at the centre of their day's slot, matching where the
            // bar charts put theirs so a line and a bar chart of the same range
            // line up column for column.
            fun mapX(index: Int): Float = index * stepX + stepX / 2f

            goalLine?.let {
                val y = mapY(it)
                if (y in 0f..plotHeight) {
                    drawLine(
                        color = goalColor,
                        start = Offset(0f, y),
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

    val totals = bars.map { bar -> bar.segments.sum() }
    if (bars.isEmpty() || totals.all { it <= 0f }) {
        EmptyPlot(modifier)
        return
    }

    val maxVal = maxOf(totals.max(), goalLine ?: 0f).takeIf { it > 0f } ?: 1f

    Canvas(modifier = modifier) {
        val plotHeight = size.height - DATE_GUTTER.toPx()
        if (plotHeight <= 0f) return@Canvas
        val stepX = size.width / bars.size
        val barWidth = (stepX * 0.6f).coerceAtMost(48.dp.toPx())

        fun mapY(value: Float): Float = plotHeight - (value / maxVal) * plotHeight

        goalLine?.let {
            val y = mapY(it)
            if (y in 0f..plotHeight) {
                drawLine(
                    color = goalColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f),
                )
            }
        }

        bars.forEachIndexed { index, bar ->
            val startX = index * stepX + (stepX - barWidth) / 2
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

    val values = days.mapNotNull { it.value }
    if (values.isEmpty()) {
        EmptyPlot(modifier)
        return
    }

    val minVal = minY ?: 0f // Usually bars start at 0
    val maxVal = maxY ?: values.max()
    val range = if (maxVal - minVal == 0f) 1f else maxVal - minVal

    Canvas(modifier = modifier) {
        val plotHeight = size.height - DATE_GUTTER.toPx()
        if (plotHeight <= 0f) return@Canvas
        val stepX = size.width / days.size
        // Proportional to the slot, but capped: one or two days of data would
        // otherwise draw a bar half the chart wide, reading as a solid block
        // rather than a bar. The cap keeps sparse data looking like a column.
        val barWidth = (stepX * 0.6f).coerceAtMost(48.dp.toPx())

        fun mapY(value: Float): Float = plotHeight - ((value - minVal) / range) * plotHeight

        goalLine?.let {
            val y = mapY(it)
            if (y in 0f..plotHeight) {
                drawLine(
                    color = goalColor,
                    start = Offset(0f, y),
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
            val startX = index * stepX + (stepX - barWidth) / 2
            drawRect(
                color = barColor,
                topLeft = Offset(startX, y),
                size = Size(barWidth, plotHeight - y),
            )
        }

        drawDateAxis(
            dates = days.map { it.date },
            plotWidth = size.width,
            plotHeight = plotHeight,
            stepX = stepX,
            textMeasurer = textMeasurer,
            color = axisTextColor,
        )
    }
}
