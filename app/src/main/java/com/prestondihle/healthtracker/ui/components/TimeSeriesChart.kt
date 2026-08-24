package com.prestondihle.healthtracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil

data class TimePoint(val time: Instant, val value: Float)

enum class ChartAxis {
    LEFT,
    RIGHT,
}

/**
 * How a series is drawn.
 *
 * [BAR] is for a quantity accumulated *over* an interval rather than measured
 * *at* an instant. Steps per hour joined into a line would claim a continuous
 * rate that was never measured, and would put a value halfway between two hours
 * at a time when nothing was counted at all; a column says "this much, in this
 * hour", which is all the data actually supports.
 */
enum class SeriesKind {
    LINE,
    BAR,
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
    val kind: SeriesKind = SeriesKind.LINE,
    /**
     * Break the line where the series stops being measured, instead of joining
     * across the gap. See [SeriesGaps].
     *
     * For measured series only. A modelled curve is a continuous function
     * sampled evenly and has no gaps to find; switching this on for one would
     * only ever misread its own sampling interval.
     */
    val breakOnGaps: Boolean = false,
    /**
     * The interval one bar covers, starting at its point's timestamp.
     *
     * Required in practice for [SeriesKind.BAR], because the gaps between points
     * cannot be trusted to reveal it: an aggregator reports nothing at all for an
     * interval it has no records in, so a night of no walking arrives as a hole
     * rather than as zeroes, and inferring the width from the spacing either side
     * of that hole would draw one bar across the whole night.
     */
    val barWidth: Duration? = null,
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
 * A series that is currently switched off, kept in the legend so it can be
 * switched back on.
 *
 * Deliberately *not* a [ChartSeries] carrying no points. A hidden series must
 * not reach the plot at all -- one that did would go on stretching the axis it
 * shares, which is the whole reason the caller filters the list before passing
 * it. Carrying only the three things a legend row draws makes that mistake
 * impossible rather than merely discouraged.
 */
data class HiddenSeries(
    val label: String,
    val color: Color,
    val kind: SeriesKind = SeriesKind.LINE,
    val dashed: Boolean = false,
)

/**
 * A horizontal rule at one value on an axis.
 *
 * [dashed] marks a figure that came from outside -- a published clinical
 * threshold. Solid marks one the reader chose from scratch. Keeping the two
 * apart matters because they carry very different authority, and a rule drawn
 * the same way in both cases quietly lends one the weight of the other.
 */
data class AxisRule(val value: Float, val dashed: Boolean = true)

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
    /**
     * Reference rules across the plot.
     *
     * A list rather than the single value this started as, because a blood
     * pressure chart carries two numbers and one rule can only ever be read
     * against one of them: drawn with a systolic rule alone, the diastolic line
     * had nothing to be read against at all.
     */
    val rules: List<AxisRule> = emptyList(),
    /**
     * Colour for this axis' numbers, or null for the ordinary label grey.
     *
     * Set where an axis serves exactly one line, so the numbers down the side
     * say which line they belong to. Left null where an axis carries several --
     * tinting shared numbers with one series' colour claims they are that
     * series', which is worse than not tinting them at all.
     */
    val color: Color? = null,
    /**
     * A range shaded behind the data, for a target the series is read against.
     *
     * A band rather than two threshold lines because what is being asked is
     * "was it in range", and a filled area answers that at a glance where two
     * rules leave the reader to work out which side of each one the trace is on.
     * Clipped to the plot, so a target reaching past the axis simply runs to the
     * edge.
     */
    val band: ClosedFloatingPointRange<Float>? = null,
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
    /**
     * Drawn as chart furniture -- hairline, in the gridline grey -- instead of in
     * the axis colour at full weight.
     *
     * A marker that shares the data's weight *is* read as data. Full-height
     * near-black rules at every meal, each captioned with that meal's carb
     * grams, read as a carbohydrate spike -- and went on reading as one after the
     * carbohydrate curve was switched off, since the rules never belonged to a
     * series in the first place. Anything that marks context rather than
     * reporting a measurement wants this.
     */
    val subdued: Boolean = false,
)

private const val MAX_RENDERED_POINTS = 240
private val AXIS_LABEL_SIZE = 10.sp

/** Bars sit behind the lines, so they have to be readable *through*. */
private const val BAR_ALPHA = 0.45f

/** Light enough that a gridline still shows through the target band. */
private const val BAND_ALPHA = 0.16f

/**
 * How far a switched-off legend entry is faded.
 *
 * Faded rather than dropped: the row is the only way back, so it has to stay
 * findable -- and keeping its colour, however faint, is what says *which* line
 * it is. Far enough down that it never reads as a line that is on the plot.
 */
private const val OFF_ALPHA = 0.3f

