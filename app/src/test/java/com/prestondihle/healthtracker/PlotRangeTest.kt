package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.Glucose
import com.prestondihle.healthtracker.domain.HeartRate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the two configurable axes do with the figures they are handed.
 *
 * Both own a `plotRange` with the same contract and neither had a test. The
 * arithmetic is trivial; what is worth pinning is the **refusal**, because its
 * failure mode is silent. A floor dialled above its own ceiling is only
 * reachable from a caller -- the settings steppers hold the pair apart -- but if
 * one ever gets through, the honest outcome is the default range. The dishonest
 * ones are a plot drawing every reading at the same height and a plot drawing
 * them upside down, and both look like a chart rather than like a bug.
 */
class PlotRangeTest {

    @Test
    fun `configured bounds are used when they make sense`() {
        assertEquals(70f..130f, Glucose.plotRange(70, 130))
        assertEquals(50f..160f, HeartRate.plotRange(50, 160))
    }

    @Test
    fun `an unset bound falls back to the seeded one`() {
        assertEquals(Glucose.PLOT_MIN..Glucose.PLOT_MAX, Glucose.plotRange(null, null))
        assertEquals(HeartRate.PLOT_MIN..HeartRate.PLOT_MAX, HeartRate.plotRange(null, null))
    }

    @Test
    fun `an inverted range degrades to the default rather than drawing upside down`() {
        assertEquals(Glucose.PLOT_MIN..Glucose.PLOT_MAX, Glucose.plotRange(180, 60))
        assertEquals(HeartRate.PLOT_MIN..HeartRate.PLOT_MAX, HeartRate.plotRange(180, 40))
    }

    @Test
    fun `a range narrower than the minimum span degrades to the default`() {
        // One unit apart. Left alone this turns sensor noise into a mountain
        // range, which reads as a close-up rather than as the misreading it is.
        assertEquals(Glucose.PLOT_MIN..Glucose.PLOT_MAX, Glucose.plotRange(100, 101))
        assertEquals(HeartRate.PLOT_MIN..HeartRate.PLOT_MAX, HeartRate.plotRange(100, 101))
    }

    @Test
    fun `a range exactly at the minimum span is allowed`() {
        // The guard is `>=`, so the narrowest legal axis is a real answer rather
        // than one unit short of being refused.
        assertEquals(
            100f..(100f + Glucose.MIN_PLOT_SPAN),
            Glucose.plotRange(100, 100 + Glucose.MIN_PLOT_SPAN),
        )
        assertEquals(
            100f..(100f + HeartRate.MIN_PLOT_SPAN),
            HeartRate.plotRange(100, 100 + HeartRate.MIN_PLOT_SPAN),
        )
    }

    /**
     * The heart-rate seeds are the figures the axis was hard-coded at, and this
     * is the assertion that stops them drifting.
     *
     * `MIGRATION_22_23` seeds 40 and 180 into an upgrading reader's row, and
     * `MigrationSchemaTest` checks the database end of that. This checks the
     * constant end. If the two are ever changed apart, a reader who has never
     * touched the setting gets one scale and a reader who has gets another, from
     * what is supposed to be the same default -- and nothing on either chart
     * would say so.
     */
    @Test
    fun `the heart rate defaults are the figures the axis was fixed at`() {
        assertEquals(40f, HeartRate.PLOT_MIN, 0f)
        assertEquals(180f, HeartRate.PLOT_MAX, 0f)
    }
}
