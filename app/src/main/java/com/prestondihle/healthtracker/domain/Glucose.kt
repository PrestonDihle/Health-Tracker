package com.prestondihle.healthtracker.domain

/**
 * The scale blood sugar is entered and plotted on.
 *
 * Milligrams per decilitre, which is what the meter and the continuous monitor
 * in use both report. Gathered here for the same reason [Ketones] is: the entry
 * stepper, the Wellness chart, the master graph and the target-range setting
 * all have to mean the same thing by this axis, and four copies of the numbers
 * drift.
 */
object Glucose {

    const val UNIT = "mg/dL"

    /** Manual fingerstick entry. Wide enough to cover a meter's whole reportable span. */
    val ENTRY_RANGE = 20..500

    /**
     * Plotted range, before settings.
     *
     * 60 to 180 is where a non-diabetic trace actually lives, and the top matters
     * more than it looks: a ceiling of 200 spends a fifth of the plot on values
     * that are never reached, which flattens the 30 mg/dL swing around a meal
     * into something that has to be squinted at. Both charts widen the axis to
     * fit an outlier, so a 210 reading still plots -- it is simply not budgeted
     * for in advance.
     *
     * These are now the seed for a setting rather than the figures themselves,
     * because how much of the plot the ordinary range deserves depends on whose
     * blood sugar it is. See [plotRange].
     */
    const val PLOT_MIN = 60f
    const val PLOT_MAX = 180f

    /**
     * Least distance the plot floor and ceiling may be apart.
     *
     * Not zero and not one: an axis spanning a couple of mg/dL turns sensor
     * noise into a mountain range, which is a misreading rather than a close-up.
     */
    const val MIN_PLOT_SPAN = 20

    /**
     * The configured plot bounds, or the seeded ones where they are unset or
     * make no sense.
     *
     * Both charts read their axis through here so that a floor above its own
     * ceiling -- reachable only by a caller, since the settings steppers hold
     * them apart -- degrades to the default range instead of to a plot that
     * draws every reading at the same height, or upside down.
     */
    fun plotRange(min: Int?, max: Int?): ClosedFloatingPointRange<Float> {
        val low = min?.toFloat() ?: PLOT_MIN
        val high = max?.toFloat() ?: PLOT_MAX
        return if (high - low >= MIN_PLOT_SPAN) low..high else PLOT_MIN..PLOT_MAX
    }

    /** Seeds the target band on first run; the real values come from settings. */
    const val DEFAULT_TARGET_LOW = 70
    const val DEFAULT_TARGET_HIGH = 140

    /** Where the solid reference rule sits until the reader moves it. */
    const val DEFAULT_REFERENCE = 100
}
