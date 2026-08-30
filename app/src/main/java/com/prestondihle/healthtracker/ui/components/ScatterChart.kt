package com.prestondihle.healthtracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prestondihle.healthtracker.domain.LinearFit
import com.prestondihle.healthtracker.domain.ScatterPoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

// ---------------------------------------------------------------------------
// A scatter of one measured quantity against another, on axes that cross at the
// origin wherever the data reaches both sides of it.
//
// Every other chart in this app is indexed by *time*: a day or an instant on the
// x axis, a measurement on the y. This one puts a measurement on both, which is
// the only way to ask whether two things move together rather than merely what
// each of them did. Nothing here is reusable from the day-indexed charts --
// their x axis is a list of dates and the tick maths assumes it.
// ---------------------------------------------------------------------------

private val LABEL_SIZE = 9.sp
private val VALUE_GUTTER = 34.dp
private val BOTTOM_GUTTER = 16.dp

/** Roughly how many gridlines to aim for on each axis. */
private const val TICKS = 4

/** Radius of a plotted point. */
private val DOT_RADIUS = 3.dp

/**
 * How much of the plot the fitted line is allowed to be.
 *
 * It is drawn right across, because a fit truncated to the span of the points
 * reads as a series that stops -- and this line is a model of the whole
 * relationship, including the part where it crosses zero, which is usually
 * outside the cloud.
 */
private val FIT_DASH = floatArrayOf(10f, 8f)

private data class Ticks(val min: Float, val max: Float, val step: Float)

/**
 * An axis snapped outward to round numbers, [Charts.niceTicks]' rule.
 *
 * Duplicated rather than shared because that one is private to the day-indexed
 * file and takes its bounds from a list of `DayPoint`s. The arithmetic is the
 * 1/2/2.5/5 series either way, and the two would only be worth merging if a
 * third caller appeared.
 */
private fun niceTicks(min: Float, max: Float): Ticks {
    val span = (max - min).takeIf { it > 0f } ?: return Ticks(min, min + 1f, 1f)
    val rough = span / (TICKS - 1)
    val magnitude = 10.0.pow(floor(log10(rough.toDouble())))
    val step =
        when (rough / magnitude) {
            in 0.0..1.0 -> 1.0
            in 1.0..2.0 -> 2.0
            in 2.0..2.5 -> 2.5
            in 2.5..5.0 -> 5.0
            else -> 10.0
        } * magnitude
    return Ticks(
        min = (floor(min / step) * step).toFloat(),
        max = (ceil(max / step) * step).toFloat(),
        step = step.toFloat(),
    )
}

/**
 * Bounds that reach the origin whenever the data is near it.
 *
 * **This is what makes the chart four-quadrant rather than merely scattered.**
 * A cloud that happens to sit entirely in deficit would otherwise be drawn on an
 * axis running 200 to 900 grams, with no zero line anywhere on it -- and the one
 * thing a reader wants to locate on this plot is the line where the weight
 * holds. Including zero costs some resolution and buys the only landmark the
 * chart has.
 *
 * It does not *force* the origin onto an axis that is genuinely far from it:
 * padding a range of 2,000-to-3,000 calories down to zero would squash every
 * point into the top third for a gridline nobody is reading against. The rule is
 * to include zero when the data comes within one span's width of it, which
 * covers every case where the crossing is a real question.
 */
private fun boundsIncludingZero(values: List<Float>): ClosedFloatingPointRange<Float> {
    if (values.isEmpty()) return -1f..1f
    val low = values.min()
    val high = values.max()
    val span = (high - low).takeIf { it > 0f } ?: (abs(high).takeIf { it > 0f } ?: 1f)
    val paddedLow = if (low > 0f && low <= span) 0f else low
    val paddedHigh = if (high < 0f && high >= -span) 0f else high
    // A single point, or a column of identical ones, still needs a plot to sit
    // in rather than a zero-height axis to divide by.
    if (paddedHigh == paddedLow) return (paddedLow - 1f)..(paddedHigh + 1f)
    return paddedLow..paddedHigh
}

/**
 * One measured quantity against another, with the origin drawn where it falls.
 *
 * [fit] is drawn dashed, which is this app's standing rule rather than a choice
 * made here: measurements are solid and models say so. The points are the
 * measurements; the line is a claim about them.
 */
