package com.prestondihle.healthtracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.prestondihle.healthtracker.domain.FastingDay
import java.time.format.DateTimeFormatter

private val DAY_LABEL = DateTimeFormatter.ofPattern("EEE d")

/**
 * One row per day, midnight to midnight, with fasted stretches filled.
 *
 * Reads as a pattern rather than a total: a row that is solid from the left
 * until mid-afternoon is a late first meal, and a gap in the middle of the
 * night is a fast that was broken and restarted. Bars are drawn from
 * pre-computed fractions, so nothing here does time arithmetic.
 */
@Composable
fun FastingTimeline(
    days: List<FastingDay>,
    modifier: Modifier = Modifier,
    fastedColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    rowHeight: androidx.compose.ui.unit.Dp = 14.dp,
) {
    if (days.isEmpty()) {
        Text(
            "No fasts logged yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Newest first: the most recent day is the one being checked.
        days.sortedByDescending { it.date }.forEach { day ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    DAY_LABEL.format(day.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(44.dp),
                )
                Canvas(modifier = Modifier.weight(1f).height(rowHeight)) {
                    val radius = CornerRadius(size.height / 4f, size.height / 4f)
                    drawRoundRect(color = trackColor, size = size, cornerRadius = radius)

                    day.segments.forEach { segment ->
                        val left = segment.start * size.width
                        val right = segment.endInclusive * size.width
                        // A very short fast still deserves a visible mark rather
                        // than a sub-pixel sliver that renders as nothing.
                        val width = (right - left).coerceAtLeast(1.5.dp.toPx())
                        drawRoundRect(
                            color = fastedColor,
                            topLeft = Offset(left, 0f),
                            size = Size(width.coerceAtMost(size.width - left), size.height),
                            cornerRadius = radius,
                        )
                    }
                }
                Text(
                    "${day.fastedSeconds / 3600}h",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(28.dp),
                )
            }
        }

        // Hour ruler, offset by the day label so it lines up with the tracks.
        Row(modifier = Modifier.fillMaxWidth()) {
            androidx.compose.foundation.layout.Spacer(Modifier.width(44.dp))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("12a", "6a", "12p", "6p", "12a").forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(28.dp))
        }
    }
}
