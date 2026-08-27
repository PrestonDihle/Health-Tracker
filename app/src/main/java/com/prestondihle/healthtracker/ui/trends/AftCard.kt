package com.prestondihle.healthtracker.ui.trends

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prestondihle.healthtracker.data.AftAttempt
import com.prestondihle.healthtracker.domain.AftEvent
import com.prestondihle.healthtracker.domain.AftScorecard
import com.prestondihle.healthtracker.domain.AftScoring
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.ui.components.AxisRule
import com.prestondihle.healthtracker.ui.components.AxisSpec
import com.prestondihle.healthtracker.ui.components.ChartSeries
import com.prestondihle.healthtracker.ui.components.DualAxisTimeChart
import com.prestondihle.healthtracker.ui.components.IntStepper
import com.prestondihle.healthtracker.ui.components.LogButton
import com.prestondihle.healthtracker.ui.components.Metric
import com.prestondihle.healthtracker.ui.components.TimePoint
import com.prestondihle.healthtracker.ui.theme.LocalChartColors
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val ATTEMPT_DATE = DateTimeFormatter.ofPattern("d MMM yyyy")

/**
 * The lowest score the trend's axis budgets for.
 *
 * Not zero. A finished test is five events at 60 or better, so the bottom half
 * of a 0-500 axis is space no plotted point ever occupies -- and spending it
 * flattens the range that actually moves into the top third, the same argument
 * that keeps the glucose plot off a 200 ceiling. The axis is a floor rather than
 * a clip, so a genuinely bad test still draws.
 */
private const val SCORE_AXIS_FLOOR = 250f

/** Five events at a hundred apiece. */
private const val SCORE_AXIS_CEILING = 500f

/**
 * The Army Fitness Test: what was logged, what it scored, and whether it passed.
 *
 * Scores are computed on read rather than stored, so editing the profile or
 * flipping the lane in Settings re-scores every past attempt in place. That is
 * the behaviour that makes the card trustworthy -- a stored score would be a
 * claim about a profile that has since changed, and nothing on screen could tell
 * the two apart.
 */
