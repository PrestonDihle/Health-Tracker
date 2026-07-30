package com.prestondihle.healthtracker.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Hold time before a held arrow starts repeating. */
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_START_MS = 140L
private const val REPEAT_INTERVAL_MIN_MS = 30L

/**
 * An arrow button that keeps firing while held, accelerating as it goes.
 *
 * Stepping to 50 pushups one tap at a time is unpleasant, so the touch path is
 * driven by press state rather than clicks. [onClick] still runs for
 * accessibility activations, which produce no press.
 */
@Composable
private fun RepeatingArrowButton(
    onClick: () -> Unit,
    enabled: Boolean,
    contentDescription: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var handledByPress by remember { mutableStateOf(false) }

    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        handledByPress = true
        currentOnClick()
        delay(REPEAT_DELAY_MS)
        var interval = REPEAT_INTERVAL_START_MS
        while (true) {
            currentOnClick()
            delay(interval)
            interval = (interval * 85 / 100).coerceAtLeast(REPEAT_INTERVAL_MIN_MS)
        }
    }

    FilledTonalIconButton(
        onClick = {
            if (!handledByPress) currentOnClick()
            handledByPress = false
        },
        enabled = enabled,
        interactionSource = interactionSource,
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}

/**
 * Label, current value, and a pair of arrows.
 *
 * [step] is applied in value space, so a quarter-inch waist control passes
 * `step = 0.25f` and gets exact quarters back via [snap].
 */
@Composable
fun Stepper(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    step: Float = 1f,
    range: ClosedFloatingPointRange<Float> = 0f..9_999f,
    enabled: Boolean = true,
    snap: (Float) -> Float = { it },
    valueFormatter: (Float) -> String = { it.roundToInt().toString() },
    supportingText: String? = null,
    /**
     * Optional slot after the arrows, for a commit action pinned inline with the
     * value it logs. Kept separate from the arrows by a wider gap so the up arrow
     * and a Log button are not mistaken for each other.
     */
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supportingText != null) {
                Text(
                    supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RepeatingArrowButton(
                onClick = { onValueChange(snap((value - step).coerceIn(range))) },
                enabled = enabled && value > range.start,
                contentDescription = "Decrease $label",
                icon = Icons.Filled.KeyboardArrowDown,
            )

            Text(
                text = valueFormatter(value),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 76.dp),
            )

            RepeatingArrowButton(
                onClick = { onValueChange(snap((value + step).coerceIn(range))) },
                enabled = enabled && value < range.endInclusive,
                contentDescription = "Increase $label",
                icon = Icons.Filled.KeyboardArrowUp,
            )

            if (trailingContent != null) {
                Spacer(Modifier.width(4.dp))
                trailingContent()
            }
        }
    }
}

/** [Stepper] for whole-number values, which is most of them. */
@Composable
fun IntStepper(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    range: IntRange = 0..9_999,
    enabled: Boolean = true,
    supportingText: String? = null,
    valueFormatter: (Int) -> String = { it.toString() },
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Stepper(
        label = label,
        value = value.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        modifier = modifier,
        step = step.toFloat(),
        range = range.first.toFloat()..range.last.toFloat(),
        enabled = enabled,
        valueFormatter = { valueFormatter(it.roundToInt()) },
        supportingText = supportingText,
        trailingContent = trailingContent,
    )
}