/** Room above the plot for marker captions; without it they would clip. */
private val MARKER_LABEL_HEIGHT = 16.dp

/** Clear space demanded between two marker captions before the later one is dropped. */
private val MARKER_LABEL_GAP = 6.dp

/** Least vertical room a gridline row may have before rows are dropped. */
private val MIN_ROW_SPACING = 28.dp

/** Least horizontal room between two time rules before the interval widens. */
private val MIN_GRIDLINE_SPACING = 14.dp

/**
 * Room down the sides for the axis numbers.
 *
 * Named constants rather than figures inline in the drawing, because the
 * gestures have to arrive at exactly the same plot rectangle. A crosshair placed
 * against one set of gutters and drawn against another lands beside the moment
 * it was asked about, and a pan measured against the wrong width moves the
 * window at the wrong speed.
 */
private val LEFT_GUTTER = 36.dp
private val RIGHT_GUTTER_LABELLED = 36.dp
private val RIGHT_GUTTER_BARE = 8.dp

/**
 * How near a series has to have been sampled to answer for an inspected moment.
 *
 * A fraction of the window rather than a fixed span, because what counts as "at
 * that moment" is a question about the plot: a minute is a hair's breadth across
 * a week and a quarter of the gap between two readings across three hours.
 * Anything further off reads as an em dash instead, which is the honest answer --
 * quoting the value from an hour either side would invent a measurement.
 */
private const val INSPECT_TOLERANCE_DIVISOR = 40L

/** Shown for a series with nothing sampled near the inspected moment. */
private const val NO_READING = "\u2014"

/** How near a tap has to land to the standing crosshair to be putting it away. */
private val DISMISS_SLOP = 12.dp

/**
 * The moment being inspected, spelled out.
 *
 * The weekday is always printed even where the window is three hours wide: the
 * crosshair survives a change of range and a pan, so the one thing it must never
 * do is leave the reader assuming today.
 */
private val INSPECT_TIME_FORMAT = DateTimeFormatter.ofPattern("EEE h:mm a")

/**
 * Where the vertical rules go on a time chart.
 *
 * Separated out and left `internal` because the choice of interval is the part
 * worth pinning down, and it is arithmetic rather than drawing.
 */
internal object TimeGridlines {

    /**
     * Intervals a day divides evenly into.
     *
     * Evenly, so that every rule lands on the same clock times each day and the
     * pattern repeats: a 5-hour interval would drift through the day and put the
     * rules in different places on Tuesday than on Monday, which is the opposite
     * of what a gridline is for. 24 is the widest -- beyond a day apart the rules
     * are marking dates, and the tick labels already do that.
     */
    private val INTERVALS = listOf(1L, 2L, 3L, 4L, 6L, 12L, 24L)

    /**
     * Hours between rules for a window of [windowHours].
     *
     * Hourly up to half a day, four-hourly beyond it: an hour is the unit a meal
     * or a walk is read in, and it stays legible while a window is short enough
     * for individual hours to matter. Past that the question stops being "which
     * hour" and becomes "which part of the day", and a rule per hour is 24 lines
     * of furniture across the data.
     *
     * The base choice is then widened until the rules are at least
     * [minSpacingPx] apart, which is what keeps a week from arriving as 42 lines
     * roughly a finger-width in total. The guard is in pixels rather than in
     * hours because it is a question about the screen, not about the clock --
     * the same window on a tablet has room for more.
     */
    fun intervalHours(windowHours: Long, plotWidthPx: Float, minSpacingPx: Float): Long {
        if (windowHours <= 0) return 0
        val base = if (windowHours <= 12) 1L else 4L
        if (plotWidthPx <= 0f || minSpacingPx <= 0f) return base
        return INTERVALS.firstOrNull {
            it >= base && plotWidthPx * (it.toFloat() / windowHours) >= minSpacingPx
        }
            // Nothing fits: a day apart is as wide as these go, and drawing the
            // widest available beats drawing none.
            ?: INTERVALS.last()
    }