@Composable
fun ScatterChart(
    points: List<ScatterPoint>,
    xLabel: String,
    yLabel: String,
    fit: LinearFit?,
    pointColor: Color,
    fitColor: Color,
    modifier: Modifier = Modifier,
    /** Spoken in place of the canvas, which is otherwise a blank to a screen reader. */
    contentDescription: String? = null,
) {
    if (points.isEmpty()) {
        EmptyScatter(modifier)
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val description = contentDescription ?: "$yLabel against $xLabel, ${points.size} points"

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().weight(1f).semantics {
                this.contentDescription = description
            }
        ) {
            val plotLeft = VALUE_GUTTER.toPx()
            val plotWidth = size.width - plotLeft
            val plotHeight = size.height - BOTTOM_GUTTER.toPx()
            if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

            val xBounds = boundsIncludingZero(points.map { it.x })
            val yBounds = boundsIncludingZero(points.map { it.y })
            val xTicks = niceTicks(xBounds.start, xBounds.endInclusive)
            val yTicks = niceTicks(yBounds.start, yBounds.endInclusive)
            val xSpan = (xTicks.max - xTicks.min).takeIf { it > 0f } ?: return@Canvas
            val ySpan = (yTicks.max - yTicks.min).takeIf { it > 0f } ?: return@Canvas

            fun px(x: Float) = plotLeft + ((x - xTicks.min) / xSpan) * plotWidth
            fun py(y: Float) = plotHeight - ((y - yTicks.min) / ySpan) * plotHeight

            val style = TextStyle(fontSize = LABEL_SIZE, color = axisColor)

            // Gridlines and their numbers first, so every point sits on top of
            // them rather than under.
            var v = yTicks.min
            while (v <= yTicks.max + yTicks.step / 100f) {
                val y = py(v)
                drawLine(gridColor, Offset(plotLeft, y), Offset(size.width, y), 1f)
                val laid = textMeasurer.measure(formatTick(v, yTicks.step), style)
                drawText(
                    textLayoutResult = laid,
                    topLeft =
                        Offset(
                            x = (plotLeft - 4.dp.toPx() - laid.size.width).coerceAtLeast(0f),
                            y =
                                (y - laid.size.height / 2f)
                                    .coerceIn(0f, plotHeight - laid.size.height),
                        ),
                )
                v += yTicks.step
            }

            var h = xTicks.min
            while (h <= xTicks.max + xTicks.step / 100f) {
                val x = px(h)
                drawLine(gridColor, Offset(x, 0f), Offset(x, plotHeight), 1f)
                val laid = textMeasurer.measure(formatTick(h, xTicks.step), style)
                drawText(
                    textLayoutResult = laid,
                    topLeft =
                        Offset(
                            x =
                                (x - laid.size.width / 2f)
                                    .coerceIn(0f, size.width - laid.size.width),
                            y = plotHeight + 2.dp.toPx(),
                        ),
                )
                h += xTicks.step
            }

            // The two zero rules, heavier than a gridline and lighter than the
            // data. They are what divide the plot into quadrants, and they mark
            // context rather than reporting a measurement -- so they must not
            // read as a series. Drawn only where zero is actually on the axis.
            if (0f in yTicks.min..yTicks.max) {
                drawLine(axisColor, Offset(plotLeft, py(0f)), Offset(size.width, py(0f)), 1.5f)
            }
            if (0f in xTicks.min..xTicks.max) {
                drawLine(axisColor, Offset(px(0f), 0f), Offset(px(0f), plotHeight), 1.5f)
            }

            // The fitted line, dashed and drawn edge to edge. Clipped to the
            // points' own span it would stop short of the crossing, which is the
            // part of it worth looking at.
            fit?.let { line ->
                val yAtLeft = line.intercept + line.slope * xTicks.min
                val yAtRight = line.intercept + line.slope * xTicks.max
                drawLine(
                    color = fitColor,
                    start = Offset(px(xTicks.min), py(yAtLeft)),
                    end = Offset(px(xTicks.max), py(yAtRight)),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(FIT_DASH, 0f),
                )
            }

            points.forEach {
                drawCircle(
                    color = pointColor,
                    radius = DOT_RADIUS.toPx(),
                    center = Offset(px(it.x), py(it.y)),
                )
            }
        }

        // Axis names under the plot rather than rotated down its side: a
        // vertical label costs a rotation layer and a chunk of width on a phone,
        // and these two names are the only thing that says what the numbers are.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "↑ $yLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "$xLabel →",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyScatter(modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Not enough days with both figures recorded",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Charts.formatAxisValue's rule, on an axis that is not private to that file. */
private fun formatTick(value: Float, step: Float): String =
    when {
        value == 0f -> "0"
        step >= 1000f -> "%.0fk".format(value / 1000f)
        step >= 100f && abs(value) >= 1000f -> "%.1fk".format(value / 1000f)
        step >= 1f -> "%.0f".format(value)
        step >= 0.1f -> "%.1f".format(value)
        else -> "%.2f".format(value)
    }
