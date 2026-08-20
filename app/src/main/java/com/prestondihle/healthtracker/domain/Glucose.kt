package com.prestondihle.healthtracker.domain

/**
 * The scale blood sugar is entered and plotted on.
 *
 * Milligrams per decilitre, which is what the meter and the continuous monitor
 * in use both report. Gathered here for the same reason [Ketones] is: the entry
 * stepper, the dashboard chart, the master graph and the target-range setting
 * all have to mean the same thing by this axis, and four copies of the numbers
 * drift.
 */
object Glucose {

    const val UNIT = "mg/dL"

    /** Manual fingerstick entry. Wide enough to cover a meter's whole reportable span. */
    val ENTRY_RANGE = 20..500

    /**
     * Plotted range.
     *
     * 60 to 180 is where a non-diabetic trace actually lives, and the top matters
     * more than it looks: a ceiling of 200 spends a fifth of the plot on values
     * that are never reached, which flattens the 30 mg/dL swing around a meal
     * into something that has to be squinted at. Both charts widen the axis to
     * fit an outlier, so a 210 reading still plots -- it is simply not budgeted
     * for in advance.
     */
    const val PLOT_MIN = 60f
    const val PLOT_MAX = 180f

    /** Seeds the target band on first run; the real values come from settings. */
    const val DEFAULT_TARGET_LOW = 70
    const val DEFAULT_TARGET_HIGH = 140
}