    /**
     * Every clock time in the window that lands on [intervalHours].
     *
     * Aligned to the local hour of day rather than stepped from the window edge,
     * so the rules sit on 4 PM and 8 PM rather than on "four hours before now".
     * Walked hour by hour instead of added to, because adding a fixed number of
     * hours across a daylight-saving change lands an hour off the clock and puts
     * every rule after it in the wrong place.
     */
    fun times(
        windowStart: Instant,
        windowEnd: Instant,
        zoneId: ZoneId,
        intervalHours: Long,
    ): List<Instant> {
        if (intervalHours <= 0 || !windowStart.isBefore(windowEnd)) return emptyList()
        val times = mutableListOf<Instant>()
        var cursor = windowStart.atZone(zoneId).truncatedTo(ChronoUnit.HOURS)
        while (!cursor.toInstant().isAfter(windowEnd)) {
            if (!cursor.toInstant().isBefore(windowStart) && cursor.hour % intervalHours == 0L) {
                times.add(cursor.toInstant())
            }
            cursor = cursor.plusHours(1)
        }
        return times
    }
}

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
    /**
     * Rules on the clock, at whole hours.
     *
     * Off by default. They earn their ink on a plot carrying several series that
     * are being read against each other in time -- did the rise start before or
     * after that walk -- and are clutter on a chart with one line on it, where
     * the tick labels already say enough.
     */
    verticalGridlines: Boolean = false,
    /**
     * Series the caller has switched off, drawn faded at the end of the legend.
     *
     * The legend can only be a complete set of controls if it also shows what is
     * *not* drawn -- otherwise switching a line off removes the only thing that
     * could switch it back on. They are passed apart from [series] rather than
     * flagged inside it because nothing hidden may reach the plot: a point that
     * is not being drawn must not go on setting an axis' ceiling.
     */
    hiddenSeries: List<HiddenSeries> = emptyList(),
    /**
     * Called with a legend row's [ChartSeries.label] when it is tapped.
     *
     * Null leaves the legend inert, which is what a chart with nothing to toggle
     * wants. Keyed by label rather than by index so the caller does not have to
     * track which list a row came from.
     */
    onSeriesTap: ((String) -> Unit)? = null,
    /**
     * Called with how far back a horizontal drag asks the window to move.
     *
     * Positive is further into the past, which is the direction a drag to the
     * right goes -- the plot moves under the finger the way a map does. Null
     * leaves the chart anchored, which is what every chart with a window it does
     * not own wants.
     */
    onPan: ((Duration) -> Unit)? = null,
    /**
     * One line naming the plot for a screen reader.
     *
     * A Canvas is a blank to TalkBack, which mattered less while a chart was only
     * something to look at. It is a control now -- tapping it reads the lines out
     * and dragging it moves the window -- and a control with no name is a control
     * that is not there at all for anyone using one.
     */
    contentDescription: String? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Clipped to the window once, here, rather than again per use. Every axis is
    // then scaled to what is actually on the plot: a series is routinely queried
    // wider than it is drawn -- meals reach back an absorption window, a bucket
    // survives from a previous sync -- and letting a point that is not on the
    // chart set the chart's ceiling flattens every point that is.
    val drawn = series.map { DrawnSeries(it, it.points.inWindow(windowStart, windowEnd)) }
    val hasData = drawn.any { it.points.isNotEmpty() }

    // The moment being inspected, or null while nothing is. Kept here rather than
    // hoisted to a ViewModel: nothing outside this chart needs to know, and a
    // round trip through state per tap would have the hairline lag the finger.
    var selectedTime by remember { mutableStateOf<Instant?>(null) }
    // Held as an instant, so it stays on the moment it was put on through a
    // change of range or a pan -- and simply stops being drawn once that moment
    // is off the plot, rather than sliding along to the edge.
    val inspected = selectedTime?.takeIf { it in windowStart..windowEnd }

    // Read by the gesture handlers without keying the pointer input on them. The
    // window and the points change every frame of a pan; restarting the gesture
    // detector that often would drop the drag being handled.
    val currentStart by rememberUpdatedState(windowStart)
    val currentEnd by rememberUpdatedState(windowEnd)
    val currentDrawn by rememberUpdatedState(drawn)
    val currentHasRightAxis by rememberUpdatedState(rightAxis != null)
    val currentOnPan by rememberUpdatedState(onPan)

    Column(modifier = modifier) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .weight(1f)
                    // Tap and drag are read by two detectors rather than one on
                    // purpose. Both watch the same pointer: the tap detector
                    // gives up the moment the finger travels, and the drag
                    // detector waits for *horizontal* slop specifically, so a
                    // vertical swipe reaches neither and the list underneath goes
                    // on scrolling.
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val plot =
                                plotSpan(size.width.toFloat(), currentHasRightAxis)
                            val span = Duration.between(currentStart, currentEnd)
                            if (plot == null || span.isZero || span.isNegative) {
                                return@detectTapGestures
                            }
                            // Read back off the state rather than from the
                            // `inspected` above: this block is launched once and
                            // never restarted, so a plain local read here would
                            // be the value the chart had when it first composed,
                            // for ever. The delegated property goes through the
                            // remembered state and is current.
                            val standing =
                                selectedTime?.takeIf { it in currentStart..currentEnd }
                            // Tapping the gutter puts the crosshair away, and so
                            // does tapping the standing one again. Judged in
                            // pixels rather than against the snapped instant: on
                            // a dense trace "the same place" is several samples
                            // wide, and a second tap that landed on the neighbour
                            // would move the hairline instead of dismissing it.
                            val standingX =
                                standing?.let {
                                    plot.start + plot.fraction(it, currentStart, span) * plot.width
                                }
                            selectedTime =
                                when {
                                    offset.x !in plot.start..plot.end -> null
                                    standingX != null &&
                                        abs(offset.x - standingX) <= DISMISS_SLOP.toPx() -> null
                                    else ->
                                        currentDrawn.nearestSampleTo(
                                            plot.timeAt(offset.x, currentStart, span)
                                        )
                                }
                        }
                    }
                    .then(
                        if (contentDescription == null) Modifier
                        else Modifier.semantics { this.contentDescription = contentDescription }
                    )
                    .then(
                        if (onPan == null) Modifier
                        else
                            Modifier.pointerInput(Unit) {
                                detectHorizontalDragGestures { change, dragAmount ->
                                    val pan = currentOnPan ?: return@detectHorizontalDragGestures
                                    val plot =
                                        plotSpan(size.width.toFloat(), currentHasRightAxis)
                                    val span = Duration.between(currentStart, currentEnd)
                                    if (plot == null || span.isZero || span.isNegative) {
                                        return@detectHorizontalDragGestures
                                    }
                                    change.consume()
                                    pan(
                                        Duration.ofMillis(
                                            ((dragAmount / plot.width) * span.toMillis()).toLong()
                                        )
                                    )
                                }
                            }
                    )
        ) {
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
                        series = drawn,
                        leftAxis = leftAxis.expandedFor(drawn, ChartAxis.LEFT),
                        rightAxis = rightAxis?.expandedFor(drawn, ChartAxis.RIGHT),
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        textMeasurer = textMeasurer,
                        gridColor = gridColor,
                        axisTextColor = axisTextColor,
                        zoneId = zoneId,
                        markers = markers,
                        verticalGridlines = verticalGridlines,
                        inspected = inspected,
                    )
                }
            }
        }

        if (inspected != null) {
            CrosshairReadout(
                at = inspected,
                series = drawn,
                leftAxis = leftAxis,
                rightAxis = rightAxis,
                tolerance =
                    Duration.between(windowStart, windowEnd)
                        .dividedBy(INSPECT_TOLERANCE_DIVISOR),
                zoneId = zoneId,
            )
        }

        Legend(
            series = drawn,
            hidden = hiddenSeries,
            leftAxis = leftAxis,
            rightAxis = rightAxis,
            onSeriesTap = onSeriesTap,
        )
    }
}

