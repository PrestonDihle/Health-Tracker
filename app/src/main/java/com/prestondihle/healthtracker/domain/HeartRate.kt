package com.prestondihle.healthtracker.domain

/**
 * The scale heart rate is plotted on.
 *
 * Beats per minute, which is what every source here reports. Gathered for
 * [Glucose]'s reason: the master graph's axis and the settings steppers have to
 * mean the same thing by this scale, and two copies of the numbers drift.
 *
 * The parallel with [Glucose] is deliberate and goes as far as it honestly can,
 * but **not to a target band**. Glucose has one because it is drawn on charts
 * carrying a single series; heart rate's only plot is the master graph, which
 * removed its own band for a reason that would recur here exactly -- a band
 * backs one series, that plot carries eight, and shaded behind carbohydrate
 * curves and step columns it stops reading as a target and starts reading as a
 * region of the chart. The reference rule carries the same information at a
 * weight that cannot be misread.
 */
object HeartRate {

    const val UNIT = "bpm"

    /** What a stepper may be dialled to. Wide enough for a resting 35 and a maximal 210. */
    val ENTRY_RANGE = 30..220

    /**
     * Plotted range, before settings.
     *
     * **These are exactly the figures the master graph's heart-rate axis was
     * hard-coded at** before it became a setting, which is the whole discipline
     * of turning a constant into one: an upgrading reader's chart must look
     * identical until they change something themselves. 40 clears the lowest
     * resting rate the watch reports and 180 clears all but a maximal effort.
     */
    const val PLOT_MIN = 40f
    const val PLOT_MAX = 180f

    /**
     * Least distance the floor and ceiling may be apart.
     *
     * Wider than [Glucose.MIN_PLOT_SPAN] because the quantity moves further: a
     * heart rate covers 50 bpm between sitting and a brisk walk, where the same
     * span of blood sugar is a whole day's excursion. An axis narrower than this
     * turns an ordinary walk into a wall.
     */
    const val MIN_PLOT_SPAN = 30

    /**
     * The configured plot bounds, or the seeded ones where they are unset or make
     * no sense.
     *
     * [Glucose.plotRange]'s contract, and it exists for the same reason: a floor
     * dialled above its own ceiling -- reachable only by a caller, since the
     * settings steppers hold them apart -- must degrade to the default range
     * rather than to a plot drawing every reading at one height, or upside down.
     */
    fun plotRange(min: Int?, max: Int?): ClosedFloatingPointRange<Float> {
        val low = min?.toFloat() ?: PLOT_MIN
        val high = max?.toFloat() ?: PLOT_MAX
        return if (high - low >= MIN_PLOT_SPAN) low..high else PLOT_MIN..PLOT_MAX
    }
}
