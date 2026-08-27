package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.BodyComposition
import com.prestondihle.healthtracker.domain.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The waist-to-height screen, and the two rules that decide real pass/fails.
 *
 * The arithmetic is one division. Everything that can go wrong here is in how
 * the two measurements are recorded before it and how the result is compared
 * after it, so that is what this pins.
 */
class BodyCompositionTest {

    private fun inches(value: Double) = Units.inchesToCm(value.toFloat())

    @Test
    fun `measurements are floored to the half inch, not rounded to it`() {
        // Down, always. 42.75 records as 42.5 rather than as 43.
        assertEquals(42.5, BodyComposition.recordedInches(inches(42.75)), 0.001)
        assertEquals(42.5, BodyComposition.recordedInches(inches(42.5)), 0.001)
        assertEquals(42.0, BodyComposition.recordedInches(inches(42.25)), 0.001)
        assertEquals(42.0, BodyComposition.recordedInches(inches(42.0)), 0.001)
        // And a value between halves floors rather than snapping to the nearer
        // one. 75.4 is 75.0, not 75.5 -- the mistake a quarter-inch snap makes.
        assertEquals(75.0, BodyComposition.recordedInches(inches(75.4)), 0.001)
        assertEquals(75.0, BodyComposition.recordedInches(inches(75.49)), 0.001)
    }

    /**
     * The float trap, which costs half an inch and can flip a verdict.
     *
     * A waist entered as an exact 42.5 inches is stored as 107.95 cm and comes
     * back out of `Float` as 42.49999. Flooring that to the half gives 42.0 --
     * half an inch the reader never lost, in the direction that flatters them.
     */
    @Test
    fun `a value that round-trips slightly under still records at its own half inch`() {
        val stored = 107.94999f // exactly 42.5 inches, as Float stores it
        assertEquals(42.5, BodyComposition.recordedInches(stored), 0.001)
    }

    @Test
    fun `the ratio divides the two recorded measurements`() {
        // 42.5 over 75.0 is 0.5667.
        val ratio = BodyComposition.ratio(inches(42.5), inches(75.0))!!
        assertEquals(0.5667, ratio, 0.0005)
    }

    /**
     * The limit is strictly less than 0.55, and exactly 0.55 is over it.
     *
     * A `<=` here would pass somebody the standard fails, on the one value most
     * likely to be tested by hand.
     */
    @Test
    fun `exactly the limit does not pass`() {
        assertFalse(BodyComposition.passes(0.55))
        assertTrue(BodyComposition.passes(0.5499))
        assertFalse(BodyComposition.passes(0.5501))
        // 33 over 60 is exactly 0.55.
        val exact = BodyComposition.ratio(inches(33.0), inches(60.0))!!
        assertEquals(0.55, exact, 0.0001)
        assertFalse(BodyComposition.passes(exact))
    }

    /**
     * Flooring is applied to both measurements and pulls the ratio both ways.
     *
     * Worth pinning separately because the net effect depends on where the two
     * fractions fall, so a single example proves nothing about either. Flooring
     * the waist shrinks the numerator and helps; flooring the height shrinks the
     * denominator and hurts. Applying it to only one -- the easy mistake, since
     * the waist is the measurement people think of as needing rounding -- would
     * quietly move real verdicts.
     */
    @Test
    fun `flooring the waist helps and flooring the height hurts`() {
        // Waist floors from 42.75 to 42.5 against an exact height.
        val waistFloored = BodyComposition.ratio(inches(42.75), inches(75.0))!!
        assertEquals(42.5 / 75.0, waistFloored, 0.0005)
        assertTrue("flooring the waist should lower the ratio", waistFloored < 42.75 / 75.0)

        // Height floors from 75.4 to 75.0 against an exact waist.
        val heightFloored = BodyComposition.ratio(inches(42.0), inches(75.4))!!
        assertEquals(42.0 / 75.0, heightFloored, 0.0005)
        assertTrue("flooring the height should raise the ratio", heightFloored > 42.0 / 75.4)
    }

    @Test
    fun `an unmeasured waist or height has no ratio`() {
        assertNull(BodyComposition.ratio(null, inches(75.0)))
        assertNull(BodyComposition.ratio(inches(42.0), null))
        assertNull(BodyComposition.ratio(null, null))
        assertNull(BodyComposition.ratio(inches(42.0), 0f))
    }

    /**
     * The largest waist that still passes, which is what the chart rule marks.
     *
     * At 75 inches the threshold is 41.25, so 41.0 is the answer: 41.5 divides
     * to 0.5533 and fails. Quoting 41.25 would name a measurement the tape is
     * never read to.
     */
    @Test
    fun `the largest passing waist is a half inch the tape can actually show`() {
        assertEquals(41.0, BodyComposition.maxPassingWaistInches(inches(75.0))!!, 0.001)
        assertTrue(BodyComposition.passes(41.0 / 75.0))
        assertFalse(BodyComposition.passes(41.5 / 75.0))
    }

    @Test
    fun `a threshold landing exactly on a half inch steps down below it`() {
        // 60 inches gives a threshold of exactly 33.0, and 33.0 is not under the
        // limit -- so the last passing waist is 32.5.
        assertEquals(32.5, BodyComposition.maxPassingWaistInches(inches(60.0))!!, 0.001)
        assertFalse(BodyComposition.passes(33.0 / 60.0))
        assertTrue(BodyComposition.passes(32.5 / 60.0))
    }

    @Test
    fun `no height means no threshold to draw`() {
        assertNull(BodyComposition.maxPassingWaistInches(null))
        assertNull(BodyComposition.maxPassingWaistInches(0f))
    }

    /**
     * The standard needs neither age nor sex, and that is worth stating.
     *
     * Every other scored thing in this app reads off a profile column. This one
     * takes two tape measurements and nothing else, which is why it works on a
     * profile that has declined to give a sex.
     */
    @Test
    fun `the screen is the same for everybody`() {
        val ratio = BodyComposition.ratio(inches(38.0), inches(70.0))!!
        assertEquals(0.5429, ratio, 0.0005)
        assertTrue(BodyComposition.passes(ratio))
    }
}