/**
 * A series paired with the part of it that falls inside the plotted window.
 *
 * Both halves are needed: the spec says how to draw, and the clipped points are
 * what every scale, legend caption and empty check has to be computed from.
 */
private data class DrawnSeries(val spec: ChartSeries, val points: List<TimePoint>)

/**
 * The horizontal stretch the plot itself occupies, and the mapping between it
 * and the clock.
 *
 * Separate from the drawing so a gesture can work in the same rectangle rather
 * than a rectangle of its own that happens to agree today.
 */
private class PlotSpan(val start: Float, val end: Float) {
    val width: Float
        get() = end - start

    fun timeAt(x: Float, windowStart: Instant, span: Duration): Instant =
        windowStart.plusMillis((((x - start) / width) * span.toMillis()).toLong())

    fun fraction(at: Instant, windowStart: Instant, span: Duration): Float =
        Duration.between(windowStart, at).toMillis().toFloat() / span.toMillis()
}

/** Null where the gutters leave no room at all, which nothing may divide by. */
private fun Density.plotSpan(widthPx: Float, hasRightAxis: Boolean): PlotSpan? {
    val left = LEFT_GUTTER.toPx()
    val right =
        widthPx - (if (hasRightAxis) RIGHT_GUTTER_LABELLED else RIGHT_GUTTER_BARE).toPx()
    return if (right - left <= 0f) null else PlotSpan(left, right)
}

/** How far [at] is from this reading, either way round. */
private fun TimePoint.distanceTo(at: Instant): Long =
    abs(Duration.between(time, at).toMillis())

/**
 * The sampled moment nearest [at], across every series on the plot.
 *
 * The crosshair lands on a moment something was actually recorded rather than
 * wherever the finger fell. A hairline at 4:37 over a trace sampled on the
 * five-minute mark would be quoting 4:35's reading under 4:37's caption.
 */
private fun List<DrawnSeries>.nearestSampleTo(at: Instant): Instant? =
    flatMap { it.points }.minByOrNull { it.distanceTo(at) }?.time

/** The reading nearest [at], or null when none was taken within [tolerance]. */
private fun List<TimePoint>.nearestTo(at: Instant, tolerance: Duration): TimePoint? =
    minByOrNull { it.distanceTo(at) }?.takeIf { it.distanceTo(at) <= tolerance.toMillis() }

