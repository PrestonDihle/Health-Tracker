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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun LineChart(
    dataPoints: List<Float>, // y values
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    goalLine: Float? = null,
    goalColor: Color = MaterialTheme.colorScheme.error,
    minY: Float? = null,
    maxY: Float? = null
) {
    if (dataPoints.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No data yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val minVal = minY ?: (dataPoints.minOrNull() ?: 0f)
    val maxVal = maxY ?: (dataPoints.maxOrNull() ?: 1f)
    val range = if (maxVal - minVal == 0f) 1f else maxVal - minVal

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        fun mapY(value: Float): Float {
            val normalized = (value - minVal) / range
            return height - (normalized * height)
        }

        // Draw goal line
        goalLine?.let {
            val y = mapY(it)
            if (y in 0f..height) {
                drawLine(
                    color = goalColor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                )
            }
        }

        if (dataPoints.size == 1) {
            // Draw a single point in the center
            drawCircle(
                color = lineColor,
                radius = 4.dp.toPx(),
                center = Offset(width / 2f, mapY(dataPoints.first()))
            )
            return@Canvas
        }

        val stepX = width / (dataPoints.size - 1)
        val path = Path().apply {
            moveTo(0f, mapY(dataPoints.first()))
            for (i in 1 until dataPoints.size) {
                lineTo(i * stepX, mapY(dataPoints[i]))
            }
        }

        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw points
        for (i in dataPoints.indices) {
            drawCircle(
                color = lineColor,
                radius = 3.dp.toPx(),
                center = Offset(i * stepX, mapY(dataPoints[i]))
            )
        }
    }
}

/** One bar's segments, bottom to top, in the same order as the series colors. */
data class StackedBar(val segments: List<Float>)

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
    val totals = bars.map { bar -> bar.segments.sum() }
    if (bars.isEmpty() || totals.all { it <= 0f }) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No data yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val maxVal = maxOf(totals.max(), goalLine ?: 0f).takeIf { it > 0f } ?: 1f

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val stepX = width / bars.size
        val barWidth = stepX * 0.6f

        fun mapY(value: Float): Float = height - (value / maxVal) * height

        goalLine?.let {
            val y = mapY(it)
            if (y in 0f..height) {
                drawLine(
                    color = goalColor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect =
                        androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(10f, 10f),
                            0f,
                        ),
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
    }
}

@Composable
fun BarChart(
    dataPoints: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    goalLine: Float? = null,
    goalColor: Color = MaterialTheme.colorScheme.error,
    minY: Float? = null,
    maxY: Float? = null
) {
    if (dataPoints.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No data yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val minVal = minY ?: 0f // Usually bars start at 0
    val maxVal = maxY ?: (dataPoints.maxOrNull() ?: 1f)
    val range = if (maxVal - minVal == 0f) 1f else maxVal - minVal

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val barWidth = width / (dataPoints.size * 2)

        fun mapY(value: Float): Float {
            val normalized = (value - minVal) / range
            return height - (normalized * height)
        }

        // Draw goal line
        goalLine?.let {
            val y = mapY(it)
            if (y in 0f..height) {
                drawLine(
                    color = goalColor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                )
            }
        }

        val stepX = width / dataPoints.size
        for (i in dataPoints.indices) {
            val value = dataPoints[i]
            val y = mapY(value)
            val startX = i * stepX + (stepX - barWidth) / 2
            drawRect(
                color = barColor,
                topLeft = Offset(startX, y),
                size = Size(barWidth, height - y)
            )
        }
    }
}