@Composable
internal fun AftCard(
    state: AftUiState,
    onSave: (AftAttempt) -> Unit,
    onDelete: (AftAttempt) -> Unit,
) {
    var editing by remember { mutableStateOf<AftAttempt?>(null) }
    var logging by remember { mutableStateOf(false) }

    TrendCard(title = "Army Fitness Test", subtitle = "${state.lane.label} standard") {
        if (!state.canScore) {
            // The general standard has no column to read without a sex, and no
            // band without an age. Said plainly, because the alternative is a
            // card full of dashes that looks like a bug.
            Text(
                "Set your age and sex on the Settings tab to score a test. " +
                    "The combat standard is sex-neutral and needs only an age.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val card = state.latestScorecard
        val latest = state.latest
        if (latest != null && card != null) {
            ScoreSummary(date = latest.date, card = card)
            HorizontalDivider()
            EventScores(attempt = latest, card = card, state = state)
        } else {
            Text(
                "No test logged yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.totals.size >= 2) {
            HorizontalDivider()
            ScoreTrend(state = state)
        }

        LogButton("Log a test", onClick = { logging = true })

        // Earlier attempts, newest first and each tappable to correct. Only the
        // ones behind the latest, since that one is already spelled out above.
        val earlier = state.attempts.dropLast(1).reversed()
        if (earlier.isNotEmpty()) {
            HorizontalDivider()
            Text(
                "Earlier tests",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            earlier.forEach { attempt ->
                val scored = state.scorecardFor(attempt)
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable { editing = attempt }
                            .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        ATTEMPT_DATE.format(attempt.date),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (scored.isComplete) "${scored.total} / 500" else "unfinished",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }

    if (logging || editing != null) {
        AftEntryDialog(
            attempt = editing,
            state = state,
            onDismiss = {
                logging = false
                editing = null
            },
            onConfirm = {
                onSave(it)
                logging = false
                editing = null
            },
            onDelete =
                editing?.let {
                    {
                        onDelete(it)
                        editing = null
                    }
                },
        )
    }
}

/** Total, verdict and the event with least room over the floor. */
@Composable
private fun ScoreSummary(date: LocalDate, card: AftScorecard) {
    val passes = card.passes
    Row(modifier = Modifier.fillMaxWidth()) {
        Metric(
            label = "Total",
            value = "${card.total}",
            supporting = "of 500, ${ATTEMPT_DATE.format(date)}",
            modifier = Modifier.weight(1.4f),
        )
        Metric(
            label = "Needs",
            value = "${card.lane.minimumTotal}",
            supporting = "${card.lane.label.lowercase()} standard",
            modifier = Modifier.weight(1f),
        )
        Metric(
            label = "Verdict",
            // Null while unfinished. A test three events in is not a failing
            // test, and calling it one would be the card's worst possible lie.
            value = if (passes == null) "--" else if (passes) "Pass" else "Fail",
            supporting = if (passes == null) "not finished" else null,
            valueColor =
                when (passes) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> null
                },
            modifier = Modifier.weight(1f),
        )
    }

    val weakest = card.weakestEvent
    if (passes != null && weakest != null) {
        val (event, margin) = weakest
        Text(
            if (margin < 0) {
                "${event.label} is ${-margin} point${plural(-margin)} under the 60 it needs."
            } else {
                "Closest to the floor: ${event.label}, $margin point${plural(margin)} over."
            },
            style = MaterialTheme.typography.bodySmall,
            color =
                if (margin < 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun plural(n: Int) = if (n == 1) "" else "s"

/** One row per event: what was done, and what it was worth. */
@Composable
private fun EventScores(attempt: AftAttempt, card: AftScorecard, state: AftUiState) {
    AftEvent.entries.forEach { event ->
        val raw = attempt.rawFor(event)
        val points = card.scores[event]
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(event.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (raw == null) "not done" else event.format(raw),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                points?.toString() ?: "--",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color =
                    if (points != null && points < AftScoring.MINIMUM_EVENT_SCORE) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
        }
    }
}

/**
 * Total score across attempts.
 *
 * Plotted against real dates rather than an index, for the reason the blood
 * pressure chart is: tests happen when they happen, months apart and not evenly,
 * and spacing them evenly would imply a cadence that is not there.
 *
 * The two pass marks are dashed because they are published figures rather than
 * anything chosen here -- the same rule that keeps 120/80 dashed and a
 * self-chosen glucose reference solid. Both lanes are drawn even though only one
 * applies, because the distance to the other is the question anyone changing
 * lanes is asking.
 */
@Composable
private fun ScoreTrend(state: AftUiState) {
    val chartColors = LocalChartColors.current
    val totals = state.totals
    DualAxisTimeChart(
        // A fortnight of padding either side, so the first and last attempt are
        // not drawn on the frame.
        windowStart = totals.first().first.minus(Duration.ofDays(14)),
        windowEnd = totals.last().first.plus(Duration.ofDays(14)),
        zoneId = state.zoneId,
        series =
            listOf(
                ChartSeries(
                    label = "Total",
                    points = totals.map { TimePoint(it.first, it.second) },
                    color = chartColors.vibe,
                )
            ),
        leftAxis =
            AxisSpec(
                min = SCORE_AXIS_FLOOR,
                max = SCORE_AXIS_CEILING,
                label = "points",
                rules = listOf(AxisRule(300f), AxisRule(350f), AxisRule(SCORE_AXIS_CEILING)),
            ),
        contentDescription = "Army Fitness Test total score by test date",
        modifier = Modifier.fillMaxWidth().height(160.dp),
    )
}

/** The raw figure this event holds on an attempt, in the units its table is published in. */
private fun AftAttempt.rawFor(event: AftEvent): Int? =
    when (event) {
        AftEvent.DEADLIFT -> deadliftKg?.let { Units.kgToWholeLbs(it) }
        AftEvent.PUSH_UP -> hrpReps
        AftEvent.SPRINT_DRAG_CARRY -> sdcSeconds
        AftEvent.PLANK -> plankSeconds
        AftEvent.TWO_MILE_RUN -> twoMileSeconds
    }

/** `2:31` for a timed event, `250 lb` or `37` for the rest. */
private fun AftEvent.format(raw: Int): String =
    when {
        isTimed -> "%d:%02d".format(raw / 60, raw % 60)
        this == AftEvent.DEADLIFT -> "$raw lb"
        else -> "$raw"
    }

/**
 * Entry for one test, five events at a time.
 *
 * Steppers rather than typed text, like every other quantity in this app. Each
 * one opens on that event's **60-point requirement** for the reader's own band
 * rather than on zero: the run is over a thousand seconds and the deadlift over
 * a hundred pounds, so zero is a long way from anywhere useful, and the pass
 * mark is the figure being aimed at anyway.
 *
 * Every event can be left alone, and one left alone stays null. That is what
 * makes a part-finished test day loggable, which is the ordinary case -- the
 * events are done in order over two hours.
 */
@Composable
private fun AftEntryDialog(
    attempt: AftAttempt?,
    state: AftUiState,
    onDismiss: () -> Unit,
    onConfirm: (AftAttempt) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val today = remember(state.zoneId) { LocalDate.now(state.zoneId) }

    // Null means "not done" and has to survive the dialog, so each event carries
    // its own included flag rather than leaning on a sentinel value -- zero is a
    // real plank time and a real rep count.
    var deadlift by remember { mutableStateOf(attempt?.let { it.deadliftKg?.let(Units::kgToWholeLbs) }) }
    var pushUps by remember { mutableStateOf(attempt?.hrpReps) }
    var sdc by remember { mutableStateOf(attempt?.sdcSeconds) }
    var plank by remember { mutableStateOf(attempt?.plankSeconds) }
    var run by remember { mutableStateOf(attempt?.twoMileSeconds) }
    var date by remember { mutableStateOf(attempt?.date ?: today) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (attempt == null) "Log a test" else "Edit test") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { date = date.minusDays(1) }) { Text("< Earlier") }
                    Text(
                        if (date == today) "Today" else ATTEMPT_DATE.format(date),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // A test cannot have happened tomorrow.
                    TextButton(
                        onClick = { date = date.plusDays(1) },
                        enabled = date.isBefore(today),
                    ) {
                        Text("Later >")
                    }
                }

                EventStepper(
                    event = AftEvent.DEADLIFT,
                    value = deadlift,
                    onValueChange = { deadlift = it },
                    state = state,
                    // The published scale is entirely in tens of pounds, so the
                    // stepper moves the way the bar is actually loaded.
                    step = 10,
                    range = 0..500,
                )
                EventStepper(
                    event = AftEvent.PUSH_UP,
                    value = pushUps,
                    onValueChange = { pushUps = it },
                    state = state,
                    step = 1,
                    range = 0..150,
                )
                EventStepper(
                    event = AftEvent.SPRINT_DRAG_CARRY,
                    value = sdc,
                    onValueChange = { sdc = it },
                    state = state,
                    step = 1,
                    range = 0..900,
                )
                EventStepper(
                    event = AftEvent.PLANK,
                    value = plank,
                    onValueChange = { plank = it },
                    state = state,
                    step = 1,
                    range = 0..900,
                )
                EventStepper(
                    event = AftEvent.TWO_MILE_RUN,
                    value = run,
                    onValueChange = { run = it },
                    state = state,
                    step = 1,
                    range = 0..3_600,
                )

                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete this test") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        AftAttempt(
                            id = attempt?.id ?: 0,
                            date = date,
                            deadliftKg = deadlift?.let { Units.lbsToKg(it.toFloat()) },
                            hrpReps = pushUps,
                            sdcSeconds = sdc,
                            plankSeconds = plank,
                            twoMileSeconds = run,
                        )
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/**
 * One event's entry, plus the score it would earn as it is changed.
 *
 * The running score is the point of showing it here rather than after saving: a
 * Soldier setting the plank knows immediately whether the number in front of
 * them clears 60, which is the only question the entry is really about.
 */
@Composable
private fun EventStepper(
    event: AftEvent,
    value: Int?,
    onValueChange: (Int?) -> Unit,
    state: AftUiState,
    step: Int,
    range: IntRange,
) {
    val minimum = state.minimumFor(event)
    val points =
        value?.let { AftScoring.score(event, it, state.ageYears, state.sex, state.lane) }

    Column(modifier = Modifier.fillMaxWidth()) {
        IntStepper(
            label = event.label,
            // Opens on the pass mark the first time it is touched; a stepper for
            // an event that was not done stays at "not done" until nudged.
            value = value ?: minimum ?: 0,
            onValueChange = { onValueChange(it) },
            step = step,
            range = range,
            enabled = true,
            supportingText =
                when {
                    value == null -> "not done"
                    points == null -> event.unitLabel
                    else -> "${event.unitLabel} · $points points"
                },
            valueFormatter = { if (event.isTimed) "%d:%02d".format(it / 60, it % 60) else "$it" },
        )
        if (value != null) {
            TextButton(onClick = { onValueChange(null) }) { Text("Clear ${event.abbreviation}") }
        }
    }
}

/** The unit a stepper is counting in, for the line under it. */
private val AftEvent.unitLabel: String
    get() =
        when (this) {
            AftEvent.DEADLIFT -> "lb"
            AftEvent.PUSH_UP -> "reps"
            else -> "min:sec"
        }