/**
 * What this series read at [at].
 *
 * A line is answered by its nearest sample, and by nothing at all where the
 * nearest is further off than [tolerance] -- quoting a heart rate from either
 * side of an eight-hour hole is inventing a measurement.
 *
 * A bar is answered by the column that *contains* the moment instead. Its
 * timestamp is the start of an interval rather than an instant it was measured
 * at, so on an hourly step bucket the nearest start to a moment halfway through
 * the hour is half an hour away -- an em dash printed under a column the reader
 * can plainly see beneath the crosshair.
 */
private fun DrawnSeries.readingAt(at: Instant, tolerance: Duration): TimePoint? {
    val width = spec.barWidth
    if (spec.kind == SeriesKind.BAR && width != null) {
        return points.lastOrNull { !at.isBefore(it.time) && at.isBefore(it.time.plus(width)) }
    }
    return points.nearestTo(at, tolerance)
}

/**
 * What every series read at the inspected moment.
 *
 * Under the plot as ordinary text rather than painted on the canvas as a bubble:
 * a bubble has to go somewhere, and everywhere on a plot carrying eight series
 * is on top of one of them. Here it costs a strip of plot height and collides
 * with nothing.
 *
 * A series with nothing sampled near enough says so. The alternative is quoting
 * whatever it last managed, which on a watch taken off overnight is a heart rate
 * from eight hours before the moment being asked about.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrosshairReadout(
    at: Instant,
    series: List<DrawnSeries>,
    leftAxis: AxisSpec,
    rightAxis: AxisSpec?,
    tolerance: Duration,
    zoneId: ZoneId,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = INSPECT_TIME_FORMAT.format(at.atZone(zoneId)),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        series.forEach { drawn ->
            val item = drawn.spec
            // The same axis the line itself is drawn against, so the number in
            // the readout is the number the reader would take off the gutter.
            val axis =
                item.scale
                    ?: if (item.axis == ChartAxis.LEFT) leftAxis else rightAxis ?: leftAxis
            val reading = drawn.readingAt(at, tolerance)
            Text(
                text = "${item.label} ${reading?.let { axis.format(it.value) } ?: NO_READING}",
                style = MaterialTheme.typography.labelSmall,
                color = item.color,
            )
        }
    }
}

/**
 * Series names with their units, wrapping onto as many rows as it takes.
 *
 * A series drawn against a scale of its own also states that scale's range here,
 * since that is the only place its numbers appear at all.
 *
 * Where the caller supplies [onSeriesTap] this is also the plot's control
 * surface: every row toggles its own line, and the switched-off ones follow the
 * drawn ones, faded. Grouping them at the end rather than holding each name in a
 * fixed slot is deliberate -- what is on the chart reads as one block and what
 * is available reads as another, which is the question actually being asked of a
 * legend that doubles as a set of switches.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(
    series: List<DrawnSeries>,
    hidden: List<HiddenSeries>,
    leftAxis: AxisSpec,
    rightAxis: AxisSpec?,
    onSeriesTap: ((String) -> Unit)?,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        series.forEach { drawn ->
            val item = drawn.spec
            // Expanded exactly as the canvas expands it. Quoting the configured
            // range instead would print a ceiling the plot is not using the
            // moment anything exceeds it -- and this is the only place a
            // self-scaled series' numbers appear at all, so the caption *is* its
            // axis.
            val scale = item.scale?.expandedFor(drawn.points)
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
            LegendRow(
                key = item.label,
                caption = caption,
                color = item.color,
                kind = item.kind,
                dashed = item.dashed,
                drawn = true,
                onSeriesTap = onSeriesTap,
            )
        }

        // No unit quoted on a hidden row: an axis is worked out from the points
        // on the plot, and a series that is off has none there. Printing the
        // range it would have had means inventing a number.
        hidden.forEach { item ->
            LegendRow(
                key = item.label,
                caption = item.label,
                color = item.color,
                kind = item.kind,
                dashed = item.dashed,
                drawn = false,
                onSeriesTap = onSeriesTap,
            )
        }
    }
}

/**
 * One legend entry: a swatch, a caption, and -- where the chart is switchable --
 * the control for its own line.
 *
 * [key] is what a tap reports, and is the series' plain label rather than the
 * [caption] the reader sees. The caption carries the unit, which moves as the
 * axis selection does, and a control keyed on something that moves is a control
 * that stops working.
 */
