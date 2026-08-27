package com.prestondihle.healthtracker.domain

/**
 * The scale ketone readings are entered and plotted on.
 *
 * Breath acetone in parts per million, which is what the meter in use reports.
 * Not interchangeable with the blood mmol/L figure quoted in most ketosis
 * guidance: that measures beta-hydroxybutyrate in blood, this measures acetone
 * in breath, and the relationship between them is loose and individual. Anything
 * quoting one against the other is comparing two different measurements.
 *
 * Gathered here so the entry stepper, the Wellness chart and the master graph
 * cannot drift apart on what the axis means.
 */
object Ketones {

    const val UNIT = "ppm"

    /**
     * Entry range.
     *
     * Consumer breath meters top out around 100 ppm and read in whole units, so
     * a 1 ppm step covers the resolution they actually offer without making the
     * stepper tedious over a wide range.
     */
    val ENTRY_RANGE = 0f..100f
    const val ENTRY_STEP = 1f
    const val DEFAULT_ENTRY = 10f

    /**
     * Plotted range.
     *
     * Narrower than the entry range: readings sit well under 40 ppm nearly all
     * the time, and scaling the axis to a 100 ppm ceiling that is never reached
     * would flatten every real reading into the bottom third. Both charts expand
     * their axis to fit the data, so a higher reading still plots -- it just is
     * not budgeted for up front.
     */
    const val PLOT_MIN = 0f
    const val PLOT_MAX = 40f

    /** Whole ppm, since that is the resolution the meters report. */
    fun format(ppm: Float): String = "%.0f".format(ppm)
}
