package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.FoodLogConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five levels, and the two ways a stored score can be nothing.
 *
 * Most of what is worth pinning here is the *scale itself*, because it is stored
 * as a bare integer and read back months later out of a CSV. If the scores ever
 * stop being 1 through 5 in order, every day already rated silently changes
 * meaning -- a 4 that used to be "mostly weighed" becomes something else, on
 * rows nobody is going to re-rate, and no screen in the app would look wrong.
 */
class FoodLogConfidenceTest {

    @Test
    fun `the scale is one to five, in order, with no gaps`() {
        assertEquals(listOf(1, 2, 3, 4, 5), FoodLogConfidence.entries.map { it.score })
    }

    @Test
    fun `a higher score is a better-logged day`() {
        // The direction is the whole basis of "throw out anything below a 3", and
        // it is the sort of thing that reads as obvious right up until somebody
        // orders the enum the other way for a nicer-looking chip row.
        assertEquals(1, FoodLogConfidence.BARELY.score)
        assertEquals(5, FoodLogConfidence.WEIGHED.score)
    }

    @Test
    fun `every level says what it means in words`() {
        // The number survives into a spreadsheet; the words are what make it
        // legible there and at the moment of rating. A blank one would leave a
        // chip the reader has to guess the meaning of.
        FoodLogConfidence.entries.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue(it.meaning.isNotBlank())
        }
    }

    @Test
    fun `an unrated day has no level`() {
        assertNull(FoodLogConfidence.of(null))
    }

    @Test
    fun `a score outside the scale has no level either`() {
        // Only reachable from a hand-edited database, and answered the same way
        // as unrated on purpose: both mean "nothing to show", and the reader has
        // no use for the difference. What matters is that neither throws and
        // neither guesses at a nearest level.
        assertNull(FoodLogConfidence.of(0))
        assertNull(FoodLogConfidence.of(6))
        assertNull(FoodLogConfidence.of(-1))
    }

    @Test
    fun `a level round-trips through its stored score`() {
        FoodLogConfidence.entries.forEach { assertEquals(it, FoodLogConfidence.of(it.score)) }
    }
}