@Composable
private fun LegendRow(
    key: String,
    caption: String,
    color: Color,
    kind: SeriesKind,
    dashed: Boolean,
    drawn: Boolean,
    onSeriesTap: ((String) -> Unit)?,
) {
    val ink = if (drawn) color else color.copy(alpha = OFF_ALPHA)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            if (onSeriesTap == null) Modifier
            else
                // Toggleable rather than merely clickable: it says what the row
                // does and which way it is currently set, which is what a screen
                // reader has to be told and is also the only handle a test has on
                // a row of plain text. The padding is the tap target -- legend
                // text on its own is a couple of millimetres tall.
                Modifier.toggleable(
                        value = drawn,
                        role = Role.Switch,
                        onValueChange = { onSeriesTap(key) },
                    )
                    .padding(vertical = 3.dp),
    ) {
        // A short stroke rather than a dot, so a dashed projection is
        // identifiable in the legend and not just on the plot. A bar series gets
        // a block instead, at the same wash it is drawn in.
        Canvas(modifier = Modifier.width(14.dp).height(8.dp)) {
            if (kind == SeriesKind.BAR) {
                drawRect(color = ink.copy(alpha = ink.alpha * BAR_ALPHA))
            } else {
                drawLine(
                    color = ink,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect =
                        if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f) else null,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (drawn) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * Widens the axis if any of its series fall outside the configured bounds.
 *
 * Series carrying a [ChartSeries.scale] of their own are excluded: they are not
 * drawn against this axis, so letting a 180 bpm trace stretch the glucose scale
 * would flatten the very line the axis exists to label.
 */
private fun AxisSpec.expandedFor(series: List<DrawnSeries>, axis: ChartAxis): AxisSpec =
    expandedFor(
        series.filter { it.spec.scale == null && it.spec.axis == axis }.flatMap { it.points }
    )

private fun AxisSpec.expandedFor(points: List<TimePoint>): AxisSpec {
    val values = points.map { it.value }
    if (values.isEmpty()) return this
    val lo = minOf(min, values.min())
    val hi = maxOf(max, values.max())
    return copy(min = lo, max = if (hi > lo) hi else lo + 1f)
}

private fun List<TimePoint>.inWindow(start: Instant, end: Instant): List<TimePoint> =
    filter { !it.time.isBefore(start) && !it.time.isAfter(end) }.sortedBy { it.time }

/** How many points have to be merged into one before the plot can draw them all. */
private fun List<TimePoint>.downsampleFactor(): Int =
    if (size <= MAX_RENDERED_POINTS) 1
    else ceil(size.toDouble() / MAX_RENDERED_POINTS).toInt()

private fun List<TimePoint>.downsampled(bucket: Int): List<TimePoint> {
    if (bucket <= 1) return this
    return chunked(bucket) { chunk ->
        TimePoint(
            time = chunk[chunk.size / 2].time,
            value = chunk.map { it.value }.average().toFloat(),
        )
    }
}

/**
 * A last-resort bar width for a series that did not declare one.
 *
 * The most common gap rather than the mean, so a series that is merely missing
 * an interval or two still yields the interval it was sampled at instead of an
 * average pulled wide by the hole.
 */
private fun List<TimePoint>.inferredBarWidth(): Duration {
    val gaps =
        zipWithNext { earlier, later ->
            Duration.between(earlier.time, later.time).toMillis()
        }
            .filter { it > 0 }
    if (gaps.isEmpty()) return Duration.ofHours(1)
    return Duration.ofMillis(gaps.groupingBy { it }.eachCount().maxBy { it.value }.key)
}

/**
 * A grey wash between two values on one axis.
 *
 * Translucent rather than a flat grey so the gridlines stay visible through it
 * -- the band says which region is wanted, and still has to be read against the
 * numbers down the side.
 */
private fun DrawScope.drawBand(
    range: ClosedFloatingPointRange<Float>,
    color: Color,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    yFor: (Float) -> Float,
) {
    if (range.endInclusive <= range.start) return
    val high = yFor(range.endInclusive).coerceIn(top, bottom)
    val low = yFor(range.start).coerceIn(top, bottom)
    if (low - high <= 0f) return
    drawRect(
        color = color.copy(alpha = BAND_ALPHA),
        topLeft = androidx.compose.ui.geometry.Offset(left, high),
        size = androidx.compose.ui.geometry.Size(right - left, low - high),
    )
}

private fun DrawScope.drawChart(
    series: List<DrawnSeries>,
    leftAxis: AxisSpec,
    rightAxis: AxisSpec?,
    windowStart: Instant,
    windowEnd: Instant,
    textMeasurer: TextMeasurer,
    gridColor: Color,
    axisTextColor: Color,
    zoneId: ZoneId,
    markers: List<ChartMarker> = emptyList(),
    verticalGridlines: Boolean = false,
    inspected: Instant? = null,
) {
    val leftGutter = LEFT_GUTTER.toPx()
    val rightGutter =
        (if (rightAxis != null) RIGHT_GUTTER_LABELLED else RIGHT_GUTTER_BARE).toPx()
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

    // Target bands, underneath everything: they are the backdrop a trace is read
    // against, and drawn over the gridlines they would hide them.
    leftAxis.band?.let {
        drawBand(it, axisTextColor, plotLeft, plotRight, plotTop, plotBottom) { v ->
            yFor(v, leftAxis)
        }
    }
    rightAxis?.band?.let {
        drawBand(it, axisTextColor, plotLeft, plotRight, plotTop, plotBottom) { v ->
            yFor(v, rightAxis)
        }
    }

    // Horizontal gridlines and axis labels.
    //
    // The row count follows the height available rather than being fixed: the
    // dashboard drops its charts to a stub when there is nothing logged, and five
    // rows of labels in 72dp overprint into an unreadable stack. Two gaps is the
    // floor, which still gives a min, a max and a midpoint.
    val rows = (plotHeight / MIN_ROW_SPACING.toPx()).toInt().coerceIn(2, 4)
    // Tinted per axis where the axis names a colour, so the numbers down the
    // side say which line they belong to. The gutter is the one place a reader
    // has to work out what a figure measures, and on a plot carrying five units
    // the label alone does not settle it.
    val leftLabelStyle = leftAxis.color?.let { labelStyle.copy(color = it) } ?: labelStyle
    val rightLabelStyle = rightAxis?.color?.let { labelStyle.copy(color = it) } ?: labelStyle
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
        val laid = textMeasurer.measure(leftAxis.format(value), leftLabelStyle)
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
            val rightLaid = textMeasurer.measure(rightAxis.format(rightValue), rightLabelStyle)
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

    // Vertical rules on the clock, where asked for.
    //
    // Deliberately not the same instants as the tick labels below: those step
    // back from now and so land wherever the window happens to end, which is
    // fine for saying roughly when, and useless for the thing these are for --
    // reading one hour against another. A rule at 2:47 does not answer "how much
    // of that rise was in the hour after eating".
    if (verticalGridlines) {
        val interval =
            TimeGridlines.intervalHours(
                windowHours = Duration.between(windowStart, windowEnd).toHours(),
                plotWidthPx = plotWidth,
                minSpacingPx = MIN_GRIDLINE_SPACING.toPx(),
            )
        for (at in TimeGridlines.times(windowStart, windowEnd, zoneId, interval)) {
            val x = xFor(at)
            if (x < plotLeft || x > plotRight) continue
            drawLine(
                color = gridColor,
                start = androidx.compose.ui.geometry.Offset(x, plotTop),
                end = androidx.compose.ui.geometry.Offset(x, plotBottom),
                strokeWidth = 1f,
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
            // Ticks step back from *now*, so they rarely land on a round hour.
            // Everything wider than this rounds the label to the hour and accepts
            // being up to half an hour out, which no reading is read that finely
            // against -- but across three hours it is a tenth of the plot, so the
            // shortest window is the one that has to print minutes.
            totalHours <= 4 -> 1L to DateTimeFormatter.ofPattern("h:mm a")
            totalHours <= 12 -> 3L to DateTimeFormatter.ofPattern("h a")
            totalHours <= 36 -> 6L to DateTimeFormatter.ofPattern("h a")
            // A weekday name per day up to a week and a bit: eight of them still
            // fit across a phone, and a week thinned to every third day would
            // label three columns out of seven.
            totalHours <= 24 * 8 -> 24L to DateTimeFormatter.ofPattern("EEE")
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

    // Reference rules, drawn under the data.
    leftAxis.rules.forEach { drawRule(yFor(it.value, leftAxis), plotLeft, plotRight, it.dashed) }
    rightAxis?.rules?.forEach { drawRule(yFor(it.value, rightAxis), plotLeft, plotRight, it.dashed) }

    // Bars before rules and lines. A column is a block of ink the width of a
    // whole hour; anything drawn under one is simply gone.
    for (drawn in series.filter { it.spec.kind == SeriesKind.BAR }) {
        val item = drawn.spec
        val inWindow = drawn.points
        if (inWindow.isEmpty()) continue
        val axis =
            item.scale?.expandedFor(inWindow)
                ?: if (item.axis == ChartAxis.LEFT) leftAxis else rightAxis ?: leftAxis

        // Summed rather than averaged when there are too many to draw: these are
        // counts accumulated over an interval, so merging two hours means adding
        // them. Averaging would halve a fortnight of steps.
        val factor = inWindow.downsampleFactor()
        val bars =
            if (factor == 1) inWindow
            else
                inWindow.chunked(factor) { chunk ->
                    TimePoint(chunk.first().time, chunk.sumOf { it.value.toDouble() }.toFloat())
                }

        val spanMillis = (item.barWidth ?: inWindow.inferredBarWidth()).toMillis() * factor
        val baseline = yFor(axis.min, axis)
        for (bar in bars) {
            if (bar.value <= 0f) continue
            val left = xFor(bar.time).coerceIn(plotLeft, plotRight)
            val right =
                xFor(bar.time.plusMillis(spanMillis)).coerceIn(plotLeft, plotRight)
            // Hairline floor: an hour with a handful of steps still has to be
            // visible as something other than an empty slot.
            val width = (right - left - 1f).coerceAtLeast(1f)
            val top = yFor(bar.value, axis).coerceIn(plotTop, baseline)
            drawRect(
                color = item.color.copy(alpha = BAR_ALPHA),
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(width, (baseline - top).coerceAtLeast(1f)),
            )
        }
    }

    // Rightmost edge of the last caption drawn, so a later one that would collide
    // with it can be dropped. Meals cluster -- three in an evening is normal --
    // and over a 48-hour window their captions otherwise overprint into a smear.
    // The rules themselves are always drawn; only the text is rationed.
    var lastLabelEnd = Float.NEGATIVE_INFINITY

    for (marker in markers.sortedBy { it.time }) {
        val x = xFor(marker.time)
        if (x !in plotLeft..plotRight) continue
        drawLine(
            // Subdued rules take the gridline grey, which is what stops a
            // reference line from being read as a reading.
            color = if (marker.subdued) gridColor else axisTextColor,
            start = androidx.compose.ui.geometry.Offset(x, plotTop),
            end = androidx.compose.ui.geometry.Offset(x, plotBottom),
            // A solid rule reads as a real instant; the dash says "projected".
            strokeWidth =
                when {
                    marker.subdued -> 1f
                    marker.dashed -> 1.dp.toPx()
                    else -> 1.5.dp.toPx()
                },
            pathEffect =
                if (marker.dashed || marker.subdued) {
                    PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                } else null,
        )

        val caption = marker.label ?: continue
        val laid = textMeasurer.measure(caption, labelStyle)
        // Centred on the rule, but kept inside the plot -- a marker near the left
        // edge would otherwise slide over the axis numbers, which sit in the
        // gutter at the same height.
        val left =
            (x - laid.size.width / 2f)
                .coerceIn(plotLeft, (size.width - laid.size.width).coerceAtLeast(plotLeft))
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

    for (drawn in series.filter { it.spec.kind == SeriesKind.LINE }) {
        val item = drawn.spec
        if (drawn.points.isEmpty()) continue
        val axis =
            item.scale?.expandedFor(drawn.points)
                ?: if (item.axis == ChartAxis.LEFT) leftAxis else rightAxis ?: leftAxis

        // Split before thinning, so the split is judged on the cadence the series
        // was actually recorded at. Downsampling first would widen every spacing
        // by the same factor and leave a real dropout looking ordinary.
        //
        // The thinning factor still comes from the whole series rather than each
        // run, or a trace broken into many runs would be drawn at many different
        // resolutions.
        val bucket = drawn.points.downsampleFactor()
        val runs =
            if (item.breakOnGaps) SeriesGaps.segments(drawn.points) else listOf(drawn.points)

        for (run in runs) {
            val points = run.downsampled(bucket)
            if (points.isEmpty()) continue

            // A run of one is an isolated reading with nothing to join it to.
            // Drawn as a dot rather than dropped: it was still measured.
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
                            androidx.compose.ui.geometry.Offset(
                                xFor(it.time),
                                yFor(it.value, axis),
                            ),
                    )
                }
            }
        }
    }

    // The crosshair, last, so no bar or curve is drawn over the one line the
    // reader put there themselves.
    //
    // Solid and in the label grey: heavier than the meal rules, which are dashed
    // gridline grey and mark context, and lighter than any series, which are 2dp
    // and coloured. Nothing is drawn *on* the lines it is asking about -- a ring
    // at each matched point would be fresh ink on the plot in the data's own
    // colours, which is exactly how the meal markers came to be read as a
    // carbohydrate spike. The readout under the chart says which value belongs
    // to which line, in words.
    if (inspected != null) {
        val x = xFor(inspected)
        if (x in plotLeft..plotRight) {
            drawLine(
                color = axisTextColor,
                start = androidx.compose.ui.geometry.Offset(x, plotTop),
                end = androidx.compose.ui.geometry.Offset(x, plotBottom),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}

private fun DrawScope.drawRule(y: Float, left: Float, right: Float, dashed: Boolean) {
    drawLine(
        color = Color(0xFFA30000),
        start = androidx.compose.ui.geometry.Offset(left, y),
        end = androidx.compose.ui.geometry.Offset(right, y),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f) else null,
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
