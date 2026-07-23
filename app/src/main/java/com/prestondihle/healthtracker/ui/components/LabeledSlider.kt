package com.prestondihle.healthtracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The three anchor points of a 1-10 subjective scale.
 *
 * All three are shown on screen: the ends alone are ambiguous, and for energy
 * in particular the midpoint is not the midpoint you would guess.
 */
data class ScaleDescriptors(val low: String, val mid: String, val high: String) {
    /** The descriptor nearest [value], for the read-out beside the number. */
    fun nearest(value: Int): String =
        when {
            value <= 3 -> low
            value <= 7 -> mid
            else -> high
        }

    companion object {
        val Vibe = ScaleDescriptors(low = "depressed", mid = "steady state", high = "awesome")
        val Energy = ScaleDescriptors(low = "nodding off", mid = "lethargic", high = "alert")
        val Focus = ScaleDescriptors(low = "distracted", mid = "focused", high = "locked in")
    }
}

/** A 1-10 slider with its scale anchors printed under the track. */
@Composable
fun LabeledSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    descriptors: ScaleDescriptors,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$value",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "  ${descriptors.nearest(value)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 1f..10f,
            // Nine steps between ten positions.
            steps = 8,
            modifier =
                Modifier.fillMaxWidth().semantics {
                    contentDescription = "$label, $value out of 10, ${descriptors.nearest(value)}"
                },
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AnchorLabel("1 ${descriptors.low}", Modifier.weight(1f), TextAlign.Start)
            AnchorLabel("5 ${descriptors.mid}", Modifier.weight(1f), TextAlign.Center)
            AnchorLabel("10 ${descriptors.high}", Modifier.weight(1f), TextAlign.End)
        }
    }
}

@Composable
private fun AnchorLabel(text: String, modifier: Modifier, align: TextAlign) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        modifier = modifier.padding(top = 2.dp),
    )
}
